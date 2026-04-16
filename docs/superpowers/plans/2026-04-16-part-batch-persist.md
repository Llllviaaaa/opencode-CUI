# Part 批量持久化优化 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将消息 part 的持久化从逐条 MySQL 写入改为 Redis 缓冲 + tool_done 时批量刷盘，减少 DB 往返次数。

**Architecture:** part 到达时序列化到 Redis LIST，seq 由 Redis INCR 原子分配。tool_done 触发 flush：从 Redis 读取所有缓冲 part，通过 MyBatis batch upsert 一次性写入 MySQL，然后清理 Redis key。Permission 查询兼容：先查 Redis 缓冲，降级查 DB。

**Tech Stack:** Spring Boot 3.4, Java 21, StringRedisTemplate, MyBatis XML Mapper, Jackson

**Spec:** `docs/superpowers/specs/2026-04-16-part-batch-persist-design.md`

---

## 文件结构

| 操作 | 文件 | 职责 |
|------|------|------|
| 新增 | `skill-server/src/main/java/.../service/PartBufferService.java` | Redis 缓冲操作（RPUSH/INCR/LRANGE/DEL + permission 查询） |
| 新增 | `skill-server/src/test/java/.../service/PartBufferServiceTest.java` | PartBufferService 单元测试 |
| 修改 | `skill-server/src/main/resources/mapper/SkillMessagePartMapper.xml` | 新增 batchUpsert SQL |
| 修改 | `skill-server/src/main/java/.../repository/SkillMessagePartRepository.java` | 新增 batchUpsert 接口方法 |
| 修改 | `skill-server/src/main/java/.../service/MessagePersistenceService.java` | persist 改写 Redis、flush 逻辑、permission 查询兼容 |
| 修改 | `skill-server/src/test/java/.../service/MessagePersistenceServiceTest.java` | 适配新的缓冲逻辑 |

> 以下 `...` 均指 `com/opencode/cui/skill`

---

### Task 1: SkillMessagePartRepository — 新增 batchUpsert

**Files:**
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/repository/SkillMessagePartRepository.java`
- Modify: `skill-server/src/main/resources/mapper/SkillMessagePartMapper.xml`

- [ ] **Step 1: 在 Repository 接口新增 batchUpsert 方法**

在 `SkillMessagePartRepository.java` 的 `deleteByMessageId` 方法之前添加：

```java
/** 批量 upsert：一次性写入多个分片 */
int batchUpsert(@Param("parts") List<SkillMessagePart> parts);
```

- [ ] **Step 2: 在 Mapper XML 新增 batchUpsert SQL**

在 `SkillMessagePartMapper.xml` 的 `</mapper>` 闭合标签之前添加：

```xml
<!-- Batch upsert: insert or update multiple parts in one statement -->
<insert id="batchUpsert">
    INSERT INTO skill_message_part
        (id, message_id, session_id, part_id, seq, part_type,
         content, tool_name, tool_call_id, tool_status, tool_input, tool_output, tool_error, tool_title,
         file_name, file_url, file_mime,
         tokens_in, tokens_out, cost, finish_reason,
         subagent_session_id, subagent_name,
         created_at, updated_at)
    VALUES
    <foreach collection="parts" item="p" separator=",">
        (#{p.id}, #{p.messageId}, #{p.sessionId}, #{p.partId}, #{p.seq}, #{p.partType},
         #{p.content}, #{p.toolName}, #{p.toolCallId}, #{p.toolStatus}, #{p.toolInput}, #{p.toolOutput}, #{p.toolError}, #{p.toolTitle},
         #{p.fileName}, #{p.fileUrl}, #{p.fileMime},
         #{p.tokensIn}, #{p.tokensOut}, #{p.cost}, #{p.finishReason},
         #{p.subagentSessionId}, #{p.subagentName},
         NOW(), NOW())
    </foreach>
    ON DUPLICATE KEY UPDATE
        content     = COALESCE(VALUES(content), content),
        tool_name   = COALESCE(VALUES(tool_name), tool_name),
        tool_call_id = COALESCE(VALUES(tool_call_id), tool_call_id),
        tool_status = COALESCE(VALUES(tool_status), tool_status),
        tool_input  = COALESCE(VALUES(tool_input), tool_input),
        tool_output = COALESCE(VALUES(tool_output), tool_output),
        tool_error  = COALESCE(VALUES(tool_error), tool_error),
        tool_title  = COALESCE(VALUES(tool_title), tool_title),
        tokens_in   = COALESCE(VALUES(tokens_in), tokens_in),
        tokens_out  = COALESCE(VALUES(tokens_out), tokens_out),
        cost        = COALESCE(VALUES(cost), cost),
        finish_reason = COALESCE(VALUES(finish_reason), finish_reason),
        subagent_session_id = COALESCE(VALUES(subagent_session_id), subagent_session_id),
        subagent_name = COALESCE(VALUES(subagent_name), subagent_name),
        updated_at  = NOW()
</insert>
```

- [ ] **Step 3: 编译验证**

Run: `cd skill-server && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add skill-server/src/main/java/com/opencode/cui/skill/repository/SkillMessagePartRepository.java
git add skill-server/src/main/resources/mapper/SkillMessagePartMapper.xml
git commit -m "feat(persistence): add batchUpsert to SkillMessagePartRepository"
```

---

### Task 2: PartBufferService — Redis 缓冲服务

**Files:**
- Create: `skill-server/src/main/java/com/opencode/cui/skill/service/PartBufferService.java`
- Create: `skill-server/src/test/java/com/opencode/cui/skill/service/PartBufferServiceTest.java`

- [ ] **Step 1: 编写 PartBufferServiceTest 测试**

```java
package com.opencode.cui.skill.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.model.SkillMessagePart;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PartBufferServiceTest {

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ListOperations<String, String> listOps;
    @Mock
    private ValueOperations<String, String> valueOps;

    private ObjectMapper objectMapper;
    private PartBufferService service;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        lenient().when(redis.opsForList()).thenReturn(listOps);
        lenient().when(redis.opsForValue()).thenReturn(valueOps);
        service = new PartBufferService(redis, objectMapper);
    }

    @Test
    @DisplayName("bufferPart should RPUSH serialized part to Redis list and set TTL")
    void bufferPartRpushAndTtl() throws Exception {
        SkillMessagePart part = SkillMessagePart.builder()
                .id(1L).messageId(100L).sessionId(10L)
                .partId("part-1").seq(1).partType("text")
                .content("hello")
                .build();

        service.bufferPart(100L, part);

        verify(listOps).rightPush(eq("ss:part-buf:100"), anyString());
        verify(redis).expire(eq("ss:part-buf:100"), eq(1L), eq(TimeUnit.HOURS));
    }

    @Test
    @DisplayName("nextSeq should INCR Redis counter and set TTL on first call")
    void nextSeqIncrement() {
        when(valueOps.increment(eq("ss:part-seq:100"))).thenReturn(1L);

        int seq = service.nextSeq(100L);

        assertThat(seq).isEqualTo(1);
        verify(valueOps).increment("ss:part-seq:100");
        verify(redis).expire(eq("ss:part-seq:100"), eq(1L), eq(TimeUnit.HOURS));
    }

    @Test
    @DisplayName("flushParts should return all buffered parts and delete Redis keys")
    void flushPartsReturnAndCleanup() throws Exception {
        SkillMessagePart part1 = SkillMessagePart.builder()
                .id(1L).messageId(100L).sessionId(10L)
                .partId("p1").seq(1).partType("text").content("a")
                .build();
        SkillMessagePart part2 = SkillMessagePart.builder()
                .id(2L).messageId(100L).sessionId(10L)
                .partId("p2").seq(2).partType("tool").toolName("bash")
                .build();

        String json1 = objectMapper.writeValueAsString(part1);
        String json2 = objectMapper.writeValueAsString(part2);
        when(listOps.range("ss:part-buf:100", 0, -1)).thenReturn(List.of(json1, json2));

        List<SkillMessagePart> result = service.flushParts(100L);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getPartId()).isEqualTo("p1");
        assertThat(result.get(1).getPartId()).isEqualTo("p2");
        verify(redis).delete("ss:part-buf:100");
        verify(redis).delete("ss:part-seq:100");
    }

    @Test
    @DisplayName("flushParts returns empty list when no buffered parts")
    void flushPartsEmptyBuffer() {
        when(listOps.range("ss:part-buf:100", 0, -1)).thenReturn(null);

        List<SkillMessagePart> result = service.flushParts(100L);

        assertThat(result).isEmpty();
        verify(redis).delete("ss:part-buf:100");
        verify(redis).delete("ss:part-seq:100");
    }

    @Test
    @DisplayName("findLatestPendingPermission finds permission part with no response from buffer")
    void findLatestPendingPermission() throws Exception {
        SkillMessagePart textPart = SkillMessagePart.builder()
                .id(1L).messageId(100L).sessionId(10L)
                .partId("p1").seq(1).partType("text").content("hi")
                .build();
        SkillMessagePart permPart = SkillMessagePart.builder()
                .id(2L).messageId(100L).sessionId(10L)
                .partId("perm-1").seq(2).partType("permission")
                .toolCallId("perm-1").toolName("Bash")
                .build();

        String json1 = objectMapper.writeValueAsString(textPart);
        String json2 = objectMapper.writeValueAsString(permPart);
        when(listOps.range("ss:part-buf:100", 0, -1)).thenReturn(List.of(json1, json2));

        SkillMessagePart result = service.findLatestPendingPermission(100L);

        assertThat(result).isNotNull();
        assertThat(result.getPartId()).isEqualTo("perm-1");
    }

    @Test
    @DisplayName("findLatestPendingPermission returns null if permission already has response")
    void findLatestPendingPermissionCompleted() throws Exception {
        SkillMessagePart permPart = SkillMessagePart.builder()
                .id(2L).messageId(100L).sessionId(10L)
                .partId("perm-1").seq(2).partType("permission")
                .toolCallId("perm-1").toolOutput("once")
                .build();

        String json = objectMapper.writeValueAsString(permPart);
        when(listOps.range("ss:part-buf:100", 0, -1)).thenReturn(List.of(json));

        SkillMessagePart result = service.findLatestPendingPermission(100L);

        assertThat(result).isNull();
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd skill-server && mvn test -pl . -Dtest=PartBufferServiceTest -q`
Expected: FAIL — `PartBufferService` 类不存在

- [ ] **Step 3: 实现 PartBufferService**

```java
package com.opencode.cui.skill.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.model.SkillMessagePart;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Redis 缓冲服务：暂存流式 part，tool_done 时批量刷入 MySQL。
 *
 * <p>Key schema（均带 1h TTL）：</p>
 * <ul>
 *   <li>{@code ss:part-buf:{messageDbId}} — LIST，缓冲序列化的 part JSON</li>
 *   <li>{@code ss:part-seq:{messageDbId}} — STRING (counter)，原子递增的 seq</li>
 * </ul>
 */
@Slf4j
@Service
public class PartBufferService {

    private static final String BUF_PREFIX = "ss:part-buf:";
    private static final String SEQ_PREFIX = "ss:part-seq:";
    private static final long TTL_HOURS = 1;

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public PartBufferService(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    /**
     * 将 part 序列化后 RPUSH 到 Redis LIST。
     */
    public void bufferPart(Long messageDbId, SkillMessagePart part) {
        String key = BUF_PREFIX + messageDbId;
        try {
            String json = objectMapper.writeValueAsString(part);
            redis.opsForList().rightPush(key, json);
            redis.expire(key, TTL_HOURS, TimeUnit.HOURS);
            log.debug("Buffered part to Redis: messageDbId={}, partId={}", messageDbId, part.getPartId());
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize part for Redis buffer: messageDbId={}, partId={}, error={}",
                    messageDbId, part.getPartId(), e.getMessage());
        }
    }

    /**
     * 原子递增 seq 计数器，返回新的 seq 值。
     */
    public int nextSeq(Long messageDbId) {
        String key = SEQ_PREFIX + messageDbId;
        Long seq = redis.opsForValue().increment(key);
        redis.expire(key, TTL_HOURS, TimeUnit.HOURS);
        return seq != null ? seq.intValue() : 1;
    }

    /**
     * 读取所有缓冲 part 并清理 Redis key。
     *
     * @return 反序列化后的 part 列表，如果缓冲为空返回空列表
     */
    public List<SkillMessagePart> flushParts(Long messageDbId) {
        String bufKey = BUF_PREFIX + messageDbId;
        String seqKey = SEQ_PREFIX + messageDbId;

        List<String> jsonList = redis.opsForList().range(bufKey, 0, -1);
        redis.delete(bufKey);
        redis.delete(seqKey);

        if (jsonList == null || jsonList.isEmpty()) {
            return Collections.emptyList();
        }

        List<SkillMessagePart> parts = new ArrayList<>(jsonList.size());
        for (String json : jsonList) {
            try {
                parts.add(objectMapper.readValue(json, SkillMessagePart.class));
            } catch (JsonProcessingException e) {
                log.error("Failed to deserialize buffered part: {}", e.getMessage());
            }
        }
        log.debug("Flushed {} parts from Redis buffer: messageDbId={}", parts.size(), messageDbId);
        return parts;
    }

    /**
     * 从 Redis 缓冲中反向查找最新的 pending permission part。
     * pending 定义：partType=permission 且 toolOutput 为 null 或空。
     *
     * @return 找到的 pending permission part，未找到返回 null
     */
    public SkillMessagePart findLatestPendingPermission(Long messageDbId) {
        String bufKey = BUF_PREFIX + messageDbId;
        List<String> jsonList = redis.opsForList().range(bufKey, 0, -1);
        if (jsonList == null || jsonList.isEmpty()) {
            return null;
        }

        // 反向遍历，找最新的 pending permission
        for (int i = jsonList.size() - 1; i >= 0; i--) {
            try {
                SkillMessagePart part = objectMapper.readValue(jsonList.get(i), SkillMessagePart.class);
                if ("permission".equals(part.getPartType())
                        && (part.getToolOutput() == null || part.getToolOutput().isEmpty())) {
                    return part;
                }
            } catch (JsonProcessingException e) {
                log.warn("Failed to deserialize buffered part during permission scan: {}", e.getMessage());
            }
        }
        return null;
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd skill-server && mvn test -pl . -Dtest=PartBufferServiceTest -q`
Expected: 全部 PASS

- [ ] **Step 5: Commit**

```bash
git add skill-server/src/main/java/com/opencode/cui/skill/service/PartBufferService.java
git add skill-server/src/test/java/com/opencode/cui/skill/service/PartBufferServiceTest.java
git commit -m "feat(persistence): add PartBufferService for Redis-based part buffering"
```

---

### Task 3: MessagePersistenceService — persist 方法改为写 Redis 缓冲

**Files:**
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/service/MessagePersistenceService.java`

- [ ] **Step 1: 注入 PartBufferService 依赖**

替换构造函数，新增 `partBufferService` 字段：

```java
private final SkillMessageService messageService;
private final SkillMessagePartRepository partRepository;
private final ObjectMapper objectMapper;
private final SnowflakeIdGenerator snowflakeIdGenerator;
private final ActiveMessageTracker tracker;
private final SkillSessionService sessionService;
private final PartBufferService partBufferService;

public MessagePersistenceService(SkillMessageService messageService,
        SkillMessagePartRepository partRepository,
        ObjectMapper objectMapper,
        SnowflakeIdGenerator snowflakeIdGenerator,
        ActiveMessageTracker tracker,
        SkillSessionService sessionService,
        PartBufferService partBufferService) {
    this.messageService = messageService;
    this.partRepository = partRepository;
    this.objectMapper = objectMapper;
    this.snowflakeIdGenerator = snowflakeIdGenerator;
    this.tracker = tracker;
    this.sessionService = sessionService;
    this.partBufferService = partBufferService;
}
```

- [ ] **Step 2: 替换 resolvePartSeq 为 Redis INCR**

将 `resolvePartSeq` 方法替换为：

```java
private int resolvePartSeq(Long messageDbId, StreamMessage msg) {
    if (msg.getPartSeq() != null && msg.getPartSeq() > 0) {
        return msg.getPartSeq();
    }
    return partBufferService.nextSeq(messageDbId);
}
```

- [ ] **Step 3: 修改 persistTextPart — 改为写 Redis**

将 `partRepository.upsert(part)` 替换为 `partBufferService.bufferPart(active.dbId(), part)`，并更新 `resolvePartSeq` 调用签名：

```java
private boolean persistTextPart(Long sessionId, StreamMessage msg, String partType,
        ActiveMessageTracker.ActiveMessageRef active) {
    if (active == null) {
        return false;
    }

    SkillMessagePart part = SkillMessagePart.builder()
            .id(snowflakeIdGenerator.nextId())
            .messageId(active.dbId())
            .sessionId(sessionId)
            .partId(msg.getPartId() != null ? msg.getPartId() : partType + "-" + active.messageSeq())
            .seq(resolvePartSeq(active.dbId(), msg))
            .partType(partType)
            .content(msg.getContent())
            .subagentSessionId(msg.getSubagentSessionId())
            .subagentName(msg.getSubagentName())
            .build();

    partBufferService.bufferPart(active.dbId(), part);
    log.debug("Buffered {} part: sessionId={}, protocolId={}, partId={}",
            partType, sessionId, active.protocolMessageId(), part.getPartId());

    return true;
}
```

- [ ] **Step 4: 修改 persistToolPart — 改为写 Redis**

同样替换 `partRepository.upsert(part)` → `partBufferService.bufferPart(active.dbId(), part)`，更新 `resolvePartSeq` 调用：

```java
private boolean persistToolPart(Long sessionId, StreamMessage msg,
        ActiveMessageTracker.ActiveMessageRef active) {
    if (active == null) {
        return false;
    }

    String inputJson = null;
    var tool = msg.getTool();
    if (tool != null && tool.getInput() != null) {
        try {
            inputJson = objectMapper.writeValueAsString(tool.getInput());
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize tool input: {}", e.getMessage());
        }
    }

    SkillMessagePart part = SkillMessagePart.builder()
            .id(snowflakeIdGenerator.nextId())
            .messageId(active.dbId())
            .sessionId(sessionId)
            .partId(msg.getPartId() != null ? msg.getPartId() : "tool-" + active.messageSeq())
            .seq(resolvePartSeq(active.dbId(), msg))
            .partType("tool")
            .toolName(tool != null ? tool.getToolName() : null)
            .toolCallId(tool != null ? tool.getToolCallId() : null)
            .toolStatus(msg.getStatus())
            .toolInput(inputJson)
            .toolOutput(tool != null ? tool.getOutput() : null)
            .toolError(msg.getError())
            .toolTitle(msg.getTitle())
            .subagentSessionId(msg.getSubagentSessionId())
            .subagentName(msg.getSubagentName())
            .build();

    partBufferService.bufferPart(active.dbId(), part);
    log.debug("Buffered tool part: sessionId={}, protocolId={}, tool={}, status={}",
            sessionId, active.protocolMessageId(),
            tool != null ? tool.getToolName() : null, msg.getStatus());
    return true;
}
```

- [ ] **Step 5: 修改 persistPermissionPart — 改为写 Redis**

替换 `partRepository.upsert(part)` → `partBufferService.bufferPart(active.dbId(), part)`，更新 `resolvePartSeq` 调用：

```java
private boolean persistPermissionPart(Long sessionId, StreamMessage msg,
        ActiveMessageTracker.ActiveMessageRef active) {
    if (active == null && StreamMessage.Types.PERMISSION_REPLY.equals(msg.getType())) {
        return updatePermissionReplyByPermissionId(sessionId, msg);
    }
    if (active == null) {
        return false;
    }

    String metadataJson = null;
    var permission = msg.getPermission();
    if (permission != null && permission.getMetadata() != null) {
        try {
            metadataJson = objectMapper.writeValueAsString(permission.getMetadata());
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize permission metadata: {}", e.getMessage());
        }
    }

    String permissionId = permission != null ? permission.getPermissionId() : null;
    SkillMessagePart part = SkillMessagePart.builder()
            .id(snowflakeIdGenerator.nextId())
            .messageId(active.dbId())
            .sessionId(sessionId)
            .partId(msg.getPartId() != null ? msg.getPartId()
                    : permissionId != null ? permissionId : "permission-" + active.messageSeq())
            .seq(resolvePartSeq(active.dbId(), msg))
            .partType("permission")
            .content(msg.getTitle() != null ? msg.getTitle() : msg.getContent())
            .toolName(permission != null ? permission.getPermType() : null)
            .toolCallId(permissionId)
            .toolStatus(msg.getStatus())
            .toolInput(metadataJson)
            .toolOutput(permission != null ? permission.getResponse() : null)
            .subagentSessionId(msg.getSubagentSessionId())
            .subagentName(msg.getSubagentName())
            .build();

    partBufferService.bufferPart(active.dbId(), part);
    log.debug("Buffered permission part: sessionId={}, protocolId={}, permissionId={}, type={}",
            sessionId, active.protocolMessageId(), permissionId, msg.getType());
    return true;
}
```

- [ ] **Step 6: 修改 persistFilePart — 改为写 Redis**

```java
private boolean persistFilePart(Long sessionId, StreamMessage msg,
        ActiveMessageTracker.ActiveMessageRef active) {
    if (active == null) {
        return false;
    }

    var f = msg.getFile();
    SkillMessagePart part = SkillMessagePart.builder()
            .id(snowflakeIdGenerator.nextId())
            .messageId(active.dbId())
            .sessionId(sessionId)
            .partId(msg.getPartId() != null ? msg.getPartId() : "file-" + active.messageSeq())
            .seq(resolvePartSeq(active.dbId(), msg))
            .partType("file")
            .fileName(f != null ? f.getFileName() : null)
            .fileUrl(f != null ? f.getFileUrl() : null)
            .fileMime(f != null ? f.getFileMime() : null)
            .subagentSessionId(msg.getSubagentSessionId())
            .subagentName(msg.getSubagentName())
            .build();

    partBufferService.bufferPart(active.dbId(), part);
    log.debug("Buffered file part: sessionId={}, protocolId={}, file={}",
            sessionId, active.protocolMessageId(),
            f != null ? f.getFileName() : null);
    return true;
}
```

- [ ] **Step 7: 修改 persistStepDone — 改为写 Redis（延迟 stats 更新）**

移除 `messageService.updateMessageStats()` 调用，stats 将在 flush 时统一处理：

```java
private boolean persistStepDone(Long sessionId, StreamMessage msg,
        ActiveMessageTracker.ActiveMessageRef active) {
    if (active == null) {
        return false;
    }

    var u = msg.getUsage();
    UsageStats stats = extractUsageStats(msg);
    int partSeq = resolvePartSeq(active.dbId(), msg);
    Double cost = u != null ? u.getCost() : null;
    SkillMessagePart part = SkillMessagePart.builder()
            .id(snowflakeIdGenerator.nextId())
            .messageId(active.dbId())
            .sessionId(sessionId)
            .partId(msg.getPartId() != null ? msg.getPartId() : "step-done-" + active.dbId() + "-" + partSeq)
            .seq(partSeq)
            .partType("step-finish")
            .tokensIn(stats.tokensIn())
            .tokensOut(stats.tokensOut())
            .cost(cost)
            .finishReason(u != null ? u.getReason() : null)
            .subagentSessionId(msg.getSubagentSessionId())
            .subagentName(msg.getSubagentName())
            .build();

    partBufferService.bufferPart(active.dbId(), part);
    log.debug("Buffered step.done: sessionId={}, protocolId={}, tokensIn={}, tokensOut={}, cost={}",
            sessionId, active.protocolMessageId(), stats.tokensIn(), stats.tokensOut(), cost);
    return true;
}
```

- [ ] **Step 8: 编译验证**

Run: `cd skill-server && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 9: Commit**

```bash
git add skill-server/src/main/java/com/opencode/cui/skill/service/MessagePersistenceService.java
git commit -m "refactor(persistence): switch persist methods from direct upsert to Redis buffer"
```

---

### Task 4: MessagePersistenceService — flush 逻辑 + permission 查询兼容

**Files:**
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/service/MessagePersistenceService.java`

- [ ] **Step 1: 新增 flushPartBuffer 方法**

在 `handleSessionStatus` 方法之后、`syncAllPendingContent` 方法之前添加：

```java
/**
 * 从 Redis 缓冲读取所有 part，批量写入 MySQL，累积 step-done 的 stats 后一次性更新。
 */
private void flushPartBuffer(Long messageDbId) {
    List<SkillMessagePart> parts = partBufferService.flushParts(messageDbId);
    if (parts.isEmpty()) {
        return;
    }

    partRepository.batchUpsert(parts);
    log.info("Batch upserted {} parts for messageDbId={}", parts.size(), messageDbId);

    // 累积 step-done 的 tokens/cost，一次性更新主消息 stats
    int totalTokensIn = 0;
    int totalTokensOut = 0;
    double totalCost = 0.0;
    boolean hasStats = false;

    for (SkillMessagePart part : parts) {
        if ("step-finish".equals(part.getPartType())) {
            if (part.getTokensIn() != null) totalTokensIn += part.getTokensIn();
            if (part.getTokensOut() != null) totalTokensOut += part.getTokensOut();
            if (part.getCost() != null) totalCost += part.getCost();
            hasStats = true;
        }
    }

    if (hasStats) {
        messageService.updateMessageStats(messageDbId,
                totalTokensIn > 0 ? totalTokensIn : null,
                totalTokensOut > 0 ? totalTokensOut : null,
                totalCost > 0 ? totalCost : null);
    }
}
```

- [ ] **Step 2: 修改 handleSessionStatus — 在 syncContent 前调用 flush**

```java
private void handleSessionStatus(Long sessionId, StreamMessage msg) {
    if (!"idle".equals(msg.getSessionStatus()) && !"completed".equals(msg.getSessionStatus())) {
        return;
    }
    // 先 flush Redis 缓冲到 MySQL，再 sync content（sync 需要从 DB 读 part）
    ActiveMessageTracker.ActiveMessageRef active = tracker.getActiveMessage(sessionId);
    if (active != null) {
        flushPartBuffer(active.dbId());
    }
    syncAllPendingContent(sessionId);
    sessionService.touchSession(sessionId);
    tracker.removeAndFinalize(sessionId);
}
```

- [ ] **Step 3: 修改 synthesizePermissionReplyFromToolOutcome — 先查 Redis 再查 DB**

```java
@Transactional(readOnly = true)
public StreamMessage synthesizePermissionReplyFromToolOutcome(Long sessionId, StreamMessage msg) {
    String inferredResponse = inferPermissionResponseFromToolOutcome(msg);
    if (inferredResponse == null) {
        return null;
    }

    // 先查 Redis 缓冲中的 pending permission part
    ActiveMessageTracker.ActiveMessageRef active = tracker.getActiveMessage(sessionId);
    SkillMessagePart pendingPart = null;
    if (active != null) {
        pendingPart = partBufferService.findLatestPendingPermission(active.dbId());
    }
    // 降级查 DB（兼容 takeover 后已刷盘的场景）
    if (pendingPart == null) {
        pendingPart = partRepository.findLatestPendingPermissionPart(sessionId);
    }
    if (pendingPart == null) {
        return null;
    }

    SkillMessage ownerMessage = messageService.findById(pendingPart.getMessageId());
    String protocolMessageId = ownerMessage != null ? ownerMessage.getMessageId() : null;

    return StreamMessage.builder()
            .type(StreamMessage.Types.PERMISSION_REPLY)
            .messageId(protocolMessageId)
            .sourceMessageId(protocolMessageId)
            .partId(pendingPart.getPartId())
            .partSeq(pendingPart.getSeq())
            .role("assistant")
            .status("completed")
            .title(pendingPart.getContent())
            .permission(StreamMessage.PermissionInfo.builder()
                    .permissionId(pendingPart.getToolCallId())
                    .permType(pendingPart.getToolName())
                    .response(inferredResponse)
                    .build())
            .build();
}
```

- [ ] **Step 4: 修改 updatePermissionReplyByPermissionId — 先查 Redis 再查 DB**

`updatePermissionReplyByPermissionId` 也需要查找已有的 permission part。由于此时 part 可能还在 Redis 缓冲中，需要兼容：

```java
private boolean updatePermissionReplyByPermissionId(Long sessionId, StreamMessage msg) {
    var permission = msg.getPermission();
    if (permission == null || permission.getPermissionId() == null) {
        return false;
    }
    String permissionId = permission.getPermissionId();
    String response = permission.getResponse();
    String status = msg.getStatus() != null ? msg.getStatus() : "completed";

    // 先查 DB（已刷盘的场景）
    SkillMessagePart existing = partRepository.findByPartId(sessionId, permissionId);
    if (existing != null) {
        existing.setToolStatus(status);
        existing.setToolOutput(response);
        existing.setUpdatedAt(null);
        partRepository.upsert(existing);
        log.info("Updated permission reply by permissionId (DB): sessionId={}, permissionId={}, response={}",
                sessionId, permissionId, response);
        return true;
    }

    // DB 中没有 → 可能在 Redis 缓冲中，此时 permission reply 会在 flush 时随缓冲一起写入
    // 不做额外处理，permission.ask 和 permission.reply 会在同一批次 batch upsert
    log.debug("Permission part not found in DB, may be in Redis buffer: sessionId={}, permissionId={}",
            sessionId, permissionId);
    return false;
}
```

- [ ] **Step 5: 编译验证**

Run: `cd skill-server && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add skill-server/src/main/java/com/opencode/cui/skill/service/MessagePersistenceService.java
git commit -m "feat(persistence): add flush logic and Redis-first permission query"
```

---

### Task 5: 更新现有测试

**Files:**
- Modify: `skill-server/src/test/java/com/opencode/cui/skill/service/MessagePersistenceServiceTest.java`

- [ ] **Step 1: 更新 MessagePersistenceServiceTest 以适配新的依赖**

测试需要 mock `PartBufferService`，并验证 persist 方法调用 `bufferPart` 而非 `upsert`：

```java
package com.opencode.cui.skill.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.model.SkillMessage;
import com.opencode.cui.skill.model.SkillMessagePart;
import com.opencode.cui.skill.model.StreamMessage;
import com.opencode.cui.skill.repository.SkillMessagePartRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MessagePersistenceServiceTest {

    @Mock
    private SkillMessageService messageService;
    @Mock
    private SkillMessagePartRepository partRepository;
    @Mock
    private SnowflakeIdGenerator snowflakeIdGenerator;
    @Mock
    private SkillSessionService skillSessionService;
    @Mock
    private PartBufferService partBufferService;

    private ActiveMessageTracker activeMessageTracker;
    private MessagePersistenceService service;

    @BeforeEach
    void setUp() {
        lenient().when(snowflakeIdGenerator.nextId()).thenReturn(501L, 502L, 503L, 504L);
        lenient().when(partBufferService.nextSeq(anyLong())).thenReturn(1, 2, 3, 4);
        activeMessageTracker = new ActiveMessageTracker(messageService);
        service = new MessagePersistenceService(messageService, partRepository, new ObjectMapper(),
                snowflakeIdGenerator, activeMessageTracker, skillSessionService, partBufferService);
    }

    private void setupActiveMessage() {
        when(messageService.saveMessage(any(com.opencode.cui.skill.model.SaveMessageCommand.class)))
                .thenReturn(SkillMessage.builder()
                        .id(11L).messageId("msg_1_1").sessionId(1L).seq(1)
                        .build());
    }

    @Test
    @DisplayName("text.done buffers part to Redis instead of direct DB upsert")
    void textDoneBuffersToRedis() {
        setupActiveMessage();

        service.persistIfFinal(1L, StreamMessage.builder()
                .type(StreamMessage.Types.TEXT_DONE)
                .partId("part-1")
                .content("final answer")
                .build());

        // 验证写入 Redis 缓冲，而非直接 DB upsert
        ArgumentCaptor<SkillMessagePart> captor = ArgumentCaptor.forClass(SkillMessagePart.class);
        verify(partBufferService).bufferPart(eq(11L), captor.capture());
        assertThat(captor.getValue().getContent()).isEqualTo("final answer");
        assertThat(captor.getValue().getPartType()).isEqualTo("text");

        // 不应调用 DB upsert
        verify(partRepository, never()).upsert(any());
    }

    @Test
    @DisplayName("session.status=idle triggers flush then sync")
    void sessionIdleFlushesAndSyncs() {
        setupActiveMessage();

        // 先持久化一个 text part（写入 Redis 缓冲）
        service.persistIfFinal(1L, StreamMessage.builder()
                .type(StreamMessage.Types.TEXT_DONE)
                .partId("part-1").content("hello")
                .build());

        // 模拟 flush 返回 part 列表
        SkillMessagePart bufferedPart = SkillMessagePart.builder()
                .id(501L).messageId(11L).sessionId(1L)
                .partId("part-1").seq(1).partType("text").content("hello")
                .build();
        when(partBufferService.flushParts(11L)).thenReturn(List.of(bufferedPart));
        when(partRepository.findConcatenatedTextByMessageId(11L)).thenReturn("hello");

        // 触发 session.status=idle
        service.persistIfFinal(1L, StreamMessage.sessionStatus("idle"));

        // 验证 flush 流程
        verify(partBufferService).flushParts(11L);
        verify(partRepository).batchUpsert(List.of(bufferedPart));
        verify(partRepository).findConcatenatedTextByMessageId(11L);
        verify(messageService).updateMessageContent(11L, "hello");
        verify(messageService).markMessageFinished(11L);
    }

    @Test
    @DisplayName("step.done stats are accumulated and applied during flush")
    void stepDoneStatsAccumulatedDuringFlush() {
        setupActiveMessage();

        service.persistIfFinal(1L, StreamMessage.builder()
                .type(StreamMessage.Types.STEP_DONE)
                .partId("step-1")
                .usage(StreamMessage.UsageInfo.builder()
                        .tokens(java.util.Map.of("input", 100, "output", 200))
                        .cost(0.01).reason("end_turn")
                        .build())
                .build());

        // stats 不应在 persist 时立即更新
        verify(messageService, never()).updateMessageStats(anyLong(), any(), any(), any());

        // 模拟 flush
        SkillMessagePart stepPart = SkillMessagePart.builder()
                .id(501L).messageId(11L).sessionId(1L)
                .partId("step-1").seq(1).partType("step-finish")
                .tokensIn(100).tokensOut(200).cost(0.01)
                .build();
        when(partBufferService.flushParts(11L)).thenReturn(List.of(stepPart));

        service.persistIfFinal(1L, StreamMessage.sessionStatus("idle"));

        // 验证 stats 在 flush 时更新
        verify(messageService).updateMessageStats(eq(11L), eq(100), eq(200), eq(0.01));
    }

    @Test
    @DisplayName("finalizeActiveAssistantTurn closes dangling assistant message")
    void finalizeActiveAssistantTurnClosesDanglingAssistantMessage() {
        setupActiveMessage();

        service.persistIfFinal(1L, StreamMessage.builder()
                .type(StreamMessage.Types.TEXT_DONE)
                .partId("part-1").content("hello")
                .build());

        service.finalizeActiveAssistantTurn(1L);

        verify(messageService).markMessageFinished(11L);
        verify(messageService, times(2)).scheduleLatestHistoryRefreshAfterCommit(1L);
    }

    @Test
    @DisplayName("finalizeActiveAssistantTurn is a no-op when no assistant turn is open")
    void finalizeActiveAssistantTurnNoopWhenNoAssistantTurnOpen() {
        service.finalizeActiveAssistantTurn(1L);
        verify(messageService, never()).markMessageFinished(anyLong());
    }
}
```

- [ ] **Step 2: 运行测试**

Run: `cd skill-server && mvn test -pl . -Dtest=MessagePersistenceServiceTest -q`
Expected: 全部 PASS

- [ ] **Step 3: 运行全量测试确认无回归**

Run: `cd skill-server && mvn test -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add skill-server/src/test/java/com/opencode/cui/skill/service/MessagePersistenceServiceTest.java
git commit -m "test(persistence): update MessagePersistenceServiceTest for Redis buffer flow"
```

---

### Task 6: 全量验证

- [ ] **Step 1: 编译全项目**

Run: `cd skill-server && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 2: 运行全量测试**

Run: `cd skill-server && mvn test -q`
Expected: BUILD SUCCESS，无失败测试

- [ ] **Step 3: Review 改动范围**

Run: `git diff --stat HEAD~4`
Expected: 只有以下文件被改动：
- `SkillMessagePartRepository.java` — 新增 batchUpsert
- `SkillMessagePartMapper.xml` — 新增 batchUpsert SQL
- `PartBufferService.java` — 新增
- `PartBufferServiceTest.java` — 新增
- `MessagePersistenceService.java` — persist 改 Redis，flush 逻辑
- `MessagePersistenceServiceTest.java` — 测试适配
