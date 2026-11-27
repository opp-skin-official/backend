package skin.opp.backend.ai;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** AI에 함께 보낼 저장된 사용자 정보(피부 유형, 주의 성분, 생활 환경). 채팅과 상담이 같이 쓴다. */
@Component
public class ProfileContext {

    private static final Map<String, String> TRAINING_LABEL = Map.of(
            "ALMOST_DAILY", "야외훈련 거의 매일",
            "TWO_TO_THREE_PER_WEEK", "야외훈련 주 2~3회",
            "WEEKLY_OR_LESS", "야외훈련 주 1회 이하",
            "IRREGULAR", "야외훈련 일정 불규칙");
    private static final Map<String, String> WASH_LABEL = Map.of(
            "MORNING_EVENING", "아침·저녁 세안 가능",
            "EVENING_ONLY", "저녁에만 세안 가능",
            "VARIES", "세안 가능 시간이 날마다 다름",
            "NO_FIXED_TIME", "정해진 세안 시간 없음");

    public record Environment(String training, String washing) {}

    /** 채팅에 함께 보낼 최근 상담: analysis_report = Model A cause_solution_report, concern = 그 상담의 고민(Model A 값) */
    public record LatestReport(JsonNode analysisReport, ObjectNode concern) {}

    /** 이보다 오래된 리포트는 지금 피부와 다를 수 있어 채팅 맥락에 넣지 않는다. */
    public static final int REPORT_CONTEXT_DAYS = 30;

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public ProfileContext(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** 최신 설문 결과의 바우만 4글자(예: OSNT-L → OSNT). 설문 전이면 null. */
    public String baumann(long userId) {
        return jdbc.sql("SELECT survey_code FROM skin_type_assessments WHERE user_id = :u ORDER BY created_at DESC, id DESC LIMIT 1")
                .param("u", userId).query(String.class).optional()
                .filter(code -> code.length() >= 4)
                .map(code -> code.substring(0, 4))
                .orElse(null);
    }

    /** 사용자가 등록한 주의(알레르기·자극) 성분·제품 */
    public List<String> alerts(long userId) {
        return jdbc.sql("SELECT target FROM skin_alerts WHERE user_id = :u ORDER BY id")
                .param("u", userId).query(String.class).list();
    }

    /** 생활 환경 문구. 입력 전이면 null. */
    public Environment environment(long userId) {
        return jdbc.sql("SELECT wash_time, outdoor_training FROM service_environments WHERE user_id = :u")
                .param("u", userId)
                .query((rs, i) -> new Environment(
                        TRAINING_LABEL.getOrDefault(rs.getString("outdoor_training"), rs.getString("outdoor_training")),
                        WASH_LABEL.getOrDefault(rs.getString("wash_time"), rs.getString("wash_time"))))
                .optional().orElse(null);
    }

    /** 최근 {@link #REPORT_CONTEXT_DAYS}일 안의 가장 최근 상담 리포트. 없으면 null. */
    public LatestReport latestReport(long userId) {
        return jdbc.sql("""
                SELECT r.analysis::text AS analysis, s.answers::text AS answers
                FROM care_reports r JOIN consultation_summaries s ON s.conversation_id = r.conversation_id
                WHERE r.user_id = :u AND r.created_at > now() - make_interval(days => :days)
                ORDER BY r.created_at DESC, r.id DESC LIMIT 1
                """).param("u", userId).param("days", REPORT_CONTEXT_DAYS)
                .query((rs, i) -> {
                    JsonNode report = json.readTree(rs.getString("analysis")).path("cause_solution_report");
                    JsonNode a = json.readTree(rs.getString("answers"));
                    ObjectNode concern = json.createObjectNode();
                    if (a.path("kind").isString()) concern.put("kind", a.path("kind").asString().toLowerCase(Locale.ROOT));
                    if (a.path("regions").isArray()) concern.set("regions", a.path("regions"));
                    for (String f : List.of("onset", "duration", "change")) {
                        if (a.path(f).isString()) concern.put(f, a.path(f).asString().toLowerCase(Locale.ROOT));
                    }
                    if (a.path("severity").isNumber()) concern.put("severity", a.path("severity").asInt());
                    return new LatestReport(report.isObject() ? report : null, concern);
                })
                .optional().orElse(null);
    }

    /** Model B survey: traits.baumann, traits.allergies, military.training·washing (명세 1.2) */
    public ObjectNode modelBSurvey(long userId) {
        ObjectNode survey = json.createObjectNode();
        ObjectNode traits = json.createObjectNode();
        String baumann = baumann(userId);
        if (baumann != null) traits.put("baumann", baumann);
        List<String> alerts = alerts(userId);
        if (!alerts.isEmpty()) {
            ArrayNode allergies = traits.putArray("allergies");
            alerts.forEach(allergies::add);
        }
        if (!traits.isEmpty()) survey.set("traits", traits);
        Environment env = environment(userId);
        if (env != null) survey.putObject("military").put("training", env.training()).put("washing", env.washing());
        return survey;
    }
}
