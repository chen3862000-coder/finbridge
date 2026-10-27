package io.github.finbridge.risk;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.time.Duration;
import io.github.finbridge.runtime.PluginRuntime;
import io.github.finbridge.runtime.PluginOperationMetrics;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RiskGatewayConfiguration {
    @Bean(destroyMethod = "shutdownNow")
    public ExecutorService riskProviderExecutor() {
        AtomicInteger threadNumber = new AtomicInteger();
        ThreadFactory factory = new ThreadFactory() {
            @Override
            public Thread newThread(Runnable task) {
                Thread thread = new Thread(task, "finbridge-risk-" + threadNumber.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }
        };
        return new ThreadPoolExecutor(4, 8, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(128), factory, new ThreadPoolExecutor.AbortPolicy());
    }

    @Bean
    public SyntheticRiskDecisionPolicy syntheticRiskDecisionPolicy() {
        return new SyntheticRiskDecisionPolicy();
    }

    @Bean
    public RiskDataGateway riskDataGateway(PluginRuntime runtime, ExecutorService riskProviderExecutor,
            PluginOperationMetrics metrics,
            @Value("${finbridge.risk-provider-timeout:PT2S}") Duration timeout) {
        return new RiskDataGateway(runtime, riskProviderExecutor, timeout, metrics);
    }

}
