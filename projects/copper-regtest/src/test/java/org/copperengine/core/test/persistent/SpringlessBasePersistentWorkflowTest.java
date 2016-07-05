/*
 * Copyright 2002-2015 SCOOP Software GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.copperengine.core.test.persistent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;

import java.io.Serializable;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.copperengine.core.Acknowledge;
import org.copperengine.core.DuplicateIdException;
import org.copperengine.core.EngineState;
import org.copperengine.core.PersistentProcessingEngine;
import org.copperengine.core.ProcessingEngine;
import org.copperengine.core.Response;
import org.copperengine.core.Workflow;
import org.copperengine.core.WorkflowFactory;
import org.copperengine.core.WorkflowInstanceDescr;
import org.copperengine.core.audit.AuditTrailEvent;
import org.copperengine.core.audit.BatchingAuditTrail;
import org.copperengine.core.audit.CompressedBase64PostProcessor;
import org.copperengine.core.audit.DummyPostProcessor;
import org.copperengine.core.db.utility.RetryingTransaction;
import org.copperengine.core.persistent.PersistentScottyEngine;
import org.copperengine.core.test.DataHolder;
import org.copperengine.core.test.backchannel.BackChannelQueue;
import org.copperengine.core.test.backchannel.WorkflowResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SpringlessBasePersistentWorkflowTest {

    private static final Logger logger = LoggerFactory.getLogger(SpringlessBasePersistentWorkflowTest.class);

    private static final long DEQUEUE_TIMEOUT = 120;

    static final String PersistentUnitTestWorkflow_NAME = "org.copperengine.core.test.persistent.PersistentUnitTestWorkflow";
    static final String WaitForEverTestWF_NAME = "org.copperengine.core.test.WaitForEverTestWF";

    public final void testDummy() {
        // for junit only
    }

    protected boolean skipTests() {
        return false;
    }

    void cleanDB(DataSource ds) throws Exception {
        new RetryingTransaction<Void>(ds) {
            @Override
            protected Void execute() throws Exception {
                Statement stmt = createStatement(getConnection());
                stmt.execute("DELETE FROM COP_AUDIT_TRAIL_EVENT");
                stmt.close();
                stmt = createStatement(getConnection());
                stmt.execute("DELETE FROM COP_WAIT");
                stmt.close();
                stmt = createStatement(getConnection());
                stmt.execute("DELETE FROM COP_RESPONSE");
                stmt.close();
                stmt = createStatement(getConnection());
                stmt.execute("DELETE FROM COP_QUEUE");
                stmt.close();
                stmt = createStatement(getConnection());
                stmt.execute("DELETE FROM COP_WORKFLOW_INSTANCE");
                stmt.close();
                stmt = createStatement(getConnection());
                stmt.execute("DELETE FROM COP_WORKFLOW_INSTANCE_ERROR");
                stmt.close();
                stmt = createStatement(getConnection());
                stmt.execute("DELETE FROM COP_LOCK");
                stmt.close();
                return null;
            }
        }.run();
    }

    private void checkNumbOfResponsesInDB(final PersistentEngineTestContext ctx, final int expected) throws Exception {
        for (int i = 0; i < 10; i++) {
            if (ctx.getBatcher().getQueueSize() == 0) {
                break;
            }
            Thread.sleep(100);
        }
        new RetryingTransaction<Void>(ctx.getDataSource()) {
            @Override
            protected Void execute() throws Exception {
                PreparedStatement pstmt = getConnection().prepareStatement("SELECT count(*) FROM COP_RESPONSE");
                ResultSet rs = pstmt.executeQuery();
                rs.next();
                int actual = rs.getInt(1);
                org.junit.Assert.assertEquals(expected, actual);
                return null;
            }
        }.run();
    }

    private Statement createStatement(Connection con) throws SQLException {
        return con.createStatement(
                ResultSet.TYPE_SCROLL_INSENSITIVE,
                ResultSet.CONCUR_READ_ONLY,
                ResultSet.CLOSE_CURSORS_AT_COMMIT);
    }

    final String createTestData(int length) {
        StringBuilder dataSB = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            int pos = (int) (Math.random() * 70.0);
            dataSB.append("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ01234567890!§$%&/()=?".substring(pos, pos + 1));
        }
        return dataSB.toString();
    }

    public void testWaitForEver(DataSourceType dsType) throws Exception {
        assumeFalse(skipTests());
        logger.info("running testWaitForEver");
        final PersistentEngineTestContext context = createContext(dsType);
        final PersistentScottyEngine engine = context.getEngine();
        final BackChannelQueue backChannelQueue = context.getBackChannelQueue();
        try {
            assertEquals(EngineState.STARTED, engine.getEngineState());

            final String uuid = UUID.randomUUID().toString();
            engine.run(WaitForEverTestWF_NAME, uuid);

            WorkflowResult x = backChannelQueue.dequeue(5, TimeUnit.SECONDS);
            assertNull(x);

        } finally {
            closeContext(context);
        }
        assertEquals(EngineState.STOPPED, engine.getEngineState());
        assertEquals(0, engine.getNumberOfWorkflowInstances());
    }

    protected void closeContext(final PersistentEngineTestContext context) {
        context.shutdown();
    }

    public void testAsynchResponse(DataSourceType dsType) throws Exception {
        assumeFalse(skipTests());
        logger.info("running testAsynchResponse");
        final int NUMB = 50;
        final String DATA = createTestData(50);
        final PersistentEngineTestContext context = createContext(dsType);
        final PersistentScottyEngine engine = context.getEngine();
        final BackChannelQueue backChannelQueue = context.getBackChannelQueue();
        try {
            assertEquals(EngineState.STARTED, engine.getEngineState());

            for (int i = 0; i < NUMB; i++) {
                engine.run(PersistentUnitTestWorkflow_NAME, DATA);
            }

            for (int i = 0; i < NUMB; i++) {
                WorkflowResult x = backChannelQueue.dequeue(DEQUEUE_TIMEOUT, TimeUnit.SECONDS);
                assertNotNull(x);
                assertNotNull(x.getResult());
                assertNotNull(x.getResult().toString().length() == DATA.length());
                assertNull(x.getException());
            }
            checkNumbOfResponsesInDB(context, 0);

        } finally {
            closeContext(context);
        }
        assertEquals(EngineState.STOPPED, engine.getEngineState());
        assertEquals(0, engine.getNumberOfWorkflowInstances());

    }

    public void testFailOnDuplicateInsert(DataSourceType dsType) throws Exception {
        assumeFalse(skipTests());
        logger.info("running testFailOnDuplicateInsert");
        final String DATA = createTestData(50);

        final PersistentEngineTestContext context = createContext(dsType);
        final PersistentScottyEngine engine = context.getEngine();
        engine.getDbStorage().setRemoveWhenFinished(false);
        try {
            WorkflowInstanceDescr<String> desc = new WorkflowInstanceDescr<String>(PersistentUnitTestWorkflow_NAME, DATA, "DUPLICATE#ID", 1, null);
            engine.run(desc);
            engine.run(desc);
            org.junit.Assert.fail("expected an DuplicateIdException");
        } catch (DuplicateIdException e) {
            // ok
        } finally {
            closeContext(context);
        }
        assertEquals(EngineState.STOPPED, engine.getEngineState());
        assertEquals(0, engine.getNumberOfWorkflowInstances());

    }

    public void testAsynchResponseLargeData(DataSourceType dsType, int dataSize) throws Exception {
        assumeFalse(skipTests());
        logger.info("running testAsynchResponse");
        final int NUMB = 20;
        final String DATA = createTestData(dataSize);
        final PersistentEngineTestContext context = createContext(dsType);
        final PersistentScottyEngine engine = context.getEngine();
        final BackChannelQueue backChannelQueue = context.getBackChannelQueue();
        try {
            assertEquals(EngineState.STARTED, engine.getEngineState());

            for (int i = 0; i < NUMB; i++) {
                engine.run(PersistentUnitTestWorkflow_NAME, DATA);
            }

            for (int i = 0; i < NUMB; i++) {
                WorkflowResult x = backChannelQueue.dequeue(DEQUEUE_TIMEOUT, TimeUnit.SECONDS);
                assertNotNull(x);
                assertNotNull(x.getResult());
                assertNotNull(x.getResult().toString().length() == DATA.length());
                assertNull(x.getException());
            }

            checkNumbOfResponsesInDB(context, 0);

        } finally {
            closeContext(context);
        }
        assertEquals(EngineState.STOPPED, engine.getEngineState());
        assertEquals(0, engine.getNumberOfWorkflowInstances());

    }

    protected PersistentEngineTestContext createContext(DataSourceType dsType) {
        PersistentEngineTestContext ctx = new PersistentEngineTestContext(dsType, true);
        ctx.startup();
        return ctx;
    }

    public void testWithConnection(DataSourceType dsType) throws Exception {
        assumeFalse(skipTests());
        logger.info("running testWithConnection");
        final int NUMB = 20;
        final PersistentEngineTestContext context = createContext(dsType);
        final PersistentScottyEngine engine = context.getEngine();
        final BackChannelQueue backChannelQueue = context.getBackChannelQueue();
        try {
            assertEquals(EngineState.STARTED, engine.getEngineState());

            new RetryingTransaction<Void>(context.getDataSource()) {
                @Override
                protected Void execute() throws Exception {
                    for (int i = 0; i < NUMB; i++) {
                        engine.run(new WorkflowInstanceDescr<Serializable>("org.copperengine.core.test.persistent.DBMockAdapterUsingPersistentUnitTestWorkflow"), getConnection());
                    }
                    return null;
                }
            }.run();

            for (int i = 0; i < NUMB; i++) {
                WorkflowResult x = backChannelQueue.dequeue(DEQUEUE_TIMEOUT, TimeUnit.SECONDS);
                assertNotNull(x);
                assertNull(x.getResult());
                assertNull(x.getException());
            }

            checkNumbOfResponsesInDB(context, 0);

        } finally {
            closeContext(context);
        }
        assertEquals(EngineState.STOPPED, engine.getEngineState());
        assertEquals(0, engine.getNumberOfWorkflowInstances());

    }

    public void testWithConnectionBulkInsert(DataSourceType dsType) throws Exception {
        assumeFalse(skipTests());
        logger.info("running testWithConnectionBulkInsert");
        final int NUMB = 50;
        final PersistentEngineTestContext context = createContext(dsType);
        final PersistentScottyEngine engine = context.getEngine();
        final BackChannelQueue backChannelQueue = context.getBackChannelQueue();
        try {
            assertEquals(EngineState.STARTED, engine.getEngineState());

            final List<Workflow<?>> list = new ArrayList<Workflow<?>>();
            for (int i = 0; i < NUMB; i++) {
                WorkflowFactory<?> wfFactory = engine.createWorkflowFactory(PersistentUnitTestWorkflow_NAME);
                Workflow<?> wf = wfFactory.newInstance();
                list.add(wf);
            }

            new RetryingTransaction<Void>(context.getDataSource()) {
                @Override
                protected Void execute() throws Exception {
                    engine.run(list, getConnection());
                    return null;
                }
            }.run();

            for (int i = 0; i < NUMB; i++) {
                WorkflowResult x = backChannelQueue.dequeue(DEQUEUE_TIMEOUT, TimeUnit.SECONDS);
                assertNotNull(x);
                assertNull(x.getResult());
                assertNull(x.getException());
            }

            checkNumbOfResponsesInDB(context, 0);

        } finally {
            closeContext(context);
        }
        assertEquals(EngineState.STOPPED, engine.getEngineState());
        assertEquals(0, engine.getNumberOfWorkflowInstances());

    }

    public void testTimeouts(DataSourceType dsType) throws Exception {
        assumeFalse(skipTests());
        logger.info("running testTimeouts");
        final int NUMB = 10;
        final PersistentEngineTestContext context = createContext(dsType);
        final PersistentScottyEngine engine = context.getEngine();
        final BackChannelQueue backChannelQueue = context.getBackChannelQueue();
        try {
            assertEquals(EngineState.STARTED, engine.getEngineState());

            for (int i = 0; i < NUMB; i++) {
                engine.run("org.copperengine.core.test.persistent.TimingOutPersistentUnitTestWorkflow", null);
            }

            for (int i = 0; i < NUMB; i++) {
                WorkflowResult x = backChannelQueue.dequeue(DEQUEUE_TIMEOUT, TimeUnit.SECONDS);
                assertNotNull(x);
                assertNull(x.getResult());
                assertNull(x.getException());
            }

            checkNumbOfResponsesInDB(context, 0);

        } finally {
            closeContext(context);
        }
        assertEquals(EngineState.STOPPED, engine.getEngineState());
        assertEquals(0, engine.getNumberOfWorkflowInstances());

    }

    public void testErrorHandlingInCoreEngine(DataSourceType dsType) throws Exception {
        assumeFalse(skipTests());
        final PersistentEngineTestContext context = createContext(dsType);
        final PersistentScottyEngine engine = context.getEngine();
        try {
            final WorkflowInstanceDescr<Serializable> wfInstanceDescr = new WorkflowInstanceDescr<Serializable>("org.copperengine.core.test.persistent.ExceptionThrowingPersistentUnitTestWorkflow");
            wfInstanceDescr.setId(engine.createUUID());
            engine.run(wfInstanceDescr);
            Thread.sleep(5000);
            // check
            new RetryingTransaction<Void>(context.getDataSource()) {
                @Override
                protected Void execute() throws Exception {
                    Statement stmt = createStatement(getConnection());
                    ResultSet rs = stmt.executeQuery("select * from COP_WORKFLOW_INSTANCE_ERROR");
                    assertTrue(rs.next());
                    assertEquals(wfInstanceDescr.getId(), rs.getString("WORKFLOW_INSTANCE_ID"));
                    assertNotNull(rs.getString("EXCEPTION"));
                    assertFalse(rs.next());
                    rs.close();
                    stmt.close();
                    return null;
                }
            }.run();
            engine.restart(wfInstanceDescr.getId());
            Thread.sleep(5000);
            new RetryingTransaction<Void>(context.getDataSource()) {
                @Override
                protected Void execute() throws Exception {
                    Statement stmt = createStatement(getConnection());
                    ResultSet rs = stmt.executeQuery("select * from COP_WORKFLOW_INSTANCE_ERROR");
                    assertTrue(rs.next());
                    assertEquals(wfInstanceDescr.getId(), rs.getString("WORKFLOW_INSTANCE_ID"));
                    assertNotNull(rs.getString("EXCEPTION"));
                    assertTrue(rs.next());
                    assertEquals(wfInstanceDescr.getId(), rs.getString("WORKFLOW_INSTANCE_ID"));
                    assertNotNull(rs.getString("EXCEPTION"));
                    assertFalse(rs.next());
                    rs.close();
                    stmt.close();
                    return null;
                }
            }.run();
        } finally {
            closeContext(context);
        }
        assertEquals(EngineState.STOPPED, engine.getEngineState());
        assertEquals(0, engine.getNumberOfWorkflowInstances());
    }

    public void testErrorHandlingInCoreEngine_restartAll(DataSourceType dsType) throws Exception {
        assumeFalse(skipTests());
        final PersistentEngineTestContext context = createContext(dsType);
        final PersistentScottyEngine engine = context.getEngine();
        try {
            final WorkflowInstanceDescr<Serializable> wfInstanceDescr = new WorkflowInstanceDescr<Serializable>("org.copperengine.core.test.persistent.ExceptionThrowingPersistentUnitTestWorkflow");
            wfInstanceDescr.setId(engine.createUUID());
            engine.run(wfInstanceDescr);
            Thread.sleep(5000);
            // check
            new RetryingTransaction<Void>(context.getDataSource()) {
                @Override
                protected Void execute() throws Exception {
                    Statement stmt = createStatement(getConnection());
                    ResultSet rs = stmt.executeQuery("select * from COP_WORKFLOW_INSTANCE_ERROR");
                    assertTrue(rs.next());
                    assertEquals(wfInstanceDescr.getId(), rs.getString("WORKFLOW_INSTANCE_ID"));
                    assertNotNull(rs.getString("EXCEPTION"));
                    assertFalse(rs.next());
                    rs.close();
                    stmt.close();
                    return null;
                }
            }.run();
            engine.restartAll();
            Thread.sleep(5000);
            new RetryingTransaction<Void>(context.getDataSource()) {
                @Override
                protected Void execute() throws Exception {
                    Statement stmt = createStatement(getConnection());
                    ResultSet rs = stmt.executeQuery("select * from COP_WORKFLOW_INSTANCE_ERROR");
                    assertTrue(rs.next());
                    assertEquals(wfInstanceDescr.getId(), rs.getString("WORKFLOW_INSTANCE_ID"));
                    assertNotNull(rs.getString("EXCEPTION"));
                    assertTrue(rs.next());
                    assertEquals(wfInstanceDescr.getId(), rs.getString("WORKFLOW_INSTANCE_ID"));
                    assertNotNull(rs.getString("EXCEPTION"));
                    assertFalse(rs.next());
                    rs.close();
                    stmt.close();
                    return null;
                }
            }.run();
        } finally {
            closeContext(context);
        }
        assertEquals(EngineState.STOPPED, engine.getEngineState());
        assertEquals(0, engine.getNumberOfWorkflowInstances());
    }

    public void testParentChildWorkflow(DataSourceType dsType) throws Exception {
        assumeFalse(skipTests());
        logger.info("running testParentChildWorkflow");
        final int NUMB = 20;
        final PersistentEngineTestContext context = createContext(dsType);
        final PersistentScottyEngine engine = context.getEngine();
        final BackChannelQueue backChannelQueue = context.getBackChannelQueue();
        try {
            assertEquals(EngineState.STARTED, engine.getEngineState());

            for (int i = 0; i < NUMB; i++) {
                engine.run("org.copperengine.core.test.persistent.subworkflow.TestParentWorkflow", null);
            }

            for (int i = 0; i < NUMB; i++) {
                WorkflowResult x = backChannelQueue.dequeue(DEQUEUE_TIMEOUT, TimeUnit.SECONDS);
                assertNotNull(x);
                assertNull(x.getResult());
                assertNull(x.getException());
            }
        } finally {
            closeContext(context);
        }
        assertEquals(EngineState.STOPPED, engine.getEngineState());
        assertEquals(0, engine.getNumberOfWorkflowInstances());
    }

    public void testErrorKeepWorkflowInstanceInDB(DataSourceType dsType) throws Exception {
        assumeFalse(skipTests());
        logger.info("running testErrorKeepWorkflowInstanceInDB");
        final int NUMB = 20;
        final String DATA = createTestData(50);
        final PersistentEngineTestContext context = createContext(dsType);
        final PersistentScottyEngine engine = context.getEngine();
        final BackChannelQueue backChannelQueue = context.getBackChannelQueue();
        engine.getDbStorage().setRemoveWhenFinished(false);
        try {
            assertEquals(EngineState.STARTED, engine.getEngineState());

            for (int i = 0; i < NUMB; i++) {
                engine.run(PersistentUnitTestWorkflow_NAME, DATA);
            }

            for (int i = 0; i < NUMB; i++) {
                WorkflowResult x = backChannelQueue.dequeue(DEQUEUE_TIMEOUT, TimeUnit.SECONDS);
                assertNotNull(x);
                assertNotNull(x.getResult());
                assertNotNull(x.getResult().toString().length() == DATA.length());
                assertNull(x.getException());
            }

            new RetryingTransaction<Void>(context.getDataSource()) {
                @Override
                protected Void execute() throws Exception {
                    Statement stmt = createStatement(getConnection());
                    ResultSet rs = stmt.executeQuery("select count(*) from COP_WORKFLOW_INSTANCE");
                    assertTrue(rs.next());
                    int x = rs.getInt(1);
                    assertEquals(NUMB, x);
                    rs.close();
                    stmt.close();
                    return null;
                }
            }.run();

        } finally {
            closeContext(context);
        }
        assertEquals(EngineState.STOPPED, engine.getEngineState());
        assertEquals(0, engine.getNumberOfWorkflowInstances());
    }

    public void testCompressedAuditTrail(DataSourceType dsType) throws Exception {
        assumeFalse(skipTests());
        logger.info("running testCompressedAuditTrail");
        final int NUMB = 20;
        final String DATA = createTestData(50);

        final PersistentEngineTestContext context = createContext(dsType);
        final PersistentScottyEngine engine = context.getEngine();
        final BackChannelQueue backChannelQueue = context.getBackChannelQueue();
        context.getAuditTrail().setMessagePostProcessor(new CompressedBase64PostProcessor());
        try {
            assertEquals(EngineState.STARTED, engine.getEngineState());

            for (int i = 0; i < NUMB; i++) {
                engine.run(PersistentUnitTestWorkflow_NAME, DATA);
            }

            for (int i = 0; i < NUMB; i++) {
                WorkflowResult x = backChannelQueue.dequeue(DEQUEUE_TIMEOUT, TimeUnit.SECONDS);
                assertNotNull(x);
                assertNotNull(x.getResult());
                assertNotNull(x.getResult().toString().length() == DATA.length());
                assertNull(x.getException());
            }
            Thread.sleep(1000);

            new RetryingTransaction<Void>(context.getDataSource()) {
                @Override
                protected Void execute() throws Exception {
                    Statement stmt = createStatement(getConnection());
                    ResultSet rs = stmt.executeQuery("select unique message from (select dbms_lob.substr(long_message, 4000, 1 ) message from COP_AUDIT_TRAIL_EVENT) order by 1 asc");
                    assertTrue(rs.next());
                    // logger.info("\""+new CompressedBase64PostProcessor().deserialize(rs.getString(1))+"\"");
                    // System.out.println(new CompressedBase64PostProcessor().deserialize(rs.getString(1)));
                    assertEquals("finished", new CompressedBase64PostProcessor().deserialize(rs.getString(1)));
                    assertTrue(rs.next());
                    assertEquals("foo successfully called", new CompressedBase64PostProcessor().deserialize(rs.getString(1)));
                    // System.out.println(new CompressedBase64PostProcessor().deserialize(rs.getString(1)));
                    assertFalse(rs.next());
                    rs.close();
                    stmt.close();
                    return null;
                }
            }.run();

        } catch (Exception e) {
            logger.error("testCompressedAuditTrail failed", e);
            throw e;
        } finally {
            closeContext(context);
        }
        assertEquals(EngineState.STOPPED, engine.getEngineState());
        assertEquals(0, engine.getNumberOfWorkflowInstances());

    }

    public void testAutoCommit(DataSourceType dsType) throws Exception {
        assumeFalse(skipTests());
        logger.info("running testAutoCommit");
        final PersistentEngineTestContext context = createContext(dsType);
        try {
            DataSource ds = context.getDataSource();
            new RetryingTransaction<Void>(ds) {
                @Override
                protected Void execute() throws Exception {
                    assertFalse(getConnection().getAutoCommit());
                    return null;
                }
            };
        } finally {
            closeContext(context);
        }
    }

    private static String createTestMessage(int size) {
        final StringBuilder sb = new StringBuilder(4000);
        for (int i = 0; i < (size / 10); i++) {
            sb.append("0123456789");
        }
        final String msg = sb.toString();
        return msg;
    }

    public void testAuditTrailUncompressed(DataSourceType dsType) throws Exception {
        assumeFalse(skipTests());
        logger.info("running testAuditTrailSmallData");
        final PersistentEngineTestContext context = createContext(dsType);
        try {
            BatchingAuditTrail auditTrail = context.getAuditTrail();
            auditTrail.setMessagePostProcessor(new DummyPostProcessor());
            auditTrail.synchLog(1, new Date(), "4711", dsType.name(), "4711", "4711", "4711", null, "TEXT");
            auditTrail.synchLog(1, new Date(), "4711", dsType.name(), "4711", "4711", "4711", createTestMessage(500), "TEXT");
            auditTrail.synchLog(1, new Date(), "4711", dsType.name(), "4711", "4711", "4711", createTestMessage(5000), "TEXT");
            auditTrail.synchLog(1, new Date(), "4711", dsType.name(), "4711", "4711", "4711", createTestMessage(50000), "TEXT");
        } finally {
            closeContext(context);
        }
    }

    public void testErrorHandlingWithWaitHook(DataSourceType dsType) throws Exception {
        assumeFalse(skipTests());
        final PersistentEngineTestContext context = createContext(dsType);
        final PersistentScottyEngine engine = context.getEngine();
        try {
            final WorkflowInstanceDescr<Serializable> wfInstanceDescr = new WorkflowInstanceDescr<Serializable>("org.copperengine.core.test.persistent.ErrorWaitHookUnitTestWorkflow");
            wfInstanceDescr.setId(engine.createUUID());
            engine.run(wfInstanceDescr, null);
            Thread.sleep(2500);
            // check
            new RetryingTransaction<Void>(context.getDataSource()) {
                @Override
                protected Void execute() throws Exception {
                    Statement stmt = createStatement(getConnection());
                    ResultSet rs = stmt.executeQuery("select * from COP_WORKFLOW_INSTANCE_ERROR");
                    assertTrue(rs.next());
                    assertEquals(wfInstanceDescr.getId(), rs.getString("WORKFLOW_INSTANCE_ID"));
                    assertNotNull(rs.getString("EXCEPTION"));
                    assertFalse(rs.next());
                    rs.close();
                    stmt.close();
                    return null;
                }
            }.run();
        } finally {
            closeContext(context);
        }
        assertEquals(EngineState.STOPPED, engine.getEngineState());
        assertEquals(0, engine.getNumberOfWorkflowInstances());
    }

    public void testAuditTrailCustomSeqNr(DataSourceType dsType) throws Exception {
        assumeFalse(skipTests());
        logger.info("running testAuditTrailCustomSeqNr");
        final PersistentEngineTestContext context = createContext(dsType);
        final BatchingAuditTrail auditTrail = context.getAuditTrail();
        auditTrail.setMessagePostProcessor(new DummyPostProcessor());
        try {
            long seqNr = 1;
            auditTrail.synchLog(new AuditTrailEvent(1, new Date(), "4711", dsType.name(), "4711", "4711", "4711", null, "TEXT", seqNr++));
            auditTrail.synchLog(new AuditTrailEvent(1, new Date(), "4711", dsType.name(), "4711", "4711", "4711", createTestMessage(500), "TEXT", seqNr++));
            auditTrail.synchLog(new AuditTrailEvent(1, new Date(), "4711", dsType.name(), "4711", "4711", "4711", createTestMessage(5000), "TEXT", seqNr++));
            auditTrail.synchLog(new AuditTrailEvent(1, new Date(), "4711", dsType.name(), "4711", "4711", "4711", createTestMessage(50000), "TEXT", seqNr++));
            // check
            new RetryingTransaction<Void>(context.getDataSource()) {
                @Override
                protected Void execute() throws Exception {
                    Statement stmt = createStatement(getConnection());
                    ResultSet rs = stmt.executeQuery("select seq_id from COP_AUDIT_TRAIL_EVENT order by seq_id");
                    assertTrue(rs.next());
                    assertEquals(1, rs.getLong(1));
                    assertTrue(rs.next());
                    assertEquals(2, rs.getLong(1));
                    assertTrue(rs.next());
                    assertEquals(3, rs.getLong(1));
                    assertTrue(rs.next());
                    assertEquals(4, rs.getLong(1));
                    assertFalse(rs.next());
                    rs.close();
                    stmt.close();
                    return null;
                }
            }.run();
        } finally {
            closeContext(context);
        }
    }

    public void testNotifyWithoutEarlyResponseHandling(DataSourceType dsType) throws Exception {
        assumeFalse(skipTests());
        logger.info("running testNotifyWithoutEarlyResponseHandling");
        final PersistentEngineTestContext context = createContext(dsType);
        final PersistentScottyEngine engine = context.getEngine();
        try {
            new RetryingTransaction<Void>(context.getDataSource()) {
                @Override
                protected Void execute() throws Exception {
                    try {
                        Response<?> response = new Response<String>("CID#withEarlyResponse", "TEST", null);
                        engine.notify(response, getConnection());
                        Statement stmt = createStatement(getConnection());
                        ResultSet rs = stmt.executeQuery("select * from COP_RESPONSE");
                        assertTrue(rs.next());
                        assertEquals(response.getCorrelationId(), rs.getString("CORRELATION_ID"));
                        assertFalse(rs.next());
                        getConnection().rollback();

                        response = new Response<String>("CID#withoutEarlyResponse", "TEST", null);
                        response.setEarlyResponseHandling(false);
                        engine.notify(response, getConnection());
                        rs = stmt.executeQuery("select * from COP_RESPONSE");
                        assertFalse(rs.next());
                        rs.close();
                        stmt.close();
                        getConnection().rollback();
                    } catch (Exception e) {
                        logger.error("testNotifyWithoutEarlyResponseHandling failed", e);
                        throw e;
                    }
                    return null;
                }
            }.run();
        } finally {
            closeContext(context);
        }
        assertEquals(EngineState.STOPPED, engine.getEngineState());
        assertEquals(0, engine.getNumberOfWorkflowInstances());

    }

    public void testQueryAllActive(DataSourceType dsType) throws Exception {
        assumeFalse(skipTests());
        final PersistentEngineTestContext context = createContext(dsType);
        final PersistentScottyEngine engine = context.getEngine();
        try {
            // just check, that the underlying SQL statements are ok.
            assertEquals(0, engine.queryActiveWorkflowInstances(null, 100).size());
            assertEquals(0, engine.queryActiveWorkflowInstances("foo.john.Doe", 100).size());
        } finally {
            closeContext(context);
        }
        assertEquals(EngineState.STOPPED, engine.getEngineState());
        assertEquals(0, engine.getNumberOfWorkflowInstances());
    }

    private <T> void manualSend(PersistentProcessingEngine engine, String correlationId, T data) {
        Response<T> response = new Response<>(correlationId, data, null);
        response.setResponseId(UUID.randomUUID().toString());
        System.out.println("manualSend: " + response.getResponseId());
        Acknowledge.DefaultAcknowledge ack = new Acknowledge.DefaultAcknowledge();
        engine.notify(response, ack);
        ack.waitForAcknowledge();

    }

    public void testMulipleResponsesForSameCidPersistentTestWorkflow(DataSourceType dsType) throws Exception {
        assumeFalse(skipTests());
        final PersistentEngineTestContext context = createContext(dsType);
        final PersistentScottyEngine engine = context.getEngine();
        try {
            String cid = "testSingleCID";
            try
            {
                engine.run("MulipleResponsesForSameCidPersistentTestWorkflow", cid);
                Thread.sleep(1000); // wait for it to start up
                for (int i = 1; i <= 9; i++)
                {
                    manualSend(engine, cid, "Response#" + i);
                }
                manualSend(engine, cid, "GG");
                Thread.sleep(1000);
            } catch (Exception e)
            {
                e.printStackTrace();
            }

        } finally {
            closeContext(context);
        }
        assertEquals(EngineState.STOPPED, engine.getEngineState());
        assertEquals(0, engine.getNumberOfWorkflowInstances());
    }

    public void testMultipleEngines(DataSourceType dsType) throws Exception {
        assumeFalse(skipTests());

        logger.info("running testMultipleEngines");
        final int NUMB = 50;

        final PersistentEngineTestContext contextRed = new PersistentEngineTestContext(dsType, true, "red", true);
        contextRed.startup();

        final PersistentEngineTestContext contextBlue = new PersistentEngineTestContext(dsType, false, "blue", true) {
            @Override
            protected DataHolder createDataHolder() {
                return contextRed.getDataHolder();
            }

            @Override
            protected BackChannelQueue createBackChannelQueue() {
                return contextRed.getBackChannelQueue();
            }
        };
        contextBlue.startup();

        final PersistentScottyEngine engineRed = contextRed.getEngine();
        final PersistentScottyEngine engineBlue = contextBlue.getEngine();
        final BackChannelQueue backChannelQueue = contextRed.getBackChannelQueue();
        try {
            assertEquals(EngineState.STARTED, engineRed.getEngineState());
            assertEquals(EngineState.STARTED, engineBlue.getEngineState());

            for (int i = 0; i < NUMB; i++) {
                ProcessingEngine engine = i % 2 == 0 ? engineRed : engineBlue;
                engine.run(PersistentUnitTestWorkflow_NAME, null);
            }

            int x = 0;
            long startTS = System.currentTimeMillis();
            while (x < NUMB && startTS + 15000 > System.currentTimeMillis()) {
                WorkflowResult wfr = backChannelQueue.poll();

                if (wfr != null) {
                    assertNull(wfr.getResult());
                    assertNull(wfr.getException());
                    x++;
                } else {
                    Thread.sleep(50);
                }
            }
            assertSame("Test failed - Timeout - " + x + " responses so far", x, NUMB);

            Thread.sleep(1000);

            // check for late queue entries
            assertNull(backChannelQueue.poll());

            // check AuditTrail Log
            new RetryingTransaction<Void>(contextRed.getDataSource()) {
                @Override
                protected Void execute() throws Exception {
                    ResultSet rs = getConnection().createStatement().executeQuery("SELECT count(*) FROM COP_AUDIT_TRAIL_EVENT");
                    rs.next();
                    int count = rs.getInt(1);
                    assertEquals(NUMB * 6, count);
                    rs.close();
                    return null;
                }
            }.run();
        } finally {
            contextRed.close();
            contextBlue.close();
        }
        assertEquals(EngineState.STOPPED, engineRed.getEngineState());
        assertEquals(EngineState.STOPPED, engineBlue.getEngineState());
        assertEquals(0, engineRed.getNumberOfWorkflowInstances());
        assertEquals(0, engineBlue.getNumberOfWorkflowInstances());

    }
}
