package com.opencode.cui.gateway.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.gateway.model.GatewayMessage;
import com.opencode.cui.gateway.model.ImPushRequest;
import com.opencode.cui.gateway.service.SkillRelayService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 云端推送控制器。
 *
 * <p>提供 REST 接口，允许外部服务触发 GW 向 SS 转发 im_push 消息。
 * SS 收到后将通过 IM 出站服务发送给目标用户或群组。</p>
 */
@RestController
@RequestMapping("/api/gateway/cloud")
@RequiredArgsConstructor
public class CloudPushController {

    private final SkillRelayService skillRelayService;
    private final ObjectMapper objectMapper;

    /**
     * 接收 IM 推送请求，封装为 GatewayMessage 并转发给 SS。
     *
     * @param request 包含 assistantAccount、userAccount、imGroupId、topicId、content 的推送请求
     * @return 200 OK（不携带业务响应体）
     */
    @PostMapping("/im-push")
    public ResponseEntity<Void> imPush(@RequestBody ImPushRequest request) {
        GatewayMessage msg = new GatewayMessage();
        msg.setType(GatewayMessage.Type.IM_PUSH);
        msg.setToolSessionId(request.getTopicId()); // 用于路由到持有该 session 的 SS 实例
        msg.setPayload(objectMapper.valueToTree(request));
        msg.setTraceId(UUID.randomUUID().toString());
        skillRelayService.relayToSkill(msg);
        return ResponseEntity.ok().build();
    }
}
