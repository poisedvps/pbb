package com.hospital.pbb.schedule.dto;

import com.hospital.pbb.schedule.DayKind;

import java.time.LocalDate;

/**
 * 月视图里的一列（一天）。
 *
 * @param date        日期
 * @param weekday     1=周一 … 7=周日
 * @param kind        该日的性质，由 {@link com.hospital.pbb.schedule.RuleCalendar} 判定
 * @param holidayName 该日所属节假日记录的名字，不属于任何记录时为 null
 */
public record DayVO(LocalDate date, int weekday, DayKind kind, String holidayName) {}
