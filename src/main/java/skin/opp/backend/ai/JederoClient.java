package skin.opp.backend.ai;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import skin.opp.backend.ai.AiException.Code;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 자체 AI 모델 호출 (명세: AI API v4.2 백엔드 연동 명세서).
 * <ul>
 *   <li>키는 X-API-Key 헤더로만 보낸다.</li>
 *   <li>502/503/504·연결 실패는 지수 백오프로 재시도, 400/401/422는 재시도하지 않는다.</li>
 *   <li>200이어도 internal_audit.gate_violations가 있으면 결과를 쓰지 않는다(Model B). Model A는 위반 항목이 이미 빠져서 온다.</li>
 *   <li>internal_audit(내부 근거 원문)은 밖으로 내보내지 않는다.</li>
 * </ul>
 */
@Component
@EnableConfigurationProperties(AiProperties.class)
public class JederoClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(JederoClient.class);

    public enum Model { A, B }

    /** 스트리밍 채팅 결과. violations가 비어 있지 않으면 chatResponse를 보여 주면 안 된다. */
    public record ChatOutcome(JsonNode chatResponse, List<String> violations) {
        public boolean blocked() {
            return !violations.isEmpty();
        }
    }

    private final AiProperties props;
    private final ObjectMapper json;
    private final HttpClient http;
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ai-stream-watchdog");
        t.setDaemon(true);
        return t;
    });

    public JederoClient(AiProperties props, ObjectMapper json) {
        this.props = props;
        this.json = json;
        this.http = HttpClient.newBuilder().connectTimeout(props.connectTimeout()).build();
    }

    /** Model A: 문진(+사진) → 원인·솔루션 리포트, 3D 데이터, 성분 프로필. internal_audit은 뺀다. */
    public ObjectNode analyze(ObjectNode payload) {
        ObjectNode body = postWithRetry(Model.A, "/analyze", payload);
        List<String> violations = violations(body);
        if (!violations.isEmpty()) {
            // Model A는 위반 pair를 빼고 돌려준다(명세 5.1). 결과는 쓰되 기록은 남긴다.
            log.warn("Model A gate_violations: {}", violations);
        }
        body.remove("internal_audit");
        return body;
    }

    /** Model B: 기간별(target_days 1~365)·일반(0) 루틴 → routine_plan */
    public JsonNode routine(ObjectNode payload) {
        ObjectNode body = postWithRetry(Model.B, "/routine", payload);
        failIfBlocked(body);
        return required(body, "routine_plan");
    }

    /** Model B: 피부 Q&A (스트리밍 없이) → chat_response */
    public JsonNode chat(ObjectNode payload) {
        ObjectNode body = postWithRetry(Model.B, "/chat", payload);
        failIfBlocked(body);
        return required(body, "chat_response");
    }

    /**
     * Model B: 피부 Q&A 스트리밍. 글자 조각마다 onDelta를 부르고, 마지막 final 이벤트의 chat_response를 돌려준다.
     * 조각은 띄어쓰기가 빠질 수 있어(replace_reply) 화면은 마지막에 chat_response.reply로 바꿔야 한다.
     */
    public ChatOutcome chatStream(ObjectNode payload, Consumer<String> onDelta) {
        HttpResponse<InputStream> response = sendStreamWithRetry(payload);
        AtomicBoolean timedOut = new AtomicBoolean();
        InputStream in = response.body();
        ScheduledFuture<?> guard = watchdog.schedule(() -> {
            timedOut.set(true);
            closeQuietly(in);
        }, props.streamTimeout().toMillis(), TimeUnit.MILLISECONDS);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String event = "message";
            StringBuilder data = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    ChatOutcome done = dispatch(event, data.toString(), onDelta);
                    if (done != null) return done;
                    event = "message";
                    data.setLength(0);
                } else if (line.startsWith("event:")) {
                    event = line.substring(6).strip();
                } else if (line.startsWith("data:")) {
                    if (!data.isEmpty()) data.append('\n');
                    data.append(line.substring(5).strip());
                }
            }
            if (!data.isEmpty()) {
                ChatOutcome done = dispatch(event, data.toString(), onDelta);
                if (done != null) return done;
            }
            throw new AiException(Code.INTERNAL, 200, "스트리밍 응답이 final 없이 끝났어요.");
        } catch (IOException e) {
            if (timedOut.get()) throw new AiException(Code.TIMEOUT, "스트리밍 응답 시간 초과", e);
            throw new AiException(Code.UNAVAILABLE, "스트리밍 응답을 읽지 못했어요.", e);
        } finally {
            guard.cancel(false);
        }
    }

    /** SSE 이벤트 하나 처리. final이면 결과를 돌려주고, 아니면 null. */
    private ChatOutcome dispatch(String event, String data, Consumer<String> onDelta) {
        if (data.isEmpty()) return null;
        switch (event) {
            case "delta" -> {
                String text = parse(data).path("text").asString("");
                if (!text.isEmpty()) onDelta.accept(text);
                return null;
            }
            case "final" -> {
                JsonNode body = parse(data);
                return new ChatOutcome(required(body, "chat_response"), violations(body));
            }
            case "error" -> throw errorFrom(200, data);
            default -> {
                return null; // meta 등은 쓰지 않는다
            }
        }
    }

    private ObjectNode postWithRetry(Model model, String path, ObjectNode payload) {
        for (int attempt = 0; ; attempt++) {
            try {
                HttpResponse<String> response = http.send(request(model, path, payload),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() == 200) {
                    JsonNode body = parse(response.body());
                    if (!(body instanceof ObjectNode object)) throw new AiException(Code.INTERNAL, 200, "응답이 JSON 객체가 아니에요.");
                    return object;
                }
                AiException error = errorFrom(response.statusCode(), response.body());
                if (!shouldRetry(error, attempt)) throw error;
            } catch (HttpTimeoutException e) {
                // 이미 오래 기다렸으므로 다시 부르지 않는다.
                throw new AiException(Code.TIMEOUT, model + path + " 응답 시간 초과", e);
            } catch (IOException e) {
                if (attempt >= props.maxRetries()) throw new AiException(Code.UNAVAILABLE, model + path + " 연결 실패", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AiException(Code.UNAVAILABLE, model + path + " 호출 중단", e);
            }
            backoff(attempt);
        }
    }

    private HttpResponse<InputStream> sendStreamWithRetry(ObjectNode payload) {
        for (int attempt = 0; ; attempt++) {
            try {
                HttpResponse<InputStream> response = http.send(request(Model.B, "/chat/stream", payload),
                        HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() == 200) return response;
                String body;
                try (InputStream in = response.body()) {
                    body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
                AiException error = errorFrom(response.statusCode(), body);
                if (!shouldRetry(error, attempt)) throw error;
            } catch (HttpTimeoutException e) {
                throw new AiException(Code.TIMEOUT, "B/chat/stream 응답 시간 초과", e);
            } catch (IOException e) {
                if (attempt >= props.maxRetries()) throw new AiException(Code.UNAVAILABLE, "B/chat/stream 연결 실패", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AiException(Code.UNAVAILABLE, "B/chat/stream 호출 중단", e);
            }
            backoff(attempt);
        }
    }

    private HttpRequest request(Model model, String path, ObjectNode payload) {
        AiProperties.Endpoint endpoint = model == Model.A ? props.modelA() : props.modelB();
        if (endpoint == null || !endpoint.configured()) {
            throw new AiException(Code.NOT_CONFIGURED, 0, "Model " + model + " URL·키가 설정되지 않았어요.");
        }
        String base = endpoint.url().endsWith("/") ? endpoint.url().substring(0, endpoint.url().length() - 1) : endpoint.url();
        return HttpRequest.newBuilder(URI.create(base + path))
                .timeout(props.readTimeout())
                .header("Content-Type", "application/json; charset=utf-8")
                .header("X-API-Key", endpoint.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload), StandardCharsets.UTF_8))
                .build();
    }

    private boolean shouldRetry(AiException error, int attempt) {
        return error.code() == Code.UNAVAILABLE && attempt < props.maxRetries();
    }

    private void backoff(int attempt) {
        long millis = props.retryBackoff().toMillis() << attempt;
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiException(Code.UNAVAILABLE, "재시도 대기 중 중단됨", e);
        }
    }

    /** 명세 5.1: {"detail": ..., "error": {"code", "message"}} */
    private AiException errorFrom(int status, String body) {
        String message = "Model 응답 오류 (HTTP " + status + ")";
        try {
            JsonNode node = json.readTree(body);
            String detail = node.path("error").path("message").asString(null);
            if (detail == null && node.path("detail").isString()) detail = node.path("detail").asString();
            if (detail != null) message = detail;
        } catch (JacksonException ignored) {
            // 본문이 JSON이 아니면 상태 코드만 쓴다.
        }
        Code code = switch (status) {
            case 400, 422 -> Code.INVALID_INPUT;
            case 401, 403 -> Code.UNAUTHORIZED;
            case 502, 503, 504 -> Code.UNAVAILABLE;
            default -> Code.INTERNAL;
        };
        return new AiException(code, status, message);
    }

    private void failIfBlocked(JsonNode body) {
        List<String> violations = violations(body);
        if (!violations.isEmpty()) {
            log.warn("Model B gate_violations: {}", violations);
            throw new AiException(Code.GATE_BLOCKED, 200, "안전 검사에 걸린 응답이에요.");
        }
    }

    private List<String> violations(JsonNode body) {
        List<String> out = new ArrayList<>();
        for (JsonNode v : body.path("internal_audit").path("gate_violations")) {
            out.add(v.isString() ? v.asString() : v.toString());
        }
        return out;
    }

    private JsonNode required(JsonNode body, String field) {
        JsonNode node = body.get(field);
        if (node == null || !node.isObject()) throw new AiException(Code.INTERNAL, 200, "응답에 " + field + "가 없어요.");
        return node;
    }

    private JsonNode parse(String text) {
        try {
            return json.readTree(text);
        } catch (JacksonException e) {
            throw new AiException(Code.INTERNAL, "응답 JSON을 읽지 못했어요.", e);
        }
    }

    private static void closeQuietly(InputStream in) {
        try {
            in.close();
        } catch (IOException ignored) {
            // 시간 초과로 끊는 중
        }
    }

    @Override
    public void close() {
        watchdog.shutdownNow();
        http.close();
    }
}
