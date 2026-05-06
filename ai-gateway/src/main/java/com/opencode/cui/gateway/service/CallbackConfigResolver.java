package com.opencode.cui.gateway.service;

public interface CallbackConfigResolver {
    /** 返回 null 表示未订阅 / AK 无效 / 上游失败 */
    CallbackConfig resolve(String ak, String scope);

    /** 实现版本标识："v1" | "v2"。 */
    String version();
}
