package com.opencode.cui.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opencode.cui.gateway.model.AssistantInstanceInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AssistantInstanceInfoServiceTest {

    @Mock
    private RestTemplate restTemplate;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOps;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private AssistantInstanceInfoService service;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        service = new AssistantInstanceInfoService(restTemplate, redisTemplate, objectMapper,
                "https://example.com/instance/query", "token-1", 300);
    }

    @Test
    @DisplayName("缓存命中时直接返回实例信息，不调用远端")
    void getInstanceInfo_cacheHit_returnsCachedInfo() throws Exception {
        AssistantInstanceInfo cached = new AssistantInstanceInfo();
        cached.setPartnerAccount("bot-001");
        cached.setIsRemote(true);
        when(valueOps.get("gw:assistant:instance:bot-001"))
                .thenReturn(objectMapper.writeValueAsString(cached));

        AssistantInstanceInfo result = service.getInstanceInfo("bot-001");

        assertNotNull(result);
        assertEquals("bot-001", result.getPartnerAccount());
        verifyNoInteractions(restTemplate);
    }

    @Test
    @DisplayName("缓存未命中时调用 instance/query，解析 remoteProperty 并写缓存")
    void getInstanceInfo_cacheMiss_fetchesAndCachesRemoteProperty() {
        when(valueOps.get("gw:assistant:instance:bot-001")).thenReturn(null);
        ObjectNode body = objectMapper.createObjectNode();
        body.put("code", 200);
        ObjectNode data = body.putObject("data");
        data.put("partnerAccount", "bot-001");
        data.put("remoteType", AssistantInstanceInfo.REMOTE_TYPE_ASSISTANT_SQUARE);
        ObjectNode remote = data.putArray("remoteProperty").addObject();
        remote.put("type", "chat");
        remote.put("commProtocol", "sse");
        remote.put("url", "https://remote.example.com/chat");
        remote.putArray("headers").addObject()
                .put("type", "custom")
                .put("customKey", "X-Token")
                .put("customValue", "secret");
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(com.fasterxml.jackson.databind.JsonNode.class)))
                .thenReturn(new ResponseEntity<>(body, HttpStatus.OK));

        AssistantInstanceInfo result = service.getInstanceInfo("bot-001");

        assertNotNull(result);
        assertTrue(result.remoteAssistant());
        assertEquals("assistant_square", result.protocolProfile());
        assertEquals("chat", result.getRemoteProperty().get(0).getType());
        verify(valueOps).set(eq("gw:assistant:instance:bot-001"), anyString(), eq(Duration.ofSeconds(300)));
    }

    @Test
    @DisplayName("data 为空时返回 null 且不写缓存")
    void getInstanceInfo_emptyData_returnsNullWithoutCacheWrite() {
        when(valueOps.get("gw:assistant:instance:missing")).thenReturn(null);
        ObjectNode body = objectMapper.createObjectNode();
        body.put("code", 200);
        body.putNull("data");
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(com.fasterxml.jackson.databind.JsonNode.class)))
                .thenReturn(new ResponseEntity<>(body, HttpStatus.OK));

        assertNull(service.getInstanceInfo("missing"));
        verify(valueOps, never()).set(eq("gw:assistant:instance:missing"), anyString(), any(Duration.class));
    }
}
