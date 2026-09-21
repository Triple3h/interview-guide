package interview.guide.modules.learning.agent;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.ai.learning-agent")
public class LearningAgentProperties {

    private String systemPromptPath = "classpath:prompts/learning-agent-system.st";

    /**
     * 首轮回答结束后自动生成会话标题的 Prompt 模板
     */
    private String titlePromptPath = "classpath:prompts/learning-session-title.st";

    /**
     * 知识库检索条数
     */
    private int searchTopK = 6;

    /**
     * 知识库检索最低相似度
     */
    private double searchMinScore = 0.25;

    /**
     * 单个检索片段注入 prompt 的最大字符数
     */
    private int maxChunkChars = 800;

    /**
     * 注入 system prompt 的台账条数上限
     */
    private int promptTopicLimit = 30;

    /**
     * 注入 system prompt 的个人记忆条数上限
     */
    private int promptMemoryLimit = 20;

    /**
     * 抽取时提供给模型对照的已有记忆条数上限。
     * 太小会让较老的记忆对模型不可见（既改不了，也拦不住重复 ADD），
     * 按每轮最多写 5 条估算，100 条够几十轮积累
     */
    private int extractMemoryContextLimit = 100;

    /**
     * 抽取 Prompt：系统指令
     */
    private String extractSystemPromptPath = "classpath:prompts/learning-memory-extract-system.st";

    /**
     * 抽取 Prompt：本轮对话与已有记忆
     */
    private String extractUserPromptPath = "classpath:prompts/learning-memory-extract-user.st";

    /**
     * 抽取时学员提问截断字符数
     */
    private int extractQuestionChars = 400;

    /**
     * 抽取时助手回答截断字符数
     */
    private int extractAnswerChars = 1200;

    /**
     * 单轮抽取最多落库的操作数
     */
    private int extractMaxOperations = 5;

    /**
     * 手动 ReAct 循环的最大工具轮数，超过后强制无工具收尾
     */
    private int maxRounds = 6;

    /**
     * askLearner 提问等待学员应答的超时秒数（需小于网关 SSE 空闲超时，当前 nginx 为 300s）
     */
    private int askTimeoutSeconds = 180;
}
