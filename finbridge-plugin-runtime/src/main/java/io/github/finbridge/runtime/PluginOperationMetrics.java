package io.github.finbridge.runtime;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Objects;

public final class PluginOperationMetrics {
    private final MeterRegistry registry;

    public PluginOperationMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    public void record(Operation operation, Outcome outcome, Duration duration) {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(duration, "duration");
        Duration nonNegativeDuration = duration.isNegative() ? Duration.ZERO : duration;
        registry.counter("finbridge.plugin.operations", "operation", operation.metricName(),
                "outcome", outcome.metricName()).increment();
        Timer.builder("finbridge.plugin.operation.duration")
                .tag("operation", operation.metricName())
                .tag("outcome", outcome.metricName())
                .register(registry)
                .record(nonNegativeDuration);
    }

    public enum Operation {
        RISK_QUERY("risk_query"), PAYMENT_DISBURSE("payment_disburse"),
        PAYMENT_RECONCILE("payment_reconcile");

        private final String metricName;

        Operation(String metricName) {
            this.metricName = metricName;
        }

        String metricName() {
            return metricName;
        }
    }

    public enum Outcome {
        SUCCESS("success"), FAILURE("failure"), PROCESSING("processing"),
        UNKNOWN("unknown"), TIMEOUT("timeout"), REJECTED("rejected");

        private final String metricName;

        Outcome(String metricName) {
            this.metricName = metricName;
        }

        String metricName() {
            return metricName;
        }
    }
}
