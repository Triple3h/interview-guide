-- 存量学习帮手失败回答统一标记为「未完成」（completed = false）：
-- 未完成的消息不进多轮上下文，前端也能据此给出「重试」入口
UPDATE rag_chat_messages
SET completed = false
WHERE type = 'ASSISTANT'
  AND completed = true
  AND content = '【错误】回答生成失败，请重试';
