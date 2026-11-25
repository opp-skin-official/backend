package skin.opp.backend.routine;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 루틴 실천 집계. Figma "이번 주 루틴 달성률"의 "아침·저녁 루틴 완료 기준":
 * 하루를 아침·저녁 두 번(시간대)으로 보고, 그 시간대의 항목을 모두 체크하면 1회 실천으로 센다.
 */
public final class RoutineStats {

    public enum DayStatus { DONE, PARTIAL, NONE, FUTURE, OUT_OF_PLAN }

    public record Item(long id, String timeOfDay) {}

    public record Check(long itemId, LocalDate date) {}

    public record Day(LocalDate date, DayStatus status) {}

    public record Week(LocalDate start, LocalDate end, int scheduled, int completed, int percent,
                       int lastWeekCompleted, List<Day> days) {}

    private RoutineStats() {}

    public static Week week(List<Item> items, Set<Check> checks, LocalDate planStart, LocalDate planEnd, LocalDate today) {
        LocalDate start = today.with(DayOfWeek.MONDAY);
        LocalDate end = start.plusDays(6);
        Map<String, List<Long>> slots = slotsOf(items);

        int scheduled = 0;
        int completed = 0;
        List<Day> days = new ArrayList<>();
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            boolean inPlan = !d.isBefore(planStart) && !d.isAfter(planEnd);
            if (!inPlan) {
                days.add(new Day(d, DayStatus.OUT_OF_PLAN));
                continue;
            }
            scheduled += slots.size();
            if (d.isAfter(today)) {
                days.add(new Day(d, DayStatus.FUTURE));
                continue;
            }
            int done = completedSlots(slots, checks, d);
            completed += done;
            days.add(new Day(d, dayStatus(items, checks, slots.size(), done, d)));
        }

        int lastWeekCompleted = 0;
        for (LocalDate d = start.minusDays(7); d.isBefore(start); d = d.plusDays(1)) {
            if (!d.isBefore(planStart) && !d.isAfter(planEnd)) {
                lastWeekCompleted += completedSlots(slots, checks, d);
            }
        }
        int percent = scheduled == 0 ? 0 : Math.round(completed * 100f / scheduled);
        return new Week(start, end, scheduled, completed, percent, lastWeekCompleted, days);
    }

    /** 시간대 → 그 시간대의 항목 ID들 */
    private static Map<String, List<Long>> slotsOf(List<Item> items) {
        Map<String, List<Long>> slots = new java.util.LinkedHashMap<>();
        for (Item item : items) {
            slots.computeIfAbsent(item.timeOfDay(), k -> new ArrayList<>()).add(item.id());
        }
        return slots;
    }

    private static int completedSlots(Map<String, List<Long>> slots, Set<Check> checks, LocalDate d) {
        int done = 0;
        for (List<Long> ids : slots.values()) {
            if (ids.stream().allMatch(id -> checks.contains(new Check(id, d)))) {
                done++;
            }
        }
        return done;
    }

    private static DayStatus dayStatus(List<Item> items, Set<Check> checks, int slotCount, int doneSlots, LocalDate d) {
        if (slotCount > 0 && doneSlots == slotCount) {
            return DayStatus.DONE;
        }
        boolean anyChecked = items.stream().anyMatch(i -> checks.contains(new Check(i.id(), d)));
        return anyChecked ? DayStatus.PARTIAL : DayStatus.NONE;
    }
}
