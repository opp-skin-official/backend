package skin.opp.backend.skintype;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import java.sql.Array;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import skin.opp.backend.skintype.SurveyDtos.AssessmentView;
import skin.opp.backend.skintype.SurveyDtos.AxisView;
import skin.opp.backend.skintype.SurveyDtos.OptionView;
import skin.opp.backend.skintype.SurveyDtos.QuestionView;
import skin.opp.backend.skintype.SurveyDtos.SubmitRequest;
import skin.opp.backend.skintype.SurveyDtos.SurveyView;

@Service
public class SkinSurveyService {

    public static final int CURRENT_VERSION = 1;
    static final String SENSITIVE_CONSENT = "SENSITIVE_HEALTH";
    private static final int RETEST_AFTER_DAYS = 28; // 경계 축이 있으면 4주 루틴 뒤 재검사 권장
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private record Option(int position, String label, int score, String environmentTag) {}

    private record Question(int number, Axis axis, String text, boolean image, boolean sensitive, List<Option> options) {}

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public SkinSurveyService(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public SurveyView survey() {
        List<QuestionView> views = loadQuestions(CURRENT_VERSION).stream()
                .map(q -> new QuestionView(q.number(), q.axis(), q.text(), q.image(), q.sensitive(),
                        q.options().stream().map(o -> new OptionView(o.position(), o.label())).toList()))
                .toList();
        return new SurveyView(CURRENT_VERSION, views);
    }

    @Transactional
    public AssessmentView submit(long userId, SubmitRequest request) {
        if (request.version() != CURRENT_VERSION) {
            throw badRequest("설문 버전이 맞지 않습니다.");
        }
        List<Question> questions = loadQuestions(CURRENT_VERSION);
        boolean consented = request.sensitiveConsent() || hasActiveConsent(userId);

        List<SkinTypeScorer.Answer> scored = new ArrayList<>();
        List<String> environmentTags = new ArrayList<>();
        for (Question q : questions) {
            Integer position = request.answers().get(q.number());
            if (q.sensitive() && !consented) {
                if (position != null) {
                    throw badRequest(q.number() + "번은 민감정보 동의 후에만 답할 수 있습니다.");
                }
                continue; // 동의하지 않으면 이 문항을 빼고 채점한다(n이 줄어듦).
            }
            if (position == null) {
                throw badRequest(q.number() + "번 문항에 답해 주세요.");
            }
            Option option = q.options().stream().filter(o -> o.position() == position).findFirst()
                    .orElseThrow(() -> badRequest(q.number() + "번 선택지가 올바르지 않습니다."));
            scored.add(new SkinTypeScorer.Answer(q.axis(), option.score()));
            if (option.environmentTag() != null) {
                environmentTags.add(option.environmentTag());
            }
        }
        for (Integer number : request.answers().keySet()) {
            if (questions.stream().noneMatch(q -> q.number() == number)) {
                throw badRequest("없는 문항 번호입니다: " + number);
            }
        }

        if (request.sensitiveConsent() && !hasActiveConsent(userId)) {
            jdbc.sql("INSERT INTO consents (user_id, type) VALUES (:userId, :type)")
                    .param("userId", userId).param("type", SENSITIVE_CONSENT).update();
        }

        SkinTypeScorer.Result result = SkinTypeScorer.score(scored);
        LocalDate retest = result.hasBorderline() ? LocalDate.now(KST).plusDays(RETEST_AFTER_DAYS) : null;

        Map<String, Object> axisJson = new LinkedHashMap<>();
        result.axes().forEach((axis, r) -> axisJson.put(axis.name(), Map.of(
                "sum", r.sum(), "answered", r.answered(), "score", r.score(),
                "letter", String.valueOf(r.letter()), "borderline", r.borderline())));

        Long id = jdbc.sql("""
                INSERT INTO skin_type_assessments
                    (user_id, question_version, answers, axis_results, survey_code, result_type_code,
                     environment_tags, retest_recommended_at)
                VALUES (:userId, :version, CAST(:answers AS jsonb), CAST(:axes AS jsonb), :surveyCode, :typeCode,
                        :envTags, :retest)
                RETURNING id
                """)
                .param("userId", userId)
                .param("version", CURRENT_VERSION)
                .param("answers", write(request.answers()))
                .param("axes", write(axisJson))
                .param("surveyCode", result.surveyCode())
                .param("typeCode", result.typeCode())
                .param("envTags", environmentTags.toArray(String[]::new))
                .param("retest", retest)
                .query(Long.class).single();
        return find(userId, id).orElseThrow();
    }

    public Optional<AssessmentView> latest(long userId) {
        return jdbc.sql("SELECT id FROM skin_type_assessments WHERE user_id = :userId ORDER BY created_at DESC, id DESC LIMIT 1")
                .param("userId", userId).query(Long.class).optional()
                .flatMap(id -> find(userId, id));
    }

    private Optional<AssessmentView> find(long userId, long id) {
        return jdbc.sql("""
                SELECT a.id, a.result_type_code, t.title, t.care_style, a.axis_results::text AS axis_results,
                       a.environment_tags, a.retest_recommended_at, a.created_at
                FROM skin_type_assessments a JOIN skin_types t ON t.code = a.result_type_code
                WHERE a.id = :id AND a.user_id = :userId
                """)
                .param("id", id).param("userId", userId)
                .query((rs, i) -> new AssessmentView(
                        rs.getLong("id"),
                        rs.getString("result_type_code"),
                        rs.getString("title"),
                        skinTags(rs.getString("result_type_code")),
                        stringArray(rs.getArray("environment_tags")),
                        rs.getString("care_style"),
                        "MULTI_STEP".equals(rs.getString("care_style")) ? "여러 단계로 관리" : "간단하게 관리",
                        axisViews(rs.getString("axis_results")),
                        rs.getObject("retest_recommended_at", LocalDate.class),
                        rs.getObject("created_at", java.time.OffsetDateTime.class).toInstant()))
                .optional();
    }

    private List<String> skinTags(String typeCode) {
        return jdbc.sql("""
                SELECT g.label FROM skin_type_tags st JOIN skin_tags g ON g.code = st.tag_code
                WHERE st.skin_type_code = :code ORDER BY st.sort_order
                """).param("code", typeCode).query(String.class).list();
    }

    private List<AxisView> axisViews(String axisJson) {
        Map<String, Map<String, Object>> axes = read(axisJson);
        List<AxisView> views = new ArrayList<>();
        for (Axis axis : Axis.values()) {
            Map<String, Object> a = axes.get(axis.name());
            views.add(new AxisView(axis, ((String) a.get("letter")).charAt(0),
                    ((Number) a.get("score")).doubleValue(), (Boolean) a.get("borderline")));
        }
        return views;
    }

    private boolean hasActiveConsent(long userId) {
        return jdbc.sql("SELECT count(*) FROM consents WHERE user_id = :userId AND type = :type AND revoked_at IS NULL")
                .param("userId", userId).param("type", SENSITIVE_CONSENT).query(Long.class).single() > 0;
    }

    private List<Question> loadQuestions(int version) {
        Map<Integer, Question> byNumber = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT q.number, q.axis, q.text, q.is_image, q.sensitive,
                       o.position, o.label, o.score, o.environment_tag
                FROM skin_type_questions q JOIN skin_type_question_options o ON o.question_id = q.id
                WHERE q.version = :version ORDER BY q.number, o.position
                """).param("version", version)
                .query(rs -> {
                    int number = rs.getInt("number");
                    Question q = byNumber.computeIfAbsent(number, n -> {
                        try {
                            return new Question(n, Axis.valueOf(rs.getString("axis")), rs.getString("text"),
                                    rs.getBoolean("is_image"), rs.getBoolean("sensitive"), new ArrayList<>());
                        } catch (SQLException e) {
                            throw new IllegalStateException(e);
                        }
                    });
                    q.options().add(new Option(rs.getInt("position"), rs.getString("label"), rs.getInt("score"),
                            rs.getString("environment_tag")));
                });
        return List.copyOf(byNumber.values());
    }

    private static List<String> stringArray(Array array) throws SQLException {
        return array == null ? List.of() : Arrays.asList((String[]) array.getArray());
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Map<String, Map<String, Object>> read(String value) {
        try {
            return json.readValue(value, new TypeReference<>() {});
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
