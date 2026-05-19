package com.opencode.cui.gateway.controller;

import com.opencode.cui.gateway.config.InternalAuthProperties;
import com.opencode.cui.gateway.model.AgentConnection;
import com.opencode.cui.gateway.model.AgentAvailabilityResponse;
import com.opencode.cui.gateway.model.AgentConnection.AgentStatus;
import com.opencode.cui.gateway.model.AgentSummaryResponse;
import com.opencode.cui.gateway.model.ApiResponse;
import com.opencode.cui.gateway.service.AgentRegistryService;
import com.opencode.cui.gateway.service.EventRelayService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** AgentController 单元测试：验证在线 Agent 查询接口。 */
@ExtendWith(MockitoExtension.class)
class AgentControllerTest {

    private static final InternalAuthProperties AUTH = new InternalAuthProperties("test-token");

    @Mock
    private AgentRegistryService agentRegistryService;

    @Mock
    private EventRelayService eventRelayService;

    private AgentController controller;

    @BeforeEach
    void setUp() {
        controller = new AgentController(agentRegistryService, eventRelayService, AUTH);
    }

    @Test
    @DisplayName("listOnlineAgents supports string userId filter")
    void listOnlineAgentsSupportsStringUserIdFilter() {
        AgentConnection agent = AgentConnection.builder()
                .id(1L)
                .userId("user-001")
                .akId("ak_test_001")
                .deviceName("MacBook Pro")
                .os("macOS")
                .toolType("CHANNEL")
                .toolVersion("1.0.0")
                .status(AgentStatus.ONLINE)
                .createdAt(LocalDateTime.now())
                .build();
        when(agentRegistryService.findOnlineByUserId("user-001")).thenReturn(List.of(agent));

        ResponseEntity<ApiResponse<List<AgentSummaryResponse>>> response = controller.listOnlineAgents(
                "Bearer test-token",
                null,
                "user-001");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(0, response.getBody().getCode());
        assertEquals(1, response.getBody().getData().size());
        assertEquals("ak_test_001", response.getBody().getData().get(0).ak());
        verify(agentRegistryService).findOnlineByUserId("user-001");
    }

    // ------------------------------------------------------------------ /internal/agent/availability

    @Test
    @DisplayName("availability: 401 when token missing")
    void availabilityUnauthorizedNoToken() {
        ResponseEntity<ApiResponse<AgentAvailabilityResponse>> response =
                controller.getAgentAvailability(null, "ak-1");

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertEquals(401, response.getBody().getCode());
    }

    @Test
    @DisplayName("availability: 400 when ak blank")
    void availabilityBadRequestBlankAk() {
        ResponseEntity<ApiResponse<AgentAvailabilityResponse>> response =
                controller.getAgentAvailability("Bearer test-token", "   ");

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    @DisplayName("availability: exists=false → 200 ok with exists=false, online=false")
    void availabilityNotExists() {
        when(agentRegistryService.queryAvailability("ak-new"))
                .thenReturn(new AgentAvailabilityResponse(false, false, null, null));

        ResponseEntity<ApiResponse<AgentAvailabilityResponse>> response =
                controller.getAgentAvailability("Bearer test-token", "ak-new");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(0, response.getBody().getCode());
        assertFalse(response.getBody().getData().exists());
        assertFalse(response.getBody().getData().online());
        assertNull(response.getBody().getData().latestToolType());
    }

    @Test
    @DisplayName("availability: online → 200 ok with online=true")
    void availabilityOnline() {
        when(agentRegistryService.queryAvailability("ak-1"))
                .thenReturn(new AgentAvailabilityResponse(true, true, "opencode", null));

        ResponseEntity<ApiResponse<AgentAvailabilityResponse>> response =
                controller.getAgentAvailability("Bearer test-token", "ak-1");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().getData().exists());
        assertTrue(response.getBody().getData().online());
        assertEquals("opencode", response.getBody().getData().latestToolType());
    }

    @Test
    @DisplayName("availability: offline + toolType=opencode")
    void availabilityOfflineWithToolType() {
        when(agentRegistryService.queryAvailability("ak-off"))
                .thenReturn(new AgentAvailabilityResponse(true, false, "opencode",
                        LocalDateTime.of(2026, 5, 18, 10, 0)));

        ResponseEntity<ApiResponse<AgentAvailabilityResponse>> response =
                controller.getAgentAvailability("Bearer test-token", "ak-off");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().getData().exists());
        assertFalse(response.getBody().getData().online());
        assertEquals("opencode", response.getBody().getData().latestToolType());
        assertNotNull(response.getBody().getData().lastSeenAt());
    }
}
