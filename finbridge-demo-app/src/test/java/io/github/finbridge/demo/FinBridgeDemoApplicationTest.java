package io.github.finbridge.demo;

import static org.mockito.Mockito.mockStatic;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;

class FinBridgeDemoApplicationTest {
    @Test
    void mainDelegatesStartupToSpringBoot() {
        String[] arguments = {"--spring.main.web-application-type=none"};

        try (var spring = mockStatic(SpringApplication.class)) {
            FinBridgeDemoApplication.main(arguments);
            spring.verify(() -> SpringApplication.run(FinBridgeDemoApplication.class, arguments));
        }
    }
}
