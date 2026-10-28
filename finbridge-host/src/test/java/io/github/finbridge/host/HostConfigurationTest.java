package io.github.finbridge.host;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import io.github.finbridge.lending.application.DisbursementService;
import io.github.finbridge.lending.application.RiskAssessmentService;
import io.github.finbridge.lending.application.LendingApplicationService;
import io.github.finbridge.lending.application.LoanOriginationService;
import io.github.finbridge.runtime.PluginOperationMetrics;
import io.github.finbridge.runtime.PluginRuntime;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;

class HostConfigurationTest {
    @Test
    void serializesMonetaryDecimalsAsExactJsonStrings() throws Exception {
        var builder = org.springframework.http.converter.json.Jackson2ObjectMapperBuilder.json();
        new HostConfiguration().decimalValuesAsStrings().customize(builder);

        assertEquals("\"123456789012345.67\"", builder.build()
                .writeValueAsString(new BigDecimal("123456789012345.67")));
    }

    @Test
    void configuresMetricsRuntimeAndSingleOrderedStartupRecovery() throws Exception {
        HostConfiguration configuration = new HostConfiguration();
        var registry = new SimpleMeterRegistry();
        PluginOperationMetrics metrics = configuration.pluginOperationMetrics(registry);
        PluginRuntime runtime = configuration.pluginRuntime(Path.of("plugins"));

        assertNotNull(metrics);
        assertNotNull(configuration.finBridgeMeterRegistry());
        assertNotNull(runtime);
        assertNotNull(configuration.pluginRecoveryRunner(mock(RiskAssessmentService.class),
                mock(DisbursementService.class)));
        assertNotNull(configuration.tenantLendingFacade(mock(LendingApplicationService.class),
                mock(RiskAssessmentService.class), mock(DisbursementService.class),
                mock(LoanOriginationService.class), mock(TenantConsoleReadRepository.class)));
        assertNotNull(configuration.m5RecoveryApplicationRunner(mock(PluginLifecycleManager.class),
                mock(PluginRecoveryRunner.class)));

        PluginLifecycleManager lifecycle = mock(PluginLifecycleManager.class);
        PluginRecoveryRunner recovery = mock(PluginRecoveryRunner.class);
        var runner = configuration.m5RecoveryApplicationRunner(lifecycle, recovery);
        runner.run(mock(ApplicationArguments.class));

        var order = inOrder(lifecycle, recovery);
        order.verify(lifecycle).restoreAtStartup();
        order.verify(recovery).recoverAtStartup();
    }
}
