package io.github.finbridge.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URL;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class PluginLifecycleCallbackExecutorTimeoutTest {
    @Test
    void callbackTimeoutQuarantinesUntilRunningCallbackCompletes() throws Exception {
        try (var executor = new PluginLifecycleCallbackExecutor(Duration.ofMillis(30));
             var loader = new java.net.URLClassLoader(new URL[0], getClass().getClassLoader())) {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            Thread releaser = Thread.ofPlatform().start(() -> {
                try {
                    if (entered.await(1, TimeUnit.SECONDS)) {
                        TimeUnit.MILLISECONDS.sleep(60);
                        release.countDown();
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            PluginLifecycleCallbackException timeout = assertThrows(PluginLifecycleCallbackException.class,
                    () -> executor.execute("initialize", loader, () -> {
                        entered.countDown();
                        awaitIgnoringInterrupt(release);
                    }));
            assertTrue(timeout.timedOut());
            assertFalse(executor.canCloseLoader(loader));
            releaser.join();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (executor.hasActiveCallback(loader) && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertFalse(executor.hasActiveCallback(loader));
            assertTrue(executor.canCloseLoader(loader));
        }
    }

    @Test
    void callbackTimeoutObservedAfterWorkerCompletionClosesLoader() throws Exception {
        try (var executor = new PluginLifecycleCallbackExecutor(Duration.ofSeconds(1));
             var loader = new java.net.URLClassLoader(new URL[0], getClass().getClassLoader())) {
            executor.quarantineTimedOutCallback(loader, new java.util.concurrent.atomic.AtomicInteger(3));
            assertTrue(executor.canCloseLoader(loader));
            assertFalse(executor.hasActiveCallback(loader));
        }
    }

    @Test
    void callbackExecutorTimeoutOfACompletedCallbackDoesNotLeaveAQuarantine() throws Exception {
        try (var executor = new PluginLifecycleCallbackExecutor(Duration.ofSeconds(1));
             var loader = new java.net.URLClassLoader(new URL[0], getClass().getClassLoader())) {
            executor.quarantineTimedOutCallback(loader, new java.util.concurrent.atomic.AtomicInteger(3));
            assertTrue(executor.canCloseLoader(loader));
        }
    }

    @Test
    void callbackExecutorTimeoutOfUnstartedCallbackAddsQuarantine() throws Exception {
        try (var executor = new PluginLifecycleCallbackExecutor(Duration.ofSeconds(1));
             var loader = new java.net.URLClassLoader(new URL[0], getClass().getClassLoader())) {
            executor.closeTimedOutLoader(loader);
            assertTrue(executor.canCloseLoader(loader));
        }
    }

    @Test
    void callbackLoaderCleanupHandlesCloseableAndNonCloseableLoaders() throws Exception {
        try (var executor = new PluginLifecycleCallbackExecutor(Duration.ofSeconds(1));
             var loader = new java.net.URLClassLoader(new URL[0], getClass().getClassLoader())) {
            executor.closeTimedOutLoader(loader);
            executor.closeTimedOutLoader(getClass().getClassLoader());
            assertTrue(executor.canCloseLoader(loader));
        }
    }

    @Test
    void callbackCloseCanCloseLoaderAfterTimedOutCallbackFinishes() throws Exception {
        try (var executor = new PluginLifecycleCallbackExecutor(Duration.ofMillis(30));
             var loader = new java.net.URLClassLoader(new URL[0], getClass().getClassLoader())) {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            Thread releaser = Thread.ofPlatform().start(() -> {
                try {
                    entered.await();
                    TimeUnit.MILLISECONDS.sleep(60);
                    release.countDown();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            assertThrows(PluginLifecycleCallbackException.class, () -> executor.execute("initialize", loader,
                    () -> {
                        entered.countDown();
                        awaitIgnoringInterrupt(release);
                    }));
            releaser.join();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (executor.hasActiveCallback(loader) && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertTrue(executor.canCloseLoader(loader));
            executor.execute("close", loader, () -> { });
            assertTrue(executor.canCloseLoader(loader));
        }
    }

    @Test
    void callbackScheduledButNotStartedByDeadlineDoesNotLeaveItsLoaderQuarantined() throws Exception {
        try (var executor = new PluginLifecycleCallbackExecutor(Duration.ofMillis(120));
             var loader = new java.net.URLClassLoader(new URL[0], getClass().getClassLoader())) {
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch entered = new CountDownLatch(2);
            CountDownLatch callbacksFinished = new CountDownLatch(2);
            Thread first = Thread.ofPlatform().start(() -> waitForCallbackDeadline(executor, loader,
                    release, entered, callbacksFinished));
            Thread second = Thread.ofPlatform().start(() -> waitForCallbackDeadline(executor, loader,
                    release, entered, callbacksFinished));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (executor.activeInitializationThreads() != 2 && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            org.junit.jupiter.api.Assertions.assertEquals(2, executor.activeInitializationThreads());
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            assertThrows(PluginLifecycleCallbackException.class,
                    () -> executor.execute("initialize", loader, () -> entered.countDown()));
            assertFalse(executor.canCloseLoader(loader));
            release.countDown();
            assertTrue(callbacksFinished.await(1, TimeUnit.SECONDS));
            first.join();
            second.join();
            assertTrue(executor.canCloseLoader(loader));
        }
    }

    private static void waitForCallbackDeadline(PluginLifecycleCallbackExecutor executor, ClassLoader loader,
            CountDownLatch release, CountDownLatch entered, CountDownLatch completed) {
        try {
            executor.execute("initialize", loader, () -> {
                entered.countDown();
                awaitIgnoringInterrupt(release);
            });
        } catch (PluginLifecycleCallbackException expected) {
            // Both callback workers are held until the third call has been rejected.
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
