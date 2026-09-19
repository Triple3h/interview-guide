package interview.guide.modules.knowledgebase.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.infrastructure.mapper.KnowledgeBaseMapper;
import interview.guide.infrastructure.mapper.RagChatMapper;
import interview.guide.modules.knowledgebase.model.RagChatMessageEntity;
import interview.guide.modules.knowledgebase.model.RagChatMessageEntity.MessageType;
import interview.guide.modules.knowledgebase.model.RagChatSessionEntity;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import interview.guide.modules.knowledgebase.repository.RagChatMessageRepository;
import interview.guide.modules.knowledgebase.repository.RagChatSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("RAG 聊天会话服务测试（回答重试与失败落库）")
class RagChatSessionServiceTest {

    private static final Long SESSION_ID = 7L;
    private static final Long USER_ID = 1L;

    @Mock
    private RagChatSessionRepository sessionRepository;
    @Mock
    private RagChatMessageRepository messageRepository;
    @Mock
    private KnowledgeBaseRepository knowledgeBaseRepository;
    @Mock
    private RagChatMapper ragChatMapper;
    @Mock
    private KnowledgeBaseMapper knowledgeBaseMapper;
    @Mock
    private KnowledgeBaseQueryProperties queryProperties;

    private RagChatSessionService sessionService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        sessionService = new RagChatSessionService(sessionRepository, messageRepository,
            knowledgeBaseRepository, ragChatMapper, knowledgeBaseMapper, queryProperties);

        RagChatSessionEntity session = new RagChatSessionEntity();
        session.setUserId(USER_ID);
        session.setMessageCount(4);
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
    }

    private RagChatMessageEntity message(Long id, MessageType type, String content, int order) {
        RagChatMessageEntity message = new RagChatMessageEntity();
        message.setId(id);
        message.setType(type);
        message.setContent(content);
        message.setMessageOrder(order);
        message.setCompleted(true);
        return message;
    }

    @Nested
    @DisplayName("重试准备")
    class PrepareRetry {

        @Test
        @DisplayName("最后一条是回答时：原位重置该条并返回对应的学员提问")
        void shouldResetLastAnswerAndReturnQuestion() {
            RagChatMessageEntity question = message(1L, MessageType.USER, "帮我入门 Redis", 2);
            RagChatMessageEntity answer = message(2L, MessageType.ASSISTANT, "【错误】回答生成失败，请重试", 3);
            answer.setToolStepsJson("[{\"tool\":\"searchKnowledgeBase\"}]");
            answer.setTimelineJson("[{\"kind\":\"text\",\"text\":\"已经写了一半\"}]");
            when(messageRepository.findRecentBySessionId(eq(SESSION_ID), any(Pageable.class)))
                .thenReturn(List.of(answer, question));

            RagChatSessionService.RetryPreparation retry = sessionService.prepareRetryMessage(SESSION_ID, USER_ID);

            assertThat(retry.messageId()).isEqualTo(2L);
            assertThat(retry.question()).isEqualTo("帮我入门 Redis");
            assertThat(answer.getContent()).isEmpty();
            assertThat(answer.getCompleted()).isFalse();
            assertThat(answer.getToolStepsJson()).isNull();
            assertThat(answer.getTimelineJson()).isNull();
            verify(messageRepository).save(answer);
        }

        @Test
        @DisplayName("最后一条不是回答时：抛 BAD_REQUEST 且不写库")
        void shouldRejectWhenLastMessageIsNotAnswer() {
            RagChatMessageEntity question = message(1L, MessageType.USER, "帮我入门 Redis", 2);
            when(messageRepository.findRecentBySessionId(eq(SESSION_ID), any(Pageable.class)))
                .thenReturn(List.of(question));

            assertThatThrownBy(() -> sessionService.prepareRetryMessage(SESSION_ID, USER_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("没有可重试的回答");
            verify(messageRepository, never()).save(any());
        }

        @Test
        @DisplayName("会话里没有消息时：抛 BAD_REQUEST")
        void shouldRejectWhenSessionIsEmpty() {
            when(messageRepository.findRecentBySessionId(eq(SESSION_ID), any(Pageable.class)))
                .thenReturn(List.of());

            assertThatThrownBy(() -> sessionService.prepareRetryMessage(SESSION_ID, USER_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("没有可重试的回答");
        }

        @Test
        @DisplayName("会话不属于当前学员时：按不存在处理")
        void shouldRejectWhenSessionNotOwned() {
            RagChatSessionEntity other = new RagChatSessionEntity();
            other.setUserId(99L);
            when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(other));

            assertThatThrownBy(() -> sessionService.prepareRetryMessage(SESSION_ID, USER_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("会话不存在");
            verify(messageRepository, never()).findRecentBySessionId(any(), any());
        }
    }

    @Nested
    @DisplayName("失败落库")
    class FailStream {

        @Test
        @DisplayName("保留已生成的部分内容，并标记为未完成")
        void shouldKeepPartialContentAndMarkIncomplete() {
            RagChatMessageEntity answer = message(5L, MessageType.ASSISTANT, "", 9);
            when(messageRepository.findById(5L)).thenReturn(Optional.of(answer));

            sessionService.failStreamMessage(5L, "已经写了一半");

            assertThat(answer.getContent()).isEqualTo("已经写了一半");
            assertThat(answer.getCompleted()).isFalse();
            verify(messageRepository).save(answer);
        }

        @Test
        @DisplayName("一段内容都没生成时：内容写入空串（content 列非空）")
        void shouldStoreEmptyContentWhenNothingGenerated() {
            RagChatMessageEntity answer = message(6L, MessageType.ASSISTANT, "旧内容", 9);
            when(messageRepository.findById(6L)).thenReturn(Optional.of(answer));

            sessionService.failStreamMessage(6L, null);

            assertThat(answer.getContent()).isEmpty();
            assertThat(answer.getCompleted()).isFalse();
        }

        @Test
        @DisplayName("消息不存在时：抛 NOT_FOUND")
        void shouldThrowWhenMessageMissing() {
            when(messageRepository.findById(66L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> sessionService.failStreamMessage(66L, "内容"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("消息不存在");
        }
    }
}
