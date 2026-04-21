# Personal Scope 云端协议本地 Agent 支持 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 `PersonalScopeStrategy` 里新增按事件顶层 `protocol` 字段分派的逻辑，让个人助理 scope 可识别"本地部署 + 云端事件格式子集"的 agent，并把它的出站事件路由到 `CloudEventTranslator`。

**Architecture:** 唯一业务修改点是 `PersonalScopeStrategy.java` —— 构造器新增 `CloudEventTranslator` 注入，`translateEvent` 按事件 `protocol` 字段分派：缺失 / `opencode` → `OpenCodeEventTranslator`（不 warn），`cloud` → `CloudEventTranslator`（DEBUG 日志），空串 / 其它未知非空值 → `OpenCodeEventTranslator` fallback + `WARN` 日志。测试基线对齐 `EventTranslationScopeTest:49`。`CloudEventTranslator` / `OpenCodeEventTranslator` / `GatewayMessageRouter` / `BusinessScopeStrategy` / `AssistantScopeStrategy` 接口 / GW 代码 **全部零改动**。

**Tech Stack:** Java 21, Spring Boot 3.4, JUnit 5, Mockito, Jackson JsonNode, Logback (ListAppender for log assertions)

**Spec:** `docs/superpowers/specs/2026-04-21-personal-scope-cloud-protocol-design.md`

---

## File Map

| File | Action | Responsibility |
|---|---|---|
| `skill-server/src/main/java/com/opencode/cui/skill/service/scope/PersonalScopeStrategy.java` | Modify | 构造器新增 `CloudEventTranslator` 参数；`translateEvent` 按 `event.protocol` 分派 |
| `skill-server/src/test/java/com/opencode/cui/skill/service/scope/PersonalScopeStrategyTest.java` | Create | 分派逻辑单元测试（7 条覆盖 null / 缺失 / opencode / cloud / CLOUD / 空串 / mcp） |
| `skill-server/src/test/java/com/opencode/cui/skill/service/scope/PersonalScopeCloudProtocolIntegrationTest.java` | Create | 真实 `CloudEventTranslator` 集成测试（partSeq 递增、session idle 清理） |
| `skill-server/src/test/java/com/opencode/cui/skill/service/EventTranslationScopeTest.java` | Modify | `setUp()` 的 `PersonalScopeStrategy` 构造器调用加 `cloudEventTranslator` 参数 |

---

## Task 1: 构造器注入 CloudEventTranslator + null event 处理 + 测试基线对齐

**目的**: 打通"构造器签名变更"的编译基线。加 `CloudEventTranslator` 注入；`translateEvent` 先保守实现（null → null，其它统一委派 `OpenCodeEventTranslator`，保留老行为）；同步修 `EventTranslationScopeTest` 构造器调用，避免 maven 测试编译失败。

**Files:**
- Create: `skill-server/src/test/java/com/opencode/cui/skill/service/scope/PersonalScopeStrategyTest.java`
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/service/scope/PersonalScopeStrategy.java`
- Modify: `skill-server/src/test/java/com/opencode/cui/skill/service/EventTranslationScopeTest.java`

- [ ] **Step 1: 新建 PersonalScopeStrategyTest.java，写第一条 `nullEvent` 测试**

文件内容（使用**目标**双参数构造器——此时代码尚未更新，意图先让测试 red）：

```java
package com.opencode.cui.skill.service.scope;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opencode.cui.skill.model.StreamMessage;
import com.opencode.cui.skill.service.CloudEventTranslator;
import com.opencode.cui.skill.service.OpenCodeEventTranslator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
@DisplayName("PersonalScopeStrategy")
class PersonalScopeStrategyTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private OpenCodeEventTranslator openCodeEventTranslator;

    @Mock
    private CloudEventTranslator cloudEventTranslator;

    private PersonalScopeStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new PersonalScopeStrategy(openCodeEventTranslator, cloudEventTranslator);
    }

    @Test
    @DisplayName("null event returns null and invokes neither translator")
    void translateEvent_nullEvent_returnsNull() {
        StreamMessage result = strategy.translateEvent(null, "session-1");

        assertNull(result);
        verifyNoInteractions(openCodeEventTranslator);
        verifyNoInteractions(cloudEventTranslator);
    }
}
```

- [ ] **Step 2: 跑测试确认编译失败**

Run:
```bash
mvn -pl skill-server -Dtest=PersonalScopeStrategyTest test
```
Expected: **编译失败**，原因 `PersonalScopeStrategy` 当前构造器只接受 1 个参数（`OpenCodeEventTranslator`），新测试代码传了 2 个。

- [ ] **Step 3: 修改 `PersonalScopeStrategy.java`——构造器加 `CloudEventTranslator` + `translateEvent` 加 null check**

用下面整段内容替换 `skill-server/src/main/java/com/opencode/cui/skill/service/scope/PersonalScopeStrategy.java` 的完整类体：

```java
package com.opencode.cui.skill.service.scope;

import com.fasterxml.jackson.databind.JsonNode;
import com.opencode.cui.skill.model.AssistantInfo;
import com.opencode.cui.skill.model.InvokeCommand;
import com.opencode.cui.skill.model.StreamMessage;
import com.opencode.cui.skill.service.CloudEventTranslator;
import com.opencode.cui.skill.service.OpenCodeEventTranslator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 个人助理策略。
 * <ul>
 *   <li>invoke 通过 AI-Gateway WebSocket 协议发送</li>
 *   <li>toolSessionId 由 Agent 返回 session_created 回调时绑定</li>
 *   <li>需要 Agent 在线检查</li>
 *   <li>事件翻译：按 event 顶层 `protocol` 字段分派
 *       <ul>
 *         <li>缺失 / `opencode`（不分大小写）→ {@link OpenCodeEventTranslator}</li>
 *         <li>`cloud`（不分大小写）→ {@link CloudEventTranslator}</li>
 *         <li>空串或其它非空未知值 → WARN + fallback 到 {@link OpenCodeEventTranslator}</li>
 *       </ul>
 *   </li>
 * </ul>
 */
@Slf4j
@Component
public class PersonalScopeStrategy implements AssistantScopeStrategy {

    private final OpenCodeEventTranslator openCodeEventTranslator;
    private final CloudEventTranslator cloudEventTranslator;

    public PersonalScopeStrategy(OpenCodeEventTranslator openCodeEventTranslator,
                                 CloudEventTranslator cloudEventTranslator) {
        this.openCodeEventTranslator = openCodeEventTranslator;
        this.cloudEventTranslator = cloudEventTranslator;
    }

    @Override
    public String getScope() {
        return "personal";
    }

    /**
     * 个人助手的 invoke 构建。
     * 当前返回 null 作为占位——实际发送逻辑仍在 GatewayRelayService 中。
     */
    @Override
    public String buildInvoke(InvokeCommand command, AssistantInfo info) {
        log.debug("PersonalScopeStrategy.buildInvoke: placeholder, ak={}, action={}",
                command.ak(), command.action());
        return null;
    }

    /**
     * 个人助手不预生成 toolSessionId，由 Agent session_created 回调时绑定。
     */
    @Override
    public String generateToolSessionId() {
        return null;
    }

    @Override
    public boolean requiresSessionCreatedCallback() {
        return true;
    }

    @Override
    public boolean requiresOnlineCheck() {
        return true;
    }

    @Override
    public StreamMessage translateEvent(JsonNode event, String sessionId) {
        if (event == null) {
            return null;
        }
        // Task 1：保守实现，non-null 统一走 opencode；cloud/warn 分支在后续 Task 引入。
        return openCodeEventTranslator.translate(event);
    }
}
```

- [ ] **Step 4: 跑测试，预期 `PersonalScopeStrategyTest` 绿但 `EventTranslationScopeTest` 编译失败**

Run:
```bash
mvn -pl skill-server test
```
Expected: 编译错误指向 `EventTranslationScopeTest.java:49` ——`PersonalScopeStrategy(openCodeEventTranslator)` 参数不匹配。

- [ ] **Step 5: 修改 `EventTranslationScopeTest.java:49` 构造器调用**

用 Edit 把：

```java
personalStrategy = new PersonalScopeStrategy(openCodeEventTranslator);
```

替换为：

```java
personalStrategy = new PersonalScopeStrategy(openCodeEventTranslator, cloudEventTranslator);
```

注意：`cloudEventTranslator` 字段在此文件已存在（line 35 `@Mock private CloudEventTranslator cloudEventTranslator;`），无需新增声明。

- [ ] **Step 6: 跑 skill-server 全量测试，确认全绿**

Run:
```bash
mvn -pl skill-server test
```
Expected: BUILD SUCCESS，所有测试通过（包含新增的 `PersonalScopeStrategyTest`、修正后的 `EventTranslationScopeTest`）。

- [ ] **Step 7: Commit**

```bash
git add skill-server/src/main/java/com/opencode/cui/skill/service/scope/PersonalScopeStrategy.java \
        skill-server/src/test/java/com/opencode/cui/skill/service/scope/PersonalScopeStrategyTest.java \
        skill-server/src/test/java/com/opencode/cui/skill/service/EventTranslationScopeTest.java
git commit -m "refactor(personal-scope): inject CloudEventTranslator into PersonalScopeStrategy

Preparatory constructor change for protocol-based dispatch. Adds nullEvent
unit test; aligns EventTranslationScopeTest constructor call. No dispatch
behavior change yet — non-null events still go to OpenCodeEventTranslator.

Refs: docs/superpowers/specs/2026-04-21-personal-scope-cloud-protocol-design.md §6"
```

---

## Task 2: 引入 Logback ListAppender + 缺失 / `opencode` 分派显式测试（含 "no warn" 断言）

**目的**:
1. 把 Logback `ListAppender` 引入 `PersonalScopeStrategyTest`（前移自原 Task 4），让所有后续测试都能断言 WARN 数量。
2. 把"缺失字段 → opencode""显式 `opencode` → opencode"两条路径加测试显式锁住，并 **显式断言 `assertEquals(0, countWarnLogs())`**，满足 spec §10.1 的 "不产生 warn" 要求。

Task 1 的实现已经天然满足行为预期，此 Task 只补测试基础设施 + 测试用例，不改实现代码。

**Files:**
- Modify: `skill-server/src/test/java/com/opencode/cui/skill/service/scope/PersonalScopeStrategyTest.java`

- [ ] **Step 1: 在 `PersonalScopeStrategyTest` 字段区后注入 Logback appender 基础设施**

在 `@BeforeEach void setUp()` 方法**之前**（`@Mock` 字段声明之后）新增：

```java
    private ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> logAppender;
    private ch.qos.logback.classic.Logger strategyLogger;

    @BeforeEach
    void attachLogAppender() {
        strategyLogger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(PersonalScopeStrategy.class);
        logAppender = new ch.qos.logback.core.read.ListAppender<>();
        logAppender.start();
        strategyLogger.addAppender(logAppender);
    }

    @org.junit.jupiter.api.AfterEach
    void detachLogAppender() {
        if (strategyLogger != null && logAppender != null) {
            strategyLogger.detachAppender(logAppender);
        }
    }

    private long countWarnLogs() {
        return logAppender.list.stream()
                .filter(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN)
                .count();
    }
```

JUnit 5 会执行所有 `@BeforeEach` 方法（按声明顺序），`attachLogAppender()` 与已有的 `setUp()`（Mockito mock 装配）不冲突。

- [ ] **Step 2: 为已有的 `translateEvent_nullEvent_returnsNull` 追加 warn 数量断言**

用 Edit 在 `translateEvent_nullEvent_returnsNull` 方法末尾 `verifyNoInteractions(cloudEventTranslator);` 这行下方追加：

```java
        org.junit.jupiter.api.Assertions.assertEquals(0, countWarnLogs(),
                "null event must not produce any WARN log");
```

- [ ] **Step 3: 在 `nullEvent` 测试下方新增两条"不 warn"测试**

```java
    @Test
    @DisplayName("no protocol field delegates to OpenCodeEventTranslator, no warn")
    void translateEvent_noProtocolField_delegatesToOpenCode() {
        ObjectNode event = objectMapper.createObjectNode();
        event.put("type", "message.part.updated");
        StreamMessage expected = StreamMessage.builder()
                .type(StreamMessage.Types.TEXT_DELTA).content("hi").build();
        when(openCodeEventTranslator.translate(event)).thenReturn(expected);

        StreamMessage result = strategy.translateEvent(event, "session-1");

        assertSame(expected, result);
        verify(openCodeEventTranslator).translate(event);
        verifyNoInteractions(cloudEventTranslator);
        org.junit.jupiter.api.Assertions.assertEquals(0, countWarnLogs(),
                "missing protocol field must not produce a WARN log");
    }

    @Test
    @DisplayName("protocol=opencode (lowercase) delegates to OpenCodeEventTranslator, no warn")
    void translateEvent_protocolOpencode_delegatesToOpenCode() {
        ObjectNode event = objectMapper.createObjectNode();
        event.put("protocol", "opencode");
        event.put("type", "message.part.updated");
        StreamMessage expected = StreamMessage.builder()
                .type(StreamMessage.Types.TEXT_DELTA).content("x").build();
        when(openCodeEventTranslator.translate(event)).thenReturn(expected);

        StreamMessage result = strategy.translateEvent(event, "session-1");

        assertSame(expected, result);
        verify(openCodeEventTranslator).translate(event);
        verifyNoInteractions(cloudEventTranslator);
        org.junit.jupiter.api.Assertions.assertEquals(0, countWarnLogs(),
                "explicit protocol=opencode must not produce a WARN log");
    }
```

同时在该类文件顶部 import 区补齐（`assertEquals` 用全限定名，无需额外 import）：

```java
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
```

- [ ] **Step 4: 跑测试**

Run:
```bash
mvn -pl skill-server -Dtest=PersonalScopeStrategyTest test
```
Expected: 3 条测试全部通过，`countWarnLogs()` 均为 0。

- [ ] **Step 5: Commit**

```bash
git add skill-server/src/test/java/com/opencode/cui/skill/service/scope/PersonalScopeStrategyTest.java
git commit -m "test(personal-scope): lock missing/opencode dispatch with 'no warn' assertions

Adds Logback ListAppender infrastructure and asserts countWarnLogs()==0
for null / no-protocol-field / protocol=opencode paths, per spec §10.1.
Implementation unchanged.

Refs: docs/superpowers/specs/2026-04-21-personal-scope-cloud-protocol-design.md §4.2, §10.1"
```

---

## Task 3: `protocol=cloud` 分派到 CloudEventTranslator

**目的**: 加入 `cloud` 分支（含大小写不敏感）+ DEBUG 日志；测试验证 sessionId 透传给 `CloudEventTranslator`。

**Files:**
- Modify: `skill-server/src/test/java/com/opencode/cui/skill/service/scope/PersonalScopeStrategyTest.java`
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/service/scope/PersonalScopeStrategy.java`

- [ ] **Step 1: 新增两条 cloud 分派测试**

在 `PersonalScopeStrategyTest` 末尾新增（最后一个方法的 `}` 之前）：

```java
    @Test
    @DisplayName("protocol=cloud delegates to CloudEventTranslator with sessionId passthrough, no warn")
    void translateEvent_protocolCloud_delegatesToCloud() {
        ObjectNode event = objectMapper.createObjectNode();
        event.put("protocol", "cloud");
        event.put("type", StreamMessage.Types.TEXT_DELTA);
        StreamMessage expected = StreamMessage.builder()
                .type(StreamMessage.Types.TEXT_DELTA).content("cloud-content").build();
        when(cloudEventTranslator.translate(event, "session-X")).thenReturn(expected);

        StreamMessage result = strategy.translateEvent(event, "session-X");

        assertSame(expected, result);
        verify(cloudEventTranslator).translate(event, "session-X");
        verifyNoInteractions(openCodeEventTranslator);
        org.junit.jupiter.api.Assertions.assertEquals(0, countWarnLogs(),
                "protocol=cloud must not produce WARN (DEBUG only)");
    }

    @Test
    @DisplayName("protocol=CLOUD (uppercase) also delegates to CloudEventTranslator, no warn")
    void translateEvent_protocolCloudUpperCase_caseInsensitive() {
        ObjectNode event = objectMapper.createObjectNode();
        event.put("protocol", "CLOUD");
        event.put("type", StreamMessage.Types.TEXT_DELTA);
        StreamMessage expected = StreamMessage.builder()
                .type(StreamMessage.Types.TEXT_DELTA).content("c").build();
        when(cloudEventTranslator.translate(event, "session-Y")).thenReturn(expected);

        StreamMessage result = strategy.translateEvent(event, "session-Y");

        assertSame(expected, result);
        verify(cloudEventTranslator).translate(event, "session-Y");
        verifyNoInteractions(openCodeEventTranslator);
        org.junit.jupiter.api.Assertions.assertEquals(0, countWarnLogs(),
                "protocol=CLOUD must not produce WARN (DEBUG only)");
    }
```

- [ ] **Step 2: 跑测试确认新用例失败**

Run:
```bash
mvn -pl skill-server -Dtest=PersonalScopeStrategyTest test
```
Expected: 两条 cloud 用例 **FAIL**——Task 1 实现对 cloud 事件也调的是 `openCodeEventTranslator.translate(event)`，没调 `cloudEventTranslator`。

- [ ] **Step 3: 修改 `PersonalScopeStrategy.translateEvent` 加入 cloud 分支**

用 Edit 把 `translateEvent` 方法体从：

```java
    @Override
    public StreamMessage translateEvent(JsonNode event, String sessionId) {
        if (event == null) {
            return null;
        }
        // Task 1：保守实现，non-null 统一走 opencode；cloud/warn 分支在后续 Task 引入。
        return openCodeEventTranslator.translate(event);
    }
```

替换为：

```java
    @Override
    public StreamMessage translateEvent(JsonNode event, String sessionId) {
        if (event == null) {
            return null;
        }
        JsonNode protocolNode = event.path("protocol");
        // 缺失字段（含 missing/null 节点）→ opencode，不 warn
        if (protocolNode.isMissingNode() || protocolNode.isNull()) {
            return openCodeEventTranslator.translate(event);
        }
        String protocol = protocolNode.asText("");
        if ("cloud".equalsIgnoreCase(protocol)) {
            log.debug("[PersonalScope] dispatch: protocol=cloud, type={}, sessionId={}",
                    event.path("type").asText(""), sessionId);
            return cloudEventTranslator.translate(event, sessionId);
        }
        // Task 3：显式 opencode / 未知值 都走 opencode（未知值的 warn 将在 Task 4 引入）
        return openCodeEventTranslator.translate(event);
    }
```

- [ ] **Step 4: 跑测试**

Run:
```bash
mvn -pl skill-server -Dtest=PersonalScopeStrategyTest test
```
Expected: 5 条测试全部通过（`nullEvent` / `noProtocolField` / `protocolOpencode` / `protocolCloud` / `protocolCloudUpperCase`）。

- [ ] **Step 5: 跑 skill-server 全量确认没破坏其他测试**

Run:
```bash
mvn -pl skill-server test
```
Expected: BUILD SUCCESS。

- [ ] **Step 6: Commit**

```bash
git add skill-server/src/main/java/com/opencode/cui/skill/service/scope/PersonalScopeStrategy.java \
        skill-server/src/test/java/com/opencode/cui/skill/service/scope/PersonalScopeStrategyTest.java
git commit -m "feat(personal-scope): dispatch protocol=cloud events to CloudEventTranslator

Reads event.protocol top-level field; case-insensitive match for 'cloud'
routes to CloudEventTranslator with sessionId passthrough. DEBUG log on
cloud dispatch. Opencode / missing / unknown values still go to
OpenCodeEventTranslator (unknown-value warn comes in next commit).

Refs: docs/superpowers/specs/2026-04-21-personal-scope-cloud-protocol-design.md §4.2, §6.1"
```

---

## Task 4: 空串 / 未知非空值 → WARN + fallback

**目的**: 把 fallback 路径上的 `log.warn` 加上（实现）；并验证空串 / `mcp` 两类输入确实产生 **一条** WARN 日志（Logback ListAppender 在 Task 2 已就绪，这里直接用）。

**Files:**
- Modify: `skill-server/src/test/java/com/opencode/cui/skill/service/scope/PersonalScopeStrategyTest.java`
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/service/scope/PersonalScopeStrategy.java`

- [ ] **Step 1: 在类末尾加入空串 / 未知值两条测试**

在 `PersonalScopeStrategyTest` 最后一个测试方法的 `}` 之前新增：

```java
    @Test
    @DisplayName("protocol='' emits WARN and falls back to OpenCodeEventTranslator")
    void translateEvent_protocolEmptyString_warnsAndFallsBackToOpenCode() {
        ObjectNode event = objectMapper.createObjectNode();
        event.put("protocol", "");
        event.put("type", "message.part.updated");
        StreamMessage expected = StreamMessage.builder()
                .type(StreamMessage.Types.TEXT_DELTA).content("fb").build();
        when(openCodeEventTranslator.translate(event)).thenReturn(expected);

        StreamMessage result = strategy.translateEvent(event, "session-E");

        assertSame(expected, result);
        verify(openCodeEventTranslator).translate(event);
        verifyNoInteractions(cloudEventTranslator);
        org.junit.jupiter.api.Assertions.assertEquals(1, countWarnLogs(),
                "expected exactly one WARN log for empty protocol");
    }

    @Test
    @DisplayName("protocol=mcp (unknown) emits WARN and falls back to OpenCodeEventTranslator")
    void translateEvent_protocolUnknownValue_warnsAndFallsBackToOpenCode() {
        ObjectNode event = objectMapper.createObjectNode();
        event.put("protocol", "mcp");
        event.put("type", "message.part.updated");
        StreamMessage expected = StreamMessage.builder()
                .type(StreamMessage.Types.TEXT_DELTA).content("fb").build();
        when(openCodeEventTranslator.translate(event)).thenReturn(expected);

        StreamMessage result = strategy.translateEvent(event, "session-U");

        assertSame(expected, result);
        verify(openCodeEventTranslator).translate(event);
        verifyNoInteractions(cloudEventTranslator);
        org.junit.jupiter.api.Assertions.assertEquals(1, countWarnLogs(),
                "expected exactly one WARN log for unknown protocol value");
    }
```

- [ ] **Step 2: 跑测试确认新用例失败**

Run:
```bash
mvn -pl skill-server -Dtest=PersonalScopeStrategyTest test
```
Expected: 两条新测试 **FAIL**——Task 3 实现下未知值/空串确实走了 opencode，但没打 warn（`countWarnLogs()` 返回 0）。

- [ ] **Step 3: 修改 `PersonalScopeStrategy.translateEvent` 加入 warn 分支**

用 Edit 把 `translateEvent` 方法体中：

```java
        // Task 3：显式 opencode / 未知值 都走 opencode（未知值的 warn 将在 Task 4 引入）
        return openCodeEventTranslator.translate(event);
```

替换为：

```java
        if ("opencode".equalsIgnoreCase(protocol)) {
            return openCodeEventTranslator.translate(event);
        }
        // 空串或其它非空未知值 → warn + fallback opencode
        log.warn("[PersonalScope] unknown protocol value=\"{}\", fallback to OpenCodeEventTranslator, type={}, sessionId={}",
                protocol, event.path("type").asText(""), sessionId);
        return openCodeEventTranslator.translate(event);
```

实现后 `translateEvent` 完整方法形如（供对照，以实际 Edit 结果为准）：

```java
    @Override
    public StreamMessage translateEvent(JsonNode event, String sessionId) {
        if (event == null) {
            return null;
        }
        JsonNode protocolNode = event.path("protocol");
        if (protocolNode.isMissingNode() || protocolNode.isNull()) {
            return openCodeEventTranslator.translate(event);
        }
        String protocol = protocolNode.asText("");
        if ("cloud".equalsIgnoreCase(protocol)) {
            log.debug("[PersonalScope] dispatch: protocol=cloud, type={}, sessionId={}",
                    event.path("type").asText(""), sessionId);
            return cloudEventTranslator.translate(event, sessionId);
        }
        if ("opencode".equalsIgnoreCase(protocol)) {
            return openCodeEventTranslator.translate(event);
        }
        log.warn("[PersonalScope] unknown protocol value=\"{}\", fallback to OpenCodeEventTranslator, type={}, sessionId={}",
                protocol, event.path("type").asText(""), sessionId);
        return openCodeEventTranslator.translate(event);
    }
```

- [ ] **Step 4: 跑测试**

Run:
```bash
mvn -pl skill-server -Dtest=PersonalScopeStrategyTest test
```
Expected: 7 条测试全部通过（原 5 条 + 空串 + 未知值）。

- [ ] **Step 5: 跑 skill-server 全量**

Run:
```bash
mvn -pl skill-server test
```
Expected: BUILD SUCCESS。

- [ ] **Step 6: Commit**

```bash
git add skill-server/src/main/java/com/opencode/cui/skill/service/scope/PersonalScopeStrategy.java \
        skill-server/src/test/java/com/opencode/cui/skill/service/scope/PersonalScopeStrategyTest.java
git commit -m "feat(personal-scope): warn on empty/unknown protocol values, fallback to opencode

Empty string and unknown non-empty protocol values (e.g. 'mcp') now emit
WARN log and fall back to OpenCodeEventTranslator. Missing field stays
silent for backward compatibility with legacy opencode agents. Adds
Logback ListAppender assertions in tests.

Refs: docs/superpowers/specs/2026-04-21-personal-scope-cloud-protocol-design.md §4.2 (dispatch matrix last two rows), §10.1"
```

---

## Task 5: Router 入口集成测试（真实 PersonalScopeStrategy + 真实 CloudEventTranslator）

**目的**: 按 spec §10.2 的入口要求，**喂事件给 `GatewayMessageRouter.route("tool_event", ...)`**（该 public 入口会调用 private `handleToolEvent`，进而进入 `scopeDispatcher.getStrategy("personal").translateEvent(event, sessionId)`）。

本测试的核心价值是验证 **router → strategy → CloudEventTranslator 的真实接线 + sessionId 透传**。router 的其他依赖（session 服务、持久化、emitter 等）全部 mock；`scopeDispatcher.getStrategy("personal")` 返回**真实** `PersonalScopeStrategy`（构造时注入**真实** `CloudEventTranslator` + `OpenCodeEventTranslator`）。

同时锁定 §5.2 G4 的 partSeq 当前实现语义：同一 partId 多次 `text.delta` → partSeq 自增；`session.status=idle` → partSeqCounters 清理。

**注意**：session.status 的 helper **只发 `properties.sessionStatus="idle"`**，不再同时发 `status` 字段——严格匹配 spec §5.1 的当前支持子集契约。这样可以防止未来代码因只依赖"status fallback 分支"而错误通过测试。

**Files:**
- Create: `skill-server/src/test/java/com/opencode/cui/skill/service/scope/PersonalScopeCloudProtocolIntegrationTest.java`

- [ ] **Step 1: 新建 router 入口集成测试**

完整内容（参照 `GatewayMessageRouterImPushTest` 的 mock 模式）：

```java
package com.opencode.cui.skill.service.scope;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opencode.cui.skill.model.SkillSession;
import com.opencode.cui.skill.model.StreamMessage;
import com.opencode.cui.skill.service.AssistantInfoService;
import com.opencode.cui.skill.service.CloudEventTranslator;
import com.opencode.cui.skill.service.GatewayMessageRouter;
import com.opencode.cui.skill.service.ImInteractionStateService;
import com.opencode.cui.skill.service.ImOutboundService;
import com.opencode.cui.skill.service.MessagePersistenceService;
import com.opencode.cui.skill.service.OpenCodeEventTranslator;
import com.opencode.cui.skill.service.RedisMessageBroker;
import com.opencode.cui.skill.service.SessionRebuildService;
import com.opencode.cui.skill.service.SessionRouteService;
import com.opencode.cui.skill.service.SkillInstanceRegistry;
import com.opencode.cui.skill.service.SkillMessageService;
import com.opencode.cui.skill.service.SkillSessionService;
import com.opencode.cui.skill.service.StreamBufferService;
import com.opencode.cui.skill.service.TranslatorSessionCache;
import com.opencode.cui.skill.service.delivery.OutboundDeliveryDispatcher;
import com.opencode.cui.skill.service.delivery.StreamMessageEmitter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Router 入口集成测试：喂 tool_event 给 GatewayMessageRouter.route，
 * 验证 router → PersonalScopeStrategy → CloudEventTranslator 的真实接线
 * 与 sessionId 透传，并锁定 §5.2 G4 的 partSeq 当前实现语义。
 *
 * <p>scopeDispatcher 和两个 Translator 使用真实实现（非 mock），
 * 其他下游服务（session / persistence / buffer / emitter / ...）全部 mock。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PersonalScopeCloudProtocolIntegration (router-entry)")
class PersonalScopeCloudProtocolIntegrationTest {

    private static final String LOCAL_INSTANCE = "ss-test-local";
    private static final String SESSION_ID = "100";
    private static final String AK = "ak-personal-1";
    private static final String USER_ID = "user-1";

    private final ObjectMapper objectMapper = new ObjectMapper();

    // --- mocked router dependencies ---
    @Mock private SkillMessageService messageService;
    @Mock private SkillSessionService sessionService;
    @Mock private RedisMessageBroker redisMessageBroker;
    @Mock private OpenCodeEventTranslator legacyTranslatorArg; // 未使用，仅用于满足构造器签名（router 通过 scopeDispatcher 拿 translator）
    @Mock private MessagePersistenceService persistenceService;
    @Mock private StreamBufferService bufferService;
    @Mock private SessionRebuildService rebuildService;
    @Mock private ImInteractionStateService interactionStateService;
    @Mock private ImOutboundService imOutboundService;
    @Mock private SessionRouteService sessionRouteService;
    @Mock private SkillInstanceRegistry skillInstanceRegistry;
    @Mock private AssistantInfoService assistantInfoService;
    @Mock private OutboundDeliveryDispatcher outboundDeliveryDispatcher;
    @Mock private StreamMessageEmitter emitter;

    // --- real dispatcher + strategies + translators ---
    private AssistantScopeDispatcher scopeDispatcher;
    private PersonalScopeStrategy personalStrategy;
    private CloudEventTranslator cloudEventTranslator;
    private OpenCodeEventTranslator openCodeEventTranslator;

    private GatewayMessageRouter router;

    @BeforeEach
    void setUp() throws Exception {
        // 1. 构造真实 CloudEventTranslator（手工触发 @PostConstruct init()）
        cloudEventTranslator = new CloudEventTranslator();
        java.lang.reflect.Method init = CloudEventTranslator.class.getDeclaredMethod("init");
        init.setAccessible(true);
        init.invoke(cloudEventTranslator);

        // 2. 构造真实 OpenCodeEventTranslator
        openCodeEventTranslator = new OpenCodeEventTranslator(
                objectMapper, new TranslatorSessionCache());

        // 3. 构造真实 PersonalScopeStrategy（注入真 translators）
        personalStrategy = new PersonalScopeStrategy(openCodeEventTranslator, cloudEventTranslator);

        // 4. 真 scope dispatcher，只注册 personal 策略（business 这里不测）
        scopeDispatcher = new AssistantScopeDispatcher(List.of(personalStrategy));

        // 5. 装配 router
        lenient().when(skillInstanceRegistry.getInstanceId()).thenReturn(LOCAL_INSTANCE);
        lenient().when(sessionRouteService.getOwnerInstance(any())).thenReturn(LOCAL_INSTANCE);
        lenient().when(assistantInfoService.getCachedScope(anyString())).thenReturn("personal");
        // session 激活不触发额外 broadcast（我们只关心 translateEvent 产物）
        lenient().when(sessionService.activateSession(anyLong())).thenReturn(false);
        SkillSession fakeSession = new SkillSession();
        fakeSession.setId(Long.valueOf(SESSION_ID));
        lenient().when(sessionService.findByIdSafe(anyLong())).thenReturn(fakeSession);

        router = new GatewayMessageRouter(
                objectMapper,
                messageService,
                sessionService,
                redisMessageBroker,
                legacyTranslatorArg, // router 构造器需要 OpenCodeEventTranslator，但实际翻译走 scopeDispatcher
                persistenceService,
                bufferService,
                rebuildService,
                interactionStateService,
                imOutboundService,
                sessionRouteService,
                skillInstanceRegistry,
                assistantInfoService,
                scopeDispatcher,
                outboundDeliveryDispatcher,
                emitter,
                120);
    }

    @Test
    @DisplayName("cloud text.delta through router: same partId -> partSeq 0 then 1 (current impl, G4)")
    void routerCloudTextDelta_partSeqIncrements() {
        router.route("tool_event", AK, USER_ID, buildToolEventNode(buildCloudTextDelta("m1", "p1", "hello ")));
        router.route("tool_event", AK, USER_ID, buildToolEventNode(buildCloudTextDelta("m1", "p1", "world")));

        // router 的助手消息最终流向 persistenceService/bufferService/emitter 等，
        // 这里用 emitter 捕获所有下游 StreamMessage（最稳定，StreamMessageEmitter 是 §1 架构中的唯一出站点）。
        ArgumentCaptor<StreamMessage> captor = ArgumentCaptor.forClass(StreamMessage.class);
        verify(emitter, atLeastOnce()).enrich(any(), any(), captor.capture());

        List<StreamMessage> captured = captor.getAllValues().stream()
                .filter(m -> StreamMessage.Types.TEXT_DELTA.equals(m.getType()))
                .toList();
        assertEquals(2, captured.size(), "expected two TEXT_DELTA emitted by emitter");
        assertEquals("hello ", captured.get(0).getContent());
        assertEquals("world", captured.get(1).getContent());
        assertEquals(Integer.valueOf(0), captured.get(0).getPartSeq(),
                "first event for partId gets partSeq=0 per current CloudEventTranslator impl (G4)");
        assertEquals(Integer.valueOf(1), captured.get(1).getPartSeq(),
                "second event for same partId gets partSeq=1 (G4)");
    }

    @Test
    @DisplayName("session.status=idle through router clears partSeq counter; next text.delta restarts at 0")
    void routerSessionIdle_clearsPartSeqCounter() {
        router.route("tool_event", AK, USER_ID, buildToolEventNode(buildCloudTextDelta("m1", "p1", "a")));
        router.route("tool_event", AK, USER_ID, buildToolEventNode(buildCloudSessionStatusIdle()));
        router.route("tool_event", AK, USER_ID, buildToolEventNode(buildCloudTextDelta("m1", "p1", "b")));

        ArgumentCaptor<StreamMessage> captor = ArgumentCaptor.forClass(StreamMessage.class);
        verify(emitter, atLeastOnce()).enrich(any(), any(), captor.capture());

        List<StreamMessage> allCaptured = captor.getAllValues();
        List<StreamMessage> textDeltas = allCaptured.stream()
                .filter(m -> StreamMessage.Types.TEXT_DELTA.equals(m.getType()))
                .toList();
        List<StreamMessage> statusMsgs = allCaptured.stream()
                .filter(m -> StreamMessage.Types.SESSION_STATUS.equals(m.getType()))
                .toList();

        assertEquals(2, textDeltas.size());
        assertEquals(Integer.valueOf(0), textDeltas.get(0).getPartSeq());
        assertEquals(Integer.valueOf(0), textDeltas.get(1).getPartSeq(),
                "after session.status=idle the counter is cleared; next delta for same partId restarts at 0");

        // 严格验证 idle 消息语义（而非仅类型）：证明 CloudEventTranslator.handleSessionStatus
        // 真的读到了 sessionStatus=idle，且 translate() 末尾的清理分支也被触发。
        StreamMessage idleMsg = statusMsgs.stream()
                .filter(m -> "idle".equals(m.getSessionStatus()))
                .findFirst()
                .orElse(null);
        assertNotNull(idleMsg, "expected SESSION_STATUS=idle to be emitted");
    }

    @Test
    @DisplayName("cloud tool.update through router: emits TOOL_UPDATE with status/toolName/toolCallId/output")
    void routerCloudToolUpdate_emitsToolUpdate() {
        // running（无 output）
        router.route("tool_event", AK, USER_ID, buildToolEventNode(
                buildCloudToolUpdate("m1", "p-tool-1", "call-abc", "web_search", "running", null)));
        // completed（有 output；按 §5.2 G2，output 是 String）
        router.route("tool_event", AK, USER_ID, buildToolEventNode(
                buildCloudToolUpdate("m1", "p-tool-1", "call-abc", "web_search", "completed", "result-text")));

        ArgumentCaptor<StreamMessage> captor = ArgumentCaptor.forClass(StreamMessage.class);
        verify(emitter, atLeastOnce()).enrich(any(), any(), captor.capture());

        List<StreamMessage> toolUpdates = captor.getAllValues().stream()
                .filter(m -> StreamMessage.Types.TOOL_UPDATE.equals(m.getType()))
                .toList();
        assertEquals(2, toolUpdates.size(), "expected two TOOL_UPDATE emitted by emitter");

        StreamMessage first = toolUpdates.get(0);
        assertEquals("running", first.getStatus());
        assertNotNull(first.getTool(), "first TOOL_UPDATE must carry ToolInfo");
        assertEquals("web_search", first.getTool().getToolName());
        assertEquals("call-abc", first.getTool().getToolCallId());

        StreamMessage second = toolUpdates.get(1);
        assertEquals("completed", second.getStatus());
        assertNotNull(second.getTool(), "second TOOL_UPDATE must carry ToolInfo");
        assertEquals("result-text", second.getTool().getOutput(),
                "output is String per §5.2 G2 (CloudEventTranslator.handleToolUpdate uses asText())");
    }

    @Test
    @DisplayName("opencode event (no protocol field) still routes to OpenCodeEventTranslator path")
    void routerOpencodeEvent_stillWorks() {
        // 构造最小 opencode message.part.updated 事件
        ObjectNode part = objectMapper.createObjectNode();
        part.put("id", "p-oc-1");
        part.put("type", "text");
        part.put("sessionID", "s-oc");
        part.put("messageID", "m-oc");
        part.put("text", "oc hello");
        ObjectNode props = objectMapper.createObjectNode();
        props.set("part", part);
        props.put("sessionID", "s-oc");
        props.put("messageID", "m-oc");
        ObjectNode event = objectMapper.createObjectNode();
        event.put("type", "message.part.updated");
        event.set("properties", props);

        router.route("tool_event", AK, USER_ID, buildToolEventNode(event));

        ArgumentCaptor<StreamMessage> captor = ArgumentCaptor.forClass(StreamMessage.class);
        verify(emitter, atLeastOnce()).enrich(any(), any(), captor.capture());
        StreamMessage out = captor.getAllValues().stream()
                .filter(m -> StreamMessage.Types.TEXT_DONE.equals(m.getType()))
                .findFirst()
                .orElse(null);
        assertNotNull(out, "expected TEXT_DONE from OpenCodeEventTranslator path");
        assertEquals("oc hello", out.getContent());
    }

    // ---------------- helpers ----------------

    /** 把 event 节点包成 router.route() 期望的 tool_event 消息结构。 */
    private ObjectNode buildToolEventNode(JsonNode eventNode) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", "tool_event");
        node.put("sessionId", SESSION_ID);
        node.put("ak", AK);
        node.set("event", eventNode);
        return node;
    }

    private JsonNode buildCloudTextDelta(String messageId, String partId, String content) {
        ObjectNode props = objectMapper.createObjectNode();
        props.put("messageId", messageId);
        props.put("partId", partId);
        props.put("content", content);
        ObjectNode event = objectMapper.createObjectNode();
        event.put("protocol", "cloud");
        event.put("type", "text.delta");
        event.set("properties", props);
        return event;
    }

    private JsonNode buildCloudToolUpdate(String messageId, String partId, String toolCallId,
                                          String toolName, String status, String output) {
        ObjectNode props = objectMapper.createObjectNode();
        props.put("messageId", messageId);
        props.put("partId", partId);
        props.put("toolCallId", toolCallId);
        props.put("toolName", toolName);
        props.put("status", status);
        if (output != null) {
            props.put("output", output);
        }
        // 按 §5.2 G2：input 当前实现走 asText()，规避嵌套对象；本 helper 不设 input
        // 以避免在测试里隐式引入 G2 行为依赖。
        ObjectNode event = objectMapper.createObjectNode();
        event.put("protocol", "cloud");
        event.put("type", "tool.update");
        event.set("properties", props);
        return event;
    }

    private JsonNode buildCloudSessionStatusIdle() {
        // 严格遵循 spec §5.1 当前支持子集——只发 sessionStatus，不发 status。
        // 如果未来实现错误地只依赖 status fallback 分支，该输入会暴露问题。
        ObjectNode props = objectMapper.createObjectNode();
        props.put("sessionStatus", "idle");
        ObjectNode event = objectMapper.createObjectNode();
        event.put("protocol", "cloud");
        event.put("type", "session.status");
        event.set("properties", props);
        return event;
    }
}
```

**执行者注意**：
- `verify(emitter, atLeastOnce()).enrich(any(), any(), captor.capture())` 基于 `StreamMessageEmitter.enrich(sessionId, userId, msg)` 签名；若签名与此不符（例如 `emitToSession` 是主要出站入口），请改用对应方法 + 参数数；核心是**通过 emitter mock 的 ArgumentCaptor 捕获所有出站 StreamMessage**。可参照 `GatewayMessageRouterImPushTest.setUp` 对 `emitter` 的使用模式。
- router.route 在 `tool_event` 分支内部会做 session affinity 路由（`requiresSessionAffinity`），mock `sessionRouteService.getOwnerInstance` 返回 `LOCAL_INSTANCE` 可避免消息被远程转发。
- 如果 `AssistantScopeDispatcher(List<AssistantScopeStrategy>)` 构造器签名不同（比如还需要 business 策略），此处 mock 一个 `AssistantScopeDispatcher` 让 `getStrategy("personal")` 返回真 `personalStrategy` 即可（效果等价）。

- [ ] **Step 2: 跑新建的集成测试**

Run:
```bash
mvn -pl skill-server -Dtest=PersonalScopeCloudProtocolIntegrationTest test
```
Expected: 4 条测试全部通过（cloud text.delta / cloud tool.update / cloud session.status=idle / opencode 穿透）。

若初次失败，常见原因：
- `StreamMessageEmitter` 的实际方法名/签名与 `.enrich(...)` 不符 → 改用实际的出站方法（查 `StreamMessageEmitter.java` 的 public 方法清单）
- `AssistantScopeDispatcher` 构造器不接受 `List<AssistantScopeStrategy>` → 改用 `@Mock AssistantScopeDispatcher` 并 `when(scopeDispatcher.getStrategy("personal")).thenReturn(personalStrategy)`
- 反射调 `init()` 失败 → 确认方法名是 `init`（`CloudEventTranslator` 第 58 行附近的 `@PostConstruct void init()`）
- router 内部跳过了 tool_event（e.g. 因 `completedSessions` 状态或 `isContextOverflowEvent` 过滤）→ 检查 router 源码对当前输入的处理分支

- [ ] **Step 3: 跑 skill-server 全量**

Run:
```bash
mvn -pl skill-server test
```
Expected: BUILD SUCCESS。

- [ ] **Step 4: Commit**

```bash
git add skill-server/src/test/java/com/opencode/cui/skill/service/scope/PersonalScopeCloudProtocolIntegrationTest.java
git commit -m "test(personal-scope): router-entry integration test for cloud protocol dispatch

Feeds tool_event through GatewayMessageRouter.route() with real
PersonalScopeStrategy + real CloudEventTranslator / OpenCodeEventTranslator
(other router deps mocked). Captures outbound StreamMessages via emitter
ArgumentCaptor. Verifies router->strategy->CloudEventTranslator wiring,
sessionId passthrough, partSeq increment semantics (§5.2 G4), and
session.status=idle counter cleanup. Strictly uses sessionStatus (not
status) per §5.1 subset contract.

Refs: docs/superpowers/specs/2026-04-21-personal-scope-cloud-protocol-design.md §10.2"
```

---

## Task 6: 回归验证（指定测试类清单）

**目的**: 按 spec §10.3 清单逐一确认相关测试类全绿。无代码修改、无 commit。

**Files:** 无

- [ ] **Step 1: 跑 `OpenCodeEventTranslatorTest`（opencode 路径不受影响）**

Run:
```bash
mvn -pl skill-server -Dtest=OpenCodeEventTranslatorTest test
```
Expected: BUILD SUCCESS，所有 OpenCodeEventTranslator 测试通过。

- [ ] **Step 2: 跑 `CloudEventTranslatorTest`（business/cloud 翻译不受影响）**

Run:
```bash
mvn -pl skill-server -Dtest=CloudEventTranslatorTest test
```
Expected: BUILD SUCCESS。

- [ ] **Step 3: 跑 `BusinessScopeStrategyTest`（business 分派不受影响）**

Run:
```bash
mvn -pl skill-server -Dtest=BusinessScopeStrategyTest test
```
Expected: BUILD SUCCESS。

- [ ] **Step 4: 跑 `EventTranslationScopeTest`（scope 分派主干）**

Run:
```bash
mvn -pl skill-server -Dtest=EventTranslationScopeTest test
```
Expected: BUILD SUCCESS，`S62: business ... delegates text.delta to CloudEventTranslator` 和 `S63: personal ... delegates event to OpenCodeEventTranslator` 两条仍绿。

- [ ] **Step 5: 跑上游集成回归**

Run:
```bash
mvn -pl skill-server -Dtest='SsRelayAndTakeoverTest,ImOutboundFilterTest,GatewayMessageRouterImPushTest' test
```
Expected: BUILD SUCCESS。

- [ ] **Step 6: 跑 skill-server 全量测试套件**

Run:
```bash
mvn -pl skill-server test
```
Expected: BUILD SUCCESS，所有测试绿。

---

## Self-Review Notes（已核对，v2 版对齐 plan review 反馈）

- **Spec §2.1 目标 1-5**：Task 3（cloud 分派）+ Task 4（warn）+ Task 1-5（零改动 GW / BusinessScope / 两个 Translator 接口签名——由测试回归验证） 全部覆盖。
- **Spec §4.2 分派矩阵 5 行**：Task 1（null）+ Task 2（缺失 / opencode，含 `countWarnLogs()==0` 断言）+ Task 3（cloud 含大小写，含 `countWarnLogs()==0` 断言）+ Task 4（空串 + 未知值，含 `countWarnLogs()==1` 断言）完整覆盖。
- **Spec §5.1 支持子集**：Task 5 router-entry integration test 覆盖子集中的三个代表事件 `text.delta` + `tool.update` + `session.status=idle`（session.status 仅用 `sessionStatus` 字段，严格匹配子集契约）+ opencode 穿透路径。**未列入 §5.1 支持子集的 event type**（`permission.ask` / `permission.reply` / `question` / `planning.*` / `searching` / `search_result` / `reference` / `ask_more`）——即使 `CloudEventTranslator` 已有 handler 仍**不纳入本次支持**；本地 cloud agent **不应**发送这些事件，本 plan 也不对这些事件做测试或实现保证，等后续 spec 补齐时再显式加入。
- **Spec §5.2 G4 partSeq 语义**：Task 5 集成测试显式锁定"当前实现"行为（`partSeq=0, 1` 递增 + idle 清理后重置为 0），并在测试 Javadoc 和断言 message 中明确标注这是已知 G4 差异。
- **Spec §6.1 代码实现**：Task 1 + Task 3 + Task 4 迭代叠加，最终形态与 spec §6.1 Java 代码一致。
- **Spec §6.2 测试基线对齐**：Task 1 Step 5 完成（行号 `EventTranslationScopeTest.java:49`，核实构造器调用实际在该行）。
- **Spec §10.1 单元测试 7 条**：全部落到 Task 1-4 的 7 个 `@Test` 方法（nullEvent / noProtocolField / protocolOpencode / protocolCloud / protocolCloudUpperCase / protocolEmptyString / protocolUnknownValue）。**所有 "no warn" 预期（前 5 条）均有显式 `assertEquals(0, countWarnLogs())` 断言**；后 2 条有 `assertEquals(1, countWarnLogs())` 断言。
- **Spec §10.2 集成测试**：Task 5 为 **router-entry 集成测试**——喂 event 给 `GatewayMessageRouter.route("tool_event", ...)`（public 入口）而非直接调 `strategy.translateEvent(...)`，真实验证 router → strategy → CloudEventTranslator 的接线和 sessionId 透传。scope dispatcher、两个 Translator 使用真实实现；其他 router 依赖 mock。
- **Spec §10.3 回归清单**：Task 6 逐项跑。
- **Spec §10.4 人工联调**：标记为可选，plan 不纳入。
- **Placeholder 扫描**：无 TBD / TODO / "implement later"。
- **Type consistency**：`PersonalScopeStrategy` 构造器在 Task 1 落地后全程保持 `(OpenCodeEventTranslator, CloudEventTranslator)` 两参数签名；`translateEvent` 返回 `StreamMessage`；`countWarnLogs()` helper 在 Task 2 Step 1 引入后所有后续测试一致使用；Logback 类型引用统一使用全限定名。

### Plan review 反馈处置记录

**v2（第一轮审查）**

| Finding | 处置 |
|---|---|
| 1. Task 5 应为 router 入口级集成测试，非 strategy-level | **采纳**。Task 5 改为通过 `GatewayMessageRouter.route("tool_event", ...)` 喂事件；scopeDispatcher/两个 Translator 真实；其他 router 依赖 mock；ArgumentCaptor 捕获 emitter 出站 StreamMessage。 |
| 2. "no warn" 预期未被断言，`countWarnLogs()` 引入过晚 | **采纳**。ListAppender 基础设施前移至 Task 2 Step 1；nullEvent / noProtocolField / protocolOpencode / protocolCloud / protocolCloudUpperCase 五条测试均追加 `assertEquals(0, countWarnLogs())`。 |
| 3. session.status 集成测试输入同时发 `sessionStatus` 和 `status`，弱化子集契约 | **采纳**。Task 5 `buildCloudSessionStatusIdle()` helper 只发 `sessionStatus`；同时对 idle 输出补 `assertNotNull + "idle".equals(m.getSessionStatus())` 语义断言。 |
| 4. `EventTranslationScopeTest.java:49` 行号漂移（审查引用 `:47`） | **核实后不改**。`grep -n "personalStrategy = new PersonalScopeStrategy"` 输出 `49:`；`:47` 是 `@BeforeEach` 方法签名行而非构造器调用行。审查方 v3 已自行复核并确认 `:49` 可保留。 |

**v3（第二轮审查）**

| Finding | 处置 |
|---|---|
| 1. Task 5 缺 `tool.update` 的 router-entry 覆盖（spec §10.2 要求 `text.delta` / `tool.update` / `session.status` 三类） | **采纳**。Task 5 新增第 4 条测试 `routerCloudToolUpdate_emitsToolUpdate` + `buildCloudToolUpdate()` helper：喂 running + completed 两条 `tool.update` 事件走 router，断言 `TOOL_UPDATE` 类型、status、toolName、toolCallId、output；helper 不设 `input`，避免隐式依赖 §5.2 G2 的 asText 行为。Step 2 expected 同步改为 4 条测试。 |
| 2. Self-Review 残留"其它 event type 通过 CloudEventTranslator 已有 handler 自动生效"的旧表述，与 spec §5.1 "未列入此表的 event type 不纳入支持子集" 矛盾 | **采纳**。改写为："未列入 §5.1 支持子集的 event type（permission.* / question / planning.* / searching / search_result / reference / ask_more）即使 `CloudEventTranslator` 已有 handler 仍**不纳入本次支持**；本地 cloud agent **不应**发送这些事件，本 plan 也不对这些事件做测试或实现保证。" |

---

## 提交节奏总览

| Task | Commit 数 | 说明 |
|---|---|---|
| 1 | 1 | 构造器变更 + null check + 基线对齐 |
| 2 | 1 | 缺失 / opencode 显式测试锁定（测试 only） |
| 3 | 1 | cloud 分派实现 + 测试 |
| 4 | 1 | warn 分支实现 + 日志断言测试 |
| 5 | 1 | 集成测试 |
| 6 | 0 | 纯回归验证 |

共 5 个原子 commit，每个都可独立 revert。
