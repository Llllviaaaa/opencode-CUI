package com.opencode.cui.skill.service.cloud;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 云端请求上下文，携带构建请求体所需的所有参数。
 */
@Data
@Builder
public class CloudRequestContext {

    /** 消息内容 */
    private String content;

    /** 内容类型，如 "text" / "IMAGE-V1" */
    private String contentType;

    /** 助理账号 */
    private String assistantAccount;

    /** 发送用户账号 */
    private String sendUserAccount;

    /** IM 群组 ID */
    private String imGroupId;

    /** 客户端语言，如 "zh" / "en" */
    private String clientLang;

    /** 客户端类型，如 "asst-pc" / "asst-wecode" */
    private String clientType;

    /** 话题 ID */
    private String topicId;

    /** 消息 ID */
    private String messageId;

    /** 扩展参数 */
    private Map<String, Object> extParameters;

    /** 仅 question_reply：关联回云端 question 事件的 toolCallId */
    private String replyToolCallId;

    /** 仅 question_reply：外层=多问题，内层=单题多选/单选/自由文本 */
    private List<List<String>> replyAnswers;

    /** 仅 permission_reply：关联回 permission.ask 的 permissionId */
    private String replyPermissionId;

    /** 仅 permission_reply：once / always / reject */
    private String replyResponse;
}
