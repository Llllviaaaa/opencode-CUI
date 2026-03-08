---
phase: 2
plan: 2
wave: 2
depends_on: [2.1]
files_modified:
  - ai-gateway/src/main/java/com/opencode/cui/gateway/service/RedisMessageBroker.java
  - ai-gateway/src/main/java/com/opencode/cui/gateway/service/EventRelayService.java
  - ai-gateway/src/main/java/com/opencode/cui/gateway/ws/AgentWebSocketHandler.java
  - ai-gateway/src/test/java/com/opencode/cui/gateway/service/EventRelayServiceTest.java
autonomous: true
user_setup: []

must_haves:
  truths:
    - "agent Redis channel 使用 ak 作为 key（agent:{ak}）"
    - "AgentWebSocketHandler 注册时用 ak 做 EventRelayService 的 key"
    - "EventRelayService 全部方法的 agentId 参数改为 ak"
  artifacts:
    - "AGENT_CHANNEL_PREFIX 保持为 agent: 不变"
    - "publishToAgent / subscribeToAgent / unsubscribeFromAgent 参数名改为 ak"
    - "EventRelayServiceTest 用 ak 字符串测试"
---

# Plan 2.2: Agent Channel 从 agentId 改为 AK

<objective>
将 Gateway 中 agent Redis channel 的 key 从内部 agentId (Long→String) 改为 ak (String)，统一全链路使用 ak 作为 Agent 路由标识。

Purpose: 消除 ak → agentId 的无谓转换，Skill Server 发 invoke(ak=xxx) 可直接路由到正确的 agent channel。
Output: EventRelayService + AgentWebSocketHandler 用 ak 注册/路由
</objective>

<context>
Load for context:
- ai-gateway/src/main/java/com/opencode/cui/gateway/service/EventRelayService.java
- ai-gateway/src/main/java/com/opencode/cui/gateway/ws/AgentWebSocketHandler.java
- ai-gateway/src/test/java/com/opencode/cui/gateway/service/EventRelayServiceTest.java
</context>

<tasks>

<task type="auto">
  <name>EventRelayService 和 RedisMessageBroker 改用 ak</name>
  <files>
    ai-gateway/src/main/java/com/opencode/cui/gateway/service/EventRelayService.java
    ai-gateway/src/main/java/com/opencode/cui/gateway/service/RedisMessageBroker.java
  </files>
  <action>
    1. RedisMessageBroker: publishToAgent / subscribeToAgent / unsubscribeFromAgent 方法的参数名从 agentId 改为 ak，Javadoc 同步更新。Channel key 不变（仍为 AGENT_CHANNEL_PREFIX + ak）。
    2. EventRelayService:
       - agentSessions 的 key 改为 ak（从 ConcurrentHashMap<String, WebSocketSession> agentSessions）
       - registerAgentSession(String agentId, ...) → registerAgentSession(String ak, ...)
       - removeAgentSession(String agentId) → removeAgentSession(String ak)
       - hasAgentSession(String agentId) → hasAgentSession(String ak)
       - relayToSkillServer(String agentId, ...) → relayToSkillServer(String ak, ...)
       - relayToAgent(String agentId, ...) → relayToAgent(String ak, ...)
       - sendToLocalAgent(String agentId, ...) → sendToLocalAgent(String ak, ...)
       - 所有日志中 agentId={} 改为 ak={}
    3. EventRelayService.relayToSkillServer 中 forwarded = message.withAgentId(agentId) 改为 message.withAk(ak)。如果 GatewayMessage 没有 withAk 方法，需要先在 GatewayMessage 中添加 ak 字段和 withAk 方法。

    AVOID: 不要修改 RedisMessageBroker 中 relay/skillOwner 相关方法——它们与 agent channel 无关。
    AVOID: 不要修改 GatewayMessage 的 agentId 字段——上行消息可能仍需 agentId 做 DB 操作，但路由 key 改为 ak。改造方式：新增 ak 字段并在路由时使用，agentId 保留用于 DB 操作。
  </action>
  <verify>项目编译通过：cd ai-gateway; mvn compile -q</verify>
  <done>EventRelayService 的 agentSessions Map key 为 ak。所有方法签名参数名为 ak。</done>
</task>

<task type="auto">
  <name>AgentWebSocketHandler 注册改用 ak</name>
  <files>
    ai-gateway/src/main/java/com/opencode/cui/gateway/ws/AgentWebSocketHandler.java
  </files>
  <action>
    1. handleRegister 方法中：
       - 当前: session.getAttributes().put(ATTR_AGENT_ID, agentId) → 增加 session.getAttributes().put("ak", akId)
       - 当前: sessionAgentMap.put(session.getId(), agentIdStr) → 改为 sessionAgentMap.put(session.getId(), akId)
       - 当前: eventRelayService.registerAgentSession(agentIdStr, session) → 改为 eventRelayService.registerAgentSession(akId, session)
       - 通知 Skill 的 agentOnline 消息中传 ak 而非 agentId
    2. afterConnectionClosed 方法中：
       - 当前: String agentId = sessionAgentMap.remove(session.getId()) → 改为 String ak = sessionAgentMap.remove(session.getId())
       - 当前: agentRegistryService.markOffline(Long.parseLong(agentId)) → 需要从 session attributes 中取 agentId(Long) 做 DB 标记
       - eventRelayService.removeAgentSession(ak)
       - offlineMsg 传 ak
    3. handleRelayToSkillServer 方法中：
       - 从 sessionAgentMap 取到的是 ak → eventRelayService.relayToSkillServer(ak, message)

    AVOID: 不要删除 ATTR_AGENT_ID — DB 操作仍需 agentId (Long)。只是路由 key 改为 ak。
  </action>
  <verify>项目编译通过：cd ai-gateway; mvn compile -q</verify>
  <done>AgentWebSocketHandler 用 ak 注册到 EventRelayService，agentId 仅用于 DB 操作。</done>
</task>

<task type="auto">
  <name>更新 EventRelayServiceTest</name>
  <files>
    ai-gateway/src/test/java/com/opencode/cui/gateway/service/EventRelayServiceTest.java
  </files>
  <action>
    1. 将测试中所有 "agent-1" 改为 "ak_test_001"（模拟真实 ak 格式）
    2. 将方法名和注释中 "agentId" → "ak"
    3. relayToSkillServerAttachesAgentIdAndRoutes → relayToSkillServerAttachesAkAndRoutes
    4. 验证断言中 m.getAgentId() 改为检查 ak 相关字段（取决于 GatewayMessage 改造结果）
    5. 确保所有现有测试逻辑不变，仅更新标识符

    AVOID: 不要删除任何现有测试——只做适配性修改。
  </action>
  <verify>cd ai-gateway; mvn test -pl . -Dtest=EventRelayServiceTest -q</verify>
  <done>EventRelayServiceTest 全部测试通过，使用 ak 作为路由 key。</done>
</task>

</tasks>

<verification>
After all tasks, verify:
- [ ] `cd ai-gateway; mvn compile -q` 编译通过
- [ ] `cd ai-gateway; mvn test -pl . -Dtest=EventRelayServiceTest` 测试通过
- [ ] `Select-String -Path "ai-gateway/src/main/java/com/opencode/cui/gateway/service/EventRelayService.java" -Pattern "registerAgentSession\(String ak"` 有匹配
- [ ] `Select-String -Path "ai-gateway/src/main/java/com/opencode/cui/gateway/ws/AgentWebSocketHandler.java" -Pattern "registerAgentSession\(akId"` 有匹配
</verification>

<success_criteria>
- [ ] agent Redis channel key 为 agent:{ak}
- [ ] AgentWebSocketHandler 注册时用 ak 做 EventRelayService key
- [ ] EventRelayServiceTest 通过
</success_criteria>
