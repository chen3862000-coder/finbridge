package io.github.finbridge.risk;

import io.github.finbridge.runtime.InvocationKind;
import io.github.finbridge.runtime.PluginInvocationLease;
import io.github.finbridge.runtime.PluginOperationMetrics;
import io.github.finbridge.runtime.PluginRuntime;
import io.github.finbridge.spi.Capability;
import io.github.finbridge.spi.OperationContext;
import io.github.finbridge.spi.risk.RiskDataProvider;
import io.github.finbridge.spi.risk.RiskDataRequest;
import io.github.finbridge.spi.risk.RiskDataResult;
import io.github.finbridge.spi.risk.RiskDataType;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class RiskDataGateway {
    private final PluginRuntime runtime;
    private final ExecutorService executor;
    private final Duration timeout;
    private final PluginOperationMetrics metrics;

    public RiskDataGateway(PluginRuntime runtime, ExecutorService executor, Duration timeout) {
        this(runtime, executor, timeout, null);
    }

    public RiskDataGateway(PluginRuntime runtime, ExecutorService executor, Duration timeout,
            PluginOperationMetrics metrics) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.metrics = metrics;
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
    }

    public Map<RiskDataType, RiskProviderOutcome> collect(UUID tenantId, UUID assessmentId,
                                                           String syntheticApplicantId,
                                                           String correlationId) {
        Map<RiskDataType, RiskProviderOutcome> outcomes = new EnumMap<>(RiskDataType.class);
        for (RiskDataType type : RiskDataType.values()) {
            UUID executionId = UUID.randomUUID();
            RiskDataRequest request = new RiskDataRequest(assessmentId, tenantId, syntheticApplicantId);
            OperationContext context = new OperationContext(executionId, correlationId,
                    java.time.Instant.now().plus(timeout));
            outcomes.put(type, query(tenantId, type, request, context));
        }
        return Map.copyOf(outcomes);
    }

    public RiskProviderOutcome query(UUID tenantId, RiskDataType type, RiskDataRequest request,
                                     OperationContext context) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(context, "context");
        return invokeRequest(tenantId, type, request, context);
    }

    private RiskProviderOutcome invokeRequest(UUID tenantId, RiskDataType type,
                                              RiskDataRequest request, OperationContext operation) {
        UUID executionId = operation.executionId();
        PluginInvocationLease lease;
        try {
            lease = runtime.route(tenantId, capability(type), InvocationKind.BUSINESS);
        } catch (IllegalArgumentException | IllegalStateException missingBinding) {
            return RiskProviderOutcome.failure(executionId, null, "CAPABILITY_UNAVAILABLE",
                    Duration.ZERO);
        }
        RiskProviderOutcome outcome = invokeWithLease(lease, type, request, operation, executionId);
        if (metrics != null) {
            PluginOperationMetrics.Outcome metricOutcome = outcome.result() != null
                    ? PluginOperationMetrics.Outcome.SUCCESS : metricOutcome(outcome.failureCode());
            metrics.record(PluginOperationMetrics.Operation.RISK_QUERY, metricOutcome, outcome.duration());
        }
        return outcome;
    }

    static PluginOperationMetrics.Outcome metricOutcome(String code) {
        if (code != null && code.contains("TIMEOUT")) {
            return PluginOperationMetrics.Outcome.TIMEOUT;
        }
        if (code != null && code.contains("SATURATED")) {
            return PluginOperationMetrics.Outcome.REJECTED;
        }
        return PluginOperationMetrics.Outcome.FAILURE;
    }

    private RiskProviderOutcome invokeWithLease(PluginInvocationLease lease, RiskDataType type,
            RiskDataRequest request, OperationContext operation, UUID executionId) {
        var pluginKey = lease.handle().key();
        long startedAt = System.nanoTime();
        FutureTask<RiskDataResult> call = providerCall(lease, request, operation);
        try {
            executor.execute(call);
        } catch (RejectedExecutionException rejected) {
            lease.close();
            return failure(executionId, pluginKey, "RISK_EXECUTOR_SATURATED", startedAt);
        }
        try {
            RiskDataResult result = call.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
            if (result == null || result.dataType() != type) {
                return failure(executionId, pluginKey, "INVALID_PROVIDER_RESPONSE", startedAt);
            }
            return RiskProviderOutcome.success(executionId, pluginKey, result,
                    Duration.ofNanos(System.nanoTime() - startedAt));
        } catch (TimeoutException timedOut) {
            call.cancel(true);
            return failure(executionId, pluginKey, "PROVIDER_TIMEOUT", startedAt);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            call.cancel(true);
            return failure(executionId, pluginKey, "CALL_INTERRUPTED", startedAt);
        } catch (ExecutionException providerFailure) {
            return failure(executionId, pluginKey, "PROVIDER_FAILURE", startedAt);
        }
    }

    private static FutureTask<RiskDataResult> providerCall(PluginInvocationLease lease,
            RiskDataRequest request, OperationContext operation) {
        AtomicBoolean started = new AtomicBoolean();
        return new FutureTask<>(() -> {
            started.set(true);
            try (lease) {
                return lease.invoke(provider -> ((RiskDataProvider) provider).query(request, operation));
            }
        }) {
            @Override
            protected void done() {
                if (isCancelled() && !started.get()) {
                    lease.close();
                }
            }

        };
    }

    private static RiskProviderOutcome failure(UUID executionId,
            io.github.finbridge.spi.PluginKey pluginKey, String code, long startedAt) {
        return RiskProviderOutcome.failure(executionId, pluginKey, code,
                Duration.ofNanos(Math.max(0, System.nanoTime() - startedAt)));
    }

    private static Capability capability(RiskDataType type) {
        return switch (type) {
            case CREDIT_SCORE -> Capability.CREDIT_SCORE;
            case BLACKLIST -> Capability.BLACKLIST;
            case FRAUD_SIGNAL -> Capability.FRAUD_SIGNAL;
        };
    }
}
