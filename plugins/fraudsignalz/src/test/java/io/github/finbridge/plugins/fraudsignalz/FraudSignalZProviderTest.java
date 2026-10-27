package io.github.finbridge.plugins.fraudsignalz;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.finbridge.spi.OperationContext;
import io.github.finbridge.spi.risk.FraudSignalValue;
import io.github.finbridge.spi.risk.RiskDataRequest;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FraudSignalZProviderTest {
    @Test
    void returnsSyntheticCriticalOrClearSignal() {
        FraudSignalZProvider provider = new FraudSignalZProvider();
        assertEquals(FraudSignalValue.Severity.CRITICAL,
                ((FraudSignalValue) provider.query(request("demo-critical-fraud"), context())
                        .value()).severity());
        assertEquals(FraudSignalValue.Severity.NONE,
                ((FraudSignalValue) provider.query(request("demo-clear"), context())
                        .value()).severity());
        assertEquals(io.github.finbridge.spi.risk.RiskDataType.FRAUD_SIGNAL,
                provider.supportedType());
        assertEquals("fraudsignalz", provider.descriptor().pluginId());
    }

    private static RiskDataRequest request(String applicantId) {
        return new RiskDataRequest(UUID.randomUUID(), UUID.randomUUID(), applicantId);
    }

    private static OperationContext context() {
        return new OperationContext(UUID.randomUUID(), "test", Instant.now().plusSeconds(2));
    }
}
