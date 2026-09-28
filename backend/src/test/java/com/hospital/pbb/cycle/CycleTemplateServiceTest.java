package com.hospital.pbb.cycle;

import com.hospital.pbb.common.BizException;
import com.hospital.pbb.cycle.dto.CycleTemplateRequest;
import com.hospital.pbb.cycle.dto.CycleTemplateVO;
import com.hospital.pbb.oplog.OpAction;
import com.hospital.pbb.oplog.OpLogService;
import com.hospital.pbb.shift.ShiftType;
import com.hospital.pbb.shift.ShiftTypeRepository;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.security.access.prepost.PreAuthorize;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CycleTemplateService 的业务规则（V2 迁移里的约束在代码里的对应关系）。
 *
 * <p>打桩仓库、不连数据库；{@code saveAndFlush} 的调用顺序就是"全表最多一个默认模板"
 * 这条部分唯一索引能不能守住的关键，单独用 InOrder 断言。</p>
 */
class CycleTemplateServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-01T01:00:00Z"), ZONE);
    private static final OffsetDateTime NOW = OffsetDateTime.now(CLOCK);
    /** V1 预置的 6 个班次代号 */
    private static final List<String> PRESET_CODES = List.of("D", "N", "Z", "B", "L", "X");

    private CycleTemplateRepository repo;
    private ShiftTypeRepository shiftRepo;
    private OpLogService opLog;
    private CycleTemplateService service;

    @BeforeEach
    void setUp() {
        repo = mock(CycleTemplateRepository.class);
        shiftRepo = mock(ShiftTypeRepository.class);
        opLog = mock(OpLogService.class);
        service = new CycleTemplateService(repo, shiftRepo, opLog, CLOCK);

        PRESET_CODES.forEach(code -> when(shiftRepo.findById(code))
                .thenReturn(Optional.of(shift(code, true))));
        when(repo.save(any(CycleTemplate.class))).thenAnswer(inv -> {
            CycleTemplate saved = inv.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(11L); // 库里 BIGSERIAL 分配的 id
            }
            return saved;
        });
        when(repo.saveAndFlush(any(CycleTemplate.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    // ---------------------------------------------------------------- 新增

    @Test
    void createReturnsTheSavedTemplateAndLogsTheWholeWeek() {
        CycleTemplateVO vo = service.create(request("夜班周期", false, "N", "X", "D", "D", "D", "X", "X"));

        assertEquals(11L, vo.id());
        assertEquals("夜班周期", vo.name());
        assertEquals(List.of("N", "X", "D", "D", "D", "X", "X"), vo.days());
        assertFalse(vo.isDefault());

        ArgumentCaptor<CycleTemplate> captor = ArgumentCaptor.forClass(CycleTemplate.class);
        verify(repo).save(captor.capture());
        assertEquals(List.of("N", "X", "D", "D", "D", "X", "X"), captor.getValue().days());
        assertFalse(captor.getValue().isDefaultTemplate());
        assertEquals(NOW, captor.getValue().getCreatedAt());
        assertEquals(NOW, captor.getValue().getUpdatedAt(), "当前时间一律取注入的 Clock");

        verify(opLog).record(OpAction.CREATE_CYCLE, "夜班周期", "周一至周日：N X D D D X X");
    }

    /** 名称首尾空格是录入噪声，查重和入库都要用去掉空格后的名称 */
    @Test
    void createTrimsTheNameBeforeSavingAndLogging() {
        CycleTemplateVO vo = service.create(request("  夜班周期  ", false, "N", "N", "X", "X", "N", "N", "X"));

        assertEquals("夜班周期", vo.name());
        verify(repo).existsByName("夜班周期");
        verify(opLog).record(OpAction.CREATE_CYCLE, "夜班周期", "周一至周日：N N X X N N X");
    }

    @Test
    void createRejectsANameAlreadyTaken() {
        when(repo.existsByName("标准周期")).thenReturn(true);

        BizException e = assertThrows(BizException.class,
                () -> service.create(request("标准周期", false, "D", "D", "D", "D", "D", "X", "X")));

        assertEquals(1801, e.getCode());
        assertEquals("模板名称已存在", e.getMessage());
        verify(repo, never()).save(any());
        verify(opLog, never()).record(any(), any(), any());
    }

    @Test
    void createRejectsAnUnknownShiftCode() {
        when(shiftRepo.findById("Q")).thenReturn(Optional.empty());

        BizException e = assertThrows(BizException.class,
                () -> service.create(request("夜班周期", false, "Q", "X", "D", "D", "D", "X", "X")));

        assertEquals(1803, e.getCode());
        assertEquals("模板中的班次不存在或已停用", e.getMessage());
        verify(repo, never()).save(any());
    }

    @Test
    void createRejectsADisabledShiftCode() {
        when(shiftRepo.findById("Z")).thenReturn(Optional.of(shift("Z", false)));

        BizException e = assertThrows(BizException.class,
                () -> service.create(request("夜班周期", false, "D", "D", "D", "D", "D", "X", "Z")));

        assertEquals(1803, e.getCode());
        verify(repo, never()).save(any());
    }

    /** 部分唯一索引 uq_cycle_template_default 只允许一行 is_default = true：旧的默认必须先落库地被摘掉 */
    @Test
    void createAsDefaultClearsTheOldDefaultBeforeInsertingItself() {
        CycleTemplate standard = template(1L, "标准周期", true, "D", "D", "D", "D", "D", "X", "X");
        when(repo.findFirstByDefaultTemplateTrue()).thenReturn(Optional.of(standard));

        CycleTemplateVO vo = service.create(request("夜班周期", true, "N", "N", "X", "X", "N", "N", "X"));

        assertTrue(vo.isDefault());
        assertFalse(standard.isDefaultTemplate(), "原默认模板要被置为 false");
        InOrder order = inOrder(repo);
        order.verify(repo).saveAndFlush(standard);
        order.verify(repo).save(any(CycleTemplate.class));
    }

    @Test
    void createAsNonDefaultLeavesTheCurrentDefaultAlone() {
        service.create(request("夜班周期", false, "N", "N", "X", "X", "N", "N", "X"));

        verify(repo, never()).findFirstByDefaultTemplateTrue();
        verify(repo, never()).saveAndFlush(any());
    }

    /** 一条模板也没有（库里只预置了“标准周期”，但可能被删到只剩非默认）时新建默认不能报错 */
    @Test
    void createAsDefaultWorksWithoutAnyExistingDefault() {
        when(repo.findFirstByDefaultTemplateTrue()).thenReturn(Optional.empty());

        assertTrue(service.create(request("夜班周期", true, "N", "N", "X", "X", "N", "N", "X")).isDefault());
        verify(repo, never()).saveAndFlush(any());
    }

    // ---------------------------------------------------------------- 修改

    @Test
    void updateReplacesNameDaysAndRefreshesUpdatedAt() {
        CycleTemplate existing = template(3L, "夜班周期", false, "N", "X", "D", "D", "D", "X", "X");
        when(repo.findById(3L)).thenReturn(Optional.of(existing));

        CycleTemplateVO vo = service.update(3L, request("  夜班周期V2 ", false, "N", "N", "N", "X", "X", "X", "X"));

        assertEquals(3L, vo.id());
        assertEquals("夜班周期V2", vo.name());
        assertEquals(List.of("N", "N", "N", "X", "X", "X", "X"), vo.days());
        assertFalse(vo.isDefault());
        assertEquals(NOW, existing.getUpdatedAt());
        // 改自己不算重名，只能用 existsByNameAndIdNot
        verify(repo).existsByNameAndIdNot("夜班周期V2", 3L);
        verify(repo, never()).existsByName(any());
        verify(opLog).record(OpAction.UPDATE_CYCLE, "夜班周期V2", "周一至周日：N N N X X X X");
    }

    @Test
    void updateRejectsAMissingTemplate() {
        when(repo.findById(999L)).thenReturn(Optional.empty());

        BizException e = assertThrows(BizException.class,
                () -> service.update(999L, request("夜班周期", false, "N", "X", "D", "D", "D", "X", "X")));

        assertEquals(1800, e.getCode());
        assertEquals("排班周期模板不存在", e.getMessage());
        verify(repo, never()).save(any());
    }

    /** 默认模板是"按规则生成不指定模板"和"恢复规则默认"的依据，不能把它改成非默认 */
    @Test
    void updateRejectsTurningTheDefaultTemplateIntoNonDefault() {
        when(repo.findById(1L)).thenReturn(Optional.of(
                template(1L, "标准周期", true, "D", "D", "D", "D", "D", "X", "X")));

        BizException e = assertThrows(BizException.class,
                () -> service.update(1L, request("标准周期", false, "D", "D", "D", "D", "D", "X", "X")));

        assertEquals(1804, e.getCode());
        assertEquals("至少保留一个默认模板", e.getMessage());
        verify(repo, never()).save(any());
    }

    @Test
    void updateRejectsATemplateNameTakenByAnotherTemplate() {
        when(repo.findById(3L)).thenReturn(Optional.of(
                template(3L, "夜班周期", false, "N", "N", "X", "X", "N", "N", "X")));
        when(repo.existsByNameAndIdNot("标准周期", 3L)).thenReturn(true);

        BizException e = assertThrows(BizException.class,
                () -> service.update(3L, request("标准周期", false, "N", "N", "X", "X", "N", "N", "X")));

        assertEquals(1801, e.getCode());
        verify(repo, never()).save(any());
    }

    @Test
    void updateRejectsADisabledShiftCode() {
        when(repo.findById(3L)).thenReturn(Optional.of(
                template(3L, "夜班周期", false, "N", "N", "X", "X", "N", "N", "X")));
        when(shiftRepo.findById("L")).thenReturn(Optional.of(shift("L", false)));

        BizException e = assertThrows(BizException.class,
                () -> service.update(3L, request("夜班周期", false, "N", "N", "L", "X", "N", "N", "X")));

        assertEquals(1803, e.getCode());
        verify(repo, never()).save(any());
    }

    @Test
    void updateToDefaultClearsTheOtherDefaultFirst() {
        CycleTemplate standard = template(1L, "标准周期", true, "D", "D", "D", "D", "D", "X", "X");
        CycleTemplate night = template(3L, "夜班周期", false, "N", "N", "X", "X", "N", "N", "X");
        when(repo.findById(3L)).thenReturn(Optional.of(night));
        when(repo.findFirstByDefaultTemplateTrue()).thenReturn(Optional.of(standard));

        CycleTemplateVO vo = service.update(3L, request("夜班周期", true, "N", "N", "X", "X", "N", "N", "X"));

        assertTrue(vo.isDefault());
        assertFalse(standard.isDefaultTemplate());
        InOrder order = inOrder(repo);
        order.verify(repo).saveAndFlush(standard);
        order.verify(repo).save(night);
    }

    /** 默认模板改自己（仍为默认）不涉及"全表最多一个默认"，不需要动别的模板 */
    @Test
    void updateOfTheDefaultTemplateItselfDoesNotTouchOthers() {
        when(repo.findById(1L)).thenReturn(Optional.of(
                template(1L, "标准周期", true, "D", "D", "D", "D", "D", "X", "X")));

        service.update(1L, request("标准周期", true, "D", "D", "D", "D", "D", "D", "X"));

        verify(repo, never()).findFirstByDefaultTemplateTrue();
        verify(repo, never()).saveAndFlush(any());
    }

    // ---------------------------------------------------------------- 删除

    @Test
    void deleteRemovesANonDefaultTemplateAndLogsAnEmptyDetail() {
        CycleTemplate night = template(3L, "夜班周期", false, "N", "N", "X", "X", "N", "N", "X");
        when(repo.findById(3L)).thenReturn(Optional.of(night));

        service.delete(3L);

        verify(repo).delete(night);
        verify(opLog).record(OpAction.DELETE_CYCLE, "夜班周期", "");
    }

    @Test
    void deleteRejectsAMissingTemplate() {
        when(repo.findById(999L)).thenReturn(Optional.empty());

        BizException e = assertThrows(BizException.class, () -> service.delete(999L));

        assertEquals(1800, e.getCode());
        assertEquals("排班周期模板不存在", e.getMessage());
        verify(repo, never()).delete(any());
    }

    /** 删默认模板会让"按规则生成"失去默认依据，1802；库里 schedule_month.cycle_template_id 悬空更要避免 */
    @Test
    void deleteRejectsTheDefaultTemplate() {
        CycleTemplate standard = template(1L, "标准周期", true, "D", "D", "D", "D", "D", "X", "X");
        when(repo.findById(1L)).thenReturn(Optional.of(standard));

        BizException e = assertThrows(BizException.class, () -> service.delete(1L));

        assertEquals(1802, e.getCode());
        assertEquals("默认模板不能删除", e.getMessage());
        verify(repo, never()).delete(any());
        verify(opLog, never()).record(any(), any(), any());
    }

    // ---------------------------------------------------------------- 列表与权限

    @Test
    void listReturnsEveryTemplateInIdOrder() {
        when(repo.findAllByOrderByIdAsc()).thenReturn(List.of(
                template(1L, "标准周期", true, "D", "D", "D", "D", "D", "X", "X"),
                template(3L, "夜班周期", false, "N", "N", "X", "X", "N", "N", "X")));

        List<CycleTemplateVO> vos = service.list();

        assertEquals(List.of(1L, 3L), vos.stream().map(CycleTemplateVO::id).toList());
        assertEquals("标准周期", vos.get(0).name());
        assertTrue(vos.get(0).isDefault());
        assertEquals(List.of("N", "N", "X", "X", "N", "N", "X"), vos.get(1).days());
        assertFalse(vos.get(1).isDefault());
    }

    /** 成员调 GET /api/cycle-templates 必须 403（curl 验收），这里守住收权注解 */
    @Test
    void controllerIsAdminOnly() {
        PreAuthorize authorize = CycleTemplateController.class.getAnnotation(PreAuthorize.class);

        assertEquals("hasRole('ADMIN')", authorize.value());
    }

    // ---------------------------------------------------------------- 参数校验（HTTP 400）

    @Test
    void requestWithSixDaysIsRejectedByValidation() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

        Set<ConstraintViolation<CycleTemplateRequest>> violations = validator.validate(
                new CycleTemplateRequest("夜班周期", List.of("D", "D", "D", "D", "D", "X"), false));

        assertEquals(1, violations.size());
        assertEquals("days", violations.iterator().next().getPropertyPath().toString());
        assertTrue(validator.validate(request("夜班周期", false, "D", "D", "D", "D", "D", "X", "X")).isEmpty());
    }

    @Test
    void requestWithSevenDaysIncludingABlankCodeIsRejectedByValidation() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

        assertFalse(validator.validate(
                new CycleTemplateRequest("夜班周期", List.of("D", "D", " ", "D", "D", "X", "X"), false)).isEmpty());
        assertFalse(validator.validate(
                new CycleTemplateRequest("夜班周期", List.of("D", "D", "D", "D", "D", "X", "X", "X"), false)).isEmpty());
    }

    // ---------------------------------------------------------------- 测试脚手架

    private static ShiftType shift(String code, boolean enabled) {
        ShiftType shift = new ShiftType();
        shift.setCode(code);
        shift.setName(code);
        shift.setEnabled(enabled);
        return shift;
    }

    private static CycleTemplate template(Long id, String name, boolean isDefault, String... days) {
        CycleTemplate template = new CycleTemplate();
        template.setId(id);
        template.setName(name);
        template.setDays(List.of(days));
        template.setDefaultTemplate(isDefault);
        return template;
    }

    private static CycleTemplateRequest request(String name, boolean isDefault, String... days) {
        return new CycleTemplateRequest(name, List.of(days), isDefault);
    }
}
