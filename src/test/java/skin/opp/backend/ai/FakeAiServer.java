package skin.opp.backend.ai;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** 테스트용 가짜 AI 서버. 경로별로 응답을 순서대로 쌓아 두고, 받은 요청을 기록한다. */
public class FakeAiServer implements AutoCloseable {

    public record Received(String path, String apiKey, String body) {}

    /** status, contentType, 본문 조각들(조각 사이에 delayMillis만큼 쉼) */
    public record Reply(int status, String contentType, List<String> chunks, long delayMillis) {
        public static Reply json(int status, String body) {
            return new Reply(status, "application/json", List.of(body), 0);
        }

        public static Reply sse(String... events) {
            return new Reply(200, "text/event-stream; charset=utf-8", List.of(events), 0);
        }
    }

    private final HttpServer server;
    private final Deque<Reply> replies = new ArrayDeque<>();
    public final List<Received> received = new CopyOnWriteArrayList<>();

    public FakeAiServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public synchronized FakeAiServer enqueue(Reply reply) {
        replies.addLast(reply);
        return this;
    }

    public synchronized void reset() {
        replies.clear();
        received.clear();
    }

    private void handle(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        received.add(new Received(ex.getRequestURI().getPath(), ex.getRequestHeaders().getFirst("X-API-Key"), body));
        Reply reply;
        synchronized (this) {
            reply = replies.pollFirst();
        }
        if (reply == null) reply = Reply.json(500, "{\"detail\":\"no reply queued\"}");
        ex.getResponseHeaders().add("Content-Type", reply.contentType());
        ex.sendResponseHeaders(reply.status(), 0);
        try (OutputStream out = ex.getResponseBody()) {
            for (String chunk : reply.chunks()) {
                out.write(chunk.getBytes(StandardCharsets.UTF_8));
                out.flush();
                if (reply.delayMillis() > 0) {
                    try {
                        Thread.sleep(reply.delayMillis());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        } catch (IOException ignored) {
            // 클라이언트가 먼저 끊음(시간 초과 테스트)
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
