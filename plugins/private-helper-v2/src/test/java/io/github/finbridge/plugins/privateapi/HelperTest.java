package io.github.finbridge.plugins.privateapi;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class HelperTest {
    @Test
    void reportsV2PrivateDependencyIdentity() {
        assertEquals("alphapay-private-v2", Helper.providerMarker());
    }
}
