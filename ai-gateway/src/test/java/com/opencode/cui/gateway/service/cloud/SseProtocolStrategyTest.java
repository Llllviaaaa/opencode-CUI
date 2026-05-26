package com.opencode.cui.gateway.service.cloud;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.gateway.logging.GatewayStreamEventLogHelper;
import com.opencode.cui.gateway.model.GatewayMessage;
import com.opencode.cui.gateway.service.cloud.decoder.DecoderSession;
import com.opencode.cui.gateway.service.cloud.decoder.DefaultSseEventDecoder;
import com.opencode.cui.gateway.service.cloud.decoder.SseEventDecoder;
import com.opencode.cui.gateway.service.cloud.decoder.SseEventDecoderFactory;
import com.opencode.cui.gateway.service.cloud.profile.CloudResponseProfile;
import com.opencode.cui.gateway.service.cloud.profile.CloudResponseProfileRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;

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
import static org.mockito.ArgumentMatchers.argThat;
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

    @Mock
    private CloudResponseProfileRegistry profileRegistry;

    private ObjectMapper objectMapper;
    private SseProtocolStrategy strategy;
    private SseEventDecoder assistantSquareStub;

    private CloudConnectionLifecycle lifecycle;

    private List<GatewayMessage> receivedEvents;
    private List<Throwable> receivedErrors;
    private Consumer<GatewayMessage> onEvent;
    private Consumer<Throwable> onError;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        // 助手广场 decoder 用 stub（避免拉一堆 handler 依赖），名字对齐 "assistant_square"
        assistantSquareStub = mock(SseEventDecoder.class);
        when(assistantSquareStub.getName()).thenReturn("assistant_square");
        lenient().when(assistantSquareStub.createSession()).thenReturn(mock(DecoderSession.class));
        lenient().when(assistantSquareStub.flush(any())).thenReturn(java.util.Collections.emptyList());
        SseEventDecoderFactory decoderFactory = new SseEventDecoderFactory(
                java.util.List.of(new DefaultSseEventDecoder(objectMapper), assistantSquareStub));
        // 默认走 fallback：profile name == decoder name（covers 既有 default 路径）
        lenient().when(profileRegistry.resolve(any()))
                .thenAnswer(inv -> {
                    String n = inv.getArgument(0);
                    String name = (n == null || n.isBlank()) ? "default" : n;
                    return new CloudResponseProfile(name, name);
                });
        strategy = spy(new SseProtocolStrategy(cloudAuthService, objectMapper, decoderFactory,
                profileRegistry, httpClient));
        lifecycle = mock(CloudConnectionLifecycle.class);

        receivedEvents = new ArrayList<>();
        receivedErrors = new ArrayList<>();
        onEvent = receivedEvents::add;
        onError = receivedErrors::add;
    }

    private CloudConnectionContext buildContext() {
        return CloudConnectionContext.builder()
                .channelAddress("https://cloud.example.com/sse")
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
        strategy.connect(buildContext(), lifecycle, onEvent, onError);

        // then
        assertEquals(2, receivedEvents.size(), "should receive 2 events");
        assertEquals(GatewayMessage.Type.TOOL_EVENT, receivedEvents.get(0).getType());
        assertEquals("s1", receivedEvents.get(0).getToolSessionId());
        assertEquals(GatewayMessage.Type.TOOL_EVENT, receivedEvents.get(1).getType());
        assertTrue(receivedErrors.isEmpty(), "should have no errors");
    }

    @Test
    @DisplayName("G24b: SSE data line logs gw.cloud_agent inbound boundary event")
    void shouldLogInboundSseDataLine() throws Exception {
        String sseStream = String.join("\n",
                "data: {\"type\":\"tool_event\",\"toolSessionId\":\"s1\",\"event\":{\"text\":\"hello\"}}",
                "data: [DONE]",
                "");
        HttpResponse<InputStream> response = mockResponse(200, sseStream);
        doReturn(response).when(strategy).sendRequest(any(HttpRequest.class));

        try (MockedStatic<GatewayStreamEventLogHelper> eventLogs =
                     mockStatic(GatewayStreamEventLogHelper.class)) {
            strategy.connect(buildContext(), lifecycle, onEvent, onError);

            eventLogs.verify(() -> GatewayStreamEventLogHelper.inbound(
                    any(Logger.class),
                    eq("gw.cloud_agent"),
                    eq("received"),
                    argThat(payload -> payload.contains("\"toolSessionId\":\"s1\"")
                            && payload.contains("\"text\":\"hello\""))));
        }
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
        strategy.connect(buildContext(), lifecycle, onEvent, onError);

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
        strategy.connect(buildContext(), lifecycle, onEvent, onError);

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
        strategy.connect(buildContext(), lifecycle, onEvent, onError);

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
        strategy.connect(buildContext(), lifecycle, onEvent, onError);

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
        strategy.connect(buildContext(), lifecycle, onEvent, onError);

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
        strategy.connect(context, lifecycle, onEvent, onError);

        // then
        verify(cloudAuthService).applyAuth(
                any(HttpRequest.Builder.class),
                eq("app_test"),
                eq("soa"));
    }

    // ==================== G31: SSE 心跳注释行 ====================

    @Test
    @DisplayName("G31: SSE 心跳注释行 - 触发 lifecycle.onHeartbeat()")
    void shouldCallOnHeartbeatForHeartbeatComment() throws Exception {
        String sseStream = String.join("\n",
                ": heartbeat",
                "data: {\"type\":\"tool_event\",\"toolSessionId\":\"s7\",\"event\":{\"text\":\"ok\"}}",
                ": heartbeat",
                "");
        HttpResponse<InputStream> response = mockResponse(200, sseStream);
        doReturn(response).when(strategy).sendRequest(any(HttpRequest.class));

        strategy.connect(buildContext(), lifecycle, onEvent, onError);

        verify(lifecycle, times(2)).onHeartbeat();
        verify(lifecycle).onConnected();
        verify(lifecycle).onEventReceived();
        assertEquals(1, receivedEvents.size());
    }

    // ==================== G32: tool_done 触发 lifecycle.onTerminalEvent() ====================

    @Test
    @DisplayName("G32: tool_done 触发 lifecycle.onTerminalEvent()")
    void shouldCallOnTerminalEventForToolDone() throws Exception {
        String sseStream = String.join("\n",
                "data: {\"type\":\"tool_event\",\"toolSessionId\":\"s8\",\"event\":{\"text\":\"hi\"}}",
                "data: {\"type\":\"tool_done\",\"toolSessionId\":\"s8\"}",
                "");
        HttpResponse<InputStream> response = mockResponse(200, sseStream);
        doReturn(response).when(strategy).sendRequest(any(HttpRequest.class));

        strategy.connect(buildContext(), lifecycle, onEvent, onError);

        verify(lifecycle).onTerminalEvent();
        verify(lifecycle, times(2)).onEventReceived();
    }

    // ==================== T13: question/permission 事件保活 ====================

    @Test
    @DisplayName("T13: event.type=question 触发 lifecycle.pauseIdleTimer()")
    void sse_questionEvent_triggersPauseIdleTimer() throws Exception {
        String sseStream = "data: {\"type\":\"tool_event\",\"toolSessionId\":\"ts-1\","
                + "\"event\":{\"type\":\"question\",\"properties\":{\"toolCallId\":\"call-1\"}}}\n\n";
        HttpResponse<InputStream> response = mockResponse(200, sseStream);
        doReturn(response).when(strategy).sendRequest(any(HttpRequest.class));

        strategy.connect(buildContext(), lifecycle, onEvent, onError);

        verify(lifecycle).pauseIdleTimer();
        verify(lifecycle, never()).resumeIdleTimer();
    }

    @Test
    @DisplayName("T13: event.type=permission.ask 触发 lifecycle.pauseIdleTimer()")
    void sse_permissionAskEvent_triggersPause() throws Exception {
        String sseStream = "data: {\"type\":\"tool_event\",\"toolSessionId\":\"ts-2\","
                + "\"event\":{\"type\":\"permission.ask\",\"properties\":{\"permissionId\":\"p-1\"}}}\n\n";
        HttpResponse<InputStream> response = mockResponse(200, sseStream);
        doReturn(response).when(strategy).sendRequest(any(HttpRequest.class));

        strategy.connect(buildContext(), lifecycle, onEvent, onError);

        verify(lifecycle).pauseIdleTimer();
        verify(lifecycle, never()).resumeIdleTimer();
    }

    @Test
    @DisplayName("T13: event.type=permission.reply 不调 pause；调 resume")
    void sse_permissionReplyEvent_doesNotTriggerPause() throws Exception {
        String sseStream = "data: {\"type\":\"tool_event\",\"toolSessionId\":\"ts-3\","
                + "\"event\":{\"type\":\"permission.reply\",\"properties\":{\"permissionId\":\"p-1\"}}}\n\n";
        HttpResponse<InputStream> response = mockResponse(200, sseStream);
        doReturn(response).when(strategy).sendRequest(any(HttpRequest.class));

        strategy.connect(buildContext(), lifecycle, onEvent, onError);

        verify(lifecycle, never()).pauseIdleTimer();
        verify(lifecycle).resumeIdleTimer();
    }

    @Test
    @DisplayName("T13: event.type=text.delta 触发 lifecycle.resumeIdleTimer()")
    void sse_textDeltaEvent_triggersResume() throws Exception {
        String sseStream = "data: {\"type\":\"tool_event\",\"toolSessionId\":\"ts-4\","
                + "\"event\":{\"type\":\"text.delta\",\"properties\":{\"text\":\"hello\"}}}\n\n";
        HttpResponse<InputStream> response = mockResponse(200, sseStream);
        doReturn(response).when(strategy).sendRequest(any(HttpRequest.class));

        strategy.connect(buildContext(), lifecycle, onEvent, onError);

        verify(lifecycle, never()).pauseIdleTimer();
        verify(lifecycle).resumeIdleTimer();
    }

    @Test
    @DisplayName("T13: appId=null 时 HTTP 请求不写 X-App-Id header")
    void sse_appIdNull_skipsXAppIdHeader() throws Exception {
        String sseStream = "data: [DONE]\n";
        HttpResponse<InputStream> response = mockResponse(200, sseStream);
        ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        doReturn(response).when(strategy).sendRequest(requestCaptor.capture());

        CloudConnectionContext context = CloudConnectionContext.builder()
                .channelAddress("https://cloud.example.com/sse")
                .cloudRequest(objectMapper.valueToTree(java.util.Map.of("prompt", "hello")))
                .appId(null)
                .authType("none")
                .traceId("trace_null_app")
                .build();

        strategy.connect(context, lifecycle, onEvent, onError);

        HttpRequest captured = requestCaptor.getValue();
        // SseProtocolStrategy 自身不再写 X-App-Id；mock 的 cloudAuthService 也不会触发真正策略，
        // 因此 X-App-Id 必须为空（同时验证不会出现重复写入）。
        assertTrue(captured.headers().allValues("X-App-Id").isEmpty(),
                "X-App-Id header MUST be absent when appId is null");
    }

    @Test
    @DisplayName("T13: SseProtocolStrategy 自身不写 X-App-Id（由 cloudAuthService 内部 strategy 负责）")
    void sse_appIdPresent_writesXAppIdHeader() throws Exception {
        String sseStream = "data: [DONE]\n";
        HttpResponse<InputStream> response = mockResponse(200, sseStream);
        ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        doReturn(response).when(strategy).sendRequest(requestCaptor.capture());

        strategy.connect(buildContext(), lifecycle, onEvent, onError);

        HttpRequest captured = requestCaptor.getValue();
        // mock 的 cloudAuthService.applyAuth 不会真正调 SoaAuthStrategy 写入 X-App-Id；
        // 此处断言 SseProtocolStrategy 自身不再写入 X-App-Id（防止两份重复写入回归）。
        assertTrue(captured.headers().allValues("X-App-Id").isEmpty(),
                "SseProtocolStrategy 不应直接写 X-App-Id（由 cloudAuthService 内部 strategy 写入）");
        // 但 cloudAuthService.applyAuth 必须被调用一次。
        verify(cloudAuthService).applyAuth(any(HttpRequest.Builder.class), eq("app_test"), eq("soa"));
    }

    // ==================== Registry 拼装进主路径 ====================

    @Test
    @DisplayName("Registry: profile_def.response_decoder 指向 assistant_square 时，主路径选用 AssistantSquare decoder")
    void sse_profileDefRoutesResponseDecoder_picksAssistantSquare() throws Exception {
        // given: profile name 与 decoder name 不一致；运维通过 profile_def.response_decoder 显式拼装
        String customProfileName = "default_req_assistant_resp";
        when(profileRegistry.resolve(customProfileName))
                .thenReturn(new CloudResponseProfile(customProfileName, "assistant_square"));

        String sseStream = "data: {\"type\":\"tool_event\",\"toolSessionId\":\"sx\",\"event\":{\"text\":\"hi\"}}\n";
        HttpResponse<InputStream> response = mockResponse(200, sseStream);
        doReturn(response).when(strategy).sendRequest(any(HttpRequest.class));

        CloudConnectionContext context = CloudConnectionContext.builder()
                .channelAddress("https://cloud.example.com/sse")
                .cloudRequest(objectMapper.valueToTree(java.util.Map.of("prompt", "hi")))
                .appId("app_test")
                .authType("soa")
                .traceId("trace_reg_1")
                .cloudProfile(customProfileName)
                .build();

        // when
        strategy.connect(context, lifecycle, onEvent, onError);

        // then: Registry 被查；AssistantSquare stub 被选中（createSession 被调用证明它真的进了主路径），
        // DefaultSseEventDecoder 没被用（receivedEvents 为空，因 stub 不真实解析）
        verify(profileRegistry).resolve(customProfileName);
        verify(assistantSquareStub).createSession();
        verify(assistantSquareStub).flush(any());
    }

    @Test
    @DisplayName("Registry: profile_def 缺失走约定 fallback（profile name == decoder name）")
    void sse_profileDefMissing_fallsBackToProfileNameAsDecoder() throws Exception {
        // given: cloudProfile 留空 → Registry 默认返回 default/default；走 DefaultSseEventDecoder
        String sseStream = "data: {\"type\":\"tool_event\",\"toolSessionId\":\"sy\",\"event\":{\"text\":\"ok\"}}\n";
        HttpResponse<InputStream> response = mockResponse(200, sseStream);
        doReturn(response).when(strategy).sendRequest(any(HttpRequest.class));

        // when
        strategy.connect(buildContext(), lifecycle, onEvent, onError);

        // then: Registry 仍被查；DefaultDecoder 路径产出事件；AssistantSquare 未被触达
        verify(profileRegistry).resolve(any());
        verify(assistantSquareStub, never()).createSession();
        assertEquals(1, receivedEvents.size());
        assertEquals("ok", receivedEvents.get(0).getEvent().get("text").asText());
    }
}
