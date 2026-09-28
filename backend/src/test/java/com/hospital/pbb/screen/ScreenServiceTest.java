package com.hospital.pbb.screen;

import com.hospital.pbb.schedule.SchedulePublishedEntry;
import com.hospital.pbb.schedule.SchedulePublishedEntryRepository;
import com.hospital.pbb.schedule.ScheduleQueryService;
import com.hospital.pbb.schedule.ScheduleStatus;
import com.hospital.pbb.schedule.dto.MonthScheduleVO;
import com.hospital.pbb.screen.dto.ScreenVO;
import com.hospital.pbb.screen.dto.TodayVO;
import com.hospital.pbb.staff.Staff;
import com.hospital.pbb.staff.StaffRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 大屏数据（任务单 M2-07 验收标准）：Clock 固定在 2026-10-09，
 * 月视图由 ScheduleQueryService 打桩给出，本测试只管两件事——
 * 今日概况的四种班次怎么归类、年月缺省时是不是取当前月。
 */
class ScreenServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 9);
    private static final String CURRENT_MONTH = "2026-10";

    private ScheduleQueryService query;
    private SchedulePublishedEntryRepository publishedRepo;
    private StaffRepository staffRepo;
    private ScreenService service;

    @BeforeEach
    void setUp() {
        query = mock(ScheduleQueryService.class);
        publishedRepo = mock(SchedulePublishedEntryRepository.class);
        staffRepo = mock(StaffRepository.class);
        Clock clock = Clock.fixed(Instant.parse("2026-10-09T00:00:00Z"), ZoneId.of("Asia/Shanghai"));
        service = new ScreenService(query, publishedRepo, staffRepo, clock);

        // 默认：今天没有任何已发布数据、没有人员，月份由 query 决定
        when(query.getMonth(any(), anyBoolean())).thenReturn(month(CURRENT_MONTH));
        when(publishedRepo.findByWorkDateBetween(any(), any())).thenReturn(List.of());
        when(staffRepo.findByActiveTrueOrderBySortOrderAscIdAsc()).thenReturn(List.of());
    }

    private static Staff staff(Long id, String name) {
        Staff staff = new Staff();
        staff.setId(id);
        staff.setEmpNo("E" + id);
        staff.setName(name);
        staff.setPosition("护士");
        staff.setSchedulable(true);
        staff.setActive(true);
        return staff;
    }

    private static SchedulePublishedEntry published(Long staffId, String shiftCode) {
        SchedulePublishedEntry entry = new SchedulePublishedEntry();
        entry.setStaffId(staffId);
        entry.setWorkDate(TODAY);
        entry.setShiftCode(shiftCode);
        entry.setVersion(1);
        return entry;
    }

    private static MonthScheduleVO month(String yearMonth) {
        return new MonthScheduleVO(yearMonth, ScheduleStatus.PUBLISHED, 1, null, false, List.of(), List.of(),
                null, "#fde047", List.of());
    }

    /** 用例 1：A、D 两人白班只计数，夜班/值班/请假按人员顺序出姓名 */
    @Test
    void todayCountsDayShiftAndListsNamesOfNightDutyLeave() {
        when(staffRepo.findByActiveTrueOrderBySortOrderAscIdAsc())
                .thenReturn(List.of(staff(1L, "A"), staff(2L, "B"), staff(3L, "C"), staff(4L, "D")));
        when(publishedRepo.findByWorkDateBetween(TODAY, TODAY)).thenReturn(List.of(
                published(1L, "D"), published(2L, "D"), published(3L, "N"), published(4L, "L")));

        TodayVO today = service.get(CURRENT_MONTH).today();

        assertEquals(TODAY, today.date());
        assertEquals(2, today.dayCount());
        assertEquals(List.of("C"), today.night());
        assertEquals(List.of(), today.duty());
        assertEquals(List.of("D"), today.leave());
    }

    /** 值班 Z 与其余三类同路；休息 X、备班 B、没排班的人都不进任何一栏 */
    @Test
    void dutyIsListedAndOtherShiftsIgnored() {
        when(staffRepo.findByActiveTrueOrderBySortOrderAscIdAsc())
                .thenReturn(List.of(staff(1L, "A"), staff(2L, "B"), staff(3L, "C"), staff(4L, "D")));
        when(publishedRepo.findByWorkDateBetween(TODAY, TODAY)).thenReturn(List.of(
                published(1L, "Z"), published(2L, "X"), published(3L, "B"), published(4L, "Z")));

        TodayVO today = service.get(CURRENT_MONTH).today();

        assertEquals(0, today.dayCount());
        assertEquals(List.of(), today.night());
        assertEquals(List.of("A", "D"), today.duty());
        assertEquals(List.of(), today.leave());
    }

    /** 姓名顺序跟人员排序走，不跟排班数据的返回顺序走 */
    @Test
    void namesFollowStaffOrderNotScheduleOrder() {
        when(staffRepo.findByActiveTrueOrderBySortOrderAscIdAsc())
                .thenReturn(List.of(staff(1L, "A"), staff(2L, "B"), staff(3L, "C")));
        when(publishedRepo.findByWorkDateBetween(TODAY, TODAY))
                .thenReturn(List.of(published(3L, "N"), published(1L, "N")));

        assertEquals(List.of("A", "C"), service.get(CURRENT_MONTH).today().night());
    }

    /** 用例 2：yearMonth 为 null 取当前月，且必须是已发布那一份（draft=false） */
    @Test
    void nullYearMonthFallsBackToCurrentMonthPublishedOnly() {
        service.get(null);

        verify(query).getMonth(CURRENT_MONTH, false);
    }

    /** 空串同 null 一样取当前月；传了就原样透传，格式不对由 getMonth 抛 1500 */
    @Test
    void blankYearMonthFallsBackAndExplicitMonthPassesThrough() {
        service.get("");
        service.get("   ");
        service.get("2026-11");

        verify(query, times(2)).getMonth(CURRENT_MONTH, false);
        verify(query).getMonth("2026-11", false);
    }

    /** 今日概况只查今天这一天，不顺手把整月快照捞一遍 */
    @Test
    void todayQueriesOnlyToday() {
        service.get(CURRENT_MONTH);

        verify(publishedRepo).findByWorkDateBetween(TODAY, TODAY);
    }

    /** 月视图原样带出 query 的结果，大屏不复制一份 */
    @Test
    void monthComesFromQueryUntouched() {
        MonthScheduleVO month = month(CURRENT_MONTH);
        when(query.getMonth(CURRENT_MONTH, false)).thenReturn(month);

        ScreenVO vo = service.get(CURRENT_MONTH);

        assertSame(month, vo.month());
        assertEquals(TODAY, vo.today().date());
        assertTrue(vo.today().night().isEmpty());
    }
}
