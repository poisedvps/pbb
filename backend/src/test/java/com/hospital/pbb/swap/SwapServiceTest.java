package com.hospital.pbb.swap;

import com.hospital.pbb.common.BizException;
import com.hospital.pbb.oplog.OpAction;
import com.hospital.pbb.oplog.OpLogService;
import com.hospital.pbb.schedule.ScheduleQueryService;
import com.hospital.pbb.schedule.ScheduleService;
import com.hospital.pbb.schedule.dto.CellChange;
import com.hospital.pbb.staff.Staff;
import com.hospital.pbb.staff.StaffRepository;
import com.hospital.pbb.swap.dto.CreateSwapRequest;
import com.hospital.pbb.swap.dto.SwapVO;
import com.hospital.pbb.user.AuthUser;
import com.hospital.pbb.user.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 调班列表（任务单 M3-03 验收标准）：Mockito 打桩仓库、人员表和
 * {@link ScheduleQueryService#publishedShift}，重点验三件事——
 * 三个 scope 各走哪条查询、没关联人员的账号查不到东西、按钮标志按当前登录人算。
 *
 * <p>M3-04 发起接口的用例、M3-05 确认/拒绝/撤销/审批的用例分别在文件后半部分的两段里，
 * 那两段的 Clock 都固定 2026-10-09，与列表用例用的 2026-10-08 无关。</p>
 */
class SwapServiceTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 8);
    private static final LocalDate OTHER_DAY = LocalDate.of(2026, 10, 9);

    /** 科长：账号不关联人员，staffId 为 null */
    private static final AuthUser ADMIN = new AuthUser(1L, "admin", Role.ADMIN, null);
    /** 成员 张三，staffId=1 */
    private static final AuthUser ZHANG = new AuthUser(2L, "N001", Role.MEMBER, 1L);
    /** 成员 李四，staffId=2 */
    private static final AuthUser LI = new AuthUser(3L, "N002", Role.MEMBER, 2L);

    private SwapRequestRepository repo;
    private StaffRepository staffRepo;
    private ScheduleQueryService query;
    private SwapService service;

    @BeforeEach
    void setUp() {
        repo = mock(SwapRequestRepository.class);
        staffRepo = mock(StaffRepository.class);
        query = mock(ScheduleQueryService.class);
        service = new SwapService(repo, staffRepo, query, mock(ScheduleService.class),
                mock(OpLogService.class), Clock.fixed(Instant.parse("2026-10-08T01:00:00Z"), ZoneOffset.UTC));

        // 默认：人员表里只有张三、李四；所有格子都查不到已发布班次
        when(staffRepo.findAll()).thenReturn(List.of(staff(1L, "张三"), staff(2L, "李四")));
        when(query.publishedShift(any(), any())).thenReturn(Optional.empty());
    }

    private static Staff staff(Long id, String name) {
        Staff staff = new Staff();
        staff.setId(id);
        staff.setEmpNo("N00" + id);
        staff.setName(name);
        staff.setSchedulable(true);
        staff.setActive(true);
        return staff;
    }

    /** 一条换班申请：张三（申请人）10-08 想和李四（对方）10-09 换。 */
    private static SwapRequest request(Long id, SwapType type, SwapStatus status, Long applicantId, Long targetId) {
        SwapRequest r = new SwapRequest();
        r.setId(id);
        r.setType(type);
        r.setStatus(status);
        r.setApplicantStaffId(applicantId);
        r.setApplicantDate(DAY);
        r.setTargetStaffId(targetId);
        r.setTargetDate(targetId == null ? null : OTHER_DAY);
        r.setReason("家里有事");
        return r;
    }

    // ---------- scope 与角色 ----------

    @Test
    void memberTodoQueriesPendingPeerWhereTargetIsSelf() {
        when(repo.findByStatusAndTargetStaffIdOrderByIdDesc(SwapStatus.PENDING_PEER, 1L)).thenReturn(List.of());

        assertEquals(List.of(), service.list(ZHANG, "TODO"));

        verify(repo).findByStatusAndTargetStaffIdOrderByIdDesc(SwapStatus.PENDING_PEER, 1L);
        verify(repo, never()).findByStatusOrderByIdDesc(any());
    }

    @Test
    void memberWithoutStaffReturnsEmptyWithoutTouchingRepository() {
        // 大屏账号或没绑定人员的账号：一个 staffId 都没有，列表必须是空的且不查库
        assertEquals(List.of(), service.list(new AuthUser(9L, "screen", Role.MEMBER, null), "ALL"));
        assertEquals(List.of(), service.list(new AuthUser(9L, "screen", Role.MEMBER, null), "TODO"));
        assertEquals(List.of(), service.list(new AuthUser(9L, "screen", Role.MEMBER, null), "MINE"));

        verifyNoInteractions(repo);
    }

    @Test
    void adminTodoQueriesAllPendingAdmin() {
        when(repo.findByStatusOrderByIdDesc(SwapStatus.PENDING_ADMIN)).thenReturn(List.of());

        service.list(ADMIN, "TODO");

        verify(repo).findByStatusOrderByIdDesc(SwapStatus.PENDING_ADMIN);
        verify(repo, never()).findByStatusAndTargetStaffIdOrderByIdDesc(any(), any());
    }

    @Test
    void adminAllAndMineUseTheirOwnQueries() {
        AuthUser adminWithStaff = new AuthUser(4L, "admin2", Role.ADMIN, 7L);
        when(repo.findAllByOrderByIdDesc()).thenReturn(List.of());
        when(repo.findByApplicantStaffIdOrderByIdDesc(7L)).thenReturn(List.of());

        service.list(adminWithStaff, "ALL");
        service.list(adminWithStaff, "MINE");

        verify(repo).findAllByOrderByIdDesc();
        verify(repo).findByApplicantStaffIdOrderByIdDesc(7L);
    }

    @Test
    void memberAllListsBothApplicantAndTargetAndMineOnlyApplicant() {
        when(repo.findByApplicantStaffIdOrTargetStaffIdOrderByIdDesc(1L, 1L)).thenReturn(List.of());
        when(repo.findByApplicantStaffIdOrderByIdDesc(1L)).thenReturn(List.of());

        service.list(ZHANG, "ALL");
        service.list(ZHANG, "MINE");

        verify(repo).findByApplicantStaffIdOrTargetStaffIdOrderByIdDesc(1L, 1L);
        verify(repo).findByApplicantStaffIdOrderByIdDesc(1L);
    }

    @Test
    void nullScopeTreatedAsAll() {
        when(repo.findByApplicantStaffIdOrTargetStaffIdOrderByIdDesc(2L, 2L)).thenReturn(List.of());
        when(repo.findAllByOrderByIdDesc()).thenReturn(List.of());

        service.list(LI, null);
        service.list(ADMIN, null);

        verify(repo).findByApplicantStaffIdOrTargetStaffIdOrderByIdDesc(2L, 2L);
        verify(repo).findAllByOrderByIdDesc();
    }

    // ---------- 字段与按钮标志 ----------

    @Test
    void noIsIdPaddedToFourDigits() {
        when(repo.findAllByOrderByIdDesc()).thenReturn(List.of(
                request(3L, SwapType.SWAP, SwapStatus.PENDING_ADMIN, 1L, 2L),
                request(12345L, SwapType.LEAVE, SwapStatus.APPROVED, 1L, null)));

        List<SwapVO> list = service.list(ADMIN, "ALL");

        assertEquals("TB-0003", list.get(0).no());
        assertEquals("TB-12345", list.get(1).no());
        assertEquals(Long.valueOf(3L), list.get(0).id());
    }

    @Test
    void namesAndShiftsComeFromStaffTableAndPublishedSnapshot() {
        when(repo.findAllByOrderByIdDesc())
                .thenReturn(List.of(request(1L, SwapType.SWAP, SwapStatus.PENDING_PEER, 1L, 2L)));
        when(query.publishedShift(1L, DAY)).thenReturn(Optional.of("D"));
        when(query.publishedShift(2L, OTHER_DAY)).thenReturn(Optional.of("N"));

        SwapVO vo = service.list(ADMIN, "ALL").get(0);

        assertEquals("张三", vo.applicantName());
        assertEquals("李四", vo.targetName());
        assertEquals("D", vo.applicantShift());
        assertEquals("N", vo.targetShift());
        assertEquals(DAY, vo.applicantDate());
        assertEquals(OTHER_DAY, vo.targetDate());
        assertEquals("家里有事", vo.reason());
    }

    /** 请假没有对方，列表不显示对方班次，姓名也为 null。 */
    @Test
    void leaveHasNoTargetShiftAndNoTargetName() {
        when(repo.findAllByOrderByIdDesc())
                .thenReturn(List.of(request(2L, SwapType.LEAVE, SwapStatus.PENDING_ADMIN, 1L, null)));
        when(query.publishedShift(1L, DAY)).thenReturn(Optional.of("D"));

        SwapVO vo = service.list(ADMIN, "ALL").get(0);

        assertNull(vo.targetShift());
        assertNull(vo.targetName());
        assertNull(vo.targetDate());
        assertEquals("D", vo.applicantShift());
    }

    /** 查不到已发布快照（那月还没发布）时班次为 null，其余字段照常返回。 */
    @Test
    void missingPublishedShiftMeansNullShift() {
        when(repo.findByApplicantStaffIdOrderByIdDesc(1L))
                .thenReturn(List.of(request(4L, SwapType.COVER, SwapStatus.PENDING_PEER, 1L, 2L)));

        SwapVO vo = service.list(ZHANG, "MINE").get(0);

        assertNull(vo.applicantShift());
        assertNull(vo.targetShift());
    }

    @Test
    void pendingPeerWhereTargetIsSelfCanConfirmOnly() {
        when(repo.findByStatusAndTargetStaffIdOrderByIdDesc(SwapStatus.PENDING_PEER, 2L))
                .thenReturn(List.of(request(5L, SwapType.SWAP, SwapStatus.PENDING_PEER, 1L, 2L)));

        SwapVO vo = service.list(LI, "TODO").get(0);

        assertTrue(vo.canConfirm());
        assertFalse(vo.canCancel());
        assertFalse(vo.canApprove());
    }

    /** 同样是 PENDING_PEER，申请人自己不能确认，只能撤销。 */
    @Test
    void pendingPeerWhereApplicantIsSelfCanCancelOnly() {
        when(repo.findByApplicantStaffIdOrderByIdDesc(1L))
                .thenReturn(List.of(request(6L, SwapType.SWAP, SwapStatus.PENDING_PEER, 1L, 2L)));

        SwapVO vo = service.list(ZHANG, "MINE").get(0);

        assertFalse(vo.canConfirm());
        assertTrue(vo.canCancel());
        assertFalse(vo.canApprove());
    }

    @Test
    void pendingAdminSeenByAdminCanApprove() {
        when(repo.findByStatusOrderByIdDesc(SwapStatus.PENDING_ADMIN))
                .thenReturn(List.of(request(7L, SwapType.SWAP, SwapStatus.PENDING_ADMIN, 1L, 2L)));

        SwapVO vo = service.list(ADMIN, "TODO").get(0);

        assertTrue(vo.canApprove());
        assertFalse(vo.canConfirm());
        // 科长不是申请人，不能替他撤销
        assertFalse(vo.canCancel());
    }

    /** 等审批的记录，申请人还能撤销，但不是科长，不能审批。 */
    @Test
    void pendingAdminSeenByApplicantCanCancelButNotApprove() {
        when(repo.findByApplicantStaffIdOrderByIdDesc(1L))
                .thenReturn(List.of(request(8L, SwapType.LEAVE, SwapStatus.PENDING_ADMIN, 1L, null)));

        SwapVO vo = service.list(ZHANG, "MINE").get(0);

        assertTrue(vo.canCancel());
        assertFalse(vo.canApprove());
        assertFalse(vo.canConfirm());
    }

    /** 审批完结的记录谁都不能再操作。 */
    @Test
    void finishedRequestHasNoButtons() {
        when(repo.findByApplicantStaffIdOrTargetStaffIdOrderByIdDesc(1L, 1L))
                .thenReturn(List.of(request(9L, SwapType.SWAP, SwapStatus.APPROVED, 1L, 2L),
                        request(10L, SwapType.SWAP, SwapStatus.REJECTED, 1L, 2L),
                        request(11L, SwapType.SWAP, SwapStatus.CANCELLED, 1L, 2L)));

        for (SwapVO vo : service.list(ZHANG, "ALL")) {
            assertFalse(vo.canConfirm(), vo.no());
            assertFalse(vo.canCancel(), vo.no());
            assertFalse(vo.canApprove(), vo.no());
        }
    }

    // ==================== 发起申请（任务单 M3-04）====================

    /** M3-04 用例里的“今天” */
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 9);
    /** 本人要调的那一天 */
    private static final LocalDate LEAVE_DAY = LocalDate.of(2026, 10, 12);
    /** 换班时对方的那一天 */
    private static final LocalDate SWAP_DAY = LocalDate.of(2026, 10, 13);

    private OpLogService opLog;

    /**
     * 发起用例专用的 service：Clock 固定 2026-10-09，仓库、人员表、已发布班次沿用 setUp 的打桩，
     * {@link OpLogService} 单独一个 mock 以便校验留痕内容。
     */
    private SwapService createService() {
        opLog = mock(OpLogService.class);
        return new SwapService(repo, staffRepo, query, mock(ScheduleService.class), opLog,
                Clock.fixed(Instant.parse("2026-10-09T01:00:00Z"), ZoneOffset.UTC));
    }

    /** 让 save 回一个 id=7 的对象，模拟入库后拿到主键（单据号要用）。 */
    private void stubSave() {
        when(repo.save(any(SwapRequest.class))).thenAnswer(invocation -> {
            SwapRequest arg = invocation.getArgument(0);
            arg.setId(7L);
            return arg;
        });
    }

    /** 张三、李四都在职且参与排班。 */
    private void stubBothStaffActive() {
        when(staffRepo.findById(1L)).thenReturn(Optional.of(staff(1L, "张三")));
        when(staffRepo.findById(2L)).thenReturn(Optional.of(staff(2L, "李四")));
    }

    /** 四个格子都有已发布班次：张三 10-12、10-13，李四 10-12、10-13。 */
    private void stubAllPublished() {
        when(query.publishedShift(eq(1L), any())).thenReturn(Optional.of("D"));
        when(query.publishedShift(eq(2L), any())).thenReturn(Optional.of("N"));
    }

    private static BizException createFails(SwapService service, CreateSwapRequest req, AuthUser me) {
        return assertThrows(BizException.class, () -> service.create(req, me));
    }

    @Test
    void leaveSavesPendingAdminAndDropsTarget() {
        SwapService create = createService();
        stubSave();
        when(query.publishedShift(1L, LEAVE_DAY)).thenReturn(Optional.of("D"));

        // LEAVE 传了对方人员和日期也要丢弃，请假没有“对方”
        SwapVO vo = create.create(new CreateSwapRequest(SwapType.LEAVE, LEAVE_DAY, 2L, SWAP_DAY, "事假"), ZHANG);

        ArgumentCaptor<SwapRequest> captor = ArgumentCaptor.forClass(SwapRequest.class);
        verify(repo).save(captor.capture());
        SwapRequest saved = captor.getValue();
        assertEquals(SwapStatus.PENDING_ADMIN, saved.getStatus());
        assertEquals(SwapType.LEAVE, saved.getType());
        assertEquals(Long.valueOf(1L), saved.getApplicantStaffId());
        assertEquals(LEAVE_DAY, saved.getApplicantDate());
        assertNull(saved.getTargetStaffId());
        assertNull(saved.getTargetDate());
        assertEquals("事假", saved.getReason());

        // 返回值就是保存后的那条，单据号取刚拿到的主键
        assertEquals(Long.valueOf(7L), vo.id());
        assertEquals("TB-0007", vo.no());
        assertEquals(SwapStatus.PENDING_ADMIN, vo.status());
        assertNull(vo.targetStaffId());
        assertTrue(vo.canCancel());
        assertFalse(vo.canConfirm());

        // LEAVE 不查对方人员
        verify(staffRepo, never()).findById(any());
        verify(opLog).record(OpAction.CREATE_SWAP, "TB-0007", "LEAVE 2026-10-12");
    }

    @Test
    void swapSavesPendingPeerWithTargetStaffAndDate() {
        SwapService create = createService();
        stubSave();
        stubBothStaffActive();
        stubAllPublished();

        SwapVO vo = create.create(new CreateSwapRequest(SwapType.SWAP, LEAVE_DAY, 2L, SWAP_DAY, "家里有事"), ZHANG);

        ArgumentCaptor<SwapRequest> captor = ArgumentCaptor.forClass(SwapRequest.class);
        verify(repo).save(captor.capture());
        SwapRequest saved = captor.getValue();
        assertEquals(SwapStatus.PENDING_PEER, saved.getStatus());
        assertEquals(Long.valueOf(2L), saved.getTargetStaffId());
        assertEquals(SWAP_DAY, saved.getTargetDate());
        assertEquals(SwapStatus.PENDING_PEER, vo.status());
        assertEquals("李四", vo.targetName());
        assertEquals("N", vo.targetShift());
        // 对方才是能确认的人
        assertFalse(vo.canConfirm());
        assertTrue(vo.canCancel());
    }

    @Test
    void coverIgnoresTargetDate() {
        SwapService create = createService();
        stubSave();
        stubBothStaffActive();
        when(query.publishedShift(eq(1L), any())).thenReturn(Optional.of("D"));
        when(query.publishedShift(eq(2L), any())).thenReturn(Optional.of("N"));

        // 替班只要对方来上本人那天，传了对方日期也不存
        create.create(new CreateSwapRequest(SwapType.COVER, LEAVE_DAY, 2L, SWAP_DAY, null), ZHANG);

        ArgumentCaptor<SwapRequest> captor = ArgumentCaptor.forClass(SwapRequest.class);
        verify(repo).save(captor.capture());
        assertEquals(SwapStatus.PENDING_PEER, captor.getValue().getStatus());
        assertEquals(Long.valueOf(2L), captor.getValue().getTargetStaffId());
        assertNull(captor.getValue().getTargetDate());
        assertNull(captor.getValue().getReason());
    }

    /** 科长账号不关联人员，连申请人是谁都答不上来。 */
    @Test
    void accountWithoutStaffCannotCreate() {
        BizException e = createFails(createService(),
                new CreateSwapRequest(SwapType.LEAVE, LEAVE_DAY, null, null, null), ADMIN);

        assertEquals(1601, e.getCode());
        assertEquals("当前账号未关联人员，不能申请调班", e.getMessage());
        verifyNoInteractions(repo);
    }

    @Test
    void applicantDateBeforeTodayRejected() {
        BizException e = createFails(createService(),
                new CreateSwapRequest(SwapType.LEAVE, TODAY.minusDays(1), null, null, null), ZHANG);

        assertEquals(1603, e.getCode());
        assertEquals("只能申请今天及以后的日期", e.getMessage());
        verify(repo, never()).save(any());
    }

    /** 昨天已过、10-12 未过：applicantDate 通过，targetDate 早于今天同样拦下。 */
    @Test
    void swapTargetDateBeforeTodayRejected() {
        stubBothStaffActive();

        BizException e = createFails(createService(),
                new CreateSwapRequest(SwapType.SWAP, LEAVE_DAY, 2L, TODAY.minusDays(1), null), ZHANG);

        assertEquals(1603, e.getCode());
    }

    @Test
    void swapWithSelfRejected() {
        BizException e = createFails(createService(),
                new CreateSwapRequest(SwapType.SWAP, LEAVE_DAY, 1L, SWAP_DAY, null), ZHANG);

        assertEquals(1604, e.getCode());
        assertEquals("对方不能是自己", e.getMessage());
    }

    /** 换班必须有对方日期，替班不需要，所以只有 SWAP 报 1606。 */
    @Test
    void swapWithoutTargetDateRejected() {
        stubBothStaffActive();

        BizException e = createFails(createService(),
                new CreateSwapRequest(SwapType.SWAP, LEAVE_DAY, 2L, null, null), ZHANG);

        assertEquals(1606, e.getCode());
        assertEquals("换班必须选择对方日期", e.getMessage());
    }

    @Test
    void missingTargetStaffRejected() {
        BizException e = createFails(createService(),
                new CreateSwapRequest(SwapType.SWAP, LEAVE_DAY, null, SWAP_DAY, null), ZHANG);

        assertEquals(1606, e.getCode());
        assertEquals("请选择对方人员", e.getMessage());
    }

    /** 停用、不参与排班、查不到三种情况都是 1605。 */
    @Test
    void invalidTargetStaffRejected() {
        Staff inactive = staff(2L, "李四");
        inactive.setActive(false);
        Staff notSchedulable = staff(2L, "李四");
        notSchedulable.setSchedulable(false);
        SwapService create = createService();

        when(staffRepo.findById(2L)).thenReturn(Optional.of(inactive));
        assertEquals(1605, createFails(create,
                new CreateSwapRequest(SwapType.COVER, LEAVE_DAY, 2L, null, null), ZHANG).getCode());

        when(staffRepo.findById(2L)).thenReturn(Optional.of(notSchedulable));
        assertEquals(1605, createFails(create,
                new CreateSwapRequest(SwapType.COVER, LEAVE_DAY, 2L, null, null), ZHANG).getCode());

        when(staffRepo.findById(2L)).thenReturn(Optional.empty());
        assertEquals(1605, createFails(create,
                new CreateSwapRequest(SwapType.COVER, LEAVE_DAY, 2L, null, null), ZHANG).getCode());

        verify(repo, never()).save(any());
    }

    /** 本人那天只有草稿、没发布过，成员看到的日历上没有这个班，不能换。 */
    @Test
    void applicantWithoutPublishedShiftRejected() {
        SwapService create = createService();

        BizException e = createFails(create, new CreateSwapRequest(SwapType.LEAVE, LEAVE_DAY, null, null, null), ZHANG);

        assertEquals(1602, e.getCode());
        assertEquals("该日期没有已发布的班次", e.getMessage());
        verify(repo, never()).save(any());
    }

    /** 换班要四格都有，对方那天空着就换不成。 */
    @Test
    void swapNeedsPublishedShiftOnBothSides() {
        SwapService create = createService();
        stubBothStaffActive();
        when(query.publishedShift(eq(1L), any())).thenReturn(Optional.of("D"));
        when(query.publishedShift(eq(2L), any())).thenReturn(Optional.of("N"));
        // 对方 10-13 没班
        when(query.publishedShift(2L, SWAP_DAY)).thenReturn(Optional.empty());

        BizException e = createFails(create,
                new CreateSwapRequest(SwapType.SWAP, LEAVE_DAY, 2L, SWAP_DAY, null), ZHANG);

        assertEquals(1602, e.getCode());
        assertEquals("对方日期没有已发布的班次", e.getMessage());
    }

    /** 替班只看对方在本人那天有没有班，对方的其它日期不影响。 */
    @Test
    void coverNeedsTargetPublishedShiftOnApplicantDate() {
        SwapService create = createService();
        stubBothStaffActive();
        when(query.publishedShift(1L, LEAVE_DAY)).thenReturn(Optional.of("D"));

        BizException e = createFails(create,
                new CreateSwapRequest(SwapType.COVER, LEAVE_DAY, 2L, null, null), ZHANG);

        assertEquals(1602, e.getCode());
        assertEquals("对方日期没有已发布的班次", e.getMessage());
    }

    @Test
    void pendingRequestOnSameDateRejected() {
        SwapService create = createService();
        when(query.publishedShift(1L, LEAVE_DAY)).thenReturn(Optional.of("D"));
        when(repo.existsByApplicantStaffIdAndApplicantDateAndStatusIn(1L, LEAVE_DAY,
                List.of(SwapStatus.PENDING_PEER, SwapStatus.PENDING_ADMIN))).thenReturn(true);

        BizException e = createFails(create, new CreateSwapRequest(SwapType.LEAVE, LEAVE_DAY, null, null, null), ZHANG);

        assertEquals(1607, e.getCode());
        assertEquals("该日期已有进行中的申请", e.getMessage());
        verify(repo, never()).save(any());
    }

    /** 全空格的理由与不填一样，存 null。 */
    @Test
    void blankReasonStoredAsNull() {
        SwapService create = createService();
        stubSave();
        when(query.publishedShift(1L, LEAVE_DAY)).thenReturn(Optional.of("D"));

        SwapVO vo = create.create(new CreateSwapRequest(SwapType.LEAVE, LEAVE_DAY, null, null, "   "), ZHANG);

        assertNull(vo.reason());
    }

    // ==================== 确认 / 拒绝 / 撤销 / 审批（任务单 M3-05）====================

    /** M3-05 用例里的“现在”，回写的 peerConfirmedAt、reviewedAt 都以它为准 */
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-09T01:00:00Z");

    /** 第三人（staffId=3），既不是申请人也不是对方 */
    private static final AuthUser WANG = new AuthUser(5L, "N003", Role.MEMBER, 3L);

    private ScheduleService schedule;

    /**
     * 流程用例专用的 service：Clock 固定 2026-10-09，{@link ScheduleService} 与
     * {@link OpLogService} 各给一个 mock，前者验回写参数、后者验留痕，仓库与人员表沿用 setUp 的打桩。
     */
    private SwapService reviewService() {
        opLog = mock(OpLogService.class);
        schedule = mock(ScheduleService.class);
        return new SwapService(repo, staffRepo, query, schedule, opLog,
                Clock.fixed(Instant.parse("2026-10-09T01:00:00Z"), ZoneOffset.UTC));
    }

    /**
     * 一条张三（staffId=1）10-12 的申请，LEAVE 没有对方，其余类型对方都是李四（staffId=2）。
     * 直接返回实体本身，方便用例断言字段是被改在这条记录上（save 打桩原样返回入参）。
     */
    private SwapRequest stubFound(Long id, SwapType type, SwapStatus status, LocalDate targetDate) {
        SwapRequest request = new SwapRequest();
        request.setId(id);
        request.setType(type);
        request.setStatus(status);
        request.setApplicantStaffId(1L);
        request.setApplicantDate(LEAVE_DAY);
        request.setTargetStaffId(type == SwapType.LEAVE ? null : 2L);
        request.setTargetDate(targetDate);
        request.setReason("家里有事");
        when(repo.findById(id)).thenReturn(Optional.of(request));
        when(repo.save(any(SwapRequest.class))).thenAnswer(invocation -> invocation.getArgument(0));
        return request;
    }

    /** 捕获回写给排班的 changes。 */
    @SuppressWarnings("unchecked")
    private List<CellChange> capturedChanges() {
        ArgumentCaptor<List<CellChange>> captor = ArgumentCaptor.forClass(List.class);
        verify(schedule).applyChanges(captor.capture(), eq("调班 TB-0007"), eq(1L));
        return captor.getValue();
    }

    // ---------- 对方确认 / 拒绝 ----------

    @Test
    void peerConfirmMovesToPendingAdminAndStampsTime() {
        SwapService review = reviewService();
        SwapRequest request = stubFound(7L, SwapType.SWAP, SwapStatus.PENDING_PEER, SWAP_DAY);

        SwapVO vo = review.confirm(7L, LI);

        assertEquals(SwapStatus.PENDING_ADMIN, request.getStatus());
        assertNotNull(request.getPeerConfirmedAt());
        assertEquals(NOW, request.getPeerConfirmedAt());
        // 对方点头不改排班，也不记审批人
        verifyNoInteractions(schedule);
        assertNull(request.getReviewedBy());
        assertEquals(SwapStatus.PENDING_ADMIN, vo.status());
        // 对 LI 来说这条已经不需要他确认了，接下来轮到科长
        assertFalse(vo.canConfirm());
        assertFalse(vo.canApprove());
        verify(opLog).record(OpAction.CONFIRM_SWAP, "TB-0007", "SWAP 2026-10-12");
    }

    /** 第三人（既不是申请人也不是对方）确认 → 1609；申请人自己确认也算越权。 */
    @Test
    void onlyTargetStaffCanConfirm() {
        SwapService review = reviewService();
        SwapRequest request = stubFound(7L, SwapType.SWAP, SwapStatus.PENDING_PEER, SWAP_DAY);

        BizException e = assertThrows(BizException.class, () -> review.confirm(7L, WANG));

        assertEquals(1609, e.getCode());
        assertEquals("无权操作该申请", e.getMessage());
        assertEquals(1609, assertThrows(BizException.class, () -> review.confirm(7L, ZHANG)).getCode());
        assertEquals(1609, assertThrows(BizException.class, () -> review.rejectPeer(7L, WANG)).getCode());
        // 科长账号 staffId 为 null，同样不是对方
        assertEquals(1609, assertThrows(BizException.class, () -> review.confirm(7L, ADMIN)).getCode());

        assertEquals(SwapStatus.PENDING_PEER, request.getStatus());
        verify(repo, never()).save(any());
    }

    /** 已经批完/驳回/撤销的记录，对方再确认就是流程错了 → 1608。 */
    @Test
    void confirmRequiresPendingPeer() {
        SwapService review = reviewService();
        stubFound(7L, SwapType.SWAP, SwapStatus.PENDING_ADMIN, SWAP_DAY);

        BizException e = assertThrows(BizException.class, () -> review.confirm(7L, LI));

        assertEquals(1608, e.getCode());
        assertEquals("当前状态不允许此操作", e.getMessage());
        verify(repo, never()).save(any());
    }

    @Test
    void peerRejectEndsRequestWithoutTouchingSchedule() {
        SwapService review = reviewService();
        SwapRequest request = stubFound(7L, SwapType.COVER, SwapStatus.PENDING_PEER, null);

        SwapVO vo = review.rejectPeer(7L, LI);

        assertEquals(SwapStatus.REJECTED, request.getStatus());
        assertNull(request.getPeerConfirmedAt());
        verifyNoInteractions(schedule);
        assertEquals(SwapStatus.REJECTED, vo.status());
        assertFalse(vo.canConfirm());
        assertFalse(vo.canCancel());
        verify(opLog).record(OpAction.REJECT_SWAP_PEER, "TB-0007", "COVER 2026-10-12");
    }

    // ---------- 申请人撤销 ----------

    /** 两个待处理状态申请人都能撤。 */
    @Test
    void applicantCancelsInBothPendingStates() {
        SwapService review = reviewService();
        SwapRequest peer = stubFound(7L, SwapType.SWAP, SwapStatus.PENDING_PEER, SWAP_DAY);
        review.cancel(7L, ZHANG);
        assertEquals(SwapStatus.CANCELLED, peer.getStatus());

        SwapRequest pendingAdmin = stubFound(8L, SwapType.LEAVE, SwapStatus.PENDING_ADMIN, null);
        SwapVO vo = review.cancel(8L, ZHANG);

        assertEquals(SwapStatus.CANCELLED, pendingAdmin.getStatus());
        assertEquals(SwapStatus.CANCELLED, vo.status());
        assertFalse(vo.canCancel());
        verify(opLog).record(OpAction.CANCEL_SWAP, "TB-0008", "LEAVE 2026-10-12");
    }

    /** 流程走完就不能撤销成“没发生过”，想反悔只能重新发一条。 */
    @Test
    void cancelAfterApprovedRejected() {
        SwapService review = reviewService();
        SwapRequest request = stubFound(7L, SwapType.SWAP, SwapStatus.APPROVED, SWAP_DAY);

        BizException e = assertThrows(BizException.class, () -> review.cancel(7L, ZHANG));

        assertEquals(1608, e.getCode());
        assertEquals("当前状态不允许此操作", e.getMessage());
        assertEquals(SwapStatus.APPROVED, request.getStatus());
        verify(repo, never()).save(any());
    }

    /** 撤销是申请人的权利，对方（哪怕是待确认状态）不能替他撤。 */
    @Test
    void onlyApplicantCanCancel() {
        SwapService review = reviewService();
        SwapRequest request = stubFound(7L, SwapType.SWAP, SwapStatus.PENDING_PEER, SWAP_DAY);

        BizException e = assertThrows(BizException.class, () -> review.cancel(7L, LI));

        assertEquals(1609, e.getCode());
        assertEquals("无权操作该申请", e.getMessage());
        assertEquals(1609, assertThrows(BizException.class, () -> review.cancel(7L, WANG)).getCode());
        assertEquals(SwapStatus.PENDING_PEER, request.getStatus());
        verify(repo, never()).save(any());
    }

    // ---------- 科长驳回 ----------

    @Test
    void adminRejectWritesReviewAndKeepsSchedule() {
        SwapService review = reviewService();
        SwapRequest request = stubFound(7L, SwapType.SWAP, SwapStatus.PENDING_ADMIN, SWAP_DAY);

        SwapVO vo = review.reject(7L, "科室没人顶，不换", ADMIN);

        assertEquals(SwapStatus.REJECTED, request.getStatus());
        assertEquals(Long.valueOf(1L), request.getReviewedBy());
        assertEquals(NOW, request.getReviewedAt());
        assertEquals("科室没人顶，不换", request.getReviewComment());
        assertEquals("科室没人顶，不换", vo.reviewComment());
        verifyNoInteractions(schedule);
        verify(opLog).record(OpAction.REJECT_SWAP, "TB-0007", "SWAP 2026-10-12");
    }

    /** 不传意见（Controller 那边 body 缺省时传 null）与传全空格一样，存 null。 */
    @Test
    void reviewCommentBlankStoredAsNull() {
        SwapService review = reviewService();
        SwapRequest request = stubFound(7L, SwapType.LEAVE, SwapStatus.PENDING_ADMIN, null);

        review.reject(7L, "   ", ADMIN);

        assertNull(request.getReviewComment());
    }

    /** 还在等对方确认的申请轮不到科长审批。 */
    @Test
    void reviewRequiresPendingAdmin() {
        SwapService review = reviewService();
        SwapRequest request = stubFound(7L, SwapType.LEAVE, SwapStatus.PENDING_PEER, null);

        BizException approve = assertThrows(BizException.class, () -> review.approve(7L, null, ADMIN));

        assertEquals(1608, approve.getCode());
        assertEquals("当前状态不允许此操作", approve.getMessage());
        assertEquals(1608, assertThrows(BizException.class, () -> review.reject(7L, null, ADMIN)).getCode());
        assertEquals(SwapStatus.PENDING_PEER, request.getStatus());
        verifyNoInteractions(schedule);
        verify(repo, never()).save(any());
    }

    // ---------- 科长审批通过：回写口径 ----------

    @Test
    void approveLeaveWritesLeaveOnly() {
        SwapService review = reviewService();
        SwapRequest request = stubFound(7L, SwapType.LEAVE, SwapStatus.PENDING_ADMIN, null);
        when(query.publishedShift(1L, LEAVE_DAY)).thenReturn(Optional.of("D"));

        SwapVO vo = review.approve(7L, "同意", ADMIN);

        assertEquals(List.of(new CellChange(1L, LEAVE_DAY, "L")), capturedChanges());
        assertEquals(SwapStatus.APPROVED, request.getStatus());
        assertEquals(Long.valueOf(1L), request.getReviewedBy());
        assertEquals(NOW, request.getReviewedAt());
        assertEquals("同意", request.getReviewComment());
        assertEquals(SwapStatus.APPROVED, vo.status());
        assertFalse(vo.canApprove());
        verify(opLog).record(OpAction.APPROVE_SWAP, "TB-0007", "LEAVE 2026-10-12，回写1格");
    }

    /** 替班：对方来上本人那个班（原班次 N 跟着走），本人改成休息。 */
    @Test
    void approveCoverGivesOriginalShiftToPeer() {
        SwapService review = reviewService();
        SwapRequest request = stubFound(7L, SwapType.COVER, SwapStatus.PENDING_ADMIN, null);
        when(query.publishedShift(1L, LEAVE_DAY)).thenReturn(Optional.of("N"));

        review.approve(7L, null, ADMIN);

        assertEquals(List.of(new CellChange(2L, LEAVE_DAY, "N"), new CellChange(1L, LEAVE_DAY, "X")),
                capturedChanges());
        assertEquals(SwapStatus.APPROVED, request.getStatus());
    }

    /** 换班跨两天就换两天，共 4 格，顺序按日期。 */
    @Test
    void approveSwapSwapsBothDays() {
        SwapService review = reviewService();
        SwapRequest request = stubFound(7L, SwapType.SWAP, SwapStatus.PENDING_ADMIN, SWAP_DAY);
        // 张三 10-12=N、10-13=D；李四 10-12=D、10-13=N
        when(query.publishedShift(1L, LEAVE_DAY)).thenReturn(Optional.of("N"));
        when(query.publishedShift(1L, SWAP_DAY)).thenReturn(Optional.of("D"));
        when(query.publishedShift(2L, LEAVE_DAY)).thenReturn(Optional.of("D"));
        when(query.publishedShift(2L, SWAP_DAY)).thenReturn(Optional.of("N"));

        review.approve(7L, null, ADMIN);

        assertEquals(List.of(
                new CellChange(1L, LEAVE_DAY, "D"), new CellChange(2L, LEAVE_DAY, "N"),
                new CellChange(1L, SWAP_DAY, "N"), new CellChange(2L, SWAP_DAY, "D")), capturedChanges());
        assertEquals(SwapStatus.APPROVED, request.getStatus());
    }

    /** 同一天互换只算一天两格，不能把同一格写两遍（第二遍会换回原样）。 */
    @Test
    void approveSwapOnSameDayWritesTwoCells() {
        SwapService review = reviewService();
        stubFound(7L, SwapType.SWAP, SwapStatus.PENDING_ADMIN, LEAVE_DAY);
        when(query.publishedShift(1L, LEAVE_DAY)).thenReturn(Optional.of("N"));
        when(query.publishedShift(2L, LEAVE_DAY)).thenReturn(Optional.of("D"));

        review.approve(7L, null, ADMIN);

        assertEquals(List.of(new CellChange(1L, LEAVE_DAY, "D"), new CellChange(2L, LEAVE_DAY, "N")),
                capturedChanges());
    }

    /**
     * 审批时某一格已被改动（无已发布班次）：1602 报错且不写排班、不改状态，
     * 科长驳回后让申请人重新发。
     */
    @Test
    void approveWhenScheduleChangedWritesNothing() {
        SwapService review = reviewService();
        SwapRequest request = stubFound(7L, SwapType.SWAP, SwapStatus.PENDING_ADMIN, SWAP_DAY);
        when(query.publishedShift(1L, LEAVE_DAY)).thenReturn(Optional.of("N"));
        when(query.publishedShift(1L, SWAP_DAY)).thenReturn(Optional.of("D"));
        when(query.publishedShift(2L, LEAVE_DAY)).thenReturn(Optional.of("D"));
        // 李四 10-13 已被改成没班
        when(query.publishedShift(2L, SWAP_DAY)).thenReturn(Optional.empty());

        BizException e = assertThrows(BizException.class, () -> review.approve(7L, null, ADMIN));

        assertEquals(1602, e.getCode());
        assertEquals("排班已变化，请驳回后重新申请", e.getMessage());
        verify(schedule, never()).applyChanges(any(), any(), any());
        verify(repo, never()).save(any());
        assertEquals(SwapStatus.PENDING_ADMIN, request.getStatus());
    }

    /** 替班时本人的班被改没了，同样不能回写。 */
    @Test
    void approveCoverWhenOwnShiftGoneWritesNothing() {
        SwapService review = reviewService();
        stubFound(7L, SwapType.COVER, SwapStatus.PENDING_ADMIN, null);

        BizException e = assertThrows(BizException.class, () -> review.approve(7L, null, ADMIN));

        assertEquals(1602, e.getCode());
        verify(schedule, never()).applyChanges(any(), any(), any());
    }

    // ---------- 单据不存在 ----------

    /** 五个方法都要在查不到时回 1600，而且一步都不往下走。 */
    @Test
    void missingRequestIs1600() {
        SwapService review = reviewService();
        when(repo.findById(999L)).thenReturn(Optional.empty());

        assertEquals(1600, assertThrows(BizException.class, () -> review.confirm(999L, LI)).getCode());
        assertEquals(1600, assertThrows(BizException.class, () -> review.rejectPeer(999L, LI)).getCode());
        assertEquals(1600, assertThrows(BizException.class, () -> review.cancel(999L, ZHANG)).getCode());
        BizException e = assertThrows(BizException.class, () -> review.approve(999L, "同意", ADMIN));
        assertEquals(1600, e.getCode());
        assertEquals("调班申请不存在", e.getMessage());
        assertEquals(1600, assertThrows(BizException.class, () -> review.reject(999L, null, ADMIN)).getCode());

        verify(repo, never()).save(any());
        verifyNoInteractions(schedule);
    }
}
