package io.github.finbridge.payment;

import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PaymentGatewayConfiguration {
    @Bean(destroyMethod = "shutdownNow")
    public ExecutorService paymentProviderExecutor() {
        AtomicInteger sequence = new AtomicInteger();
        ThreadFactory factory = task -> {
            Thread worker = new Thread(task, "finbridge-payment-" + sequence.incrementAndGet());
            worker.setDaemon(true);
            return worker;
        };
        return new ThreadPoolExecutor(2, 4, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(64), factory, new ThreadPoolExecutor.AbortPolicy());
    }

    @Bean
    public PaymentGateway paymentGateway(ExecutorService paymentProviderExecutor,
            @Value("${finbridge.payment-provider-timeout:PT5S}") Duration timeout) {
        return new PaymentGateway(paymentProviderExecutor, timeout);
    }
}
