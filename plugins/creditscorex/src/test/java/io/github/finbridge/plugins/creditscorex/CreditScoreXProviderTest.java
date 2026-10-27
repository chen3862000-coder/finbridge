package io.github.finbridge.plugins.creditscorex;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.finbridge.spi.OperationContext;
import io.github.finbridge.spi.risk.CreditScoreValue;
import io.github.finbridge.spi.risk.RiskDataRequest;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CreditScoreXProviderTest {
    @Test
    void returnsDeterministicSyntheticScoreBands() {
        CreditScoreXProvider provider = new CreditScoreXProvider();
        assertEquals(550, ((CreditScoreValue) provider.query(request("low-score"), context())
                .value()).score());
        assertEquals(640, ((CreditScoreValue) provider.query(request("review-score"), context())
                .value()).score());
        assertEquals(720, ((CreditScoreValue) provider.query(request("normal"), context())
                .value()).score());
        assertEquals("creditscorex", provider.descriptor().pluginId());
        assertEquals(io.github.finbridge.spi.risk.RiskDataType.CREDIT_SCORE,
                provider.supportedType());
    }

    private static RiskDataRequest request(String applicantId) {
        return new RiskDataRequest(UUID.randomUUID(), UUID.randomUUID(), applicantId);
    }

    private static OperationContext context() {
        return new OperationContext(UUID.randomUUID(), "test", Instant.now().plusSeconds(2));
    }
}
