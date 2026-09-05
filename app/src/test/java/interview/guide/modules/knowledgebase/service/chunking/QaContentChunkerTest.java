package interview.guide.modules.knowledgebase.service.chunking;

import interview.guide.infrastructure.file.ParsedDocument;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseChunkingProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("题库按题分块测试")
class QaContentChunkerTest {

    private KnowledgeBaseChunkingProperties props() {
        return new KnowledgeBaseChunkingProperties();
    }

    private ParsedDocument richText(String content) {
        return new ParsedDocument(content, ParsedDocument.DocumentFormat.RICH_TEXT);
    }

    private static final String QA_BANK = """
        Java 面试题库，涵盖集合、并发等知识点。

        1. HashMap 的底层实现是什么？
        A. 数组加链表
        B. 纯红黑树
        C. 跳表
        答案：A
        解析：JDK8 之后为数组加链表加红黑树。

        2. ArrayList 默认初始容量是多少？
        A. 8
        B. 10
        C. 16
        答案：B
        解析：默认容量 10，扩容为原来的 1.5 倍。

        3. 以下哪个集合不是线程安全的？
        A. Vector
        B. Hashtable
        C. HashSet
        答案：C
        解析：HashSet 基于 HashMap 实现，不具备线程安全性。
        """;

    @Nested
    @DisplayName("题库识别")
    class Supports {

        @Test
        @DisplayName("带答案标记的编号题库识别为题库")
        void shouldSupportQaBankWithAnswers() {
            QaContentChunker chunker = new QaContentChunker(props());

            assertTrue(chunker.supports(richText(QA_BANK)));
        }

        @Test
        @DisplayName("第N题格式识别为题库")
        void shouldSupportDiFormat() {
            String content = """
                第1题：什么是事务的传播行为？
                答案：事务传播行为描述了事务方法嵌套调用时事务如何传递。
                第2题：什么是脏读？
                答案：读到其他事务未提交的数据。
                第3题：什么是不可重复读？
                答案：同一事务内两次读取结果不一致。
                """;
            QaContentChunker chunker = new QaContentChunker(props());

            assertTrue(chunker.supports(richText(content)));
        }

        @Test
        @DisplayName("少量无答案的编号列表不误判为题库")
        void shouldRejectNumberedListWithoutAnswers() {
            String content = """
                项目计划
                1. 完成用户模块
                2. 完成订单模块
                3. 上线监控告警
                """;
            QaContentChunker chunker = new QaContentChunker(props());

            assertFalse(chunker.supports(richText(content)));
        }

        @Test
        @DisplayName("无答案但题号数量达到阈值的列表识别为题库")
        void shouldSupportLongQuestionListWithoutAnswers() {
            StringBuilder content = new StringBuilder("口述题清单\n");
            for (int i = 1; i <= 12; i++) {
                content.append(i).append(". 解释第 ").append(i).append(" 个概念的含义。\n");
            }
            QaContentChunker chunker = new QaContentChunker(props());

            assertTrue(chunker.supports(richText(content.toString())));
        }

        @Test
        @DisplayName("关闭配置后一律不支持")
        void shouldRespectQaEnabledFlag() {
            KnowledgeBaseChunkingProperties properties = props();
            properties.setQaEnabled(false);
            QaContentChunker chunker = new QaContentChunker(properties);

            assertFalse(chunker.supports(richText(QA_BANK)));
        }
    }

    @Nested
    @DisplayName("分块行为")
    class Chunking {

        @Test
        @DisplayName("一题一块，导语单独成块，题干与答案保持在同一块")
        void shouldChunkPerQuestion() {
            KnowledgeBaseChunkingProperties properties = props();
            properties.setMinChunkChars(0); // 关闭合并，验证一一对应
            QaContentChunker chunker = new QaContentChunker(properties);

            List<Document> chunks = chunker.chunk(richText(QA_BANK));

            assertEquals(4, chunks.size(), "导语 + 三道题");
            assertEquals(ChunkMetadataKeys.TYPE_PREFACE, chunks.get(0).getMetadata().get(ChunkMetadataKeys.CHUNK_TYPE));
            for (int i = 1; i <= 3; i++) {
                Document chunk = chunks.get(i);
                assertEquals(ChunkMetadataKeys.TYPE_QA, chunk.getMetadata().get(ChunkMetadataKeys.CHUNK_TYPE));
                assertEquals(i, chunk.getMetadata().get(ChunkMetadataKeys.QUESTION_NO));
                assertTrue(chunk.getText().contains("答案："), "题干与答案应在同一块: " + chunk.getText());
            }
            assertTrue(chunks.get(1).getText().contains("HashMap"));
            assertTrue(chunks.get(3).getText().contains("HashSet"));
        }

        @Test
        @DisplayName("相邻过小的题目块合并到最小块大小")
        void shouldMergeSmallBlocks() {
            KnowledgeBaseChunkingProperties properties = props();
            properties.setMinChunkChars(10000); // 全部合并
            QaContentChunker chunker = new QaContentChunker(properties);

            List<Document> chunks = chunker.chunk(richText(QA_BANK));

            assertEquals(2, chunks.size(), "导语 + 合并后的题库块");
            Document merged = chunks.get(1);
            assertEquals(ChunkMetadataKeys.TYPE_QA, merged.getMetadata().get(ChunkMetadataKeys.CHUNK_TYPE));
            assertEquals(1, merged.getMetadata().get(ChunkMetadataKeys.QUESTION_NO), "合并块取首个题号");
            assertTrue(merged.getText().contains("HashMap"));
            assertTrue(merged.getText().contains("HashSet"));
        }

        @Test
        @DisplayName("题目解析里的子编号不会拆出新题目")
        void shouldNotSplitOnSubNumbering() {
            String content = """
                1. 第一题的题干。答案：A
                2. 第二题的题干。答案：B
                3. 第三题的题干。答案：C
                4. 第四题的题干。答案：D
                5. 第五题的题干。答案：E
                解析：2) 这是第五题解析里的子编号，不是新题目。
                """;
            KnowledgeBaseChunkingProperties properties = props();
            properties.setMinChunkChars(0);
            QaContentChunker chunker = new QaContentChunker(properties);

            List<Document> chunks = chunker.chunk(richText(content));

            assertEquals(5, chunks.size(), "子编号不应拆出新题目");
            Document last = chunks.get(4);
            assertTrue(last.getText().contains("子编号"), "子编号内容应留在第五题块内");
        }

        @Test
        @DisplayName("超长单题回退通用切分并保留题号元数据")
        void shouldFallbackSplitOversizedBlock() {
            KnowledgeBaseChunkingProperties properties = props();
            properties.setChunkSizeTokens(50); // maxChars = 100
            QaContentChunker chunker = new QaContentChunker(properties);

            StringBuilder content = new StringBuilder("1. 请详细描述 JVM 内存模型。\n答案：");
            for (int i = 0; i < 30; i++) {
                content.append("堆内存分为新生代和老年代，新生代又分为 Eden 和两个 Survivor 区。");
            }
            content.append("\n");

            List<Document> chunks = chunker.chunk(richText(content.toString()));

            assertTrue(chunks.size() >= 2, "超长题目应被回退切分");
            for (int i = 0; i < chunks.size(); i++) {
                Document chunk = chunks.get(i);
                assertEquals(ChunkMetadataKeys.TYPE_QA, chunk.getMetadata().get(ChunkMetadataKeys.CHUNK_TYPE));
                assertEquals(1, chunk.getMetadata().get(ChunkMetadataKeys.QUESTION_NO));
                assertEquals(i + 1, chunk.getMetadata().get(ChunkMetadataKeys.PART));
            }
        }
    }
}
