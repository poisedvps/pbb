package com.hospital.pbb.schedule;

import com.hospital.pbb.common.BizException;
import com.hospital.pbb.holiday.Holiday;
import com.hospital.pbb.holiday.HolidayType;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RuleCalendar 的日期性质判定 + ScheduleMonths 的月份解析（任务单 M2-02 验收标准）。
 */
class RuleCalendarTest {

    private static Holiday record(String name, String start, String end, HolidayType type) {
        Holiday holiday = new Holiday();
        holiday.setYear(LocalDate.parse(start).getYear());
        holiday.setName(name);
        holiday.setStartDate(LocalDate.parse(start));
        holiday.setEndDate(LocalDate.parse(end));
        holiday.setType(type);
        return holiday;
    }

    private static RuleCalendar empty() {
        return new RuleCalendar(List.of());
    }

    @Test
    void plainMondayIsWorkday() {
        RuleCalendar calendar = empty();
        LocalDate monday = LocalDate.of(2026, 10, 5);

        assertEquals(DayKind.WORKDAY, calendar.kindOf(monday));
        assertEquals("D", calendar.defaultShift(monday));
        assertFalse(calendar.isOffDay(monday));
        assertNull(calendar.holidayName(monday));
    }

    @Test
    void plainSaturdayIsWeekend() {
        RuleCalendar calendar = empty();
        LocalDate saturday = LocalDate.of(2026, 10, 10);

        assertEquals(DayKind.WEEKEND, calendar.kindOf(saturday));
        assertEquals("X", calendar.defaultShift(saturday));
        assertTrue(calendar.isOffDay(saturday));
        assertNull(calendar.holidayName(saturday));
    }

    @Test
    void holidayRecordOverridesWeekdayInsideRange() {
        RuleCalendar calendar = new RuleCalendar(
                List.of(record("国庆节", "2026-10-01", "2026-10-07", HolidayType.HOLIDAY)));
        LocalDate within = LocalDate.of(2026, 10, 5);

        assertEquals(DayKind.HOLIDAY, calendar.kindOf(within));
        assertEquals("X", calendar.defaultShift(within));
        assertTrue(calendar.isOffDay(within));
        assertEquals("国庆节", calendar.holidayName(within));
    }

    @Test
    void holidayRecordCoversBothEndpoints() {
        RuleCalendar calendar = new RuleCalendar(
                List.of(record("国庆节", "2026-10-01", "2026-10-07", HolidayType.HOLIDAY)));

        assertEquals(DayKind.HOLIDAY, calendar.kindOf(LocalDate.of(2026, 10, 1)));
        assertEquals(DayKind.HOLIDAY, calendar.kindOf(LocalDate.of(2026, 10, 7)));
        // 区间外照常按星期几判定：10-08 是周四
        assertEquals(DayKind.WORKDAY, calendar.kindOf(LocalDate.of(2026, 10, 8)));
        assertNull(calendar.holidayName(LocalDate.of(2026, 10, 8)));
    }

    @Test
    void workdayRecordOverridesWeekend() {
        RuleCalendar calendar = new RuleCalendar(
                List.of(record("国庆调休", "2026-10-10", "2026-10-10", HolidayType.WORKDAY)));
        LocalDate saturday = LocalDate.of(2026, 10, 10);

        assertEquals(DayKind.ADJUSTED_WORKDAY, calendar.kindOf(saturday));
        assertEquals("D", calendar.defaultShift(saturday));
        assertFalse(calendar.isOffDay(saturday));
        assertEquals("国庆调休", calendar.holidayName(saturday));
    }

    // ---------- 按周期模板取默认班次（任务单 M4-05）----------

    /** 周一..周日：N X D D D X X */
    private static final List<String> TEMPLATE_DAYS = List.of("N", "X", "D", "D", "D", "X", "X");

    @Test
    void templateDecidesPlainDays() {
        RuleCalendar calendar = empty();

        // 10-05 周一（普通日）→ 模板周一 N
        assertEquals("N", calendar.defaultShift(LocalDate.of(2026, 10, 5), TEMPLATE_DAYS));
        // 10-07 周三 → 模板周三 D
        assertEquals("D", calendar.defaultShift(LocalDate.of(2026, 10, 7), TEMPLATE_DAYS));
        // 10-10 普通周六 → 模板周六 X
        assertEquals("X", calendar.defaultShift(LocalDate.of(2026, 10, 10), TEMPLATE_DAYS));
        // 换成双休排 N 的模板，同一个周六就排 N
        assertEquals("N", calendar.defaultShift(LocalDate.of(2026, 10, 10),
                List.of("D", "D", "D", "D", "D", "N", "N")));
    }

    @Test
    void adjustedWorkdayStillIsDRegardlessOfTemplate() {
        RuleCalendar calendar = new RuleCalendar(
                List.of(record("国庆调休", "2026-10-10", "2026-10-10", HolidayType.WORKDAY)));
        LocalDate saturday = LocalDate.of(2026, 10, 10);

        // 模板周六是 X，但 10-10 登记成了调休上班日 → 还是 D
        assertEquals(DayKind.ADJUSTED_WORKDAY, calendar.kindOf(saturday));
        assertEquals("D", calendar.defaultShift(saturday, TEMPLATE_DAYS));
        // 同一个日子在没有登记的年份照模板走
        assertEquals("X", empty().defaultShift(saturday, TEMPLATE_DAYS));
    }

    @Test
    void holidayStillIsXRegardlessOfTemplate() {
        RuleCalendar calendar = new RuleCalendar(
                List.of(record("国庆节", "2026-10-06", "2026-10-06", HolidayType.HOLIDAY)));
        List<String> allWork = List.of("D", "D", "D", "D", "D", "D", "D");

        // 10-06 周二登记为放假日：模板全是 D，也还是 X
        assertEquals(DayKind.HOLIDAY, calendar.kindOf(LocalDate.of(2026, 10, 6)));
        assertEquals("X", calendar.defaultShift(LocalDate.of(2026, 10, 6), allWork));
        // 区间外照模板：10-05 周一 → D
        assertEquals("D", calendar.defaultShift(LocalDate.of(2026, 10, 5), allWork));
    }

    @Test
    void nullTemplateDaysEqualsBuiltinDefaultShift() {
        RuleCalendar calendar = empty();
        LocalDate saturday = LocalDate.of(2026, 10, 10);

        // 普通周六、days=null → 内置规则 X
        assertEquals("X", calendar.defaultShift(saturday, null));
        assertEquals(calendar.defaultShift(saturday), calendar.defaultShift(saturday, null));
        assertEquals("D", calendar.defaultShift(LocalDate.of(2026, 10, 5), null));
    }

    @Test
    void parseValidMonth() {
        assertEquals(YearMonth.of(2026, 10), ScheduleMonths.parse("2026-10"));
        assertEquals(YearMonth.of(2026, 1), ScheduleMonths.parse("2026-01"));
        assertEquals(YearMonth.of(2026, 12), ScheduleMonths.parse("2026-12"));
    }

    @Test
    void parseRejectsBadFormat() {
        for (String bad : List.of("2026-13", "2026-1", "2026-00", "2026-100", "26-10", "2026/10", "202610", "", "abc")) {
            BizException e = assertThrows(BizException.class, () -> ScheduleMonths.parse(bad), bad);
            assertEquals(1500, e.getCode(), bad);
        }
        BizException e = assertThrows(BizException.class, () -> ScheduleMonths.parse(null));
        assertEquals(1500, e.getCode());
        assertEquals("月份格式应为 YYYY-MM", e.getMessage());
    }

    @Test
    void lockKeyIsYearTimes100PlusMonth() {
        assertEquals(202610, ScheduleMonths.lockKey(YearMonth.of(2026, 10)));
        assertEquals(202601, ScheduleMonths.lockKey(ScheduleMonths.parse("2026-01")));
        assertEquals(202612, ScheduleMonths.lockKey(ScheduleMonths.parse("2026-12")));
    }
}
