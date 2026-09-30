package com.hospital.pbb.staff;

import com.hospital.pbb.common.BizException;
import com.hospital.pbb.oplog.OpAction;
import com.hospital.pbb.oplog.OpLogService;
import com.hospital.pbb.schedule.ScheduleService;
import com.hospital.pbb.staff.dto.CreateStaffRequest;
import com.hospital.pbb.staff.dto.CreateStaffResult;
import com.hospital.pbb.staff.dto.StaffImportResult;
import com.hospital.pbb.staff.dto.StaffVO;
import com.hospital.pbb.staff.dto.UpdateStaffRequest;
import com.hospital.pbb.swap.SwapService;
import com.hospital.pbb.user.AppUser;
import com.hospital.pbb.user.AppUserRepository;
import com.hospital.pbb.user.Role;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
    private ScheduleService scheduleService;
    private SwapService swapService;
    private StaffService service;

    @BeforeEach
    void setUp() {
        staffRepo = mock(StaffRepository.class);
        userRepo = mock(AppUserRepository.class);
        encoder = new BCryptPasswordEncoder();
        opLog = mock(OpLogService.class);
        scheduleService = mock(ScheduleService.class);
        swapService = mock(SwapService.class);

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

        service = new StaffService(staffRepo, userRepo, encoder, opLog, CLOCK, scheduleService, swapService);
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

    /** 导出：每行 6 列、顺序同 StaffImportParser.HEADERS，岗位和电话为 null 时写空串，角色写中文 */
    @Test
    void exportRowsWritesSixColumnsInHeaderOrder() {
        Staff a = staff(1L, "A01", "张三", 1, true);
        a.setPosition("医生");
        a.setPhone("13800000000");
        a.setSchedulable(true);
        Staff b = staff(2L, "A02", "李四", 2, true);
        b.setSchedulable(false);
        when(staffRepo.findByActiveTrueOrderBySortOrderAscIdAsc()).thenReturn(List.of(a, b));
        when(userRepo.findByStaffId(1L)).thenReturn(Optional.of(account(11L, 1L, Role.ADMIN)));
        when(userRepo.findByStaffId(2L)).thenReturn(Optional.of(account(12L, 2L, Role.MEMBER)));

        List<List<Object>> rows = service.exportRows();

        assertEquals(2, rows.size());
        assertEquals(List.of("A01", "张三", "医生", "13800000000", "是", "科长"), rows.get(0));
        assertEquals(List.of("A02", "李四", "", "", "否", "成员"), rows.get(1));
        // detail 只有人数，手机号不得进操作日志
        verify(opLog).record(OpAction.EXPORT_STAFF, "人员名单", "2人");
    }

    /** 没有人可导也要留痕，detail 写 0人 */
    @Test
    void exportRowsWithNoActiveStaffReturnsEmptyListAndLogsZero() {
        when(staffRepo.findByActiveTrueOrderBySortOrderAscIdAsc()).thenReturn(List.of());

        List<List<Object>> rows = service.exportRows();

        assertTrue(rows.isEmpty());
        verify(opLog).record(OpAction.EXPORT_STAFF, "人员名单", "0人");
    }

    /** 没有账号的人员：角色列给空串，导入时按「不填角色」处理 */
    @Test
    void exportRowsWithoutAccountWritesEmptyRole() {
        Staff a = staff(1L, "A01", "张三", 1, true);
        a.setSchedulable(true);
        when(staffRepo.findByActiveTrueOrderBySortOrderAscIdAsc()).thenReturn(List.of(a));
        when(userRepo.findByStaffId(1L)).thenReturn(Optional.empty());

        List<List<Object>> rows = service.exportRows();

        assertEquals(List.of("A01", "张三", "", "", "是", ""), rows.get(0));
        verify(opLog).record(OpAction.EXPORT_STAFF, "人员名单", "1人");
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

    private static AppUser account(long id, Long staffId, Role role) {
        AppUser user = new AppUser();
        user.setId(id);
        user.setStaffId(staffId);
        user.setUsername("A05");
        user.setRole(role);
        return user;
    }

    /** 成员：排班→调班→日志→账号→人员，最后才是「删除人员」留痕（设计 §9.4） */
    @Test
    void deletePurgesInOrderAndLogsLast() {
        Staff s = staff(5L, "A05", "王五", 5, true);
        AppUser user = account(9L, 5L, Role.MEMBER);
        when(staffRepo.findById(5L)).thenReturn(Optional.of(s));
        when(userRepo.findByStaffId(5L)).thenReturn(Optional.of(user));
        when(staffRepo.countByName("王五")).thenReturn(1L);

        service.delete(5L);

        InOrder order = inOrder(scheduleService, swapService, opLog, userRepo, staffRepo);
        order.verify(scheduleService).purgeStaff(5L, 9L);
        order.verify(swapService).purgeStaff(5L, 9L);
        order.verify(opLog).purgeStaff(9L, "A05", "王五", true);
        order.verify(userRepo).delete(user);
        order.verify(staffRepo).delete(s);
        order.verify(opLog).record(OpAction.DELETE_STAFF, "A05", null);
        order.verifyNoMoreInteractions();
    }

    /** 重名：按姓名的两条日志规则要跳过，否则误删别人的排班日志 */
    @Test
    void deleteWithDuplicateNameSkipsByNameLogPurge() {
        Staff s = staff(5L, "A05", "王五", 5, true);
        AppUser user = account(9L, 5L, Role.MEMBER);
        when(staffRepo.findById(5L)).thenReturn(Optional.of(s));
        when(userRepo.findByStaffId(5L)).thenReturn(Optional.of(user));
        when(staffRepo.countByName("王五")).thenReturn(2L);

        service.delete(5L);

        verify(opLog).purgeStaff(9L, "A05", "王五", false);
        verify(staffRepo).delete(s);
    }

    /** 没有账号的人员：userId 一路传 null，账号相关的删除一次都不能发生 */
    @Test
    void deleteStaffWithoutAccountSkipsUserDelete() {
        Staff s = staff(5L, "A05", "王五", 5, true);
        when(staffRepo.findById(5L)).thenReturn(Optional.of(s));
        when(userRepo.findByStaffId(5L)).thenReturn(Optional.empty());
        when(staffRepo.countByName("王五")).thenReturn(1L);

        service.delete(5L);

        InOrder order = inOrder(scheduleService, swapService, opLog, staffRepo);
        order.verify(scheduleService).purgeStaff(5L, null);
        order.verify(swapService).purgeStaff(5L, null);
        order.verify(opLog).purgeStaff(null, "A05", "王五", true);
        order.verify(staffRepo).delete(s);
        order.verify(opLog).record(OpAction.DELETE_STAFF, "A05", null);
        verify(userRepo, never()).delete(any(AppUser.class));
        verify(userRepo, never()).flush();
    }

    /** 科长（ADMIN 账号）不能删，而且必须是任何清理发生之前的前置校验 */
    @Test
    void deleteAdminReturns1203AndPurgesNothing() {
        Staff s = staff(5L, "A05", "王五", 5, true);
        when(staffRepo.findById(5L)).thenReturn(Optional.of(s));
        when(userRepo.findByStaffId(5L)).thenReturn(Optional.of(account(9L, 5L, Role.ADMIN)));

        assertEquals(1203, bizCode(() -> service.delete(5L)));

        verifyNoInteractions(scheduleService, swapService);
        verify(userRepo, never()).delete(any(AppUser.class));
        verify(staffRepo, never()).delete(any(Staff.class));
        verify(opLog, never()).purgeStaff(any(), anyString(), anyString(), anyBoolean());
        verify(opLog, never()).record(anyString(), anyString(), any());
    }

    /** 人员不存在：1200，不能退回到 id 去碰其他表 */
    @Test
    void deleteUnknownStaffReturns1200() {
        when(staffRepo.findById(99L)).thenReturn(Optional.empty());

        assertEquals(1200, bizCode(() -> service.delete(99L)));

        verifyNoInteractions(scheduleService, swapService);
        verify(userRepo, never()).delete(any(AppUser.class));
        verify(staffRepo, never()).delete(any(Staff.class));
        verify(opLog, never()).record(anyString(), anyString(), any());
    }

    /** 内存里造一份导入文件（写法同 StaffImportParserTest），表头永远齐 */
    private static InputStream importFile(String[]... dataRows) throws IOException {
        XSSFWorkbook book = new XSSFWorkbook();
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = book.createSheet("人员名单");
            Row header = sheet.createRow(0);
            for (int col = 0; col < StaffImportParser.HEADERS.size(); col++) {
                header.createCell(col).setCellValue(StaffImportParser.HEADERS.get(col));
            }
            int rowNo = 1;
            for (String[] cells : dataRows) {
                Row row = sheet.createRow(rowNo++);
                for (int col = 0; col < cells.length; col++) {
                    row.createCell(col).setCellValue(cells[col]);
                }
            }
            book.write(out);
            return new ByteArrayInputStream(out.toByteArray());
        } finally {
            book.close();
        }
    }

    /** 一行已在册（改姓名）+ 一行新人员（角色列空）：一人更新、一人新增带临时密码 */
    @Test
    void importUpdatesExistingRowAndCreatesMissingRow() throws IOException {
        Staff existing = staff(1L, "A01", "张三", 1, true);
        AppUser existingAccount = account(11L, 1L, Role.MEMBER);
        when(staffRepo.findByEmpNo("A01")).thenReturn(Optional.of(existing));
        when(staffRepo.findByEmpNo("A09")).thenReturn(Optional.empty());
        when(userRepo.existsByUsername("A09")).thenReturn(false);
        when(userRepo.findByStaffId(1L)).thenReturn(Optional.of(existingAccount));

        StaffImportResult result = service.importStaff(importFile(
                new String[] {"A01", "张三丰", "工程师", "13800000000", "是", "成员"},
                new String[] {"A09", "孙七", "", "", "", ""}));

        assertEquals(1, result.created());
        assertEquals(1, result.updated());
        assertEquals(2, result.lines().size());

        // 更新行走 save，不动启停状态，姓名同步到账号
        assertEquals("张三丰", existing.getName());
        assertTrue(existing.isActive());
        assertEquals(NOW, existing.getUpdatedAt());
        assertEquals("张三丰", existingAccount.getDisplayName());
        verify(staffRepo).save(existing);
        verify(userRepo).save(existingAccount);

        // 新增行：角色列为空→成员，首次登录强制改密
        ArgumentCaptor<AppUser> createdUser = ArgumentCaptor.forClass(AppUser.class);
        verify(userRepo).saveAndFlush(createdUser.capture());
        AppUser created = createdUser.getValue();
        assertEquals("A09", created.getUsername());
        assertEquals(Role.MEMBER, created.getRole());
        assertTrue(created.isMustChangePassword());
        assertTrue(created.isEnabled());

        StaffImportResult.Line updatedLine = result.lines().get(0);
        assertEquals("A01", updatedLine.empNo());
        assertEquals("更新", updatedLine.result());
        assertNull(updatedLine.tempPassword());

        StaffImportResult.Line createdLine = result.lines().get(1);
        assertEquals("A09", createdLine.empNo());
        assertEquals("孙七", createdLine.name());
        assertEquals("新增", createdLine.result());
        assertNotNull(createdLine.tempPassword());
        assertTrue(encoder.matches(createdLine.tempPassword(), created.getPasswordHash()));

        // 逐行照旧留痕，最后才是「导入人员」汇总；detail 只有人数
        InOrder order = inOrder(opLog);
        order.verify(opLog).record(OpAction.UPDATE_STAFF, "A01", "张三丰");
        order.verify(opLog).record(OpAction.CREATE_STAFF, "A09", "孙七");
        order.verify(opLog).record(OpAction.IMPORT_STAFF, "人员导入", "新增1人，更新1人");
        order.verifyNoMoreInteractions();
    }

    /** 工号不在人员表里但已是某个账号的用户名：整批不写，1210 带行号 */
    @Test
    void importRejectsEmpNoTakenByAnotherAccount() throws IOException {
        when(staffRepo.findByEmpNo("A01")).thenReturn(Optional.of(staff(1L, "A01", "张三", 1, true)));
        when(staffRepo.findByEmpNo("A09")).thenReturn(Optional.empty());
        when(userRepo.existsByUsername("A09")).thenReturn(true);

        BizException e = assertThrows(BizException.class, () -> service.importStaff(importFile(
                new String[] {"A01", "张三丰", "", "", "是", "成员"},
                new String[] {"A09", "孙七", "", "", "是", ""})));

        assertEquals(1210, e.getCode());
        assertTrue(e.getMessage().contains("第3行：工号 A09 已被其他账号占用"), e.getMessage());
        // 一行都不能写：那行 A01 看着能更新，也不得提前落库
        verify(staffRepo, never()).saveAndFlush(any(Staff.class));
        verify(staffRepo, never()).save(any(Staff.class));
        verify(userRepo, never()).save(any(AppUser.class));
        verify(userRepo, never()).saveAndFlush(any(AppUser.class));
        verify(opLog, never()).record(anyString(), anyString(), any());
    }

    /** 25 行姓名全空：只列前 20 条，尾部报总数 */
    @Test
    void importTruncatesErrorListToTwentyAndTellsTotal() throws IOException {
        String[][] rows = new String[25][];
        for (int i = 0; i < rows.length; i++) {
            rows[i] = new String[] {String.format("E%02d", i + 1), "", "", "", "是", "成员"};
        }

        BizException e = assertThrows(BizException.class, () -> service.importStaff(importFile(rows)));

        assertEquals(1210, e.getCode());
        assertTrue(e.getMessage().startsWith("导入失败，没有写入任何数据："), e.getMessage());
        assertTrue(e.getMessage().endsWith("；等共 25 处错误"), e.getMessage());
        // 只展前 20 条：第 21 条（数据行第 21 行 = Excel 第 22 行）不得出现在文案里
        assertTrue(e.getMessage().contains("第2行：姓名不能为空"), e.getMessage());
        assertFalse(e.getMessage().contains("第22行"), e.getMessage());
        verify(staffRepo, never()).save(any(Staff.class));
        verify(staffRepo, never()).saveAndFlush(any(Staff.class));
    }

    /** 已在册的人文件里写了「科长」也不改角色：导入只负人员基本信息，不提权 */
    @Test
    void importKeepsRoleOfExistingStaff() throws IOException {
        Staff existing = staff(1L, "A01", "张三", 1, true);
        AppUser member = account(11L, 1L, Role.MEMBER);
        member.setEnabled(true);
        when(staffRepo.findByEmpNo("A01")).thenReturn(Optional.of(existing));
        when(userRepo.findByStaffId(1L)).thenReturn(Optional.of(member));

        StaffImportResult result = service.importStaff(importFile(
                new String[] {"A01", "张三丰", "医生", "13800000000", "否", "科长"}));

        assertEquals(0, result.created());
        assertEquals(1, result.updated());
        assertEquals(Role.MEMBER, member.getRole());
        assertTrue(member.isEnabled());
        assertFalse(existing.isSchedulable());
        assertEquals("张三丰", existing.getName());
        assertEquals("医生", existing.getPosition());
        assertEquals("13800000000", existing.getPhone());
        verify(opLog).record(OpAction.IMPORT_STAFF, "人员导入", "新增0人，更新1人");
        verify(opLog, never()).record(eq(OpAction.CREATE_STAFF), anyString(), any());
    }
}
