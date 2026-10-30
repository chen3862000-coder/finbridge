package io.github.finbridge.runtime;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Runs plugin init/health/close hooks on isolated, bounded daemon executors. */
final class PluginLifecycleCallbackExecutor implements AutoCloseable {
    private static final int CALLBACK_THREADS = 2;
    private final Duration timeout;
    private final ThreadPoolExecutor initializationCallbacks;
    private final ThreadPoolExecutor closeCallbacks;
    private final java.util.Set<ClassLoader> quarantinedLoaders =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.Set<ClassLoader> activeLoaders = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();
    private final Object submissionLock = new Object();

    PluginLifecycleCallbackExecutor(Duration timeout) {
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("Lifecycle callback timeout must be positive");
        }
        initializationCallbacks = callbackPool("init-health");
        closeCallbacks = callbackPool("close");
    }

    void execute(String callbackName, ClassLoader pluginLoader, Runnable callback) {
        Objects.requireNonNull(callbackName, "callbackName");
        Objects.requireNonNull(pluginLoader, "pluginLoader");
        Objects.requireNonNull(callback, "callback");
        Thread caller = Thread.currentThread();
        ClassLoader callerLoader = caller.getContextClassLoader();
        caller.setContextClassLoader(pluginLoader);
        try {
            executeWithContext(callbackName, pluginLoader, callback);
        } finally {
            caller.setContextClassLoader(callerLoader);
        }
    }

    private void executeWithContext(String callbackName, ClassLoader pluginLoader, Runnable callback) {
        java.util.concurrent.atomic.AtomicInteger callbackState = new java.util.concurrent.atomic.AtomicInteger();
        CompletableFuture<Void> completion = submit(callbackName, pluginLoader, callback, callbackState);
        await(callbackName, pluginLoader, completion, callbackState);
    }

    private CompletableFuture<Void> submit(String callbackName, ClassLoader pluginLoader, Runnable callback,
                                           java.util.concurrent.atomic.AtomicInteger callbackState) {
        CompletableFuture<Void> completion = new CompletableFuture<>();
        try {
            synchronized (submissionLock) {
                if (closed.get()) {
                    throw new PluginLifecycleCallbackException("Plugin lifecycle runtime is closed", null, false,
                            "PLUGIN_LIFECYCLE_RUNTIME_CLOSED");
                }
                executorFor(callbackName).execute(() -> {
                if (!callbackState.compareAndSet(0, 1)) {
                    closeLoaderAfterTimedOutCallback(pluginLoader);
                    quarantinedLoaders.remove(pluginLoader);
                    return;
                }
                activeLoaders.add(pluginLoader);
                try {
                    withPluginContextLoader(pluginLoader, callback);
                    completion.complete(null);
                } catch (RuntimeException | Error failure) {
                    completion.completeExceptionally(failure);
                } finally {
                    int finalState = callbackState.getAndSet(3);
                    if (finalState == 2) {
                        closeLoaderAfterTimedOutCallback(pluginLoader);
                        quarantinedLoaders.remove(pluginLoader);
                    } else if (finalState == 0) {
                        completion.completeExceptionally(new IllegalStateException(
                                "Lifecycle callback ended after the caller deadline"));
                    }
                    activeLoaders.remove(pluginLoader);
                }
                });
            }
        } catch (RejectedExecutionException saturated) {
            throw new PluginLifecycleCallbackException(
                    "Plugin lifecycle callback executor is saturated", saturated, false,
                    "PLUGIN_LIFECYCLE_EXECUTOR_SATURATED");
        } catch (PluginLifecycleCallbackException closedRuntime) {
            throw closedRuntime;
        } catch (RuntimeException submissionFailure) {
            throw new PluginLifecycleCallbackException("Plugin lifecycle callback submission failed",
                    submissionFailure, false, "PLUGIN_LIFECYCLE_SUBMISSION_FAILED");
        }
        return completion;
    }

    private void await(String callbackName, ClassLoader pluginLoader, CompletableFuture<Void> completion,
                       java.util.concurrent.atomic.AtomicInteger callbackState) {
        try {
            completion.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (java.util.concurrent.TimeoutException timeoutFailure) {
            quarantineTimedOutCallback(pluginLoader, callbackState);
            throw new PluginLifecycleCallbackException(
                    "Plugin lifecycle callback timed out: " + callbackName, timeoutFailure, true,
                    "PLUGIN_LIFECYCLE_CALLBACK_TIMED_OUT");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new PluginLifecycleCallbackException(
                    "Interrupted while running plugin lifecycle callback: " + callbackName,
                    interrupted, false, "PLUGIN_LIFECYCLE_CALLBACK_INTERRUPTED");
        } catch (java.util.concurrent.ExecutionException failed) {
            throw callbackFailure(callbackName, failed);
        }
    }

    private static PluginLifecycleCallbackException callbackFailure(String callbackName,
            java.util.concurrent.ExecutionException failure) {
        Throwable cause = failure.getCause();
        return new PluginLifecycleCallbackException("Plugin lifecycle callback failed: " + callbackName,
                cause, false, "PLUGIN_LIFECYCLE_CALLBACK_FAILED");
    }

    void quarantineTimedOutCallback(ClassLoader pluginLoader,
            java.util.concurrent.atomic.AtomicInteger callbackState) {
        int previousState = callbackState.getAndSet(2);
        if (previousState == 3) {
            closeLoaderAfterTimedOutCallback(pluginLoader);
            return;
        }
        quarantinedLoaders.add(pluginLoader);
    }

    private static void closeLoaderAfterTimedOutCallback(ClassLoader pluginLoader) {
        if (pluginLoader instanceof java.net.URLClassLoader urlClassLoader) {
            try {
                urlClassLoader.close();
            } catch (java.io.IOException ignored) {
                // The callback has returned; best-effort closure cannot change its timeout result.
            }
        }
    }

    void closeTimedOutLoader(ClassLoader pluginLoader) {
        closeLoaderAfterTimedOutCallback(pluginLoader);
    }

    private ThreadPoolExecutor executorFor(String callbackName) {
        return "close".equals(callbackName) || "failed-load close".equals(callbackName)
                ? closeCallbacks : initializationCallbacks;
    }

    boolean isClosed() {
        return closed.get();
    }

    int activeInitializationThreads() {
        return initializationCallbacks.getActiveCount();
    }

    boolean hasActiveCallback(ClassLoader pluginLoader) {
        return activeLoaders.contains(pluginLoader);
    }

    boolean canCloseLoader(ClassLoader pluginLoader) {
        return !hasActiveCallback(pluginLoader);
    }

    void releaseQuarantine(ClassLoader pluginLoader) {
        quarantinedLoaders.remove(pluginLoader);
    }

    private static ThreadPoolExecutor callbackPool(String poolName) {
        return new ThreadPoolExecutor(CALLBACK_THREADS, CALLBACK_THREADS, 0, TimeUnit.MILLISECONDS,
                new SynchronousQueue<>(), callbackThreads(poolName),
                new ThreadPoolExecutor.AbortPolicy());
    }

    private static void withPluginContextLoader(ClassLoader pluginLoader, Runnable callback) {
        Thread thread = Thread.currentThread();
        ClassLoader original = thread.getContextClassLoader();
        try {
            thread.setContextClassLoader(pluginLoader);
            callback.run();
        } finally {
            thread.setContextClassLoader(original);
        }
    }

    private static ThreadFactory callbackThreads(String poolName) {
        java.util.concurrent.atomic.AtomicInteger sequence = new java.util.concurrent.atomic.AtomicInteger();
        return task -> {
            Thread thread = new Thread(task,
                    "finbridge-plugin-lifecycle-" + poolName + "-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    @Override
    public void close() {
        synchronized (submissionLock) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            initializationCallbacks.shutdownNow();
            closeCallbacks.shutdownNow();
        }
    }
}
