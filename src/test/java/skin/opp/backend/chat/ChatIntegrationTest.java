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
import skin.opp.backend.user.KakaoProfile;
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
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;

    private long userId;

    @BeforeEach
    void setUp() {
        ai.reset();
        userId = userService.upsertFromKakao(new KakaoProfile(System.nanoTime(), "채팅 사용자", null, null)).getId();
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
        long other = userService.upsertFromKakao(new KakaoProfile(System.nanoTime(), "다른 사용자", null, null)).getId();

        mvc.perform(get("/api/conversations/{id}", id).with(login(other))).andExpect(status().isNotFound());
        mvc.perform(post("/api/conversations/{id}/messages", id).with(login(other)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"질문\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/conversations").with(login(other))).andExpect(jsonPath("$.length()").value(0));
        assertThat(ai.received).isEmpty();
    }
}
