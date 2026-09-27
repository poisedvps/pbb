package com.hospital.pbb.schedule.dto;

import com.hospital.pbb.schedule.DayKind;

import java.time.LocalDate;

/**
 * "我的排班"里的一天。
 *
 * <p>比月视图的 {@link DayVO} 多一个 {@code shiftCode}：成员只看自己那一行，
 * 不需要再带 staffId，直接给出当天的班次代号；没排到班时为 null。</p>
 *
 * @param date        日期
 * @param weekday     1=周一 … 7=周日
 * @param kind        该日的性质，由 {@link com.hospital.pbb.schedule.RuleCalendar} 判定
 * @param holidayName 该日所属节假日记录的名字，不属于任何记录时为 null
 * @param shiftCode   本人当天的班次代号，未排班或账号未关联人员时为 null
 */
public record MineDayVO(LocalDate date, int weekday, DayKind kind, String holidayName, String shiftCode) {}
