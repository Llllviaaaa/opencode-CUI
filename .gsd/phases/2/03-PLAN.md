---
phase: 2
plan: 3
wave: 2
depends_on: [2.1]
files_modified:
  - ai-gateway/src/main/java/com/opencode/cui/gateway/service/EventRelayService.java
  - ai-gateway/src/main/java/com/opencode/cui/gateway/model/MessageEnvelope.java
  - ai-gateway/src/main/java/com/opencode/cui/gateway/model/GatewayMessage.java
  - ai-gateway/src/test/java/com/opencode/cui/gateway/model/GatewayMessageTest.java
autonomous: true
user_setup: []

must_haves:
  truths:
    - "MessageEnvelope 类已删除"
    - "GatewayMessage 中无 envelope 相关字段和方法"
    - "EventRelayService.relayToSkillServer 中无 envelope 日志"
  artifacts:
    - "MessageEnvelope.java 已删除"
    - "GatewayMessage.hasEnvelope() 方法已删除"
    - "GatewayMessage.getEnvelope() 方法已删除"
---

# Plan 2.3: 清理 Envelope 残留代码

<objective>
删除协议中已移除的 envelope 相关代码，包括 MessageEnvelope 模型和所有引用。

Purpose: 协议文档已删除 envelope 层，代码中仍有残留，清理技术债。
Output: 无 envelope 引用的干净代码
</objective>

<context>
Load for context:
- ai-gateway/src/main/java/com/opencode/cui/gateway/model/MessageEnvelope.java
- ai-gateway/src/main/java/com/opencode/cui/gateway/model/GatewayMessage.java
- ai-gateway/src/main/java/com/opencode/cui/gateway/service/EventRelayService.java
- ai-gateway/src/test/java/com/opencode/cui/gateway/model/GatewayMessageTest.java
</context>

<tasks>

<task type="auto">
  <name>删除 MessageEnvelope 类和 GatewayMessage 中的 envelope 字段</name>
  <files>
    ai-gateway/src/main/java/com/opencode/cui/gateway/model/MessageEnvelope.java
    ai-gateway/src/main/java/com/opencode/cui/gateway/model/GatewayMessage.java
  </files>
  <action>
    1. 删除 MessageEnvelope.java 文件
    2. GatewayMessage 中：
       - 删除 envelope 字段（MessageEnvelope 类型）
       - 删除 hasEnvelope() 方法
       - 删除 getEnvelope() 方法
       - 删除 import MessageEnvelope 语句
       - 如有 withEnvelope 方法也删除
       - 更新 builder 模式中的 envelope 相关部分

    AVOID: 不要修改 GatewayMessage 中其他字段——仅清理 envelope 相关。
  </action>
  <verify>项目编译通过：cd ai-gateway; mvn compile -q</verify>
  <done>MessageEnvelope.java 已删除，GatewayMessage 中无 envelope 引用</done>
</task>

<task type="auto">
  <name>清理 envelope 引用并更新测试</name>
  <files>
    ai-gateway/src/main/java/com/opencode/cui/gateway/service/EventRelayService.java
    ai-gateway/src/test/java/com/opencode/cui/gateway/model/GatewayMessageTest.java
  </files>
  <action>
    1. EventRelayService.relayToSkillServer 方法中：
       - 删除 if (forwarded.hasEnvelope()) { ... } 代码块（约 L72-76）
    2. GatewayMessageTest 中：
       - 删除 hasEnvelope 相关测试用例（约 L162 和 L171 附近）
       - 删除测试中创建 envelope 的构造代码
       - 确保其余测试不受影响

    AVOID: 不要删除 GatewayMessageTest 中非 envelope 相关的测试。
  </action>
  <verify>cd ai-gateway; mvn test -pl . -Dtest=GatewayMessageTest -q</verify>
  <done>EventRelayService 中无 hasEnvelope 引用。GatewayMessageTest 通过。</done>
</task>

</tasks>

<verification>
After all tasks, verify:
- [ ] `cd ai-gateway; mvn compile -q` 编译通过
- [ ] `cd ai-gateway; mvn test -q` 所有测试通过
- [ ] `Select-String -Recurse -Path "ai-gateway/src" -Include "*.java" -Pattern "envelope|Envelope" | Where-Object { $_.Path -notmatch "target" }` 无匹配（除非 Jackson @JsonIgnoreProperties 中有自动忽略，但不应有代码引用）
- [ ] MessageEnvelope.java 文件不存在
</verification>

<success_criteria>
- [ ] MessageEnvelope 类已删除
- [ ] 代码中无 envelope 引用
- [ ] 全量测试通过
</success_criteria>
