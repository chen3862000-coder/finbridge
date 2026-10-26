package io.github.finbridge.runtime;

import io.github.finbridge.spi.FinBridgePlugin;
import io.github.finbridge.spi.PluginDescriptor;
import io.github.finbridge.spi.PluginKey;
import java.io.IOException;
import java.net.URLClassLoader;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

public final class PluginHandle {
    private final PluginDescriptor descriptor;
    private final String sha256;
    private final FinBridgePlugin provider;
    private final URLClassLoader classLoader;
    private final List<PluginLifecycleEvent> events = new ArrayList<>();
    private PluginLifecycleState state = PluginLifecycleState.LOADED;
    private int activeInvocations;

    PluginHandle(PluginDescriptor descriptor, String sha256, FinBridgePlugin provider,
                 URLClassLoader classLoader) {
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
        this.sha256 = Objects.requireNonNull(sha256, "sha256");
        this.provider = Objects.requireNonNull(provider, "provider");
        this.classLoader = Objects.requireNonNull(classLoader, "classLoader");
        record(PluginLifecycleState.DISCOVERED, "ARTIFACT_DISCOVERED");
        record(PluginLifecycleState.VALIDATED, "ARTIFACT_VALIDATED");
        record(PluginLifecycleState.LOADED, "PLUGIN_LOADED");
    }

    public PluginDescriptor descriptor() {
        return descriptor;
    }

    public PluginKey key() {
        return PluginKey.from(descriptor);
    }

    public String sha256() {
        return sha256;
    }

    public String classLoaderIdentity() {
        return classLoader.getClass().getName() + "@"
                + Integer.toHexString(System.identityHashCode(classLoader));
    }

    public synchronized PluginLifecycleState state() {
        return state;
    }

    public synchronized int activeInvocations() {
        return activeInvocations;
    }

    public synchronized List<PluginLifecycleEvent> lifecycleEvents() {
        return List.copyOf(events);
    }

    public synchronized void activate() {
        requireState(PluginLifecycleState.LOADED);
        transition(PluginLifecycleState.ACTIVE, "PLUGIN_ACTIVATED");
    }

    public synchronized void beginDrain() {
        requireState(PluginLifecycleState.ACTIVE);
        transition(PluginLifecycleState.DRAINING, "PLUGIN_DRAINING");
    }

    public synchronized PluginInvocationLease acquire(InvocationKind kind) {
        Objects.requireNonNull(kind, "kind");
        boolean eligible = state == PluginLifecycleState.ACTIVE
                || (state == PluginLifecycleState.DRAINING
                && kind == InvocationKind.RECONCILIATION);
        if (!eligible) {
            throw new IllegalStateException("Plugin is not eligible for " + kind + ": " + state);
        }
        activeInvocations++;
        return new PluginInvocationLease(this);
    }

    public synchronized boolean awaitDrained(Duration timeout) throws InterruptedException {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must not be negative");
        }
        long remaining = timeout.toNanos();
        long deadline = System.nanoTime() + remaining;
        while (activeInvocations > 0 && remaining > 0) {
            TimeUnit.NANOSECONDS.timedWait(this, remaining);
            remaining = deadline - System.nanoTime();
        }
        return activeInvocations == 0;
    }

    synchronized void releaseInvocation() {
        if (activeInvocations <= 0) {
            throw new IllegalStateException("Invocation lease accounting underflow");
        }
        activeInvocations--;
        if (activeInvocations == 0) {
            notifyAll();
        }
    }

    synchronized void unload() throws IOException {
        requireState(PluginLifecycleState.DRAINING);
        if (activeInvocations != 0) {
            throw new IllegalStateException("Cannot unload while invocations remain");
        }
        closeProviderAndLoader();
        transition(PluginLifecycleState.UNLOADED, "PLUGIN_UNLOADED");
    }

    <T> T invoke(Function<FinBridgePlugin, T> invocation) {
        Objects.requireNonNull(invocation, "invocation");
        return withPluginContextClassLoader(() -> invocation.apply(provider));
    }

    private void requireState(PluginLifecycleState expected) {
        if (state != expected) {
            throw new IllegalStateException("Expected " + expected + " but was " + state);
        }
    }

    private void transition(PluginLifecycleState next, String diagnostic) {
        state = next;
        record(next, diagnostic);
    }

    private void record(PluginLifecycleState lifecycleState, String diagnostic) {
        events.add(new PluginLifecycleEvent(key(), lifecycleState, Instant.now(), diagnostic));
    }

    private void closeProviderAndLoader() throws IOException {
        Thread thread = Thread.currentThread();
        ClassLoader originalLoader = thread.getContextClassLoader();
        try (URLClassLoader ignoredLoader = classLoader;
             FinBridgePlugin closingProvider = provider) {
            thread.setContextClassLoader(classLoader);
            closingProvider.close();
        } catch (RuntimeException failure) {
            state = PluginLifecycleState.FAILED;
            record(PluginLifecycleState.FAILED, "PLUGIN_CLOSE_FAILED");
            throw new IOException("Plugin close hook failed", failure);
        } finally {
            thread.setContextClassLoader(originalLoader);
        }
    }

    private <T> T withPluginContextClassLoader(PluginCall<T> action) {
        Thread thread = Thread.currentThread();
        ClassLoader original = thread.getContextClassLoader();
        try {
            thread.setContextClassLoader(classLoader);
            return action.call();
        } finally {
            thread.setContextClassLoader(original);
        }
    }

    @FunctionalInterface
    interface PluginCall<T> {
        T call();
    }

}
