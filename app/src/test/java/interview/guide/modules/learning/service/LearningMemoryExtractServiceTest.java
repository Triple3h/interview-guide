package interview.guide.modules.learning.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.modules.knowledgebase.service.RagChatSessionService;
import interview.guide.modules.knowledgebase.service.RagChatSessionService.CompletedTurn;
import interview.guide.modules.learning.agent.LearningAgentProperties;
import interview.guide.modules.learning.model.LearningMemoryDTO.ExtractResult;
import interview.guide.modules.learning.model.LearningMemoryDTO.MemoryOp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.slf4j.Logger;
import org.springframework.ai.chat.client.ChatClient;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("个人记忆抽取服务测试")
class LearningMemoryExtractServiceTest {

    @Mock
    private RagChatSessionService sessionService;

    @Mock
    private LearningMemoryService memoryService;

    @Mock
    private LlmProviderRegistry llmProviderRegistry;

    @Mock
    private StructuredOutputInvoker structuredOutputInvoker;

    @Mock
    private ResourceLoader resourceLoader;

    @Mock
    private Resource systemResource;

    @Mock
    private Resource userResource;

    @Mock
    private ChatClient chatClient;

    private LearningMemoryExtractService extractService;

    @BeforeEach
    void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);
        when(resourceLoader.getResource("classpath:prompts/learning-memory-extract-system.st"))
            .thenReturn(systemResource);
        when(resourceLoader.getResource("classpath:prompts/learning-memory-extract-user.st"))
            .thenReturn(userResource);
        when(systemResource.getContentAsString(StandardCharsets.UTF_8)).thenReturn("整理记忆");
        when(userResource.getContentAsString(StandardCharsets.UTF_8))
            .thenReturn("已有：{existingMemories}\n问：{question}\n答：{answer}");
        when(llmProviderRegistry.getChatClientOrDefault(null)).thenReturn(chatClient);
        extractService = new LearningMemoryExtractService(
            sessionService, memoryService, llmProviderRegistry, structuredOutputInvoker,
            new LearningAgentProperties(), resourceLoader);
    }

    @Test
    @DisplayName("回合不可用时跳过，不调用模型")
    void shouldSkipWhenTurnMissing() {
        when(sessionService.findCompletedAssistantTurn(20L, 1L)).thenReturn(Optional.empty());

        extractService.extractFromCompletedTurn(1L, 10L, 20L);

        verify(structuredOutputInvoker, never()).invoke(
            any(), anyString(), anyString(), any(), any(), anyString(), anyString(), any(Logger.class));
        verify(memoryService, never()).applyExtractedOperations(any(), any(), any(), any());
    }

    @Test
    @DisplayName("抽取后把操作交给记忆服务落库")
    void shouldApplyExtractedOperations() {
        when(sessionService.findCompletedAssistantTurn(20L, 1L))
            .thenReturn(Optional.of(new CompletedTurn(10L, "我喜欢代码示例", "好的，之后讲解会配代码")));
        when(memoryService.recentMemories(eq(1L), anyInt())).thenReturn(List.of());
        when(structuredOutputInvoker.invoke(
            any(), anyString(), anyString(), any(), any(), anyString(), anyString(), any(Logger.class)))
            .thenReturn(new ExtractResult(List.of(
                new MemoryOp("ADD", null, "PREFERENCE", "讲解时给代码示例"),
                new MemoryOp("ADD", null, "NOTE", "多余1"),
                new MemoryOp("ADD", null, "NOTE", "多余2"),
                new MemoryOp("ADD", null, "NOTE", "多余3"),
                new MemoryOp("ADD", null, "NOTE", "多余4"),
                new MemoryOp("ADD", null, "NOTE", "不应写入"))));

        extractService.extractFromCompletedTurn(1L, 10L, 20L);

        ArgumentCaptor<List<MemoryOp>> captor = ArgumentCaptor.forClass(List.class);
        verify(memoryService).applyExtractedOperations(eq(1L), eq(10L), eq(20L), captor.capture());
        assertThat(captor.getValue()).hasSize(5);
    }
}
