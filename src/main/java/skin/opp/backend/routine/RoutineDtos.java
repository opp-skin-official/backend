package skin.opp.backend.routine;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;

public final class RoutineDtos {

    private RoutineDtos() {}

    /** 어떤 날을 준비하나요? (Figma 목표일 설정) */
    public enum GoalType { VACATION, OUTING, OVERNIGHT, CUSTOM }

    /** 집중할 관리 목표 */
    public enum FocusGoal { BASIC_ROUTINE, PRODUCT_REACTION, SKIN_CHANGE }

    public enum TimeOfDay { MORNING, EVENING }

    public record PlanRequest(
            @NotNull GoalType goalType,
            @Size(max = 30) String goalLabel,
            @NotNull LocalDate targetDate,
            @NotNull FocusGoal focusGoal) {}

    public record PlanView(
            long id, GoalType goalType, String goalLabel, FocusGoal focusGoal,
            LocalDate startDate, LocalDate targetDate, long daysLeft) {}

    public record ItemView(long id, TimeOfDay timeOfDay, String title, String subtitle, boolean checked) {}

    public record DayView(LocalDate date, RoutineStats.DayStatus status) {}

    public record WeekView(
            LocalDate start, LocalDate end, int scheduled, int completed, int percent,
            int lastWeekCompleted, List<DayView> days) {}

    public record TodayView(LocalDate date, PlanView plan, List<ItemView> items, int doneCount, WeekView week) {}
}
