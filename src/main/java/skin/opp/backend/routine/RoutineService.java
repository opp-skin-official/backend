package skin.opp.backend.routine;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import skin.opp.backend.routine.RoutineDtos.DayView;
import skin.opp.backend.routine.RoutineDtos.FocusGoal;
import skin.opp.backend.routine.RoutineDtos.GoalType;
import skin.opp.backend.routine.RoutineDtos.ItemView;
import skin.opp.backend.routine.RoutineDtos.PlanRequest;
import skin.opp.backend.routine.RoutineDtos.PlanView;
import skin.opp.backend.routine.RoutineDtos.TimeOfDay;
import skin.opp.backend.routine.RoutineDtos.TodayView;
import skin.opp.backend.routine.RoutineDtos.WeekView;

@Service
public class RoutineService {

    /** 목표일은 오늘부터 최대 1년 뒤까지 */
    static final int MAX_PLAN_DAYS = 365;

    /** 루틴 항목 하나. 제목·부제는 routine_items 컬럼 길이(100자)에 맞춰 넣는다. */
    public record DefaultItem(TimeOfDay time, String title, String subtitle) {}

    /** 기본 루틴 (Figma "루틴": 아침·저녁 각각 순하게 세안하기, 보습하기). 직접 계획을 만들 때 쓴다. */

    static final List<DefaultItem> DEFAULT_ITEMS = List.of(
            new DefaultItem(TimeOfDay.MORNING, "순하게 세안하기", "사용 중인 세안 제품"),
            new DefaultItem(TimeOfDay.MORNING, "보습하기", "사용 중인 보습 제품"),
            new DefaultItem(TimeOfDay.EVENING, "순하게 세안하기", "사용 중인 세안 제품"),
            new DefaultItem(TimeOfDay.EVENING, "보습하기", "사용 중인 보습 제품"));

    private record Plan(long id, GoalType goalType, String goalLabel, FocusGoal focusGoal,
                        LocalDate startDate, LocalDate targetDate) {}

    private record Item(long id, TimeOfDay time, String title, String subtitle) {}

    private final JdbcClient jdbc;
    private final Clock clock;

    public RoutineService(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    private static String cut(String text) {
        String t = text.strip();
        return t.length() > 100 ? t.substring(0, 99) + "…" : t;
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }

    /** 새 관리 계획을 기본 루틴으로 만든다. 진행 중인 계획이 있으면 보관(ARCHIVED)하고 새로 시작한다. */
    @Transactional
    public PlanView createPlan(long userId, PlanRequest request) {
        return createPlan(userId, request, DEFAULT_ITEMS);
    }

    /** 새 관리 계획을 주어진 루틴 항목으로 만든다(상담 리포트의 루틴 적용). 항목이 없으면 기본 루틴. */
    @Transactional
    public PlanView createPlan(long userId, PlanRequest request, List<DefaultItem> routine) {
        List<DefaultItem> items = routine.isEmpty() ? DEFAULT_ITEMS : routine;
        LocalDate today = today();
        if (request.targetDate().isBefore(today)) {
            throw badRequest("목표 날짜는 오늘이거나 그 이후여야 해요.");
        }
        if (request.targetDate().isAfter(today.plusDays(MAX_PLAN_DAYS))) {
            throw badRequest("목표 날짜는 1년 이내로 정해 주세요.");
        }
        String label = request.goalLabel() == null ? null : request.goalLabel().strip();
        if (request.goalType() == GoalType.CUSTOM && (label == null || label.isEmpty())) {
            throw badRequest("직접 입력한 목표 이름을 적어 주세요.");
        }
        if (request.goalType() != GoalType.CUSTOM) {
            label = null;
        }

        jdbc.sql("UPDATE care_plans SET status = 'ARCHIVED' WHERE user_id = :u AND status = 'ACTIVE'")
                .param("u", userId).update();
        long planId = jdbc.sql("""
                INSERT INTO care_plans (user_id, goal_type, goal_label, focus_goal, start_date, target_date, status)
                VALUES (:u, :type, :label, :focus, :start, :target, 'ACTIVE') RETURNING id
                """)
                .param("u", userId).param("type", request.goalType().name()).param("label", label)
                .param("focus", request.focusGoal().name()).param("start", today)
                .param("target", request.targetDate())
                .query(Long.class).single();
        int order = 0;
        for (DefaultItem item : items) {
            jdbc.sql("""
                    INSERT INTO routine_items (care_plan_id, time_of_day, title, subtitle, sort_order)
                    VALUES (:p, :t, :title, :sub, :o)
                    """)
                    .param("p", planId).param("t", item.time().name()).param("title", cut(item.title()))
                    .param("sub", item.subtitle() == null ? null : cut(item.subtitle())).param("o", order++).update();
        }
        return view(activePlan(userId).orElseThrow());
    }

    public Optional<PlanView> currentPlan(long userId) {
        return activePlan(userId).map(this::view);
    }

    public Optional<TodayView> today(long userId) {
        return activePlan(userId).map(plan -> {
            LocalDate today = today();
            List<Item> items = items(plan.id());
            Set<RoutineStats.Check> checks = checks(plan.id(), today.minusDays(14), today);
            List<ItemView> itemViews = items.stream()
                    .map(i -> new ItemView(i.id(), i.time(), i.title(), i.subtitle(),
                            checks.contains(new RoutineStats.Check(i.id(), today))))
                    .toList();
            RoutineStats.Week week = RoutineStats.week(
                    items.stream().map(i -> new RoutineStats.Item(i.id(), i.time().name())).toList(),
                    checks, plan.startDate(), plan.targetDate(), today);
            WeekView weekView = new WeekView(week.start(), week.end(), week.scheduled(), week.completed(),
                    week.percent(), week.lastWeekCompleted(),
                    week.days().stream().map(d -> new DayView(d.date(), d.status())).toList());
            int done = (int) itemViews.stream().filter(ItemView::checked).count();
            return new TodayView(today, view(plan), itemViews, done, weekView);
        });
    }

    @Transactional
    public void setChecked(long userId, long itemId, LocalDate date, boolean checked) {
        Plan plan = jdbc.sql("""
                SELECT p.id, p.goal_type, p.goal_label, p.focus_goal, p.start_date, p.target_date
                FROM routine_items i JOIN care_plans p ON p.id = i.care_plan_id
                WHERE i.id = :item AND p.user_id = :u AND p.status = 'ACTIVE'
                """).param("item", itemId).param("u", userId)
                .query((rs, n) -> new Plan(rs.getLong("id"), GoalType.valueOf(rs.getString("goal_type")),
                        rs.getString("goal_label"), FocusGoal.valueOf(rs.getString("focus_goal")),
                        rs.getObject("start_date", LocalDate.class), rs.getObject("target_date", LocalDate.class)))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (date.isAfter(today())) {
            throw badRequest("아직 오지 않은 날은 체크할 수 없어요.");
        }
        if (date.isBefore(plan.startDate()) || date.isAfter(plan.targetDate())) {
            throw badRequest("관리 계획 기간 안의 날짜만 체크할 수 있어요.");
        }
        if (checked) {
            jdbc.sql("INSERT INTO routine_checks (routine_item_id, check_date) VALUES (:i, :d) ON CONFLICT DO NOTHING")
                    .param("i", itemId).param("d", date).update();
        } else {
            jdbc.sql("DELETE FROM routine_checks WHERE routine_item_id = :i AND check_date = :d")
                    .param("i", itemId).param("d", date).update();
        }
    }

    private PlanView view(Plan plan) {
        long daysLeft = ChronoUnit.DAYS.between(today(), plan.targetDate());
        return new PlanView(plan.id(), plan.goalType(), plan.goalLabel(), plan.focusGoal(),
                plan.startDate(), plan.targetDate(), daysLeft);
    }

    private Optional<Plan> activePlan(long userId) {
        return jdbc.sql("""
                SELECT id, goal_type, goal_label, focus_goal, start_date, target_date
                FROM care_plans WHERE user_id = :u AND status = 'ACTIVE'
                """).param("u", userId)
                .query((rs, n) -> new Plan(rs.getLong("id"), GoalType.valueOf(rs.getString("goal_type")),
                        rs.getString("goal_label"), FocusGoal.valueOf(rs.getString("focus_goal")),
                        rs.getObject("start_date", LocalDate.class), rs.getObject("target_date", LocalDate.class)))
                .optional();
    }

    private List<Item> items(long planId) {
        return jdbc.sql("""
                SELECT id, time_of_day, title, subtitle FROM routine_items
                WHERE care_plan_id = :p ORDER BY CASE time_of_day WHEN 'MORNING' THEN 0 ELSE 1 END, sort_order
                """).param("p", planId)
                .query((rs, n) -> new Item(rs.getLong("id"), TimeOfDay.valueOf(rs.getString("time_of_day")),
                        rs.getString("title"), rs.getString("subtitle")))
                .list();
    }

    private Set<RoutineStats.Check> checks(long planId, LocalDate from, LocalDate to) {
        return new HashSet<>(jdbc.sql("""
                SELECT c.routine_item_id, c.check_date FROM routine_checks c
                JOIN routine_items i ON i.id = c.routine_item_id
                WHERE i.care_plan_id = :p AND c.check_date BETWEEN :from AND :to
                """).param("p", planId).param("from", from).param("to", to)
                .query((rs, n) -> new RoutineStats.Check(rs.getLong(1), rs.getObject(2, LocalDate.class)))
                .list());
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
