package com.opencode.cui.skill.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.model.StreamMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class StreamBufferServiceTest {

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ListOperations<String, String> listOps;
    @Mock
    private ValueOperations<String, String> valueOps;

    private ObjectMapper objectMapper;
    private StreamBufferService service;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        lenient().when(redis.opsForValue()).thenReturn(valueOps);
        lenient().when(redis.opsForList()).thenReturn(listOps);
        service = new StreamBufferService(redis, objectMapper);
    }

    @Test
    @DisplayName("session.status=busy marks session streaming for resume snapshot")
    void sessionStatusBusyMarksSessionStreaming() {
        service.accumulate("42", StreamMessage.sessionStatus("busy"));

        verify(valueOps).set("stream:42:status", "{\"status\":\"busy\"}", 1L, TimeUnit.HOURS);
    }

    @Test
    @DisplayName("session.status=retry keeps session streaming for resume snapshot")
    void sessionStatusRetryKeepsSessionStreaming() {
        service.accumulate("42", StreamMessage.sessionStatus("retry"));

        verify(valueOps).set("stream:42:status", "{\"status\":\"busy\"}", 1L, TimeUnit.HOURS);
    }

    @Test
    @DisplayName("error clears completed live parts from resume snapshot")
    void errorClearsCompletedLiveParts() {
        when(listOps.range("stream:42:parts_order", 0, -1)).thenReturn(List.of("part-1"));

        service.accumulate("42", StreamMessage.error("timeout"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
        verify(redis).delete(keysCaptor.capture());
        assertThat(keysCaptor.getValue()).containsExactly(
                "stream:42:parts_order",
                "stream:42:status",
                "stream:42:part:part-1",
                "stream:42:part:part-1:registered");
    }

    @Test
    @DisplayName("session.error clears live replay state")
    void sessionErrorClearsLiveReplayState() {
        when(listOps.range("stream:42:parts_order", 0, -1)).thenReturn(List.of());

        service.accumulate("42", StreamMessage.builder()
                .type(StreamMessage.Types.SESSION_ERROR)
                .error("cloud timeout")
                .build());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
        verify(redis).delete(keysCaptor.capture());
        assertThat(keysCaptor.getValue()).containsExactly(
                "stream:42:parts_order",
                "stream:42:status");
    }

    @Test
    @DisplayName("text.done keeps completed content in Redis until session idle")
    void textDoneKeepsCompletedPartUntilIdle() throws Exception {
        String partKey = "stream:42:part:part-1";
        StreamMessage buffered = StreamMessage.builder()
                .type(StreamMessage.Types.TEXT_DELTA)
                .sessionId("42")
                .messageId("msg-1")
                .role("assistant")
                .partId("part-1")
                .content("partial answer")
                .build();
        when(redis.hasKey(partKey)).thenReturn(true);
        when(valueOps.get(partKey)).thenReturn(objectMapper.writeValueAsString(buffered));

        service.accumulate("42", StreamMessage.builder()
                .type(StreamMessage.Types.TEXT_DONE)
                .partId("part-1")
                .build());

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(eq(partKey), jsonCaptor.capture(), eq(1L), eq(TimeUnit.HOURS));
        StreamMessage persisted = objectMapper.readValue(jsonCaptor.getValue(), StreamMessage.class);
        assertThat(persisted.getType()).isEqualTo(StreamMessage.Types.TEXT_DONE);
        assertThat(persisted.getContent()).isEqualTo("partial answer");
        assertThat(persisted.getMessageId()).isEqualTo("msg-1");

        verify(listOps, never()).remove(any(), anyLong(), any());
        verify(redis, never()).delete(partKey);
    }

    @Test
    @DisplayName("full semantic parts are written and registered for resume")
    void fullPartWritesAndRegistersForResume() {
        String partKey = "stream:42:part:tool-1";
        String registeredKey = partKey + ":registered";
        when(redis.hasKey(partKey)).thenReturn(false);
        when(valueOps.setIfAbsent(eq(registeredKey), eq("1"), eq(1L), eq(TimeUnit.HOURS)))
                .thenReturn(true);

        service.accumulate("42", StreamMessage.builder()
                .type(StreamMessage.Types.TOOL_UPDATE)
                .partId("tool-1")
                .status("completed")
                .build());

        verify(valueOps).set(eq(partKey), any(String.class), eq(1L), eq(TimeUnit.HOURS));
        verify(listOps).rightPush("stream:42:parts_order", "tool-1");
        verify(redis).expire("stream:42:parts_order", 1L, TimeUnit.HOURS);
    }

    @Test
    @DisplayName("delta append upgrades generated message identity when upstream id arrives")
    void appendContentUpgradesGeneratedMessageIdentity() throws Exception {
        String partKey = "stream:42:part:part-1";
        StreamMessage buffered = StreamMessage.builder()
                .type(StreamMessage.Types.TEXT_DELTA)
                .sessionId("42")
                .messageId("msg_42_1")
                .sourceMessageId("msg_42_1")
                .messageSeq(1)
                .role("assistant")
                .partId("part-1")
                .content("hel")
                .build();
        when(valueOps.get(partKey)).thenReturn(objectMapper.writeValueAsString(buffered));
        when(valueOps.setIfPresent(eq(partKey), any(String.class), eq(1L), eq(TimeUnit.HOURS)))
                .thenReturn(true);

        service.accumulate("42", StreamMessage.builder()
                .type(StreamMessage.Types.TEXT_DELTA)
                .messageId("opencode-msg-1")
                .sourceMessageId("opencode-msg-1")
                .messageSeq(1)
                .role("assistant")
                .partId("part-1")
                .content("lo")
                .build());

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOps).setIfPresent(eq(partKey), jsonCaptor.capture(), eq(1L), eq(TimeUnit.HOURS));
        StreamMessage persisted = objectMapper.readValue(jsonCaptor.getValue(), StreamMessage.class);
        assertThat(persisted.getContent()).isEqualTo("hello");
        assertThat(persisted.getMessageId()).isEqualTo("opencode-msg-1");
        assertThat(persisted.getSourceMessageId()).isEqualTo("opencode-msg-1");
        assertThat(persisted.getMessageSeq()).isEqualTo(1);
    }

    @Test
    @DisplayName("clearSession deletes part registration sentinels")
    void clearSessionDeletesRegisteredSentinels() {
        when(listOps.range("stream:42:parts_order", 0, -1)).thenReturn(List.of("part-1", "tool-1"));

        service.clearSession("42");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
        verify(redis).delete(keysCaptor.capture());
        assertThat(keysCaptor.getValue()).containsExactly(
                "stream:42:parts_order",
                "stream:42:status",
                "stream:42:part:part-1",
                "stream:42:part:part-1:registered",
                "stream:42:part:tool-1",
                "stream:42:part:tool-1:registered");
    }
}
