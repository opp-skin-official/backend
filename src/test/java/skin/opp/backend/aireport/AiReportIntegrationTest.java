package skin.opp.backend.aireport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import skin.opp.backend.TestUsers;
import skin.opp.backend.auth.KakaoOAuth2UserService;
import skin.opp.backend.user.TermsService;
import skin.opp.backend.user.UserService;

/** AI 답변·상담 리포트 신고. Docker 없으면 건너뜀. */
@SpringBootTest(properties = "spring.docker.compose.enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class AiReportIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17");

    @Autowired MockMvc mvc;
    @Autowired UserService userService;
    @Autowired TermsService terms;
    @Autowired JdbcClient jdbc;

    private long userId;
    private long qna;
    private long question;
    private long answer;

    private RequestPostProcessor login(long id) {
        return oauth2Login().oauth2User(new DefaultOAuth2User(
                AuthorityUtils.createAuthorityList("ROLE_USER"),
                Map.of(KakaoOAuth2UserService.USER_ID, id), KakaoOAuth2UserService.USER_ID));
    }

    private long conversation(long user, String kind) {
        return jdbc.sql("INSERT INTO conversations (user_id, kind) VALUES (:u, :k) RETURNING id")
                .param("u", user).param("k", kind).query(Long.class).single();
    }

    private long message(long conversation, String role) {
        return jdbc.sql("INSERT INTO conversation_messages (conversation_id, role, content) VALUES (:c, :r, '내용') RETURNING id")
                .param("c", conversation).param("r", role).query(Long.class).single();
    }

    private long consultWithReport(long user) {
        long c = conversation(user, "CONSULT");
        jdbc.sql("INSERT INTO care_reports (conversation_id, user_id, analysis) VALUES (:c, :u, '{}')")
                .param("c", c).param("u", user).update();
        return c;
    }

    private ResultActions reportMessage(long user, long conversation, long message, String body) throws Exception {
        return mvc.perform(post("/api/conversations/{id}/messages/{m}/reports", conversation, message)
                .with(login(user)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions reportConsult(long user, long conversation, String body) throws Exception {
        return mvc.perform(post("/api/consultations/{id}/reports", conversation)
                .with(login(user)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private Map<String, Object> onlyReport() {
        return jdbc.sql("SELECT conversation_id, message_id, reason, detail FROM ai_content_reports WHERE user_id = :u")
                .param("u", userId).query().singleRow();
    }

    /** 이 테스트 사용자(와 다른 사용자)가 남긴 신고 수. 테스트끼리 DB를 함께 쓰므로 전체가 아니라 사용자로 센다. */
    private long reportsBy(long... users) {
        long n = 0;
        for (long u : users) {
            n += jdbc.sql("SELECT count(*) FROM ai_content_reports WHERE user_id = :u").param("u", u).query(Long.class).single();
        }
        return n;
    }

    @BeforeEach
    void setUp() {
        userId = TestUsers.member(userService, terms, "신고 사용자");
        qna = conversation(userId, "QNA");
        question = message(qna, "USER");
        answer = message(qna, "ASSISTANT");
    }

    @Test
    void reportsOwnAnswerAndReportingAgainUpdatesIt() throws Exception {
        reportMessage(userId, qna, answer, "{\"reason\":\"OFFENSIVE\",\"detail\":\"  말투가 불쾌해요 \"}")
                .andExpect(status().isNoContent());
        assertThat(onlyReport()).containsEntry("conversation_id", qna).containsEntry("message_id", answer)
                .containsEntry("reason", "OFFENSIVE").containsEntry("detail", "말투가 불쾌해요");

        reportMessage(userId, qna, answer, "{\"reason\":\"MEDICAL\",\"detail\":\"\"}").andExpect(status().isNoContent());
        assertThat(onlyReport()).containsEntry("reason", "MEDICAL").containsEntry("detail", null);
    }

    @Test
    void onlyOwnAssistantMessagesCanBeReported() throws Exception {
        String body = "{\"reason\":\"HARMFUL\"}";
        reportMessage(userId, qna, question, body).andExpect(status().isNotFound()); // 내 질문은 신고 대상이 아님
        reportMessage(userId, qna, 999_999, body).andExpect(status().isNotFound());
        reportMessage(userId, conversation(userId, "QNA"), answer, body).andExpect(status().isNotFound()); // 다른 대화의 id

        long other = TestUsers.member(userService, terms, "다른 사용자");
        reportMessage(other, qna, answer, body).andExpect(status().isNotFound());
        assertThat(reportsBy(userId, other)).isZero();
    }

    @Test
    void reportsOwnConsultationReport() throws Exception {
        long consult = consultWithReport(userId);
        reportConsult(userId, consult, "{\"reason\":\"INACCURATE\"}").andExpect(status().isNoContent());
        assertThat(onlyReport()).containsEntry("conversation_id", consult).containsEntry("message_id", null)
                .containsEntry("reason", "INACCURATE");

        // 리포트가 없는 상담, 다른 사람의 상담은 찾을 수 없다
        reportConsult(userId, conversation(userId, "CONSULT"), "{\"reason\":\"OTHER\"}").andExpect(status().isNotFound());
        long other = TestUsers.member(userService, terms, "다른 사용자");
        reportConsult(other, consult, "{\"reason\":\"OTHER\"}").andExpect(status().isNotFound());
        assertThat(reportsBy(userId, other)).isOne();
    }

    @Test
    void validatesInputAndRequiresLoginAndCsrf() throws Exception {
        reportMessage(userId, qna, answer, "{}").andExpect(status().isBadRequest());
        reportMessage(userId, qna, answer, "{\"reason\":\"SPAM\"}").andExpect(status().isBadRequest());
        reportMessage(userId, qna, answer, "{\"reason\":\"OTHER\",\"detail\":\"" + "가".repeat(501) + "\"}")
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/consultations/{id}/reports", qna).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"OTHER\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/consultations/{id}/reports", qna).with(login(userId))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"OTHER\"}"))
                .andExpect(status().isForbidden());
        assertThat(reportsBy(userId)).isZero();
    }
}
