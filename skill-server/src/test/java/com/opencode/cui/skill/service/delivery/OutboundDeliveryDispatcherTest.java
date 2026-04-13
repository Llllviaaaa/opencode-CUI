package com.opencode.cui.skill.service.delivery;

import com.opencode.cui.skill.model.SkillSession;
import com.opencode.cui.skill.model.StreamMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OutboundDeliveryDispatcherTest {

    @Test
    @DisplayName("dispatches to first matching strategy by order")
    void dispatchesToFirstMatch() {
        OutboundDeliveryStrategy s1 = mock(OutboundDeliveryStrategy.class);
        when(s1.order()).thenReturn(1);
        when(s1.supports(any())).thenReturn(false);

        OutboundDeliveryStrategy s2 = mock(OutboundDeliveryStrategy.class);
        when(s2.order()).thenReturn(2);
        when(s2.supports(any())).thenReturn(true);

        OutboundDeliveryStrategy s3 = mock(OutboundDeliveryStrategy.class);
        when(s3.order()).thenReturn(3);

        OutboundDeliveryDispatcher dispatcher = new OutboundDeliveryDispatcher(List.of(s3, s1, s2));

        SkillSession session = SkillSession.builder().build();
        StreamMessage msg = StreamMessage.builder().type("delta").build();
        dispatcher.deliver(session, "123", "user1", msg);

        verify(s2).deliver(session, "123", "user1", msg);
        verify(s1, never()).deliver(any(), any(), any(), any());
        verify(s3, never()).deliver(any(), any(), any(), any());
    }

    @Test
    @DisplayName("logs warning when no strategy matches")
    void noMatchDoesNotThrow() {
        OutboundDeliveryStrategy s1 = mock(OutboundDeliveryStrategy.class);
        when(s1.order()).thenReturn(1);
        when(s1.supports(any())).thenReturn(false);

        OutboundDeliveryDispatcher dispatcher = new OutboundDeliveryDispatcher(List.of(s1));

        SkillSession session = SkillSession.builder()
                .businessSessionDomain("unknown").build();
        StreamMessage msg = StreamMessage.builder().type("delta").build();
        dispatcher.deliver(session, "123", "user1", msg);

        verify(s1, never()).deliver(any(), any(), any(), any());
    }
}
