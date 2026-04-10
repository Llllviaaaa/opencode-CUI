package com.opencode.cui.gateway.service.cloud;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.gateway.model.GatewayMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * SseProtocolStrategy 单元测试。
 *
 * <p>通过 Spy 覆盖 protected sendRequest 方法，返回预设的 HttpResponse，
 * 避免真实 HTTP 调用。</p>
 *
 * <ul>
 *   <li>G24: 正常 SSE 读取</li>
 *   <li>G25: SSE 含 tool_done</li>
 *   <li>G26: SSE 含 tool_error</li>
 *   <li>G27: HTTP 非 200</li>
 *   <li>G28: SSE 行格式错误</li>
 *   <li>G29: 空行和非 data 行</li>
 *   <li>G30: 认证头设置</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class SseProtocolStrategyTest {

    @Mock
    private CloudAuthService cloudAuthService;

    @Mock
    private HttpClient httpClient;

    private ObjectMapper objectMapper;
    private SseProtocolStrategy strategy;

    private List<GatewayMessage> receivedEvents;
    private List<Throwable> receivedErrors;
    private Consumer<GatewayMessage> onEvent;
    private Consumer<Throwable> onError;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        strategy = spy(new SseProtocolStrategy(cloudAuthService, objectMapper, httpClient));

        receivedEvents = new ArrayList<>();
        receivedErrors = new ArrayList<>();
        onEvent = receivedEvents::add;
        onError = receivedErrors::add;
    }

    private CloudConnectionContext buildContext() {
        return CloudConnectionContext.builder()
                .endpoint("https://cloud.example.com/sse")
                .cloudRequest(objectMapper.valueToTree(java.util.Map.of("prompt", "hello")))
                .appId("app_test")
                .authType("soa")
                .traceId("trace_001")
                .build();
    }

    @SuppressWarnings("unchecked")
    private HttpResponse<InputStream> mockResponse(int statusCode, String body) {
        HttpResponse<InputStream> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(statusCode);
        if (statusCode == 200) {
            when(response.body()).thenReturn(
                    new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        }
        return response;
    }

    // ==================== G24: 正常 SSE 读取 ====================

    @Test
    @DisplayName("G24: 正常 SSE 读取 - 多条 data JSON 事件被正确解析")
    void shouldParseMultipleSseEventsCorrectly() throws Exception {
        // given
        String sseStream = String.join("\n",
                "data: {\"type\":\"tool_event\",\"toolSessionId\":\"s1\",\"event\":{\"text\":\"hello\"}}",
                "data: {\"type\":\"tool_event\",\"toolSessionId\":\"s1\",\"event\":{\"text\":\"world\"}}",
                "data: [DONE]",
                "");

        HttpResponse<InputStream> response = mockResponse(200, sseStream);
        doReturn(response).when(strategy).sendRequest(any(HttpRequest.class));

        // when
        strategy.connect(buildContext(), onEvent, onError);

        // then
        assertEquals(2, receivedEvents.size(), "should receive 2 events");
        assertEquals(GatewayMessage.Type.TOOL_EVENT, receivedEvents.get(0).getType());
        assertEquals("s1", receivedEvents.get(0).getToolSessionId());
        assertEquals(GatewayMessage.Type.TOOL_EVENT, receivedEvents.get(1).getType());
        assertTrue(receivedErrors.isEmpty(), "should have no errors");
    }

    // ==================== G25: SSE 含 tool_done ====================

    @Test
    @DisplayName("G25: SSE 含 tool_done - tool_done 事件被正确解析")
    void shouldParseToolDoneEvent() throws Exception {
        // given
        String sseStream = String.join("\n",
                "data: {\"type\":\"tool_event\",\"toolSessionId\":\"s2\",\"event\":{\"text\":\"working\"}}",
                "data: {\"type\":\"tool_done\",\"toolSessionId\":\"s2\",\"usage\":{\"tokens\":100}}",
                "");

        HttpResponse<InputStream> response = mockResponse(200, sseStream);
        doReturn(response).when(strategy).sendRequest(any(HttpRequest.class));

        // when
        strategy.connect(buildContext(), onEvent, onError);

        // then
        assertEquals(2, receivedEvents.size());
        GatewayMessage doneMsg = receivedEvents.get(1);
        assertEquals(GatewayMessage.Type.TOOL_DONE, doneMsg.getType());
        assertEquals("s2", doneMsg.getToolSessionId());
        assertNotNull(doneMsg.getUsage(), "usage should not be null");
        assertEquals(100, doneMsg.getUsage().get("tokens").asInt());
        assertTrue(receivedErrors.isEmpty());
    }

    // ==================== G26: SSE 含 tool_error ====================

    @Test
    @DisplayName("G26: SSE 含 tool_error - tool_error 事件被正确解析")
    void shouldParseToolErrorEvent() throws Exception {
        // given
        String sseStream = String.join("\n",
                "data: {\"type\":\"tool_error\",\"toolSessionId\":\"s3\",\"error\":\"timeout occurred\"}",
                "");

        HttpResponse<InputStream> response = mockResponse(200, sseStream);
        doReturn(response).when(strategy).sendRequest(any(HttpRequest.class));

        // when
        strategy.connect(buildContext(), onEvent, onError);

        // then
        assertEquals(1, receivedEvents.size());
        GatewayMessage errorMsg = receivedEvents.get(0);
        assertEquals(GatewayMessage.Type.TOOL_ERROR, errorMsg.getType());
        assertEquals("s3", errorMsg.getToolSessionId());
        assertEquals("timeout occurred", errorMsg.getError());
        assertTrue(receivedErrors.isEmpty());
    }

    // ==================== G27: HTTP 非 200 ====================

    @Test
    @DisplayName("G27: HTTP 非 200 - onError 被调用")
    void shouldCallOnErrorWhenHttpNon200() throws Exception {
        // given
        HttpResponse<InputStream> response = mockResponse(500, "Internal Server Error");
        doReturn(response).when(strategy).sendRequest(any(HttpRequest.class));

        // when
        strategy.connect(buildContext(), onEvent, onError);

        // then
        assertTrue(receivedEvents.isEmpty(), "should have no events on HTTP error");
        assertEquals(1, receivedErrors.size(), "should have exactly one error");
        assertInstanceOf(RuntimeException.class, receivedErrors.get(0));
        assertTrue(receivedErrors.get(0).getMessage().contains("500"));
    }

    // ==================== G28: SSE 行格式错误 ====================

    @Test
    @DisplayName("G28: SSE 行格式错误 - 非法 JSON 行被跳过，后续行继续处理")
    void shouldSkipMalformedJsonAndContinue() throws Exception {
        // given
        String sseStream = String.join("\n",
                "data: {\"type\":\"tool_event\",\"toolSessionId\":\"s4\",\"event\":{\"text\":\"before\"}}",
                "data: {invalid json here!!!}",
                "data: {\"type\":\"tool_event\",\"toolSessionId\":\"s4\",\"event\":{\"text\":\"after\"}}",
                "");

        HttpResponse<InputStream> response = mockResponse(200, sseStream);
        doReturn(response).when(strategy).sendRequest(any(HttpRequest.class));

        // when
        strategy.connect(buildContext(), onEvent, onError);

        // then
        assertEquals(2, receivedEvents.size(), "should receive 2 valid events, skipping malformed line");
        assertEquals("before", receivedEvents.get(0).getEvent().get("text").asText());
        assertEquals("after", receivedEvents.get(1).getEvent().get("text").asText());
        assertTrue(receivedErrors.isEmpty(), "malformed JSON line should not trigger onError");
    }

    // ==================== G29: 空行和非 data 行 ====================

    @Test
    @DisplayName("G29: 空行和非 data 行 - SSE 注释和空行被忽略")
    void shouldIgnoreEmptyLinesAndComments() throws Exception {
        // given
        String sseStream = String.join("\n",
                ": this is a SSE comment",
                "",
                "data: {\"type\":\"tool_event\",\"toolSessionId\":\"s5\",\"event\":{\"text\":\"valid\"}}",
                "",
                "event: custom_event",
                "id: 123",
                "retry: 3000",
                "data: [DONE]",
                "");

        HttpResponse<InputStream> response = mockResponse(200, sseStream);
        doReturn(response).when(strategy).sendRequest(any(HttpRequest.class));

        // when
        strategy.connect(buildContext(), onEvent, onError);

        // then
        assertEquals(1, receivedEvents.size(), "should only parse the single valid data line");
        assertEquals("valid", receivedEvents.get(0).getEvent().get("text").asText());
        assertTrue(receivedErrors.isEmpty());
    }

    // ==================== G30: 认证头设置 ====================

    @Test
    @DisplayName("G30: 认证头设置 - CloudAuthService.applyAuth 被调用")
    void shouldCallCloudAuthServiceApplyAuth() throws Exception {
        // given
        String sseStream = "data: {\"type\":\"tool_event\",\"toolSessionId\":\"s6\",\"event\":{\"text\":\"ok\"}}\n";
        HttpResponse<InputStream> response = mockResponse(200, sseStream);
        doReturn(response).when(strategy).sendRequest(any(HttpRequest.class));

        CloudConnectionContext context = buildContext();

        // when
        strategy.connect(context, onEvent, onError);

        // then
        verify(cloudAuthService).applyAuth(
                any(HttpRequest.Builder.class),
                eq("app_test"),
                eq("soa"));
    }
}
