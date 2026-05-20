package com.opencode.cui.skill.telemetry.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * WeLink 埋码上报配置。
 *
 * <p>本地/测试默认 {@code enabled=false}；仅生产环境开启。
 * 当 {@code enabled=true} 但 {@code url}/{@code token}/{@code publicKey}/{@code tenantId}
 * 任一为空时，{@code WelinkTelemetryAutoConfiguration} 会 WARN 并视同关闭。
 */
@Data
@ConfigurationProperties(prefix = "telemetry.welink")
public class WelinkTelemetryProperties {

    /** 总开关 */
    private boolean enabled = false;

    /** WeLink producer 端点 URL */
    private String url = "";

    /** Bearer Token */
    private String token = "";

    /** RSA 公钥 base64 */
    private String publicKey = "";

    /** 策略名（埋码 policyName 字段） */
    private String policyName = "POLICY_WELINK_SERVER";

    /** 租户 ID（静态配置，所有事件共享） */
    private String tenantId = "";

    /** serviceName 字段（埋码事件） */
    private String serviceName = "skill-server";

    /** appName 字段（埋码事件） */
    private String appName = "skill-server";

    /** appPackageName 字段（埋码事件） */
    private String appPackageName = "com.opencode.cui.skill";

    /** 异步执行器配置 */
    private Executor executor = new Executor();

    @Data
    public static class Executor {
        private int corePoolSize = 2;
        private int maxPoolSize = 4;
        private int queueCapacity = 1000;
        /** 拒绝策略：当前仅支持 discard（队列满直接丢 + WARN） */
        private String rejectPolicy = "discard";
    }
}
