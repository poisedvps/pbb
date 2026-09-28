package com.hospital.pbb.schedule;

import com.hospital.pbb.common.BizException;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 排班月份（{@code YYYY-MM}）的解析与锁号计算。
 *
 * <p>所有接口上的月份参数都先过 {@link #parse}，格式不对一律 code=1500；
 * {@code schedule_month} 的 advisory lock 用整数锁号，由 {@link #lockKey} 统一算。</p>
 *
 * <p>值班电话按周存（{@code duty_phone_week} 一周一行），一个月往往和上一个月的最后一周纠缠在一起，
 * {@link #firstWeekStart}、{@link #monthsOfWeek} 就是专门算这个跨越的。</p>
 */
public final class ScheduleMonths {

    private static final Pattern FORMAT = Pattern.compile("^\\d{4}-(0[1-9]|1[0-2])$");

    private ScheduleMonths() {
    }

    /**
     * 解析 {@code YYYY-MM}。
     *
     * @param yearMonth 形如 {@code 2026-10} 的月份字符串
     * @return 对应的 {@link YearMonth}
     * @throws BizException code=1500，入参为 null 或格式不匹配 {@code ^\d{4}-(0[1-9]|1[0-2])$}
     */
    public static YearMonth parse(String yearMonth) {
        if (yearMonth == null || !FORMAT.matcher(yearMonth).matches()) {
            throw new BizException(1500, "月份格式应为 YYYY-MM");
        }
        int year = Integer.parseInt(yearMonth.substring(0, 4));
        int month = Integer.parseInt(yearMonth.substring(5, 7));
        return YearMonth.of(year, month);
    }

    /**
     * 月份对应的锁号，供 {@code ScheduleMonthRepository.lockMonth} 使用：{@code 2026-10 → 202610}。
     */
    public static int lockKey(YearMonth ym) {
        return ym.getYear() * 100 + ym.getMonthValue();
    }

    /**
     * 该月 1 日所在周的周一，可能落在上个月（设计 §8.4：值班电话按周存，
     * 取数要从含本月 1 日的那一周算起，而不是从 1 日本身）。
     *
     * @param ym 排班月份
     * @return 该月 1 日当天或之前最近的周一，如 {@code 2026-10 → 2026-09-28}、{@code 2026-06 → 2026-06-01}
     */
    public static LocalDate firstWeekStart(YearMonth ym) {
        return ym.atDay(1).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    /**
     * 某周（周一起）跨到的月份，升序去重。
     *
     * <p>一周最多跨两个月，所以只看周一和周日两端。</p>
     *
     * @param weekStart 该周的周一
     * @return 如 {@code 2026-09-28 → [2026-09, 2026-10]}、{@code 2026-10-05 → [2026-10]}
     */
    public static List<YearMonth> monthsOfWeek(LocalDate weekStart) {
        YearMonth first = YearMonth.from(weekStart);
        YearMonth last = YearMonth.from(weekStart.plusDays(6));
        return first.equals(last) ? List.of(first) : List.of(first, last);
    }
}
