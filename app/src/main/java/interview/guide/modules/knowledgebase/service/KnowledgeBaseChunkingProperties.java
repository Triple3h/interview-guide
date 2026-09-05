package interview.guide.modules.knowledgebase.service;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 知识库分块配置
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.knowledgebase.chunking")
public class KnowledgeBaseChunkingProperties {

    /** 通用文本分块的目标 token 数 */
    private int chunkSizeTokens = 800;

    /** 相邻 chunk 的重叠字符数，0 表示关闭重叠 */
    private int overlapChars = 200;

    /** 是否启用题库按题分块 */
    private boolean qaEnabled = true;

    /** 判定为题库所需的最少题号标记数 */
    private int qaMinQuestionMarkers = 3;

    /** 没有答案标记时，判定为题库所需的最少题号标记数 */
    private int qaMinMarkersWithoutAnswer = 10;

    /** 相邻小节/相邻题目合并的最小字符阈值 */
    private int minChunkChars = 120;
}
