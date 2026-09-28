package com.hospital.pbb.schedule;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 月份与周的换算（任务单 M4-02 验收标准）：值班电话按周存，
 * 取数区间要从含本月 1 日的那一周一算起，一周又可能跨到上个月。
 */
class ScheduleMonthsTest {

    @Test
    void firstWeekStartFallsIntoPreviousMonth() {
        // 2026-10-01 是周四，它所在周的周一在 9 月
        assertEquals(LocalDate.of(2026, 9, 28), ScheduleMonths.firstWeekStart(YearMonth.of(2026, 10)));
    }

    @Test
    void firstWeekStartIsFirstDayWhenThatDayIsMonday() {
        // 2026-06-01 本身就是周一
        assertEquals(LocalDate.of(2026, 6, 1), ScheduleMonths.firstWeekStart(YearMonth.of(2026, 6)));
    }

    @Test
    void weekCrossingMonthEndReturnsBothMonths() {
        assertEquals(List.of(YearMonth.of(2026, 9), YearMonth.of(2026, 10)),
                ScheduleMonths.monthsOfWeek(LocalDate.of(2026, 9, 28)));
    }

    @Test
    void weekInsideOneMonthReturnsSingleMonth() {
        assertEquals(List.of(YearMonth.of(2026, 10)),
                ScheduleMonths.monthsOfWeek(LocalDate.of(2026, 10, 5)));
    }
}
