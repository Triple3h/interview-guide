package interview.guide.modules.knowledgebase.service.chunking;

/**
 * 分块 metadata 键约定
 * 写入 vector_store 的 metadata 字段，与 kb_id 等向量服务键并存
 */
public final class ChunkMetadataKeys {

    /** 分块类型：qa / preface / section / general */
    public static final String CHUNK_TYPE = "chunk_type";
    /** chunk 在文档内的顺序号（0 开始） */
    public static final String CHUNK_SEQ = "chunk_seq";
    /** 题库分块的题号 */
    public static final String QUESTION_NO = "question_no";
    /** Markdown 小节的标题路径，如 "Java 基础 > 集合" */
    public static final String SECTION_PATH = "section_path";
    /** Markdown 标题层级（1-6） */
    public static final String HEADING_LEVEL = "heading_level";
    /** 同一逻辑块超长回退切分后的分段号（1 开始） */
    public static final String PART = "part";

    /** chunk_type 取值 */
    public static final String TYPE_QA = "qa";
    public static final String TYPE_PREFACE = "preface";
    public static final String TYPE_SECTION = "section";
    public static final String TYPE_GENERAL = "general";

    private ChunkMetadataKeys() {
    }
}
