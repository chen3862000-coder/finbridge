package io.github.finbridge.runtime;

import io.github.finbridge.spi.FinBridgePlugin;
import java.io.IOException;
import java.net.URLClassLoader;
import java.util.Objects;
import java.util.function.Function;

/** Owns a provider instance and its loader for one runtime handle. */
final class PluginLifecycleResources {
    private final FinBridgePlugin provider;
    private final URLClassLoader classLoader;
    private final PluginLifecycleCallbackExecutor callbacks;
    private final boolean ownsCallbacks;
    private boolean closed;

    PluginLifecycleResources(FinBridgePlugin provider, URLClassLoader classLoader,
            PluginLifecycleCallbackExecutor callbacks, boolean ownsCallbacks) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.classLoader = Objects.requireNonNull(classLoader, "classLoader");
        this.callbacks = Objects.requireNonNull(callbacks, "callbacks");
        this.ownsCallbacks = ownsCallbacks;
    }

    String classLoaderIdentity() {
        return classLoader.getClass().getName() + "@"
                + Integer.toHexString(System.identityHashCode(classLoader));
    }

    <T> T invoke(Function<FinBridgePlugin, T> operation) {
        Thread thread = Thread.currentThread();
        ClassLoader original = thread.getContextClassLoader();
        try {
            thread.setContextClassLoader(classLoader);
            return operation.apply(provider);
        } finally {
            thread.setContextClassLoader(original);
        }
    }

    synchronized void close() throws IOException {
        if (closed) {
            return;
        }
        try (LifecycleResourceOwner ignored = new LifecycleResourceOwner(callbacks, classLoader, ownsCallbacks)) {
            callbacks.execute("close", classLoader, provider::close);
            closed = true;
        } catch (RuntimeException failure) {
            throw new IOException("Plugin close hook failed", failure);
        }
    }

    private record LifecycleResourceOwner(PluginLifecycleCallbackExecutor callbacks,
                                          URLClassLoader classLoader, boolean ownsCallbacks)
            implements AutoCloseable {
        @Override
        public void close() throws IOException {
            if (ownsCallbacks) {
                callbacks.close();
            }
            if (callbacks.canCloseLoader(classLoader)) {
                classLoader.close();
            }
        }
    }
}
