package com.opencode.cui.gateway.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.gateway.model.GatewayMessage;
import com.opencode.cui.gateway.model.ImPushRequest;
import com.opencode.cui.gateway.service.AssistantAccountResolver;
import com.opencode.cui.gateway.service.AssistantAccountResolver.ResolveResult;
import com.opencode.cui.gateway.service.SkillRelayService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * 云端推送控制器。
 *
 * <p>提供 REST 接口，允许外部服务触发 GW 向 SS 转发 im_push 消息。
 * SS 收到后将通过 IM 出站服务发送给目标用户或群组。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/gateway/cloud")
@RequiredArgsConstructor
public class CloudPushController {

    private final SkillRelayService skillRelayService;
    private final ObjectMapper objectMapper;
    private final AssistantAccountResolver assistantAccountResolver;

    /**
     * 接收 IM 推送请求，执行安全校验后封装为 GatewayMessage 并转发给 SS。
     *
     * <p>校验流程：
     * <ol>
     *   <li>基础校验：assistantAccount、content 非空</li>
     *   <li>调上游 resolve API 验证 assistantAccount 是否为有效 agent 账号</li>
     *   <li>单聊（imGroupId 为空）：校验 userAccount == create_by，不匹配则拒绝</li>
     *   <li>群聊：不校验 userAccount</li>
     * </ol>
     *
     * @param request 包含 assistantAccount、userAccount、imGroupId、topicId、content 的推送请求
     * @return 200 OK / 400 Bad Request / 403 Forbidden
     */
    @PostMapping("/im-push")
    public ResponseEntity<?> imPush(@RequestBody ImPushRequest request) {
        // 1. 基础校验
        if (isBlank(request.getAssistantAccount())) {
            log.warn("[IM_PUSH] Rejected: assistantAccount is blank");
            return ResponseEntity.badRequest().body(Map.of("error", "assistantAccount is required"));
        }
        if (isBlank(request.getContent())) {
            log.warn("[IM_PUSH] Rejected: content is blank, assistantAccount={}",
                    request.getAssistantAccount());
            return ResponseEntity.badRequest().body(Map.of("error", "content is required"));
        }

        // 2. 验证 assistantAccount 有效性
        ResolveResult resolved = assistantAccountResolver.resolve(request.getAssistantAccount());
        if (resolved == null) {
            log.warn("[IM_PUSH] Rejected: invalid assistant account={}", request.getAssistantAccount());
            return ResponseEntity.badRequest().body(Map.of("error", "invalid assistant account"));
        }

        // 3. 单聊校验 userAccount == create_by
        boolean isGroup = !isBlank(request.getImGroupId());
        if (!isGroup) {
            if (isBlank(request.getUserAccount())) {
                log.warn("[IM_PUSH] Rejected: userAccount is required for direct chat, assistantAccount={}",
                        request.getAssistantAccount());
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "userAccount is required for direct chat"));
            }
            if (!request.getUserAccount().equals(resolved.getCreateBy())) {
                log.warn("[IM_PUSH] Rejected: userAccount={} does not match creator={}, assistantAccount={}",
                        request.getUserAccount(), resolved.getCreateBy(), request.getAssistantAccount());
                return ResponseEntity.status(403)
                        .body(Map.of("error", "userAccount does not match assistant creator"));
            }
        }

        // 4. 校验通过，转发
        GatewayMessage msg = new GatewayMessage();
        msg.setType(GatewayMessage.Type.IM_PUSH);
        msg.setToolSessionId(request.getTopicId()); // 用于路由到持有该 session 的 SS 实例
        msg.setPayload(objectMapper.valueToTree(request));
        msg.setTraceId(UUID.randomUUID().toString());
        skillRelayService.relayToSkill(msg);
        return ResponseEntity.ok().build();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
