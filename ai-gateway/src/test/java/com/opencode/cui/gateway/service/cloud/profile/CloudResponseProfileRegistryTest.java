package com.opencode.cui.gateway.service.cloud.profile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.gateway.service.SkillServerConfigClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CloudResponseProfileRegistryTest {

    private SkillServerConfigClient skillServerConfigClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        skillServerConfigClient = mock(SkillServerConfigClient.class);
    }

    private CloudResponseProfileRegistry newRegistry(long ttl) {
        return new CloudResponseProfileRegistry(skillServerConfigClient, objectMapper, ttl);
    }

    @Test
    void resolve_blankName_defaultsToDefaultProfile() {
        CloudResponseProfileRegistry registry = newRegistry(300_000L);
        when(skillServerConfigClient.getConfigValue(eq("cloud_protocol_profile_def"), eq("default")))
                .thenReturn(null);
        CloudResponseProfile p = registry.resolve(null);
        assertThat(p.name()).isEqualTo("default");
        assertThat(p.responseDecoderName()).isEqualTo("default");
    }

    @Test
    void resolve_profileDefPresent_readsResponseDecoder() {
        CloudResponseProfileRegistry registry = newRegistry(300_000L);
        when(skillServerConfigClient.getConfigValue(eq("cloud_protocol_profile_def"), eq("custom")))
                .thenReturn("{\"request_strategy\":\"default\",\"response_decoder\":\"assistant_square\"}");
        CloudResponseProfile p = registry.resolve("custom");
        assertThat(p.name()).isEqualTo("custom");
        assertThat(p.responseDecoderName()).isEqualTo("assistant_square");
    }

    @Test
    void resolve_businessTagMapping_readsMappedProfileDef() {
        CloudResponseProfileRegistry registry = newRegistry(300_000L);
        when(skillServerConfigClient.getConfigValue(eq("cloud_protocol_profile"), eq("biz-tag")))
                .thenReturn("assistant_square");
        when(skillServerConfigClient.getConfigValue(eq("cloud_protocol_profile_def"), eq("assistant_square")))
                .thenReturn("{\"response_decoder\":\"assistant_square\"}");
        CloudResponseProfile p = registry.resolve("biz-tag");
        assertThat(p.name()).isEqualTo("assistant_square");
        assertThat(p.responseDecoderName()).isEqualTo("assistant_square");
    }

    @Test
    void resolve_profileDefMissing_conventionFallback_decoderEqualsProfileName() {
        CloudResponseProfileRegistry registry = newRegistry(300_000L);
        when(skillServerConfigClient.getConfigValue(eq("cloud_protocol_profile_def"), eq("assistant_square")))
                .thenReturn(null);
        CloudResponseProfile p = registry.resolve("assistant_square");
        assertThat(p.responseDecoderName()).isEqualTo("assistant_square");
    }

    @Test
    void resolve_isCached_secondCallDoesNotHitClient() {
        CloudResponseProfileRegistry registry = newRegistry(300_000L);
        when(skillServerConfigClient.getConfigValue(eq("cloud_protocol_profile_def"), eq("default")))
                .thenReturn(null);
        registry.resolve("default");
        registry.resolve("default");
        verify(skillServerConfigClient, times(1))
                .getConfigValue(eq("cloud_protocol_profile_def"), eq("default"));
    }
}
