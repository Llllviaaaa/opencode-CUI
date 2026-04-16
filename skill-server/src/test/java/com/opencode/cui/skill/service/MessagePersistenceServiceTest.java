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
import java.util.Map;

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

        ArgumentCaptor<SkillMessagePart> captor = ArgumentCaptor.forClass(SkillMessagePart.class);
        verify(partBufferService).bufferPart(eq(11L), captor.capture());
        assertThat(captor.getValue().getContent()).isEqualTo("final answer");
        assertThat(captor.getValue().getPartType()).isEqualTo("text");

        verify(partRepository, never()).upsert(any());
    }

    @Test
    @DisplayName("session.status=idle triggers flush then sync")
    void sessionIdleFlushesAndSyncs() {
        setupActiveMessage();

        service.persistIfFinal(1L, StreamMessage.builder()
                .type(StreamMessage.Types.TEXT_DONE)
                .partId("part-1").content("hello")
                .build());

        SkillMessagePart bufferedPart = SkillMessagePart.builder()
                .id(501L).messageId(11L).sessionId(1L)
                .partId("part-1").seq(1).partType("text").content("hello")
                .build();
        when(partBufferService.flushParts(11L)).thenReturn(List.of(bufferedPart));
        when(partRepository.findConcatenatedTextByMessageId(11L)).thenReturn("hello");

        service.persistIfFinal(1L, StreamMessage.sessionStatus("idle"));

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
                        .tokens(Map.of("input", 100, "output", 200))
                        .cost(0.01).reason("end_turn")
                        .build())
                .build());

        verify(messageService, never()).updateMessageStats(anyLong(), any(), any(), any());

        SkillMessagePart stepPart = SkillMessagePart.builder()
                .id(501L).messageId(11L).sessionId(1L)
                .partId("step-1").seq(1).partType("step-finish")
                .tokensIn(100).tokensOut(200).cost(0.01)
                .build();
        when(partBufferService.flushParts(11L)).thenReturn(List.of(stepPart));
        when(partRepository.findConcatenatedTextByMessageId(11L)).thenReturn("");

        service.persistIfFinal(1L, StreamMessage.sessionStatus("idle"));

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
