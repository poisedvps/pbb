package com.hospital.pbb.user;

import com.hospital.pbb.common.BizException;
import com.hospital.pbb.oplog.OpAction;
import com.hospital.pbb.oplog.OpLogService;
import com.hospital.pbb.user.dto.CreateUserRequest;
import com.hospital.pbb.user.dto.TempPasswordVO;
import com.hospital.pbb.user.dto.UserVO;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 账号管理（科长专用）：查看账号、新增大屏账号、重置密码、解锁、停用、启用。
 *
 * <p><b>临时密码只在返回值里出现一次，绝不写进操作日志或普通日志。</b></p>
 */
@Service
public class UserAdminService {

    private final AppUserRepository repo;
    private final PasswordEncoder encoder;
    private final OpLogService opLog;
    private final Clock clock;

    public UserAdminService(AppUserRepository repo, PasswordEncoder encoder, OpLogService opLog, Clock clock) {
        this.repo = repo;
        this.encoder = encoder;
        this.opLog = opLog;
        this.clock = clock;
    }

    public List<UserVO> list() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        return repo.findAllByOrderByIdAsc().stream().map(u -> toVO(u, now)).toList();
    }

    /** 只允许新增大屏账号；大屏要长期保持登录，所以不强制改密 */
    @Transactional
    public TempPasswordVO createScreenUser(CreateUserRequest req) {
        if (req.role() != Role.SCREEN) {
            throw new BizException(1102, "只能新增大屏账号");
        }
        if (repo.existsByUsername(req.username())) {
            throw new BizException(1201, "用户名已存在");
        }
        String tempPassword = PasswordUtil.randomTempPassword();
        OffsetDateTime now = OffsetDateTime.now(clock);

        AppUser user = new AppUser();
        user.setUsername(req.username());
        user.setDisplayName(req.displayName());
        user.setRole(Role.SCREEN);
        user.setPasswordHash(encoder.encode(tempPassword));
        user.setEnabled(true);
        user.setMustChangePassword(false);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        repo.save(user);

        opLog.record(OpAction.CREATE_USER, req.username(), "role=SCREEN");
        return new TempPasswordVO(tempPassword);
    }

    /** 重置为随机临时密码；大屏账号免改密，其余账号下次登录必须改密 */
    @Transactional
    public TempPasswordVO resetPassword(Long id) {
        AppUser user = loadUser(id);
        String tempPassword = PasswordUtil.randomTempPassword();

        user.setPasswordHash(encoder.encode(tempPassword));
        user.setMustChangePassword(user.getRole() != Role.SCREEN);
        user.setFailedAttempts(0);
        user.setLockedUntil(null);
        user.setUpdatedAt(OffsetDateTime.now(clock));
        repo.save(user);

        opLog.record(OpAction.RESET_PASSWORD, user.getUsername(), null);
        return new TempPasswordVO(tempPassword);
    }

    @Transactional
    public void unlock(Long id) {
        AppUser user = loadUser(id);
        user.setFailedAttempts(0);
        user.setLockedUntil(null);
        user.setUpdatedAt(OffsetDateTime.now(clock));
        repo.save(user);
        opLog.record(OpAction.UNLOCK_USER, user.getUsername(), null);
    }

    @Transactional
    public void disable(Long id, Long currentUserId) {
        if (id.equals(currentUserId)) {
            throw new BizException(1101, "不能停用自己");
        }
        AppUser user = loadUser(id);
        user.setEnabled(false);
        user.setUpdatedAt(OffsetDateTime.now(clock));
        repo.save(user);
        opLog.record(OpAction.DISABLE_USER, user.getUsername(), null);
    }

    @Transactional
    public void enable(Long id) {
        AppUser user = loadUser(id);
        user.setEnabled(true);
        user.setUpdatedAt(OffsetDateTime.now(clock));
        repo.save(user);
        opLog.record(OpAction.ENABLE_USER, user.getUsername(), null);
    }

    private AppUser loadUser(Long id) {
        return repo.findById(id).orElseThrow(() -> new BizException(1100, "账号不存在"));
    }

    private static UserVO toVO(AppUser u, OffsetDateTime now) {
        boolean locked = u.getLockedUntil() != null && u.getLockedUntil().isAfter(now);
        return new UserVO(u.getId(), u.getUsername(), u.getDisplayName(), u.getRole(), u.getStaffId(),
                u.isEnabled(), locked, u.getLastLoginAt());
    }
}
