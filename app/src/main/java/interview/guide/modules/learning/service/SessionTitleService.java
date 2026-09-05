package interview.guide.modules.learning.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.modules.knowledgebase.model.RagChatSessionEntity;
import interview.guide.modules.knowledgebase.service.RagChatSessionService;
import interview.guide.modules.learning.agent.LearningAgentProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

/**
 * 学习会话标题自动生成：首轮回答结束后，把默认占位标题替换为概括对话主题的短标题。
 * 仅在标题仍是占位（或为空）时生成，绝不覆盖用户手动改名。
 */
@Slf4j
@Service
public class SessionTitleService {

    public static final String LEARNING_DEFAULT_TITLE = "新的学习对话";

    private static final int QUESTION_SNIPPET_CHARS = 200;
    private static final int ANSWER_SNIPPET_CHARS = 600;
    private static final int MAX_TITLE_CHARS = 30;
    private static final int FALLBACK_TITLE_CHARS = 16;

    private final LlmProviderRegistry llmProviderRegistry;
    private final RagChatSessionService sessionService;
    private final PromptTemplate titlePromptTemplate;

    public SessionTitleService(LlmProviderRegistry llmProviderRegistry,
                               RagChatSessionService sessionService,
                               LearningAgentProperties properties,
                               ResourceLoader resourceLoader) throws IOException {
        this.llmProviderRegistry = llmProviderRegistry;
        this.sessionService = sessionService;
        this.titlePromptTemplate = new PromptTemplate(resourceLoader
            .getResource(properties.getTitlePromptPath())
            .getContentAsString(StandardCharsets.UTF_8));
    }

    /**
     * 首轮回答结束后尝试自动改名；任何失败都静默返回 empty，不影响已完成的回答。
     *
     * @return 生成的标题；未生成（已改名/提问为空/生成失败）时返回 empty
     */
    public Optional<String> autoRenameIfDefault(Long sessionId, Long userId, String question, String answer) {
        try {
            RagChatSessionEntity session = sessionService.getOwnedSession(sessionId, userId);
            if (!isDefaultTitle(session.getTitle())) {
                return Optional.empty();
            }
            String title = generateTitle(question, answer);
            if (title == null || title.isBlank()) {
                return Optional.empty();
            }
            sessionService.updateSessionTitle(sessionId, title, userId);
            log.info("自动生成会话标题: sessionId={}, title={}", sessionId, title);
            return Optional.of(title);
        } catch (Exception e) {
            log.warn("自动生成会话标题失败: sessionId={}", sessionId, e);
            return Optional.empty();
        }
    }

    private boolean isDefaultTitle(String title) {
        return title == null || title.isBlank() || LEARNING_DEFAULT_TITLE.equals(title.trim());
    }

    /**
     * LLM 生成标题；失败或输出无效时退回「提问截断」兜底，保证首轮也能得到可用标题
     */
    private String generateTitle(String question, String answer) {
        if (question == null || question.isBlank()) {
            return null;
        }
        String generated = null;
        try {
            String prompt = titlePromptTemplate.render(Map.of(
                "question", snippet(question, QUESTION_SNIPPET_CHARS),
                "answer", snippet(answer == null ? "" : answer, ANSWER_SNIPPET_CHARS)
            ));
            String text = llmProviderRegistry.getPlainChatClient(null)
                .prompt()
                .user(prompt)
                .call()
                .chatResponse()
                .getResult()
                .getOutput()
                .getText();
            generated = stripToTitle(text);
        } catch (Exception e) {
            log.warn("调用模型生成会话标题失败，退回提问截断: {}", e.getMessage());
        }
        if (generated == null || generated.isBlank()) {
            generated = fallbackTitle(question);
        }
        return cut(generated, MAX_TITLE_CHARS);
    }

    /**
     * 提问截断兜底标题
     */
    private String fallbackTitle(String question) {
        String compact = stripToTitle(question);
        if (compact.length() <= FALLBACK_TITLE_CHARS) {
            return compact;
        }
        return compact.substring(0, FALLBACK_TITLE_CHARS) + "…";
    }

    /**
     * 去掉引号包裹、换行和常见「标题：」前缀，只留标题本体
     */
    private String stripToTitle(String text) {
        if (text == null) {
            return "";
        }
        String cleaned = text.replaceAll("[\"'“”„「」『』`]", "")
            .replaceAll("\\s+", " ")
            .trim();
        return cleaned.replaceFirst("^(标题|会话标题|title)[:：]\\s*", "").trim();
    }

    private String snippet(String text, int maxChars) {
        String compact = text.replaceAll("\\s+", " ").trim();
        return compact.length() <= maxChars ? compact : compact.substring(0, maxChars) + "…";
    }

    private String cut(String title, int maxChars) {
        return title.length() <= maxChars ? title : title.substring(0, maxChars);
    }
}
