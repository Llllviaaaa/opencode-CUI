package com.opencode.cui.gateway.service.cloud.profile;

/**
 * GW 端云端响应套餐定义（POJO / 数据）。
 *
 * <p>由 {@link CloudResponseProfileRegistry} 按 SS SysConfig 运行时拼装。
 * 不是 @Component。</p>
 *
 * @param name                profile 名（如 {@code "default"} / {@code "assistant_square"}）
 * @param responseDecoderName 对应 SSE decoder 名称（{@link com.opencode.cui.gateway.service.cloud.decoder.SseEventDecoder#getName()}）
 */
public record CloudResponseProfile(String name, String responseDecoderName) {
}
