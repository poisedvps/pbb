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
import com.hospital.pbb.user.PasswordUtil;
import com.hospital.pbb.user.Role;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
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

    private final StaffRepository staffRepo;
    private final AppUserRepository userRepo;
    private final PasswordEncoder encoder;
    private final OpLogService opLog;
    private final Clock clock;

    public StaffService(StaffRepository staffRepo, AppUserRepository userRepo, PasswordEncoder encoder,
                        OpLogService opLog, Clock clock) {
        this.staffRepo = staffRepo;
        this.userRepo = userRepo;
        this.encoder = encoder;
        this.opLog = opLog;
        this.clock = clock;
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

    /** 新增人员 + 创建登录账号，返回随机生成的临时密码（仅此一次可见） */
    @Transactional
    public CreateStaffResult create(CreateStaffRequest req) {
        Role role = req.role();
        if (role != Role.ADMIN && role != Role.MEMBER) {
            throw new BizException(1202, "人员角色只能是科长或成员");
        }
        String empNo = req.empNo();
        // 工号即账号用户名，app_user.username 上有唯一约束，先查一次给出可读的错误
        if (staffRepo.existsByEmpNo(empNo) || userRepo.existsByUsername(empNo)) {
            throw new BizException(1201, "工号已存在");
        }

        OffsetDateTime now = OffsetDateTime.now(clock);
        Staff staff = new Staff();
        staff.setEmpNo(empNo);
        staff.setName(req.name());
        staff.setPosition(blankToNull(req.position()));
        staff.setPhone(blankToNull(req.phone()));
        staff.setSchedulable(req.schedulable());
        staff.setSortOrder(staffRepo.maxSortOrder() + 1);
        staff.setActive(true);
        staff.setCreatedAt(now);
        staff.setUpdatedAt(now);
        staffRepo.save(staff);

        String tempPassword = PasswordUtil.randomTempPassword();
        AppUser user = new AppUser();
        user.setUsername(empNo);
        user.setPasswordHash(encoder.encode(tempPassword));
        user.setDisplayName(req.name());
        user.setRole(role);
        user.setStaffId(staff.getId());
        user.setEnabled(true);
        user.setMustChangePassword(true);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        userRepo.save(user);

        opLog.record(OpAction.CREATE_STAFF, empNo, req.name());
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

    /** 前端清空输入框传的是空串，一律存 null，避免列表里出现空白字符串 */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
