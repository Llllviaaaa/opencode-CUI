package com.opencode.cui.gateway.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CloudRouteSwitchServiceTest {

    @Mock
    private SkillServerConfigClient skillServerConfigClient;

    @Test
    void remotePropertyEnabled_defaultsToTrueWhenConfigMissing() {
        when(skillServerConfigClient.getConfigValue(
                CloudRouteSwitchService.CONFIG_TYPE,
                CloudRouteSwitchService.REMOTE_PROPERTY_ENABLED_KEY))
                .thenReturn(null);

        CloudRouteSwitchService service = new CloudRouteSwitchService(skillServerConfigClient, 300_000L);

        assertTrue(service.remotePropertyEnabled());
    }

    @Test
    void remotePropertyEnabled_returnsFalseWhenConfigIsZero() {
        when(skillServerConfigClient.getConfigValue(
                CloudRouteSwitchService.CONFIG_TYPE,
                CloudRouteSwitchService.REMOTE_PROPERTY_ENABLED_KEY))
                .thenReturn("0");

        CloudRouteSwitchService service = new CloudRouteSwitchService(skillServerConfigClient, 300_000L);

        assertFalse(service.remotePropertyEnabled());
    }

    @Test
    void remotePropertyEnabled_cachesValueWithinTtl() {
        when(skillServerConfigClient.getConfigValue(
                CloudRouteSwitchService.CONFIG_TYPE,
                CloudRouteSwitchService.REMOTE_PROPERTY_ENABLED_KEY))
                .thenReturn("1");

        CloudRouteSwitchService service = new CloudRouteSwitchService(skillServerConfigClient, 300_000L);

        assertTrue(service.remotePropertyEnabled());
        assertTrue(service.remotePropertyEnabled());
        verify(skillServerConfigClient, times(1)).getConfigValue(
                CloudRouteSwitchService.CONFIG_TYPE,
                CloudRouteSwitchService.REMOTE_PROPERTY_ENABLED_KEY);
    }
}
