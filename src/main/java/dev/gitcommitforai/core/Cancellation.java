package dev.gitcommitforai.core;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

public final class Cancellation {
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final BooleanSupplier external;
    public Cancellation() { this(() -> false); }
    public Cancellation(BooleanSupplier external) { this.external = external; }
    public void cancel() { cancelled.set(true); }
    public void check() { if (cancelled.get() || external.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new CancellationException(); }
}

