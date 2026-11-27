package skin.opp.backend.chat;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import skin.opp.backend.ai.AiException;
import skin.opp.backend.ai.JederoClient;
import skin.opp.backend.ai.ProfileContext;
import skin.opp.backend.chat.ChatDtos.ConversationSummary;
import skin.opp.backend.chat.ChatDtos.ConversationView;
import skin.opp.backend.chat.ChatDtos.Kind;
import skin.opp.backend.chat.ChatDtos.MessageView;
import skin.opp.backend.chat.ChatDtos.Role;
import skin.opp.backend.chat.ChatDtos.Status;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** 피부 Q&A 대화 (Model B /chat/stream). 대화·메시지 저장과 AI에 보낼 맥락(피부 정보, 최근 대화)을 맡는다. */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    /** Model B는 최근 6개만 반영한다(명세 1.2). */
    static final int HISTORY_SIZE = 6;
    static final int TITLE_LENGTH = 40;
    static final String BLOCKED_REPLY =
            "이 질문에는 안전하게 답하기 어려워요. 증상이 심하거나 오래가면 의무대나 가까운 의료기관에 상담해 주세요.";


    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final JederoClient ai;
    private final ProfileContext profile;

    public ChatService(JdbcClient jdbc, ObjectMapper json, JederoClient ai, ProfileContext profile) {
        this.jdbc = jdbc;
        this.json = json;
        this.ai = ai;
        this.profile = profile;
    }

    @Transactional
    public ConversationView create(long userId, boolean useSavedProfile) {
        long id = jdbc.sql("""
                INSERT INTO conversations (user_id, kind, use_saved_profile) VALUES (:u, :kind, :profile) RETURNING id
                """).param("u", userId).param("kind", Kind.QNA.name()).param("profile", useSavedProfile)
                .query(Long.class).single();
        return get(userId, id);
    }

    public List<ConversationSummary> list(long userId, int limit) {
        return jdbc.sql("""
                SELECT id, kind, title, created_at, updated_at FROM conversations
                WHERE user_id = :u ORDER BY updated_at DESC, id DESC LIMIT :limit
                """).param("u", userId).param("limit", Math.clamp(limit, 1, 50))
                .query((rs, i) -> new ConversationSummary(rs.getLong("id"), Kind.valueOf(rs.getString("kind")),
                        rs.getString("title"), rs.getObject("created_at", OffsetDateTime.class),
                        rs.getObject("updated_at", OffsetDateTime.class)))
                .list();
    }

    public ConversationView get(long userId, long conversationId) {
        return jdbc.sql("""
                SELECT id, kind, title, use_saved_profile, created_at FROM conversations WHERE id = :id AND user_id = :u
                """).param("id", conversationId).param("u", userId)
                .query((rs, i) -> new ConversationView(rs.getLong("id"), Kind.valueOf(rs.getString("kind")),
                        rs.getString("title"), rs.getBoolean("use_saved_profile"),
                        rs.getObject("created_at", OffsetDateTime.class), messages(conversationId)))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "대화를 찾을 수 없어요."));
    }

    /** 내 대화인지 확인하고 맥락 사용 여부를 돌려준다. 남의 대화면 404. */
    public boolean requireOwned(long userId, long conversationId) {
        return jdbc.sql("SELECT use_saved_profile FROM conversations WHERE id = :id AND user_id = :u AND kind = 'QNA'")
                .param("id", conversationId).param("u", userId).query(Boolean.class).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "대화를 찾을 수 없어요."));
    }

    /** 사용자 질문을 저장한다. 첫 질문이면 대화 제목으로 쓴다. */
    @Transactional
    public MessageView saveUserMessage(long conversationId, String rawContent) {
        String content = rawContent.strip();
        if (content.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "질문을 입력해 주세요.");
        String title = content.length() > TITLE_LENGTH ? content.substring(0, TITLE_LENGTH) + "…" : content;
        jdbc.sql("""
                UPDATE conversations SET title = COALESCE(title, :title), updated_at = now() WHERE id = :id
                """).param("title", title.replaceAll("\\s+", " ")).param("id", conversationId).update();
        long id = jdbc.sql("""
                INSERT INTO conversation_messages (conversation_id, role, content) VALUES (:c, 'USER', :content) RETURNING id
                """).param("c", conversationId).param("content", content).query(Long.class).single();
        return message(id);
    }

    /**
     * AI 답변을 받아 저장한다(오래 걸리므로 트랜잭션 밖에서 부른다). 글자 조각은 onDelta로 넘긴다.
     * 안전 검사에 걸리면 안내 문구로 바꿔 BLOCKED로 저장한다. AI 호출이 실패하면 AiException을 그대로 던진다.
     */
    public MessageView answer(long userId, long conversationId, long questionId, boolean useSavedProfile,
                              Consumer<String> onDelta) {
        ObjectNode payload = json.createObjectNode();
        payload.put("user_message", message(questionId).content());
        if (useSavedProfile) {
            ObjectNode survey = profile.modelBSurvey(userId);
            // 최근 상담이 있으면 그 결과와 고민을 함께 보내, 상담을 이어서 묻는 질문에 맞게 답하게 한다(명세 1.2).
            ProfileContext.LatestReport report = profile.latestReport(userId);
            if (report != null) {
                if (report.analysisReport() != null) payload.set("analysis_report", report.analysisReport());
                if (!report.concern().isEmpty()) survey.putArray("concerns").add(report.concern());
            }
            if (!survey.isEmpty()) payload.set("survey", survey);
        }
        ArrayNode history = history(conversationId, questionId);
        if (!history.isEmpty()) payload.set("history", history);

        JederoClient.ChatOutcome outcome = ai.chatStream(payload, onDelta);
        if (outcome.blocked()) {
            log.warn("conversation {} 답변 차단: {}", conversationId, outcome.violations());
            ObjectNode blocked = json.createObjectNode().put("refer_to_clinician", true);
            return saveAssistant(conversationId, Status.BLOCKED, BLOCKED_REPLY, blocked);
        }
        JsonNode response = outcome.chatResponse();
        String reply = response.path("reply").asString("").strip();
        if (reply.isEmpty() && outcome.markdown() == null) throw new AiException(AiException.Code.INTERNAL, 200, "빈 답변");
        ObjectNode extra = json.createObjectNode();
        // v6.3: 저장은 final.markdown 기준(실천 팁 포함 전체 답변). 없으면 이전처럼 reply
        extra.put("format", outcome.markdown() != null ? "markdown" : "text");
        for (String field : List.of("action_tips", "follow_up_questions", "evidence_ids")) {
            if (response.path(field).isArray()) extra.set(field, response.get(field));
        }
        extra.put("refer_to_clinician", response.path("refer_to_clinician").asBoolean(false));
        for (String field : List.of("notice", "referral_notice")) {
            if (response.path(field).isString()) extra.put(field, response.get(field).asString());
        }
        return saveAssistant(conversationId, Status.DONE, outcome.markdown() != null ? outcome.markdown() : reply, extra);
    }

    private MessageView saveAssistant(long conversationId, Status status, String content, ObjectNode payload) {
        long id = jdbc.sql("""
                INSERT INTO conversation_messages (conversation_id, role, status, content, payload)
                VALUES (:c, 'ASSISTANT', :status, :content, CAST(:payload AS jsonb)) RETURNING id
                """).param("c", conversationId).param("status", status.name()).param("content", content)
                .param("payload", json.writeValueAsString(payload)).query(Long.class).single();
        jdbc.sql("UPDATE conversations SET updated_at = now() WHERE id = :id").param("id", conversationId).update();
        return message(id);
    }

    /** 이번 질문 앞의 최근 대화(안전 검사로 바뀐 답변은 빼고) */
    private ArrayNode history(long conversationId, long beforeMessageId) {
        List<MessageView> recent = new ArrayList<>(jdbc.sql("""
                SELECT * FROM conversation_messages
                WHERE conversation_id = :c AND id < :before AND status = 'DONE'
                ORDER BY id DESC LIMIT :n
                """).param("c", conversationId).param("before", beforeMessageId).param("n", HISTORY_SIZE)
                .query(this::mapMessage).list());
        ArrayNode history = json.createArrayNode();
        for (int i = recent.size() - 1; i >= 0; i--) {
            MessageView m = recent.get(i);
            history.addObject().put("role", m.role() == Role.USER ? "user" : "assistant").put("content", m.content());
        }
        return history;
    }

    private List<MessageView> messages(long conversationId) {
        return jdbc.sql("SELECT * FROM conversation_messages WHERE conversation_id = :c ORDER BY id")
                .param("c", conversationId).query(this::mapMessage).list();
    }

    private MessageView message(long id) {
        return jdbc.sql("SELECT * FROM conversation_messages WHERE id = :id").param("id", id)
                .query(this::mapMessage).single();
    }

    private MessageView mapMessage(ResultSet rs, int row) throws SQLException {
        JsonNode payload = Optional.ofNullable(rs.getString("payload")).map(json::readTree).orElse(json.createObjectNode());
        return new MessageView(
                rs.getLong("id"), Role.valueOf(rs.getString("role")), Status.valueOf(rs.getString("status")),
                rs.getString("content"), payload.path("format").asString("text"),
                strings(payload.path("action_tips")), strings(payload.path("follow_up_questions")),
                payload.path("refer_to_clinician").asBoolean(false),
                payload.path("notice").asString(null), payload.path("referral_notice").asString(null),
                rs.getObject("created_at", OffsetDateTime.class));
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        for (JsonNode n : array) if (n.isString()) out.add(n.asString());
        return out;
    }
}
