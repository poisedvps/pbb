package com.hospital.pbb.schedule;

import com.hospital.pbb.common.BizException;

import java.time.YearMonth;
import java.util.regex.Pattern;

/**
 * 排班月份（{@code YYYY-MM}）的解析与锁号计算。
 *
 * <p>所有接口上的月份参数都先过 {@link #parse}，格式不对一律 code=1500；
 * {@code schedule_month} 的 advisory lock 用整数锁号，由 {@link #lockKey} 统一算。</p>
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
}
