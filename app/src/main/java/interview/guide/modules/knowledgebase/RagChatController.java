package interview.guide.modules.knowledgebase;

import interview.guide.common.result.Result;
import interview.guide.common.web.CurrentUser;
import interview.guide.common.web.LoginUser;
import interview.guide.modules.knowledgebase.model.RagChatDTO.CreateSessionRequest;
import interview.guide.modules.knowledgebase.model.RagChatDTO.SessionDTO;
import interview.guide.modules.knowledgebase.model.RagChatDTO.SessionDetailDTO;
import interview.guide.modules.knowledgebase.model.RagChatDTO.SessionListItemDTO;
import interview.guide.modules.knowledgebase.model.RagChatDTO.UpdateKnowledgeBasesRequest;
import interview.guide.modules.knowledgebase.model.RagChatDTO.UpdateTitleRequest;
import interview.guide.modules.knowledgebase.service.RagChatSessionService;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * RAG 聊天控制器（会话管理）
 * 消息流式问答由学习帮手 Agent 提供：/api/learning/sessions/{id}/stream
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@Tag(name = "RAG 问答", description = "基于知识库的智能问答会话")
public class RagChatController {

    private final RagChatSessionService sessionService;

    /**
     * 创建新会话
     */
    @PostMapping("/api/rag-chat/sessions")
    public Result<SessionDTO> createSession(@Valid @RequestBody CreateSessionRequest request,
                                            @LoginUser CurrentUser currentUser) {
        return Result.success(sessionService.createSession(request, currentUser.id()));
    }

    /**
     * 获取会话列表（当前成员）
     */
    @GetMapping("/api/rag-chat/sessions")
    public Result<List<SessionListItemDTO>> listSessions(@LoginUser CurrentUser currentUser) {
        return Result.success(sessionService.listSessions(currentUser.id()));
    }

    /**
     * 获取会话详情（包含消息历史）
     * GET /api/rag-chat/sessions/{sessionId}
     */
    @GetMapping("/api/rag-chat/sessions/{sessionId}")
    public Result<SessionDetailDTO> getSessionDetail(@PathVariable Long sessionId,
                                                     @LoginUser CurrentUser currentUser) {
        return Result.success(sessionService.getSessionDetail(sessionId, currentUser.id()));
    }

    /**
     * 更新会话标题
     */
    @PutMapping("/api/rag-chat/sessions/{sessionId}/title")
    public Result<Void> updateSessionTitle(
            @PathVariable Long sessionId,
            @Valid @RequestBody UpdateTitleRequest request,
            @LoginUser CurrentUser currentUser) {
        sessionService.updateSessionTitle(sessionId, request.title(), currentUser.id());
        return Result.success(null);
    }

    /**
     * 切换会话置顶状态
     * PUT /api/rag-chat/sessions/{sessionId}/pin
     */
    @PutMapping("/api/rag-chat/sessions/{sessionId}/pin")
    public Result<Void> togglePin(@PathVariable Long sessionId,
                                  @LoginUser CurrentUser currentUser) {
        sessionService.togglePin(sessionId, currentUser.id());
        return Result.success(null);
    }

    /**
     * 更新会话知识库
     */
    @PutMapping("/api/rag-chat/sessions/{sessionId}/knowledge-bases")
    public Result<Void> updateSessionKnowledgeBases(
            @PathVariable Long sessionId,
            @Valid @RequestBody UpdateKnowledgeBasesRequest request,
            @LoginUser CurrentUser currentUser) {
        sessionService.updateSessionKnowledgeBases(sessionId, request.knowledgeBaseIds(), currentUser.id());
        return Result.success(null);
    }

    /**
     * 删除会话
     * DELETE /api/rag-chat/sessions/{sessionId}
     */
    @DeleteMapping("/api/rag-chat/sessions/{sessionId}")
    public Result<Void> deleteSession(@PathVariable Long sessionId,
                                      @LoginUser CurrentUser currentUser) {
        sessionService.deleteSession(sessionId, currentUser.id());
        return Result.success(null);
    }
}
