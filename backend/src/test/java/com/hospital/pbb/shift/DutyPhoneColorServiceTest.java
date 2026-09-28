package com.hospital.pbb.shift;

import com.hospital.pbb.common.BizException;
import com.hospital.pbb.oplog.OpAction;
import com.hospital.pbb.oplog.OpLogService;
import com.hospital.pbb.shift.dto.DutyPhoneColorVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 值班电话底色：读要能兜住“库里根本没有这一行”，写要统一小写并留痕，格式不对一律 1304 且不落库。
 */
class DutyPhoneColorServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Instant NOW = Instant.parse("2026-10-01T01:00:00Z");
    private static final String TARGET = "值班电话底色";

    private AppSettingRepository settingRepo;
    private OpLogService opLog;
    private DutyPhoneColorService service;

    @BeforeEach
    void setUp() {
        settingRepo = mock(AppSettingRepository.class);
        opLog = mock(OpLogService.class);
        service = new DutyPhoneColorService(settingRepo, opLog, Clock.fixed(NOW, ZONE));

        when(settingRepo.findById(AppSetting.DUTY_PHONE_COLOR)).thenReturn(Optional.empty());
        when(settingRepo.save(any(AppSetting.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    /** 库里已有的一行（V2 迁移脚本预置的就是 #fde047） */
    private AppSetting seed(String value) {
        AppSetting setting = new AppSetting();
        setting.setKey(AppSetting.DUTY_PHONE_COLOR);
        setting.setValue(value);
        when(settingRepo.findById(AppSetting.DUTY_PHONE_COLOR)).thenReturn(Optional.of(setting));
        return setting;
    }

    private static int bizCode(Runnable call) {
        return assertThrows(BizException.class, call::run).getCode();
    }

    /** 保存动作只认这一次 save，返回它落库的那个对象 */
    private AppSetting savedOnce() {
        ArgumentCaptor<AppSetting> saved = ArgumentCaptor.forClass(AppSetting.class);
        verify(settingRepo, times(1)).save(saved.capture());
        return saved.getValue();
    }

    @Test
    void getReturnsDefaultWhenRowMissing() {
        assertEquals("#fde047", service.get().color());
    }

    @Test
    void getReturnsStoredValue() {
        seed("#00ff00");

        assertEquals("#00ff00", service.get().color());
    }

    /** 大写入参统一转小写入库，并记一条“旧值 → 新值”的日志 */
    @Test
    void updateLowercasesValueAndRecordsLog() {
        seed("#fde047");

        DutyPhoneColorVO result = service.update("#FF0000");

        assertEquals("#ff0000", result.color());
        AppSetting saved = savedOnce();
        assertEquals("#ff0000", saved.getValue());
        verify(opLog).record(OpAction.UPDATE_DUTY_PHONE_COLOR, TARGET, "#fde047 → #ff0000");
    }

    /** 这一行还没被创建过（迁移脚本没跑或被误删）时按 key 新建，更新时间取注入的 Clock */
    @Test
    void updateCreatesRowWhenMissing() {
        DutyPhoneColorVO result = service.update("#FDBA74");

        AppSetting saved = savedOnce();
        assertEquals(AppSetting.DUTY_PHONE_COLOR, saved.getKey(), "key 写错会变成另一项设置，读的时候读不到");
        assertEquals("#fdba74", saved.getValue());
        assertEquals(OffsetDateTime.ofInstant(NOW, ZONE), saved.getUpdatedAt());
        assertEquals("#fdba74", result.color());
        // 旧值取的是接口的兜底口径：库里没有就等于默认色
        verify(opLog).record(OpAction.UPDATE_DUTY_PHONE_COLOR, TARGET, "#fde047 → #fdba74");
    }

    /** 已有这一行必须原地改，不能新插一行（主键是 setting_key，插新行等于覆盖，但测试要钉住复用同一对象） */
    @Test
    void updateReusesExistingRow() {
        AppSetting existing = seed("#1d4ed8");

        service.update("#92400e");

        assertSame(existing, savedOnce());
        assertEquals("#92400e", existing.getValue());
        assertEquals(OffsetDateTime.ofInstant(NOW, ZONE), existing.getUpdatedAt());
    }

    /** 大小写混写、纯小写都是合法颜色，原样（小写）返回 */
    @Test
    void updateAcceptsAnyHexCase() {
        assertEquals("#abcdef", service.update("#aBcDeF").color());
        assertEquals("#000000", service.update("#000000").color());
        verify(opLog, times(2)).record(anyString(), anyString(), anyString());
    }

    /** 颜色一律按 #RRGGBB 存，前端拿到的是 css 里能直接用的值，缩写和带 alpha 的一律不收 */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"red", "#fff", "#ffff", "#fffff", "#fffffff", "ff0000", "#ff 000", "#gggggg",
            "#ff0000 ", "  #ff0000", "#ff0000gg", "rgb(255,0,0)"})
    void illegalColorReturns1304(String color) {
        BizException e = assertThrows(BizException.class, () -> service.update(color));

        assertEquals(1304, e.getCode());
        assertEquals("颜色格式应为 #RRGGBB", e.getMessage());
        verify(settingRepo, never()).save(any(AppSetting.class));
        verify(opLog, never()).record(anyString(), anyString(), any());
    }

    /** 1304 抛出后一行都不许碰：不保存、不留痕 */
    @Test
    void illegalColorLeavesExistingRowUntouched() {
        AppSetting existing = seed("#fde047");

        List.of("red", "#12345", "#1234567").forEach(color ->
                assertEquals(1304, bizCode(() -> service.update(color))));

        verify(settingRepo, never()).save(any(AppSetting.class));
        assertEquals("#fde047", existing.getValue());
        verify(opLog, never()).record(anyString(), anyString(), any());
    }
}
