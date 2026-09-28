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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.prepost.PreAuthorize;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CycleTemplateService 的业务规则（V2 迁移里的约束在代码里的对应关系）。
 *
 * <p>打桩仓库、不连数据库；{@code saveAndFlush} 的调用顺序就是"全表最多一个默认模板"
 * 这条部分唯一索引能不能守住的关键，单独用 InOrder 断言；表级 advisory lock 抢在哪些读取
 * 之前，同样只有 InOrder 才看得出来。</p>
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
        // 生产代码只走 saveAndFlush：预检查之后要让写入立刻落库，约束冲突才能在方法内被抓到
        when(repo.saveAndFlush(any(CycleTemplate.class))).thenAnswer(inv -> {
            CycleTemplate saved = inv.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(11L); // 库里 BIGSERIAL 分配的 id
            }
            return saved;
        });
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
        verify(repo).saveAndFlush(captor.capture());
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
        verify(repo, never()).saveAndFlush(any());
        verify(opLog, never()).record(any(), any(), any());
    }

    @Test
    void createRejectsAnUnknownShiftCode() {
        when(shiftRepo.findById("Q")).thenReturn(Optional.empty());

        BizException e = assertThrows(BizException.class,
                () -> service.create(request("夜班周期", false, "Q", "X", "D", "D", "D", "X", "X")));

        assertEquals(1803, e.getCode());
        assertEquals("模板中的班次不存在或已停用", e.getMessage());
        verify(repo, never()).saveAndFlush(any());
    }

    @Test
    void createRejectsADisabledShiftCode() {
        when(shiftRepo.findById("Z")).thenReturn(Optional.of(shift("Z", false)));

        BizException e = assertThrows(BizException.class,
                () -> service.create(request("夜班周期", false, "D", "D", "D", "D", "D", "X", "Z")));

        assertEquals(1803, e.getCode());
        verify(repo, never()).saveAndFlush(any());
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
        order.verify(repo).saveAndFlush(any(CycleTemplate.class)); // 自己的写入也要落库
    }

    @Test
    void createAsNonDefaultLeavesTheCurrentDefaultAlone() {
        service.create(request("夜班周期", false, "N", "N", "X", "X", "N", "N", "X"));

        verify(repo, never()).findFirstByDefaultTemplateTrue();
        verify(repo, times(1)).saveAndFlush(any()); // 只有新建的这条被写入，没有谁的默认被摘掉
    }

    /** 一条模板也没有（库里只预置了“标准周期”，但可能被删到只剩非默认）时新建默认不能报错 */
    @Test
    void createAsDefaultWorksWithoutAnyExistingDefault() {
        when(repo.findFirstByDefaultTemplateTrue()).thenReturn(Optional.empty());

        assertTrue(service.create(request("夜班周期", true, "N", "N", "X", "X", "N", "N", "X")).isDefault());
        verify(repo, times(1)).saveAndFlush(any()); // 没有旧默认要摘，只写自己这一条
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
        verify(repo, never()).saveAndFlush(any());
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
        verify(repo, never()).saveAndFlush(any());
    }

    @Test
    void updateRejectsATemplateNameTakenByAnotherTemplate() {
        when(repo.findById(3L)).thenReturn(Optional.of(
                template(3L, "夜班周期", false, "N", "N", "X", "X", "N", "N", "X")));
        when(repo.existsByNameAndIdNot("标准周期", 3L)).thenReturn(true);

        BizException e = assertThrows(BizException.class,
                () -> service.update(3L, request("标准周期", false, "N", "N", "X", "X", "N", "N", "X")));

        assertEquals(1801, e.getCode());
        verify(repo, never()).saveAndFlush(any());
    }

    @Test
    void updateRejectsADisabledShiftCode() {
        when(repo.findById(3L)).thenReturn(Optional.of(
                template(3L, "夜班周期", false, "N", "N", "X", "X", "N", "N", "X")));
        when(shiftRepo.findById("L")).thenReturn(Optional.of(shift("L", false)));

        BizException e = assertThrows(BizException.class,
                () -> service.update(3L, request("夜班周期", false, "N", "N", "L", "X", "N", "N", "X")));

        assertEquals(1803, e.getCode());
        verify(repo, never()).saveAndFlush(any());
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
        order.verify(repo).saveAndFlush(night);
    }

    /** 默认模板改自己（仍为默认）不涉及"全表最多一个默认"，不需要动别的模板 */
    @Test
    void updateOfTheDefaultTemplateItselfDoesNotTouchOthers() {
        when(repo.findById(1L)).thenReturn(Optional.of(
                template(1L, "标准周期", true, "D", "D", "D", "D", "D", "X", "X")));

        service.update(1L, request("标准周期", true, "D", "D", "D", "D", "D", "D", "X"));

        verify(repo, never()).findFirstByDefaultTemplateTrue();
        verify(repo, times(1)).saveAndFlush(any()); // 只写它自己这一条，没有别的模板被摘掉默认
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

    // -------------------------------------------------- 表级 advisory lock（写在任何读取之前）

    /**
     * 新增：先取表锁，再查重、再读当前默认。锁在后面读就只能读到加锁前别的事务提交的中间态，
     * "摘掉旧默认"也会摘错行。
     */
    @Test
    void createTakesTheTableLockBeforeAnyOtherRepositoryCall() {
        CycleTemplate standard = template(1L, "标准周期", true, "D", "D", "D", "D", "D", "X", "X");
        when(repo.findFirstByDefaultTemplateTrue()).thenReturn(Optional.of(standard));

        service.create(request("夜班周期", true, "N", "N", "X", "X", "N", "N", "X"));

        InOrder order = inOrder(repo);
        order.verify(repo).lockTemplate(0);
        order.verify(repo).existsByName("夜班周期");
        order.verify(repo).findFirstByDefaultTemplateTrue();
        order.verify(repo).saveAndFlush(standard);
        order.verify(repo).saveAndFlush(any(CycleTemplate.class));
        verify(repo, times(1)).lockTemplate(0);
    }

    /** 修改：load(id) 也要在锁后，否则 1804 判断用的是加锁前的 is_default */
    @Test
    void updateTakesTheTableLockBeforeAnyOtherRepositoryCall() {
        CycleTemplate standard = template(1L, "标准周期", true, "D", "D", "D", "D", "D", "X", "X");
        CycleTemplate night = template(3L, "夜班周期", false, "N", "N", "X", "X", "N", "N", "X");
        when(repo.findById(3L)).thenReturn(Optional.of(night));
        when(repo.findFirstByDefaultTemplateTrue()).thenReturn(Optional.of(standard));

        service.update(3L, request("夜班周期", true, "N", "N", "X", "X", "N", "N", "X"));

        InOrder order = inOrder(repo);
        order.verify(repo).lockTemplate(0);
        order.verify(repo).findById(3L);
        order.verify(repo).existsByNameAndIdNot("夜班周期", 3L);
        order.verify(repo).findFirstByDefaultTemplateTrue();
        order.verify(repo).saveAndFlush(standard);
        order.verify(repo).saveAndFlush(night);
        verify(repo, times(1)).lockTemplate(0);
    }

    /** 删除也要锁：默认标记在锁前读，两个并发删除能把默认模板删干净 */
    @Test
    void deleteTakesTheTableLockBeforeAnyOtherRepositoryCall() {
        CycleTemplate night = template(3L, "夜班周期", false, "N", "N", "X", "X", "N", "N", "X");
        when(repo.findById(3L)).thenReturn(Optional.of(night));

        service.delete(3L);

        InOrder order = inOrder(repo);
        order.verify(repo).lockTemplate(0);
        order.verify(repo).findById(3L);
        order.verify(repo).delete(night);
        verify(repo, times(1)).lockTemplate(0);
    }

    /** 抛错的业务码也一样走了锁：1804 / 1802 用的 is_default 必须是加锁后读到的那一份 */
    @Test
    void rejectedWritesStillReadOnlyAfterTheLock() {
        CycleTemplate standard = template(1L, "标准周期", true, "D", "D", "D", "D", "D", "X", "X");
        when(repo.findById(1L)).thenReturn(Optional.of(standard));

        assertEquals(1804, bizCode(() ->
                service.update(1L, request("标准周期", false, "D", "D", "D", "D", "D", "X", "X"))));
        assertEquals(1802, bizCode(() -> service.delete(1L)));

        InOrder order = inOrder(repo);
        order.verify(repo).lockTemplate(0); // update 那一轮
        order.verify(repo).findById(1L);
        order.verify(repo).lockTemplate(0); // delete 那一轮
        order.verify(repo).findById(1L);
        verify(repo, times(2)).lockTemplate(0);
        verify(repo, never()).delete(any());
    }

    /** 列表只读，不加锁 */
    @Test
    void listDoesNotTakeTheTableLock() {
        service.list();

        verify(repo, never()).lockTemplate(anyInt());
    }

    // -------------------------------------------------- 名称唯一约束兜底（并发）

    /** Hibernate + PostgreSQL 撞唯一约束时的真实异常形状 */
    private static DataIntegrityViolationException uniqueViolation(String constraint, String column) {
        SQLException root = new SQLException("ERROR: duplicate key value violates unique constraint \""
                + constraint + "\"  Detail: Key (" + column + ")=(夜班周期) already exists.", "23505");
        return new DataIntegrityViolationException("could not execute statement [duplicate key value]",
                new org.hibernate.exception.ConstraintViolationException(
                        "duplicate key value violates unique constraint \"" + constraint + "\"", root, constraint));
    }

    /**
     * 两个管理员同时新增同名模板：预检查都通过，后提交的一方在 cycle_template.name 的唯一约束上撞下。
     * 必须转成 1801（不是 500），且不记新增日志。异常链按 Hibernate + PostgreSQL 的真实形状模拟。
     */
    @Test
    void createMapsNameUniqueViolationTo1801() {
        when(repo.saveAndFlush(any(CycleTemplate.class)))
                .thenThrow(uniqueViolation(CycleTemplateService.NAME_UNIQUE_CONSTRAINT, "name"));

        BizException e = assertThrows(BizException.class,
                () -> service.create(request("夜班周期", false, "N", "N", "X", "X", "N", "N", "X")));

        assertEquals(1801, e.getCode());
        assertEquals("模板名称已存在", e.getMessage());
        verify(opLog, never()).record(any(), any(), any());
    }

    /** 修改同理：改完的名字撞上别人也是 1801，不能漏到提交时变 500 */
    @Test
    void updateMapsNameUniqueViolationTo1801() {
        when(repo.findById(3L)).thenReturn(Optional.of(
                template(3L, "夜班周期", false, "N", "N", "X", "X", "N", "N", "X")));
        when(repo.saveAndFlush(any(CycleTemplate.class)))
                .thenThrow(uniqueViolation(CycleTemplateService.NAME_UNIQUE_CONSTRAINT, "name"));

        BizException e = assertThrows(BizException.class,
                () -> service.update(3L, request("标准周期", false, "N", "N", "X", "X", "N", "N", "X")));

        assertEquals(1801, e.getCode());
        verify(opLog, never()).record(any(), any(), any());
    }

    /**
     * 并发切默认撞 uq_cycle_template_default 也是 23505，但它不是重名，
     * 不能翻成 1801 把调用方误导到"改个名字"上——这类冲突一律原样抛出。
     */
    @Test
    void defaultIndexViolationIsNotMistakenForADuplicateName() {
        DataIntegrityViolationException defaultIndex = uniqueViolation("uq_cycle_template_default", "id");
        when(repo.findFirstByDefaultTemplateTrue()).thenReturn(Optional.of(
                template(1L, "标准周期", true, "D", "D", "D", "D", "D", "X", "X")));
        when(repo.saveAndFlush(any(CycleTemplate.class))).thenThrow(defaultIndex);

        DataIntegrityViolationException thrown = assertThrows(DataIntegrityViolationException.class, () ->
                service.create(request("夜班周期", true, "N", "N", "X", "X", "N", "N", "X")));

        assertSame(defaultIndex, thrown);
        verify(opLog, never()).record(any(), any(), any());
    }

    /** 外键一类非唯一约束（这里用 day_codes 的 FK）同样原样抛出，保证事务回滚并能定位到 500 */
    @Test
    void otherIntegrityViolationIsRethrown() {
        SQLException root = new SQLException(
                "ERROR: insert or update on table \"cycle_template\" violates foreign key constraint \"fk_cycle_template_day_codes\"",
                "23503");
        DataIntegrityViolationException fk = new DataIntegrityViolationException("could not execute statement", root);
        when(repo.saveAndFlush(any(CycleTemplate.class))).thenThrow(fk);

        DataIntegrityViolationException thrown = assertThrows(DataIntegrityViolationException.class, () ->
                service.create(request("夜班周期", false, "N", "N", "X", "X", "N", "N", "X")));

        assertSame(fk, thrown);
        verify(opLog, never()).record(any(), any(), any());
    }

    /**
     * 真并发：两个线程同时新增同名模板。查重只看得见的已提交行，两边都能通过预检查；
     * 后落库的一方由内存表里的唯一约束兜底。结局必须是一个成功、一个 1801，表里只剩一行。
     */
    @Test
    void concurrentCreateWithTheSameNameLeavesOneRowAndOne1801() throws Exception {
        FakeCycleTable table = new FakeCycleTable();
        CycleTemplateService shared = new CycleTemplateService(table.repository(), shiftRepo, opLog, CLOCK);

        List<Integer> codes = runConcurrently(
                () -> transaction(table, () -> shared.create(request("夜班周期", false, "N", "N", "X", "X", "N", "N", "X"))),
                () -> transaction(table, () -> shared.create(request("夜班周期", false, "N", "N", "X", "X", "N", "N", "X"))));

        assertEquals(List.of(0, 1801), codes.stream().sorted().toList(),
                "同名并发新增只能成一个，另一个要是 1801 而不是 500");
        assertEquals(1, table.rowCount(), "表里不能留下两行同名模板");
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

    /** 跑一段业务调用，返回它抛的业务码；没抛 BizException 或者抛了别的异常都算失败 */
    private static int bizCode(Runnable body) {
        try {
            body.run();
        } catch (BizException e) {
            return e.getCode();
        }
        throw new AssertionError("期望抛业务码，但没有抛 BizException");
    }

    /** 一次假事务：成功提交，业务异常回滚并把错误码交给断言；其他异常照旧抛出，在测试里等于 500 */
    private static int transaction(FakeCycleTable table, Runnable body) {
        table.begin();
        try {
            body.run();
            table.commit();
            return 0;
        } catch (BizException e) {
            table.rollback();
            return e.getCode();
        } catch (RuntimeException e) {
            table.rollback();
            throw e;
        }
    }

    /** 多个线程同时起跑，返回每个线程的结果 */
    @SafeVarargs
    private static List<Integer> runConcurrently(Callable<Integer>... tasks) throws Exception {
        CyclicBarrier ready = new CyclicBarrier(tasks.length);
        ExecutorService pool = Executors.newFixedThreadPool(tasks.length);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (Callable<Integer> task : tasks) {
                futures.add(pool.submit(() -> {
                    ready.await(5, TimeUnit.SECONDS); // 都准备好后一起开始，不然碰不上并发
                    return task.call();
                }));
            }
            List<Integer> results = new ArrayList<>();
            for (Future<Integer> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 只够用来复现名称竞争的内存表。
     *
     * <p>查重只看得见的已提交行，唯一性推迟到 flush 时才判；看见别人正在插同名时先等它落定——
     * 等价于 PostgreSQL 唯一索引上对未提交键值的等待。有了这个等待，谁先提交谁赢就是确定的，
     * 不会因线程调度顺序而偶发。</p>
     */
    private static final class FakeCycleTable {

        private final Object lock = new Object();
        private final Map<String, CycleTemplate> committed = new LinkedHashMap<>();
        /** 正在插入、尚未提交的名称 → 持有它的事务结束时 countDown 的门闩 */
        private final Map<String, CountDownLatch> inserting = new HashMap<>();
        private final ThreadLocal<List<CycleTemplate>> writes = ThreadLocal.withInitial(ArrayList::new);
        private final ThreadLocal<String> heldName = new ThreadLocal<>();
        private long sequence = 10;

        CycleTemplateRepository repository() {
            CycleTemplateRepository mock = mock(CycleTemplateRepository.class);
            when(mock.existsByName(any())).thenAnswer(inv -> existsByName(inv.getArgument(0)));
            when(mock.saveAndFlush(any(CycleTemplate.class))).thenAnswer(inv -> flush(inv.getArgument(0)));
            when(mock.findFirstByDefaultTemplateTrue()).thenAnswer(inv -> findDefault());
            when(mock.findById(any())).thenAnswer(inv -> findById((Long) inv.getArgument(0)));
            when(mock.findAllByOrderByIdAsc()).thenAnswer(inv -> list());
            return mock;
        }

        void begin() {
            writes.get().clear();
        }

        void commit() {
            synchronized (lock) {
                for (CycleTemplate template : writes.get()) {
                    if (template.getId() == null) {
                        template.setId(++sequence); // 库里 BIGSERIAL 分配的 id
                    }
                    committed.put(template.getName(), template);
                }
                writes.get().clear();
                releaseHeldName();
            }
        }

        void rollback() {
            synchronized (lock) {
                writes.get().clear();
                releaseHeldName();
            }
        }

        int rowCount() {
            synchronized (lock) {
                return committed.size();
            }
        }

        private boolean existsByName(String name) {
            synchronized (lock) {
                return committed.containsKey(name)
                        || writes.get().stream().anyMatch(t -> t.getName().equals(name));
            }
        }

        private CycleTemplate flush(CycleTemplate template) {
            while (true) {
                CountDownLatch blocking;
                synchronized (lock) {
                    if (committed.containsKey(template.getName())) {
                        throw uniqueViolation(CycleTemplateService.NAME_UNIQUE_CONSTRAINT, "name");
                    }
                    blocking = inserting.get(template.getName());
                    if (blocking == null) {
                        releaseHeldName(); // 本表每事务最多持一个名称的锁，够用了
                        inserting.put(template.getName(), new CountDownLatch(1));
                        heldName.set(template.getName());
                        writes.get().add(template);
                        return template;
                    }
                }
                awaitQuietly(blocking); // 同名未提交：等对方落定，和唯一索引上的等待一样
            }
        }

        private Optional<CycleTemplate> findDefault() {
            synchronized (lock) {
                return committed.values().stream().filter(CycleTemplate::isDefaultTemplate).findFirst();
            }
        }

        private Optional<CycleTemplate> findById(Long id) {
            synchronized (lock) {
                return id == null ? Optional.empty()
                        : committed.values().stream().filter(t -> id.equals(t.getId())).findFirst();
            }
        }

        private List<CycleTemplate> list() {
            synchronized (lock) {
                return committed.values().stream().sorted(Comparator.comparing(CycleTemplate::getId)).toList();
            }
        }

        /** 调用方需持有 lock */
        private void releaseHeldName() {
            String name = heldName.get();
            if (name != null) {
                heldName.remove();
                CountDownLatch latch = inserting.remove(name);
                if (latch != null) {
                    latch.countDown();
                }
            }
        }

        private static void awaitQuietly(CountDownLatch latch) {
            try {
                if (!latch.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("等未提交的同名插入落定超时，内存表的名称锁没释放");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }
}
