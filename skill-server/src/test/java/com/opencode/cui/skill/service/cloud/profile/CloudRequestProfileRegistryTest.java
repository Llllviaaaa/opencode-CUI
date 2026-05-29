package com.opencode.cui.skill.service.cloud.profile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opencode.cui.skill.service.SysConfigService;
import com.opencode.cui.skill.service.cloud.CloudRequestContext;
import com.opencode.cui.skill.service.cloud.CloudRequestStrategy;
import com.opencode.cui.skill.service.cloud.DefaultCloudRequestStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CloudRequestProfileRegistryTest {

    private static class FakeStrategy implements CloudRequestStrategy {
        private final String name;
        FakeStrategy(String name) { this.name = name; }
        @Override public String getName() { return name; }
        @Override public ObjectNode build(CloudRequestContext context) {
            return new ObjectMapper().createObjectNode();
        }
    }

    private SysConfigService sysConfigService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        sysConfigService = mock(SysConfigService.class);
    }

    private CloudRequestProfileRegistry newRegistry(long ttl, CloudRequestStrategy... strategies) {
        return new CloudRequestProfileRegistry(List.of(strategies), sysConfigService, objectMapper, ttl);
    }

    @Test
    void resolve_missingMapping_fallsBackToDefault() {
        CloudRequestStrategy def = new FakeStrategy(DefaultCloudRequestStrategy.STRATEGY_NAME);
        CloudRequestProfileRegistry registry = newRegistry(300_000L, def);
        when(sysConfigService.getValue(eq("cloud_protocol_profile"), eq("bt-1"))).thenReturn(null);

        CloudRequestProfile profile = registry.resolve("bt-1");

        assertThat(profile.name()).isEqualTo("default");
        assertThat(profile.requestStrategy()).isSameAs(def);
    }

    @Test
    void resolve_profileDefPresent_readsRequestStrategyFromJson() {
        CloudRequestStrategy def = new FakeStrategy(DefaultCloudRequestStrategy.STRATEGY_NAME);
        CloudRequestStrategy asq = new FakeStrategy("assistant_square");
        CloudRequestProfileRegistry registry = newRegistry(300_000L, def, asq);

        when(sysConfigService.getValue(eq("cloud_protocol_profile"), eq("bt-1")))
                .thenReturn("custom_combo");
        when(sysConfigService.getValue(eq("cloud_protocol_profile_def"), eq("custom_combo")))
                .thenReturn("{\"request_strategy\":\"assistant_square\",\"response_decoder\":\"default\"}");

        CloudRequestProfile profile = registry.resolve("bt-1");

        assertThat(profile.name()).isEqualTo("custom_combo");
        assertThat(profile.requestStrategy()).isSameAs(asq);
    }

    @Test
    void resolve_profileDefMissing_conventionFallback_nameEqualsStrategyName() {
        CloudRequestStrategy def = new FakeStrategy(DefaultCloudRequestStrategy.STRATEGY_NAME);
        CloudRequestStrategy asq = new FakeStrategy("assistant_square");
        CloudRequestProfileRegistry registry = newRegistry(300_000L, def, asq);

        when(sysConfigService.getValue(eq("cloud_protocol_profile"), eq("bt-1")))
                .thenReturn("assistant_square");
        when(sysConfigService.getValue(eq("cloud_protocol_profile_def"), eq("assistant_square")))
                .thenReturn(null);

        CloudRequestProfile profile = registry.resolve("bt-1");

        assertThat(profile.name()).isEqualTo("assistant_square");
        assertThat(profile.requestStrategy()).isSameAs(asq);
    }

    @Test
    void resolveProfile_directProfileNameSkipsBusinessTagMapping() {
        CloudRequestStrategy def = new FakeStrategy(DefaultCloudRequestStrategy.STRATEGY_NAME);
        CloudRequestStrategy asq = new FakeStrategy("assistant_square");
        CloudRequestProfileRegistry registry = newRegistry(300_000L, def, asq);

        CloudRequestProfile profile = registry.resolveProfile("assistant_square");

        assertThat(profile.name()).isEqualTo("assistant_square");
        assertThat(profile.requestStrategy()).isSameAs(asq);
        verifyNoInteractions(sysConfigService);
    }

    @Test
    void resolve_unregisteredStrategy_fallsBackToDefaultStrategy() {
        CloudRequestStrategy def = new FakeStrategy(DefaultCloudRequestStrategy.STRATEGY_NAME);
        CloudRequestProfileRegistry registry = newRegistry(300_000L, def);

        when(sysConfigService.getValue(eq("cloud_protocol_profile"), eq("bt-1")))
                .thenReturn("nonexistent");
        when(sysConfigService.getValue(eq("cloud_protocol_profile_def"), eq("nonexistent")))
                .thenReturn(null);

        CloudRequestProfile profile = registry.resolve("bt-1");

        assertThat(profile.name()).isEqualTo("nonexistent");
        assertThat(profile.requestStrategy()).isSameAs(def);
    }

    @Test
    void resolve_isCached_secondCallDoesNotHitSysConfig() {
        CloudRequestStrategy def = new FakeStrategy(DefaultCloudRequestStrategy.STRATEGY_NAME);
        CloudRequestProfileRegistry registry = newRegistry(300_000L, def);

        when(sysConfigService.getValue(eq("cloud_protocol_profile"), eq("bt-1"))).thenReturn(null);

        registry.resolve("bt-1");
        registry.resolve("bt-1");

        verify(sysConfigService, times(1)).getValue(eq("cloud_protocol_profile"), eq("bt-1"));
    }
}
