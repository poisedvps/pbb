package com.hospital.pbb.staff;

import com.hospital.pbb.common.BizException;
import com.hospital.pbb.oplog.OpAction;
import com.hospital.pbb.oplog.OpLogService;
import com.hospital.pbb.schedule.ScheduleService;
import com.hospital.pbb.staff.dto.CreateStaffRequest;
import com.hospital.pbb.staff.dto.CreateStaffResult;
import com.hospital.pbb.staff.dto.StaffImportParseResult;
import com.hospital.pbb.staff.dto.StaffImportResult;
import com.hospital.pbb.staff.dto.StaffImportRow;
import com.hospital.pbb.staff.dto.StaffVO;
import com.hospital.pbb.staff.dto.UpdateStaffRequest;
import com.hospital.pbb.swap.SwapService;
import com.hospital.pbb.user.AppUser;
import com.hospital.pbb.user.AppUserRepository;
import com.hospital.pbb.user.PasswordUtil;
import com.hospital.pbb.user.Role;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 人员管理（设计 §5）。
 *
 * <p>人员与账号是一对一：新增人员时同步创建登录账号（username = 工号，初始密码随机、首次登录强制修改），
 * 停用人员时同步停用账号。</p>
 *
 * <p><b>操作日志里禁止出现密码、临时密码、手机号</b>，只写工号和姓名。</p>
 */
@Service
public class StaffService {

    /** PostgreSQL 的唯一键冲突 SQLState（unique_violation），驱动抛的 PSQLException 属于 SQLException */
    private static final String UNIQUE_VIOLATION_SQL_STATE = "23505";

    /** 1210 的错误文案最多列几条，剩下的只报总数（一次可能报几十条，全拼上去没人看得完） */
    private static final int MAX_IMPORT_ERRORS = 20;

    /** 【导入结果】xlsx 的「结果」列取值 */
    private static final String IMPORT_CREATED = "新增";
    private static final String IMPORT_UPDATED = "更新";

    private final StaffRepository staffRepo;
    private final AppUserRepository userRepo;
    private final PasswordEncoder encoder;
    private final OpLogService opLog;
    private final Clock clock;
    private final ScheduleService scheduleService;
    private final SwapService swapService;

    public StaffService(StaffRepository staffRepo, AppUserRepository userRepo, PasswordEncoder encoder,
                        OpLogService opLog, Clock clock, ScheduleService scheduleService, SwapService swapService) {
        this.staffRepo = staffRepo;
        this.userRepo = userRepo;
        this.encoder = encoder;
        this.opLog = opLog;
        this.clock = clock;
        this.scheduleService = scheduleService;
        this.swapService = swapService;
    }

    /** 人员列表按排序号返回；includeInactive=true 时连停用人员一起返回 */
    public List<StaffVO> list(boolean includeInactive) {
        List<Staff> staffList = includeInactive
                ? staffRepo.findAllByOrderBySortOrderAscIdAsc()
                : staffRepo.findByActiveTrueOrderBySortOrderAscIdAsc();
        List<StaffVO> result = new ArrayList<>(staffList.size());
        for (Staff staff : staffList) {
            result.add(toVO(staff, roleOf(staff.getId())));
        }
        return result;
    }

    /**
     * 人员名单导出（设计 §9.1 第 3 条）：在职人员按排序号，每行 6 列，顺序同 {@link StaffImportParser#HEADERS}。
     *
     * <p>导出文件同时就是批量导入的模板（M5-12），所以空值统一写空串而不是 {@code null}，
     * 「是 / 否」「科长 / 成员」也按导入那边能原样读回的文本来写。</p>
     *
     * <p>留痕的 detail 只有人数，手机号一个都不写。</p>
     */
    public List<List<Object>> exportRows() {
        List<Staff> staffList = staffRepo.findByActiveTrueOrderBySortOrderAscIdAsc();
        List<List<Object>> rows = new ArrayList<>(staffList.size());
        for (Staff staff : staffList) {
            rows.add(List.of(
                    staff.getEmpNo(),
                    staff.getName(),
                    nullToEmpty(staff.getPosition()),
                    nullToEmpty(staff.getPhone()),
                    staff.isSchedulable() ? "是" : "否",
                    roleToText(roleOf(staff.getId()))));
        }
        opLog.record(OpAction.EXPORT_STAFF, "人员名单", rows.size() + "人");
        return rows;
    }

    /** 新增人员 + 创建登录账号，返回随机生成的临时密码（仅此一次可见） */
    @Transactional
    public CreateStaffResult create(CreateStaffRequest req) {
        Role role = req.role();
        if (role != Role.ADMIN && role != Role.MEMBER) {
            throw new BizException(1202, "人员角色只能是科长或成员");
        }
        String empNo = req.empNo();
        // 工号即账号用户名，app_user.username 上有唯一约束，先查一次给出可读的错误。
        // 预检查只能走快路径，不保证并发正确：两个管理员同时提交同一工号会双双通过，
        // 真正的兜底是 emp_no / username 上的唯一索引，见 createOne 里的 try。
        if (staffRepo.existsByEmpNo(empNo) || userRepo.existsByUsername(empNo)) {
            throw new BizException(1201, "工号已存在");
        }
        return createOne(empNo, req.name(), req.position(), req.phone(), req.schedulable(), role);
    }

    /**
     * 建人员 + 建登录账号 + 记「新增人员」留痕，不做任何前置校验。
     *
     * <p>单个新增（{@link #create}）与批量导入（{@link #importStaff}）共用这一段，
     * 两条路径建出来的账号必须一模一样：初始密码随机、首次登录强制改密。</p>
     */
    private CreateStaffResult createOne(String empNo, String name, String position, String phone,
                                        boolean schedulable, Role role) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        Staff staff = new Staff();
        staff.setEmpNo(empNo);
        staff.setName(name);
        staff.setPosition(blankToNull(position));
        staff.setPhone(blankToNull(phone));
        staff.setSchedulable(schedulable);
        staff.setSortOrder(staffRepo.maxSortOrder() + 1);
        staff.setActive(true);
        staff.setCreatedAt(now);
        staff.setUpdatedAt(now);

        String tempPassword = PasswordUtil.randomTempPassword();
        AppUser user = new AppUser();
        user.setUsername(empNo);
        user.setPasswordHash(encoder.encode(tempPassword));
        user.setDisplayName(name);
        user.setRole(role);
        user.setEnabled(true);
        user.setMustChangePassword(true);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);

        try {
            // 必须 saveAndFlush 而不是 save：只靠 save 时唯一键冲突可能要到事务提交才冒出来，
            // 那时代码已离开本方法，捕获不到，只能给前端回 500。
            staffRepo.saveAndFlush(staff);
            user.setStaffId(staff.getId());
            userRepo.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            if (!isDuplicateKey(e)) {
                // 不是唯一键冲突（例如非空、外键），原样抛出，不能冒充成 1201 骗过调用方
                throw e;
            }
            // 冲突后当前事务已被标成 rollback-only，这里抛 RuntimeException 让它回滚：
            // 半途插入的人员不会留下，前端拿到 1201 后重查列表就能看到那条工号已被占用
            throw new BizException(1201, "工号已存在");
        }

        opLog.record(OpAction.CREATE_STAFF, empNo, name);
        return new CreateStaffResult(toVO(staff, role), empNo, tempPassword);
    }

    /** 修改人员，姓名和启停状态同步到对应账号 */
    @Transactional
    public StaffVO update(Long id, UpdateStaffRequest req) {
        Staff staff = load(id);
        OffsetDateTime now = OffsetDateTime.now(clock);
        staff.setName(req.name());
        staff.setPosition(blankToNull(req.position()));
        staff.setPhone(blankToNull(req.phone()));
        staff.setSchedulable(req.schedulable());
        staff.setActive(req.active());
        staff.setUpdatedAt(now);
        staffRepo.save(staff);

        Role role = null;
        AppUser user = userRepo.findByStaffId(id).orElse(null);
        if (user != null) {
            user.setDisplayName(staff.getName());
            user.setEnabled(staff.isActive());
            user.setUpdatedAt(now);
            userRepo.save(user);
            role = user.getRole();
        }
        // detail 只写姓名，手机号不得进操作日志
        opLog.record(OpAction.UPDATE_STAFF, staff.getEmpNo(), staff.getName());
        return toVO(staff, role);
    }

    /**
     * 批量导入人员（设计 §9.1 第 4 条），一个事务。
     *
     * <p>三段顺序不能换：先用 {@link StaffImportParser} 收集文件里不查库就能看出的错，
     * 一条都没有才逐行查库补上「工号被别的账号占了」，还有错就整批不写、直接抛 1210。
     * 半批人员先进了库比全部失败难收场。</p>
     *
     * <p>已在册的人只改姓名 / 岗位 / 联系电话 / 参与排班，<b>启停状态和角色一律不动</b>：
     * 一份旧文件就能把停用账号悄悄放回来、把成员悄悄提成科长。</p>
     *
     * <p>逐行照旧记「新增人员」/「修改人员」（在 {@link #createOne} 与 {@link #updateFromImport} 里），
     * 最后再记一条「导入人员」汇总。日志里只有工号、姓名和人数。</p>
     *
     * @param in 与【批量导出】同格式的 xlsx 输入流，调用方负责关闭
     * @return 每行一行结果，新增行带一次性初始密码
     * @throws BizException 1210：文件里有任何一行不合格，一行也不会写库
     */
    @Transactional
    public StaffImportResult importStaff(InputStream in) {
        StaffImportParseResult parsed = StaffImportParser.parse(in);
        List<String> errors = new ArrayList<>(parsed.errors());
        List<StaffImportRow> rows = parsed.rows();

        // 解析全通过才查库；顺手把在册人员存下来，写库阶段不再查第二遍（文件内工号不重复，解析阶段已保证）
        Map<String, Staff> existingByEmpNo = new HashMap<>();
        if (errors.isEmpty()) {
            for (StaffImportRow row : rows) {
                Staff existing = staffRepo.findByEmpNo(row.empNo()).orElse(null);
                if (existing == null) {
                    // 人员表里没有、账号表里却有：这条工号是别人的登录名，新增必然撞 username 唯一键
                    if (userRepo.existsByUsername(row.empNo())) {
                        errors.add("第" + row.rowNo() + "行：工号 " + row.empNo() + " 已被其他账号占用");
                    }
                } else {
                    existingByEmpNo.put(row.empNo(), existing);
                }
            }
        }
        if (!errors.isEmpty()) {
            throw new BizException(1210, importFailureMessage(errors));
        }

        List<StaffImportResult.Line> lines = new ArrayList<>(rows.size());
        int created = 0;
        int updated = 0;
        for (StaffImportRow row : rows) {
            Staff existing = existingByEmpNo.get(row.empNo());
            if (existing == null) {
                // 「角色」列留空时，新人员默认成员（已在册的人根本不读这一列）
                Role role = row.role() == null ? Role.MEMBER : row.role();
                CreateStaffResult result = createOne(row.empNo(), row.name(), row.position(), row.phone(),
                        row.schedulable(), role);
                created++;
                lines.add(new StaffImportResult.Line(row.empNo(), row.name(), IMPORT_CREATED, result.tempPassword()));
            } else {
                updateFromImport(existing, row);
                updated++;
                lines.add(new StaffImportResult.Line(row.empNo(), row.name(), IMPORT_UPDATED, null));
            }
        }

        // detail 只有人数：手机号和初始密码一个都不能进操作日志
        opLog.record(OpAction.IMPORT_STAFF, "人员导入", "新增" + created + "人，更新" + updated + "人");
        return new StaffImportResult(created, updated, lines);
    }

    /** 拖拽排序：ids 的顺序就是新的展示顺序，排序号从 1 开始 */
    @Transactional
    public void saveOrder(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        for (int i = 0; i < ids.size(); i++) {
            Staff staff = load(ids.get(i));
            staff.setSortOrder(i + 1);
            staffRepo.save(staff);
        }
        opLog.record(OpAction.SORT_STAFF, ids.stream().map(String::valueOf).collect(Collectors.joining(",")), null);
    }

    /**
     * 删除人员及其全部相关数据（设计 §9.4）。科长不能删（1203）。
     *
     * <p>顺序不能随便排：先清理排班、调班，再清日志，最后才删账号与人员——人和账号还在的时候
     * 外键都指向得到，反过来删会先撞 {@code app_user.staff_id} / {@code schedule_entry.staff_id}。</p>
     *
     * <p>那条「删除人员」日志必须记在 {@link OpLogService#purgeStaff} 之后：purgeStaff 会按
     * {@code target = 工号} 删日志，先记就被自己删掉了。</p>
     *
     * <p>日志里的姓名只在同名唯一时才当过滤条件用（{@code byName}），否则 {@code 姓名 %} 会误删别人的排班日志。</p>
     */
    @Transactional
    public void delete(Long id) {
        Staff staff = load(id);
        AppUser user = userRepo.findByStaffId(id).orElse(null);
        Long userId = user == null ? null : user.getId();
        if (user != null && user.getRole() == Role.ADMIN) {
            throw new BizException(1203, "科长不能删除");
        }
        boolean byName = staffRepo.countByName(staff.getName()) == 1;

        scheduleService.purgeStaff(id, userId);
        swapService.purgeStaff(id, userId);
        opLog.purgeStaff(userId, staff.getEmpNo(), staff.getName(), byName);

        if (user != null) {
            // 先清账号再删人员：app_user.staff_id 是指向 staff 的外键
            userRepo.delete(user);
            userRepo.flush();
        }
        staffRepo.delete(staff);
        staffRepo.flush();

        // detail 传 null：姓名和手机号都不进操作日志
        opLog.record(OpAction.DELETE_STAFF, staff.getEmpNo(), null);
    }

    /**
     * 导入时更新已在册的人员：改的字段与 {@link #update} 完全一样，
     * 但不改 active、也不改账号角色（设计 §9.1 第 4 条：导入只改人员基本信息）。
     */
    private void updateFromImport(Staff staff, StaffImportRow row) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        staff.setName(row.name());
        staff.setPosition(blankToNull(row.position()));
        staff.setPhone(blankToNull(row.phone()));
        staff.setSchedulable(row.schedulable());
        staff.setUpdatedAt(now);
        staffRepo.save(staff);

        AppUser user = userRepo.findByStaffId(staff.getId()).orElse(null);
        if (user != null) {
            // 只同步姓名：enabled 与 role 保持原样，导入文件里的「角色」列对已在册的人不生效
            user.setDisplayName(staff.getName());
            user.setUpdatedAt(now);
            userRepo.save(user);
        }
        // detail 只写姓名，手机号不得进操作日志
        opLog.record(OpAction.UPDATE_STAFF, staff.getEmpNo(), staff.getName());
    }

    /** 1210 的文案：前 {@value #MAX_IMPORT_ERRORS} 条逐条列出，多出来的折成一句总数 */
    private static String importFailureMessage(List<String> errors) {
        int shown = Math.min(MAX_IMPORT_ERRORS, errors.size());
        StringBuilder message = new StringBuilder("导入失败，没有写入任何数据：")
                .append(String.join("；", errors.subList(0, shown)));
        if (shown < errors.size()) {
            message.append("；等共 ").append(errors.size()).append(" 处错误");
        }
        return message.toString();
    }

    /**
     * 异常链里有没有唯一键冲突。只看 SQLState 23505 和 Spring 的 {@link DuplicateKeyException}，
     * 不把 23502（非空）、23503（外键）这类完整性故障误判成重复工号。
     */
    private static boolean isDuplicateKey(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof DuplicateKeyException) {
                return true;
            }
            if (t instanceof SQLException sql && UNIQUE_VIOLATION_SQL_STATE.equals(sql.getSQLState())) {
                return true;
            }
            if (t == t.getCause()) {
                break;
            }
        }
        return false;
    }

    private Staff load(Long id) {
        return staffRepo.findById(id).orElseThrow(() -> new BizException(1200, "人员不存在"));
    }

    private Role roleOf(Long staffId) {
        return userRepo.findByStaffId(staffId).map(AppUser::getRole).orElse(null);
    }

    private static StaffVO toVO(Staff s, Role role) {
        return new StaffVO(s.getId(), s.getEmpNo(), s.getName(), s.getPosition(), s.getPhone(),
                s.isSchedulable(), s.getSortOrder(), s.isActive(), role);
    }

    /** 导出格子里写空串而不是 null：与导入模板一致，空列就是空字符串，导入时读回也是空串 */
    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /** 导入模板里「角色」列的写法：没有账号就是空串，导入时按「不填角色」处理 */
    private static String roleToText(Role role) {
        if (role == Role.ADMIN) {
            return "科长";
        }
        return role == Role.MEMBER ? "成员" : "";
    }

    /** 前端清空输入框传的是空串，一律存 null，避免列表里出现空白字符串 */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
