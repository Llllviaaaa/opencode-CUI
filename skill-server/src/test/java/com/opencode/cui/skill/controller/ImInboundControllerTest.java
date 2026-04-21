package com.opencode.cui.skill.controller;

import com.opencode.cui.skill.model.ImMessageRequest;
import com.opencode.cui.skill.service.InboundProcessingService;
import com.opencode.cui.skill.service.InboundProcessingService.InboundResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
/** ImInboundController 单元测试：验证 IM 入站消息的校验和委派逻辑。 */
class ImInboundControllerTest {

        @Mock
        private InboundProcessingService processingService;

        private ImInboundController controller;

        @BeforeEach
        void setUp() {
                controller = new ImInboundController(processingService);
        }

        @Test
        @DisplayName("valid direct message delegates to processing service and returns OK")
        void validDirectMessageDelegates() {
                ImMessageRequest request = new ImMessageRequest(
                                "im", "direct", "dm-001", "assist-001",
                                null, "hello", "text", null, null);

                when(processingService.processChat(
                                "im", "direct", "dm-001", "assist-001",
                                null, "hello", "text", null, null, "IM"))
                                .thenReturn(InboundResult.ok());

                var response = controller.receiveMessage(request);

                assertEquals(HttpStatus.OK, response.getStatusCode());
                assertNotNull(response.getBody());
                assertEquals(0, response.getBody().getCode());
                verify(processingService).processChat(
                                "im", "direct", "dm-001", "assist-001",
                                null, "hello", "text", null, null, "IM");
        }

        @Test
        @DisplayName("valid group message delegates with chatHistory")
        void validGroupMessageDelegatesWithHistory() {
                var history = List.of(
                                new ImMessageRequest.ChatMessage("user-1", "Alice", "history", 1710000000L));
                ImMessageRequest request = new ImMessageRequest(
                                "im", "group", "grp-001", "assist-001",
                                null, "summarize this", "text", null, history);

                when(processingService.processChat(
                                eq("im"), eq("group"), eq("grp-001"), eq("assist-001"),
                                isNull(), eq("summarize this"), eq("text"), eq(null), eq(history), eq("IM")))
                                .thenReturn(InboundResult.ok());

                var response = controller.receiveMessage(request);

                assertEquals(HttpStatus.OK, response.getStatusCode());
                verify(processingService).processChat(
                                eq("im"), eq("group"), eq("grp-001"), eq("assist-001"),
                                isNull(), eq("summarize this"), eq("text"), eq(null), eq(history), eq("IM"));
        }

        @Test
        @DisplayName("processing service error result returns OK with error body")
        void processingErrorReturnsErrorBody() {
                ImMessageRequest request = new ImMessageRequest(
                                "im", "direct", "dm-001", "assist-001",
                                null, "hello", "text", null, null);

                when(processingService.processChat(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                                .thenReturn(InboundResult.error(404, "Invalid assistant account"));

                var response = controller.receiveMessage(request);

                assertEquals(HttpStatus.OK, response.getStatusCode());
                assertNotNull(response.getBody());
                assertEquals(404, response.getBody().getCode());
        }

        // ========== 校验测试 ==========

        @Test
        @DisplayName("null request returns 400")
        void nullRequestReturns400() {
                var response = controller.receiveMessage(null);
                assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
                verify(processingService, never()).processChat(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("non-IM domain returns 400")
        void nonImDomainReturns400() {
                ImMessageRequest request = new ImMessageRequest(
                                "external", "direct", "dm-001", "assist-001",
                                null, "hello", "text", null, null);

                var response = controller.receiveMessage(request);

                assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
                verify(processingService, never()).processChat(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("missing sessionType returns 400")
        void missingSessionTypeReturns400() {
                ImMessageRequest request = new ImMessageRequest(
                                "im", null, "dm-001", "assist-001",
                                null, "hello", "text", null, null);

                var response = controller.receiveMessage(request);

                assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
                verify(processingService, never()).processChat(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("missing content returns 400")
        void missingContentReturns400() {
                ImMessageRequest request = new ImMessageRequest(
                                "im", "direct", "dm-001", "assist-001",
                                null, null, "text", null, null);

                var response = controller.receiveMessage(request);

                assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
                verify(processingService, never()).processChat(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("non-text msgType returns 400")
        void nonTextMsgTypeReturns400() {
                ImMessageRequest request = new ImMessageRequest(
                                "im", "direct", "dm-001", "assist-001",
                                null, "hello", "image", null, null);

                var response = controller.receiveMessage(request);

                assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
                verify(processingService, never()).processChat(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        }
}
