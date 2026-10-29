package io.github.finbridge.plugins.blacklisty;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.finbridge.spi.OperationContext;
import io.github.finbridge.spi.risk.BlacklistValue;
import io.github.finbridge.spi.risk.RiskDataRequest;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BlacklistYProviderTest {
    @Test
    void returnsOnlySyntheticBlacklistFacts() {
        BlacklistYProvider provider = new BlacklistYProvider();
        assertTrue(((BlacklistValue) provider.query(request("demo-blacklisted"), context())
                .value()).hit());
        assertTrue(((BlacklistValue) provider.query(request("demo-borrower-alpha-blacklist-self"), context())
                .value()).hit());
        assertFalse(((BlacklistValue) provider.query(request("demo-clear"), context())
                .value()).hit());
        assertEquals(io.github.finbridge.spi.risk.RiskDataType.BLACKLIST, provider.supportedType());
        assertEquals("blacklisty", provider.descriptor().pluginId());
    }

    private static RiskDataRequest request(String applicantId) {
        return new RiskDataRequest(UUID.randomUUID(), UUID.randomUUID(), applicantId);
    }

    private static OperationContext context() {
        return new OperationContext(UUID.randomUUID(), "test", Instant.now().plusSeconds(2));
    }
}
