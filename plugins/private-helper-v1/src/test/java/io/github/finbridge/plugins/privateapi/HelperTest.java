package io.github.finbridge.plugins.privateapi;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class HelperTest {
    @Test
    void reportsV1PrivateDependencyIdentity() {
        assertEquals("alphapay-private-v1", Helper.providerMarker());
    }
}
