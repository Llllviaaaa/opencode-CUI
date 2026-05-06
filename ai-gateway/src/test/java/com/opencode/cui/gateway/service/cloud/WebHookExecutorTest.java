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

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * WebHookExecutor 单元测试（T15）。
 *
 * <p>覆盖：
 * <ul>
 *   <li>2xx 响应：fire-and-forget，不回流</li>
 *   <li>非 2xx：回流 TOOL_ERROR</li>
 *   <li>IO 异常：回流 TOOL_ERROR</li>
 *   <li>X-App-Id / X-Trace-Id header 写入</li>
 *   <li>CloudAuthService.applyAuth 调用</li>
 * </ul>
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class WebHookExecutorTest {

    @Mock
    private CloudAuthService cloudAuthService;

    private ObjectMapper objectMapper;
    private WebHookExecutor executor;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        executor = spy(new WebHookExecutor(objectMapper, cloudAuthService, 10));
    }

    private CloudConnectionContext buildContext() {
        return buildContextWithAppId("app-1");
    }

    private CloudConnectionContext buildContextWithAppId(String appId) {
        return CloudConnectionContext.builder()
                .channelAddress("https://cloud.example.com/webhook")
                .channelType("webhook")
                .scope("callback:weagent:question_response")
                .cloudRequest(objectMapper.valueToTree(java.util.Map.of("k", "v")))
                .appId(appId)
                .authType("soa")
                .traceId("trace-1")
                .build();
    }

    private GatewayMessage buildInvokeMessage(String ak, String userId, String traceId) {
        return GatewayMessage.builder()
                .type(GatewayMessage.Type.INVOKE)
                .ak(ak)
                .userId(userId)
                .traceId(traceId)
                .welinkSessionId("wsid-1")
                .build();
    }

    @SuppressWarnings("unchecked")
    private HttpResponse<String> mockResponse(int status, String body) {
        HttpResponse<String> resp = mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(status);
        lenient().when(resp.body()).thenReturn(body);
        return resp;
    }

    private void stubResponse(int status, String body) throws Exception {
        doReturn(mockResponse(status, body)).when(executor).sendRequest(any(HttpRequest.class));
    }

    private void stubError(Exception ex) throws Exception {
        doThrow(ex).when(executor).sendRequest(any(HttpRequest.class));
    }

    private HttpRequest captureRequest(CloudConnectionContext ctx) throws Exception {
        ArgumentCaptor<HttpRequest> cap = ArgumentCaptor.forClass(HttpRequest.class);
        doReturn(mockResponse(200, "{}")).when(executor).sendRequest(cap.capture());
        @SuppressWarnings("unchecked")
        Consumer<GatewayMessage> onRelay = mock(Consumer.class);
        executor.execute(ctx, onRelay, buildInvokeMessage("ak1", "u1", "trace-1"), "ts-1");
        return cap.getValue();
    }

    // ==================== 行为：响应处理 ====================

    @Test
    @DisplayName("2xx 响应：不回流任何 GatewayMessage")
    @SuppressWarnings("unchecked")
    void execute_2xxResponse_doesNotEmitMessage() throws Exception {
        Consumer<GatewayMessage> onRelay = mock(Consumer.class);
        stubResponse(200, "{}");

        GatewayMessage invoke = buildInvokeMessage("ak1", "u1", "trace-1");
        executor.execute(buildContext(), onRelay, invoke, "ts-1");

        verifyNoInteractions(onRelay);
    }

    @Test
    @DisplayName("5xx 响应：回流 TOOL_ERROR，error 含状态码")
    @SuppressWarnings("unchecked")
    void execute_5xxResponse_emitsToolError() throws Exception {
        Consumer<GatewayMessage> onRelay = mock(Consumer.class);
        stubResponse(503, "");

        GatewayMessage invoke = buildInvokeMessage("ak1", "u1", "trace-1");
        executor.execute(buildContext(), onRelay, invoke, "ts-1");

        ArgumentCaptor<GatewayMessage> cap = ArgumentCaptor.forClass(GatewayMessage.class);
        verify(onRelay).accept(cap.capture());
        GatewayMessage err = cap.getValue();
        assertThat(err.getType()).isEqualTo(GatewayMessage.Type.TOOL_ERROR);
        assertThat(err.getError()).contains("503");
        assertThat(err.getAk()).isEqualTo("ak1");
        assertThat(err.getToolSessionId()).isEqualTo("ts-1");
        assertThat(err.getTraceId()).isEqualTo("trace-1");
    }

    @Test
    @DisplayName("IO 异常：回流 TOOL_ERROR，error 含异常消息")
    @SuppressWarnings("unchecked")
    void execute_ioException_emitsToolError() throws Exception {
        Consumer<GatewayMessage> onRelay = mock(Consumer.class);
        stubError(new java.io.IOException("network down"));

        executor.execute(buildContext(), onRelay,
                buildInvokeMessage("ak1", "u1", "trace-1"), "ts-1");

        ArgumentCaptor<GatewayMessage> cap = ArgumentCaptor.forClass(GatewayMessage.class);
        verify(onRelay).accept(cap.capture());
        assertThat(cap.getValue().getType()).isEqualTo(GatewayMessage.Type.TOOL_ERROR);
        assertThat(cap.getValue().getError()).contains("network down");
    }

    // ==================== 行为：Header 写入 ====================

    @Test
    @DisplayName("appId 非空时写 X-App-Id 与 X-Trace-Id header（X-App-Id 仅出现一次）")
    void execute_writesXAppIdHeader_whenAppIdPresent() throws Exception {
        HttpRequest captured = captureRequest(buildContextWithAppId("app-1"));
        // 防回归：X-App-Id 必须只有一个值（由 SoaAuthStrategy 写入），WebHookExecutor 自身不再重复写。
        // 注意：测试中 cloudAuthService 是 mock，不会真正调用 SoaAuthStrategy；
        // 此处断言 WebHookExecutor 自身没有写 X-App-Id（即 mock 路径下应当为空）。
        assertThat(captured.headers().allValues("X-App-Id")).isEmpty();
        assertThat(captured.headers().firstValue("X-Trace-Id")).contains("trace-1");
    }

    @Test
    @DisplayName("appId 为 null 时 WebHookExecutor 自身不写 X-App-Id header")
    void execute_skipsXAppIdHeader_whenAppIdNull() throws Exception {
        HttpRequest captured = captureRequest(buildContextWithAppId(null));
        // mock 的 cloudAuthService 不会真正调 SoaAuthStrategy；此处断言 WebHookExecutor 自身没写。
        assertThat(captured.headers().allValues("X-App-Id")).isEmpty();
    }

    // ==================== 行为：鉴权调用 ====================

    @Test
    @DisplayName("CloudAuthService.applyAuth 被调用一次（authType=soa）")
    @SuppressWarnings("unchecked")
    void execute_appliesAuthViaCloudAuthService() throws Exception {
        stubResponse(200, "{}");
        Consumer<GatewayMessage> onRelay = mock(Consumer.class);

        executor.execute(buildContextWithAppId("app-1"), onRelay,
                buildInvokeMessage("ak1", "u1", "trace-1"), "ts-1");

        verify(cloudAuthService).applyAuth(any(HttpRequest.Builder.class), eq("app-1"), eq("soa"));
    }

    // ==================== T15 Should Fix: 错误回流字段与异常分支 ====================

    @Test
    @DisplayName("T15: 5xx 响应回流的 tool_error 包含 userId 与 welinkSessionId")
    @SuppressWarnings("unchecked")
    void execute_5xxResponse_includesUserIdAndWelinkSessionIdInToolError() throws Exception {
        Consumer<GatewayMessage> onRelay = mock(Consumer.class);
        stubResponse(503, "");

        GatewayMessage invoke = buildInvokeMessage("ak1", "u1", "trace-1");
        executor.execute(buildContext(), onRelay, invoke, "ts-1");

        ArgumentCaptor<GatewayMessage> cap = ArgumentCaptor.forClass(GatewayMessage.class);
        verify(onRelay).accept(cap.capture());
        GatewayMessage err = cap.getValue();
        assertThat(err.getType()).isEqualTo(GatewayMessage.Type.TOOL_ERROR);
        assertThat(err.getUserId()).isEqualTo("u1");
        assertThat(err.getWelinkSessionId()).isEqualTo("wsid-1");
    }

    @Test
    @DisplayName("T15: InterruptedException 触发 tool_error 且恢复 interrupt 标志")
    @SuppressWarnings("unchecked")
    void execute_interrupted_emitsToolErrorAndRestoresInterruptFlag() throws Exception {
        Consumer<GatewayMessage> onRelay = mock(Consumer.class);
        stubError(new InterruptedException("simulated interrupt"));

        try {
            executor.execute(buildContext(), onRelay,
                    buildInvokeMessage("ak1", "u1", "trace-1"), "ts-1");

            ArgumentCaptor<GatewayMessage> cap = ArgumentCaptor.forClass(GatewayMessage.class);
            verify(onRelay).accept(cap.capture());
            GatewayMessage err = cap.getValue();
            assertThat(err.getType()).isEqualTo(GatewayMessage.Type.TOOL_ERROR);
            assertThat(err.getError()).contains("WebHook interrupted");
            // 恢复后的 interrupt 标志应为 true（Thread.interrupted() 检查并清除）
            assertThat(Thread.interrupted()).isTrue();
        } finally {
            // 兜底清理 interrupt 标志，避免污染后续测试
            Thread.interrupted();
        }
    }

    @Test
    @DisplayName("T15: JSON 序列化失败回流 tool_error")
    @SuppressWarnings("unchecked")
    void execute_jsonSerializationFailure_emitsToolError() throws Exception {
        // 用一个 ObjectMapper spy，让 writeValueAsString 抛 JsonProcessingException
        ObjectMapper failingMapper = spy(new ObjectMapper());
        doThrow(new com.fasterxml.jackson.core.JsonProcessingException("serialize fail") {})
                .when(failingMapper).writeValueAsString(any(Object.class));

        WebHookExecutor failingExecutor = spy(new WebHookExecutor(failingMapper, cloudAuthService, 10));
        Consumer<GatewayMessage> onRelay = mock(Consumer.class);

        failingExecutor.execute(buildContext(), onRelay,
                buildInvokeMessage("ak1", "u1", "trace-1"), "ts-1");

        ArgumentCaptor<GatewayMessage> cap = ArgumentCaptor.forClass(GatewayMessage.class);
        verify(onRelay).accept(cap.capture());
        GatewayMessage err = cap.getValue();
        assertThat(err.getType()).isEqualTo(GatewayMessage.Type.TOOL_ERROR);
        assertThat(err.getError()).contains("WebHook delivery failed");
        assertThat(err.getError()).contains("serialize fail");
    }
}
