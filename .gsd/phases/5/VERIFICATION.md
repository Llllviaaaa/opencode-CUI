# Phase 5 验证报告：协议规范化

> 日期：2026-03-08 | 结果：**4/4 PASS** ✅

## Must-Haves

| #   | 验证项                                                 | 结果  | 证据                                                                                |
| --- | ------------------------------------------------------ | :---: | ----------------------------------------------------------------------------------- |
| 1   | REST API 响应统一为 `{code, errormsg, data}`           |   ✅   | `ApiResponse` 在 SkillSessionController + SkillMessageController 中 15+ 处使用      |
| 2   | StreamMessage 中 session ID 字段名为 `welinkSessionId` |   ✅   | StreamMessage.java L26: `@JsonProperty("welinkSessionId")`                          |
| 3   | 代码中无 envelope 相关引用                             |   ✅   | Skill/Gateway/PC Agent 全部 `MessageEnvelope\|ProtocolAdapter` → **0 matches**      |
| 4   | Gateway 可主动查询 Agent 的 OpenCode 运行状态          |   ✅   | EventRelayService.java L121: `sendStatusQuery(ak)` + L132: `sendStatusQueryToAll()` |

## 代码证据

### MH-1: ApiResponse 包装
```
SkillMessageController.java:5: import ApiResponse
SkillMessageController.java:69: ResponseEntity<ApiResponse<SkillMessage>> sendMessage(...)
SkillSessionController.java:5: import ApiResponse
SkillSessionController.java:49: ResponseEntity<ApiResponse<SkillSession>> createSession(...)
... (15+ occurrences across 2 controllers)
```

### MH-2: welinkSessionId
```
StreamMessage.java:26: @JsonProperty("welinkSessionId")
StreamMessage.java:27: private String sessionId;
```

### MH-3: envelope 引用全清
```
PS> skill-server/src/main/java "MessageEnvelope" → (no output)
PS> ai-gateway/src/main/java "MessageEnvelope" → (no output)
PS> src/main/pc-agent/*.ts "MessageEnvelope|ProtocolAdapter" → (no output)
```

### MH-4: sendStatusQuery
```
EventRelayService.java:121: public void sendStatusQuery(String ak)
EventRelayService.java:132: public void sendStatusQueryToAll()
```

## Commits
- `fa1b822`: chore(phase-5): remove envelope/ProtocolAdapter dead code (Plan 5.1)
- `e025edf`: feat(phase-5): unified ApiResponse wrapper for REST APIs (Plan 5.2)
- `27727ed`: feat(phase-5): sendStatusQuery + welinkSessionId field rename (Plan 5.3)
