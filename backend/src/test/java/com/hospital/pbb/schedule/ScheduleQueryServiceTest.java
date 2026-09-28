package com.hospital.pbb.schedule;

import com.hospital.pbb.common.BizException;
import com.hospital.pbb.holiday.Holiday;
import com.hospital.pbb.holiday.HolidayRepository;
import com.hospital.pbb.holiday.HolidayType;
import com.hospital.pbb.schedule.dto.CellVO;
import com.hospital.pbb.schedule.dto.DayVO;
import com.hospital.pbb.schedule.dto.MineDayVO;
import com.hospital.pbb.schedule.dto.MineVO;
import com.hospital.pbb.schedule.dto.MonthScheduleVO;
import com.hospital.pbb.schedule.dto.StaffRowVO;
import com.hospital.pbb.shift.AppSettingRepository;
import com.hospital.pbb.shift.ShiftType;
import com.hospital.pbb.shift.ShiftTypeRepository;
import com.hospital.pbb.staff.Staff;
import com.hospital.pbb.staff.StaffRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 月视图查询（任务单 M2-03 验收标准）：Mockito 打桩五个仓库，
 * 重点验"科长读草稿、其他人读已发布快照"这条分界，以及月份格式、节假日历的接入。
 *
 * <p>后半部分是"我的排班"（任务单 M2-06 验收标准），时钟固定为 2026-10-09。</p>
 */
class ScheduleQueryServiceTest {

    private static final String YM = "2026-10";
    private static final LocalDate START = LocalDate.of(2026, 10, 1);
    private static final LocalDate END = LocalDate.of(2026, 10, 31);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    /** "我的排班"的"今天" */
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 9);
    /** "下一个班次"的检索上限：今天 + 60 天 */
    private static final LocalDate NEXT_UNTIL = TODAY.plusDays(60);

    private ScheduleMonthRepository monthRepo;
    private ScheduleEntryRepository entryRepo;
    private SchedulePublishedEntryRepository publishedRepo;
    private StaffRepository staffRepo;
    private HolidayRepository holidayRepo;
    private ShiftTypeRepository shiftRepo;
    private DutyPhoneWeekRepository dutyRepo;
    private DutyPhonePublishedRepository dutyPublishedRepo;
    private AppSettingRepository settingRepo;
    private ScheduleQueryService service;

    @BeforeEach
    void setUp() {
        monthRepo = mock(ScheduleMonthRepository.class);
        entryRepo = mock(ScheduleEntryRepository.class);
        publishedRepo = mock(SchedulePublishedEntryRepository.class);
        staffRepo = mock(StaffRepository.class);
        holidayRepo = mock(HolidayRepository.class);
        shiftRepo = mock(ShiftTypeRepository.class);
        dutyRepo = mock(DutyPhoneWeekRepository.class);
        dutyPublishedRepo = mock(DutyPhonePublishedRepository.class);
        settingRepo = mock(AppSettingRepository.class);
        Clock clock = Clock.fixed(TODAY.atStartOfDay(ZONE).toInstant(), ZONE);
        service = new ScheduleQueryService(monthRepo, entryRepo, publishedRepo, staffRepo, holidayRepo,
                shiftRepo, clock, dutyRepo, dutyPublishedRepo, settingRepo);

        // 默认：没有任何节假日、排班数据，schedule_month 里也还没有这一月
        when(holidayRepo.findOverlapping(any(), any())).thenReturn(List.of());
        when(entryRepo.findByWorkDateBetween(any(), any())).thenReturn(List.of());
        when(publishedRepo.findByWorkDateBetween(any(), any())).thenReturn(List.of());
        when(publishedRepo.findByStaffIdAndWorkDateBetweenOrderByWorkDateAsc(any(), any(), any()))
                .thenReturn(List.of());
        when(monthRepo.findById(any())).thenReturn(Optional.empty());
        when(staffRepo.findByActiveTrueOrderBySortOrderAscIdAsc()).thenReturn(List.of(staff(1L, "A", true)));
        when(shiftRepo.findAllByOrderBySortOrderAsc()).thenReturn(List.of(
                shift("D", "8.0", true), shift("N", "14.5", true), shift("Z", "24.0", true),
                shift("B", "0.0", false), shift("L", "0.0", false), shift("X", "0.0", false)));
    }

    private static Staff staff(Long id, String empNo, boolean schedulable) {
        Staff staff = new Staff();
        staff.setId(id);
        staff.setEmpNo(empNo);
        staff.setName("人员" + empNo);
        staff.setPosition("护士");
        staff.setSchedulable(schedulable);
        staff.setActive(true);
        return staff;
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

    private static ScheduleEntry draft(Long staffId, String date, String shiftCode, boolean manual, String remark) {
        ScheduleEntry entry = new ScheduleEntry();
        entry.setStaffId(staffId);
        entry.setWorkDate(LocalDate.parse(date));
        entry.setShiftCode(shiftCode);
        entry.setManual(manual);
        entry.setRemark(remark);
        return entry;
    }

    private static SchedulePublishedEntry published(Long staffId, String date, String shiftCode, String remark) {
        SchedulePublishedEntry entry = new SchedulePublishedEntry();
        entry.setStaffId(staffId);
        entry.setWorkDate(LocalDate.parse(date));
        entry.setShiftCode(shiftCode);
        entry.setRemark(remark);
        return entry;
    }

    /** 按 V1 预置的口径造班次：工时按 NUMERIC(4,1) 给 scale=1 */
    private static ShiftType shift(String code, String workHours, boolean countsAsWork) {
        ShiftType shift = new ShiftType();
        shift.setCode(code);
        shift.setName("班次" + code);
        shift.setWorkHours(new BigDecimal(workHours));
        shift.setCountsAsWork(countsAsWork);
        shift.setEnabled(true);
        return shift;
    }

    private static ScheduleMonth monthRow(int version) {
        ScheduleMonth month = new ScheduleMonth();
        month.setYearMonth(YM);
        month.setStatus(version > 0 ? ScheduleStatus.PUBLISHED : ScheduleStatus.DRAFT);
        month.setVersion(version);
        return month;
    }

    /** 用例 1：整月 31 天，只列可排班的人员，没排班数据时 cells 为空、状态为未发布的 DRAFT */
    @Test
    void emptyMonthHasEveryDayAndOnlySchedulableRows() {
        when(staffRepo.findByActiveTrueOrderBySortOrderAscIdAsc())
                .thenReturn(List.of(staff(1L, "A", true), staff(2L, "B", false)));

        MonthScheduleVO vo = service.getMonth(YM, true);

        assertEquals(YM, vo.yearMonth());
        assertEquals(31, vo.days().size());
        assertEquals(START, vo.days().get(0).date());
        assertEquals(END, vo.days().get(30).date());
        assertEquals(4, vo.days().get(0).weekday());   // 2026-10-01 是周四
        assertEquals(List.of(), vo.days().stream().filter(d -> d.holidayName() != null).toList());

        assertEquals(1, vo.rows().size());
        StaffRowVO row = vo.rows().get(0);
        assertEquals(1L, row.staffId());
        assertEquals("A", row.empNo());
        assertTrue(row.cells().isEmpty());

        assertEquals(ScheduleStatus.DRAFT, vo.status());
        assertEquals(0, vo.version());
        assertNull(vo.publishedAt());
    }

    /** weekday 取 ISO 值：1=周一 … 7=周日 */
    @Test
    void dayWeekdayMatchesJavaDayOfWeek() {
        MonthScheduleVO vo = service.getMonth(YM, true);

        for (DayVO day : vo.days()) {
            assertEquals(day.date().getDayOfWeek().getValue(), day.weekday());
        }
    }

    /** 用例 2：国庆 10-01~10-07 整段是 HOLIDAY，holidayName 带出记录名 */
    @Test
    void nationalDayRangeIsHoliday() {
        when(holidayRepo.findOverlapping(START, END))
                .thenReturn(List.of(holiday("国庆节", "2026-10-01", "2026-10-07", HolidayType.HOLIDAY)));

        MonthScheduleVO vo = service.getMonth(YM, true);

        DayVO first = vo.days().get(0);
        assertEquals(DayKind.HOLIDAY, first.kind());
        assertEquals("国庆节", first.holidayName());
        assertEquals(DayKind.HOLIDAY, vo.days().get(6).kind());
        // 10-08 起恢复正常：周四 → 工作日
        assertEquals(DayKind.WORKDAY, vo.days().get(7).kind());
        assertNull(vo.days().get(7).holidayName());
    }

    /** 用例 3：draft=true 读草稿，manual 原样带出 */
    @Test
    void draftReadsScheduleEntryAndKeepsManual() {
        when(entryRepo.findByWorkDateBetween(START, END))
                .thenReturn(List.of(draft(1L, "2026-10-08", "N", true, null)));

        MonthScheduleVO vo = service.getMonth(YM, true);

        assertTrue(vo.draft());
        CellVO cell = vo.rows().get(0).cells().get("2026-10-08");
        assertEquals(new CellVO("N", true, null), cell);
        verify(publishedRepo, never()).findByWorkDateBetween(any(), any());
    }

    /** 用例 4：draft=false 只读已发布快照，快照里的 manual 一律 false，草稿仓库一次都不查 */
    @Test
    void publishedReadsSnapshotOnlyAndForcesManualFalse() {
        when(publishedRepo.findByWorkDateBetween(START, END))
                .thenReturn(List.of(published(1L, "2026-10-08", "D", null)));

        MonthScheduleVO vo = service.getMonth(YM, false);

        assertFalse(vo.draft());
        CellVO cell = vo.rows().get(0).cells().get("2026-10-08");
        assertEquals(new CellVO("D", false, null), cell);
        verify(entryRepo, never()).findByWorkDateBetween(any(), any());
    }

    /** 用例 5：月份格式不对 → 1500，且不去查任何数据 */
    @Test
    void invalidYearMonthThrows1500() {
        BizException e = assertThrows(BizException.class, () -> service.getMonth("202610", true));

        assertEquals(1500, e.getCode());
        verify(entryRepo, never()).findByWorkDateBetween(any(), any());
        verify(staffRepo, never()).findByActiveTrueOrderBySortOrderAscIdAsc();
    }

    /** 已发布的月份：status / version / publishedAt 一律取自 schedule_month */
    @Test
    void monthStatusComesFromScheduleMonth() {
        ScheduleMonth month = new ScheduleMonth();
        month.setYearMonth(YM);
        month.setStatus(ScheduleStatus.PUBLISHED);
        month.setVersion(3);
        OffsetDateTime publishedAt = OffsetDateTime.of(2026, 9, 28, 9, 0, 0, 0, ZoneOffset.ofHours(8));
        month.setPublishedAt(publishedAt);
        when(monthRepo.findById(YM)).thenReturn(Optional.of(month));

        MonthScheduleVO vo = service.getMonth(YM, false);

        assertEquals(ScheduleStatus.PUBLISHED, vo.status());
        assertEquals(3, vo.version());
        assertEquals(publishedAt, vo.publishedAt());
    }

    /** calendar 是给 screen、stats 复用的入口：按 [start,end] 查一次节假日，判定与 RuleCalendar 一致 */
    @Test
    void calendarExposesRuleCalendarForRange() {
        when(holidayRepo.findOverlapping(START, END))
                .thenReturn(List.of(holiday("国庆节", "2026-10-01", "2026-10-07", HolidayType.HOLIDAY),
                        holiday("国庆调休", "2026-10-10", "2026-10-10", HolidayType.WORKDAY)));

        RuleCalendar calendar = service.calendar(START, END);

        verify(holidayRepo).findOverlapping(START, END);
        assertEquals(DayKind.HOLIDAY, calendar.kindOf(START));
        assertEquals(DayKind.ADJUSTED_WORKDAY, calendar.kindOf(LocalDate.of(2026, 10, 10)));   // 周六调休上班
        assertEquals("国庆调休", calendar.holidayName(LocalDate.of(2026, 10, 10)));
        assertTrue(calendar.isOffDay(LocalDate.of(2026, 10, 11)));   // 10-11 是周日
        assertEquals("X", calendar.defaultShift(LocalDate.of(2026, 10, 11)));
        // 日历按整月范围查一次，不是逐日查
        verify(holidayRepo).findOverlapping(any(), any());
    }

    // ===== 以下是“我的排班”（任务单 M2-06）=====

    /** 用例 1：本月已发布 N/D/X → counts 按出现顺序统计，工时 14.5+8.0+0.0=22.5，next 是今天第一个计工时的班 */
    @Test
    void mineCountsShiftsHoursAndNextShift() {
        when(monthRepo.findById(YM)).thenReturn(Optional.of(monthRow(3)));
        when(publishedRepo.findByStaffIdAndWorkDateBetweenOrderByWorkDateAsc(1L, START, END))
                .thenReturn(List.of(published(1L, "2026-10-08", "N", null),
                        published(1L, "2026-10-09", "D", null),
                        published(1L, "2026-10-10", "X", null)));
        // 今天(10-09)往后的快照不含已过期的 10-08
        when(publishedRepo.findByStaffIdAndWorkDateBetweenOrderByWorkDateAsc(1L, TODAY, NEXT_UNTIL))
                .thenReturn(List.of(published(1L, "2026-10-09", "D", null),
                        published(1L, "2026-10-10", "X", null)));

        MineVO vo = service.mine(1L, YM);

        assertEquals(YM, vo.yearMonth());
        assertTrue(vo.published());
        assertEquals(List.of("N", "D", "X"), List.copyOf(vo.counts().keySet()));
        assertEquals(List.of(1, 1, 1), List.copyOf(vo.counts().values()));
        assertEquals(0, vo.workHours().compareTo(new BigDecimal("22.5")));

        assertEquals(31, vo.days().size());
        assertNull(vo.days().get(0).shiftCode());                                     // 10-01 没排班
        assertEquals("N", vo.days().get(7).shiftCode());                              // 10-08
        assertEquals("D", vo.days().get(8).shiftCode());
        assertEquals("X", vo.days().get(9).shiftCode());
        assertEquals(5, vo.days().get(8).weekday());                                   // 10-09 是周五
        assertEquals(DayKind.WORKDAY, vo.days().get(8).kind());
        assertEquals(DayKind.WEEKEND, vo.days().get(9).kind());                        // 10-10 是周六

        MineDayVO next = vo.next();
        assertEquals(TODAY, next.date());
        assertEquals("D", next.shiftCode());
        assertEquals(DayKind.WORKDAY, next.kind());
        assertNull(next.holidayName());
    }

    /** 用例 2：账号未关联人员 → 日历照出，但没有班次、没有统计、没有下次班，也不查快照 */
    @Test
    void mineWithoutStaffReturnsCalendarOnly() {
        MineVO vo = service.mine(null, YM);

        assertEquals(31, vo.days().size());
        assertEquals(List.of(), vo.days().stream().map(MineDayVO::shiftCode).filter(Objects::nonNull).toList());
        assertTrue(vo.counts().isEmpty());
        assertEquals(0, vo.workHours().compareTo(BigDecimal.ZERO));
        assertNull(vo.next());
        assertFalse(vo.published());
        verify(publishedRepo, never()).findByStaffIdAndWorkDateBetweenOrderByWorkDateAsc(any(), any(), any());
    }

    /** 用例 3：schedule_month 没有记录、或有记录但 version 还是 0，都算未发布 */
    @Test
    void mineReportsUnpublishedMonth() {
        assertFalse(service.mine(1L, YM).published());

        when(monthRepo.findById(YM)).thenReturn(Optional.of(monthRow(0)));
        assertFalse(service.mine(1L, YM).published());

        when(monthRepo.findById(YM)).thenReturn(Optional.of(monthRow(1)));
        assertTrue(service.mine(1L, YM).published());
    }

    /** next 跳过不计工时的休息/请假，而且查的是 [今天, 今天+60] 这个范围自己的日历 */
    @Test
    void nextShiftSkipsOffDaysAndUsesItsOwnCalendarRange() {
        when(publishedRepo.findByStaffIdAndWorkDateBetweenOrderByWorkDateAsc(1L, TODAY, NEXT_UNTIL))
                .thenReturn(List.of(published(1L, "2026-10-10", "X", null),
                        published(1L, "2026-10-11", "L", null),
                        published(1L, "2026-11-05", "Z", null)));
        when(holidayRepo.findOverlapping(TODAY, NEXT_UNTIL))
                .thenReturn(List.of(holiday("立冬", "2026-11-05", "2026-11-05", HolidayType.HOLIDAY)));

        MineVO vo = service.mine(1L, YM);

        assertEquals(LocalDate.of(2026, 11, 5), vo.next().date());
        assertEquals("Z", vo.next().shiftCode());
        assertEquals(DayKind.HOLIDAY, vo.next().kind());
        assertEquals("立冬", vo.next().holidayName());
        verify(holidayRepo).findOverlapping(START, END);
        verify(holidayRepo).findOverlapping(TODAY, NEXT_UNTIL);
        // 本月没有任何班次
        assertTrue(vo.counts().isEmpty());
        assertEquals(0, vo.workHours().compareTo(BigDecimal.ZERO));
    }

    /** 60 天内没有计工时的班 → next 为 null */
    @Test
    void nextShiftIsNullWhenNothingCountsAsWork() {
        when(publishedRepo.findByStaffIdAndWorkDateBetweenOrderByWorkDateAsc(1L, TODAY, NEXT_UNTIL))
                .thenReturn(List.of(published(1L, "2026-10-10", "X", null),
                        published(1L, "2026-12-08", "B", null)));

        assertNull(service.mine(1L, YM).next());
    }

    /** 快照里的班次代号在 shift_type 查不到（数据残留）→ 计 0 工时，但天数照常统计 */
    @Test
    void unknownShiftCodeCountsZeroHours() {
        when(publishedRepo.findByStaffIdAndWorkDateBetweenOrderByWorkDateAsc(1L, START, END))
                .thenReturn(List.of(published(1L, "2026-10-12", "Q", null),
                        published(1L, "2026-10-13", "D", null)));

        MineVO vo = service.mine(1L, YM);

        assertEquals(List.of("Q", "D"), List.copyOf(vo.counts().keySet()));
        assertEquals(List.of(1, 1), List.copyOf(vo.counts().values()));
        assertEquals(0, vo.workHours().compareTo(new BigDecimal("8.0")));
    }

    /** 月份格式不对 → 1500，不查任何数据 */
    @Test
    void mineWithInvalidYearMonthThrows1500() {
        BizException e = assertThrows(BizException.class, () -> service.mine(1L, "2026/10"));

        assertEquals(1500, e.getCode());
        verify(publishedRepo, never()).findByStaffIdAndWorkDateBetweenOrderByWorkDateAsc(any(), any(), any());
        verify(monthRepo, never()).findById(any());
    }

    // ===== 以下是“某人某天的已发布班次”（任务单 M3-01）=====

    /** 用例：快照里有这条记录 → Optional.of("D")，只按 (staffId, workDate) 查一次快照 */
    @Test
    void publishedShiftReturnsCodeWhenSnapshotExists() {
        LocalDate day = LocalDate.of(2026, 10, 8);
        when(publishedRepo.findByStaffIdAndWorkDate(1L, day))
                .thenReturn(Optional.of(published(1L, "2026-10-08", "D", "顶班")));

        assertEquals(Optional.of("D"), service.publishedShift(1L, day));

        verify(publishedRepo).findByStaffIdAndWorkDate(1L, day);
        // 调班校验的是成员已经看到的那个班，草稿表一律不查
        verify(entryRepo, never()).findByStaffIdAndWorkDate(any(), any());
    }

    /** 用例：那天没排到班、或者整月还没发布 → Optional.empty() */
    @Test
    void publishedShiftIsEmptyWhenNoRecord() {
        LocalDate day = LocalDate.of(2026, 10, 8);
        when(publishedRepo.findByStaffIdAndWorkDate(1L, day)).thenReturn(Optional.empty());

        assertEquals(Optional.empty(), service.publishedShift(1L, day));
    }
}
