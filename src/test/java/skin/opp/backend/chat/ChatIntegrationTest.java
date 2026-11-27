package skin.opp.backend.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import skin.opp.backend.ai.FakeAiServer;
import skin.opp.backend.ai.FakeAiServer.Reply;
import skin.opp.backend.auth.KakaoOAuth2UserService;
import skin.opp.backend.TestUsers;
import skin.opp.backend.user.TermsService;
import skin.opp.backend.user.UserService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 피부 Q&A: 대화 저장, AI에 보내는 맥락, SSE 이벤트, 안전 검사·실패 처리. AI는 가짜 서버. Docker 없으면 건너뜀. */
@SpringBootTest(properties = {"spring.docker.compose.enabled=false", "app.ai.retry-backoff=10ms"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class ChatIntegrationTest {

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
        registry.add("app.ai.model-b.url", ai::url);
        registry.add("app.ai.model-b.api-key", () -> "test-model-b-key");
    }

    @AfterAll
    static void stopAi() {
        ai.close();
    }

    @Autowired MockMvc mvc;
    @Autowired UserService userService;
    @Autowired TermsService terms;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;

    private long userId;

    @BeforeEach
    void setUp() {
        ai.reset();
        userId = TestUsers.member(userService, terms, "채팅 사용자");
    }

    private RequestPostProcessor login(long id) {
        return oauth2Login().oauth2User(new DefaultOAuth2User(
                AuthorityUtils.createAuthorityList("ROLE_USER"),
                Map.of(KakaoOAuth2UserService.USER_ID, id), KakaoOAuth2UserService.USER_ID));
    }

    private static String finalEvent(String reply, String violations) {
        return "event: final\ndata: {\"replace_reply\":true,\"chat_response\":{\"reply\":\"" + reply + "\","
                + "\"action_tips\":[\"결 방향으로 면도하기\"],\"follow_up_questions\":[\"루틴을 받아보실까요?\"],"
                + "\"evidence_ids\":[\"STUDY-1\"],\"refer_to_clinician\":false,"
                + "\"notice\":\"참고용 안내입니다.\",\"referral_notice\":null},"
                + "\"internal_audit\":{\"gate_violations\":" + violations + ",\"evidence_used\":{\"STUDY-1\":\"내부 원문\"}}}\n\n";
    }

    private long createConversation(String body) throws Exception {
        var request = post("/api/conversations").with(login(userId)).with(csrf());
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(body);
        String response = mvc.perform(request).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(response).path("id").asLong();
    }

    record Event(String name, JsonNode data) {}

    /** 질문을 보내고 SSE 스트림이 끝날 때까지 기다려 이벤트를 돌려준다. */
    private List<Event> send(long conversationId, String content) throws Exception {
        MvcResult result = mvc.perform(post("/api/conversations/{id}/messages", conversationId)
                        .with(login(userId)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("content", content))))
                .andExpect(request().asyncStarted())
                .andReturn();
        // nginx가 SSE를 모았다가 보내지 않도록 (deploy/nginx-api.opp.skin.conf와 별개로 보장)
        assertThat(result.getResponse().getHeader("X-Accel-Buffering")).isEqualTo("no");
        assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-cache");
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        String body = "";
        while (System.nanoTime() < deadline) {
            body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
            // 이벤트는 여러 번에 나눠 쓰이므로, 마지막 이벤트의 data와 끝 빈 줄까지 온 뒤에 읽는다.
            boolean terminal = body.contains("event:done") || body.contains("event:error");
            if (terminal && body.endsWith("\n\n")) break;
            Thread.sleep(20);
        }
        List<Event> events = new ArrayList<>();
        for (String block : body.split("\n\n")) {
            String name = null;
            StringBuilder data = new StringBuilder();
            for (String line : block.split("\n")) {
                if (line.startsWith("event:")) name = line.substring(6);
                else if (line.startsWith("data:")) data.append(line.substring(5));
            }
            if (name != null) events.add(new Event(name, json.readTree(data.toString())));
        }
        return events;
    }

    private JsonNode lastRequestToAi() {
        return json.readTree(ai.received.getLast().body());
    }

    @Test
    void requiresLogin() throws Exception {
        mvc.perform(get("/api/conversations")).andExpect(status().isUnauthorized());
    }

    @Test
    void streamsAnswerWithSavedProfileAndStoresMessages() throws Exception {
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
        ai.enqueue(Reply.sse(
                "event: meta\ndata: {\"cluster\":\"shaving\"}\n\n",
                "event: delta\ndata: {\"text\":\"면도 \"}\n\n",
                "event: delta\ndata: {\"text\":\"직후\"}\n\n",
                finalEvent("면도 직후에는 순한 보습제를 바르세요.", "[]")));
        long id = createConversation(null);

        List<Event> events = send(id, "  면도 후 턱이 따가워요. 어떻게 관리하면 좋을까요? 매일 아침 면도를 하는데 계속 그래요  ");

        assertThat(events).extracting(Event::name).containsExactly("question", "delta", "delta", "done");
        assertThat(events.get(0).data().path("role").asString()).isEqualTo("USER");
        assertThat(events.get(1).data().path("text").asString()).isEqualTo("면도 ");
        JsonNode done = events.get(3).data();
        assertThat(done.path("role").asString()).isEqualTo("ASSISTANT");
        assertThat(done.path("status").asString()).isEqualTo("DONE");
        assertThat(done.path("content").asString()).isEqualTo("면도 직후에는 순한 보습제를 바르세요.");
        assertThat(done.path("format").asString()).isEqualTo("text"); // markdown이 없는 응답(이전 형식)
        assertThat(done.path("actionTips").get(0).asString()).isEqualTo("결 방향으로 면도하기");
        assertThat(done.path("followUpQuestions").get(0).asString()).isEqualTo("루틴을 받아보실까요?");
        assertThat(done.path("notice").asString()).isEqualTo("참고용 안내입니다.");
        assertThat(done.toString()).doesNotContain("내부 원문", "evidence_used", "internal_audit");

        JsonNode sent = lastRequestToAi();
        assertThat(ai.received.getLast().path()).isEqualTo("/chat/stream");
        assertThat(ai.received.getLast().apiKey()).isEqualTo("test-model-b-key");
        assertThat(sent.path("user_message").asString()).startsWith("면도 후 턱이 따가워요.");
        assertThat(sent.path("survey").path("traits").path("baumann").asString()).isEqualTo("OSNT");
        assertThat(sent.path("survey").path("traits").path("allergies").get(0).asString()).isEqualTo("향료");
        assertThat(sent.path("survey").path("military").path("training").asString()).isEqualTo("야외훈련 거의 매일");
        assertThat(sent.path("survey").path("military").path("washing").asString()).isEqualTo("저녁에만 세안 가능");
        assertThat(sent.has("history")).isFalse();

        mvc.perform(get("/api/conversations/{id}", id).with(login(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("면도 후 턱이 따가워요. 어떻게 관리하면 좋을까요? 매일 아침 면도를 하…"))
                .andExpect(jsonPath("$.messages.length()").value(2))
                .andExpect(jsonPath("$.messages[1].actionTips[0]").value("결 방향으로 면도하기"));
        mvc.perform(get("/api/conversations").with(login(userId)))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].kind").value("QNA"));
    }

    @Test
    void markdownAnswerIsSavedAsIs() throws Exception {
        // v6.3: final.markdown에 실천 팁까지 들어 있는 전체 답변이 오고, 저장은 markdown 기준(명세 4.3)
        String markdown = "면도 후 따가움은 흔해요.\\n\\n### 실천 팁\\n- **순한 보습제** 바르기\\n\\n더 궁금한 점이 있나요?";
        ai.enqueue(Reply.sse(
                "event: meta\ndata: {\"version\":\"6.3.2\"}\n\n",
                "event: tool_call\ndata: {\"step\":1,\"name\":\"check_ingredient_conflicts\",\"status_ko\":\"점검 중\"}\n\n",
                "event: tool_result\ndata: {\"step\":1,\"name\":\"check_ingredient_conflicts\",\"result_summary\":\"{}\"}\n\n",
                "event: delta\ndata: {\"text\":\"면도 후\",\"format\":\"markdown\"}\n\n",
                "event: final\ndata: {\"replace_reply\":true,\"markdown\":\"" + markdown + "\","
                        + "\"chat_response\":{\"reply\":\"면도 후 따가움은 흔해요. 더 궁금한 점이 있나요?\","
                        + "\"action_tips\":[\"순한 보습제 바르기\"],\"follow_up_questions\":[],\"refer_to_clinician\":false,"
                        + "\"notice\":\"참고용 안내입니다.\"},\"internal_audit\":{\"gate_violations\":[]}}\n\n"));
        long id = createConversation(null);

        List<Event> events = send(id, "면도 후 따가워요");

        assertThat(events).extracting(Event::name).containsExactly("question", "delta", "done"); // 도구 이벤트는 넘기지 않음
        JsonNode done = events.getLast().data();
        assertThat(done.path("format").asString()).isEqualTo("markdown");
        assertThat(done.path("content").asString())
                .isEqualTo("면도 후 따가움은 흔해요.\n\n### 실천 팁\n- **순한 보습제** 바르기\n\n더 궁금한 점이 있나요?");
        assertThat(done.path("actionTips").get(0).asString()).isEqualTo("순한 보습제 바르기");
        mvc.perform(get("/api/conversations/{id}", id).with(login(userId)))
                .andExpect(jsonPath("$.messages[1].format").value("markdown"))
                .andExpect(jsonPath("$.messages[1].content").value(org.hamcrest.Matchers.startsWith("면도 후 따가움은 흔해요.\n\n### 실천 팁")));
    }

    @Test
    void followUpSendsRecentHistoryAndProfileCanBeTurnedOff() throws Exception {
        long id = createConversation("{\"useSavedProfile\":false}");
        ai.enqueue(Reply.sse(finalEvent("첫 답변", "[]")));
        send(id, "첫 질문");
        ai.enqueue(Reply.sse(finalEvent("둘째 답변", "[]")));

        send(id, "둘째 질문");

        JsonNode sent = lastRequestToAi();
        assertThat(sent.has("survey")).isFalse();
        assertThat(sent.path("history").size()).isEqualTo(2);
        assertThat(sent.path("history").get(0).path("role").asString()).isEqualTo("user");
        assertThat(sent.path("history").get(0).path("content").asString()).isEqualTo("첫 질문");
        assertThat(sent.path("history").get(1).path("role").asString()).isEqualTo("assistant");
        assertThat(sent.path("history").get(1).path("content").asString()).isEqualTo("첫 답변");
    }

    @Test
    void gateViolationReplacesAnswerAndIsLeftOutOfHistory() throws Exception {
        long id = createConversation(null);
        ai.enqueue(Reply.sse("event: delta\ndata: {\"text\":\"진단\"}\n\n", finalEvent("진단합니다", "[\"medical_dx\"]")));

        List<Event> events = send(id, "이거 무슨 병이에요?");

        JsonNode done = events.getLast().data();
        assertThat(events.getLast().name()).isEqualTo("done");
        assertThat(done.path("status").asString()).isEqualTo("BLOCKED");
        assertThat(done.path("content").asString()).isEqualTo(ChatService.BLOCKED_REPLY);
        assertThat(done.path("referToClinician").asBoolean()).isTrue();

        ai.enqueue(Reply.sse(finalEvent("답변", "[]")));
        send(id, "다른 질문");
        JsonNode history = lastRequestToAi().path("history");
        assertThat(history.size()).isEqualTo(1);
        assertThat(history.get(0).path("content").asString()).isEqualTo("이거 무슨 병이에요?");
    }

    @Test
    void aiFailureSendsErrorEventAndKeepsOnlyQuestion() throws Exception {
        long id = createConversation(null);
        for (int i = 0; i < 3; i++) ai.enqueue(Reply.json(503, "{\"detail\":\"busy\"}"));

        List<Event> events = send(id, "질문");

        assertThat(events).extracting(Event::name).containsExactly("question", "error");
        assertThat(events.get(1).data().path("code").asString())
                .as("events=%s, AI 요청=%s", events, ai.received).isEqualTo("UNAVAILABLE");
        assertThat(events.get(1).data().path("message").asString()).contains("잠시 후 다시 시도");
        assertThat(ai.received).hasSize(3);
        mvc.perform(get("/api/conversations/{id}", id).with(login(userId)))
                .andExpect(jsonPath("$.messages.length()").value(1))
                .andExpect(jsonPath("$.messages[0].role").value("USER"));
    }

    @Test
    void blankQuestionIsRejected() throws Exception {
        long id = createConversation(null);
        mvc.perform(post("/api/conversations/{id}/messages", id).with(login(userId)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"   \"}"))
                .andExpect(status().isBadRequest());
        assertThat(ai.received).isEmpty();
    }

    @Test
    void otherUsersConversationIsHidden() throws Exception {
        long id = createConversation(null);
        long other = TestUsers.member(userService, terms, "다른 사용자");

        mvc.perform(get("/api/conversations/{id}", id).with(login(other))).andExpect(status().isNotFound());
        mvc.perform(post("/api/conversations/{id}/messages", id).with(login(other)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"질문\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/conversations").with(login(other))).andExpect(jsonPath("$.length()").value(0));
        assertThat(ai.received).isEmpty();
    }

    /** 상담 리포트 하나를 daysAgo일 전에 만든 것으로 넣는다. */
    private void consultReport(int daysAgo, String cause) {
        long c = jdbc.sql("""
                INSERT INTO conversations (user_id, kind, title, state) VALUES (:u, 'CONSULT', '상담', 'COMPLETED') RETURNING id
                """).param("u", userId).query(Long.class).single();
        jdbc.sql("""
                INSERT INTO consultation_summaries (conversation_id, answers, red_flag) VALUES (:c, CAST(:a AS jsonb), false)
                """).param("c", c).param("a", """
                {"kind":"FOLLICULITIS_SHAVING","regions":["CHIN"],"onset":"POST_PRODUCT_SHAVE","duration":"ACUTE_DAYS",
                 "severity":3,"change":"WORSENED","redFlag":false,"targetDays":14}""").update();
        jdbc.sql("""
                INSERT INTO care_reports (conversation_id, user_id, analysis, created_at)
                VALUES (:c, :u, CAST(:a AS jsonb), now() - make_interval(days => :d))
                """).param("c", c).param("u", userId).param("d", daysAgo).param("a", """
                {"cause_solution_report":{"schema_version":"cause_solution.v1","cause_solution_pairs":[
                  {"regions":["CHIN"],"cause_summary":"%s","solution_action":"결 방향으로 면도"}],"refer_to_clinician":false},
                 "internal_audit":{"gate_violations":[]}}""".formatted(cause)).update();
    }

    @Test
    void recentConsultationReportIsSentAsContext() throws Exception {
        consultReport(40, "오래된 원인");
        consultReport(2, "면도 마찰");
        ai.enqueue(Reply.sse(finalEvent("답변", "[]")));
        long id = createConversation(null);

        send(id, "지난번 상담한 턱은 어떻게 관리해요?");

        JsonNode sent = lastRequestToAi();
        assertThat(sent.path("analysis_report").path("cause_solution_pairs").get(0).path("cause_summary").asString())
                .isEqualTo("면도 마찰");
        assertThat(sent.path("analysis_report").has("internal_audit")).isFalse();
        JsonNode concern = sent.path("survey").path("concerns").get(0);
        assertThat(concern.path("kind").asString()).isEqualTo("folliculitis_shaving");
        assertThat(concern.path("regions").get(0).asString()).isEqualTo("CHIN");
        assertThat(concern.path("onset").asString()).isEqualTo("post_product_shave");
        assertThat(concern.path("severity").asInt()).isEqualTo(3);
    }

    @Test
    void oldReportOrProfileOffSendsNoReport() throws Exception {
        consultReport(40, "오래된 원인");
        ai.enqueue(Reply.sse(finalEvent("답변", "[]")));
        send(createConversation(null), "질문");
        assertThat(lastRequestToAi().has("analysis_report")).isFalse();
        assertThat(lastRequestToAi().path("survey").has("concerns")).isFalse();

        consultReport(1, "최근 원인");
        ai.enqueue(Reply.sse(finalEvent("답변", "[]")));
        send(createConversation("{\"useSavedProfile\":false}"), "질문");
        assertThat(lastRequestToAi().has("analysis_report")).isFalse();
        assertThat(lastRequestToAi().has("survey")).isFalse();
    }
}
