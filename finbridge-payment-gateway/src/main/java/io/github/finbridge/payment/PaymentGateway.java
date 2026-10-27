package io.github.finbridge.payment;

import io.github.finbridge.runtime.InvocationKind;
import io.github.finbridge.runtime.PluginHandle;
import io.github.finbridge.runtime.PluginInvocationLease;
import io.github.finbridge.runtime.PluginRuntime;
import io.github.finbridge.spi.OperationContext;
import io.github.finbridge.spi.PluginKey;
import io.github.finbridge.spi.payment.DisbursementRequest;
import io.github.finbridge.spi.payment.PaymentProvider;
import io.github.finbridge.spi.payment.PaymentResult;
import io.github.finbridge.spi.payment.PaymentStatus;
import io.github.finbridge.spi.payment.PaymentTransactionRef;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class PaymentGateway {
    private final ExecutorService executor;
    private final Duration timeout;

    public PaymentGateway(ExecutorService executor, Duration timeout) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
    }

    public DisbursementOutcome disburse(PluginRuntime runtime, PluginKey owner,
            DisbursementRequest request, OperationContext context) {
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(context, "context");
        try {
            return invoke(runtime.plugin(owner), owner, request, context, false);
        } catch (IllegalArgumentException | IllegalStateException unavailable) {
            return DisbursementOutcome.unknown(owner, "PAYMENT_PROVIDER_UNAVAILABLE");
        }
    }

    public DisbursementOutcome reconcile(PluginRuntime runtime, PluginKey owner,
            UUID paymentTransactionId, String idempotencyKey, String providerTransactionId,
            OperationContext context) {
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(context, "context");
        PaymentTransactionRef transaction = providerTransactionId == null
                ? PaymentTransactionRef.byClientReference(paymentTransactionId, idempotencyKey)
                : new PaymentTransactionRef(providerTransactionId);
        try {
            return invoke(runtime.plugin(owner), owner, transaction, context, true);
        } catch (IllegalArgumentException | IllegalStateException unavailable) {
            return DisbursementOutcome.unknown(owner, "PAYMENT_OWNER_UNAVAILABLE");
        }
    }

    private DisbursementOutcome invoke(PluginHandle handle, PluginKey owner, Object request,
            OperationContext context, boolean reconciliation) {
        InvocationKind kind = reconciliation ? InvocationKind.RECONCILIATION : InvocationKind.BUSINESS;
        PluginInvocationLease lease;
        try {
            lease = handle.acquire(kind);
        } catch (IllegalStateException unavailable) {
            return DisbursementOutcome.unknown(owner, "PROVIDER_UNAVAILABLE");
        }
        AtomicInteger state = new AtomicInteger();
        FutureTask<DisbursementOutcome> task = new FutureTask<>(
                () -> invokeProvider(lease, owner, request, context, reconciliation)) {
            @Override
            public void run() {
                if (!state.compareAndSet(0, 1)) {
                    return;
                }
                super.run();
            }

            @Override
            protected void done() {
                if (isCancelled() && state.compareAndSet(0, 2)) {
                    lease.close();
                }
            }
        };
        try {
            executor.execute(task);
        } catch (RejectedExecutionException saturated) {
            lease.close();
            return DisbursementOutcome.unknown(owner, "PAYMENT_EXECUTOR_SATURATED");
        }
        try {
            return task.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException timedOut) {
            task.cancel(true);
            return DisbursementOutcome.unknown(owner, "PAYMENT_PROVIDER_TIMEOUT");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            task.cancel(true);
            return DisbursementOutcome.unknown(owner, "PAYMENT_CALL_INTERRUPTED");
        } catch (ExecutionException failed) {
            return DisbursementOutcome.unknown(owner, "PAYMENT_PROVIDER_FAILURE");
        }
    }

    private static DisbursementOutcome invokeProvider(PluginInvocationLease lease, PluginKey owner,
            Object request, OperationContext context, boolean reconciliation) {
        try (lease) {
            if (reconciliation) {
                PaymentResult result = lease.invoke(provider -> ((PaymentProvider) provider)
                        .queryStatus((PaymentTransactionRef) request, context));
                return DisbursementOutcome.fromResult(owner, result);
            }
            PaymentResult result = lease.invoke(provider -> ((PaymentProvider) provider)
                    .disburse((DisbursementRequest) request, context));
            return DisbursementOutcome.fromResult(owner, result);
        }
    }

    public record DisbursementOutcome(PluginKey owner, PaymentStatus status,
                                      String providerTransactionId, String diagnosticCode) {
        public DisbursementOutcome {
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(status, "status");
        }

        private static DisbursementOutcome fromResult(PluginKey owner, PaymentResult result) {
            if (result == null) {
                return unknown(owner, "INVALID_PROVIDER_RESPONSE");
            }
            return fromStatus(owner, result.status(), result.providerTransactionId(),
                    result.providerCode() == null ? result.errorCategory() == null
                            ? null : result.errorCategory().name() : result.providerCode());
        }

        private static DisbursementOutcome fromStatus(PluginKey owner, PaymentStatus status,
                String providerTransactionId, String diagnostic) {
            return new DisbursementOutcome(owner, status, providerTransactionId, diagnostic);
        }

        private static DisbursementOutcome unknown(PluginKey owner, String diagnostic) {
            return new DisbursementOutcome(owner, PaymentStatus.UNKNOWN, null, diagnostic);
        }
    }
}
