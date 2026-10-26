package io.github.finbridge.lending.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

class LendingCoreConfigurationTest {
    @Test
    void suppliesUtcClockAndScheduleCalculatorBeans() {
        LendingCoreConfiguration configuration = new LendingCoreConfiguration();
        assertEquals("Z", configuration.lendingClock().getZone().getId());
        assertNotNull(configuration.lendingClock().instant());
        assertNotNull(configuration.repaymentScheduleCalculator());
    }
}
