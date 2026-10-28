package io.github.finbridge.host;

import io.github.finbridge.runtime.PluginOperationMetrics;
import io.github.finbridge.runtime.PluginRuntime;
import io.github.finbridge.lending.application.LendingApplicationService;
import io.github.finbridge.lending.application.RiskAssessmentService;
import io.github.finbridge.lending.application.DisbursementService;
import io.github.finbridge.lending.application.LoanOriginationService;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class HostConfiguration {
    @Bean
    public PluginOperationMetrics pluginOperationMetrics(MeterRegistry registry) {
        return new PluginOperationMetrics(registry);
    }

    @Bean
    public MeterRegistry finBridgeMeterRegistry() {
        return new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
    }

    @Bean
    public org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer
            decimalValuesAsStrings() {
        return builder -> builder.serializerByType(BigDecimal.class,
                com.fasterxml.jackson.databind.ser.std.ToStringSerializer.instance);
    }

    @Bean
    public PluginRuntime pluginRuntime(
            @Value("${finbridge.plugin-directory:plugins}") Path pluginDirectory) {
        return HostRuntime.create(pluginDirectory);
    }

    @Bean
    public TenantLendingFacade tenantLendingFacade(LendingApplicationService applications,
            RiskAssessmentService risk, DisbursementService payments, LoanOriginationService origination,
            TenantConsoleReadRepository consoleReads) {
        return new TenantLendingFacade(applications, risk, payments, origination, consoleReads);
    }

    @Bean
    public PluginRecoveryRunner pluginRecoveryRunner(
            RiskAssessmentService risk, DisbursementService disbursements) {
        return new PluginRecoveryRunner(risk, disbursements);
    }

    @Bean
    public org.springframework.boot.ApplicationRunner m5RecoveryApplicationRunner(
            PluginLifecycleManager lifecycle, PluginRecoveryRunner recovery) {
        return arguments -> {
            lifecycle.restoreAtStartup();
            recovery.recoverAtStartup();
        };
    }

}
