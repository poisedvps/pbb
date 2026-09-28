package com.hospital.pbb.schedule;

import com.hospital.pbb.common.BizException;
import com.hospital.pbb.cycle.CycleTemplate;
import com.hospital.pbb.cycle.CycleTemplateRepository;
import com.hospital.pbb.holiday.Holiday;
import com.hospital.pbb.holiday.HolidayType;
import com.hospital.pbb.oplog.OpAction;
import com.hospital.pbb.oplog.OpLogService;
import com.hospital.pbb.schedule.dto.CellChange;
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
    /** 调班回写写进 remark 和日志 target 的那句话（任务单 M3-01） */
    private static final String SWAP_TARGET = "调班 TB-0001";
    private static final int NOV_LOCK_KEY = 202611;
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
    private CycleTemplateRepository templateRepo;
    private DutyPhoneWeekRepository dutyRepo;
    private DutyPhonePublishedRepository dutyPublishedRepo;
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
        templateRepo = mock(CycleTemplateRepository.class);
        dutyRepo = mock(DutyPhoneWeekRepository.class);
        dutyPublishedRepo = mock(DutyPhonePublishedRepository.class);
        service = new ScheduleService(monthRepo, entryRepo, publishedRepo, staffRepo, shiftRepo, query, opLog,
                Clock.fixed(Instant.parse("2026-09-30T00:00:00Z"), ZoneId.of("Asia/Shanghai")),
                templateRepo, dutyRepo, dutyPublishedRepo);

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

    private static SchedulePublishedEntry snapshot(Long staffId, LocalDate date, String shiftCode, int version) {
        SchedulePublishedEntry entry = new SchedulePublishedEntry();
        entry.setStaffId(staffId);
        entry.setWorkDate(date);
        entry.setShiftCode(shiftCode);
        entry.setVersion(version);
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

    /** 周一..周日的周期模板；days 必须恰好 7 个 */
    private static CycleTemplate template(long id, String name, boolean asDefault, String... days) {
        CycleTemplate template = new CycleTemplate();
        template.setId(id);
        template.setName(name);
        template.setDays(List.of(days));
        template.setDefaultTemplate(asDefault);
        return template;
    }

    /** 全白班模板：周一..周日全排 N */
    private static CycleTemplate allN(long id, String name) {
        return template(id, name, false, "N", "N", "N", "N", "N", "N", "N");
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

    /** 生成会先打回草稿、再补记模板 id，save 不止一次；取最后一次，那是最终落库的那条 */
    private ScheduleMonth savedMonth() {
        ArgumentCaptor<ScheduleMonth> captor = ArgumentCaptor.forClass(ScheduleMonth.class);
        verify(monthRepo, atLeastOnce()).save(captor.capture());
        List<ScheduleMonth> saved = captor.getAllValues();
        return saved.get(saved.size() - 1);
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
        GenerateResultVO result = service.generate(YM, null, OPERATOR);

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

        GenerateResultVO result = service.generate(YM, null, OPERATOR);

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

        service.generate(YM, null, OPERATOR);

        ScheduleEntry entry = savedEntries().get("1|" + D5);
        assertEquals("D", entry.getShiftCode());
        assertFalse(entry.isManual());
    }

    /** 用例：已发布的月份重新生成后状态回到 DRAFT，version 不动（发布是 M2-05 的事） */
    @Test
    void generateResetsPublishedMonthToDraft() {
        when(monthRepo.findById(YM)).thenReturn(Optional.of(month(ScheduleStatus.PUBLISHED, 3)));

        service.generate(YM, null, OPERATOR);

        ScheduleMonth saved = savedMonth();
        assertEquals(ScheduleStatus.DRAFT, saved.getStatus());
        assertEquals(3, saved.getVersion());
    }

    /** 节假日按规则日历算：国庆当天是 X，不是按周六日判 */
    @Test
    void generateFollowsRuleCalendarForHolidays() {
        when(query.calendar(START, END)).thenAnswer(inv ->
                new RuleCalendar(List.of(holiday("国庆节", "2026-10-01", "2026-10-07", HolidayType.HOLIDAY))));

        service.generate(YM, null, OPERATOR);

        Map<String, ScheduleEntry> saved = savedEntries();
        assertEquals("X", saved.get("1|" + START).getShiftCode());       // 10-01 周四但放假
        assertEquals("D", saved.get("1|" + LocalDate.of(2026, 10, 8)).getShiftCode());
    }

    /** 停用、不参与排班的人员不铺格子 */
    @Test
    void generateSkipsStaffNotSchedulable() {
        when(staffRepo.findByActiveTrueOrderBySortOrderAscIdAsc())
                .thenReturn(List.of(staff(1L, true, true), staff(2L, false, true)));

        GenerateResultVO result = service.generate(YM, null, OPERATOR);

        assertEquals(new GenerateResultVO(31, 0), result);
        assertEquals(31, savedEntries().size());
    }

    /** 生成留痕：target 是月份，detail 是“内置规则/模板【名称】，写入与跳过格数”（未指定模板且无默认模板时为内置规则） */
    @Test
    void generateRecordsOpLog() {
        service.generate(YM, null, OPERATOR);

        verify(opLog).record(OpAction.GENERATE_SCHEDULE, YM, "内置规则，生成31格，跳过手工0格");
    }

    /** 用例：写之前必须先取当月 advisory lock，锁在 lockMonth(202610) 之后才允许落库 */
    @Test
    void generateTakesMonthLockBeforeSaving() {
        service.generate(YM, null, OPERATOR);

        InOrder order = inOrder(monthRepo, entryRepo);
        order.verify(monthRepo).lockMonth(LOCK_KEY);
        // 整月写入，save 不止一次，只验它在锁之后
        order.verify(entryRepo, atLeastOnce()).save(any(ScheduleEntry.class));
    }

    // ---------- generate：按周期模板（任务单 M4-05）----------

    /** 用例：templateId=null 且有默认模板 [D,D,D,D,D,X,X] → 10-05 D、10-10 X，月份记下默认模板 id */
    @Test
    void generateUsesDefaultTemplateAndRecordsItsId() {
        when(templateRepo.findFirstByDefaultTemplateTrue())
                .thenReturn(Optional.of(template(1L, "做五休二", true, "D", "D", "D", "D", "D", "X", "X")));

        service.generate(YM, null, OPERATOR);

        Map<String, ScheduleEntry> saved = savedEntries();
        assertEquals("D", saved.get("1|" + D5).getShiftCode());    // 10-05 周一
        assertEquals("X", saved.get("1|" + D10).getShiftCode());   // 10-10 周六
        assertEquals(1L, savedMonth().getCycleTemplateId());
        assertEquals(ScheduleStatus.DRAFT, savedMonth().getStatus());
        verify(opLog).record(OpAction.GENERATE_SCHEDULE, YM, "模板【做五休二】，生成31格，跳过手工0格");
    }

    /** 用例：指定模板 2（全 N）→ 非节假日全为 N（含周六），节假日仍是 X，记下 cycleTemplateId=2 */
    @Test
    void generateUsesTemplateByIdAndKeepsHolidaysOff() {
        when(query.calendar(START, END)).thenAnswer(inv ->
                new RuleCalendar(List.of(holiday("国庆节", "2026-10-01", "2026-10-07", HolidayType.HOLIDAY))));
        when(templateRepo.findById(2L)).thenReturn(Optional.of(allN(2L, "全白班")));

        service.generate(YM, 2L, OPERATOR);

        Map<String, ScheduleEntry> saved = savedEntries();
        assertEquals(31, saved.size());
        assertEquals("N", saved.get("1|" + LocalDate.of(2026, 10, 8)).getShiftCode());  // 假期后的周四
        assertEquals("N", saved.get("1|" + D10).getShiftCode());   // 周六也排 N
        for (LocalDate date = START; !date.isAfter(LocalDate.of(2026, 10, 7)); date = date.plusDays(1)) {
            assertEquals("X", saved.get("1|" + date).getShiftCode(), date.toString());   // 国庆 7 天照旧休
        }
        assertEquals(2L, savedMonth().getCycleTemplateId());
        // 指定了模板就不再看默认模板
        verify(templateRepo, never()).findFirstByDefaultTemplateTrue();
    }

    /** 用例：指定的模板不存在 → 1507，一格也不写 */
    @Test
    void generateRejectsUnknownTemplateAndWritesNothing() {
        when(templateRepo.findById(99L)).thenReturn(Optional.empty());

        BizException e = assertThrows(BizException.class, () -> service.generate(YM, 99L, OPERATOR));

        assertEquals(1507, e.getCode());
        verify(entryRepo, never()).save(any(ScheduleEntry.class));
        verify(monthRepo, never()).save(any(ScheduleMonth.class));
        verify(opLog, never()).record(anyString(), anyString(), anyString());
    }

    /** 用例：模板里有已停用 / 不存在的班次 → 1502，整月不生成 */
    @Test
    void generateRejectsTemplateWithDisabledOrUnknownShift() {
        when(templateRepo.findById(2L)).thenReturn(Optional.of(
                template(2L, "含停用班", false, "D", "D", "D", "D", "D", "D", "B")));
        when(shiftRepo.findById("B")).thenReturn(Optional.of(shift("B", false)));

        BizException e = assertThrows(BizException.class, () -> service.generate(YM, 2L, OPERATOR));
        assertEquals(1502, e.getCode());

        when(templateRepo.findById(3L)).thenReturn(Optional.of(
                template(3L, "含不存在班", false, "D", "Q", "D", "D", "D", "D", "D")));
        when(shiftRepo.findById("Q")).thenReturn(Optional.empty());
        assertEquals(1502, assertThrows(BizException.class,
                () -> service.generate(YM, 3L, OPERATOR)).getCode());

        verify(entryRepo, never()).save(any(ScheduleEntry.class));
        verify(monthRepo, never()).save(any(ScheduleMonth.class));
    }

    /** 用例：没有默认模板且 templateId=null → 按内置规则生成，cycleTemplateId 置 null（连上次记的一并清掉） */
    @Test
    void generateWithoutAnyTemplateFallsBackToBuiltinRule() {
        ScheduleMonth recorded = month(ScheduleStatus.PUBLISHED, 1);
        recorded.setCycleTemplateId(2L);
        when(monthRepo.findById(YM)).thenReturn(Optional.of(recorded));
        when(templateRepo.findFirstByDefaultTemplateTrue()).thenReturn(Optional.empty());

        service.generate(YM, null, OPERATOR);

        Map<String, ScheduleEntry> saved = savedEntries();
        assertEquals("D", saved.get("1|" + D5).getShiftCode());
        assertEquals("X", saved.get("1|" + D10).getShiftCode());
        assertNull(savedMonth().getCycleTemplateId());
        verify(templateRepo, never()).findById(any());
    }

    /** 用例：模板只影响非手工格——手工改过的 N 格照样跳过，不会被模板值冲掉 */
    @Test
    void generateWithTemplateStillKeepsManualCells() {
        when(templateRepo.findFirstByDefaultTemplateTrue())
                .thenReturn(Optional.of(template(1L, "做五休二", true, "D", "D", "D", "D", "D", "X", "X")));
        when(entryRepo.findByWorkDateBetween(START, END)).thenReturn(List.of(draft(1L, D10, "Z", true)));

        GenerateResultVO result = service.generate(YM, null, OPERATOR);

        assertEquals(new GenerateResultVO(30, 1), result);
        assertFalse(savedEntries().containsKey("1|" + D10));
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

    /** 该月记的是全 N 模板 → 10-05 恢复默认得到模板值 N（不是内置规则的 D），manual=false */
    @Test
    void updateEntryWithNullCodeRestoresMonthTemplateDefault() {
        ScheduleMonth recorded = month(ScheduleStatus.DRAFT, 1);
        recorded.setCycleTemplateId(2L);
        when(monthRepo.findById(YM)).thenReturn(Optional.of(recorded));
        when(templateRepo.findById(2L)).thenReturn(Optional.of(allN(2L, "全白班")));
        when(entryRepo.findByStaffIdAndWorkDate(1L, D5))
                .thenReturn(Optional.of(draft(1L, D5, "D", true)));

        CellVO cell = service.updateEntry(YM, new UpdateEntryRequest(1L, D5, null, null), OPERATOR);

        assertEquals(new CellVO("N", false, null), cell);
        verify(opLog).record(OpAction.UPDATE_SCHEDULE, "人员1 10-05", "D → N");
    }

    /** 该月记的模板后来被删了 → 退回内置规则（10-05 周一 = D），不报错 */
    @Test
    void updateEntryWithNullCodeFallsBackWhenMonthTemplateGone() {
        ScheduleMonth recorded = month(ScheduleStatus.DRAFT, 1);
        recorded.setCycleTemplateId(9L);
        when(monthRepo.findById(YM)).thenReturn(Optional.of(recorded));
        when(templateRepo.findById(9L)).thenReturn(Optional.empty());

        CellVO cell = service.updateEntry(YM, new UpdateEntryRequest(1L, D5, null, null), OPERATOR);

        assertEquals(new CellVO("D", false, null), cell);
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
        service.generate(YM, null, OPERATOR);
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

    // ---------- applyChanges（任务单 M3-01：调班回写）----------

    /** 用例：A 10-08→X、B 10-08→N，两人草稿和已发布都有 → 草稿两格 manual=true 带备注，已发布两格代码跟着改 */
    @Test
    void applyChangesWritesBothDraftAndPublishedSnapshot() {
        LocalDate d8 = LocalDate.of(2026, 10, 8);
        when(entryRepo.findByStaffIdAndWorkDate(1L, d8)).thenReturn(Optional.of(draft(1L, d8, "D", false)));
        when(entryRepo.findByStaffIdAndWorkDate(2L, d8)).thenReturn(Optional.of(draft(2L, d8, "N", false)));
        when(publishedRepo.findByStaffIdAndWorkDate(1L, d8)).thenReturn(Optional.of(snapshot(1L, d8, "D", 3)));
        when(publishedRepo.findByStaffIdAndWorkDate(2L, d8)).thenReturn(Optional.of(snapshot(2L, d8, "N", 3)));

        service.applyChanges(List.of(new CellChange(1L, d8, "X"), new CellChange(2L, d8, "N")),
                SWAP_TARGET, OPERATOR);

        Map<String, ScheduleEntry> saved = savedEntries();
        assertEquals(2, saved.size());
        ScheduleEntry a = saved.get("1|" + d8);
        assertEquals("X", a.getShiftCode());
        assertTrue(a.isManual());
        assertEquals(SWAP_TARGET, a.getRemark());
        assertEquals(OPERATOR, a.getUpdatedBy());
        assertEquals(NOW, a.getUpdatedAt());
        ScheduleEntry b = saved.get("2|" + d8);
        assertEquals("N", b.getShiftCode());
        assertTrue(b.isManual());
        assertEquals(SWAP_TARGET, b.getRemark());

        ArgumentCaptor<SchedulePublishedEntry> captor = ArgumentCaptor.forClass(SchedulePublishedEntry.class);
        verify(publishedRepo, times(2)).save(captor.capture());
        List<SchedulePublishedEntry> snapshots = captor.getAllValues();
        assertEquals("X", snapshots.get(0).getShiftCode());
        assertEquals(SWAP_TARGET, snapshots.get(0).getRemark());
        // 回写不是发布：快照仍属原来那一版，version 不能被改
        assertEquals(3, snapshots.get(0).getVersion());
        assertEquals("N", snapshots.get(1).getShiftCode());

        verify(monthRepo, times(1)).lockMonth(LOCK_KEY);
        verify(opLog).record(OpAction.APPLY_SWAP_TO_SCHEDULE, SWAP_TARGET, "共2格");
    }

    /** 用例：这个格子没有已发布记录 → 只写草稿，不报错也不补插快照 */
    @Test
    void applyChangesWritesDraftOnlyWhenSnapshotMissing() {
        when(publishedRepo.findByStaffIdAndWorkDate(1L, D5)).thenReturn(Optional.empty());

        service.applyChanges(List.of(new CellChange(1L, D5, "N")), SWAP_TARGET, OPERATOR);

        ScheduleEntry saved = savedEntries().get("1|" + D5);
        assertEquals("N", saved.getShiftCode());
        assertTrue(saved.isManual());
        assertEquals(SWAP_TARGET, saved.getRemark());
        assertEquals(OPERATOR, saved.getUpdatedBy());
        verify(publishedRepo, never()).save(any());
        verify(publishedRepo, never()).saveAll(any());
        verify(opLog).record(OpAction.APPLY_SWAP_TO_SCHEDULE, SWAP_TARGET, "共1格");
    }

    /** 库里没这个草稿格子时新建一条，staffId/workDate 从 change 上带 */
    @Test
    void applyChangesCreatesMissingDraftCell() {
        service.applyChanges(List.of(new CellChange(2L, D10, "Z")), SWAP_TARGET, OPERATOR);

        ScheduleEntry saved = savedEntries().get("2|" + D10);
        assertEquals(2L, saved.getStaffId());
        assertEquals(D10, saved.getWorkDate());
        assertEquals("Z", saved.getShiftCode());
    }

    /** 用例：不调 markDraft——月份状态和 version 一律不动，连 schedule_month 都不去读、不补记录 */
    @Test
    void applyChangesLeavesMonthStatusAndVersionUntouched() {
        service.applyChanges(List.of(new CellChange(1L, D5, "N")), SWAP_TARGET, OPERATOR);

        verify(monthRepo, never()).save(any(ScheduleMonth.class));
        verify(monthRepo, never()).findById(anyString());
    }

    /** 用例：跨 10、11 月 → 先 lockMonth(202610) 再 lockMonth(202611)，同月去重只锁一次，锁在写库之前 */
    @Test
    void applyChangesLocksMonthsAscendingAndDeduplicated() {
        LocalDate d8 = LocalDate.of(2026, 10, 8);
        LocalDate d20 = LocalDate.of(2026, 10, 20);
        LocalDate nov5 = LocalDate.of(2026, 11, 5);

        // 11 月那条先传，锁仍要先 10 月后 11 月；10 月两格只锁一次
        service.applyChanges(List.of(new CellChange(1L, nov5, "D"), new CellChange(1L, d8, "X"),
                new CellChange(1L, d20, "N")), SWAP_TARGET, OPERATOR);

        InOrder order = inOrder(monthRepo, entryRepo);
        order.verify(monthRepo).lockMonth(LOCK_KEY);
        order.verify(monthRepo).lockMonth(NOV_LOCK_KEY);
        order.verify(entryRepo, times(3)).save(any(ScheduleEntry.class));
        verify(monthRepo, times(1)).lockMonth(LOCK_KEY);
        verify(monthRepo, times(1)).lockMonth(NOV_LOCK_KEY);
    }
}
