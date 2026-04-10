package com.opencode.cui.skill.service.cloud;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 云端请求构建策略接口。
 * 不同的 appId 可通过 SysConfig 配置不同的策略实现。
 */
public interface CloudRequestStrategy {

    /**
     * 返回策略名称，用于与 SysConfig 配置值匹配。
     *
     * @return 策略名称
     */
    String getName();

    /**
     * 根据上下文构建云端请求体。
     *
     * @param context 请求上下文
     * @return 请求体 JSON 节点
     */
    ObjectNode build(CloudRequestContext context);
}
