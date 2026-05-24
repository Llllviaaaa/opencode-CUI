package com.opencode.cui.skill.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.model.StreamMessage;
import com.opencode.cui.skill.repository.SkillMessagePartRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SnapshotServiceTest {

    @Mock
    private StreamBufferService bufferService;
    @Mock
    private SkillSessionService sessionService;
    @Mock
    private SkillMessageService messageService;
    @Mock
    private SkillMessagePartRepository partRepository;

    private SnapshotService service;

    @BeforeEach
    void setUp() {
        service = new SnapshotService(new ObjectMapper(), bufferService, sessionService,
                messageService, partRepository);
    }

    @Test
    @DisplayName("streaming snapshot chooses upstream message id over generated tool part id")
    void streamingSnapshotPrefersUpstreamMessageId() {
        when(bufferService.isSessionStreaming("42")).thenReturn(true);
        when(bufferService.getStreamingParts("42")).thenReturn(List.of(
                StreamMessage.builder()
                        .type(StreamMessage.Types.QUESTION)
                        .messageId("msg_42_1")
                        .sourceMessageId("msg_42_1")
                        .messageSeq(1)
                        .role("assistant")
                        .partId("question-1")
                        .status("running")
                        .tool(StreamMessage.ToolInfo.builder()
                                .toolName("question")
                                .toolCallId("call-1")
                                .build())
                        .build(),
                StreamMessage.builder()
                        .type(StreamMessage.Types.TEXT_DELTA)
                        .messageId("opencode-msg-1")
                        .sourceMessageId("opencode-msg-1")
                        .messageSeq(1)
                        .role("assistant")
                        .partId("text-1")
                        .content("answer")
                        .build()));

        StreamMessage snapshot = service.buildStreamingState("42", 7L);

        assertThat(snapshot.getMessageId()).isEqualTo("opencode-msg-1");
        assertThat(snapshot.getMessageSeq()).isEqualTo(1);
        assertThat(snapshot.getRole()).isEqualTo("assistant");
        assertThat(snapshot.getParts()).hasSize(2);
    }
}
