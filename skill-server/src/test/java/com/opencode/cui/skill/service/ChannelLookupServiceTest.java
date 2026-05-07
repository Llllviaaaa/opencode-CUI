package com.opencode.cui.skill.service;

import com.opencode.cui.skill.model.AgentSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChannelLookupServiceTest {

    @Mock
    private GatewayApiClient gatewayApiClient;

    private ChannelLookupService service;

    @BeforeEach
    void setUp() {
        service = new ChannelLookupService(gatewayApiClient);
    }

    @Test
    @DisplayName("ak=null/blank → empty, no API call")
    void blankAk_empty() {
        assertTrue(service.getToolType(null).isEmpty());
        assertTrue(service.getToolType("").isEmpty());
        assertTrue(service.getToolType("   ").isEmpty());
        verify(gatewayApiClient, times(0)).getAgentByAk("");
    }

    @Test
    @DisplayName("first call hits API, second call hits cache")
    void cacheHit_secondCallNoApi() {
        when(gatewayApiClient.getAgentByAk("ak-1"))
                .thenReturn(AgentSummary.builder().ak("ak-1").toolType("opencode").build());

        Optional<String> first = service.getToolType("ak-1");
        Optional<String> second = service.getToolType("ak-1");

        assertEquals("opencode", first.orElse(null));
        assertEquals("opencode", second.orElse(null));
        verify(gatewayApiClient, times(1)).getAgentByAk("ak-1");
    }

    @Test
    @DisplayName("API returns null → empty + cached as ABSENT (no repeated calls)")
    void apiReturnsNull_cachedAsAbsent() {
        when(gatewayApiClient.getAgentByAk("ak-2")).thenReturn(null);

        assertTrue(service.getToolType("ak-2").isEmpty());
        assertTrue(service.getToolType("ak-2").isEmpty());

        verify(gatewayApiClient, times(1)).getAgentByAk("ak-2");
    }

    @Test
    @DisplayName("API returns agent with blank toolType → empty + ABSENT")
    void apiReturnsBlankToolType_empty() {
        when(gatewayApiClient.getAgentByAk("ak-3"))
                .thenReturn(AgentSummary.builder().ak("ak-3").toolType("").build());

        Optional<String> got = service.getToolType("ak-3");
        assertFalse(got.isPresent());
    }

    @Test
    @DisplayName("API throws → empty (defensive)")
    void apiThrows_empty() {
        when(gatewayApiClient.getAgentByAk("ak-err"))
                .thenThrow(new RuntimeException("upstream down"));

        Optional<String> got = service.getToolType("ak-err");
        assertTrue(got.isEmpty());
    }
}
