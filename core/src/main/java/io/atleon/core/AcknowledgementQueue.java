package io.atleon.core;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A thread safe Queue whose extensions manage the order-of-completion of In-Flight
 * Acknowledgements, and where the execution of those Acknowledgements is also thread safe
 */
abstract class AcknowledgementQueue {

    private static final AtomicIntegerFieldUpdater<AcknowledgementQueue> DRAINS_IN_PROGRESS =
        AtomicIntegerFieldUpdater.newUpdater(AcknowledgementQueue.class, "drainsInProgress");

    protected final Queue<InFlight> queue = new ConcurrentLinkedQueue<>();

    private volatile int drainsInProgress;

    /**
     * Append an In-Flight Acknowledgement to the Queue backed by the following Acknowledger and
     * Nacknowledger
     *
     * @return The In-Flight Acknowledgement to be completed on this Queue in the Future
     */
    public InFlight add(Runnable acknowledger, Consumer<? super Throwable> nacknowledger) {
        InFlight inFlight = new InFlight(acknowledger, nacknowledger);
        queue.add(inFlight);
        return inFlight;
    }

    /**
     * Complete an In-Flight Acknowledgement in this Queue
     *
     * @return The number of elements drained from this Queue due to completion of Acknowledgement
     */
    public long complete(InFlight toComplete) {
        return complete(toComplete, InFlight::complete) ? drain() : 0L;
    }

    /**
     * Exceptionally complete an In-Flight Acknowledgement in this Queue
     *
     * @return The number of elements drained from this Queue due to completion of Acknowledgement
     */
    public long completeExceptionally(InFlight toComplete, Throwable error) {
        return complete(toComplete, inFlight -> inFlight.completeExceptionally(error)) ? drain() : 0L;
    }

    protected abstract boolean complete(InFlight inFlight, Function<InFlight, Boolean> completer);

    private long drain() {
        if (DRAINS_IN_PROGRESS.getAndIncrement(this) != 0) {
            return 0L;
        }

        long drained = 0L;
        int missed = 1;
        do {
            while (!queue.isEmpty() && !queue.peek().isInProcess()) {
                queue.remove().execute();
                drained++;
            }

            missed = DRAINS_IN_PROGRESS.addAndGet(this, -missed);
        } while (missed != 0);

        return drained;
    }

    static final class InFlight {

        private enum State {IN_PROCESS, COMPLETED, EXECUTED}

        private static final AtomicReferenceFieldUpdater<InFlight, State> STATE =
            AtomicReferenceFieldUpdater.newUpdater(InFlight.class, State.class, "state");

        private static final AtomicReferenceFieldUpdater<InFlight, Throwable> ERROR =
            AtomicReferenceFieldUpdater.newUpdater(InFlight.class, Throwable.class, "error");

        private final Runnable acknowledger;

        private final Consumer<? super Throwable> nacknowledger;

        private volatile State state = State.IN_PROCESS;

        private volatile Throwable error;

        private InFlight(Runnable acknowledger, Consumer<? super Throwable> nacknowledger) {
            this.acknowledger = acknowledger;
            this.nacknowledger = nacknowledger;
        }

        boolean isInProcess() {
            return state == State.IN_PROCESS;
        }

        private boolean completeExceptionally(Throwable error) {
            return ERROR.compareAndSet(this, null, error) && complete();
        }

        private boolean complete() {
            return STATE.compareAndSet(this, State.IN_PROCESS, State.COMPLETED);
        }

        private void execute() {
            if (STATE.getAndSet(this, State.EXECUTED) != State.EXECUTED) {
                executeAcknowledgement();
            }
        }

        private void executeAcknowledgement() {
            if (error == null) {
                acknowledger.run();
            } else {
                nacknowledger.accept(error);
            }
        }
    }
}
