package skin.opp.backend.consult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import skin.opp.backend.ai.FakeAiServer;
import skin.opp.backend.ai.FakeAiServer.Reply;
import skin.opp.backend.auth.KakaoOAuth2UserService;
import skin.opp.backend.user.KakaoProfile;
import skin.opp.backend.user.UserService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 문진 상담: AI에 보내는 값, 리포트·루틴 저장, 위험 신호, 실패 처리, 관리 계획 적용. AI는 가짜 서버. Docker 없으면 건너뜀. */
@SpringBootTest(properties = {"spring.docker.compose.enabled=false", "app.ai.retry-backoff=10ms"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class ConsultIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17");

    static final FakeAiServer ai = start();

    private static FakeAiServer start() {
        try {
            return new FakeAiServer();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void aiProperties(DynamicPropertyRegistry registry) {
        registry.add("app.ai.model-a.url", ai::url);
        registry.add("app.ai.model-a.api-key", () -> "test-model-a-key");
        registry.add("app.ai.model-b.url", ai::url);
        registry.add("app.ai.model-b.api-key", () -> "test-model-b-key");
    }

    @AfterAll
    static void stopAi() {
        ai.close();
    }

    @Autowired MockMvc mvc;
    @Autowired UserService userService;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;

    private long userId;

    static final String ANALYZE = """
            {"latency_ms":10,
             "cause_solution_report":{"schema_version":"cause_solution.v1","cause_solution_pairs":[
               {"regions":["CHIN"],"state_observation":"턱에 붉은기가 보입니다","cause_summary":"면도 마찰이 영향을 줄 수 있습니다",
                "solution_action":"결 방향으로 면도하고 진정 보습제를 바르세요","confidence":"high","evidence_ids":["STUDY-1"]}],
               "refer_to_clinician":false,"notice":"참고용 안내입니다.","referral_notice":null},
             "skin3d_payload":{"regions":{"CHIN":{"active":true,"trouble_intensity":0.7}}},
             "ingredient_profile":{"baumann_inferred":"OSNT",
               "recommended_ingredients":[{"key":"panthenol","inci":"Panthenol","label_ko":"판테놀","score":0.9}],
               "caution_ingredients":[{"key":"synthetic_fragrance","label_ko":"인공 향료","reason_axis":"S"}]},
             "internal_audit":{"gate_violations":[],"evidence_used":{"STUDY-1":"내부 원문"}}}""";

    static final String ROUTINE = """
            {"routine_plan":{"schema_version":"routine_plan.v1","mode":"period_plan","target_days":14,
               "summary":"14일 루틴입니다.","phases":[
                 {"phase_label":"1단계 자극 완화","day_range":"1~4일차","focus":"자극을 줄입니다",
                  "morning":["미온수로 세안하기","보습제 바르기"],"evening":["순한 세안제로 씻기"],
                  "post_activity":["훈련 직후 헹구기"],"checkpoint":"붉어짐 기록"},
                 {"phase_label":"2단계 회복","day_range":"5~14일차","focus":"회복","morning":["선크림"],"evening":["보습"],
                  "post_activity":[],"checkpoint":"점검"}],
               "evidence_ids":[],"refer_to_clinician":false,"notice":"참고용","referral_notice":null},
             "internal_audit":{"gate_violations":[]}}""";

    @BeforeEach
    void setUp() {
        ai.reset();
        userId = userService.upsertFromKakao(new KakaoProfile(System.nanoTime(), "상담 사용자", null, null)).getId();
    }

    private RequestPostProcessor login(long id) {
        return oauth2Login().oauth2User(new DefaultOAuth2User(
                AuthorityUtils.createAuthorityList("ROLE_USER"),
                Map.of(KakaoOAuth2UserService.USER_ID, id), KakaoOAuth2UserService.USER_ID));
    }

    private static String request(boolean redFlag, String careTime) {
        return """
                {"kind":"FOLLICULITIS_SHAVING","regions":["CHIN","CHEEK_LEFT"],"onset":"POST_PRODUCT_SHAVE",
                 "duration":"ACUTE_DAYS","severity":3,"change":"WORSENED","redFlag":%s,
                 "activities":["OUTDOOR_TRAINING","GAS_MASK","SHAVING"],"careTime":%s,
                 "products":["CLEANSER","MOISTURIZER"],"targetDays":14}"""
                .formatted(redFlag, careTime == null ? "null" : "\"" + careTime + "\"");
    }

    private ResultActions create(String body) throws Exception {
        return mvc.perform(post("/api/consultations").with(login(userId)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private JsonNode sentTo(String path) {
        return json.readTree(ai.received.stream().filter(r -> r.path().equals(path)).reduce((a, b) -> b).orElseThrow().body());
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table + " t JOIN conversations c ON c.id = t.conversation_id WHERE c.user_id = :u")
                .param("u", userId).query(Long.class).single();
    }

    @Test
    void createsReportAndRoutineFromIntake() throws Exception {
        jdbc.sql("""
                INSERT INTO skin_type_assessments (user_id, question_version, answers, axis_results, survey_code,
                    result_type_code, environment_tags)
                VALUES (:u, 1, '{}', '{}', 'OSNT-L', 'TYPE_08', '{}')
                """).param("u", userId).update();
        jdbc.sql("INSERT INTO skin_alerts (user_id, target) VALUES (:u, '향료')").param("u", userId).update();
        jdbc.sql("""
                INSERT INTO service_environments (user_id, wash_time, outdoor_training, allergy_experience)
                VALUES (:u, 'EVENING_ONLY', 'ALMOST_DAILY', 'YES')
                """).param("u", userId).update();
        ai.enqueue(Reply.json(200, ANALYZE)).enqueue(Reply.json(200, ROUTINE));

        String body = create(request(false, "EVENING_ONLY"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("COMPLETED"))
                .andExpect(jsonPath("$.title").value("왼쪽 볼·턱 · 면도 후 붉어짐·따가움"))
                .andExpect(jsonPath("$.answers.kind").value("FOLLICULITIS_SHAVING"))
                .andExpect(jsonPath("$.report.pairs[0].observation").value("턱에 붉은기가 보입니다"))
                .andExpect(jsonPath("$.report.pairs[0].regions[0]").value("CHIN"))
                .andExpect(jsonPath("$.report.notice").value("참고용 안내입니다."))
                .andExpect(jsonPath("$.report.recommendedIngredients[0].labelKo").value("판테놀"))
                .andExpect(jsonPath("$.report.cautionIngredients[0].labelKo").value("인공 향료"))
                .andExpect(jsonPath("$.report.routine.mode").value("period_plan"))
                .andExpect(jsonPath("$.report.routine.phases[0].morning[0]").value("미온수로 세안하기"))
                .andExpect(jsonPath("$.report.appliedPlanId").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("internal_audit", "내부 원문");

        JsonNode a = sentTo("/analyze");
        assertThat(ai.received.getFirst().apiKey()).isEqualTo("test-model-a-key");
        JsonNode concern = a.path("concerns").get(0);
        assertThat(concern.path("kind").asString()).isEqualTo("folliculitis_shaving");
        assertThat(concern.path("regions").toString()).isEqualTo("[\"CHEEK_LEFT\",\"CHIN\"]");
        assertThat(concern.path("onset").asString()).isEqualTo("post_product_shave");
        assertThat(concern.path("duration").asString()).isEqualTo("acute_days");
        assertThat(concern.path("severity").asInt()).isEqualTo(3);
        assertThat(concern.path("change").asString()).isEqualTo("worsened");
        assertThat(a.path("traits").path("allergies_conditions_medication").asString()).isEqualTo("향료");
        JsonNode military = a.path("military");
        assertThat(military.path("training").asString()).isEqualTo("야외훈련, 야외훈련 거의 매일");
        assertThat(military.path("irritants").asString()).isEqualTo("방독면 착용, 면도");
        assertThat(military.path("care_time").asString()).isEqualTo("저녁에만 세안 가능");
        assertThat(military.path("washing").asString()).isEqualTo("저녁에만 세안 가능");
        assertThat(military.path("hygiene_product_access").asString()).isEqualTo("세안제, 보습제");

        JsonNode b = sentTo("/routine");
        assertThat(b.path("target_days").asInt()).isEqualTo(14);
        assertThat(b.path("analysis_report").path("cause_solution_pairs").get(0).path("cause_summary").asString())
                .isEqualTo("면도 마찰이 영향을 줄 수 있습니다");
        assertThat(b.path("survey").path("traits").path("baumann").asString()).isEqualTo("OSNT");
        assertThat(b.path("survey").path("concerns").get(0).path("kind").asString()).isEqualTo("folliculitis_shaving");

        mvc.perform(get("/api/conversations").with(login(userId)))
                .andExpect(jsonPath("$[0].kind").value("CONSULT"))
                .andExpect(jsonPath("$[0].title").value("왼쪽 볼·턱 · 면도 후 붉어짐·따가움"));
    }

    @Test
    void redFlagEndsWithReferralWithoutCallingAi() throws Exception {
        create(request(true, null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("REFERRED"))
                .andExpect(jsonPath("$.answers.redFlag").value(true))
                .andExpect(jsonPath("$.report").doesNotExist());
        assertThat(ai.received).isEmpty();
    }

    @Test
    void careTimeIsRequiredUnlessReferred() throws Exception {
        create(request(false, null)).andExpect(status().isBadRequest());
        create(request(false, "EVENING_ONLY").replace("\"severity\":3", "\"severity\":6")).andExpect(status().isBadRequest());
        create(request(false, "EVENING_ONLY").replace("\"regions\":[\"CHIN\",\"CHEEK_LEFT\"]", "\"regions\":[]"))
                .andExpect(status().isBadRequest());
        assertThat(ai.received).isEmpty();
    }

    @Test
    void analysisFailureSavesNothing() throws Exception {
        ai.enqueue(Reply.json(400, "{\"error\":{\"code\":\"INVALID_INPUT\",\"message\":\"bad\"}}"));

        create(request(false, "MORNING_EVENING"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.detail").value("AI 분석을 하지 못했어요. 잠시 후 다시 시도해 주세요."));
        assertThat(count("consultation_summaries")).isZero();
        assertThat(ai.received).hasSize(1);
    }

    @Test
    void routineFailureKeepsReportAndCanBeRegenerated() throws Exception {
        ai.enqueue(Reply.json(200, ANALYZE))
                .enqueue(Reply.json(200, "{\"routine_plan\":{\"phases\":[]},\"internal_audit\":{\"gate_violations\":[\"x\"]}}"));

        String body = create(request(false, "MINIMAL"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.report.pairs.length()").value(1))
                .andExpect(jsonPath("$.report.routine").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        long id = json.readTree(body).path("id").asLong();

        mvc.perform(post("/api/consultations/{id}/apply", id).with(login(userId)).with(csrf()))
                .andExpect(status().isConflict());

        ai.enqueue(Reply.json(200, ROUTINE));
        mvc.perform(post("/api/consultations/{id}/routine", id).with(login(userId)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.report.routine.phases.length()").value(2));
        assertThat(sentTo("/routine").path("analysis_report").isObject()).isTrue();
    }

    @Test
    void applyStartsCarePlanWithFirstPhase() throws Exception {
        ai.enqueue(Reply.json(200, ANALYZE)).enqueue(Reply.json(200, ROUTINE));
        long id = json.readTree(create(request(false, "MORNING_EVENING")).andReturn().getResponse().getContentAsString())
                .path("id").asLong();

        mvc.perform(post("/api/consultations/{id}/apply", id).with(login(userId)).with(csrf()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.goalType").value("CUSTOM"))
                .andExpect(jsonPath("$.goalLabel").value("상담 루틴 마무리"))
                .andExpect(jsonPath("$.focusGoal").value("SKIN_CHANGE"));

        mvc.perform(get("/api/routines/today").with(login(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[0].timeOfDay").value("MORNING"))
                .andExpect(jsonPath("$.items[0].title").value("미온수로 세안하기"))
                .andExpect(jsonPath("$.items[0].subtitle").value("1단계 자극 완화"))
                .andExpect(jsonPath("$.items[2].timeOfDay").value("EVENING"));
        mvc.perform(get("/api/consultations/{id}", id).with(login(userId)))
                .andExpect(jsonPath("$.report.appliedPlanId").isNumber());
    }

    @Test
    void consultationsArePrivateAndNotChatConversations() throws Exception {
        long id = json.readTree(create(request(true, null)).andReturn().getResponse().getContentAsString()).path("id").asLong();
        long other = userService.upsertFromKakao(new KakaoProfile(System.nanoTime(), "다른 사용자", null, null)).getId();

        mvc.perform(get("/api/consultations/{id}", id).with(login(other))).andExpect(status().isNotFound());
        mvc.perform(post("/api/consultations/{id}/apply", id).with(login(other)).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(post("/api/conversations/{id}/messages", id).with(login(userId)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"질문\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/consultations/{id}", id)).andExpect(status().isUnauthorized());
    }

    @Test
    void latestReturnsNewestConsultationWithReport() throws Exception {
        mvc.perform(get("/api/consultations/latest").with(login(userId))).andExpect(status().isNotFound());
        ai.enqueue(Reply.json(200, ANALYZE)).enqueue(Reply.json(200, ROUTINE));
        long id = json.readTree(create(request(false, "MORNING_EVENING")).andReturn().getResponse().getContentAsString())
                .path("id").asLong();
        // 위험 신호로 끝난 상담은 리포트가 없어 "지난번" 대상이 아니다.
        create(request(true, null)).andExpect(status().isCreated());

        mvc.perform(get("/api/consultations/latest").with(login(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.followsId").doesNotExist());
    }

    @Test
    void followUpReusesAnalysisAndOnlyRegeneratesRoutine() throws Exception {
        ai.enqueue(Reply.json(200, ANALYZE)).enqueue(Reply.json(200, ROUTINE));
        long previous = json.readTree(create(request(false, "MORNING_EVENING")).andReturn().getResponse().getContentAsString())
                .path("id").asLong();
        ai.reset();
        ai.enqueue(Reply.json(200, ROUTINE.replace("14일 루틴입니다.", "저녁만 가능한 루틴입니다.")));

        String body = mvc.perform(post("/api/consultations/{id}/follow-ups", previous).with(login(userId)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"careTime\":\"MINIMAL\",\"products\":[\"ALL_IN_ONE\"],\"targetDays\":7}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("COMPLETED"))
                .andExpect(jsonPath("$.followsId").value(previous))
                .andExpect(jsonPath("$.answers.kind").value("FOLLICULITIS_SHAVING"))
                .andExpect(jsonPath("$.answers.severity").value(3))
                .andExpect(jsonPath("$.answers.careTime").value("MINIMAL"))
                .andExpect(jsonPath("$.answers.products[0]").value("ALL_IN_ONE"))
                .andExpect(jsonPath("$.answers.targetDays").value(7))
                .andExpect(jsonPath("$.report.pairs[0].observation").value("턱에 붉은기가 보입니다"))
                .andExpect(jsonPath("$.report.routine.summary").value("저녁만 가능한 루틴입니다."))
                .andReturn().getResponse().getContentAsString();
        long followUp = json.readTree(body).path("id").asLong();
        assertThat(followUp).isNotEqualTo(previous);

        // 분석(Model A)은 다시 부르지 않고 루틴(Model B)만 새 조건으로 부른다.
        assertThat(ai.received).extracting(FakeAiServer.Received::path).containsExactly("/routine");
        JsonNode b = sentTo("/routine");
        assertThat(b.path("target_days").asInt()).isEqualTo(7);
        assertThat(b.path("survey").path("military").path("care_time").asString()).isEqualTo("관리 시간 2분 이내");
        assertThat(b.path("survey").path("military").path("hygiene_product_access").asString()).isEqualTo("올인원");
        assertThat(b.path("analysis_report").path("cause_solution_pairs").get(0).path("cause_summary").asString())
                .isEqualTo("면도 마찰이 영향을 줄 수 있습니다");

        mvc.perform(get("/api/consultations/latest").with(login(userId)))
                .andExpect(jsonPath("$.id").value(followUp));
        // 이전 리포트는 당시 결과 그대로 남는다.
        mvc.perform(get("/api/consultations/{id}", previous).with(login(userId)))
                .andExpect(jsonPath("$.report.routine.summary").value("14일 루틴입니다."));
    }

    @Test
    void followUpFailureSavesNothingAndNeedsReport() throws Exception {
        ai.enqueue(Reply.json(200, ANALYZE)).enqueue(Reply.json(200, ROUTINE));
        long previous = json.readTree(create(request(false, "MORNING_EVENING")).andReturn().getResponse().getContentAsString())
                .path("id").asLong();
        long referred = json.readTree(create(request(true, null)).andReturn().getResponse().getContentAsString())
                .path("id").asLong();
        String body = "{\"careTime\":\"EVENING_ONLY\",\"products\":[],\"targetDays\":0}";
        long before = jdbc.sql("SELECT count(*) FROM conversations WHERE user_id = :u").param("u", userId).query(Long.class).single();

        ai.enqueue(Reply.json(200, "{\"routine_plan\":{},\"internal_audit\":{\"gate_violations\":[\"x\"]}}"));
        mvc.perform(post("/api/consultations/{id}/follow-ups", previous).with(login(userId)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.detail").exists());
        mvc.perform(post("/api/consultations/{id}/follow-ups", referred).with(login(userId)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/consultations/{id}/follow-ups", previous).with(login(userId)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"products\":[],\"targetDays\":0}"))
                .andExpect(status().isBadRequest());
        long other = userService.upsertFromKakao(new KakaoProfile(System.nanoTime(), "다른 사용자", null, null)).getId();
        mvc.perform(post("/api/consultations/{id}/follow-ups", previous).with(login(other)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound());

        assertThat(jdbc.sql("SELECT count(*) FROM conversations WHERE user_id = :u").param("u", userId).query(Long.class).single())
                .isEqualTo(before);
    }
}
