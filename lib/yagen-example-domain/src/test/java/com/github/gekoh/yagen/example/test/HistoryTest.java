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
package com.github.gekoh.yagen.example.test;

import com.github.gekoh.yagen.example.Aircraft;
import com.github.gekoh.yagen.example.AircraftHst;
import com.github.gekoh.yagen.example.BoardBookEntry;
import com.github.gekoh.yagen.example.EngineType;
import com.github.gekoh.yagen.hst.Operation;
import org.joda.time.DateTime;
import org.junit.Assert;
import org.junit.Test;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Iterator;

/**
 * @author Georg Kohlweiss
 */
public class HistoryTest extends TestBase {
    //private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(HistoryTest.class);

    @Test
    public void testHistory() {

        Aircraft ac;
        try {
            ac = em.createQuery("select ac from Aircraft ac where ac.callSign=:callSign", Aircraft.class)
                    .setParameter("callSign", "OE-DVK")
                    .getSingleResult();
        } catch (Exception e) {
            ac = new Aircraft(EngineType.piston, "C172", "OE-DVK", 10.92f, 8.2f);
            em.getTransaction().begin();
            em.persist(ac);
            em.flush();
            em.getTransaction().commit();
        }

        AircraftHst ach = (AircraftHst) em.createQuery("from AircraftHst a where a.liveUuid=:uuid")
                .setParameter("uuid", ac.getUuid()).getSingleResult();

        Assert.assertEquals("wrong operation,", Operation.I, ach.getOperation());
        Assert.assertEquals("wrong engine type,", EngineType.piston, ach.getEngineType());
        Assert.assertEquals("wrong type,", "C172", ach.getType());
        Assert.assertEquals("wrong callsign,", "OE-DVK", ach.getCallSign());
    }

    /**
     * Unfortunately this does not work with ORACLE since we use ORACLE's rowid as identifier for transaction changes
     * whereas for HSQLDB we concatenate the primary key columns of collection tables.
     * So in ORACLE this will violate the UK constraint of the history table.
     */
    @Test
    public void testHistoryCollectionTableLimitation() {
        testHistory();

        em = emf.createEntityManager();
        em.getTransaction().begin();

        Aircraft ac = em.createQuery("select ac from Aircraft ac where ac.callSign=:callSign", Aircraft.class)
                .setParameter("callSign", "OE-DVK")
                .getSingleResult();

        BoardBookEntry boardBookEntry = new BoardBookEntry(1, "1+3", "LOAN", new DateTime().minusHours(1), "LOAN", new DateTime(), 3);
        ac.addBoardBookEntry(boardBookEntry);

        em.flush();

        String resource = "50l AVGAS";
        em.createNativeQuery("insert into OPERATING_RESOURCES (BOARD_BOOK_UUID, ADDED_OPERATING_RESOURCES) values (:bbeUuid, :resource)")
                .setParameter("bbeUuid", boardBookEntry.getUuid())
                .setParameter("resource", resource)
                .executeUpdate();

        em.flush();

        em.createNativeQuery("delete from OPERATING_RESOURCES where BOARD_BOOK_UUID=:bbeUuid and ADDED_OPERATING_RESOURCES=:resource")
                .setParameter("bbeUuid", boardBookEntry.getUuid())
                .setParameter("resource", resource)
                .executeUpdate();

        em.getTransaction().commit();
        em.getTransaction().begin();

        em.createNativeQuery("insert into OPERATING_RESOURCES (BOARD_BOOK_UUID, ADDED_OPERATING_RESOURCES) values (:bbeUuid, :resource)")
                .setParameter("bbeUuid", boardBookEntry.getUuid())
                .setParameter("resource", resource)
                .executeUpdate();

        em.getTransaction().commit();
        em.getTransaction().begin();

        em.createNativeQuery("delete from OPERATING_RESOURCES where BOARD_BOOK_UUID=:bbeUuid and ADDED_OPERATING_RESOURCES=:resource")
                .setParameter("bbeUuid", boardBookEntry.getUuid())
                .setParameter("resource", resource)
                .executeUpdate();

        em.flush();

        em.createNativeQuery("insert into OPERATING_RESOURCES (BOARD_BOOK_UUID, ADDED_OPERATING_RESOURCES) values (:bbeUuid, :resource)")
                .setParameter("bbeUuid", boardBookEntry.getUuid())
                .setParameter("resource", resource)
                .executeUpdate();

        em.getTransaction().commit();
    }

    @Test
    public void testConsistentHistory() {
        Timestamp timestamp = new Timestamp(System.currentTimeMillis());

        Aircraft ac = new Aircraft(EngineType.piston, "PA23", "DGGGG", 11.28f, 8.27f);
        em.getTransaction().begin();
        em.persist(ac);
        em.flush();
        em.getTransaction().commit();

        em.getTransaction().begin();
        em.createNativeQuery("call set_transaction_timestamp(:ts);").setParameter("ts", timestamp).executeUpdate();
        try {
            em.createNativeQuery("update AIRCRAFT set CALL_SIGN='D-GGGG' where CALL_SIGN='DGGGG'").executeUpdate();
            em.flush();
            em.getTransaction().commit();
            Assert.fail();
        } catch (Exception e) {
            Assert.assertTrue(e.getCause().getCause() instanceof SQLException);
            Assert.assertEquals("20100", ((SQLException) e.getCause().getCause()).getSQLState());
        }
    }

    @Test
    public void testCollectionTableUpdate() {

        Aircraft ac = new Aircraft(EngineType.piston, "C172", "OE-DVA", 10.92f, 8.2f);
        em.getTransaction().begin();
        em.persist(ac);
        em.flush();
        em.getTransaction().commit();

        em.getTransaction().begin();

        ac = em.createQuery("select ac from Aircraft ac where ac.callSign=:callSign", Aircraft.class)
                .setParameter("callSign", "OE-DVA")
                .getSingleResult();

        BoardBookEntry boardBookEntry = new BoardBookEntry(1, "1+3", "LOAN", new DateTime().minusHours(1), "LOAN", new DateTime(), 3);
        ac.addBoardBookEntry(boardBookEntry);

        boardBookEntry.getAddedOperatingResources().add("76l AVGAS");

        em.flush();
        em.getTransaction().commit();

        em.getTransaction().begin();

        String newValue = "75.5l AVGAS";
        em.createNativeQuery("update OPERATING_RESOURCES set ADDED_OPERATING_RESOURCES=:newValue where BOARD_BOOK_UUID=:bbUuid")
                .setParameter("newValue", newValue)
                .setParameter("bbUuid", boardBookEntry.getUuid())
                .executeUpdate();

        em.flush();
        em.getTransaction().commit();

        Assert.assertEquals(1, em.createNativeQuery("select 1 from OPERATING_RESOURCES_HST where OPERATION in ('U','D') and ADDED_OPERATING_RESOURCES=:newValue and BOARD_BOOK_UUID=:bbUuid")
                .setParameter("newValue", newValue)
                .setParameter("bbUuid", boardBookEntry.getUuid())
                .getResultList()
                .size());

        em.getTransaction().begin();

        Iterator<String> iterator = boardBookEntry.getAddedOperatingResources().iterator();
        iterator.next();
        iterator.remove();

        em.getTransaction().commit();

        Assert.assertEquals(2, em.createNativeQuery("select 1 from OPERATING_RESOURCES_HST where OPERATION in ('U','D') and ADDED_OPERATING_RESOURCES=:newValue and BOARD_BOOK_UUID=:bbUuid")
                .setParameter("newValue", newValue)
                .setParameter("bbUuid", boardBookEntry.getUuid())
                .getResultList()
                .size());
    }
}