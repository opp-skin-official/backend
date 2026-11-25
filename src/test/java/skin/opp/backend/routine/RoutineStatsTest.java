package skin.opp.backend.routine;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import skin.opp.backend.routine.RoutineStats.Check;
import skin.opp.backend.routine.RoutineStats.DayStatus;
import skin.opp.backend.routine.RoutineStats.Item;
import skin.opp.backend.routine.RoutineStats.Week;

class RoutineStatsTest {

    // 아침 세안(1)·보습(2), 저녁 세안(3)·보습(4)
    static final List<Item> ITEMS = List.of(
            new Item(1, "MORNING"), new Item(2, "MORNING"), new Item(3, "EVENING"), new Item(4, "EVENING"));

    // 2026-11-23(월) ~ 2026-11-29(일)
    static final LocalDate MON = LocalDate.of(2026, 11, 23);

    static void checkAll(Set<Check> checks, LocalDate d, long... ids) {
        for (long id : ids) checks.add(new Check(id, d));
    }

    @Test
    void fullWeekInPlanSchedulesFourteenSlots() {
        Week w = RoutineStats.week(ITEMS, Set.of(), MON.minusDays(30), MON.plusDays(30), MON.plusDays(6));
        assertThat(w.start()).isEqualTo(MON);
        assertThat(w.end()).isEqualTo(MON.plusDays(6));
        assertThat(w.scheduled()).isEqualTo(14);
        assertThat(w.completed()).isZero();
        assertThat(w.percent()).isZero();
    }

    @Test
    void slotCountsOnlyWhenAllItemsOfThatTimeAreChecked() {
        Set<Check> checks = new HashSet<>();
        checkAll(checks, MON, 1, 2, 3);        // 아침 완료, 저녁은 세안만 → 1회
        checkAll(checks, MON.plusDays(1), 1, 2, 3, 4); // 2회
        Week w = RoutineStats.week(ITEMS, checks, MON.minusDays(30), MON.plusDays(30), MON.plusDays(1));

        assertThat(w.completed()).isEqualTo(3);
        assertThat(w.percent()).isEqualTo(Math.round(3 * 100f / 14)); // 21
        assertThat(w.days().get(0).status()).isEqualTo(DayStatus.PARTIAL);
        assertThat(w.days().get(1).status()).isEqualTo(DayStatus.DONE);
        assertThat(w.days().get(2).status()).isEqualTo(DayStatus.FUTURE);
    }

    @Test
    void figmaExampleTwelveOfFourteenIs86Percent() {
        Set<Check> checks = new HashSet<>();
        for (int i = 0; i < 6; i++) checkAll(checks, MON.plusDays(i), 1, 2, 3, 4); // 12회
        Week w = RoutineStats.week(ITEMS, checks, MON.minusDays(30), MON.plusDays(30), MON.plusDays(6));
        assertThat(w.completed()).isEqualTo(12);
        assertThat(w.percent()).isEqualTo(86);
        assertThat(w.days().get(6).status()).isEqualTo(DayStatus.NONE);
    }

    @Test
    void daysOutsidePlanAreNotScheduled() {
        // 계획: 수요일 시작, 금요일 목표일 → 3일 × 2 = 6회
        Week w = RoutineStats.week(ITEMS, Set.of(), MON.plusDays(2), MON.plusDays(4), MON.plusDays(2));
        assertThat(w.scheduled()).isEqualTo(6);
        assertThat(w.days().get(0).status()).isEqualTo(DayStatus.OUT_OF_PLAN);
        assertThat(w.days().get(2).status()).isEqualTo(DayStatus.NONE);
        assertThat(w.days().get(3).status()).isEqualTo(DayStatus.FUTURE);
        assertThat(w.days().get(5).status()).isEqualTo(DayStatus.OUT_OF_PLAN);
    }

    @Test
    void countsLastWeekWithinPlanForComparison() {
        Set<Check> checks = new HashSet<>();
        checkAll(checks, MON.minusDays(1), 1, 2, 3, 4); // 지난주 일요일 2회
        checkAll(checks, MON.minusDays(7), 1, 2);       // 지난주 월요일 1회
        checkAll(checks, MON.minusDays(8), 1, 2, 3, 4); // 지지난주 → 제외
        Week w = RoutineStats.week(ITEMS, checks, MON.minusDays(30), MON.plusDays(30), MON);
        assertThat(w.lastWeekCompleted()).isEqualTo(3);
    }
}
