package jp.tonekeep.app;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** Explicit cancellation is immediate; changes made by other store clients are polled at most 4 Hz. */
final class JobControl implements BooleanSupplier {
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final BooleanSupplier persistedActive;
    private final LongSupplier clock;
    private long checkedAt;
    private boolean checked;
    JobControl(BooleanSupplier persistedActive, LongSupplier clock) { this.persistedActive = persistedActive; this.clock = clock; }
    void cancel() { cancelled.set(true); }
    @Override public boolean getAsBoolean() {
        if (cancelled.get() || Thread.currentThread().isInterrupted()) return false;
        long now = clock.getAsLong();
        if (!checked || now - checkedAt >= 250) {
            checkedAt = now; checked = true;
            if (!persistedActive.getAsBoolean()) cancelled.set(true);
        }
        return !cancelled.get();
    }
}
