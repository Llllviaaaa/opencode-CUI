package com.opencode.cui.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

/**
 * GatewayCallbackResolver 单元测试（TDD）。
 *
 * <p>纯单元测试：直接 new 实例 + Mockito spy 拦截 protected sendRequest 方法，
 * 不启动 Spring context，避免 @ConditionalOnProperty 影响。</p>
 */
class GatewayCallbackResolverTest {

    private GatewayCallbackResolver newResolver() {
        return new GatewayCallbackResolver(new ObjectMapper(), "https://api.example.com", "test-token");
    }

    private GatewayCallbackResolver spyResolverWithResponse(String body) throws Exception {
        GatewayCallbackResolver resolver = spy(newResolver());
        @SuppressWarnings("unchecked")
        HttpResponse<String> resp = (HttpResponse<String>) org.mockito.Mockito.mock(HttpResponse.class);
        doReturn(200).when(resp).statusCode();
        doReturn(body).when(resp).body();
        doReturn(resp).when(resolver).sendRequest(any(HttpRequest.class));
        return resolver;
    }

    private GatewayCallbackResolver spyResolverWithError(Exception e) throws Exception {
        GatewayCallbackResolver resolver = spy(newResolver());
        doThrow(e).when(resolver).sendRequest(any(HttpRequest.class));
        return resolver;
    }

    @Test
    void resolve_success_parsesChannelTypeAndAuthType() throws Exception {
        String responseBody = """
            {"code":"200","data":{
              "ak":"AK1","scope":"callback:weagent:chat",
              "channelType":2,"channelAddress":"https://cloud/chat","authType":1}}""";
        GatewayCallbackResolver resolver = spyResolverWithResponse(responseBody);
        CallbackConfig cfg = resolver.resolve("AK1", "callback:weagent:chat");
        assertThat(cfg.getChannelType()).isEqualTo("sse");
        assertThat(cfg.getChannelAddress()).isEqualTo("https://cloud/chat");
        assertThat(cfg.getAuthType()).isEqualTo("soa");
        assertThat(cfg.getAppId()).isNull();   // v2 不返回 appId
    }

    @Test
    void resolve_dataNull_returnsNull() throws Exception {
        String body = "{\"code\":\"200\",\"data\":null}";
        GatewayCallbackResolver resolver = spyResolverWithResponse(body);
        assertThat(resolver.resolve("AK1", "callback:weagent:chat")).isNull();
    }

    @Test
    void resolve_httpError_returnsNull() throws Exception {
        GatewayCallbackResolver resolver = spyResolverWithError(new RuntimeException("upstream 500"));
        assertThat(resolver.resolve("AK1", "callback:weagent:chat")).isNull();
    }
}
