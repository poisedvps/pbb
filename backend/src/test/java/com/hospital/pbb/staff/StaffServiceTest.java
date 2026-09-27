package com.hospital.pbb.staff;

import com.hospital.pbb.common.BizException;
import com.hospital.pbb.oplog.OpAction;
import com.hospital.pbb.oplog.OpLogService;
import com.hospital.pbb.staff.dto.CreateStaffRequest;
import com.hospital.pbb.staff.dto.CreateStaffResult;
import com.hospital.pbb.staff.dto.StaffVO;
import com.hospital.pbb.staff.dto.UpdateStaffRequest;
import com.hospital.pbb.user.AppUser;
import com.hospital.pbb.user.AppUserRepository;
import com.hospital.pbb.user.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Mockito + 真实 BCrypt：临时密码必须能matches，不接受假编码器。 */
class StaffServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-01T01:00:00Z"), ZONE);
    private static final OffsetDateTime NOW = OffsetDateTime.now(CLOCK);

    private StaffRepository staffRepo;
    private AppUserRepository userRepo;
    private PasswordEncoder encoder;
    private OpLogService opLog;
    private StaffService service;

    @BeforeEach
    void setUp() {
        staffRepo = mock(StaffRepository.class);
        userRepo = mock(AppUserRepository.class);
        encoder = new BCryptPasswordEncoder();
        opLog = mock(OpLogService.class);

        // 模拟数据库的自增主键，方便断言账号上的 staffId
        when(staffRepo.saveAndFlush(any(Staff.class))).thenAnswer(inv -> {
            Staff s = inv.getArgument(0);
            if (s.getId() == null) {
                s.setId(100L);
            }
            return s;
        });
        when(userRepo.saveAndFlush(any(AppUser.class))).thenAnswer(inv -> inv.getArgument(0));
        when(staffRepo.maxSortOrder()).thenReturn(0);

        service = new StaffService(staffRepo, userRepo, encoder, opLog, CLOCK);
    }

    private static CreateStaffRequest request(String empNo, String name, String position, String phone,
                                              boolean schedulable, Role role) {
        return new CreateStaffRequest(empNo, name, position, phone, schedulable, role);
    }

    private static UpdateStaffRequest update(String name, String position, String phone,
                                             boolean schedulable, boolean active) {
        return new UpdateStaffRequest(name, position, phone, schedulable, active);
    }

    private int bizCode(Runnable call) {
        BizException e = assertThrows(BizException.class, call::run);
        return e.getCode();
    }

    private static Staff staff(long id, String empNo, String name, int sortOrder, boolean active) {
        Staff s = new Staff();
        s.setId(id);
        s.setEmpNo(empNo);
        s.setName(name);
        s.setSortOrder(sortOrder);
        s.setActive(active);
        return s;
    }

    @Test
    void createStaffAlsoCreatesAccountWithTempPassword() {
        when(staffRepo.maxSortOrder()).thenReturn(5);

        CreateStaffResult result = service.create(request("IT001", "张三", "工程师", "13800000000", true, Role.MEMBER));

        ArgumentCaptor<Staff> savedStaff = ArgumentCaptor.forClass(Staff.class);
        verify(staffRepo).saveAndFlush(savedStaff.capture());
        Staff staff = savedStaff.getValue();
        assertEquals("IT001", staff.getEmpNo());
        assertEquals("张三", staff.getName());
        assertEquals(6, staff.getSortOrder());
        assertTrue(staff.isActive());
        assertTrue(staff.isSchedulable());
        assertEquals(NOW, staff.getCreatedAt());
        assertEquals(NOW, staff.getUpdatedAt());

        ArgumentCaptor<AppUser> savedUser = ArgumentCaptor.forClass(AppUser.class);
        verify(userRepo).saveAndFlush(savedUser.capture());
        AppUser user = savedUser.getValue();
        assertEquals("IT001", user.getUsername());
        assertEquals("张三", user.getDisplayName());
        assertEquals(Role.MEMBER, user.getRole());
        assertEquals(staff.getId(), user.getStaffId());
        assertTrue(user.isEnabled());
        assertTrue(user.isMustChangePassword());
        assertTrue(encoder.matches(result.tempPassword(), user.getPasswordHash()));
        assertFalse(user.getPasswordHash().contains(result.tempPassword()));
        assertTrue(user.getPasswordHash().startsWith("$2"), "密码必须存 BCrypt 哈希");

        assertEquals("IT001", result.username());
        assertEquals(6, result.staff().sortOrder());
        assertEquals(Role.MEMBER, result.staff().role());
        assertNotNull(result.tempPassword());

        // 留痕：target=工号，detail 只有姓名
        verify(opLog).record(OpAction.CREATE_STAFF, "IT001", "张三");
    }

    @Test
    void createWithExistingEmpNoReturns1201() {
        when(staffRepo.existsByEmpNo("IT001")).thenReturn(true);

        assertEquals(1201, bizCode(() -> service.create(
                request("IT001", "张三", null, null, true, Role.MEMBER))));

        verify(staffRepo, never()).saveAndFlush(any(Staff.class));
        verify(userRepo, never()).saveAndFlush(any(AppUser.class));
    }

    /** 工号没占用但用户名被账号占了（例如 admin），同样是 1201 */
    @Test
    void createWithExistingUsernameReturns1201() {
        when(userRepo.existsByUsername("admin")).thenReturn(true);

        assertEquals(1201, bizCode(() -> service.create(
                request("admin", "张三", null, null, true, Role.ADMIN))));

        verify(staffRepo, never()).saveAndFlush(any(Staff.class));
    }

    @Test
    void createWithScreenRoleReturns1202() {
        assertEquals(1202, bizCode(() -> service.create(
                request("IT002", "李四", null, null, true, Role.SCREEN))));

        verify(staffRepo, never()).saveAndFlush(any(Staff.class));
        verify(userRepo, never()).saveAndFlush(any(AppUser.class));
    }

    /**
     * 并发回归：两个管理员同时提交同一工号，exists 预检查都返回 false，冲突到 insert 才暴露。
     * 此时 emp_no 上的唯一索引（SQLState 23505）必须转成 1201，不能漏到全局处理器变成 500。
     */
    @Test
    void createRacesOnEmpNoUniqueIndexReturns1201() {
        when(staffRepo.saveAndFlush(any(Staff.class)))
                .thenThrow(duplicate("staff_emp_no_key", "23505"));

        assertEquals(1201, bizCode(() -> service.create(
                request("IT001", "张三", null, null, true, Role.MEMBER))));

        // 人员没插进去，账号也不能建，留痕同样不能记
        verify(userRepo, never()).saveAndFlush(any(AppUser.class));
        verify(opLog, never()).record(anyString(), anyString(), any());
    }

    /** 并发回归：撞上的是 app_user.username 唯一索引（工号新、用户名已被占），同样要回 1201 */
    @Test
    void createRacesOnUsernameUniqueIndexReturns1201() {
        when(userRepo.saveAndFlush(any(AppUser.class)))
                .thenThrow(duplicate("app_user_username_key", "23505"));

        assertEquals(1201, bizCode(() -> service.create(
                request("IT001", "张三", null, null, true, Role.MEMBER))));

        verify(opLog, never()).record(anyString(), anyString(), any());
    }

    /** 不是唯一键的完整性故障（23502 非空）不能冒充重复工号，要原样抛出让事务回滚、接口报 500 */
    @Test
    void createRethrowsOtherIntegrityFailure() {
        when(userRepo.saveAndFlush(any(AppUser.class)))
                .thenThrow(new DataIntegrityViolationException(
                        "null value in column \"display_name\" violates not-null constraint",
                        new SQLException("null value in column \"display_name\" violates not-null constraint", "23502")));

        assertThrows(DataIntegrityViolationException.class,
                () -> service.create(request("IT001", "张三", null, null, true, Role.MEMBER)));

        verify(opLog, never()).record(anyString(), anyString(), any());
    }

    /** 造一个与 PostgreSQL 唯一键冲突同形的异常链（Spring 转译后的 DuplicateKeyException 包 SQLException 23505） */
    private static DataIntegrityViolationException duplicate(String constraint, String sqlState) {
        SQLException sqlException = new SQLException(
                "duplicate key value violates unique constraint \"" + constraint + "\"", sqlState);
        return new DuplicateKeyException(
                "could not execute statement [" + constraint + "]", sqlException);
    }

    @Test
    void createWithBlankPositionAndPhoneStoresNull() {
        service.create(request("IT003", "王五", "", "", false, Role.MEMBER));

        ArgumentCaptor<Staff> saved = ArgumentCaptor.forClass(Staff.class);
        verify(staffRepo).saveAndFlush(saved.capture());
        assertNull(saved.getValue().getPosition());
        assertNull(saved.getValue().getPhone());
        assertFalse(saved.getValue().isSchedulable());
        assertEquals(1, saved.getValue().getSortOrder());
    }

    @Test
    void listDefaultSkipsInactiveAndResolvesRole() {
        Staff a = staff(1L, "IT001", "张三", 1, true);
        when(staffRepo.findByActiveTrueOrderBySortOrderAscIdAsc()).thenReturn(List.of(a));
        AppUser user = new AppUser();
        user.setRole(Role.ADMIN);
        when(userRepo.findByStaffId(1L)).thenReturn(Optional.of(user));
        when(userRepo.findByStaffId(2L)).thenReturn(Optional.empty());

        List<StaffVO> list = service.list(false);

        assertEquals(1, list.size());
        assertEquals(Role.ADMIN, list.get(0).role());
        verify(staffRepo, never()).findAllByOrderBySortOrderAscIdAsc();

        Staff b = staff(2L, "IT002", "李四", 2, false);
        when(staffRepo.findAllByOrderBySortOrderAscIdAsc()).thenReturn(List.of(a, b));
        List<StaffVO> all = service.list(true);

        assertEquals(2, all.size());
        assertNull(all.get(1).role());
    }

    @Test
    void updateDisabledStaffDisablesAccountAndSyncsName() {
        Staff s = staff(1L, "IT001", "张三", 3, true);
        when(staffRepo.findById(1L)).thenReturn(Optional.of(s));
        AppUser user = new AppUser();
        user.setId(9L);
        user.setUsername("IT001");
        user.setDisplayName("张三");
        user.setRole(Role.MEMBER);
        user.setStaffId(1L);
        when(userRepo.findByStaffId(1L)).thenReturn(Optional.of(user));

        StaffVO vo = service.update(1L, update("张三丰", "科长", "", true, false));

        assertFalse(s.isActive());
        assertFalse(user.isEnabled());
        assertEquals("张三丰", user.getDisplayName());
        assertEquals("张三丰", vo.name());
        assertNull(vo.phone());
        assertEquals(NOW, s.getUpdatedAt());
        assertEquals(NOW, user.getUpdatedAt());
        assertEquals(Role.MEMBER, vo.role());
        // detail 不写电话
        verify(opLog).record(OpAction.UPDATE_STAFF, "IT001", "张三丰");
    }

    /** 人员没有账号时也要能改，role 返回 null */
    @Test
    void updateStaffWithoutAccountReturnsNullRole() {
        Staff s = staff(1L, "IT001", "张三", 1, true);
        when(staffRepo.findById(1L)).thenReturn(Optional.of(s));
        when(userRepo.findByStaffId(1L)).thenReturn(Optional.empty());

        StaffVO vo = service.update(1L, update("张三丰", "工程师", "13800000000", true, true));

        assertNull(vo.role());
        verify(userRepo, never()).save(any(AppUser.class));
    }

    @Test
    void updateUnknownStaffReturns1200() {
        when(staffRepo.findById(99L)).thenReturn(Optional.empty());

        assertEquals(1200, bizCode(() -> service.update(99L, update("张三", null, null, true, true))));

        verify(staffRepo, never()).save(any(Staff.class));
    }

    @Test
    void saveOrderRewritesSortOrderInGivenOrder() {
        Staff s1 = staff(1L, "IT001", "张三", 1, true);
        Staff s2 = staff(2L, "IT002", "李四", 2, true);
        Staff s3 = staff(3L, "IT003", "王五", 3, true);
        when(staffRepo.findById(1L)).thenReturn(Optional.of(s1));
        when(staffRepo.findById(2L)).thenReturn(Optional.of(s2));
        when(staffRepo.findById(3L)).thenReturn(Optional.of(s3));

        service.saveOrder(List.of(3L, 1L, 2L));

        assertEquals(1, s3.getSortOrder());
        assertEquals(2, s1.getSortOrder());
        assertEquals(3, s2.getSortOrder());
        verify(opLog).record(eq(OpAction.SORT_STAFF), eq("3,1,2"), isNull());
    }

    @Test
    void saveOrderWithUnknownIdReturns1200() {
        when(staffRepo.findById(1L)).thenReturn(Optional.of(staff(1L, "IT001", "张三", 1, true)));
        when(staffRepo.findById(9L)).thenReturn(Optional.empty());

        assertEquals(1200, bizCode(() -> service.saveOrder(List.of(1L, 9L))));
    }
}
