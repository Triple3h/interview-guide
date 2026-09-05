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
     * 手动 ReAct 循环的最大工具轮数，超过后强制无工具收尾
     */
    private int maxRounds = 6;
}
