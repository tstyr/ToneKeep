package jp.tonekeep.app;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;
import static org.junit.Assert.*;

public class JobControlTest {
    @Test public void frequentAudioChecksOnlyReadPersistentStateFourTimesPerSecond() {
        AtomicLong clock = new AtomicLong(); AtomicInteger reads = new AtomicInteger();
        JobControl control = new JobControl(() -> { reads.incrementAndGet(); return true; }, clock::get);
        for (int i = 0; i < 10000; i++) { clock.set(i); assertTrue(control.getAsBoolean()); }
        assertEquals(40, reads.get());
        control.cancel(); assertFalse(control.getAsBoolean()); assertEquals(40, reads.get());
    }
    @Test public void cancellationByAnotherStoreClientIsDetectedWithinThePollingInterval() {
        AtomicLong clock = new AtomicLong(); AtomicBoolean active = new AtomicBoolean(true);
        JobControl control = new JobControl(active::get, clock::get);
        assertTrue(control.getAsBoolean()); active.set(false); clock.set(249); assertTrue(control.getAsBoolean());
        clock.set(250); assertFalse(control.getAsBoolean());
        active.set(true); clock.set(500); assertFalse(control.getAsBoolean());
    }
    @Test public void interruptedWorkerStopsWithoutReadingTheDatabase() {
        JobControl control = new JobControl(() -> { throw new AssertionError("Unexpected database read"); }, () -> 0);
        Thread.currentThread().interrupt();
        try { assertFalse(control.getAsBoolean()); } finally { Thread.interrupted(); }
    }
}
