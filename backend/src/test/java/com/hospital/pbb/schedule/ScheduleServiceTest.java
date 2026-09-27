package com.hospital.pbb.schedule;

import com.hospital.pbb.common.BizException;
import com.hospital.pbb.holiday.Holiday;
import com.hospital.pbb.holiday.HolidayType;
import com.hospital.pbb.oplog.OpAction;
import com.hospital.pbb.oplog.OpLogService;
import com.hospital.pbb.schedule.dto.CellVO;
import com.hospital.pbb.schedule.dto.GenerateResultVO;
import com.hospital.pbb.schedule.dto.PublishResultVO;
import com.hospital.pbb.schedule.dto.UpdateEntryRequest;
import com.hospital.pbb.shift.ShiftType;
import com.hospital.pbb.shift.ShiftTypeRepository;
import com.hospital.pbb.staff.Staff;
import com.hospital.pbb.staff.StaffRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 按规则生成与单元格修改（任务单 M2-04 验收标准）：Mockito 打桩仓库与查询服务，
 * 重点验三条口径——手工格子不被生成覆盖、任何写入都把月份打回 DRAFT、写之前必须先取当月锁。
 */
class ScheduleServiceTest {

    private static final String YM = "2026-10";
    private static final int LOCK_KEY = 202610;
    private static final LocalDate START = LocalDate.of(2026, 10, 1);
    private static final LocalDate END = LocalDate.of(2026, 10, 31);
    private static final LocalDate D5 = LocalDate.of(2026, 10, 5);
    private static final LocalDate D10 = LocalDate.of(2026, 10, 10);
    private static final Long OPERATOR = 7L;
    /** 固定的"当前时间"：2026-09-30 08:00 +08:00 */
    private static final OffsetDateTime NOW =
            OffsetDateTime.ofInstant(Instant.parse("2026-09-30T00:00:00Z"), ZoneId.of("Asia/Shanghai"));

    private ScheduleMonthRepository monthRepo;
    private ScheduleEntryRepository entryRepo;
    private SchedulePublishedEntryRepository publishedRepo;
    private StaffRepository staffRepo;
    private ShiftTypeRepository shiftRepo;
    private ScheduleQueryService query;
    private OpLogService opLog;
    private ScheduleService service;

    @BeforeEach
    void setUp() {
        monthRepo = mock(ScheduleMonthRepository.class);
        entryRepo = mock(ScheduleEntryRepository.class);
        publishedRepo = mock(SchedulePublishedEntryRepository.class);
        staffRepo = mock(StaffRepository.class);
        shiftRepo = mock(ShiftTypeRepository.class);
        query = mock(ScheduleQueryService.class);
        opLog = mock(OpLogService.class);
        service = new ScheduleService(monthRepo, entryRepo, publishedRepo, staffRepo, shiftRepo, query, opLog,
                Clock.fixed(Instant.parse("2026-09-30T00:00:00Z"), ZoneId.of("Asia/Shanghai")));

        // 默认：1 名可排班人员、整月无节假日、库里没有任何草稿、schedule_month 里也没有这一月
        when(staffRepo.findByActiveTrueOrderBySortOrderAscIdAsc()).thenReturn(List.of(staff(1L, true, true)));
        when(staffRepo.findById(any())).thenAnswer(inv -> inv.getArgument(0).equals(1L)
                ? Optional.of(staff(1L, true, true)) : Optional.empty());
        when(query.calendar(START, END)).thenReturn(new RuleCalendar(List.of()));
        when(entryRepo.findByWorkDateBetween(any(), any())).thenReturn(List.of());
        when(entryRepo.findByStaffIdAndWorkDate(any(), any())).thenReturn(Optional.empty());
        when(monthRepo.findById(anyString())).thenReturn(Optional.empty());
        when(entryRepo.save(any(ScheduleEntry.class))).thenAnswer(inv -> inv.getArgument(0));
        when(monthRepo.save(any(ScheduleMonth.class))).thenAnswer(inv -> inv.getArgument(0));
        when(shiftRepo.findById(anyString())).thenAnswer(inv -> Optional.of(shift((String) inv.getArgument(0), true)));
    }

    // ---------- 构造测试数据 ----------

    private static Staff staff(Long id, boolean schedulable, boolean active) {
        Staff staff = new Staff();
        staff.setId(id);
        staff.setEmpNo("E" + id);
        staff.setName("人员" + id);
        staff.setPosition("护士");
        staff.setSchedulable(schedulable);
        staff.setActive(active);
        return staff;
    }

    private static ShiftType shift(String code, boolean enabled) {
        ShiftType shift = new ShiftType();
        shift.setCode(code);
        shift.setName("班次" + code);
        shift.setEnabled(enabled);
        return shift;
    }

    private static ScheduleEntry draft(Long staffId, LocalDate date, String shiftCode, boolean manual) {
        ScheduleEntry entry = new ScheduleEntry();
        entry.setStaffId(staffId);
        entry.setWorkDate(date);
        entry.setShiftCode(shiftCode);
        entry.setManual(manual);
        return entry;
    }

    private static ScheduleMonth month(ScheduleStatus status, int version) {
        ScheduleMonth month = new ScheduleMonth();
        month.setYearMonth(YM);
        month.setStatus(status);
        month.setVersion(version);
        return month;
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

    /** 生成是整月写入，save 会被调用很多次，统一按 "人员|日期" 收拢后再断言 */
    private Map<String, ScheduleEntry> savedEntries() {
        ArgumentCaptor<ScheduleEntry> captor = ArgumentCaptor.forClass(ScheduleEntry.class);
        verify(entryRepo, atLeastOnce()).save(captor.capture());
        Map<String, ScheduleEntry> saved = new HashMap<>();
        for (ScheduleEntry entry : captor.getAllValues()) {
            saved.put(entry.getStaffId() + "|" + entry.getWorkDate(), entry);
        }
        return saved;
    }

    private ScheduleMonth savedMonth() {
        ArgumentCaptor<ScheduleMonth> captor = ArgumentCaptor.forClass(ScheduleMonth.class);
        verify(monthRepo).save(captor.capture());
        return captor.getValue();
    }

    /** 发布是整月重写快照，saveAll 应恰好被调用一次 */
    private List<SchedulePublishedEntry> savedSnapshots() {
        ArgumentCaptor<List<SchedulePublishedEntry>> captor = ArgumentCaptor.captor();
        verify(publishedRepo).saveAll(captor.capture());
        return captor.getValue();
    }

    // ---------- generate ----------

    /** 用例：整月无草稿、无节假日 → 31 格全按规则写入，工作日 D、周六 X，月份状态为 DRAFT */
    @Test
    void generateFillsWholeMonthWithRuleDefaults() {
        GenerateResultVO result = service.generate(YM, OPERATOR);

        assertEquals(new GenerateResultVO(31, 0), result);
        Map<String, ScheduleEntry> saved = savedEntries();
        assertEquals(31, saved.size());
        assertEquals("D", saved.get("1|" + D5).getShiftCode());      // 10-05 周一
        assertEquals("X", saved.get("1|" + D10).getShiftCode());     // 10-10 周六
        assertFalse(saved.get("1|" + D5).isManual());
        assertEquals(OPERATOR, saved.get("1|" + D5).getUpdatedBy());
        assertEquals(NOW, saved.get("1|" + D5).getUpdatedAt());
        assertEquals(ScheduleStatus.DRAFT, savedMonth().getStatus());
    }

    /** 用例：10-05 已被科长手工改成 N → 跳过不覆盖，返回 (30, 1) */
    @Test
    void generateKeepsManualCellsUntouched() {
        ScheduleEntry manual = draft(1L, D5, "N", true);
        when(entryRepo.findByWorkDateBetween(START, END)).thenReturn(List.of(manual));

        GenerateResultVO result = service.generate(YM, OPERATOR);

        assertEquals(new GenerateResultVO(30, 1), result);
        // 跳过的格子既不重写也不保存，其余 30 格照写
        assertEquals(30, savedEntries().size());
        assertEquals("N", manual.getShiftCode());
        assertTrue(manual.isManual());
        assertNull(manual.getUpdatedBy());
        assertFalse(savedEntries().containsKey("1|" + D5));
        assertEquals("D", savedEntries().get("1|" + LocalDate.of(2026, 10, 6)).getShiftCode());
    }

    /** 用例：非手工的旧草稿按规则重算，不是保留原值 */
    @Test
    void generateRewritesNonManualCells() {
        when(entryRepo.findByWorkDateBetween(START, END))
                .thenReturn(List.of(draft(1L, D5, "N", false)));

        service.generate(YM, OPERATOR);

        ScheduleEntry entry = savedEntries().get("1|" + D5);
        assertEquals("D", entry.getShiftCode());
        assertFalse(entry.isManual());
    }

    /** 用例：已发布的月份重新生成后状态回到 DRAFT，version 不动（发布是 M2-05 的事） */
    @Test
    void generateResetsPublishedMonthToDraft() {
        when(monthRepo.findById(YM)).thenReturn(Optional.of(month(ScheduleStatus.PUBLISHED, 3)));

        service.generate(YM, OPERATOR);

        ScheduleMonth saved = savedMonth();
        assertEquals(ScheduleStatus.DRAFT, saved.getStatus());
        assertEquals(3, saved.getVersion());
    }

    /** 节假日按规则日历算：国庆当天是 X，不是按周六日判 */
    @Test
    void generateFollowsRuleCalendarForHolidays() {
        when(query.calendar(START, END)).thenAnswer(inv ->
                new RuleCalendar(List.of(holiday("国庆节", "2026-10-01", "2026-10-07", HolidayType.HOLIDAY))));

        service.generate(YM, OPERATOR);

        Map<String, ScheduleEntry> saved = savedEntries();
        assertEquals("X", saved.get("1|" + START).getShiftCode());       // 10-01 周四但放假
        assertEquals("D", saved.get("1|" + LocalDate.of(2026, 10, 8)).getShiftCode());
    }

    /** 停用、不参与排班的人员不铺格子 */
    @Test
    void generateSkipsStaffNotSchedulable() {
        when(staffRepo.findByActiveTrueOrderBySortOrderAscIdAsc())
                .thenReturn(List.of(staff(1L, true, true), staff(2L, false, true)));

        GenerateResultVO result = service.generate(YM, OPERATOR);

        assertEquals(new GenerateResultVO(31, 0), result);
        assertEquals(31, savedEntries().size());
    }

    /** 生成留痕：target 是月份，detail 是写入与跳过格数 */
    @Test
    void generateRecordsOpLog() {
        service.generate(YM, OPERATOR);

        verify(opLog).record(OpAction.GENERATE_SCHEDULE, YM, "生成31格，跳过手工0格");
    }

    /** 用例：写之前必须先取当月 advisory lock，锁在 lockMonth(202610) 之后才允许落库 */
    @Test
    void generateTakesMonthLockBeforeSaving() {
        service.generate(YM, OPERATOR);

        InOrder order = inOrder(monthRepo, entryRepo);
        order.verify(monthRepo).lockMonth(LOCK_KEY);
        // 整月写入，save 不止一次，只验它在锁之后
        order.verify(entryRepo, atLeastOnce()).save(any(ScheduleEntry.class));
    }

    // ---------- updateEntry ----------

    /** 用例：10-05 原本是规则默认 D，手工改成 N → 返回 (N, true, null)，日志 detail 为 "D → N" */
    @Test
    void updateEntryMarksCellManualAndLogsOldAndNewCode() {
        when(entryRepo.findByStaffIdAndWorkDate(1L, D5))
                .thenReturn(Optional.of(draft(1L, D5, "D", false)));

        CellVO cell = service.updateEntry(YM, new UpdateEntryRequest(1L, D5, "N", null), OPERATOR);

        assertEquals(new CellVO("N", true, null), cell);
        ArgumentCaptor<ScheduleEntry> captor = ArgumentCaptor.forClass(ScheduleEntry.class);
        verify(entryRepo).save(captor.capture());
        ScheduleEntry saved = captor.getValue();
        assertEquals("N", saved.getShiftCode());
        assertTrue(saved.isManual());
        assertNull(saved.getRemark());
        assertEquals(OPERATOR, saved.getUpdatedBy());
        assertEquals(NOW, saved.getUpdatedAt());
        verify(opLog).record(OpAction.UPDATE_SCHEDULE, "人员1 10-05", "D → N");
    }

    /** 用例：shiftCode=null → 恢复规则默认（10-10 是周六，默认 X），manual 摘掉 */
    @Test
    void updateEntryWithNullCodeRestoresRuleDefault() {
        when(entryRepo.findByStaffIdAndWorkDate(1L, D10))
                .thenReturn(Optional.of(draft(1L, D10, "N", true)));

        CellVO cell = service.updateEntry(YM, new UpdateEntryRequest(1L, D10, null, "顺延"), OPERATOR);

        assertEquals(new CellVO("X", false, "顺延"), cell);
        ArgumentCaptor<ScheduleEntry> captor = ArgumentCaptor.forClass(ScheduleEntry.class);
        verify(entryRepo).save(captor.capture());
        assertFalse(captor.getValue().isManual());
        assertEquals("顺延", captor.getValue().getRemark());
        verify(opLog).record(OpAction.UPDATE_SCHEDULE, "人员1 10-10", "N → X");
    }

    /** 空备注存 null，不能存空串 */
    @Test
    void updateEntryStoresNullRemarkForEmptyString() {
        service.updateEntry(YM, new UpdateEntryRequest(1L, D5, "N", ""), OPERATOR);

        ArgumentCaptor<ScheduleEntry> captor = ArgumentCaptor.forClass(ScheduleEntry.class);
        verify(entryRepo).save(captor.capture());
        assertNull(captor.getValue().getRemark());
    }

    /** 库里没有这个格子时新建一条，日志里的旧 code 为空 */
    @Test
    void updateEntryCreatesMissingCellAndLogsEmptyOldCode() {
        CellVO cell = service.updateEntry(YM, new UpdateEntryRequest(1L, D5, "Z", null), OPERATOR);

        assertEquals(new CellVO("Z", true, null), cell);
        ArgumentCaptor<ScheduleEntry> captor = ArgumentCaptor.forClass(ScheduleEntry.class);
        verify(entryRepo).save(captor.capture());
        ScheduleEntry saved = captor.getValue();
        assertEquals(1L, saved.getStaffId());
        assertEquals(D5, saved.getWorkDate());
        verify(opLog).record(OpAction.UPDATE_SCHEDULE, "人员1 10-05", " → Z");
    }

    /** 用例：改完格子月份状态回到 DRAFT */
    @Test
    void updateEntryResetsMonthToDraft() {
        when(monthRepo.findById(YM)).thenReturn(Optional.of(month(ScheduleStatus.PUBLISHED, 2)));

        service.updateEntry(YM, new UpdateEntryRequest(1L, D5, "N", null), OPERATOR);

        assertEquals(ScheduleStatus.DRAFT, savedMonth().getStatus());
        // 生成与改格子都不碰 version，那是发布（M2-05）维护的字段
        assertEquals(2, savedMonth().getVersion());
    }

    /** 从没生成过的月份改格子时补一条 schedule_month，version 从 0 起 */
    @Test
    void updateEntryCreatesMonthRowWhenAbsent() {
        service.updateEntry(YM, new UpdateEntryRequest(1L, D5, "N", null), OPERATOR);

        ScheduleMonth saved = savedMonth();
        assertEquals(YM, saved.getYearMonth());
        assertEquals(ScheduleStatus.DRAFT, saved.getStatus());
        assertEquals(0, saved.getVersion());
    }

    /** 用例：日期跨到 11 月 → 1503，且一行都不写 */
    @Test
    void updateEntryRejectsDateOutsideMonth() {
        BizException e = assertThrows(BizException.class, () ->
                service.updateEntry(YM, new UpdateEntryRequest(1L, LocalDate.of(2026, 11, 1), "N", null), OPERATOR));

        assertEquals(1503, e.getCode());
        verify(entryRepo, never()).save(any(ScheduleEntry.class));
        verify(monthRepo, never()).save(any(ScheduleMonth.class));
        verify(opLog, never()).record(anyString(), anyString(), anyString());
    }

    /** 用例：人员不参与排班 → 1501（不存在、已停用同一条错误码） */
    @Test
    void updateEntryRejectsStaffNotSchedulable() {
        when(staffRepo.findById(1L)).thenReturn(Optional.of(staff(1L, false, true)));

        BizException e = assertThrows(BizException.class, () ->
                service.updateEntry(YM, new UpdateEntryRequest(1L, D5, "N", null), OPERATOR));

        assertEquals(1501, e.getCode());
        verify(entryRepo, never()).save(any(ScheduleEntry.class));
    }

    @Test
    void updateEntryRejectsUnknownOrInactiveStaff() {
        when(staffRepo.findById(99L)).thenReturn(Optional.empty());
        assertEquals(1501, assertThrows(BizException.class, () ->
                service.updateEntry(YM, new UpdateEntryRequest(99L, D5, "N", null), OPERATOR)).getCode());

        when(staffRepo.findById(2L)).thenReturn(Optional.of(staff(2L, true, false)));
        assertEquals(1501, assertThrows(BizException.class, () ->
                service.updateEntry(YM, new UpdateEntryRequest(2L, D5, "N", null), OPERATOR)).getCode());
    }

    /** 用例：班次不存在 → 1502 */
    @Test
    void updateEntryRejectsUnknownShiftCode() {
        when(shiftRepo.findById("Q")).thenReturn(Optional.empty());

        BizException e = assertThrows(BizException.class, () ->
                service.updateEntry(YM, new UpdateEntryRequest(1L, D5, "Q", null), OPERATOR));

        assertEquals(1502, e.getCode());
        verify(entryRepo, never()).save(any(ScheduleEntry.class));
    }

    /** 已停用的班次同样不能排 → 1502 */
    @Test
    void updateEntryRejectsDisabledShiftCode() {
        when(shiftRepo.findById("L")).thenReturn(Optional.of(shift("L", false)));

        BizException e = assertThrows(BizException.class, () ->
                service.updateEntry(YM, new UpdateEntryRequest(1L, D5, "L", null), OPERATOR));

        assertEquals(1502, e.getCode());
    }

    /** 月份格式不对 → 1500，锁都不取 */
    @Test
    void updateEntryRejectsBadMonthFormat() {
        BizException e = assertThrows(BizException.class, () ->
                service.updateEntry("202610", new UpdateEntryRequest(1L, D5, "N", null), OPERATOR));

        assertEquals(1500, e.getCode());
        verify(monthRepo, never()).lockMonth(LOCK_KEY);
    }

    /** 用例：改格子同样要先取锁，再写库 */
    @Test
    void updateEntryTakesMonthLockBeforeSaving() {
        service.updateEntry(YM, new UpdateEntryRequest(1L, D5, "N", null), OPERATOR);

        InOrder order = inOrder(monthRepo, entryRepo);
        order.verify(monthRepo).lockMonth(LOCK_KEY);
        order.verify(entryRepo, times(1)).save(any(ScheduleEntry.class));
    }

    /** 生成、改格子都只动草稿表，已发布快照一律不碰（发布是 M2-05） */
    @Test
    void writeMethodsNeverTouchPublishedSnapshot() {
        service.generate(YM, OPERATOR);
        service.updateEntry(YM, new UpdateEntryRequest(1L, D5, "N", null), OPERATOR);

        verify(publishedRepo, never()).save(any());
        verify(publishedRepo, never()).saveAll(any());
        verify(publishedRepo, never()).deleteByWorkDateRange(any(), any());
        verify(entryRepo, never()).delete(any());
        verify(opLog, never()).record(eq(OpAction.PUBLISH_SCHEDULE), anyString(), anyString());
    }

    // ---------- publish ----------

    /** 10-05 带备注的草稿、10-10 手工改过的草稿，发布后这两列都要原样带走 */
    private static List<ScheduleEntry> threeDrafts() {
        ScheduleEntry withRemark = draft(1L, D5, "N", true);
        withRemark.setRemark("顶班");
        return List.of(withRemark, draft(2L, D5, "D", false), draft(1L, D10, "X", false));
    }

    /** 用例：草稿 3 条、schedule_month 里无记录 → 返回 (1, 3)，快照按草稿逐格复制，不写草稿表 */
    @Test
    void publishCopiesDraftIntoSnapshotAndReturnsFirstVersion() {
        when(entryRepo.findByWorkDateBetween(START, END)).thenReturn(threeDrafts());

        PublishResultVO result = service.publish(YM, OPERATOR);

        assertEquals(new PublishResultVO(1, 3), result);
        List<SchedulePublishedEntry> saved = savedSnapshots();
        assertEquals(3, saved.size());
        SchedulePublishedEntry first = saved.get(0);
        assertEquals(1L, first.getStaffId());
        assertEquals(D5, first.getWorkDate());
        assertEquals("N", first.getShiftCode());
        assertEquals("顶班", first.getRemark());
        assertEquals(1, first.getVersion());
        assertEquals(2L, saved.get(1).getStaffId());
        assertEquals("D", saved.get(1).getShiftCode());
        assertNull(saved.get(1).getRemark());
        assertEquals(D10, saved.get(2).getWorkDate());
        // 发布只读草稿，不重写格子
        verify(entryRepo, never()).save(any(ScheduleEntry.class));
    }

    /** 用例：先删旧快照再存新快照，且都在取到当月锁之后 */
    @Test
    void publishDeletesOldSnapshotBeforeSavingNewOne() {
        when(entryRepo.findByWorkDateBetween(START, END)).thenReturn(threeDrafts());

        service.publish(YM, OPERATOR);

        InOrder order = inOrder(monthRepo, publishedRepo);
        order.verify(monthRepo).lockMonth(LOCK_KEY);
        order.verify(publishedRepo).deleteByWorkDateRange(START, END);
        order.verify(publishedRepo).saveAll(any());
    }

    /** 用例：从未发布过的月份置为 PUBLISHED、version=1、publishedAt/publishedBy 写入 */
    @Test
    void publishMarksMonthPublishedWithOperatorAndTime() {
        when(entryRepo.findByWorkDateBetween(START, END)).thenReturn(threeDrafts());

        service.publish(YM, OPERATOR);

        ScheduleMonth saved = savedMonth();
        assertEquals(YM, saved.getYearMonth());
        assertEquals(ScheduleStatus.PUBLISHED, saved.getStatus());
        assertEquals(1, saved.getVersion());
        assertEquals(OPERATOR, saved.getPublishedBy());
        assertEquals(NOW, saved.getPublishedAt());
        verify(opLog).record(OpAction.PUBLISH_SCHEDULE, YM, "版本 v1，共3格");
    }

    /** 用例：月份 version=2 时再次发布 → 返回 version=3，快照上的 version 跟着走 */
    @Test
    void publishIncrementsExistingVersion() {
        when(monthRepo.findById(YM)).thenReturn(Optional.of(month(ScheduleStatus.DRAFT, 2)));
        when(entryRepo.findByWorkDateBetween(START, END)).thenReturn(threeDrafts());

        PublishResultVO result = service.publish(YM, OPERATOR);

        assertEquals(3, result.version());
        assertEquals(3, result.count());
        assertEquals(3, savedMonth().getVersion());
        assertEquals(3, savedSnapshots().get(0).getVersion());
        verify(opLog).record(OpAction.PUBLISH_SCHEDULE, YM, "版本 v3，共3格");
    }

    /** 用例：整月无草稿 → 1504，旧快照不能被误删 */
    @Test
    void publishRejectsMonthWithoutAnyDraft() {
        BizException e = assertThrows(BizException.class, () -> service.publish(YM, OPERATOR));

        assertEquals(1504, e.getCode());
        verify(publishedRepo, never()).deleteByWorkDateRange(any(), any());
        verify(publishedRepo, never()).saveAll(any());
        verify(monthRepo, never()).save(any(ScheduleMonth.class));
        verify(opLog, never()).record(anyString(), anyString(), anyString());
    }

    /** 用例：月份格式不对 → 1500，锁都不取 */
    @Test
    void publishRejectsBadMonthFormat() {
        BizException e = assertThrows(BizException.class, () -> service.publish("202610", OPERATOR));

        assertEquals(1500, e.getCode());
        verify(monthRepo, never()).lockMonth(LOCK_KEY);
        verify(publishedRepo, never()).deleteByWorkDateRange(any(), any());
    }
}
