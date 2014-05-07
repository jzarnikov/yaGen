/*
 Copyright 2014 Georg Kohlweiss

 Licensed under the Apache License, Version 2.0 (the License);
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an AS IS BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
*/
package com.github.gekoh.yagen.ddl;

import com.github.gekoh.yagen.api.Auditable;
import com.github.gekoh.yagen.api.CascadeDelete;
import com.github.gekoh.yagen.api.CascadeNullable;
import com.github.gekoh.yagen.api.Default;
import com.github.gekoh.yagen.api.Deferrable;
import com.github.gekoh.yagen.api.I18NDetailEntityRelation;
import com.github.gekoh.yagen.api.IntervalPartitioning;
import com.github.gekoh.yagen.api.Profile;
import com.github.gekoh.yagen.api.Sequence;
import com.github.gekoh.yagen.api.TemporalEntity;
import com.github.gekoh.yagen.util.MappingUtils;
import org.apache.commons.lang.StringUtils;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import javax.persistence.AttributeOverride;
import javax.persistence.AttributeOverrides;
import javax.persistence.CollectionTable;
import javax.persistence.Column;
import javax.persistence.DiscriminatorValue;
import javax.persistence.Embedded;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.JoinTable;
import javax.persistence.ManyToMany;
import javax.persistence.ManyToOne;
import javax.persistence.MappedSuperclass;
import javax.persistence.OneToMany;
import javax.persistence.OneToOne;
import javax.persistence.PrimaryKeyJoinColumn;
import javax.persistence.Transient;
import java.lang.annotation.Annotation;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * @author Georg Kohlweiss 
 */
@SuppressWarnings({"unchecked"})
public class TableConfig {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(TableConfig.class);

    private static final Set<Class<? extends Annotation>> COLLECT_ANNOTATIONS = new HashSet(Arrays.asList(
            Profile.class,
            TemporalEntity.class,
            com.github.gekoh.yagen.api.Table.class,
            IntervalPartitioning.class,
            Auditable.class
    ));


    private String tableName;
    private Class baseClass;
    private CreateDDL ddlEnhancer;
    private boolean tableToBeRendered = true;

    private List<String> pkColnames = new ArrayList<String>();
    private Set<Annotation> annotations = new HashSet<Annotation>();
    private List<Sequence> sequences = new ArrayList<Sequence>();

    private Map<String, String> columnNameToEnumCheckConstraints = new HashMap<String, String>();
    private Map<String, Deferrable> columnNameToDeferrable = new HashMap<String, Deferrable>();
    private Set<String> columnNamesIsCascadeDelete = new HashSet<String>();
    private Set<String> columnNamesIsCascadeNullable = new HashSet<String>();
    private Map<String, String> colNameToDefault = new HashMap<String, String>();
    private String i18nBaseEntityFkCol;
    private String i18nBaseEntityTblName;

    public TableConfig(CreateDDL ddlEnhancer, Class baseClass, String tableName) {
        this.ddlEnhancer = ddlEnhancer;
        this.baseClass = baseClass;
        this.tableName = tableName.toLowerCase();
    }

    public void scanEntityClass(Class entityClass, boolean selectiveRendering) {
        Class annClass = entityClass;
        while (annClass != null) {
            for (Annotation annotation : annClass.getAnnotations()) {
                if (COLLECT_ANNOTATIONS.contains(annotation.annotationType()) && !annotations.contains(annotation)) {
                    putTableAnnotation(annotation);
                }
            }
            if (baseClass.equals(getClassOfTableAnnotation(annClass))) {
                annClass = annClass.getSuperclass();
            }
            else {
                break;
            }
        }

        com.github.gekoh.yagen.api.Table addTblInfo = (com.github.gekoh.yagen.api.Table) entityClass.getAnnotation(com.github.gekoh.yagen.api.Table.class);
        if (addTblInfo != null && addTblInfo.additionalSequences().length > 0) {
            sequences.addAll(Arrays.asList(addTblInfo.additionalSequences()));
        }

        processTypeAnnotations(entityClass, selectiveRendering);

        addI18NInfo(entityClass.getDeclaredFields());
        addI18NInfo(entityClass.getDeclaredMethods());

        gatherPkColumn(entityClass);
        gatherEnumCheckConstraints(entityClass);
        gatherCascade(entityClass);
        gatherDeferrable(entityClass);
    }

    public boolean isTableToBeRendered() {
        return tableToBeRendered;
    }

    public void setTableToBeRendered(boolean tableToBeRendered) {
        this.tableToBeRendered = tableToBeRendered;
    }

    public String getTableName() {
        return tableName;
    }

    public Class getEntityBaseClass() {
        return baseClass;
    }

    private void putTableAnnotation (Annotation annotation) {
        if (!COLLECT_ANNOTATIONS.contains(annotation.annotationType())) {
            throw new IllegalArgumentException("not chosen to collect annotations of type " + annotation.getClass());
        }

        annotations.add(annotation);
    }

    public <T extends Annotation> T getTableAnnotationOfType (Class<T> annotationType) {
        for (Annotation annotation: annotations) {
            if (annotationType.isAssignableFrom(annotation.getClass())) {
                return (T) annotation;
            }
        }

        return null;
    }

    public String getI18nBaseEntityFkCol() {
        return i18nBaseEntityFkCol;
    }

    public String getI18nBaseEntityTblName() {
        return i18nBaseEntityTblName;
    }

    public List<String> getPkColnames() {
        return pkColnames;
    }

    public Set<Annotation> getAnnotations() {
        return annotations;
    }

    public List<Sequence> getSequences() {
        return sequences;
    }

    public Map<String, String> getColumnNameToEnumCheckConstraints() {
        return columnNameToEnumCheckConstraints;
    }

    public Map<String, Deferrable> getColumnNameToDeferrable() {
        return columnNameToDeferrable;
    }

    public Set<String> getColumnNamesIsCascadeDelete() {
        return columnNamesIsCascadeDelete;
    }

    public Set<String> getColumnNamesIsCascadeNullable() {
        return columnNamesIsCascadeNullable;
    }

    public Map<String, String> getColNameToDefault() {
        return colNameToDefault;
    }

    private void gatherEnumCheckConstraints(Class entityClass) {
        gatherEnumCheckConstraints(new HashMap<String, String>(), "", entityClass);
    }

    private void gatherEnumCheckConstraints(final Map<String, String> attr2colName, final String attrPath, Class type) {
//        HACK: we are using field annotations, need to skip methods otherwise we would have wrong constraints
        traverseFieldsAndMethods(type, true, false, new GatherFieldOrMethodInfoAction() {
            @Override
            public void gatherInfo(AccessibleObject fOm) {
                String attributeName = getAttributeName(fOm);
                String attrPathField = attrPath + "." + attributeName;
                Class attributeType = getAttributeType(fOm);

                if (fOm.isAnnotationPresent(Embedded.class)) {
                    addAttributeOverrides(attr2colName, attrPathField, fOm);
                    gatherEnumCheckConstraints(attr2colName, attrPathField, attributeType);
                }
                else if (attributeType.isEnum()) {
                    String colName = attr2colName.get(attrPathField);
                    if (colName == null) {
                        if (fOm.isAnnotationPresent(Column.class)) {
                            colName = fOm.getAnnotation(Column.class).name().toLowerCase();
                        }

                        if (StringUtils.isEmpty(colName)) {
                            colName = attributeName.toLowerCase();
                        }
                    }
                    boolean useName = fOm.isAnnotationPresent(Enumerated.class) && fOm.getAnnotation(Enumerated.class).value() == EnumType.STRING;
                    StringBuilder cons = new StringBuilder();
                    for (Object e : attributeType.getEnumConstants()) {
                        if (cons.length() > 0) {
                            cons.append(", ");
                        }
                        if (useName) {
                            cons.append("'").append(((Enum)e).name()).append("'");
                        }
                        else {
                            cons.append(((Enum) e).ordinal());
                        }
                    }
                    columnNameToEnumCheckConstraints.put(colName, cons.toString());
                }
            }
        });

        Class superClass = getEntitySuperclass(type);
        if (superClass != null) {
            gatherEnumCheckConstraints(attr2colName, attrPath, superClass);
        }
    }

    private static Class getEntitySuperclass(Class entity) {
        // in case of joined table inheritance the superclass has its own table and we do not need
        // to care about attributes from superclasses
        if (entity.isAnnotationPresent(DiscriminatorValue.class)) {
            return null;
        }

        // in all other cases we also need to consider attributes/columns of superclasses as they
        // were found in this entity's table
        Class superClass = entity.getSuperclass();

        if (superClass != null &&
                (superClass.isAnnotationPresent(MappedSuperclass.class) || superClass.isAnnotationPresent(Entity.class))) {
            return superClass;
        }

        return null;
    }

    private void gatherCascade(Class entityClass) {
        do {
            gatherCascade(new HashMap<String, String>(), "", entityClass);
        } while ((entityClass = getEntitySuperclass(entityClass)) != null);
    }

    private void gatherCascade(final Map<String, String> attr2colName, final String attrPath, Class type) {
//        HACK: we are using field annotations, need to skip methods otherwise we would have wrong constraints
        traverseFieldsAndMethods(type, true, false, new GatherFieldOrMethodInfoAction() {
            @Override
            public void gatherInfo(AccessibleObject fOm) {
                String attributeName = getAttributeName(fOm);
                String attrPathField = attrPath + "." + attributeName;
                Class attributeType = getAttributeType(fOm);
                boolean onDeleteCascade = fOm.isAnnotationPresent(OnDelete.class) ? fOm.getAnnotation(OnDelete.class).action() == OnDeleteAction.CASCADE : false;

                if (fOm.isAnnotationPresent(Embedded.class)) {
                    addAttributeOverrides(attr2colName, attrPathField, fOm);
                    gatherCascade(attr2colName, attrPathField, attributeType);
                }

                if (fOm.isAnnotationPresent(CascadeNullable.class)) {
                    if (onDeleteCascade) {
                        throw new IllegalStateException("conflicting declaration of @CascadeNullable and CascadeType on relation " + fOm);
                    }
                    String colName = attr2colName.get(attrPathField);
                    if (colName == null) {
                        if (fOm.isAnnotationPresent(JoinColumn.class)) {
                            colName = fOm.getAnnotation(JoinColumn.class).name().toLowerCase();
                        }

                        if (StringUtils.isEmpty(colName)) {
                            colName = attributeName.toLowerCase();
                        }
                        columnNamesIsCascadeNullable.add(colName);
                    }
                }

                Set<String> fkCols = new HashSet<String>();
                String fkTableName = null;
                if (fOm.isAnnotationPresent(JoinTable.class)) {
                    JoinTable joinTable = fOm.getAnnotation(JoinTable.class);
                    fkCols.add(joinTable.joinColumns()[0].name().toLowerCase());
                    fkCols.add(joinTable.inverseJoinColumns()[0].name().toLowerCase());
                    fkTableName = joinTable.name();
                }
                else if (fOm.isAnnotationPresent(OneToMany.class)) {
                    JoinColumn joinColumn = getJoinColumn(fOm);
                    if (joinColumn != null) {
                        Class<?> targetEntityClass = MappingUtils.determineTargetEntity(fOm, fOm.getAnnotation(OneToMany.class).targetEntity());
                        fkTableName = getTableAnnotation(targetEntityClass).name();
                        fkCols.add(joinColumn.name().toLowerCase());
                    }
                }
                else if (fOm.isAnnotationPresent(ManyToOne.class) || fOm.isAnnotationPresent(OneToOne.class)) {
                    JoinColumn joinColumn = getJoinColumn(fOm);
                    if (joinColumn != null) {
                        fkTableName = tableName;
                        fkCols.add(joinColumn.name().toLowerCase());
                    }
                }

                if (fkTableName != null && (
                        onDeleteCascade || fOm.isAnnotationPresent(CascadeDelete.class
                        ))) {
                    TableConfig fkConfig = ddlEnhancer.getConfigForTableName(fkTableName.toLowerCase());
                    if (fkConfig != null) {
                        fkConfig.columnNamesIsCascadeDelete.addAll(fkCols);
                    }
                }
            }
        });
    }

    private void gatherDeferrable(Class entityClass) {
        gatherDeferrable(new HashMap<String, String>(), "", entityClass);
    }

    private void gatherDeferrable(final Map<String, String> attr2colName, final String attrPath, Class type) {
        traverseFieldsAndMethods(type, true, true, new GatherFieldOrMethodInfoAction() {
            @Override
            public void gatherInfo(AccessibleObject fOm) {
                String attributeName = getAttributeName(fOm);
                String attrPathField = attrPath + "." + attributeName;
                Class attributeType = getAttributeType(fOm);

                if (fOm.isAnnotationPresent(Embedded.class)) {
                    addAttributeOverrides(attr2colName, attrPathField, fOm);
                    gatherDeferrable(attr2colName, attrPathField, attributeType);
                } else if (fOm.isAnnotationPresent(Deferrable.class)) {
                    String colName = attr2colName.get(attrPathField);
                    if (colName == null) {
                        if (fOm.isAnnotationPresent(JoinColumn.class)) {
                            colName = fOm.getAnnotation(JoinColumn.class).name().toLowerCase();
                        }

                        if (StringUtils.isEmpty(colName)) {
                            colName = attributeName.toLowerCase();
                        }
                        columnNameToDeferrable.put(colName, fOm.getAnnotation(Deferrable.class));
                    }
                }
            }
        });
    }

    private static void traverseFieldsAndMethods (Class type, boolean fields, boolean methods, GatherFieldOrMethodInfoAction action) {
        List<AccessibleObject> fOms = new ArrayList<AccessibleObject>();
        if (fields) {
            fOms.addAll(Arrays.asList(type.getDeclaredFields()));
        }
        if (methods) {
            fOms.addAll(Arrays.asList(type.getDeclaredMethods()));
        }

        for (AccessibleObject fOm : fOms) {
            if (fOm.isAnnotationPresent(Transient.class)) {
                continue;
            }

            action.gatherInfo(fOm);
        }
    }

    private static interface GatherFieldOrMethodInfoAction {
        void gatherInfo(AccessibleObject fieldOrMethod);
    }

    private static String getAttributeName(AccessibleObject fom) {
        if (fom instanceof Field) {
            return ((Field)fom).getName();
        }
        else if (fom instanceof Method) {
            String name = ((Method)fom).getName().replaceAll("(set|get)", "");
            return name.substring(0, 1).toLowerCase() + name.substring(1);
        }
        return null;
    }

    private static Class getAttributeType(AccessibleObject fom) {
        if (fom instanceof Field) {
            return ((Field)fom).getType();
        }
        else if (fom instanceof Method) {
            return ((Method)fom).getReturnType();
        }
        return null;
    }

    private static void addAttributeOverrides(Map<String, String> attr2colName, String attrPath, AccessibleObject fom) {
        List<AttributeOverride> overrides = new ArrayList<AttributeOverride>();
        if (fom.isAnnotationPresent(AttributeOverride.class)) {
            overrides.add(fom.getAnnotation(AttributeOverride.class));
        }

        if (fom.isAnnotationPresent(AttributeOverrides.class)) {
            overrides.addAll(Arrays.asList(fom.getAnnotation(AttributeOverrides.class).value()));
        }

        for (AttributeOverride override : overrides) {
            String attr = attrPath+"."+override.name();

            if (!attr2colName.containsKey(attr)) {
                attr2colName.put(attr, override.column().name().toLowerCase());
            }
        }
    }

    private void gatherPkColumn(Class entityClass) {
        Set<AccessibleObject> fieldsOrMethods = getAnnotatedFieldOrMethod(new HashSet<AccessibleObject>(), Id.class, entityClass, true);

        if (entityClass.isAnnotationPresent(PrimaryKeyJoinColumn.class)) {
            pkColnames = Arrays.asList(((PrimaryKeyJoinColumn) entityClass.getAnnotation(PrimaryKeyJoinColumn.class)).name());
        }
        else if (fieldsOrMethods.size() > 0) {
            pkColnames = new ArrayList<String>();
            for (AccessibleObject fieldOrMethod : fieldsOrMethods) {
                String colName = null;
                if (fieldOrMethod.isAnnotationPresent(Column.class)) {
                    colName = fieldOrMethod.getAnnotation(Column.class).name();
                }
                if (colName == null && fieldOrMethod instanceof Field) {
                    colName = ((Field) fieldOrMethod).getName();
                }
                if (colName != null) {
                    pkColnames.add(colName);
                }
            }
        }
    }

    private static <T extends Annotation> Set<AccessibleObject> getAnnotatedFieldOrMethod(Set<AccessibleObject> fieldsOrMethods,
                                                                                          Class<T> annotationClass,
                                                                                          Class entityClass,
                                                                                          boolean withInheritance) {
        for (Field field : entityClass.getDeclaredFields()) {
            if (field.isAnnotationPresent(annotationClass)) {
                fieldsOrMethods.add(field);
            }
        }
        for (Method method : entityClass.getDeclaredMethods()) {
            if (method.isAnnotationPresent(annotationClass)) {
                fieldsOrMethods.add(method);
            }
        }
        if (entityClass.getSuperclass() != null && withInheritance) {
            return getAnnotatedFieldOrMethod(fieldsOrMethods, annotationClass, entityClass.getSuperclass(), withInheritance);
        }
        return fieldsOrMethods;
    }

    private void processTypeAnnotations(Class type, boolean selectiveRendering) {
        do {
            processAnnotations(type.getDeclaredFields(), selectiveRendering);
            processAnnotations(type.getDeclaredMethods(), selectiveRendering);
        } while ((type = getEntitySuperclass(type)) != null);
    }

    private void processAnnotations (AccessibleObject[] fieldsOrMethods, boolean selectiveRendering) {
        for (AccessibleObject fieldOrMethod: fieldsOrMethods) {
            JoinTable joinTable = fieldOrMethod.getAnnotation(JoinTable.class);
            TableConfig joinTableConfig = joinTable != null ? ddlEnhancer.getConfigForTableName(joinTable.name().toLowerCase()) : null;

            if (joinTable != null && joinTableConfig == null) {
                joinTableConfig = new TableConfig(ddlEnhancer, null, joinTable.name());
                ddlEnhancer.addTableConfig(joinTableConfig);
            }

            if (joinTable != null) {
                if (fieldOrMethod.isAnnotationPresent(IntervalPartitioning.class)) {
                    joinTableConfig.putTableAnnotation(fieldOrMethod.getAnnotation(IntervalPartitioning.class));
                }
                if (fieldOrMethod.isAnnotationPresent(Auditable.class)) {
                    joinTableConfig.putTableAnnotation(fieldOrMethod.getAnnotation(Auditable.class));
                }
            }
            else if (fieldOrMethod.isAnnotationPresent(CollectionTable.class)) {
                joinTableConfig = new TableConfig(ddlEnhancer, null, fieldOrMethod.getAnnotation(CollectionTable.class).name());
                ddlEnhancer.addTableConfig(joinTableConfig);
            }

            if (fieldOrMethod.getAnnotation(Profile.class) != null) {
                if (joinTable == null) {
                    throw new IllegalArgumentException("need @"+JoinTable.class.getSimpleName()+" for @"+Profile.class.getSimpleName()+" on a field");
                }
                Profile annotation = fieldOrMethod.getAnnotation(Profile.class);

                if (selectiveRendering && !Arrays.asList(annotation.value()).contains(ddlEnhancer.getProfile().getName())) {
                    joinTableConfig.setTableToBeRendered(false);
                }
            }
            if (fieldOrMethod.getAnnotation(TemporalEntity.class) != null) {
                if (joinTable == null) {
                    throw new IllegalArgumentException("need @"+JoinTable.class.getSimpleName()+" for @"+TemporalEntity.class.getSimpleName()+" on a field");
                }
                TemporalEntity annotation = fieldOrMethod.getAnnotation(TemporalEntity.class);

                if (annotation != null) {
                    joinTableConfig.putTableAnnotation(annotation);
                }
            }
            if (fieldOrMethod.getAnnotation(Sequence.class) != null) {
                sequences.add(fieldOrMethod.getAnnotation(Sequence.class));
            }
            else if (fieldOrMethod.getAnnotation(Embedded.class) != null) {
                Class embed;
                if (fieldOrMethod instanceof Field) {
                    Field field = (Field) fieldOrMethod;
                    embed = field.getType();
                }
                else if (fieldOrMethod instanceof Method) {
                    Method method = (Method) fieldOrMethod;
                    embed = method.getReturnType();
                }
                else {
                    throw new IllegalStateException("AccessibleObject not type of Field or Method");
                }
                processTypeAnnotations(embed, selectiveRendering);
            }
//            if we have a ManyToMany JoinTable and current entity is part of selective rendering,
//            we also need to render the JoinTable
            else if (fieldOrMethod.isAnnotationPresent(ManyToMany.class)) {
                if (joinTable == null) {
                    String propName = fieldOrMethod instanceof Field ? ((Field) fieldOrMethod).getName() : ((Method) fieldOrMethod).getName().substring(3);
                    joinTableConfig = new TableConfig(ddlEnhancer, null, ddlEnhancer.getProfile().getNamingStrategy().collectionTableName(null, getTableName(), null, null, propName));
                    ddlEnhancer.addTableConfig(joinTableConfig);
                }
                joinTableConfig.setTableToBeRendered(true);
            }

            if (fieldOrMethod.isAnnotationPresent(Default.class)) {
                String defaultValue = fieldOrMethod.getAnnotation(Default.class).sqlExpression();
                if (fieldOrMethod.isAnnotationPresent(Column.class)) {
                    colNameToDefault.put(fieldOrMethod.getAnnotation(Column.class).name().toLowerCase(), defaultValue);
                }
                else if (fieldOrMethod instanceof Field) {
                    colNameToDefault.put(ddlEnhancer.getProfile().getNamingStrategy().columnName(((Field) fieldOrMethod).getName()).toLowerCase(), defaultValue);
                }
                else {
                    LOG.warn(Default.class+" only supported on fields or @{} annotated methods", Column.class.toString());
                }
            }
        }
    }

    private static JoinColumn getJoinColumn(AccessibleObject fieldOrMethod) {
        if (fieldOrMethod.isAnnotationPresent(JoinColumn.class)) {
            return fieldOrMethod.getAnnotation(JoinColumn.class);
        }
        if (fieldOrMethod.isAnnotationPresent(OneToMany.class)) {
            OneToMany o2m = fieldOrMethod.getAnnotation(OneToMany.class);
            try {
                if (fieldOrMethod.getAnnotation(JoinColumn.class) != null) {
                    return fieldOrMethod.getAnnotation(JoinColumn.class);
                }
                else {
                    Class<?> targetEntityClass = MappingUtils.determineTargetEntity(fieldOrMethod, o2m.targetEntity());
                    return targetEntityClass.getDeclaredField(o2m.mappedBy()).getAnnotation(JoinColumn.class);
                }
            } catch (NoSuchFieldException e) {
                throw new IllegalStateException(e);
            }
        }
        return null;
    }

    private void addI18NInfo (AccessibleObject[] fieldsOrMethods) {
        for (AccessibleObject fieldOrMethod: fieldsOrMethods) {
            if (fieldOrMethod.getAnnotation(I18NDetailEntityRelation.class) != null) {
                JoinColumn joinColumn = getJoinColumn(fieldOrMethod);
                String entityTableName, detailTableName;
                if (fieldOrMethod.getAnnotation(OneToMany.class) != null) {
                    OneToMany o2m = fieldOrMethod.getAnnotation(OneToMany.class);
                    Class<?> targetEntityClass = MappingUtils.determineTargetEntity(fieldOrMethod, o2m.targetEntity());
                    detailTableName = getTableAnnotation(targetEntityClass).name();
                    entityTableName = getTableName();
                }
                else {
                    ManyToOne m2o = fieldOrMethod.getAnnotation(ManyToOne.class);
                    Class<?> targetEntityClass = MappingUtils.determineTargetEntity(fieldOrMethod, m2o.targetEntity());
                    entityTableName = getTableAnnotation(targetEntityClass).name();
                    detailTableName = getTableName();
                }

                TableConfig detailTableConfig = ddlEnhancer.getConfigForTableName(detailTableName.toLowerCase());

                if (joinColumn != null && detailTableConfig != null) {
                    detailTableConfig.i18nBaseEntityFkCol = joinColumn.name();
                    detailTableConfig.i18nBaseEntityTblName = entityTableName;
                }
            }
        }
    }

    private static javax.persistence.Table getTableAnnotation(Class type) {
        Class entityClass = getClassOfTableAnnotation(type);
        if (entityClass != null) {
            return (javax.persistence.Table) entityClass.getAnnotation(javax.persistence.Table.class);
        }
        return null;
    }

    public static Class getClassOfTableAnnotation(Class type) {
        do {
            if (type.isAnnotationPresent(javax.persistence.Table.class)) {
                return type;
            }
        } while ((type = type.getSuperclass()) != null);

        return null;
    }

}