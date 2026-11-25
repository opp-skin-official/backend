package skin.opp.backend.skintype;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;
import skin.opp.backend.auth.KakaoOAuth2UserService;
import skin.opp.backend.user.KakaoProfile;
import skin.opp.backend.user.UserService;

/** 실제 PostgreSQL로 설문 시드 데이터, 채점, 동의 처리, 저장·조회를 확인한다. Docker 없으면 건너뜀. */
@SpringBootTest(properties = "spring.docker.compose.enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class SkinSurveyIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17");

    @Autowired MockMvc mvc;
    @Autowired UserService userService;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;

    private long userId;

    @BeforeEach
    void createUser() {
        userId = userService.upsertFromKakao(
                new KakaoProfile(System.nanoTime(), "설문 사용자", null, null)).getId();
    }

    private RequestPostProcessor login(long id) {
        return oauth2Login().oauth2User(new DefaultOAuth2User(
                AuthorityUtils.createAuthorityList("ROLE_USER"),
                Map.of(KakaoOAuth2UserService.USER_ID, id), KakaoOAuth2UserService.USER_ID));
    }

    /** 축별 선택 위치: 유분·민감도·색소·노화요인·관리성향 순서 */
    private static Map<Integer, Integer> answers(int oil, int sens, int pig, int wt, int care, boolean includeSensitive) {
        Map<Integer, Integer> a = new LinkedHashMap<>();
        int[] axis = {oil, sens, pig, wt, care};
        for (int n = 1; n <= 25; n++) {
            if (!includeSensitive && (n == 14 || n == 18)) continue;
            a.put(n, axis[(n - 1) / 5]);
        }
        return a;
    }

    private String body(boolean consent, Map<Integer, Integer> answers) {
        return json.writeValueAsString(Map.of("version", 1, "sensitiveConsent", consent, "answers", answers));
    }

    @Test
    void seedDataIsComplete() {
        assertThat(jdbc.sql("select count(*) from skin_types").query(Long.class).single()).isEqualTo(32);
        assertThat(jdbc.sql("select count(*) from skin_type_questions").query(Long.class).single()).isEqualTo(25);
        assertThat(jdbc.sql("select count(*) from skin_type_question_options").query(Long.class).single()).isEqualTo(100);
        // 4.2 매핑표: TYPE_08 = OSNT + 간편형
        assertThat(jdbc.sql("select oil||'/'||sensitivity||'/'||pigmentation||'/'||wt||'/'||care_style from skin_types where code='TYPE_08'")
                .query(String.class).single()).isEqualTo("OILY/SENSITIVE/NON_PIGMENTED/T/SIMPLE");
    }

    @Test
    void surveyRequiresLoginAndHidesScores() throws Exception {
        mvc.perform(get("/api/skin-survey")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/skin-survey").with(login(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.questions.length()").value(25))
                .andExpect(jsonPath("$.questions[0].options.length()").value(4))
                .andExpect(jsonPath("$.questions[0].options[0].score").doesNotExist())
                .andExpect(jsonPath("$.questions[13].sensitive").value(true))
                .andExpect(jsonPath("$.questions[3].image").value(true));
    }

    @Test
    void submitWithConsentScoresAllAnswersAndRecordsConsent() throws Exception {
        // 유분 4, 민감도 4, 색소 1, 노화요인 1, 관리성향 1 → OSNT-L → TYPE_08
        mvc.perform(post("/api/skin-survey/assessments").with(login(userId)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body(true, answers(4, 4, 1, 1, 1, true))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.typeCode").value("TYPE_08"))
                .andExpect(jsonPath("$.title").value("번들거리고 예민한 유형"))
                .andExpect(jsonPath("$.skinTags[0]").value("번들거림"))
                .andExpect(jsonPath("$.skinTags[1]").value("얼굴이 쉽게 붉어짐"))
                .andExpect(jsonPath("$.careStyleLabel").value("간단하게 관리"))
                .andExpect(jsonPath("$.environmentTags[0]").value("야외 활동 1시간 미만"))
                .andExpect(jsonPath("$.environmentTags[1]").value("선크림을 매일 덧바름"))
                .andExpect(jsonPath("$.environmentTags.length()").value(3))
                .andExpect(jsonPath("$.axes[0].letter").value("O"))
                .andExpect(jsonPath("$.axes[0].score").value(100.0))
                .andExpect(jsonPath("$.retestRecommendedAt").doesNotExist());

        assertThat(jdbc.sql("select count(*) from consents where user_id = :u and type = 'SENSITIVE_HEALTH'")
                .param("u", userId).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("select survey_code from skin_type_assessments where user_id = :u")
                .param("u", userId).query(String.class).single()).isEqualTo("OSNT-L");
    }

    @Test
    void withoutConsentSensitiveQuestionsAreExcludedFromScoring() throws Exception {
        // 색소 축 4문항 모두 ③(3점): (12-4)/12 = 66.7 → P
        mvc.perform(post("/api/skin-survey/assessments").with(login(userId)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body(false, answers(1, 1, 3, 1, 4, false))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.axes[2].letter").value("P"))
                .andExpect(jsonPath("$.axes[2].score").value(66.7));

        // 민감 문항(14번 색소, 18번 노화요인)이 빠져 두 축은 4문항으로 채점된다.
        assertThat(jdbc.sql("select (axis_results->'PIGMENTATION'->>'answered')::int from skin_type_assessments where user_id = :u")
                .param("u", userId).query(Integer.class).single()).isEqualTo(4);
        assertThat(jdbc.sql("select (axis_results->'WT'->>'answered')::int from skin_type_assessments where user_id = :u")
                .param("u", userId).query(Integer.class).single()).isEqualTo(4);
        assertThat(jdbc.sql("select count(*) from consents where user_id = :u").param("u", userId)
                .query(Long.class).single()).isZero();
    }

    @Test
    void sensitiveAnswerWithoutConsentIsRejected() throws Exception {
        mvc.perform(post("/api/skin-survey/assessments").with(login(userId)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body(false, answers(1, 1, 1, 1, 1, true))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void missingAnswerIsRejected() throws Exception {
        Map<Integer, Integer> a = answers(1, 1, 1, 1, 1, true);
        a.remove(7);
        mvc.perform(post("/api/skin-survey/assessments").with(login(userId)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body(true, a)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void borderlineSetsRetestDateAndLatestReturnsNewestOwnResult() throws Exception {
        mvc.perform(get("/api/skin-survey/assessments/latest").with(login(userId)))
                .andExpect(status().isNotFound());

        mvc.perform(post("/api/skin-survey/assessments").with(login(userId)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body(true, answers(4, 4, 4, 4, 4, true))))
                .andExpect(status().isCreated());
        // 유분 축: ③(3점) 5개 → 합 15 → 66.7, 경계 아님 / 관리성향 ②·③ 섞어 합 13 → 53.3 경계
        Map<Integer, Integer> a = answers(3, 1, 1, 1, 3, true);
        a.put(21, 2); a.put(22, 2); // 관리성향 합 = 2+2+3+3+3 = 13
        mvc.perform(post("/api/skin-survey/assessments").with(login(userId)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body(true, a)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.axes[4].borderline").value(true))
                .andExpect(jsonPath("$.axes[4].letter").value("F"))
                .andExpect(jsonPath("$.retestRecommendedAt").exists());

        mvc.perform(get("/api/skin-survey/assessments/latest").with(login(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.axes[4].borderline").value(true));

        long otherUser = userService.upsertFromKakao(new KakaoProfile(System.nanoTime(), "다른 사용자", null, null)).getId();
        mvc.perform(get("/api/skin-survey/assessments/latest").with(login(otherUser)))
                .andExpect(status().isNotFound());
    }
}
