package io.github.finbridge.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;

@SpringBootApplication(scanBasePackages = "io.github.finbridge")
public class FinBridgeDemoApplication {
    public static void main(String[] args) {
        SpringApplication.run(FinBridgeDemoApplication.class, args);
    }

    @Bean
    ApplicationRunner demoMemberships(DemoIdentityMembershipRepository memberships,
            org.springframework.core.env.Environment environment) {
        return arguments -> {
            String issuer = environment.getProperty("finbridge.demo.oidc-issuer");
            memberships.assignDemoMembership(issuer, "10000000-0000-0000-0000-000000000001",
                    "borrower-alpha", java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"), false);
            memberships.assignDemoMembership(issuer, "10000000-0000-0000-0000-000000000002",
                    "borrower-beta", java.util.UUID.fromString("00000000-0000-0000-0000-000000000002"), false);
            memberships.assignDemoMembership(issuer, "10000000-0000-0000-0000-000000000003",
                    "demo-admin", null, true);
            memberships.assignDemoMembership(issuer, "10000000-0000-0000-0000-000000000004",
                    "borrower-alpha-review", java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"),
                    false);
            memberships.assignDemoMembership(issuer, "10000000-0000-0000-0000-000000000005",
                    "borrower-alpha-blacklist", java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"),
                    false);
            memberships.assignDemoMembership(issuer, "10000000-0000-0000-0000-000000000006",
                    "borrower-alpha-critical-fraud",
                    java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"), false);
        };
    }
}
