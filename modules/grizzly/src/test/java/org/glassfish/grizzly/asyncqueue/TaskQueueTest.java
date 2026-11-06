/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0, which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the
 * Eclipse Public License v. 2.0 are satisfied: GNU General Public License,
 * version 2 with the GNU Classpath Exception, which is available at
 * https://www.gnu.org/software/classpath/license.html.
 *
 * SPDX-License-Identifier: EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0
 */

package org.glassfish.grizzly.asyncqueue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;

import org.glassfish.grizzly.WriteResult;
import org.junit.Test;

/**
 * Tests that {@link TaskQueue} keeps its reserved-space counter synchronized with the actually queued records,
 * in particular across {@link TaskQueue#onClose()}.
 */
public class TaskQueueTest {

    private static final class TestRecord extends AsyncQueueRecord<WriteResult> {
        private final AtomicBoolean failureNotified = new AtomicBoolean();

        TestRecord() {
            super(null, null, null);
        }

        @Override
        public void notifyFailure(final Throwable e) {
            failureNotified.set(true);
        }

        @Override
        public void recycle() {
        }

        @Override
        public WriteResult getCurrentResult() {
            return null;
        }
    }

    private static TaskQueue<TestRecord> newQueue() {
        return TaskQueue.createTaskQueue(() -> 65536);
    }

    @Test
    public void reservedSpaceIsReleasedOnClose() {
        final TaskQueue<TestRecord> queue = newQueue();
        final TestRecord record = new TestRecord();
        assertTrue(queue.offer(record));
        assertEquals(45, queue.reserveSpace(45));
        assertFalse(queue.isEmpty());

        queue.onClose();

        assertTrue(record.failureNotified.get());
        assertTrue(queue.isClosed());
        assertTrue(queue.isEmpty());
        assertEquals(0, queue.size());
        assertEquals(0, queue.spaceInBytes());
        assertNull(queue.poll());
    }

    @Test
    public void offerAndReserveAfterCloseKeepQueueEmpty() {
        final TaskQueue<TestRecord> queue = newQueue();
        queue.onClose();

        final TestRecord record = new TestRecord();
        assertFalse(queue.offer(record));
        assertTrue(record.failureNotified.get());
        assertEquals(0, queue.reserveSpace(45));
        assertEquals(0, queue.releaseSpace(45));
        assertTrue(queue.isEmpty());
        assertEquals(0, queue.size());
        assertEquals(0, queue.spaceInBytes());
    }

    @Test
    public void closeDrainsRecordWhoseSpaceIsNotYetReserved() {
        final TaskQueue<TestRecord> queue = newQueue();
        final TestRecord record = new TestRecord();
        assertTrue(queue.offer(record));

        queue.onClose();

        assertTrue(record.failureNotified.get());
        assertNull(queue.poll());
        assertTrue(queue.isEmpty());
        assertEquals(0, queue.size());
    }

    @Test
    public void closeResetsSpaceReservedForMultipleRecords() {
        final TaskQueue<TestRecord> queue = newQueue();
        final TestRecord first = new TestRecord();
        final TestRecord second = new TestRecord();
        assertTrue(queue.offer(first));
        assertTrue(queue.offer(second));
        assertEquals(45, queue.reserveSpace(45));
        assertEquals(90, queue.reserveSpace(45));

        queue.onClose();

        assertTrue(first.failureNotified.get());
        assertTrue(second.failureNotified.get());
        assertTrue(queue.isEmpty());
        assertEquals(0, queue.size());
        assertEquals(0, queue.spaceInBytes());
    }
}
