package com.opencode.cui.skill.service.cloud.profile;

import com.opencode.cui.skill.service.cloud.CloudRequestStrategy;

/**
 * SS 端云端请求套餐（POJO / 数据）。
 *
 * <p>由 {@link CloudRequestProfileRegistry} 按 SysConfig 运行时拼装。不是 @Component。
 * 用于一并携带 profile name（GW 端解码器映射 key）+ requestStrategy 实例。</p>
 *
 * @param name             profile 名（如 {@code "default"} / {@code "assistant_square"}）
 * @param requestStrategy  对应入参构建策略实例
 */
public record CloudRequestProfile(String name, CloudRequestStrategy requestStrategy) {
}
