package com.hospital.pbb.schedule;

import com.hospital.pbb.holiday.Holiday;
import com.hospital.pbb.holiday.HolidayType;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 规则日历：把节假日记录 + 星期几折算成某一天的 {@link DayKind}。
 *
 * <p>构造时一次性把传入的节假日记录按日展开成两张表，之后的查询都是 O(1)，
 * 整月、整屏、整季度的判定都从这里取，避免各处各写一遍“周六日就是休息”。</p>
 *
 * <p>优先级：调休上班日（{@link HolidayType#WORKDAY}）&gt; 放假日（{@link HolidayType#HOLIDAY}）
 * &gt; 周六日 &gt; 工作日。</p>
 */
public class RuleCalendar {

    /** 调休上班日：date → 所属节假日记录的 name */
    private final Map<LocalDate, String> workdays = new HashMap<>();

    /** 放假日：date → 所属节假日记录的 name */
    private final Map<LocalDate, String> holidays = new HashMap<>();

    /**
     * @param holidays 覆盖要计算的日期范围的节假日记录（可来自
     *                 {@code HolidayRepository.findOverlapping}），允许为 null 或空
     */
    public RuleCalendar(List<Holiday> holidays) {
        if (holidays == null) {
            return;
        }
        for (Holiday holiday : holidays) {
            if (holiday == null || holiday.getType() == null
                    || holiday.getStartDate() == null || holiday.getEndDate() == null) {
                continue;
            }
            Map<LocalDate, String> target =
                    holiday.getType() == HolidayType.WORKDAY ? this.workdays : this.holidays;
            for (LocalDate date = holiday.getStartDate();
                 !date.isAfter(holiday.getEndDate());
                 date = date.plusDays(1)) {
                target.put(date, holiday.getName());
            }
        }
    }

    /**
     * 某一天的性质：调休上班日 &gt; 放假日 &gt; 周六日 &gt; 工作日。
     */
    public DayKind kindOf(LocalDate date) {
        if (workdays.containsKey(date)) {
            return DayKind.ADJUSTED_WORKDAY;
        }
        if (holidays.containsKey(date)) {
            return DayKind.HOLIDAY;
        }
        DayOfWeek dow = date.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) {
            return DayKind.WEEKEND;
        }
        return DayKind.WORKDAY;
    }

    /**
     * 该日所属节假日记录的 name，不属于任何一条记录时返回 null；
     * 调休上班日同样返回它所属记录的名字。
     */
    public String holidayName(LocalDate date) {
        String name = workdays.get(date);
        return name != null ? name : holidays.get(date);
    }

    /**
     * 该日的默认班次代号：上班（WORKDAY、ADJUSTED_WORKDAY）→ {@code D}，休息（WEEKEND、HOLIDAY）→ {@code X}。
     */
    public String defaultShift(LocalDate date) {
        return isOffDay(date) ? "X" : "D";
    }

    /**
     * 该日是否休息（WEEKEND 或 HOLIDAY）。
     */
    public boolean isOffDay(LocalDate date) {
        DayKind kind = kindOf(date);
        return kind == DayKind.WEEKEND || kind == DayKind.HOLIDAY;
    }
}
