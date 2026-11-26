package skin.opp.backend.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import skin.opp.backend.ai.AiException.Code;
import skin.opp.backend.ai.FakeAiServer.Reply;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class JederoClientTest {

    private final ObjectMapper json = JsonMapper.builder().build();
    private FakeAiServer server;
    private JederoClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = new FakeAiServer();
        client = client(Duration.ofSeconds(5));
    }

    private JederoClient client(Duration streamTimeout) {
        return new JederoClient(new AiProperties(
                new AiProperties.Endpoint(server.url(), "key-a"),
                new AiProperties.Endpoint(server.url() + "/", "key-b"),
                Duration.ofSeconds(2), Duration.ofSeconds(5), streamTimeout, 2, Duration.ofMillis(10)), json);
    }

    @AfterEach
    void tearDown() {
        client.close();
        server.close();
    }

    private ObjectNode message(String text) {
        return json.createObjectNode().put("user_message", text);
    }

    @Test
    void chatSendsKeyAndReturnsOnlyChatResponse() {
        server.enqueue(Reply.json(200, """
                {"latency_ms":1,"chat_response":{"reply":"답변","action_tips":["팁"]},
                 "internal_audit":{"gate_violations":[],"evidence_used":{"X":"내부 원문"}}}"""));

        JsonNode response = client.chat(message("질문"));

        assertThat(response.path("reply").asString()).isEqualTo("답변");
        assertThat(response.has("internal_audit")).isFalse();
        assertThat(server.received).singleElement().satisfies(r -> {
            assertThat(r.path()).isEqualTo("/chat");
            assertThat(r.apiKey()).isEqualTo("key-b");
            assertThat(r.body()).contains("\"user_message\":\"질문\"");
        });
    }

    @Test
    void retriesGatewayErrorsThenSucceeds() {
        server.enqueue(Reply.json(503, "{\"detail\":\"busy\"}"))
                .enqueue(Reply.json(504, "gateway timeout"))
                .enqueue(Reply.json(200, "{\"routine_plan\":{\"mode\":\"general_routine\"},\"internal_audit\":{\"gate_violations\":[]}}"));

        JsonNode plan = client.routine(json.createObjectNode().put("target_days", 0));

        assertThat(plan.path("mode").asString()).isEqualTo("general_routine");
        assertThat(server.received).hasSize(3);
    }

    @Test
    void givesUpAfterMaxRetries() {
        for (int i = 0; i < 3; i++) server.enqueue(Reply.json(502, "bad gateway"));

        assertThatThrownBy(() -> client.chat(message("질문")))
                .isInstanceOfSatisfying(AiException.class, e -> {
                    assertThat(e.code()).isEqualTo(Code.UNAVAILABLE);
                    assertThat(e.status()).isEqualTo(502);
                });
        assertThat(server.received).hasSize(3);
    }

    @Test
    void invalidInputIsNotRetriedAndKeepsMessage() {
        server.enqueue(Reply.json(400, """
                {"detail":"user_message (or message) must be a non-empty string",
                 "error":{"code":"INVALID_INPUT","message":"user_message (or message) must be a non-empty string"}}"""));

        assertThatThrownBy(() -> client.chat(message("")))
                .isInstanceOfSatisfying(AiException.class, e -> {
                    assertThat(e.code()).isEqualTo(Code.INVALID_INPUT);
                    assertThat(e.getMessage()).contains("non-empty");
                });
        assertThat(server.received).hasSize(1);
    }

    @Test
    void unauthorizedIsNotRetried() {
        server.enqueue(Reply.json(401, "{\"error\":{\"code\":\"UNAUTHORIZED\",\"message\":\"bad key\"}}"));

        assertThatThrownBy(() -> client.chat(message("질문")))
                .isInstanceOfSatisfying(AiException.class, e -> assertThat(e.code()).isEqualTo(Code.UNAUTHORIZED));
        assertThat(server.received).hasSize(1);
    }

    @Test
    void gateViolationsBlockModelBResults() {
        server.enqueue(Reply.json(200, "{\"chat_response\":{\"reply\":\"x\"},\"internal_audit\":{\"gate_violations\":[\"medical_claim\"]}}"));

        assertThatThrownBy(() -> client.chat(message("질문")))
                .isInstanceOfSatisfying(AiException.class, e -> assertThat(e.code()).isEqualTo(Code.GATE_BLOCKED));
    }

    @Test
    void analyzeKeepsReportButDropsInternalAudit() {
        server.enqueue(Reply.json(200, """
                {"cause_solution_report":{"cause_solution_pairs":[]},"skin3d_payload":{"regions":{}},
                 "ingredient_profile":{"baumann_inferred":"OSNT"},
                 "internal_audit":{"gate_violations":["pair_0"]}}"""));

        ObjectNode body = client.analyze(json.createObjectNode());

        assertThat(body.has("internal_audit")).isFalse();
        assertThat(body.path("ingredient_profile").path("baumann_inferred").asString()).isEqualTo("OSNT");
        assertThat(server.received.getFirst().apiKey()).isEqualTo("key-a");
    }

    @Test
    void streamCollectsDeltasAndFinal() {
        server.enqueue(Reply.sse(
                "event: meta\ndata: {\"cluster\":\"x\"}\n\n",
                "event: delta\ndata: {\"text\":\"면도 \",\"chars_emitted\":3}\n\n",
                "event: delta\ndata: {\"text\":\"직후\",\"chars_emitted\":5}\n\n",
                "event: final\ndata: {\"replace_reply\":true,\"chat_response\":{\"reply\":\"면도 직후에는\"},\"internal_audit\":{\"gate_violations\":[]}}\n\n"));
        List<String> deltas = new ArrayList<>();

        JederoClient.ChatOutcome outcome = client.chatStream(message("질문"), deltas::add);

        assertThat(deltas).containsExactly("면도 ", "직후");
        assertThat(outcome.blocked()).isFalse();
        assertThat(outcome.chatResponse().path("reply").asString()).isEqualTo("면도 직후에는");
        assertThat(server.received.getFirst().path()).isEqualTo("/chat/stream");
    }

    @Test
    void streamReportsGateViolations() {
        server.enqueue(Reply.sse(
                "event: final\ndata: {\"chat_response\":{\"reply\":\"x\"},\"internal_audit\":{\"gate_violations\":[{\"rule\":\"dx\"}]}}\n\n"));

        JederoClient.ChatOutcome outcome = client.chatStream(message("질문"), d -> {});

        assertThat(outcome.blocked()).isTrue();
        assertThat(outcome.violations()).containsExactly("{\"rule\":\"dx\"}");
    }

    @Test
    void streamWithoutFinalFails() {
        server.enqueue(Reply.sse("event: delta\ndata: {\"text\":\"a\"}\n\n"));

        assertThatThrownBy(() -> client.chatStream(message("질문"), d -> {}))
                .isInstanceOfSatisfying(AiException.class, e -> assertThat(e.code()).isEqualTo(Code.INTERNAL));
    }

    @Test
    void streamRetriesGatewayErrorBeforeBody() {
        server.enqueue(Reply.json(503, "busy"))
                .enqueue(Reply.sse("event: final\ndata: {\"chat_response\":{\"reply\":\"ok\"}}\n\n"));

        assertThat(client.chatStream(message("질문"), d -> {}).chatResponse().path("reply").asString()).isEqualTo("ok");
        assertThat(server.received).hasSize(2);
    }

    @Test
    void stalledStreamTimesOut() {
        client.close();
        client = client(Duration.ofMillis(300));
        server.enqueue(new Reply(200, "text/event-stream",
                List.of("event: delta\ndata: {\"text\":\"a\"}\n\n", "event: final\ndata: {\"chat_response\":{}}\n\n"), 3_000));

        long start = System.nanoTime();
        assertThatThrownBy(() -> client.chatStream(message("질문"), d -> {}))
                .isInstanceOfSatisfying(AiException.class, e -> assertThat(e.code()).isEqualTo(Code.TIMEOUT));
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
    }

    @Test
    void missingConfigurationFailsFast() {
        JederoClient unconfigured = new JederoClient(new AiProperties(null, new AiProperties.Endpoint("", ""),
                Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1), 0, Duration.ZERO), json);

        assertThatThrownBy(() -> unconfigured.chat(message("질문")))
                .isInstanceOfSatisfying(AiException.class, e -> assertThat(e.code()).isEqualTo(Code.NOT_CONFIGURED));
        unconfigured.close();
    }
}
