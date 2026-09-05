package interview.guide.modules.knowledgebase.listener;

import interview.guide.modules.knowledgebase.repository.RagChatSessionRepository;
import interview.guide.modules.user.model.UserCreatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 首位学习成员创建后，把无归属的存量 RAG 会话划归该成员
 * 保证升级前的历史会话在多用户模式下仍然可见
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RagChatSessionBackfillListener {

    private final RagChatSessionRepository sessionRepository;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onUserCreated(UserCreatedEvent event) {
        int updated = sessionRepository.backfillLegacySessions(event.userId());
        if (updated > 0) {
            log.info("存量 RAG 会话划归成员 userId={}: {} 个", event.userId(), updated);
        }
    }
}
