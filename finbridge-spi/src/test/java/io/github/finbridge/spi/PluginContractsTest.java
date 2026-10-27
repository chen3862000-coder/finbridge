package io.github.finbridge.spi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.finbridge.spi.payment.DisbursementRequest;
import io.github.finbridge.spi.payment.CollectionRequest;
import io.github.finbridge.spi.payment.PaymentResult;
import io.github.finbridge.spi.payment.PaymentStatus;
import io.github.finbridge.spi.payment.PaymentTransactionRef;
import io.github.finbridge.spi.risk.RiskDataRequest;
import io.github.finbridge.spi.risk.RiskDataResult;
import io.github.finbridge.spi.risk.RiskDataType;
import io.github.finbridge.spi.risk.CreditScoreValue;
import io.github.finbridge.spi.risk.BlacklistValue;
import io.github.finbridge.spi.risk.FraudSignalValue;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PluginContractsTest {
    private static final Currency CNY = Currency.getInstance("CNY");

    @Test
    void descriptorAndPluginKeyValidateAndExposeStableIdentity() {
        PluginDescriptor descriptor = paymentDescriptor();
        assertEquals(1, descriptor.spiMajorVersion());
        assertEquals("synthetic-pay", descriptor.pluginId());
        assertEquals("SYNTHETIC", descriptor.providerCode());
        assertEquals("1.0.0", descriptor.pluginVersion());
        assertEquals("1.2", descriptor.spiApiVersion());
        assertEquals(PluginType.PAYMENT, descriptor.pluginType());
        assertEquals(Set.of(Capability.DISBURSE), descriptor.capabilities());
        assertEquals("Synthetic Pay", descriptor.displayName());
        assertEquals("Test provider", descriptor.description());
        assertEquals(new PluginKey("synthetic-pay", "1.0.0"), PluginKey.from(descriptor));
        assertThrows(IllegalArgumentException.class, () -> new PluginDescriptor(
                "", "provider", "1", "1.0", PluginType.PAYMENT,
                Set.of(Capability.DISBURSE), "name", "description"));
        assertThrows(IllegalArgumentException.class, () -> new PluginDescriptor(
                "id", "provider", "1", "bad", PluginType.PAYMENT,
                Set.of(Capability.DISBURSE), "name", "description").spiMajorVersion());
        assertThrows(IllegalArgumentException.class, () -> new PluginKey("id", " "));
    }

    @Test
    void contextHealthAndOperationValuesAreValidated() {
        PluginContext context = new PluginContext(Map.of("mode", "synthetic"));
        assertEquals(Map.of("mode", "synthetic"), context.configuration());
        assertThrows(UnsupportedOperationException.class,
                () -> context.configuration().put("other", "value"));
        assertTrue(PluginHealth.healthyStatus().healthy());
        assertFalse(PluginHealth.unhealthy("NOT_READY").healthy());
        OperationContext operation = new OperationContext(UUID.randomUUID(), "correlation",
                Instant.now());
        assertEquals("correlation", operation.correlationId());
        assertNotNull(operation.executionId());
        assertNotNull(operation.deadline());
        assertThrows(IllegalArgumentException.class, () -> new OperationContext(
                UUID.randomUUID(), " ", Instant.now()));
        assertThrows(NullPointerException.class, () -> new OperationContext(null, "x", Instant.now()));
    }

    @Test
    void paymentAndRiskResultsPreserveExplicitOutcomes() {
        PaymentResult success = PaymentResult.succeeded("synthetic-transaction");
        assertEquals(PaymentStatus.SUCCEEDED, success.status());
        assertEquals("synthetic-transaction", success.providerTransactionId());
        assertNull(success.errorCategory());
        assertNull(success.providerCode());
        assertThrows(IllegalArgumentException.class,
                () -> new PaymentResult(PaymentStatus.SUCCEEDED, null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new PaymentResult(PaymentStatus.UNKNOWN, " ", null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new PaymentResult(PaymentStatus.FAILED, null, null, " "));
        PaymentResult failed = new PaymentResult(PaymentStatus.FAILED, null,
                ProviderErrorCategory.DECLINED, "DECLINED");
        assertEquals(ProviderErrorCategory.DECLINED, failed.errorCategory());
        assertEquals("DECLINED", failed.providerCode());
        assertEquals(PaymentStatus.values().length, 4);
        assertEquals(ProviderErrorCategory.values().length, 6);
        assertEquals(Capability.values().length, 6);
        assertEquals(PluginType.values().length, 2);
        assertThrows(IllegalArgumentException.class,
                () -> new DisbursementRequest(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                        BigDecimal.ZERO, CNY, "key"));
        DisbursementRequest disbursement = new DisbursementRequest(UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("10.00"),
                CNY, "key");
        assertEquals(new BigDecimal("10.00"), disbursement.amount());
        assertEquals("CNY", disbursement.currency().getCurrencyCode());
        assertEquals("key", disbursement.idempotencyKey());
        assertThrows(IllegalArgumentException.class, () -> new DisbursementRequest(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), BigDecimal.ONE,
                CNY, " "));
        CollectionRequest collection = new CollectionRequest(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), BigDecimal.ONE, CNY,
                "collection-key");
        assertEquals("collection-key", collection.idempotencyKey());
        assertNotNull(collection.installmentId());
        PaymentTransactionRef transactionRef = new PaymentTransactionRef("provider-ref");
        assertEquals("provider-ref", transactionRef.providerTransactionId());
        assertThrows(IllegalArgumentException.class, () -> new PaymentTransactionRef(" "));
        assertEquals(RiskDataType.BLACKLIST, new BlacklistValue(true).dataType());
        assertEquals(RiskDataType.FRAUD_SIGNAL,
                new FraudSignalValue(FraudSignalValue.Severity.NONE, "CLEAR").dataType());
    }

    @Test
    void riskResultsPreserveTypedAvailableUnavailableAndInvalidOutcomes() {
        RiskDataResult available = new RiskDataResult(RiskDataType.CREDIT_SCORE,
                RiskDataResult.Status.AVAILABLE, new CreditScoreValue(720), null);
        assertEquals(720, ((CreditScoreValue) available.value()).score());
        assertEquals(RiskDataType.CREDIT_SCORE, available.dataType());
        assertEquals(RiskDataResult.Status.AVAILABLE, available.status());
        assertEquals(680, ((CreditScoreValue) RiskDataResult.available(
                new CreditScoreValue(680)).value()).score());
        assertThrows(IllegalArgumentException.class, () -> new RiskDataResult(
                RiskDataType.BLACKLIST, RiskDataResult.Status.UNAVAILABLE, null, null));
        RiskDataResult unavailable = new RiskDataResult(RiskDataType.BLACKLIST,
                RiskDataResult.Status.UNAVAILABLE, null, "PROVIDER_TIMEOUT");
        assertEquals("PROVIDER_TIMEOUT", unavailable.reasonCode());
        assertEquals("NO_BINDING", RiskDataResult.unavailable(
                RiskDataType.BLACKLIST, "NO_BINDING").reasonCode());
        RiskDataResult invalid = new RiskDataResult(RiskDataType.FRAUD_SIGNAL,
                RiskDataResult.Status.INVALID, null, "INVALID_RESPONSE");
        assertEquals(RiskDataType.FRAUD_SIGNAL, invalid.dataType());
        assertEquals(RiskDataResult.Status.INVALID, invalid.status());
        assertEquals("BAD_VALUE", RiskDataResult.invalid(
                RiskDataType.CREDIT_SCORE, "BAD_VALUE").reasonCode());
        assertEquals(RiskDataResult.Status.values().length, 3);
        assertEquals(RiskDataType.values().length, 3);

        RiskDataRequest request = new RiskDataRequest(UUID.randomUUID(), UUID.randomUUID(),
                "synthetic-applicant");
        assertEquals("synthetic-applicant", request.syntheticApplicantId());
        assertNotNull(request.assessmentId());
        assertNotNull(request.tenantId());
        assertThrows(IllegalArgumentException.class, () -> new RiskDataRequest(UUID.randomUUID(),
                UUID.randomUUID(), " "));
        assertThrows(IllegalArgumentException.class, () -> new RiskDataResult(
                RiskDataType.CREDIT_SCORE, RiskDataResult.Status.AVAILABLE, null, null));
        assertThrows(IllegalArgumentException.class, () -> new RiskDataResult(
                RiskDataType.CREDIT_SCORE, RiskDataResult.Status.AVAILABLE,
                new BlacklistValue(true), null));
        assertThrows(IllegalArgumentException.class, () -> new RiskDataResult(
                RiskDataType.BLACKLIST, RiskDataResult.Status.UNAVAILABLE,
                new BlacklistValue(false), "NO_DATA"));
    }

    @Test
    void defaultPluginLifecycleMethodsAreSafeAndContextual() {
        TestPlugin plugin = new TestPlugin();
        assertEquals(paymentDescriptor(), plugin.descriptor());
        plugin.initialize(new PluginContext(Map.of()));
        assertTrue(plugin.healthCheck().healthy());
        plugin.close();
    }

    private static PluginDescriptor paymentDescriptor() {
        return new PluginDescriptor("synthetic-pay", "SYNTHETIC", "1.0.0", "1.2",
                PluginType.PAYMENT, Set.of(Capability.DISBURSE), "Synthetic Pay", "Test provider");
    }

    private static final class TestPlugin implements FinBridgePlugin, AutoCloseable {
        @Override
        public PluginDescriptor descriptor() {
            return paymentDescriptor();
        }

        @Override
        public void close() {
            FinBridgePlugin.super.close();
        }
    }
}
