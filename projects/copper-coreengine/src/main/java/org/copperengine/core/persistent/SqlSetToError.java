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

import java.io.PrintWriter;
import java.io.StringWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.util.Collection;

import org.copperengine.core.Acknowledge;
import org.copperengine.core.batcher.AbstractBatchCommand;
import org.copperengine.core.batcher.AcknowledgeCallbackWrapper;
import org.copperengine.core.batcher.BatchCommand;
import org.copperengine.core.batcher.BatchExecutor;

class SqlSetToError {

    static final class Command extends AbstractBatchCommand<Executor, Command> {

        private final PersistentWorkflow<?> wf;
        private final Throwable error;
        private final DBProcessingState dbProcessingState;

        public Command(PersistentWorkflow<?> wf, Throwable error, DBProcessingState dbProcessingState, final long targetTime, Acknowledge ack) {
            super(new AcknowledgeCallbackWrapper<Command>(ack), targetTime);
            this.wf = wf;
            this.error = error;
            this.dbProcessingState = dbProcessingState;
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
                    final PreparedStatement stmtDelQueue = con.prepareStatement("DELETE FROM COP_QUEUE WHERE WORKFLOW_INSTANCE_ID=?");
                    final PreparedStatement stmtUpdateState = con.prepareStatement("UPDATE COP_WORKFLOW_INSTANCE SET STATE=?, LAST_MOD_TS=? WHERE ID=?");
                    final PreparedStatement stmtInsertError = con.prepareStatement("INSERT INTO COP_WORKFLOW_INSTANCE_ERROR (WORKFLOW_INSTANCE_ID, EXCEPTION, ERROR_TS) VALUES (?,?,?)")) {

                for (BatchCommand<Executor, Command> _cmd : commands) {
                    final Timestamp NOW = new Timestamp(System.currentTimeMillis());
                    Command cmd = (Command) _cmd;
                    stmtUpdateState.setInt(1, cmd.dbProcessingState.ordinal());
                    stmtUpdateState.setTimestamp(2, NOW);
                    stmtUpdateState.setString(3, cmd.wf.getId());
                    stmtUpdateState.addBatch();

                    stmtInsertError.setString(1, cmd.wf.getId());
                    stmtInsertError.setString(2, convert2String(cmd.error));
                    stmtInsertError.setTimestamp(3, NOW);
                    stmtInsertError.addBatch();

                    stmtDelQueue.setString(1, cmd.wf.getId());
                    stmtDelQueue.addBatch();
                }
                stmtUpdateState.executeBatch();
                stmtInsertError.executeBatch();
                stmtDelQueue.executeBatch();
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

    private static final String convert2String(Throwable t) {
        StringWriter sw = new StringWriter(2048);
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }
}
