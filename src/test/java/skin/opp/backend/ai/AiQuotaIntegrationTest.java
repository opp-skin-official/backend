package skin.opp.backend.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import skin.opp.backend.ai.FakeAiServer.Reply;
import skin.opp.backend.auth.KakaoOAuth2UserService;
import skin.opp.backend.user.KakaoProfile;
import skin.opp.backend.user.UserService;
import tools.jackson.databind.ObjectMapper;

/** 사용자별 AI 호출 횟수 제한(최근 24시간). 제한을 2회로 낮춰 확인한다. Docker 없으면 건너뜀. */
@SpringBootTest(properties = {"spring.docker.compose.enabled=false", "app.ai.retry-backoff=10ms",
        "app.ai.limit.chat-per-day=2", "app.ai.limit.consult-per-day=2"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class AiQuotaIntegrationTest {

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

    @BeforeEach
    void setUp() {
        ai.reset();
        userId = newUser();
    }

    private long newUser() {
        return userService.upsertFromKakao(new KakaoProfile(System.nanoTime(), "제한 사용자", null, null)).getId();
    }

    private RequestPostProcessor login(long id) {
        return oauth2Login().oauth2User(new DefaultOAuth2User(
                AuthorityUtils.createAuthorityList("ROLE_USER"),
                Map.of(KakaoOAuth2UserService.USER_ID, id), KakaoOAuth2UserService.USER_ID));
    }

    private static final String INTAKE = """
            {"kind":"FOLLICULITIS_SHAVING","regions":["CHIN"],"onset":"POST_PRODUCT_SHAVE",
             "duration":"ACUTE_DAYS","severity":3,"change":"WORSENED","redFlag":%s,
             "activities":["SHAVING"],"careTime":"EVENING_ONLY","products":["CLEANSER"],"targetDays":14}""";

    private ResultActions consult(long user, boolean redFlag) throws Exception {
        return mvc.perform(post("/api/consultations").with(login(user)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(INTAKE.formatted(redFlag)));
    }

    private static Reply aiRejects() {
        // 재시도하지 않는 오류라 바로 끝난다(실패한 호출도 횟수에 센다)
        return Reply.json(400, "{\"error\":{\"code\":\"INVALID_INPUT\",\"message\":\"bad\"}}");
    }

    @Test
    void consultationsStopAtLimitAndReopenAfter24Hours() throws Exception {
        ai.enqueue(aiRejects()).enqueue(aiRejects()).enqueue(aiRejects());
        consult(userId, false).andExpect(status().isBadGateway());
        consult(userId, false).andExpect(status().isBadGateway());

        consult(userId, false)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.detail").value("AI 상담은 하루(24시간)에 2번까지 할 수 있어요. 나중에 다시 이용해 주세요."));
        assertThat(ai.received).hasSize(2); // 세 번째는 모델을 부르지 않았다

        // 위험 신호 안내는 AI를 쓰지 않으므로 제한과 상관없다
        consult(userId, true).andExpect(status().isCreated());
        // 다른 사용자는 따로 센다
        consult(newUser(), false).andExpect(status().isBadGateway());

        // 24시간이 지난 기록은 세지 않는다
        jdbc.sql("UPDATE ai_calls SET created_at = now() - interval '25 hours' WHERE user_id = :u").param("u", userId).update();
        ai.enqueue(aiRejects());
        consult(userId, false).andExpect(status().isBadGateway());
    }

    @Test
    void chatQuestionsStopAtLimitBeforeOpeningStream() throws Exception {
        String created = mvc.perform(post("/api/conversations").with(login(userId)).with(csrf()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long conversationId = json.readTree(created).path("id").asLong();
        ai.enqueue(aiRejects()).enqueue(aiRejects());

        for (int i = 0; i < 2; i++) {
            MvcResult result = mvc.perform(ask(conversationId)).andExpect(request().asyncStarted()).andReturn();
            awaitEvent(result, "event:error");
        }

        mvc.perform(ask(conversationId))
                .andExpect(status().isTooManyRequests());
        long questions = jdbc.sql("SELECT count(*) FROM conversation_messages WHERE conversation_id = :c AND role = 'USER'")
                .param("c", conversationId).query(Long.class).single();
        assertThat(questions).isEqualTo(2); // 막힌 질문은 저장하지 않는다
        assertThat(ai.received).hasSize(2);

        // 상담 제한은 채팅과 따로 센다
        ai.enqueue(aiRejects());
        consult(userId, false).andExpect(status().isBadGateway());
    }

    private org.springframework.test.web.servlet.RequestBuilder ask(long conversationId) {
        return post("/api/conversations/{id}/messages", conversationId).with(login(userId)).with(csrf())
                .accept(MediaType.TEXT_EVENT_STREAM) // 프론트와 같은 Accept
                .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"면도 후 따가워요\"}");
    }

    private static void awaitEvent(MvcResult result, String marker) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
            if (body.contains(marker) && body.endsWith("\n\n")) return;
            Thread.sleep(20);
        }
        throw new AssertionError("이벤트가 오지 않았어요: " + marker);
    }
}
