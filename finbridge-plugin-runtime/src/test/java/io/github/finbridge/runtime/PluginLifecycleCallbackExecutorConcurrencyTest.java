package io.github.finbridge.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URL;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class PluginLifecycleCallbackExecutorConcurrencyTest {
    @Test
    void callbackExecutorUsesSeparateBoundedPoolsAndRejectsExcessConcurrency() throws Exception {
        try (var executor = new PluginLifecycleCallbackExecutor(Duration.ofMillis(150));
             var initLoader = new java.net.URLClassLoader(new URL[0], getClass().getClassLoader());
             var closeLoader = new java.net.URLClassLoader(new URL[0], getClass().getClassLoader())) {
            CountDownLatch initEntered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            Thread init = Thread.ofPlatform().start(() -> {
                try {
                    executor.execute("initialize", initLoader, () -> {
                        initEntered.countDown();
                        awaitIgnoringInterrupt(release);
                    });
                } catch (PluginLifecycleCallbackException expected) {
                    // The callback deadline bounds this uncooperative fixture.
                }
            });
            assertTrue(initEntered.await(1, TimeUnit.SECONDS));
            CountDownLatch closeCompleted = new CountDownLatch(1);
            executor.execute("close", closeLoader, closeCompleted::countDown);
            assertTrue(closeCompleted.await(100, TimeUnit.MILLISECONDS));
            release.countDown();
            init.join();
            assertFalse(init.isAlive());
        }
    }

    @Test
    void callbackExecutorSaturationIsRejectedWithoutGrowingTheWorkerPool() throws Exception {
        try (var executor = new PluginLifecycleCallbackExecutor(Duration.ofMillis(120));
             var loader = new java.net.URLClassLoader(new URL[0], getClass().getClassLoader())) {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch completed = new CountDownLatch(2);
            Thread first = Thread.ofPlatform().start(() -> blockUntilDeadline(executor, loader,
                    entered, release, ready, completed));
            Thread second = Thread.ofPlatform().start(() -> blockUntilDeadline(executor, loader,
                    entered, release, ready, completed));
            assertTrue(ready.await(1, TimeUnit.SECONDS));
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (executor.activeInitializationThreads() != 2 && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertEquals(2, executor.activeInitializationThreads());
            PluginLifecycleCallbackException rejection = assertThrows(PluginLifecycleCallbackException.class,
                    () -> executor.execute("initialize", loader, () -> { }));
            assertEquals("PLUGIN_LIFECYCLE_EXECUTOR_SATURATED", rejection.diagnosticCode());
            release.countDown();
            assertTrue(completed.await(1, TimeUnit.SECONDS));
            first.join();
            second.join();
            assertFalse(first.isAlive());
            assertFalse(second.isAlive());
        }
    }

    @Test
    void callbackExecutorReportsDiagnosticWhenSubmissionAfterShutdownIsRejected() throws Exception {
        var executor = new PluginLifecycleCallbackExecutor(Duration.ofMillis(100));
        try (var loader = new java.net.URLClassLoader(new URL[0], getClass().getClassLoader())) {
            executor.close();
            PluginLifecycleCallbackException failure = assertThrows(PluginLifecycleCallbackException.class,
                    () -> executor.execute("initialize", loader, () -> { }));
            assertEquals("PLUGIN_LIFECYCLE_RUNTIME_CLOSED", failure.diagnosticCode());
        }
    }

    @Test
    void callbackExecutorClosedStateIsObservable() {
        var executor = new PluginLifecycleCallbackExecutor(Duration.ofMillis(100));
        assertFalse(executor.isClosed());
        executor.close();
        assertTrue(executor.isClosed());
    }

    @Test
    void keepsCloseCallbacksAvailableWhileInitCallbacksAreStalled() throws Exception {
        try (var executor = new PluginLifecycleCallbackExecutor(Duration.ofMillis(100));
             var initLoader = new java.net.URLClassLoader(new URL[0], getClass().getClassLoader());
             var closeLoader = new java.net.URLClassLoader(new URL[0], getClass().getClassLoader())) {
            CountDownLatch initEntered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch finished = new CountDownLatch(1);
            Thread init = Thread.ofPlatform().start(() -> {
                try {
                    executor.execute("initialize", initLoader, () -> {
                        initEntered.countDown();
                        awaitIgnoringInterrupt(release);
                    });
                } catch (PluginLifecycleCallbackException expected) {
                    // This test intentionally stalls init until its deadline.
                } finally {
                    finished.countDown();
                }
            });
            assertTrue(initEntered.await(1, TimeUnit.SECONDS));
            CountDownLatch closeCompleted = new CountDownLatch(1);
            executor.execute("close", closeLoader, closeCompleted::countDown);
            assertTrue(closeCompleted.await(100, TimeUnit.MILLISECONDS));
            release.countDown();
            assertTrue(finished.await(1, TimeUnit.SECONDS));
            init.join();
            assertFalse(init.isAlive());
        }
    }

    private static void blockUntilDeadline(PluginLifecycleCallbackExecutor executor, ClassLoader loader,
            CountDownLatch entered, CountDownLatch release, CountDownLatch ready, CountDownLatch completed) {
        ready.countDown();
        try {
            executor.execute("initialize", loader, () -> {
                entered.countDown();
                awaitIgnoringInterrupt(release);
            });
        } catch (PluginLifecycleCallbackException expected) {
            // Both workers are expected to reach their configured callback deadlines.
        } finally {
            completed.countDown();
        }
    }

    private static void awaitIgnoringInterrupt(CountDownLatch release) {
        boolean waiting = true;
        while (waiting) {
            try {
                release.await();
                waiting = false;
            } catch (InterruptedException ignored) {
                // This fixture models a trusted callback that ignores interruption.
            }
        }
    }
}
