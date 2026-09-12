-- 学习帮手回答时间线（思考 / 工具调用 / 正文，按发生顺序），供历史会话回放
ALTER TABLE rag_chat_messages
    ADD COLUMN timeline_json TEXT;
