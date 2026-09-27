package com.hospital.pbb.shift;

import com.hospital.pbb.common.BizException;
import com.hospital.pbb.oplog.OpAction;
import com.hospital.pbb.oplog.OpLogService;
import com.hospital.pbb.shift.dto.UpdateShiftTypeRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 班次不能增删，只验修改：重点是把不该改的（code、sortOrder）和该拦的（停必需班次、时间不配对）拦住。 */
class ShiftTypeServiceTest {

    private ShiftTypeRepository shiftRepo;
    private OpLogService opLog;
    private ShiftTypeService service;

    @BeforeEach
    void setUp() {
        shiftRepo = mock(ShiftTypeRepository.class);
        opLog = mock(OpLogService.class);
        service = new ShiftTypeService(shiftRepo, opLog);

        when(shiftRepo.findById(anyString())).thenAnswer(inv -> seed(inv.getArgument(0)));
        when(shiftRepo.save(any(ShiftType.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    /** 与 V1__init_schema.sql 预置一致的 6 条班次 */
    private static Optional<ShiftType> seed(String code) {
        return switch (code) {
            case "D" -> Optional.of(shift("D", "白班", "08:00", "17:30", false, "8.0", true, "#1d4ed8", 1));
            case "N" -> Optional.of(shift("N", "夜班", "17:30", "08:00", true, "14.5", true, "#6d28d9", 2));
            case "Z" -> Optional.of(shift("Z", "值班", "08:00", "08:00", true, "24.0", true, "#b91c1c", 3));
            case "B" -> Optional.of(shift("B", "备班", null, null, false, "0.0", false, "#92400e", 4));
            case "L" -> Optional.of(shift("L", "请假", null, null, false, "0.0", false, "#166534", 5));
            case "X" -> Optional.of(shift("X", "休息", null, null, false, "0.0", false, "#6b7280", 6));
            default -> Optional.empty();
        };
    }

    private static ShiftType shift(String code, String name, String start, String end, boolean crossDay,
                                  String workHours, boolean countsAsWork, String color, int sortOrder) {
        ShiftType s = new ShiftType();
        s.setCode(code);
        s.setName(name);
        s.setStartTime(start == null ? null : LocalTime.parse(start));
        s.setEndTime(end == null ? null : LocalTime.parse(end));
        s.setCrossDay(crossDay);
        s.setWorkHours(new BigDecimal(workHours));
        s.setCountsAsWork(countsAsWork);
        s.setColor(color);
        s.setSortOrder(sortOrder);
        s.setEnabled(true);
        return s;
    }

    /** 后端传来的形状即前端提交的形状：时间 "HH:mm"，工时可能是整数 */
    private static UpdateShiftTypeRequest req(String name, LocalTime start, LocalTime end, boolean crossDay,
                                              String workHours, boolean countsAsWork, String color, boolean enabled) {
        return new UpdateShiftTypeRequest(name, start, end, crossDay, new BigDecimal(workHours),
                countsAsWork, color, enabled);
    }

    private static int bizCode(Runnable call) {
        return assertThrows(BizException.class, call::run).getCode();
    }

    @Test
    void listReturnsAllShiftsOrderedBySortOrder() {
        List<ShiftType> all = List.of(
                shift("D", "白班", "08:00", "17:30", false, "8.0", true, "#1d4ed8", 1),
                shift("X", "休息", null, null, false, "0.0", false, "#6b7280", 6));
        when(shiftRepo.findAllByOrderBySortOrderAsc()).thenReturn(all);

        assertSame(all, service.list());
        verify(shiftRepo).findAllByOrderBySortOrderAsc();
    }

    @Test
    void updateNightShiftNameAndCrossDayTimesSucceeds() {
        ShiftType result = service.update("N",
                req("晚班", LocalTime.of(17, 30), LocalTime.of(8, 0), true, "15", true, "#6d28d9", true));

        assertEquals("晚班", result.getName());
        ArgumentCaptor<ShiftType> saved = ArgumentCaptor.forClass(ShiftType.class);
        verify(shiftRepo).save(saved.capture());
        ShiftType s = saved.getValue();
        assertEquals("N", s.getCode(), "code 是指向排班数据的键，不能改");
        assertEquals(2, s.getSortOrder(), "排序号不在请求里，必须保持预置值");
        assertEquals(LocalTime.of(17, 30), s.getStartTime());
        assertEquals(LocalTime.of(8, 0), s.getEndTime());
        assertTrue(s.isCrossDay());
        assertEquals(0, new BigDecimal("15").compareTo(s.getWorkHours()));
        assertTrue(s.isCountsAsWork());
        assertEquals("#6d28d9", s.getColor());
        assertTrue(s.isEnabled());

        // 留痕写修改前后的名称和工时
        verify(opLog).record(OpAction.UPDATE_SHIFT, "N", "夜班→晚班, 14.5→15");
    }

    /** 白班是规则排班的兜底班次，停用了规则就排不出来 */
    @Test
    void disableDayShiftReturns1301() {
        assertEquals(1301, bizCode(() -> service.update("D",
                req("白班", LocalTime.of(8, 0), LocalTime.of(17, 30), false, "8.0", true, "#1d4ed8", false))));

        verify(shiftRepo, never()).save(any(ShiftType.class));
        verify(opLog, never()).record(anyString(), anyString(), any());
    }

    /** 休息同样是必需班次 */
    @Test
    void disableRestShiftReturns1301() {
        assertEquals(1301, bizCode(() -> service.update("X",
                req("休息", null, null, false, "0.0", false, "#6b7280", false))));

        verify(shiftRepo, never()).save(any(ShiftType.class));
    }

    /** 备班不是必需班次，可以停 */
    @Test
    void disableStandbyShiftSucceeds() {
        ShiftType result = service.update("B",
                req("备班", null, null, false, "0.0", false, "#92400e", false));

        assertTrue(!result.isEnabled());
        verify(shiftRepo).save(any(ShiftType.class));
        verify(opLog).record(OpAction.UPDATE_SHIFT, "B", "备班→备班, 0.0→0.0");
    }

    /** 只填上班时间：等于把下班时间清空，数据会自相矛盾，1302 */
    @Test
    void onlyStartTimeReturns1302() {
        assertEquals(1302, bizCode(() -> service.update("D",
                req("白班", LocalTime.of(8, 0), null, false, "8.0", true, "#1d4ed8", true))));

        verify(shiftRepo, never()).save(any(ShiftType.class));
        verify(opLog, never()).record(anyString(), anyString(), any());
    }

    /** 反过来只填下班时间一样是 1302 */
    @Test
    void onlyEndTimeReturns1302() {
        assertEquals(1302, bizCode(() -> service.update("D",
                req("白班", null, LocalTime.of(17, 30), false, "8.0", true, "#1d4ed8", true))));

        verify(shiftRepo, never()).save(any(ShiftType.class));
    }

    /** 不跨天却让下班时间倒挂（17:00~08:00），1303 */
    @Test
    void nonCrossDayWithEndTimeNotAfterStartTimeReturns1303() {
        assertEquals(1303, bizCode(() -> service.update("D",
                req("白班", LocalTime.of(17, 0), LocalTime.of(8, 0), false, "8.0", true, "#1d4ed8", true))));

        verify(shiftRepo, never()).save(any(ShiftType.class));
    }

    /** 上下班填同一个时刻也不是“上一整天”，不跨天时同样算 1303 */
    @Test
    void nonCrossDayWithEqualTimesReturns1303() {
        assertEquals(1303, bizCode(() -> service.update("D",
                req("白班", LocalTime.of(8, 0), LocalTime.of(8, 0), false, "8.0", true, "#1d4ed8", true))));

        verify(shiftRepo, never()).save(any(ShiftType.class));
    }

    /** 两个时间同时为空是合法形状（备班、请假、休息），不能误报 1302 */
    @Test
    void bothTimesNullIsAllowed() {
        ShiftType result = service.update("L",
                req("年假", null, null, false, "0.0", false, "#166534", true));

        assertEquals("年假", result.getName());
        assertNull(result.getStartTime());
        assertNull(result.getEndTime());
    }

    /** 跨天班次本来就是下班早于上班，17:30~08:00 这类数据必须放行 */
    @Test
    void crossDayAllowsEndTimeBeforeStartTime() {
        ShiftType result = service.update("Z",
                req("值班", LocalTime.of(8, 0), LocalTime.of(8, 0), true, "24.0", true, "#b91c1c", true));

        assertTrue(result.isCrossDay());
        verify(shiftRepo).save(any(ShiftType.class));
    }

    /** code 走主键，查不到就是班次不存在，不能靠 mock 兜底成空对象 */
    @Test
    void unknownCodeReturns1300() {
        assertEquals(1300, bizCode(() -> service.update("Q",
                req("未知", null, null, false, "0.0", false, "#000000", true))));

        verify(shiftRepo, never()).save(any(ShiftType.class));
        verify(opLog, never()).record(anyString(), anyString(), any());
    }
}
