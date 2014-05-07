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
package com.github.gekoh.yagen.hst;

import com.github.gekoh.yagen.api.Constants;
import org.hibernate.annotations.Type;
import org.joda.time.DateTime;

import javax.persistence.Column;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.Id;
import javax.persistence.MappedSuperclass;
import java.util.UUID;

/**
 * @author Georg Kohlweiss
 */
@SuppressWarnings({"UnusedDeclaration", "FieldCanBeLocal"})
@MappedSuperclass
public abstract class BaseEntity {
    //private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(BaseEntity.class);

    /**
     * Technical unique key of the row.
     */
    @Id
    @Column(name = "HST_UUID", length = Constants.UUID_LEN)
    private String uuid;

    /**
    * operation on live data
    */
   @Enumerated(EnumType.STRING)
   @Column(name = "OPERATION", length = Constants.ENUM_DEFAULT_LEN, nullable = false)
   private Operation operation;

    /**
     * Timestamp which defines when the entity was committed
     * (currently we use the timestamp when the first entity of a transaction was changed,
     * but this timestamp then is used for all changed entities within the same transaction)
     */
    @Type(type = "com.csg.cs.aura.core.infra.hibernate.DateTimeType")
    @Column(name = "TRANSACTION_TIMESTAMP", nullable = false)
    private DateTime transactionTimestamp;

    /**
     * Entity was invalidated at.
     */
    @Type(type = "com.csg.cs.aura.core.infra.hibernate.DateTimeType")
    @Column(name = "INVALIDATED_AT", nullable = true)
    private DateTime invalidatedAt;

    protected BaseEntity() {
        this.uuid = UUID.randomUUID().toString().replaceAll("-", "").toUpperCase();
    }

    public BaseEntity(String liveUuid, Operation operation, DateTime transactionTimestamp, DateTime invalidatedAt) {
        this();
        setLiveUuid(liveUuid);
        this.operation = operation;
        this.transactionTimestamp = transactionTimestamp;
        this.invalidatedAt = invalidatedAt;
    }

    public String getUuid() {
        return uuid;
    }

    protected abstract void setLiveUuid(String liveUuid);

    public DateTime getInvalidatedAt() {
        return invalidatedAt;
    }

    public DateTime getTransactionTimestamp() {
        return transactionTimestamp;
    }

    public Operation getOperation() {
        return operation;
    }
}
