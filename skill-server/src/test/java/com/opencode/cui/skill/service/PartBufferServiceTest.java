package com.opencode.cui.skill.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.model.SkillMessagePart;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
