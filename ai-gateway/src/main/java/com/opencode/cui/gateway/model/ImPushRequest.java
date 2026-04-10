package com.opencode.cui.gateway.model;

import lombok.Data;

/**
 * 云端 IM 推送请求体。
 *
 * <p>由外部调用方通过 POST /api/gateway/cloud/im-push 发送，
 * 用于触发 GW 向 SS 转发 im_push 消息，再由 SS 通过 IM 出站服务发送给目标用户。</p>
 */
@Data
public class ImPushRequest {

    /** 以哪个助手 Bot 身份发送消息 */
    private String assistantAccount;

    /** 目标用户账号（单聊时使用） */
    private String userAccount;

    /** 群 ID，为 null 表示单聊 */
    private String imGroupId;

    /** 对应 toolSessionId，用于路由到持有该 session 的 SS 实例 */
    private String topicId;

    /** 消息文本内容 */
    private String content;
}
