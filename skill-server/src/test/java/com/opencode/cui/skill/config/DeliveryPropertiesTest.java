package com.opencode.cui.skill.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DeliveryPropertiesTest {

    @Test
    @DisplayName("defaults: mode=rest, invoke-source-ttl=300, registry-ttl=30, heartbeat=10000")
    void defaults() {
        DeliveryProperties props = new DeliveryProperties();
        assertEquals("rest", props.getMode());
        assertEquals(300, props.getInvokeSourceTtlSeconds());
        assertEquals(30, props.getRegistryTtlSeconds());
        assertEquals(10000, props.getRegistryHeartbeatIntervalMs());
    }

    @Test
    @DisplayName("isWsMode returns true only when mode is ws")
    void isWsMode() {
        DeliveryProperties props = new DeliveryProperties();
        assertFalse(props.isWsMode());
        props.setMode("ws");
        assertTrue(props.isWsMode());
    }
}
