package io.github.finbridge.host;

import io.github.finbridge.runtime.PluginOperationMetrics;
import io.github.finbridge.runtime.PluginRuntime;
import io.micrometer.core.instrument.MeterRegistry;
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
    public PluginRuntime pluginRuntime(
            @Value("${finbridge.plugin-directory:plugins}") Path pluginDirectory) {
        return HostRuntime.create(pluginDirectory);
    }

    @Bean
    public PluginRecoveryRunner pluginRecoveryRunner(
            io.github.finbridge.lending.application.RiskAssessmentService risk,
            io.github.finbridge.lending.application.DisbursementService disbursements) {
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
