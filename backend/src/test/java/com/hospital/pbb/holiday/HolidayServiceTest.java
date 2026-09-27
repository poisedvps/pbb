package com.hospital.pbb.holiday;

import com.hospital.pbb.common.BizException;
import com.hospital.pbb.holiday.dto.HolidayRequest;
import com.hospital.pbb.oplog.OpAction;
import com.hospital.pbb.oplog.OpLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Mockito 校验 HolidayService 的六条业务规则：不跨年、不重叠、year 由开始日期算、整年复制。 */
class HolidayServiceTest {

    private static final LocalDate NATIONAL_DAY_START = LocalDate.of(2026, 10, 1);
    private static final LocalDate NATIONAL_DAY_END = LocalDate.of(2026, 10, 7);

    private HolidayRepository repo;
    private OpLogService opLog;
    private HolidayService service;

    @BeforeEach
    void setUp() {
        repo = mock(HolidayRepository.class);
        opLog = mock(OpLogService.class);
        when(repo.save(any(Holiday.class))).thenAnswer(inv -> inv.getArgument(0));
        service = new HolidayService(repo, opLog);
    }

    private static HolidayRequest request(String name, String start, String end,
                                         HolidayType type, String remark) {
        return new HolidayRequest(name, LocalDate.parse(start), LocalDate.parse(end), type, remark);
    }

    private static Holiday row(long id, String name, String start, String end, HolidayType type) {
        Holiday holiday = new Holiday();
        holiday.setId(id);
        holiday.setYear(LocalDate.parse(start).getYear());
        holiday.setName(name);
        holiday.setStartDate(LocalDate.parse(start));
        holiday.setEndDate(LocalDate.parse(end));
        holiday.setType(type);
        return holiday;
    }

    /** 让 [start, end] 区间查不到任何已有记录，即"没有重叠" */
    private void noOverlap() {
        when(repo.findOverlapping(any(LocalDate.class), any(LocalDate.class))).thenReturn(List.of());
    }

    private Holiday captureSave() {
        ArgumentCaptor<Holiday> captor = ArgumentCaptor.forClass(Holiday.class);
        verify(repo).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void createUsesStartYearAndKeepsFields() {
        noOverlap();

        Holiday saved = captureSaveAfterCreate(request("国庆节", "2026-10-01", "2026-10-07",
                HolidayType.HOLIDAY, "国庆七天假"));

        assertEquals(2026, saved.getYear());
        assertEquals("国庆节", saved.getName());
        assertEquals(NATIONAL_DAY_START, saved.getStartDate());
        assertEquals(NATIONAL_DAY_END, saved.getEndDate());
        assertEquals(HolidayType.HOLIDAY, saved.getType());
        assertEquals("国庆七天假", saved.getRemark());
        verify(opLog).record(OpAction.CREATE_HOLIDAY, "国庆节", "2026-10-01~2026-10-07 HOLIDAY");
    }

    @Test
    void createReturnsTheSavedRow() {
        noOverlap();
        Holiday created = service.create(request("国庆节", "2026-10-01", "2026-10-07",
                HolidayType.HOLIDAY, null));
        assertSame(captureSave(), created);
    }

    /** 前端清空备注框传的是空串，存进去必须是 null，不能是空白字符串 */
    @Test
    void createStoresNullForBlankRemark() {
        noOverlap();
        Holiday saved = captureSaveAfterCreate(request("元旦", "2026-01-01", "2026-01-03",
                HolidayType.HOLIDAY, "   "));
        assertNull(saved.getRemark());
    }

    @Test
    void createRejectsEndDateBeforeStartDate() {
        BizException e = assertThrows(BizException.class, () -> service.create(request("国庆节",
                "2026-10-01", "2026-09-30", HolidayType.HOLIDAY, null)));

        assertEquals(1401, e.getCode());
        assertEquals("结束日期不能早于开始日期", e.getMessage());
        verify(repo, never()).save(any());
    }

    @Test
    void createRejectsCrossYearRange() {
        BizException e = assertThrows(BizException.class, () -> service.create(request("元旦",
                "2026-12-31", "2027-01-01", HolidayType.HOLIDAY, null)));

        assertEquals(1402, e.getCode());
        assertEquals("节假日不能跨年，请分两条录入", e.getMessage());
        verify(repo, never()).save(any());
    }

    @Test
    void createRejectsOverlappingDatesAndNamesTheConflict() {
        when(repo.findOverlapping(LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 8)))
                .thenReturn(List.of(row(7L, "国庆节", "2026-10-01", "2026-10-07", HolidayType.HOLIDAY)));

        BizException e = assertThrows(BizException.class, () -> service.create(request("调休",
                "2026-10-05", "2026-10-08", HolidayType.WORKDAY, null)));

        assertEquals(1403, e.getCode());
        assertTrue(e.getMessage().contains("国庆节"), () -> "错误信息里要指出冲突的是哪一条，实际：" + e.getMessage());
        verify(repo, never()).save(any());
    }

    /** 修改自己时日期区间必然与自身重合，排除 selfId 之后才算没有重叠 */
    @Test
    void updateSelfWithSameDatesIsAllowed() {
        Holiday existing = row(7L, "国庆节", "2026-10-01", "2026-10-07", HolidayType.HOLIDAY);
        when(repo.findById(7L)).thenReturn(Optional.of(existing));
        when(repo.findOverlapping(NATIONAL_DAY_START, NATIONAL_DAY_END)).thenReturn(List.of(existing));

        Holiday updated = service.update(7L, request("国庆节", "2026-10-01", "2026-10-07",
                HolidayType.HOLIDAY, "按国务院安排"));

        assertEquals(2026, updated.getYear());
        assertEquals("按国务院安排", updated.getRemark());
        assertSame(existing, updated);
        verify(opLog).record(OpAction.UPDATE_HOLIDAY, "国庆节", "2026-10-01~2026-10-07 HOLIDAY");
    }

    @Test
    void updateRecomputesYearAndRejectsAnotherRowsDates() {
        when(repo.findById(7L)).thenReturn(Optional.of(row(7L, "国庆节", "2026-10-01", "2026-10-07",
                HolidayType.HOLIDAY)));
        when(repo.findOverlapping(LocalDate.of(2027, 10, 1), LocalDate.of(2027, 10, 3)))
                .thenReturn(List.of(row(9L, "中秋", "2027-10-02", "2027-10-04", HolidayType.HOLIDAY)));

        BizException e = assertThrows(BizException.class, () -> service.update(7L, request("国庆节",
                "2027-10-01", "2027-10-03", HolidayType.HOLIDAY, null)));

        assertEquals(1403, e.getCode());
        assertTrue(e.getMessage().contains("中秋"));
    }

    @Test
    void updateMissingIdReturns1400() {
        when(repo.findById(999L)).thenReturn(Optional.empty());

        BizException e = assertThrows(BizException.class, () -> service.update(999L, request("国庆节",
                "2026-10-01", "2026-10-07", HolidayType.HOLIDAY, null)));

        assertEquals(1400, e.getCode());
        assertEquals("节假日不存在", e.getMessage());
    }

    @Test
    void copyShiftsDatesToTargetYearAndReturnsCount() {
        when(repo.findByYearOrderByStartDateAsc(2027)).thenReturn(List.of());
        when(repo.findByYearOrderByStartDateAsc(2026)).thenReturn(List.of(
                row(1L, "国庆节", "2026-10-01", "2026-10-07", HolidayType.HOLIDAY),
                row(2L, "节后上班", "2026-10-10", "2026-10-10", HolidayType.WORKDAY)));

        int copied = service.copy(2026, 2027);

        assertEquals(2, copied);
        ArgumentCaptor<Holiday> captor = ArgumentCaptor.forClass(Holiday.class);
        verify(repo, times(2)).save(captor.capture());
        List<Holiday> saved = captor.getAllValues();

        assertEquals(LocalDate.of(2027, 10, 1), saved.get(0).getStartDate());
        assertEquals(LocalDate.of(2027, 10, 7), saved.get(0).getEndDate());
        assertEquals(LocalDate.of(2027, 10, 10), saved.get(1).getStartDate());
        assertEquals(LocalDate.of(2027, 10, 10), saved.get(1).getEndDate());
        for (Holiday holiday : saved) {
            assertEquals(2027, holiday.getYear());
            assertNull(holiday.getId(), "复制出来的是新记录，不能带上源记录的 id");
            assertTrue(holiday.getRemark().contains("从2026年复制"), holiday.getRemark());
        }
        assertEquals("国庆节", saved.get(0).getName());
        assertEquals(HolidayType.HOLIDAY, saved.get(0).getType());
        assertEquals("节后上班", saved.get(1).getName());
        assertEquals(HolidayType.WORKDAY, saved.get(1).getType());
        verify(opLog).record(OpAction.COPY_HOLIDAY, "2027年", "从2026年复制2条");
    }

    @Test
    void copyRejectsWhenTargetYearNotEmpty() {
        when(repo.findByYearOrderByStartDateAsc(2027))
                .thenReturn(List.of(row(3L, "元旦", "2027-01-01", "2027-01-03", HolidayType.HOLIDAY)));

        BizException e = assertThrows(BizException.class, () -> service.copy(2026, 2027));

        assertEquals(1404, e.getCode());
        assertEquals("2027 年已有节假日，不能复制", e.getMessage());
        verify(repo, never()).save(any());
    }

    @Test
    void copyRejectsWhenSourceYearEmpty() {
        when(repo.findByYearOrderByStartDateAsc(2026)).thenReturn(List.of());
        when(repo.findByYearOrderByStartDateAsc(2025)).thenReturn(List.of());

        BizException e = assertThrows(BizException.class, () -> service.copy(2025, 2026));

        assertEquals(1405, e.getCode());
        assertEquals("2025 年没有节假日可复制", e.getMessage());
        verify(repo, never()).save(any());
    }

    @Test
    void deleteMissingIdReturns1400() {
        when(repo.findById(999L)).thenReturn(Optional.empty());

        BizException e = assertThrows(BizException.class, () -> service.delete(999L));

        assertEquals(1400, e.getCode());
        assertEquals("节假日不存在", e.getMessage());
        verify(repo, never()).delete(any());
    }

    @Test
    void deleteExistingRowLogsTheAction() {
        Holiday existing = row(7L, "国庆节", "2026-10-01", "2026-10-07", HolidayType.HOLIDAY);
        when(repo.findById(7L)).thenReturn(Optional.of(existing));

        service.delete(7L);

        verify(repo).delete(existing);
        verify(opLog).record(OpAction.DELETE_HOLIDAY, "国庆节", "2026-10-01~2026-10-07 HOLIDAY");
    }

    @Test
    void listReturnsRowsOfThatYearInDateOrder() {
        List<Holiday> rows = List.of(row(1L, "元旦", "2026-01-01", "2026-01-03", HolidayType.HOLIDAY));
        when(repo.findByYearOrderByStartDateAsc(eq(2026))).thenReturn(rows);

        assertSame(rows, service.list(2026));
    }

    private Holiday captureSaveAfterCreate(HolidayRequest req) {
        service.create(req);
        return captureSave();
    }
}
