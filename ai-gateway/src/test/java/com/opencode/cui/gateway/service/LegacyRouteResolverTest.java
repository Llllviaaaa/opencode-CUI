package com.opencode.cui.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

/**
 * LegacyRouteResolver 单元测试（TDD）。
 *
 * <p>纯单元测试：直接 new 实例 + Mockito spy 拦截 protected sendRequest 方法，
 * 不启动 Spring context，避免 @ConditionalOnProperty 影响。</p>
 */
class LegacyRouteResolverTest {

    private LegacyRouteResolver newResolver() {
        return new LegacyRouteResolver(new ObjectMapper(), "https://api.example.com", "test-token");
    }

    private LegacyRouteResolver spyResolverWithResponse(String body) throws Exception {
        LegacyRouteResolver resolver = spy(newResolver());
        @SuppressWarnings("unchecked")
        HttpResponse<String> resp = (HttpResponse<String>) org.mockito.Mockito.mock(HttpResponse.class);
        doReturn(200).when(resp).statusCode();
        doReturn(body).when(resp).body();
        doReturn(resp).when(resolver).sendRequest(any(HttpRequest.class));
        return resolver;
    }

    @Test
    void resolve_chatScope_callsLegacyEndpoint() throws Exception {
        String body = """
            {"code":"200","data":{
              "hisAppId":"app-1","endpoint":"https://cloud/chat",
              "protocol":"2","authType":"1"}}""";
        LegacyRouteResolver resolver = spyResolverWithResponse(body);
        CallbackConfig cfg = resolver.resolve("AK1", "callback:weagent:chat");
        assertThat(cfg.getAppId()).isEqualTo("app-1");
        assertThat(cfg.getChannelAddress()).isEqualTo("https://cloud/chat");
        assertThat(cfg.getChannelType()).isEqualTo("sse");
        assertThat(cfg.getAuthType()).isEqualTo("soa");
    }

    @Test
    void resolve_questionReplyScope_returnsNull_inV1() throws Exception {
        LegacyRouteResolver resolver = spyResolverWithResponse("");
        assertThat(resolver.resolve("AK1", "callback:weagent:question_reply")).isNull();
        assertThat(resolver.resolve("AK1", "callback:weagent:permission_reply")).isNull();
    }
}
