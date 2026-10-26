package io.github.finbridge.lending.config;

import io.github.finbridge.lending.domain.RepaymentScheduleCalculator;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LendingCoreConfiguration {
    @Bean
    public Clock lendingClock() {
        return Clock.systemUTC();
    }

    @Bean
    public RepaymentScheduleCalculator repaymentScheduleCalculator() {
        return new RepaymentScheduleCalculator();
    }
}
