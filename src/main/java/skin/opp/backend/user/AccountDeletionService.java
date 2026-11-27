package skin.opp.backend.user;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회원 탈퇴: 사용자와 그 사람의 모든 기록(설문, 상담·채팅, 리포트, 관리 계획·체크, 경과, 제품·리뷰, 주의 성분, AI 호출 기록, AI 신고)을
 * 바로 지우고, 다른 기기의 로그인 세션도 끊는다. 되돌릴 수 없다.
 * 외래 키가 ON DELETE CASCADE가 아니라서 자식 테이블부터 지운다. users를 참조하는 테이블이 새로 생기면
 * {@link #DIRECT_TABLES} 또는 아래 순서에 넣어야 한다(AccountDeletionIntegrationTest가 빠진 테이블을 잡는다).
 */
@Service
public class AccountDeletionService {

    /** user_id로 바로 지우는 테이블 (다른 테이블이 참조하지 않음) */
    static final List<String> DIRECT_TABLES = List.of(
            "consents", "skin_type_assessments", "service_environments", "skin_alerts",
            "progress_records", "user_products", "product_reviews", "ai_calls", "ai_content_reports");

    /** 순서가 있어 따로 지우는 테이블 */
    static final List<String> ORDERED_TABLES = List.of("care_reports", "conversations", "care_plans");

    private final JdbcClient jdbc;

    public AccountDeletionService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public void delete(long userId) {
        // 리포트는 상담(conversations)과 관리 계획(care_plans)을 모두 참조하므로 먼저
        exec("DELETE FROM care_reports WHERE user_id = :u", userId);
        // 후속 상담이 이전 상담을 가리키는 연결을 끊고 지운다(메시지·문진 요약은 CASCADE)
        exec("UPDATE conversations SET follows_id = NULL WHERE user_id = :u AND follows_id IS NOT NULL", userId);
        exec("DELETE FROM conversations WHERE user_id = :u", userId);
        exec("""
                DELETE FROM routine_checks WHERE routine_item_id IN (
                  SELECT i.id FROM routine_items i JOIN care_plans p ON p.id = i.care_plan_id WHERE p.user_id = :u)
                """, userId);
        exec("DELETE FROM routine_items WHERE care_plan_id IN (SELECT id FROM care_plans WHERE user_id = :u)", userId);
        exec("DELETE FROM care_plans WHERE user_id = :u", userId);
        for (String table : DIRECT_TABLES) {
            exec("DELETE FROM " + table + " WHERE user_id = :u", userId);
        }
        exec("DELETE FROM users WHERE id = :u", userId);
        // 모든 기기의 로그인 세션 (principal_name = 사용자 id, 세션 속성은 CASCADE)
        jdbc.sql("DELETE FROM spring_session WHERE principal_name = :name").param("name", String.valueOf(userId)).update();
    }

    private void exec(String sql, long userId) {
        jdbc.sql(sql).param("u", userId).update();
    }
}
