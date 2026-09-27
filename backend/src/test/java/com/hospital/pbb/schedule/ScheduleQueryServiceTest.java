package com.hospital.pbb.schedule;

import com.hospital.pbb.common.BizException;
import com.hospital.pbb.holiday.Holiday;
import com.hospital.pbb.holiday.HolidayRepository;
import com.hospital.pbb.holiday.HolidayType;
import com.hospital.pbb.schedule.dto.CellVO;
import com.hospital.pbb.schedule.dto.DayVO;
import com.hospital.pbb.schedule.dto.MonthScheduleVO;
import com.hospital.pbb.schedule.dto.StaffRowVO;
import com.hospital.pbb.staff.Staff;
import com.hospital.pbb.staff.StaffRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
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
 */
class ScheduleQueryServiceTest {

    private static final String YM = "2026-10";
    private static final LocalDate START = LocalDate.of(2026, 10, 1);
    private static final LocalDate END = LocalDate.of(2026, 10, 31);

    private ScheduleMonthRepository monthRepo;
    private ScheduleEntryRepository entryRepo;
    private SchedulePublishedEntryRepository publishedRepo;
    private StaffRepository staffRepo;
    private HolidayRepository holidayRepo;
    private ScheduleQueryService service;

    @BeforeEach
    void setUp() {
        monthRepo = mock(ScheduleMonthRepository.class);
        entryRepo = mock(ScheduleEntryRepository.class);
        publishedRepo = mock(SchedulePublishedEntryRepository.class);
        staffRepo = mock(StaffRepository.class);
        holidayRepo = mock(HolidayRepository.class);
        service = new ScheduleQueryService(monthRepo, entryRepo, publishedRepo, staffRepo, holidayRepo);

        // 默认：没有任何节假日、排班数据，schedule_month 里也还没有这一月
        when(holidayRepo.findOverlapping(any(), any())).thenReturn(List.of());
        when(entryRepo.findByWorkDateBetween(any(), any())).thenReturn(List.of());
        when(publishedRepo.findByWorkDateBetween(any(), any())).thenReturn(List.of());
        when(monthRepo.findById(any())).thenReturn(Optional.empty());
        when(staffRepo.findByActiveTrueOrderBySortOrderAscIdAsc()).thenReturn(List.of(staff(1L, "A", true)));
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
}
