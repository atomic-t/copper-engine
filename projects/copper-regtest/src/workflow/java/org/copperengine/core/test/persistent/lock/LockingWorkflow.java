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
package org.copperengine.core.test.persistent.lock;

import org.copperengine.core.AutoWire;
import org.copperengine.core.Interrupt;
import org.copperengine.core.Response;
import org.copperengine.core.WaitMode;
import org.copperengine.core.persistent.PersistentWorkflow;
import org.copperengine.core.persistent.lock.PersistentLockManager;
import org.copperengine.core.persistent.lock.PersistentLockResult;
import org.copperengine.core.util.Backchannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LockingWorkflow extends PersistentWorkflow<String> {

    private static final long serialVersionUID = 1L;
    private static final Logger logger = LoggerFactory.getLogger(LockingWorkflow.class);

    private transient PersistentLockManager persistentLockManager;
    private transient Backchannel backchannel;

    @AutoWire
    public void setPersistentLockManager(PersistentLockManager persistentLockManager) {
        this.persistentLockManager = persistentLockManager;
    }

    @AutoWire
    public void setBackchannel(Backchannel backchannel) {
        this.backchannel = backchannel;
    }

    @Override
    public void main() throws Interrupt {
        logger.info("Starting...");
        Boolean success = false;
        for (int i = 0; i < 10; i++) {
            final String lockId = getData();
            try {
                acquireLock(lockId);

                // logger.info("sleep for 50 msec...");
                // wait(WaitMode.ALL, 50, getEngine().createUUID());
                // logger.info("Done sleeping for 50 msec.");

                logger.info("sleep for 50 msec...");
                Thread.sleep(600);
                logger.info("Done sleeping for 50 msec.");

                success = true;

            } catch (Exception e) {
                logger.error("main failed", e);
            }
            releaseLock(lockId);
        }
        backchannel.notify(getId(), success);
        logger.info("finished!");
    }

    private void releaseLock(final String lockId) {
        logger.info("releaseLock({})", lockId);
        persistentLockManager.releaseLock(lockId, this.getId());
    }

    private void acquireLock(final String lockId) throws Interrupt {
        logger.info("Going to aquire lock {}", lockId);
        final String correlationId = getEngine().createUUID();
        persistentLockManager.acquireLock(lockId, correlationId, this.getId());
        logger.info("Calling wait...");
        long startTS = System.currentTimeMillis();
        wait(WaitMode.ALL, 10000, correlationId);
        long et = System.currentTimeMillis() - startTS;
        logger.info("wait returned after {} msesc - getAndRemoveResponse...", et);
        final Response<PersistentLockResult> result = getAndRemoveResponse(correlationId);
        if (result.isTimeout()) {
            throw new RuntimeException("acquireLock timed out");
        }
        logger.info("lock result={}", result);
    }
}
