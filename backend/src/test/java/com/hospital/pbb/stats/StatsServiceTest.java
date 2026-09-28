package com.hospital.pbb.stats;

import com.hospital.pbb.common.BizException;
import com.hospital.pbb.holiday.Holiday;
import com.hospital.pbb.holiday.HolidayType;
import com.hospital.pbb.schedule.DutyPhonePublished;
import com.hospital.pbb.schedule.DutyPhonePublishedRepository;
import com.hospital.pbb.schedule.RuleCalendar;
import com.hospital.pbb.schedule.SchedulePublishedEntry;
import com.hospital.pbb.schedule.SchedulePublishedEntryRepository;
import com.hospital.pbb.schedule.ScheduleQueryService;
import com.hospital.pbb.shift.ShiftType;
import com.hospital.pbb.shift.ShiftTypeRepository;
import com.hospital.pbb.staff.Staff;
import com.hospital.pbb.staff.StaffRepository;
import com.hospital.pbb.stats.dto.StatsRowVO;
import com.hospital.pbb.stats.dto.StatsVO;
import com.hospital.pbb.user.AuthUser;
import com.hospital.pbb.user.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 统计接口（任务单 M3-06、M4-07 验收标准）：Mockito 打桩三个仓库加 {@link ScheduleQueryService}，
 * 重点验 §5.4 的三条口径——各班次天数、节假日/周末上班天数、总工时，成员只看自己，
 * 以及 §8.5 的值班电话天数（按天与统计区间取交集）。
 */
class StatsServiceTest {

    private static final LocalDate FROM = LocalDate.of(2026, 10, 1);
    private static final LocalDate TO = LocalDate.of(2026, 10, 31);

    /** 科长（科长账号不关联人员，staffId 为 null） */
    private static final AuthUser ADMIN = new AuthUser(1L, "admin", Role.ADMIN, null);

    private SchedulePublishedEntryRepository publishedRepo;
    private StaffRepository staffRepo;
    private ShiftTypeRepository shiftRepo;
    private ScheduleQueryService query;
    private DutyPhonePublishedRepository dutyPublishedRepo;
    private StatsService service;

    @BeforeEach
    void setUp() {
        publishedRepo = mock(SchedulePublishedEntryRepository.class);
        staffRepo = mock(StaffRepository.class);
        shiftRepo = mock(ShiftTypeRepository.class);
        query = mock(ScheduleQueryService.class);
        dutyPublishedRepo = mock(DutyPhonePublishedRepository.class);
        service = new StatsService(publishedRepo, staffRepo, shiftRepo, query, dutyPublishedRepo);

        // 默认：区间内没有节假日、没有快照，人员表里只有一个可排班的 A
        when(query.calendar(any(), any())).thenReturn(new RuleCalendar(List.of()));
        when(publishedRepo.findByWorkDateBetween(any(), any())).thenReturn(List.of());
        when(staffRepo.findByActiveTrueOrderBySortOrderAscIdAsc()).thenReturn(List.of(staff(1L, "A001", "张三")));
        when(shiftRepo.findAllByOrderBySortOrderAsc()).thenReturn(List.of(
                shift("D", "8.0", true), shift("N", "14.5", true), shift("Z", "24.0", true),
                shift("B", "0.0", false), shift("L", "0.0", false), shift("X", "0.0", false)));
    }

    private static Staff staff(Long id, String empNo, String name) {
        Staff staff = new Staff();
        staff.setId(id);
        staff.setEmpNo(empNo);
        staff.setName(name);
        staff.setPosition("护士");
        staff.setSchedulable(true);
        staff.setActive(true);
        return staff;
    }

    /** 按 V1 预置口径造班次：工时 NUMERIC(4,1)，请假/休息不计工时 */
    private static ShiftType shift(String code, String workHours, boolean countsAsWork) {
        ShiftType shift = new ShiftType();
        shift.setCode(code);
        shift.setName("班次" + code);
        shift.setWorkHours(new BigDecimal(workHours));
        shift.setCountsAsWork(countsAsWork);
        shift.setEnabled(true);
        return shift;
    }

    private static SchedulePublishedEntry published(Long staffId, String date, String shiftCode) {
        SchedulePublishedEntry entry = new SchedulePublishedEntry();
        entry.setStaffId(staffId);
        entry.setWorkDate(LocalDate.parse(date));
        entry.setShiftCode(shiftCode);
        entry.setVersion(1);
        return entry;
    }

    private static Holiday holiday(String name, String start, String end, HolidayType type) {
        Holiday holiday = new Holiday();
        holiday.setYear(LocalDate.parse(start).getYear());
        holiday.setName(name);
        holiday.setStartDate(LocalDate.parse(start));
        holiday.setEndDate(LocalDate.parse(end));
        holiday.setType(type);
        return holiday;
    }

    /** 把 query.calendar 换成真实的规则日历，周末/放假判断走 RuleCalendar 本身的逻辑 */
    private void calendarWith(Holiday... holidays) {
        when(query.calendar(FROM, TO)).thenReturn(new RuleCalendar(List.of(holidays)));
    }

    /** 一周已发布的值班电话（week_start 必是周一） */
    private static DutyPhonePublished dutyPhone(String weekStart, Long staffId) {
        DutyPhonePublished row = new DutyPhonePublished();
        row.setWeekStart(LocalDate.parse(weekStart));
        row.setStaffId(staffId);
        return row;
    }

    /** 把 duty_phone_published 换成给定的几周（统计只按 [from-6, to] 查一次，参数由实现自己算） */
    private void dutyPhones(DutyPhonePublished... rows) {
        when(dutyPublishedRepo.findByWeekStartBetweenOrderByWeekStartAsc(any(), any())).thenReturn(List.of(rows));
    }

    /** 用例 A：10-05 D、10-10 N（周六）、10-11 X → 各班次天数齐、周末上班 1 天、工时 8+14.5+0=22.5 */
    @Test
    void countsShiftDaysOffDayWorkAndTotalHours() {
        calendarWith();
        when(publishedRepo.findByWorkDateBetween(FROM, TO)).thenReturn(List.of(
                published(1L, "2026-10-05", "D"),
                published(1L, "2026-10-10", "N"),   // 2026-10-10 是周六
                published(1L, "2026-10-11", "X")));  // 2026-10-11 是周日

        StatsVO vo = service.stats(FROM, TO, ADMIN);

        assertEquals(FROM, vo.from());
        assertEquals(TO, vo.to());
        assertEquals(1, vo.rows().size());
        StatsRowVO row = vo.rows().get(0);
        assertEquals(1L, row.staffId());
        assertEquals("A001", row.empNo());
        assertEquals("张三", row.name());
        // 六个班次全都在，没排的为 0，顺序按班次 sort_order
        assertEquals(List.of("D", "N", "Z", "B", "L", "X"), List.copyOf(row.counts().keySet()));
        assertEquals(List.of(1, 1, 0, 0, 0, 1), List.copyOf(row.counts().values()));
        assertEquals(1, row.offDayWork());
        assertEquals(0, row.totalHours().compareTo(new BigDecimal("22.5")));
        verify(query).calendar(FROM, TO);
    }

    /** 用例 B：国庆 10-01~07 放假，10-02 上了 24h 的 Z → 放假日上班同样算进 offDayWork */
    @Test
    void holidayOffDayWorkCounts() {
        calendarWith(holiday("国庆节", "2026-10-01", "2026-10-07", HolidayType.HOLIDAY));
        when(publishedRepo.findByWorkDateBetween(FROM, TO))
                .thenReturn(List.of(published(1L, "2026-10-02", "Z")));

        StatsRowVO row = service.stats(FROM, TO, ADMIN).rows().get(0);

        assertEquals(1, row.counts().get("Z"));
        assertEquals(1, row.offDayWork());
        assertEquals(0, row.totalHours().compareTo(new BigDecimal("24.0")));
    }

    /** 调休上班日（周末被调成上班）不算"节假日/周末上班" */
    @Test
    void adjustedWorkdayIsNotOffDayWork() {
        calendarWith(holiday("国庆调休", "2026-10-10", "2026-10-10", HolidayType.WORKDAY));
        when(publishedRepo.findByWorkDateBetween(FROM, TO))
                .thenReturn(List.of(published(1L, "2026-10-10", "D")));

        StatsRowVO row = service.stats(FROM, TO, ADMIN).rows().get(0);

        assertEquals(1, row.counts().get("D"));
        assertEquals(0, row.offDayWork());
    }

    /** 不计工时的班次（休息、请假）落在周末，也不计入 offDayWork */
    @Test
    void shiftWithoutWorkOnWeekendIsNotOffDayWork() {
        calendarWith();
        when(publishedRepo.findByWorkDateBetween(FROM, TO))
                .thenReturn(List.of(published(1L, "2026-10-11", "L")));   // 周日请假

        StatsRowVO row = service.stats(FROM, TO, ADMIN).rows().get(0);

        assertEquals(1, row.counts().get("L"));
        assertEquals(0, row.offDayWork());
        assertEquals(0, row.totalHours().compareTo(BigDecimal.ZERO));
    }

    /** 用例 C：成员调用，A、B 两人都有数据 → rows 只有本人那一行 */
    @Test
    void memberSeesOnlyOwnRow() {
        calendarWith();
        when(staffRepo.findByActiveTrueOrderBySortOrderAscIdAsc())
                .thenReturn(List.of(staff(1L, "A001", "张三"), staff(2L, "B002", "李四")));
        when(publishedRepo.findByWorkDateBetween(FROM, TO)).thenReturn(List.of(
                published(1L, "2026-10-05", "D"), published(1L, "2026-10-12", "D"),
                published(2L, "2026-10-06", "N")));

        StatsVO vo = service.stats(FROM, TO, new AuthUser(2L, "A001", Role.MEMBER, 1L));

        assertEquals(1, vo.rows().size());
        assertEquals(1L, vo.rows().get(0).staffId());
        assertEquals(2, vo.rows().get(0).counts().get("D"));
    }

    /** 成员账号没关联人员（staffId 为 null）→ 一行都没有 */
    @Test
    void memberWithoutStaffHasNoRows() {
        StatsVO vo = service.stats(FROM, TO, new AuthUser(3L, "screen", Role.MEMBER, null));

        assertEquals(List.of(), vo.rows());
    }

    /** 不参与排班的人员不出现在报表里；一行没有快照的人也要出一行全 0 */
    @Test
    void unschedulableStaffIsSkippedAndEmptyStaffStillHasRow() {
        calendarWith();
        Staff unschedulable = staff(3L, "C003", "王五");
        unschedulable.setSchedulable(false);
        when(staffRepo.findByActiveTrueOrderBySortOrderAscIdAsc())
                .thenReturn(List.of(staff(1L, "A001", "张三"), staff(2L, "B002", "李四"), unschedulable));
        when(publishedRepo.findByWorkDateBetween(FROM, TO))
                .thenReturn(List.of(published(1L, "2026-10-05", "D")));

        StatsVO vo = service.stats(FROM, TO, ADMIN);

        assertEquals(List.of(1L, 2L), vo.rows().stream().map(StatsRowVO::staffId).toList());
        StatsRowVO empty = vo.rows().get(1);
        assertEquals(Map.of("D", 0, "N", 0, "Z", 0, "B", 0, "L", 0, "X", 0), empty.counts());
        assertEquals(0, empty.offDayWork());
        assertEquals(0, empty.totalHours().compareTo(BigDecimal.ZERO));
    }

    /** 快照里的代号在 shift_type 查不到（数据残留）→ 天数照计，工时按 0 */
    @Test
    void unknownShiftCodeCountsZeroHours() {
        calendarWith();
        when(publishedRepo.findByWorkDateBetween(FROM, TO))
                .thenReturn(List.of(published(1L, "2026-10-10", "Q"), published(1L, "2026-10-12", "D")));

        StatsRowVO row = service.stats(FROM, TO, ADMIN).rows().get(0);

        assertEquals(1, row.counts().get("Q"));
        assertEquals(0, row.offDayWork());
        assertEquals(0, row.totalHours().compareTo(new BigDecimal("8.0")));
    }

    /** 用例 D：from 晚于 to → 1700，且不去查任何数据 */
    @Test
    void fromAfterToThrows1700() {
        BizException e = assertThrows(BizException.class,
                () -> service.stats(LocalDate.of(2026, 10, 31), FROM, ADMIN));

        assertEquals(1700, e.getCode());
        assertEquals("开始日期不能晚于结束日期", e.getMessage());
        verify(publishedRepo, never()).findByWorkDateBetween(any(), any());
        verify(staffRepo, never()).findByActiveTrueOrderBySortOrderAscIdAsc();
    }

    /** 用例 E：366 天整可以，多一天 → 1701 */
    @Test
    void rangeOver366DaysThrows1701() {
        calendarWith();
        LocalDate to = LocalDate.of(2027, 1, 1);
        // 2026-01-01 ~ 2027-01-01 正好 366 天，刚好在上限内
        StatsVO vo = service.stats(LocalDate.of(2026, 1, 1), to, ADMIN);
        assertEquals(1, vo.rows().size());

        BizException e = assertThrows(BizException.class,
                () -> service.stats(LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 2), ADMIN));

        assertEquals(1701, e.getCode());
        assertEquals("统计范围不能超过 366 天", e.getMessage());
    }

    /** from == to 是合法的单日区间 */
    @Test
    void singleDayRangeIsValid() {
        calendarWith();
        LocalDate day = LocalDate.of(2026, 10, 10);
        when(publishedRepo.findByWorkDateBetween(day, day)).thenReturn(List.of(published(1L, "2026-10-10", "N")));

        StatsVO vo = service.stats(day, day, ADMIN);

        assertEquals(day, vo.from());
        assertEquals(day, vo.to());
        assertEquals(1, vo.rows().get(0).counts().get("N"));
        assertEquals(1, vo.rows().get(0).offDayWork());
    }

    /** M4-07 用例 A：A 值 2026-09-28 那一周（跨月），统计 10-01~10-31 → 只算 10-01~10-04 共 4 天 */
    @Test
    void dutyPhoneDaysCountsOnlyDaysInsideRange() {
        calendarWith();
        dutyPhones(dutyPhone("2026-09-28", 1L));

        StatsVO vo = service.stats(FROM, TO, ADMIN);

        assertEquals(4, vo.rows().get(0).dutyPhoneDays());
    }

    /** M4-07 用例 B：A 值 10-05、10-12 两个整周 → 7+7=14 天 */
    @Test
    void dutyPhoneDaysSumsFullWeeks() {
        calendarWith();
        dutyPhones(dutyPhone("2026-10-05", 1L), dutyPhone("2026-10-12", 1L));

        StatsVO vo = service.stats(FROM, TO, ADMIN);

        assertEquals(14, vo.rows().get(0).dutyPhoneDays());
    }

    /** M4-07 用例 C：A 值 10-26 那一周（尾跨下个月）→ 只算 10-26~10-31 共 6 天 */
    @Test
    void dutyPhoneDaysTruncatesWeekAtRangeEnd() {
        calendarWith();
        dutyPhones(dutyPhone("2026-10-26", 1L));

        StatsVO vo = service.stats(FROM, TO, ADMIN);

        assertEquals(6, vo.rows().get(0).dutyPhoneDays());
    }

    /** M4-07 用例 D：没有值班电话 → 0；值班电话与当天排什么班无关，没排班的人也可以有天数 */
    @Test
    void dutyPhoneDaysIsZeroWhenNobodyIsOnDuty() {
        calendarWith();
        when(dutyPublishedRepo.findByWeekStartBetweenOrderByWeekStartAsc(any(), any())).thenReturn(List.of());

        StatsVO vo = service.stats(FROM, TO, ADMIN);

        assertEquals(0, vo.rows().get(0).dutyPhoneDays());
    }

    /** M4-07 验收：天数按人归档，没值班的那个人是 0，不是共用同一个人 */
    @Test
    void dutyPhoneDaysAreCountedPerStaff() {
        calendarWith();
        when(staffRepo.findByActiveTrueOrderBySortOrderAscIdAsc())
                .thenReturn(List.of(staff(1L, "A001", "张三"), staff(2L, "B002", "李四")));
        dutyPhones(dutyPhone("2026-10-05", 2L));

        StatsVO vo = service.stats(FROM, TO, ADMIN);

        assertEquals(0, vo.rows().get(0).dutyPhoneDays());
        assertEquals(7, vo.rows().get(1).dutyPhoneDays());
    }

    /** M4-07 用例 E：成员调用 → 只有本人一行，dutyPhoneDays 照常计算 */
    @Test
    void memberRowStillHasDutyPhoneDays() {
        calendarWith();
        when(staffRepo.findByActiveTrueOrderBySortOrderAscIdAsc())
                .thenReturn(List.of(staff(1L, "A001", "张三"), staff(2L, "B002", "李四")));
        dutyPhones(dutyPhone("2026-09-28", 1L), dutyPhone("2026-10-05", 2L));

        StatsVO vo = service.stats(FROM, TO, new AuthUser(2L, "A001", Role.MEMBER, 1L));

        assertEquals(1, vo.rows().size());
        assertEquals(1L, vo.rows().get(0).staffId());
        assertEquals(4, vo.rows().get(0).dutyPhoneDays());
    }

    /** M4-07 验收：统计 10-01~10-31 时，仓库按 (2026-09-25, 2026-10-31) 查，才能捐到 9 月那一跨月周 */
    @Test
    void dutyPhoneQueryStartsSixDaysBeforeRangeStart() {
        calendarWith();

        service.stats(FROM, TO, ADMIN);

        verify(dutyPublishedRepo).findByWeekStartBetweenOrderByWeekStartAsc(LocalDate.of(2026, 9, 25), TO);
    }

    /** M3-08 验收：6 个班次 → 表头 2+6+3=11 列；M4-07 验收：倒数第二列是值班电话（天），最后一列是总工时 */
    @Test
    void exportHeadersAreStaffColumnsPlusShiftNamesPlusThreeTotals() {
        List<String> headers = service.exportHeaders();

        assertEquals(11, headers.size());
        // 班次列名用 name（不是代号），顺序同班次 sort_order
        assertEquals(List.of("工号", "姓名", "班次D", "班次N", "班次Z", "班次B", "班次L", "班次X",
                "节假日/周末上班", "值班电话（天）", "总工时"), headers);
    }

    /** M3-08 验收：每行 11 个值且顺序同表头；残留代号不占列；没排班的人也有一行全 0 */
    @Test
    void exportRowsMatchHeadersAndEveryRowHasElevenCells() {
        calendarWith();
        when(staffRepo.findByActiveTrueOrderBySortOrderAscIdAsc())
                .thenReturn(List.of(staff(1L, "A001", "张三"), staff(2L, "B002", "李四")));
        when(publishedRepo.findByWorkDateBetween(FROM, TO)).thenReturn(List.of(
                published(1L, "2026-10-05", "D"),
                published(1L, "2026-10-10", "N"),    // 2026-10-10 是周六
                published(1L, "2026-10-11", "Q")));  // shift_type 里查不到的残留代号
        dutyPhones(dutyPhone("2026-10-12", 2L));

        List<List<Object>> rows = service.exportRows(service.stats(FROM, TO, ADMIN));

        assertEquals(2, rows.size());
        for (List<Object> row : rows) {
            assertEquals(11, row.size());
        }

        List<Object> zhang = rows.get(0);
        // Q 没有对应的班次列，不写进导出（写出来会和表头错位）
        assertEquals(List.of("A001", "张三", 1, 1, 0, 0, 0, 0, 1, 0), zhang.subList(0, 10));
        assertEquals(Integer.class, zhang.get(2).getClass());
        assertEquals(Integer.class, zhang.get(8).getClass());
        // 倒数第二列是值班电话（天），也是 Integer，Excel 里能直接求和
        assertEquals(Integer.class, zhang.get(9).getClass());
        assertEquals(0, ((BigDecimal) zhang.get(10)).compareTo(new BigDecimal("22.5")));

        List<Object> li = rows.get(1);
        assertEquals(List.of("B002", "李四", 0, 0, 0, 0, 0, 0, 0, 7, new BigDecimal("0")), li);
    }

    /** 一行数据都没有（成员没关联人员）→ 导出只有表头，行列表为空 */
    @Test
    void exportRowsIsEmptyWhenStatsHasNoRows() {
        StatsVO vo = service.stats(FROM, TO, new AuthUser(3L, "screen", Role.MEMBER, null));

        assertEquals(List.of(), service.exportRows(vo));
    }
}
