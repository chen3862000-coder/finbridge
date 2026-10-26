package io.github.finbridge.runtime;

import io.github.finbridge.spi.FinBridgePlugin;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

public final class PluginInvocationLease implements AutoCloseable {
    private final PluginHandle handle;
    private final AtomicBoolean closed = new AtomicBoolean();

    PluginInvocationLease(PluginHandle handle) {
        this.handle = handle;
    }

    public PluginHandle handle() {
        return handle;
    }

    public <T> T invoke(Function<FinBridgePlugin, T> invocation) {
        return handle.invoke(invocation);
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            handle.releaseInvocation();
        }
    }
}
