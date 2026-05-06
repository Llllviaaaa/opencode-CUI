package com.opencode.cui.gateway.service.cloud;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.http.HttpRequest;

/**
 * 无鉴权策略（authType=0/"none"）：不向请求构建器写入任何鉴权 header。
 *
 * <p>用于 v2 模式 / 测试环境 / 公开端点等不需要 SOA/APIG 鉴权的场景。</p>
 */
@Slf4j
@Component
public class NoAuthStrategy implements CloudAuthStrategy {

    @Override
    public String getAuthType() {
        return "none";
    }

    @Override
    public void applyAuth(HttpRequest.Builder requestBuilder, String appId) {
        // no-op：不写任何 header
        log.debug("[CLOUD_AUTH] No-op auth applied");
    }
}
