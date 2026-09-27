package com.hospital.pbb.schedule;

/**
 * 某一天对排班的性质，由 {@link RuleCalendar} 判定。
 *
 * <p>WORKDAY = 周一至周五；WEEKEND = 周六日；HOLIDAY = 放假；ADJUSTED_WORKDAY = 调休上班。</p>
 */
public enum DayKind {
    WORKDAY,
    WEEKEND,
    HOLIDAY,
    ADJUSTED_WORKDAY
}
