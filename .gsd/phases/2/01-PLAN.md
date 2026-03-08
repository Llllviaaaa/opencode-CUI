---
phase: 2
plan: 1
wave: 1
depends_on: []
files_modified:
  - ai-gateway/src/main/java/com/opencode/cui/gateway/service/RedisMessageBroker.java
  - ai-gateway/src/main/java/com/opencode/cui/gateway/service/SkillRelayService.java
autonomous: true
user_setup: []

must_haves:
  truths:
    - "RedisMessageBroker 不再包含 sessionOwner 相关方法"
    - "SkillRelayService.relayToSkill 不再尝试按 session 路由，改为发给任意可用 Skill 链路"
  artifacts:
    - "setSessionOwner / getSessionOwner / clearSessionOwner / sessionOwnerKey 方法已删除"
    - "bindSession / resolveSessionOwner / touchSessionOwner / sendToBoundLink 方法已删除"
    - "SESSION_OWNER_KEY_PREFIX 常量已删除"
---

# Plan 2.1: 移除 Gateway Session 级路由

<objective>
删除 Gateway 中 session 级路由相关代码，使上行消息统一走"任意 Skill 链路"策略。

Purpose: Gap Analysis 决策——Gateway 不再维护 session 归属映射，回归纯链路转发，由 Skill Server 自行通过 DB 查 toolSessionId 做路由。
Output: 精简后的 RedisMessageBroker 和 SkillRelayService
</objective>

<context>
Load for context:
- .gsd/SPEC.md
- ai-gateway/src/main/java/com/opencode/cui/gateway/service/RedisMessageBroker.java
- ai-gateway/src/main/java/com/opencode/cui/gateway/service/SkillRelayService.java
</context>

<tasks>

<task type="auto">
  <name>删除 RedisMessageBroker 中 sessionOwner 方法</name>
  <files>ai-gateway/src/main/java/com/opencode/cui/gateway/service/RedisMessageBroker.java</files>
  <action>
    1. 删除 SESSION_OWNER_KEY_PREFIX 常量
    2. 删除 setSessionOwner(String sessionId, String instanceId, Duration ttl) 方法
    3. 删除 getSessionOwner(String sessionId) 方法
    4. 删除 clearSessionOwner(String sessionId) 方法
    5. 删除 sessionOwnerKey(String sessionId) 私有方法
    6. 更新类 Javadoc 注释，移除 session 路由相关描述

    AVOID: 不要删除 refreshSkillOwner / removeSkillOwner / hasActiveSkillOwner / getActiveSkillOwners 方法——这些是 Skill 实例级别心跳，仍然需要。
    AVOID: 不要删除 relay 相关方法——跨 Gateway 实例中继仍然需要。
  </action>
  <verify>项目编译通过：cd ai-gateway; mvn compile -q</verify>
  <done>RedisMessageBroker 中无任何 sessionOwner / SESSION_OWNER 引用</done>
</task>

<task type="auto">
  <name>简化 SkillRelayService 上行路由逻辑</name>
  <files>ai-gateway/src/main/java/com/opencode/cui/gateway/service/SkillRelayService.java</files>
  <action>
    1. 删除 sendToBoundLink(String sessionId, GatewayMessage message) 方法
    2. 删除 bindSession(String sessionId, String linkId) 方法
    3. 删除 touchSessionOwner(String sessionId, String ownerId) 方法
    4. 删除 resolveSessionOwner(String sessionId) 方法
    5. 删除 cleanupLocalBindingIfCompleted(GatewayMessage message) 方法
    6. 删除 sessionBindings ConcurrentHashMap 字段（如存在）
    7. 修改 relayToSkill(GatewayMessage message) 方法：
       - 移除按 sessionId 查路由的逻辑（sendToBoundLink 分支）
       - 直接走 sendViaDefaultLink 逻辑——选择任意可用 Skill 链路发送
       - sessionIdToBind 参数不再需要，简化 sendViaDefaultLink
    8. 修改 handleInvokeFromSkill：移除绑定 session 的逻辑
    9. 删除对 redisMessageBroker.setSessionOwner / getSessionOwner / clearSessionOwner 的所有调用

    AVOID: 不要修改 registerSkillSession / removeSkillSession——Skill WS 连接注册仍需要。
    AVOID: 不要修改 refreshOwnerState / clearOwnerState——Skill 实例心跳仍需要。
    AVOID: 不要修改 ensureRelaySubscription——跨 Gateway 中继仍需要。
  </action>
  <verify>项目编译通过：cd ai-gateway; mvn compile -q</verify>
  <done>SkillRelayService 中无 bindSession / resolveSessionOwner / sessionOwner / sendToBoundLink 引用。relayToSkill 直接选择任意 Skill 链路。</done>
</task>

</tasks>

<verification>
After all tasks, verify:
- [ ] `cd ai-gateway; mvn compile -q` 编译通过
- [ ] `Select-String -Path "ai-gateway/src/main/java/com/opencode/cui/gateway/service/RedisMessageBroker.java" -Pattern "sessionOwner"` 无匹配
- [ ] `Select-String -Path "ai-gateway/src/main/java/com/opencode/cui/gateway/service/SkillRelayService.java" -Pattern "bindSession|resolveSessionOwner|touchSessionOwner|sendToBoundLink"` 无匹配
</verification>

<success_criteria>
- [ ] Gateway 不再维护 session → Skill 实例映射
- [ ] 所有上行消息走统一的"任意 Skill 链路"路由
- [ ] 编译通过无错误
</success_criteria>
