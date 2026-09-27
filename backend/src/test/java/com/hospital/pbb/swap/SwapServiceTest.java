package com.hospital.pbb.swap;

import com.hospital.pbb.oplog.OpLogService;
import com.hospital.pbb.schedule.ScheduleQueryService;
import com.hospital.pbb.schedule.ScheduleService;
import com.hospital.pbb.staff.Staff;
import com.hospital.pbb.staff.StaffRepository;
import com.hospital.pbb.swap.dto.SwapVO;
import com.hospital.pbb.user.AuthUser;
import com.hospital.pbb.user.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 调班列表（任务单 M3-03 验收标准）：Mockito 打桩仓库、人员表和
 * {@link ScheduleQueryService#publishedShift}，重点验三件事——
 * 三个 scope 各走哪条查询、没关联人员的账号查不到东西、按钮标志按当前登录人算。
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
}
