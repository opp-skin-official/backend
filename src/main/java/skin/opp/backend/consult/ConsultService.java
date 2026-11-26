package skin.opp.backend.consult;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import skin.opp.backend.ai.AiException;
import skin.opp.backend.ai.AiQuota;
import skin.opp.backend.ai.JederoClient;
import skin.opp.backend.ai.ProfileContext;
import skin.opp.backend.consult.ConsultDtos.Activity;
import skin.opp.backend.consult.ConsultDtos.CareTime;
import skin.opp.backend.consult.ConsultDtos.ConcernKind;
import skin.opp.backend.consult.ConsultDtos.ConsultRequest;
import skin.opp.backend.consult.ConsultDtos.ConsultView;
import skin.opp.backend.consult.ConsultDtos.FollowUpRequest;
import skin.opp.backend.consult.ConsultDtos.IngredientView;
import skin.opp.backend.consult.ConsultDtos.PairView;
import skin.opp.backend.consult.ConsultDtos.PhaseView;
import skin.opp.backend.consult.ConsultDtos.Product;
import skin.opp.backend.consult.ConsultDtos.Region;
import skin.opp.backend.consult.ConsultDtos.ReportView;
import skin.opp.backend.consult.ConsultDtos.RoutineView;
import skin.opp.backend.consult.ConsultDtos.State;
import skin.opp.backend.routine.RoutineDtos.FocusGoal;
import skin.opp.backend.routine.RoutineDtos.GoalType;
import skin.opp.backend.routine.RoutineDtos.PlanRequest;
import skin.opp.backend.routine.RoutineDtos.PlanView;
import skin.opp.backend.routine.RoutineDtos.TimeOfDay;
import skin.opp.backend.routine.RoutineService;
import skin.opp.backend.routine.RoutineService.DefaultItem;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 문진 상담: 문진 확정(C-02) → Model A 리포트 → Model B 루틴 → 관리 계획 적용.
 * AI 호출(수십 초)은 트랜잭션 밖에서 하고, 결과가 나온 뒤 한 번에 저장한다.
 */
@Service
public class ConsultService {

    private static final Logger log = LoggerFactory.getLogger(ConsultService.class);

    /** 일반 루틴(기간 없음)을 관리 계획으로 시작할 때 기본 기간 */
    static final int GENERAL_PLAN_DAYS = 14;
    static final int MAX_ITEMS_PER_TIME = 5;
    // 루틴 카드에 "{이름}까지 D-n"으로 보인다.
    static final String PLAN_LABEL = "상담 루틴 마무리";

    static final Map<ConcernKind, String> KIND_LABEL = Map.of(
            ConcernKind.ACNE_COMEDONAL_INFLAMMATORY, "여드름·뾰루지",
            ConcernKind.POST_ACNE_SCAR_ERYTHEMA_PORES, "트러블 자국·모공",
            ConcernKind.CONTACT_IRRITANT_DERMATITIS, "제품·위장크림 후 자극",
            ConcernKind.DRYNESS_BARRIER_DAMAGE, "건조·당김",
            ConcernKind.FOLLICULITIS_SHAVING, "면도 후 붉어짐·따가움",
            ConcernKind.UV_PIGMENTATION_PHOTOAGING, "햇빛 후 잡티·기미",
            ConcernKind.ATOPIC_DERMATITIS, "가렵고 건조한 붉은 부위",
            ConcernKind.SEBORRHEIC_DERMATITIS, "번들거림과 각질",
            ConcernKind.SKIN_IMPROVEMENT_MAINTENANCE, "전반적인 피부 관리");
    static final Map<Region, String> REGION_LABEL = Map.of(
            Region.FOREHEAD, "이마", Region.CHEEK_LEFT, "왼쪽 볼", Region.CHEEK_RIGHT, "오른쪽 볼",
            Region.NOSE, "코", Region.CHIN, "턱", Region.EYE_AREA, "눈가");
    private static final Map<Activity, String> ACTIVITY_LABEL = Map.of(
            Activity.OUTDOOR_TRAINING, "야외훈련", Activity.MARCH, "행군", Activity.GAS_MASK, "방독면 착용",
            Activity.CAMO_CREAM, "위장크림 사용", Activity.HELMET_STRAP, "헬멧 턱끈 마찰", Activity.SHAVING, "면도");
    private static final Map<CareTime, String> CARE_TIME_LABEL = Map.of(
            CareTime.MORNING_EVENING, "아침·저녁 세안과 보습 가능",
            CareTime.EVENING_ONLY, "저녁에만 세안 가능",
            CareTime.MINIMAL, "관리 시간 2분 이내");
    private static final Map<Product, String> PRODUCT_LABEL = Map.of(
            Product.CLEANSER, "세안제", Product.MOISTURIZER, "보습제", Product.SUNSCREEN, "자외선 차단제",
            Product.ALL_IN_ONE, "올인원");
    /** 훈련 쪽(military.training)과 자극 쪽(military.irritants)으로 나눈다. */
    private static final List<Activity> IRRITANTS = List.of(Activity.GAS_MASK, Activity.CAMO_CREAM, Activity.HELMET_STRAP, Activity.SHAVING);

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final JederoClient ai;
    private final ProfileContext profile;
    private final RoutineService routines;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final AiQuota quota;

    public ConsultService(JdbcClient jdbc, ObjectMapper json, JederoClient ai, ProfileContext profile,
                          RoutineService routines, TransactionTemplate tx, Clock clock, AiQuota quota) {
        this.jdbc = jdbc;
        this.json = json;
        this.ai = ai;
        this.profile = profile;
        this.routines = routines;
        this.tx = tx;
        this.clock = clock;
        this.quota = quota;
    }

    /**
     * 확정한 문진으로 상담을 만든다. 위험 신호가 있으면 AI 없이 REFERRED로 끝낸다.
     * Model A가 실패하면 아무것도 저장하지 않고 502(다시 시도). Model B만 실패하면 루틴 없이 저장한다.
     */
    public ConsultView create(long userId, ConsultRequest request) {
        if (!request.redFlag() && request.careTime() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "관리 가능한 시간을 골라 주세요.");
        }
        if (request.redFlag()) {
            long id = tx.execute(s -> insertConversation(userId, request, State.REFERRED, null));
            return get(userId, id);
        }

        quota.acquire(userId, AiQuota.Kind.CONSULT);
        ObjectNode analysis = analyze(userId, request);
        JsonNode routine = routineOrNull(userId, request, analysis);
        return get(userId, saveReport(userId, request, analysis, routine, null));
    }

    /** 리포트가 있는 가장 최근 상담(재방문 때 "지난번과 달라진 점" 질문에 씀). 없으면 404. */
    public ConsultView latest(long userId) {
        return jdbc.sql("""
                SELECT c.id FROM conversations c JOIN care_reports r ON r.conversation_id = c.id
                WHERE c.user_id = :u AND c.kind = 'CONSULT' AND c.state = 'COMPLETED'
                ORDER BY c.created_at DESC, c.id DESC LIMIT 1
                """).param("u", userId).query(Long.class).optional()
                .map(id -> get(userId, id))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "이전 상담이 없어요."));
    }

    /**
     * 후속 상담(시간이나 물품이 달라졌어요): 이전 상담의 고민·증상과 분석(Model A 결과)은 그대로 쓰고,
     * 지금 가능한 관리 시간·제품·목표 기간으로 루틴(Model B)만 다시 만들어 새 상담으로 저장한다.
     * 루틴 생성이 실패하면 아무것도 저장하지 않고 502.
     */
    public ConsultView followUp(long userId, long previousId, FollowUpRequest request) {
        ConsultView previous = get(userId, previousId);
        if (previous.report() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "리포트가 있는 상담만 이어서 할 수 있어요.");
        }
        ConsultRequest old = previous.answers();
        ConsultRequest answers = new ConsultRequest(old.kind(), old.regions(), old.onset(), old.duration(),
                old.severity(), old.change(), false, old.activities(), request.careTime(), request.products(),
                request.targetDays());
        JsonNode analysis = analysisOf(previousId);
        quota.acquire(userId, AiQuota.Kind.CONSULT);
        JsonNode routine;
        try {
            routine = ai.routine(routinePayload(userId, answers, analysis));
        } catch (AiException e) {
            log.warn("consultation {} 후속 루틴 생성 실패: {} {}", previousId, e.code(), e.getMessage());
            throw unavailable(e);
        }
        return get(userId, saveReport(userId, answers, analysis, routine, previousId));
    }

    private long saveReport(long userId, ConsultRequest request, JsonNode analysis, JsonNode routine, Long followsId) {
        return tx.execute(s -> {
            long conversationId = insertConversation(userId, request, State.COMPLETED, followsId);
            jdbc.sql("""
                    INSERT INTO care_reports (conversation_id, user_id, analysis, routine_plan)
                    VALUES (:c, :u, CAST(:analysis AS jsonb), CAST(:routine AS jsonb))
                    """).param("c", conversationId).param("u", userId)
                    .param("analysis", json.writeValueAsString(analysis))
                    .param("routine", routine == null ? null : json.writeValueAsString(routine))
                    .update();
            return conversationId;
        });
    }

    private JsonNode analysisOf(long conversationId) {
        return jdbc.sql("SELECT analysis::text FROM care_reports WHERE conversation_id = :c")
                .param("c", conversationId).query(String.class).optional().map(json::readTree).orElseThrow();
    }

    public ConsultView get(long userId, long id) {
        return jdbc.sql("""
                SELECT c.id, c.state, c.title, c.created_at, c.follows_id, s.answers::text AS answers,
                       r.analysis::text AS analysis, r.routine_plan::text AS routine_plan, r.applied_plan_id
                FROM conversations c
                JOIN consultation_summaries s ON s.conversation_id = c.id
                LEFT JOIN care_reports r ON r.conversation_id = c.id
                WHERE c.id = :id AND c.user_id = :u AND c.kind = 'CONSULT'
                """).param("id", id).param("u", userId)
                .query((rs, i) -> new ConsultView(
                        rs.getLong("id"), State.valueOf(rs.getString("state")), rs.getString("title"),
                        json.readValue(rs.getString("answers"), ConsultRequest.class),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getString("analysis") == null ? null : report(
                                json.readTree(rs.getString("analysis")),
                                rs.getString("routine_plan") == null ? null : json.readTree(rs.getString("routine_plan")),
                                (Long) rs.getObject("applied_plan_id", Long.class)),
                        (Long) rs.getObject("follows_id", Long.class)))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "상담을 찾을 수 없어요."));
    }

    /** 루틴을 다시 만든다(처음에 실패했거나 새로 받고 싶을 때). 실패하면 502. */
    public ConsultView regenerateRoutine(long userId, long id) {
        ConsultView view = get(userId, id);
        if (view.report() == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "리포트가 없는 상담이에요.");
        JsonNode analysis = analysisOf(id);
        quota.acquire(userId, AiQuota.Kind.CONSULT);
        JsonNode routine;
        try {
            routine = ai.routine(routinePayload(userId, view.answers(), analysis));
        } catch (AiException e) {
            log.warn("consultation {} 루틴 다시 만들기 실패: {} {}", id, e.code(), e.getMessage());
            throw unavailable(e);
        }
        jdbc.sql("UPDATE care_reports SET routine_plan = CAST(:r AS jsonb), updated_at = now() WHERE conversation_id = :c")
                .param("r", json.writeValueAsString(routine)).param("c", id).update();
        return get(userId, id);
    }

    /** 리포트의 루틴(1단계 아침·저녁)으로 관리 계획을 시작한다. 진행 중인 계획은 보관된다. */
    @Transactional
    public PlanView applyRoutine(long userId, long id) {
        ConsultView view = get(userId, id);
        RoutineView routine = view.report() == null ? null : view.report().routine();
        if (routine == null || routine.phases().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "적용할 루틴이 없어요.");
        }
        PhaseView first = routine.phases().getFirst();
        List<DefaultItem> items = new ArrayList<>();
        first.morning().stream().limit(MAX_ITEMS_PER_TIME).forEach(t -> items.add(new DefaultItem(TimeOfDay.MORNING, t, first.label())));
        first.evening().stream().limit(MAX_ITEMS_PER_TIME).forEach(t -> items.add(new DefaultItem(TimeOfDay.EVENING, t, first.label())));
        int days = routine.targetDays() > 0 ? routine.targetDays() : GENERAL_PLAN_DAYS;
        LocalDate target = LocalDate.now(clock).plusDays(days - 1L);
        PlanView plan = routines.createPlan(userId,
                new PlanRequest(GoalType.CUSTOM, PLAN_LABEL, target, FocusGoal.SKIN_CHANGE), items);
        jdbc.sql("UPDATE care_reports SET applied_plan_id = :p, updated_at = now() WHERE conversation_id = :c")
                .param("p", plan.id()).param("c", id).update();
        return plan;
    }

    private long insertConversation(long userId, ConsultRequest request, State state, Long followsId) {
        long id = jdbc.sql("""
                INSERT INTO conversations (user_id, kind, use_saved_profile, title, state, follows_id)
                VALUES (:u, 'CONSULT', TRUE, :title, :state, :follows) RETURNING id
                """).param("u", userId).param("title", title(request)).param("state", state.name())
                .param("follows", followsId)
                .query(Long.class).single();
        jdbc.sql("""
                INSERT INTO consultation_summaries (conversation_id, answers, red_flag)
                VALUES (:c, CAST(:answers AS jsonb), :red)
                """).param("c", id).param("answers", json.writeValueAsString(request)).param("red", request.redFlag())
                .update();
        return id;
    }

    /** 예: "턱 · 면도 후 붉어짐·따가움" */
    static String title(ConsultRequest request) {
        String regions = request.regions().stream().sorted().map(REGION_LABEL::get).collect(Collectors.joining("·"));
        String title = regions + " · " + KIND_LABEL.get(request.kind());
        return title.length() > 100 ? title.substring(0, 99) + "…" : title;
    }

    private ObjectNode analyze(long userId, ConsultRequest request) {
        ObjectNode payload = json.createObjectNode();
        payload.putArray("concerns").add(concern(request));
        List<String> alerts = profile.alerts(userId);
        if (!alerts.isEmpty()) payload.putObject("traits").put("allergies_conditions_medication", String.join(", ", alerts));
        payload.set("military", military(userId, request));
        try {
            return ai.analyze(payload);
        } catch (AiException e) {
            log.warn("consultation 분석 실패 (user {}): {} {}", userId, e.code(), e.getMessage());
            throw unavailable(e);
        }
    }

    private JsonNode routineOrNull(long userId, ConsultRequest request, JsonNode analysis) {
        try {
            return ai.routine(routinePayload(userId, request, analysis));
        } catch (AiException e) {
            log.warn("consultation 루틴 생성 실패 (user {}): {} {}", userId, e.code(), e.getMessage());
            return null;
        }
    }

    private ObjectNode routinePayload(long userId, ConsultRequest request, JsonNode analysis) {
        ObjectNode payload = json.createObjectNode();
        payload.put("target_days", request.targetDays());
        if (analysis.path("cause_solution_report").isObject()) {
            payload.set("analysis_report", analysis.get("cause_solution_report"));
        }
        ObjectNode survey = payload.putObject("survey");
        survey.putArray("concerns").add(concern(request));
        ObjectNode traits = json.createObjectNode();
        String baumann = profile.baumann(userId);
        if (baumann != null) traits.put("baumann", baumann);
        List<String> alerts = profile.alerts(userId);
        if (!alerts.isEmpty()) {
            ArrayNode allergies = traits.putArray("allergies");
            alerts.forEach(allergies::add);
        }
        if (!traits.isEmpty()) survey.set("traits", traits);
        survey.set("military", military(userId, request));
        return payload;
    }

    private ObjectNode concern(ConsultRequest request) {
        ObjectNode concern = json.createObjectNode();
        concern.put("kind", request.kind().name().toLowerCase());
        ArrayNode regions = concern.putArray("regions");
        request.regions().stream().sorted().forEach(r -> regions.add(r.name()));
        concern.put("onset", request.onset().name().toLowerCase());
        concern.put("duration", request.duration().name().toLowerCase());
        concern.put("severity", request.severity());
        concern.put("change", request.change().name().toLowerCase());
        return concern;
    }

    /** 명세의 military 9칸 중 문진·생활 환경으로 채울 수 있는 것만 */
    private ObjectNode military(long userId, ConsultRequest request) {
        ObjectNode military = json.createObjectNode();
        ProfileContext.Environment env = profile.environment(userId);
        List<String> training = request.activitiesOrEmpty().stream().filter(a -> !IRRITANTS.contains(a))
                .map(ACTIVITY_LABEL::get).collect(Collectors.toCollection(ArrayList::new));
        if (env != null) training.add(env.training());
        if (!training.isEmpty()) military.put("training", String.join(", ", training));
        List<String> irritants = request.activitiesOrEmpty().stream().filter(IRRITANTS::contains)
                .map(ACTIVITY_LABEL::get).toList();
        if (!irritants.isEmpty()) military.put("irritants", String.join(", ", irritants));
        if (env != null) military.put("washing", env.washing());
        if (request.careTime() != null) military.put("care_time", CARE_TIME_LABEL.get(request.careTime()));
        List<String> products = request.productsOrEmpty().stream().map(PRODUCT_LABEL::get).toList();
        military.put("hygiene_product_access", products.isEmpty() ? "사용 가능한 제품 없음" : String.join(", ", products));
        return military;
    }

    private ReportView report(JsonNode analysis, JsonNode routine, Long appliedPlanId) {
        JsonNode report = analysis.path("cause_solution_report");
        List<PairView> pairs = new ArrayList<>();
        for (JsonNode p : report.path("cause_solution_pairs")) {
            pairs.add(new PairView(strings(p.path("regions")), text(p, "state_observation"), text(p, "cause_summary"),
                    text(p, "solution_action"), text(p, "confidence")));
        }
        JsonNode ingredients = analysis.path("ingredient_profile");
        return new ReportView(pairs, report.path("refer_to_clinician").asBoolean(false),
                text(report, "notice"), text(report, "referral_notice"),
                ingredients(ingredients.path("recommended_ingredients")),
                ingredients(ingredients.path("caution_ingredients")),
                routine == null ? null : routineView(routine), appliedPlanId);
    }

    private RoutineView routineView(JsonNode r) {
        List<PhaseView> phases = new ArrayList<>();
        for (JsonNode p : r.path("phases")) {
            phases.add(new PhaseView(text(p, "phase_label"), text(p, "day_range"), text(p, "focus"),
                    strings(p.path("morning")), strings(p.path("evening")), strings(p.path("post_activity")),
                    text(p, "checkpoint")));
        }
        return new RoutineView(text(r, "mode"), r.path("target_days").asInt(0), text(r, "summary"), phases,
                r.path("refer_to_clinician").asBoolean(false), text(r, "referral_notice"));
    }

    private static List<IngredientView> ingredients(JsonNode array) {
        List<IngredientView> out = new ArrayList<>();
        for (JsonNode n : array) out.add(new IngredientView(text(n, "key"), text(n, "label_ko"), text(n, "inci")));
        return out;
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        for (JsonNode n : array) if (n.isString()) out.add(n.asString());
        return out;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isString() ? v.asString() : null;
    }

    private static ResponseStatusException unavailable(AiException e) {
        String message = switch (e.code()) {
            case TIMEOUT, UNAVAILABLE -> "AI 분석이 늦어지고 있어요. 잠시 후 다시 시도해 주세요.";
            default -> "AI 분석을 하지 못했어요. 잠시 후 다시 시도해 주세요.";
        };
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, message, e);
    }
}
