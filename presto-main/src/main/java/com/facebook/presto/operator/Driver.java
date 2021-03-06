/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.operator;

import com.facebook.presto.ScheduledSplit;
import com.facebook.presto.TaskSource;
import com.facebook.presto.spi.Split;
import com.facebook.presto.sql.planner.plan.PlanNodeId;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.ListenableFuture;
import io.airlift.log.Logger;
import io.airlift.units.Duration;

import javax.annotation.concurrent.GuardedBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import static com.facebook.presto.operator.Operator.NOT_BLOCKED;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

//
// NOTE:  As a general strategy the methods should "stage" a change and only
// process the actual change before lock release (DriverLockResult.close()).
// The assures that only one thread will be working with the operators at a
// time and state changer threads are not blocked.
//
public class Driver
{
    private static final Logger log = Logger.get(Driver.class);

    private final DriverContext driverContext;
    private final List<Operator> operators;
    private final Map<PlanNodeId, SourceOperator> sourceOperators;
    private final ConcurrentMap<PlanNodeId, TaskSource> newSources = new ConcurrentHashMap<>();

    private final AtomicReference<State> state = new AtomicReference<>(State.ALIVE);

    private final ReentrantLock exclusiveLock = new ReentrantLock();

    @GuardedBy("this")
    private Thread lockHolder;

    @GuardedBy("exclusiveLock")
    private Map<PlanNodeId, TaskSource> currentSources = new ConcurrentHashMap<>();

    private enum State
    {
        ALIVE, NEED_DESTRUCTION, DESTROYED
    }

    public Driver(DriverContext driverContext, Operator firstOperator, Operator... otherOperators)
    {
        this(checkNotNull(driverContext, "driverContext is null"),
                ImmutableList.<Operator>builder()
                        .add(checkNotNull(firstOperator, "firstOperator is null"))
                        .add(checkNotNull(otherOperators, "otherOperators is null"))
                        .build());
    }

    public Driver(DriverContext driverContext, List<Operator> operators)
    {
        this.driverContext = checkNotNull(driverContext, "driverContext is null");
        this.operators = ImmutableList.copyOf(checkNotNull(operators, "operators is null"));
        checkArgument(!operators.isEmpty(), "There must be at least one operator");

        ImmutableMap.Builder<PlanNodeId, SourceOperator> sourceOperators = ImmutableMap.builder();
        for (Operator operator : operators) {
            if (operator instanceof SourceOperator) {
                SourceOperator sourceOperator = (SourceOperator) operator;
                sourceOperators.put(sourceOperator.getSourceId(), sourceOperator);
            }
        }
        this.sourceOperators = sourceOperators.build();
    }

    public DriverContext getDriverContext()
    {
        return driverContext;
    }

    public Set<PlanNodeId> getSourceIds()
    {
        return sourceOperators.keySet();
    }

    public void close()
    {
        // mark the service for destruction
        if (!state.compareAndSet(State.ALIVE, State.NEED_DESTRUCTION)) {
            return;
        }

        // if we can get the lock, attempt a clean shutdown; otherwise someone else will shutdown
        try (DriverLockResult lockResult = tryLockAndProcessPendingStateChanges(0, TimeUnit.MILLISECONDS)) {
            // if we did not get the lock, interrupt the lock holder
            if (!lockResult.wasAcquired()) {
                // there is a benign race condition here were the lock holder
                // can be change between attempting to get lock and grabbing
                // the synchronized lock here, but in either case we want to
                // interrupt the lock holder thread
                synchronized (this) {
                    if (lockHolder != null) {
                        lockHolder.interrupt();
                    }
                }
            }

            // clean shutdown is automatically triggered during lock release
        }
    }

    public boolean isFinished()
    {
        checkLockNotHeld("Can not check finished status while holding the driver lock");

        // if we can get the lock, attempt a clean shutdown; otherwise someone else will shutdown
        try (DriverLockResult lockResult = tryLockAndProcessPendingStateChanges(0, TimeUnit.MILLISECONDS)) {
            if (lockResult.wasAcquired()) {
                boolean finished = state.get() != State.ALIVE || driverContext.isDone() || operators.get(operators.size() - 1).isFinished();
                if (finished) {
                    state.compareAndSet(State.ALIVE, State.NEED_DESTRUCTION);
                }
                return finished;
            }
            else {
                // did not get the lock, so we can't check operators, or destroy
                return state.get() != State.ALIVE || driverContext.isDone();
            }
        }
    }

    public void updateSource(TaskSource source)
    {
        checkLockNotHeld("Can not update sources while holding the driver lock");

        // does this driver have an operator for the specified source?
        if (!sourceOperators.containsKey(source.getPlanNodeId())) {
            return;
        }

        // stage the new updates
        while (true) {
            // attempt to update directly to the new source
            TaskSource currentNewSource = newSources.putIfAbsent(source.getPlanNodeId(), source);

            // if update succeeded, just break
            if (currentNewSource == null) {
                break;
            }

            // merge source into the current new source
            TaskSource newSource = currentNewSource.update(source);

            // if this is not a new source, just return
            if (newSource == currentNewSource) {
                break;
            }

            // attempt to replace the currentNewSource with the new source
            if (newSources.replace(source.getPlanNodeId(), currentNewSource, newSource)) {
                break;
            }

            // someone else updated while we were processing
        }

        // attempt to get the lock and process the updates we staged above
        // updates will be processed in close if and only if we got the lock
        tryLockAndProcessPendingStateChanges(0, TimeUnit.MILLISECONDS).close();
    }

    private void processNewSources()
    {
        checkLockHeld("Lock must be held to call processNewSources");

        // only update if the driver is still alive
        if (state.get() != State.ALIVE) {
            return;
        }

        // copy the pending sources
        // it is ok to "miss" a source added during the copy as it will be
        // handled on the next call to this method
        Map<PlanNodeId, TaskSource> sources = new HashMap<>(newSources);
        for (Entry<PlanNodeId, TaskSource> entry : sources.entrySet()) {
            // Remove the entries we are going to process from the newSources map.
            // It is ok if someone already updated the entry; we will catch it on
            // the next iteration.
            newSources.remove(entry.getKey(), entry.getValue());

            processNewSource(entry.getValue());
        }
    }

    private void processNewSource(TaskSource source)
    {
        checkLockHeld("Lock must be held to call processNewSources");

        // create new source
        Set<ScheduledSplit> newSplits;
        TaskSource currentSource = currentSources.get(source.getPlanNodeId());
        if (currentSource == null) {
            newSplits = source.getSplits();
            currentSources.put(source.getPlanNodeId(), source);
        }
        else {
            // merge the current source and the specified source
            TaskSource newSource = currentSource.update(source);

            // if this is not a new source, just return
            if (newSource == currentSource) {
                return;
            }

            // find the new splits to add
            newSplits = Sets.difference(newSource.getSplits(), currentSource.getSplits());
            currentSources.put(source.getPlanNodeId(), newSource);
        }

        // add new splits
        for (ScheduledSplit newSplit : newSplits) {
            Split split = newSplit.getSplit();

            SourceOperator sourceOperator = sourceOperators.get(source.getPlanNodeId());
            if (sourceOperator != null) {
                sourceOperator.addSplit(split);
            }
        }

        // set no more splits
        if (source.isNoMoreSplits()) {
            sourceOperators.get(source.getPlanNodeId()).noMoreSplits();
        }
    }

    public ListenableFuture<?> processFor(Duration duration)
    {
        checkLockNotHeld("Can not process for a duration while holding the driver lock");

        checkNotNull(duration, "duration is null");

        long maxRuntime = duration.roundTo(TimeUnit.NANOSECONDS);

        long start = System.nanoTime();
        do {
            ListenableFuture<?> future = process();
            if (!future.isDone()) {
                return future;
            }
        }
        while (System.nanoTime() - start < maxRuntime && !isFinished());

        return NOT_BLOCKED;
    }

    public ListenableFuture<?> process()
    {
        checkLockNotHeld("Can not process while holding the driver lock");

        try (DriverLockResult lockResult = tryLockAndProcessPendingStateChanges(100, TimeUnit.MILLISECONDS)) {
            try {
                if (!lockResult.wasAcquired()) {
                    // this is unlikely to happen unless the driver is being
                    // destroyed and in that case the caller should notice notice
                    // this state change by calling isFinished
                    return NOT_BLOCKED;
                }

                driverContext.start();

                if (!newSources.isEmpty()) {
                    processNewSources();
                }

                for (int i = 0; i < operators.size() - 1 && !driverContext.isDone(); i++) {
                    // check if current operator is blocked
                    Operator current = operators.get(i);
                    ListenableFuture<?> blocked = current.isBlocked();
                    if (!blocked.isDone()) {
                        current.getOperatorContext().recordBlocked(blocked);
                        return blocked;
                    }

                    // check if next operator is blocked
                    Operator next = operators.get(i + 1);
                    blocked = next.isBlocked();
                    if (!blocked.isDone()) {
                        next.getOperatorContext().recordBlocked(blocked);
                        return blocked;
                    }

                    // if current operator is finished...
                    if (current.isFinished()) {
                        // let next operator know there will be no more data
                        next.getOperatorContext().startIntervalTimer();
                        next.finish();
                        next.getOperatorContext().recordFinish();
                    }
                    else {
                        // if next operator needs input...
                        if (next.needsInput()) {
                            // get an output page from current operator
                            current.getOperatorContext().startIntervalTimer();
                            Page page = current.getOutput();
                            current.getOperatorContext().recordGetOutput(page);

                            // if we got an output page, add it to the next operator
                            if (page != null) {
                                next.getOperatorContext().startIntervalTimer();
                                next.addInput(page);
                                next.getOperatorContext().recordAddInput(page);
                            }
                        }
                    }
                }
                return NOT_BLOCKED;
            }
            catch (Throwable t) {
                driverContext.failed(t);
                throw t;
            }
        }
    }

    private void destroyIfNecessary()
    {
        checkLockHeld("Lock must be held to call destroyIfNecessary");

        if (!state.compareAndSet(State.NEED_DESTRUCTION, State.DESTROYED)) {
            return;
        }

        Throwable inFlightException = null;
        try {
            // call finish on every operator; if error occurs, just bail out; we will still call close
            for (Operator operator : operators) {
                operator.finish();
            }
        }
        catch (Throwable t) {
            // record in flight exception so we can add suppressed exceptions below
            inFlightException = t;
            throw t;
        }
        finally {
            // record the current interrupted status (and clear the flag); we'll reset it later
            boolean wasInterrupted = Thread.interrupted();

            // if we get an error while closing a driver, record it and we will throw it at the end
            try {
                for (Operator operator : operators) {
                    if (operator instanceof AutoCloseable) {
                        try {
                            ((AutoCloseable) operator).close();
                        }
                        catch (InterruptedException t) {
                            // don't record the stack
                            wasInterrupted = true;
                        }
                        catch (Throwable t) {
                            inFlightException = addSuppressedException(
                                    inFlightException,
                                    t,
                                    "Error closing operator %s for task %s",
                                    operator.getOperatorContext().getOperatorId(),
                                    driverContext.getTaskId());
                        }
                    }
                }
                driverContext.finished();
            }
            catch (Throwable t) {
                // this shouldn't happen but be safe
                inFlightException = addSuppressedException(
                        inFlightException,
                        t,
                        "Error destroying driver for task %s",
                        driverContext.getTaskId());
            }
            finally {
                // reset the interrupted flag
                if (wasInterrupted) {
                    Thread.currentThread().interrupt();
                }
            }

            if (inFlightException != null) {
                // this will always be an Error or Runtime
                throw Throwables.propagate(inFlightException);
            }
        }
    }

    private Throwable addSuppressedException(Throwable inFlightException, Throwable newException, String message, Object... args)
    {
        if (newException instanceof Error) {
            if (inFlightException == null) {
                inFlightException = newException;
            }
            else {
                inFlightException.addSuppressed(newException);
            }
        }
        else {
            // log normal exceptions instead of rethrowing them
            log.error(newException, message, args);
        }
        return inFlightException;
    }

    private DriverLockResult tryLockAndProcessPendingStateChanges(int timeout, TimeUnit unit)
    {
        checkLockNotHeld("Can not acquire the driver lock while already holding the driver lock");

        return new DriverLockResult(timeout, unit);
    }

    private synchronized void checkLockNotHeld(String message)
    {
        checkState(Thread.currentThread() != lockHolder, message);
    }

    private synchronized void checkLockHeld(String message)
    {
        checkState(Thread.currentThread() == lockHolder, message);
    }

    private class DriverLockResult
            implements AutoCloseable
    {
        private final boolean acquired;

        private DriverLockResult(int timeout, TimeUnit unit)
        {
            boolean acquired = false;
            try {
                acquired = exclusiveLock.tryLock(timeout, unit);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            this.acquired = acquired;

            if (acquired) {
                synchronized (Driver.this) {
                    lockHolder = Thread.currentThread();
                }
            }
        }

        public boolean wasAcquired()
        {
            return acquired;
        }

        @Override
        public void close()
        {
            if (!acquired) {
                return;
            }

            // before releasing the lock, process any new sources and/or destroy the driver
            try {
                try {
                    processNewSources();
                }
                finally {
                    destroyIfNecessary();
                }
            }
            finally {
                synchronized (Driver.this) {
                    lockHolder = null;
                }
                exclusiveLock.unlock();
            }
        }
    }
}
