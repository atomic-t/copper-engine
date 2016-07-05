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
package org.copperengine.core.persistent;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.copperengine.core.Acknowledge;
import org.copperengine.core.WaitHook;
import org.copperengine.core.WaitMode;
import org.copperengine.core.batcher.AbstractBatchCommand;
import org.copperengine.core.batcher.BatchCommand;
import org.copperengine.core.batcher.BatchExecutor;
import org.copperengine.core.batcher.CommandCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class SqlRegisterCallback {

    private static final Logger logger = LoggerFactory.getLogger(SqlRegisterCallback.class);

    static final class Command extends AbstractBatchCommand<Executor, Command> {

        private final RegisterCall registerCall;
        private final Serializer serializer;
        private final WorkflowPersistencePlugin workflowPersistencePlugin;

        public Command(final RegisterCall registerCall, final Serializer serializer, final ScottyDBStorageInterface dbStorage, final long targetTime, final WorkflowPersistencePlugin workflowPersistencePlugin, final Acknowledge ack) {
            super(new CommandCallback<Command>() {
                @Override
                public void commandCompleted() {
                    ack.onSuccess();
                }

                @Override
                public void unhandledException(Exception e) {
                    ack.onException(e);
                    logger.error("Execution of batch entry in a single txn failed.", e);
                    dbStorage.error(registerCall.workflow, e, new Acknowledge.BestEffortAcknowledge());
                }
            }, targetTime);
            this.registerCall = registerCall;
            this.serializer = serializer;
            this.workflowPersistencePlugin = workflowPersistencePlugin;
        }

        @Override
        public Executor executor() {
            return Executor.INSTANCE;
        }

    }

    static final class Executor extends BatchExecutor<Executor, Command> {

        private static final Executor INSTANCE = new Executor();

        @Override
        public void doExec(final Collection<BatchCommand<Executor, Command>> commands, final Connection con) throws Exception {
            try (
                    PreparedStatement stmtDelQueue = con.prepareStatement("DELETE FROM COP_QUEUE WHERE WORKFLOW_INSTANCE_ID=?");
                    PreparedStatement deleteWait = con.prepareStatement("DELETE FROM COP_WAIT WHERE CORRELATION_ID=?");
                    PreparedStatement deleteResponse = con.prepareStatement("DELETE FROM COP_RESPONSE WHERE RESPONSE_ID=?");
                    PreparedStatement insertWaitStmt = con.prepareStatement("INSERT INTO COP_WAIT (CORRELATION_ID,WORKFLOW_INSTANCE_ID,MIN_NUMB_OF_RESP,TIMEOUT_TS,STATE,PRIORITY,PPOOL_ID) VALUES (?,?,?,?,?,?,?)");
                    PreparedStatement updateWfiStmt = con.prepareStatement("UPDATE COP_WORKFLOW_INSTANCE SET STATE=?, PRIORITY=?, LAST_MOD_TS=?, PPOOL_ID=?, DATA=?, OBJECT_STATE=?, CS_WAITMODE=?, MIN_NUMB_OF_RESP=?, NUMB_OF_WAITS=?, TIMEOUT=? WHERE ID=?")) {

                final Timestamp now = new Timestamp(System.currentTimeMillis());
                boolean doWaitDeletes = false;
                boolean doResponseDeletes = false;
                HashMap<WorkflowPersistencePlugin, ArrayList<PersistentWorkflow<?>>> wfs = new HashMap<WorkflowPersistencePlugin, ArrayList<PersistentWorkflow<?>>>();
                for (BatchCommand<Executor, Command> _cmd : commands) {
                    Command cmd = (Command) _cmd;
                    RegisterCall rc = cmd.registerCall;
                    PersistentWorkflow<?> persistentWorkflow = (PersistentWorkflow<?>) rc.workflow;
                    persistentWorkflow.flushCheckpointAcknowledges();
                    ArrayList<PersistentWorkflow<?>> _wfs = wfs.get(cmd.workflowPersistencePlugin);
                    if (_wfs == null) {
                        _wfs = new ArrayList<PersistentWorkflow<?>>();
                        wfs.put(cmd.workflowPersistencePlugin, _wfs);
                    }
                    _wfs.add(persistentWorkflow);
                    for (String cid : rc.correlationIds) {
                        insertWaitStmt.setString(1, cid);
                        insertWaitStmt.setString(2, rc.workflow.getId());
                        insertWaitStmt.setInt(3, rc.waitMode == WaitMode.ALL ? rc.correlationIds.length : 1);
                        insertWaitStmt.setTimestamp(4, rc.timeoutTS);
                        insertWaitStmt.setInt(5, 0);
                        insertWaitStmt.setInt(6, rc.workflow.getPriority());
                        insertWaitStmt.setString(7, rc.workflow.getProcessorPoolId());
                        insertWaitStmt.addBatch();
                    }
                    int idx = 1;
                    SerializedWorkflow sw = cmd.serializer.serializeWorkflow(rc.workflow);
                    updateWfiStmt.setInt(idx++, DBProcessingState.WAITING.ordinal());
                    updateWfiStmt.setInt(idx++, rc.workflow.getPriority());
                    updateWfiStmt.setTimestamp(idx++, now);
                    updateWfiStmt.setString(idx++, rc.workflow.getProcessorPoolId());
                    updateWfiStmt.setString(idx++, sw.getData());
                    updateWfiStmt.setString(idx++, sw.getObjectState());
                    updateWfiStmt.setInt(idx++, rc.waitMode.ordinal());
                    updateWfiStmt.setInt(idx++, rc.waitMode == WaitMode.FIRST ? 1 : rc.correlationIds.length);
                    updateWfiStmt.setInt(idx++, rc.correlationIds.length);
                    updateWfiStmt.setTimestamp(idx++, rc.timeoutTS);
                    updateWfiStmt.setString(idx++, rc.workflow.getId());
                    updateWfiStmt.addBatch();

                    stmtDelQueue.setString(1, ((PersistentWorkflow<?>) rc.workflow).getId());
                    stmtDelQueue.addBatch();
                    logger.debug("Deleting {} from cop_queue", ((PersistentWorkflow<?>) rc.workflow).getId());

                    Set<String> cidList = ((PersistentWorkflow<?>) rc.workflow).waitCidList;
                    if (cidList != null) {
                        for (String cid : cidList) {
                            deleteWait.setString(1, cid);
                            deleteWait.addBatch();
                            doWaitDeletes = true;
                        }
                    }
                    List<String> responseIdList = ((PersistentWorkflow<?>) rc.workflow).responseIdList;
                    if (responseIdList != null) {
                        for (String responseId : responseIdList) {
                            deleteResponse.setString(1, responseId);
                            deleteResponse.addBatch();
                            doResponseDeletes = true;
                        }
                    }

                }
                if (doResponseDeletes)
                    deleteResponse.executeBatch();
                if (doWaitDeletes)
                    deleteWait.executeBatch();

                insertWaitStmt.executeBatch();
                updateWfiStmt.executeBatch();
                stmtDelQueue.executeBatch();

                for (BatchCommand<Executor, Command> _cmd : commands) {
                    Command cmd = (Command) _cmd;
                    RegisterCall rc = cmd.registerCall;
                    for (WaitHook wh : rc.waitHooks) {
                        wh.onWait(rc.workflow, con);
                    }
                }

                for (Map.Entry<WorkflowPersistencePlugin, ArrayList<PersistentWorkflow<?>>> en : wfs.entrySet()) {
                    en.getKey().onWorkflowsSaved(con, en.getValue());
                }
            }
        }

        @Override
        public int maximumBatchSize() {
            return 100;
        }

        @Override
        public int preferredBatchSize() {
            return 50;
        }

    }
}
