package com.opencode.cui.skill.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.model.InvokeCommand;
import com.opencode.cui.skill.model.SkillSession;
import com.opencode.cui.skill.model.StreamMessage;
import com.opencode.cui.skill.repository.SkillMessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
/** SessionRebuildService 单元测试：验证重建计数器超限行为及会话隔离逻辑。 */
class SessionRebuildServiceTest {

    @Mock
    private SkillSessionService sessionService;

    @Mock
    private SkillMessageRepository messageRepository;

    private SessionRebuildService service;

    /** 捕获回调，将广播和 invoke 命令分别存入列表便于断言。 */
    static class CapturingCallback implements SessionRebuildService.RebuildCallback {
        final List<StreamMessage> broadcasts = new ArrayList<>();
        final List<InvokeCommand> invokes = new ArrayList<>();

        @Override
        public void broadcast(String sessionId, String userId, StreamMessage msg) {
            broadcasts.add(msg);
        }

        @Override
        public void sendInvoke(InvokeCommand command) {
            invokes.add(command);
        }
    }

    /** 构建一个带 ak 的测试用 SkillSession。 */
    private SkillSession buildSession(Long id, String userId, String ak, String title) {
        return SkillSession.builder()
                .id(id)
                .userId(userId)
                .ak(ak)
                .title(title)
                .build();
    }

    @BeforeEach
    void setUp() {
        // maxRebuildAttempts=3, rebuildCooldownSeconds=30
        service = new SessionRebuildService(new ObjectMapper(), sessionService, messageRepository, 3, 30);
    }

    @Test
    @DisplayName("在限额内的重建次数：3 次都应发送 invoke 并广播 retry 状态")
    void rebuildToolSession_shouldAllowAttemptsWithinLimit() {
        String sessionId = "1001";
        SkillSession session = buildSession(1001L, "user-1", "ak-abc", "测试会话");
        CapturingCallback cb = new CapturingCallback();

        service.rebuildToolSession(sessionId, session, "用户消息", cb);
        service.rebuildToolSession(sessionId, session, "用户消息", cb);
        service.rebuildToolSession(sessionId, session, "用户消息", cb);

        // 3 次重建均应触发 invoke
        assertEquals(3, cb.invokes.size(), "3 次重建都应发送 invoke");
        // 每次重建都应广播 retry 状态（session.status = retry）
        long retryCount = cb.broadcasts.stream()
                .filter(m -> StreamMessage.Types.SESSION_STATUS.equals(m.getType()))
                .filter(m -> "retry".equals(m.getSessionStatus()))
                .count();
        assertEquals(3, retryCount, "每次重建都应广播 retry 状态");
    }

    @Test
    @DisplayName("超出限额：第 4 次应阻断 invoke 并广播含「重建已达上限」的错误消息，同时清除 toolSessionId")
    void rebuildToolSession_shouldBlockAfterMaxAttempts() {
        String sessionId = "1002";
        SkillSession session = buildSession(1002L, "user-2", "ak-def", "溢出会话");
        CapturingCallback cb = new CapturingCallback();

        // 前 3 次在限额内，应正常通过
        service.rebuildToolSession(sessionId, session, "消息", cb);
        service.rebuildToolSession(sessionId, session, "消息", cb);
        service.rebuildToolSession(sessionId, session, "消息", cb);

        // 第 4 次超限
        service.rebuildToolSession(sessionId, session, "消息", cb);

        // invoke 只应被调用 3 次，第 4 次不应触发
        assertEquals(3, cb.invokes.size(), "超限后不应再发送 invoke");

        // 第 4 次广播应包含「重建已达上限」错误消息
        StreamMessage lastBroadcast = cb.broadcasts.get(cb.broadcasts.size() - 1);
        assertEquals(StreamMessage.Types.ERROR, lastBroadcast.getType(), "超限广播应为 error 类型");
        assertNotNull(lastBroadcast.getError(), "error 字段不应为 null");
        assertTrue(lastBroadcast.getError().contains("重建已达上限"),
                "错误消息应包含「重建已达上限」，实际：" + lastBroadcast.getError());

        // 应调用 clearToolSessionId
        verify(sessionService, times(1)).clearToolSessionId(1002L);
    }

    @Test
    @DisplayName("不同会话拥有独立计数器：会话 A 耗尽后，会话 B 仍可正常重建")
    void rebuildToolSession_differentSessionsShouldHaveSeparateCounters() {
        SkillSession sessionA = buildSession(2001L, "user-a", "ak-aaa", "会话A");
        SkillSession sessionB = buildSession(2002L, "user-b", "ak-bbb", "会话B");
        CapturingCallback cbA = new CapturingCallback();
        CapturingCallback cbB = new CapturingCallback();

        // 耗尽会话 A 的计数器（3 次）
        service.rebuildToolSession("2001", sessionA, "msg", cbA);
        service.rebuildToolSession("2001", sessionA, "msg", cbA);
        service.rebuildToolSession("2001", sessionA, "msg", cbA);

        // 验证第 4 次对会话 A 已被阻断
        service.rebuildToolSession("2001", sessionA, "msg", cbA);
        assertEquals(3, cbA.invokes.size(), "会话 A 第 4 次应被阻断");

        // 会话 B 的第 1 次重建应正常通过
        service.rebuildToolSession("2002", sessionB, "msg", cbB);
        assertEquals(1, cbB.invokes.size(), "会话 B 应有独立计数器，第 1 次应成功");
        // 会话 B 未超限，不应调用 clearToolSessionId
        verify(sessionService, never()).clearToolSessionId(2002L);
    }

    @Test
    @DisplayName("超限后 consumePendingMessage 应返回 null（待重建消息已被清除）")
    void rebuildToolSession_blockedAttemptShouldClearPendingMessages() {
        String sessionId = "3001";
        SkillSession session = buildSession(3001L, "user-3", "ak-ghi", "消息清理会话");
        CapturingCallback cb = new CapturingCallback();

        // 耗尽计数器
        service.rebuildToolSession(sessionId, session, "待重试消息", cb);
        service.rebuildToolSession(sessionId, session, "待重试消息", cb);
        service.rebuildToolSession(sessionId, session, "待重试消息", cb);

        // 第 4 次触发超限清除逻辑
        service.rebuildToolSession(sessionId, session, "待重试消息", cb);

        // 超限后，待重建消息缓存应已清空
        String pending = service.consumePendingMessage(sessionId);
        assertNull(pending, "超限后 consumePendingMessage 应返回 null");
    }
}
