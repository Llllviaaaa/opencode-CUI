package com.opencode.cui.skill.controller;

import com.opencode.cui.skill.service.GatewayApiClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentQueryControllerTest {

    @Mock
    private GatewayApiClient gatewayApiClient;

    private AgentQueryController controller;

    @BeforeEach
    void setUp() {
        controller = new AgentQueryController(gatewayApiClient);
    }

    @Test
    @DisplayName("getOnlineAgents returns 200 for cookie-authenticated user")
    void getOnlineAgentsWithCookie() {
        List<Map<String, Object>> agents = List.of(Map.of("ak", "ak-1"));
        when(gatewayApiClient.getOnlineAgentsByUserId("10001")).thenReturn(agents);

        var response = controller.getOnlineAgents("10001");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(0, response.getBody().getCode());
        assertEquals("ak-1", response.getBody().getData().get(0).get("ak"));
        assertEquals("ak-1", response.getBody().getData().get(0).get("akId"));
        verify(gatewayApiClient).getOnlineAgentsByUserId("10001");
    }

    @Test
    @DisplayName("getOnlineAgents returns 400 when userId cookie is missing")
    void getOnlineAgentsWithoutCookie() {
        var response = controller.getOnlineAgents(null);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(400, response.getBody().getCode());
    }
}
