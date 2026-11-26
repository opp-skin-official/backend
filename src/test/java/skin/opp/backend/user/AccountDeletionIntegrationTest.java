package skin.opp.backend.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import skin.opp.backend.auth.KakaoOAuth2UserService;

/** 회원 탈퇴: 본인 기록은 모두 지우고 다른 사람 기록은 그대로 둔다. Docker 없으면 건너뜀. */
@SpringBootTest(properties = "spring.docker.compose.enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class AccountDeletionIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17");

    @Autowired MockMvc mvc;
    @Autowired UserService userService;
    @Autowired JdbcClient jdbc;

    private RequestPostProcessor login(long id) {
        return oauth2Login().oauth2User(new DefaultOAuth2User(
                AuthorityUtils.createAuthorityList("ROLE_USER"),
                Map.of(KakaoOAuth2UserService.USER_ID, id), KakaoOAuth2UserService.USER_ID));
    }

    private long newUser(String nickname) {
        return userService.upsertFromKakao(new KakaoProfile(System.nanoTime(), nickname, null, null)).getId();
    }

    /** users를 참조하는 모든 테이블에 이 사용자 기록을 하나 이상 만든다. */
    private void fillEverything(long u, long productId) {
        jdbc.sql("INSERT INTO consents (user_id, type) VALUES (:u, 'TERMS')").param("u", u).update();
        jdbc.sql("""
                INSERT INTO skin_type_assessments (user_id, question_version, answers, axis_results, survey_code, result_type_code, environment_tags)
                VALUES (:u, 1, '{}', '[]', 'OSNTS', (SELECT code FROM skin_types LIMIT 1), '{}')
                """).param("u", u).update();
        jdbc.sql("INSERT INTO service_environments (user_id, wash_time, outdoor_training, allergy_experience) VALUES (:u, 'EVENING_ONLY', 'IRREGULAR', 'NO')")
                .param("u", u).update();
        jdbc.sql("INSERT INTO skin_alerts (user_id, target) VALUES (:u, '향료')").param("u", u).update();
        jdbc.sql("INSERT INTO progress_records (user_id, record_date, perceived_change, skin_condition) VALUES (:u, current_date, 'SAME', 'NORMAL')")
                .param("u", u).update();
        jdbc.sql("INSERT INTO user_products (user_id, product_id, kind) VALUES (:u, :p, 'OWNED')").param("u", u).param("p", productId).update();
        jdbc.sql("INSERT INTO product_reviews (product_id, user_id, rating, content) VALUES (:p, :u, 5, '좋아요')")
                .param("u", u).param("p", productId).update();
        jdbc.sql("INSERT INTO ai_calls (user_id, kind) VALUES (:u, 'CHAT')").param("u", u).update();

        long plan = jdbc.sql("""
                INSERT INTO care_plans (user_id, goal_type, focus_goal, start_date, target_date, status)
                VALUES (:u, 'CUSTOM', 'SKIN_CHANGE', current_date, current_date + 13, 'ACTIVE') RETURNING id
                """).param("u", u).query(Long.class).single();
        long item = jdbc.sql("INSERT INTO routine_items (care_plan_id, time_of_day, title, sort_order) VALUES (:p, 'EVENING', '보습', 0) RETURNING id")
                .param("p", plan).query(Long.class).single();
        jdbc.sql("INSERT INTO routine_checks (routine_item_id, check_date) VALUES (:i, current_date)").param("i", item).update();

        long first = jdbc.sql("INSERT INTO conversations (user_id, kind, state) VALUES (:u, 'CONSULT', 'COMPLETED') RETURNING id")
                .param("u", u).query(Long.class).single();
        long followUp = jdbc.sql("INSERT INTO conversations (user_id, kind, state, follows_id) VALUES (:u, 'CONSULT', 'COMPLETED', :f) RETURNING id")
                .param("u", u).param("f", first).query(Long.class).single();
        for (long c : List.of(first, followUp)) {
            jdbc.sql("INSERT INTO consultation_summaries (conversation_id, answers, red_flag) VALUES (:c, '{}', false)").param("c", c).update();
            jdbc.sql("INSERT INTO care_reports (conversation_id, user_id, analysis, applied_plan_id) VALUES (:c, :u, '{}', :p)")
                    .param("c", c).param("u", u).param("p", plan).update();
        }
        long qna = jdbc.sql("INSERT INTO conversations (user_id, kind) VALUES (:u, 'QNA') RETURNING id").param("u", u).query(Long.class).single();
        jdbc.sql("INSERT INTO conversation_messages (conversation_id, role, content) VALUES (:c, 'USER', '질문')").param("c", qna).update();

        // 다른 기기의 로그인 세션
        String id = UUID.randomUUID().toString();
        jdbc.sql("""
                INSERT INTO spring_session (primary_id, session_id, creation_time, last_access_time, max_inactive_interval, expiry_time, principal_name)
                VALUES (:id, :id, 0, 0, 1800, 9999999999999, :name)
                """).param("id", id).param("name", String.valueOf(u)).update();
    }

    /** 이 사용자와 연결된 행 수 (테이블별) */
    private Map<String, Long> rowsOf(long u) {
        String byConversation = "(SELECT id FROM conversations WHERE user_id = :u)";
        Map<String, String> sql = new java.util.LinkedHashMap<>();
        sql.put("users", "SELECT count(*) FROM users WHERE id = :u");
        for (String t : AccountDeletionService.DIRECT_TABLES) sql.put(t, "SELECT count(*) FROM " + t + " WHERE user_id = :u");
        for (String t : AccountDeletionService.ORDERED_TABLES) sql.put(t, "SELECT count(*) FROM " + t + " WHERE user_id = :u");
        sql.put("routine_items", "SELECT count(*) FROM routine_items WHERE care_plan_id IN (SELECT id FROM care_plans WHERE user_id = :u)");
        sql.put("routine_checks", "SELECT count(*) FROM routine_checks c JOIN routine_items i ON i.id = c.routine_item_id JOIN care_plans p ON p.id = i.care_plan_id WHERE p.user_id = :u");
        sql.put("conversation_messages", "SELECT count(*) FROM conversation_messages WHERE conversation_id IN " + byConversation);
        sql.put("consultation_summaries", "SELECT count(*) FROM consultation_summaries WHERE conversation_id IN " + byConversation);
        sql.put("spring_session", "SELECT count(*) FROM spring_session WHERE principal_name = CAST(:u AS text)");
        Map<String, Long> out = new java.util.LinkedHashMap<>();
        sql.forEach((table, q) -> out.put(table, jdbc.sql(q).param("u", u).query(Long.class).single()));
        return out;
    }

    @Test
    void deletesEverythingOfTheUserAndNothingOfOthers() throws Exception {
        long product = jdbc.sql("INSERT INTO products (brand, name, category, price) VALUES ('브랜드', '제품', 'CREAM', 1000) RETURNING id")
                .query(Long.class).single();
        long leaving = newUser("떠나는 사용자");
        long staying = newUser("남는 사용자");
        fillEverything(leaving, product);
        fillEverything(staying, product);
        assertThat(rowsOf(leaving).values()).allMatch(n -> n > 0); // 준비: 모든 테이블에 기록이 있다

        mvc.perform(delete("/api/me").with(login(leaving)).with(csrf())).andExpect(status().isNoContent());

        assertThat(rowsOf(leaving)).allSatisfy((table, n) -> assertThat(n).as(table).isZero());
        assertThat(rowsOf(staying)).allSatisfy((table, n) -> assertThat(n).as(table).isPositive());
        assertThat(jdbc.sql("SELECT count(*) FROM products WHERE id = :p").param("p", product).query(Long.class).single()).isOne();
    }

    @Test
    void requiresLoginAndCsrf() throws Exception {
        long u = newUser("사용자");
        mvc.perform(delete("/api/me").with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/me").with(login(u))).andExpect(status().isForbidden());
        assertThat(jdbc.sql("SELECT count(*) FROM users WHERE id = :u").param("u", u).query(Long.class).single()).isOne();
    }

    @Test
    void everyTableReferencingUsersIsCovered() {
        // users를 참조하는 테이블이 새로 생기면 탈퇴 때 지우도록 AccountDeletionService에 넣어야 한다.
        List<String> referencing = jdbc.sql("""
                SELECT DISTINCT conrelid::regclass::text FROM pg_constraint
                WHERE contype = 'f' AND confrelid = 'users'::regclass
                """).query(String.class).list();
        List<String> covered = new ArrayList<>(AccountDeletionService.DIRECT_TABLES);
        covered.addAll(AccountDeletionService.ORDERED_TABLES);
        assertThat(covered).containsExactlyInAnyOrderElementsOf(referencing);
    }
}
