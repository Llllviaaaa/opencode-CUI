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
