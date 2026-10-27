package io.github.finbridge.host;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

import io.github.finbridge.lending.application.DisbursementService;
import io.github.finbridge.lending.application.RiskAssessmentService;
import io.github.finbridge.runtime.PluginOperationMetrics;
import io.github.finbridge.runtime.PluginRuntime;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;

class HostConfigurationTest {
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

        PluginLifecycleManager lifecycle = mock(PluginLifecycleManager.class);
        PluginRecoveryRunner recovery = mock(PluginRecoveryRunner.class);
        var runner = configuration.m5RecoveryApplicationRunner(lifecycle, recovery);
        runner.run(mock(ApplicationArguments.class));

        var order = inOrder(lifecycle, recovery);
        order.verify(lifecycle).restoreAtStartup();
        order.verify(recovery).recoverAtStartup();
    }
}
