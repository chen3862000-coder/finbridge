package io.github.finbridge.risk;

import io.github.finbridge.spi.PluginKey;
import io.github.finbridge.spi.risk.RiskDataResult;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

public record RiskProviderOutcome(UUID executionId, PluginKey pluginKey, RiskDataResult result,
                                  Duration duration, String failureCode) {
    public RiskProviderOutcome {
        Objects.requireNonNull(executionId, "executionId");
        Objects.requireNonNull(duration, "duration");
        if (result == null && (failureCode == null || failureCode.isBlank())) {
            throw new IllegalArgumentException("Provider result or failure code is required");
        }
        if (result != null && failureCode != null) {
            throw new IllegalArgumentException("Provider result and failure code are mutually exclusive");
        }
    }

    public static RiskProviderOutcome success(UUID executionId, PluginKey pluginKey,
            RiskDataResult result, Duration duration) {
        return new RiskProviderOutcome(executionId, pluginKey, result, duration, null);
    }

    public static RiskProviderOutcome failure(UUID executionId, PluginKey pluginKey,
            String failureCode, Duration duration) {
        return new RiskProviderOutcome(executionId, pluginKey, null, duration, failureCode);
    }
}
