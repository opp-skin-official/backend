package skin.opp.backend.aireport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import skin.opp.backend.aireport.AiReportDtos.ReportRequest;

/** AI 답변(채팅)과 상담 리포트 신고. 본인 것만 신고할 수 있고, 같은 대상을 다시 신고하면 사유를 고친다. */
@Service
public class AiReportService {

    private static final Logger log = LoggerFactory.getLogger(AiReportService.class);

    private final JdbcClient jdbc;

    public AiReportService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 채팅·상담 대화 안의 AI 답변 하나를 신고한다. */
    @Transactional
    public void reportMessage(long userId, long conversationId, long messageId, ReportRequest request) {
        boolean found = jdbc.sql("""
                SELECT count(*) FROM conversation_messages m JOIN conversations c ON c.id = m.conversation_id
                WHERE m.id = :m AND c.id = :c AND c.user_id = :u AND m.role = 'ASSISTANT'
                """).param("m", messageId).param("c", conversationId).param("u", userId).query(Long.class).single() > 0;
        if (!found) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "답변을 찾을 수 없어요.");
        save(userId, conversationId, messageId, request);
    }

    /** 문진 상담의 관리 리포트를 신고한다 (id = 상담 id). */
    @Transactional
    public void reportConsultation(long userId, long conversationId, ReportRequest request) {
        boolean found = jdbc.sql("SELECT count(*) FROM care_reports WHERE conversation_id = :c AND user_id = :u")
                .param("c", conversationId).param("u", userId).query(Long.class).single() > 0;
        if (!found) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "리포트를 찾을 수 없어요.");
        save(userId, conversationId, null, request);
    }

    private void save(long userId, long conversationId, Long messageId, ReportRequest request) {
        String detail = request.detail() == null || request.detail().isBlank() ? null : request.detail().strip();
        jdbc.sql("""
                INSERT INTO ai_content_reports (user_id, conversation_id, message_id, reason, detail)
                VALUES (:u, :c, :m, :r, :d)
                ON CONFLICT (user_id, conversation_id, COALESCE(message_id, 0))
                DO UPDATE SET reason = EXCLUDED.reason, detail = EXCLUDED.detail, updated_at = now()
                """).param("u", userId).param("c", conversationId).param("m", messageId)
                .param("r", request.reason().name()).param("d", detail).update();
        // 운영자가 로그에서 바로 볼 수 있게 (내용은 DB에서 확인)
        log.warn("AI 콘텐츠 신고: conversation={} message={} reason={}", conversationId, messageId, request.reason());
    }
}
