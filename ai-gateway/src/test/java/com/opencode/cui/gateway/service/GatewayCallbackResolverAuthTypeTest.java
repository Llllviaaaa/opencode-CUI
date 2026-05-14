package com.opencode.cui.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

/**
 * 验证 {@code GatewayCallbackResolver.mapAuthType} 支持 case 3 → {@code integration_token}。
 */
class GatewayCallbackResolverAuthTypeTest {

    private GatewayCallbackResolver newResolver() {
        return new GatewayCallbackResolver(new ObjectMapper(), "https://api.example.com", "test-token");
    }

    @SuppressWarnings("unchecked")
    private GatewayCallbackResolver spyWith(String body) throws Exception {
        GatewayCallbackResolver resolver = spy(newResolver());
        HttpResponse<String> resp = (HttpResponse<String>) mock(HttpResponse.class);
        doReturn(200).when(resp).statusCode();
        doReturn(body).when(resp).body();
        doReturn(resp).when(resolver).sendRequest(any(HttpRequest.class));
        return resolver;
    }

    @Test
    void mapAuthType_case3_integrationToken() throws Exception {
        String body = """
            {"code":"200","data":{
              "ak":"AK1","scope":"callback:weagent:chat",
              "channelType":2,"channelAddress":"https://cloud/chat","authType":3}}""";
        GatewayCallbackResolver resolver = spyWith(body);
        CallbackConfig cfg = resolver.resolve("AK1", "callback:weagent:chat");
        assertThat(cfg.getAuthType()).isEqualTo("integration_token");
    }
}
