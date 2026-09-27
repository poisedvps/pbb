package com.hospital.pbb.user;

import com.hospital.pbb.common.BizException;
import com.hospital.pbb.oplog.OpAction;
import com.hospital.pbb.oplog.OpLogService;
import com.hospital.pbb.user.dto.CreateUserRequest;
import com.hospital.pbb.user.dto.TempPasswordVO;
import com.hospital.pbb.user.dto.UserVO;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.sql.SQLException;
import java.util.List;

/**
 * 账号管理（科长专用）：查看账号、新增大屏账号、重置密码、解锁、停用、启用。
 *
 * <p><b>临时密码只在返回值里出现一次，绝不写进操作日志或普通日志。</b></p>
 */
@Service
public class UserAdminService {

    /** V1__init_schema.sql 里 app_user.username 的 UNIQUE 约束名 */
    static final String USERNAME_UNIQUE_CONSTRAINT = "app_user_username_key";
    /** PostgreSQL 的 unique_violation */
    private static final String UNIQUE_VIOLATION_SQL_STATE = "23505";

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
        try {
            // 强制写入：预检查到提交之间存在竞态，两个请求可能同时通过 existsByUsername，
            // 必须由数据库唯一约束兜底，不能把约束冲突直接抛成 500。
            repo.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            if (!isUsernameConflict(e)) {
                throw e; // 其他完整性冲突原样抛出，事务照常回滚
            }
            throw new BizException(1201, "用户名已存在");
        }

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

    /**
     * 只认用户名唯一约束冲突：约束名命中，或 SQLState 为 23505 且异常链里出现 username。
     * 其余完整性冲突（非空、外键、别的表的唯一约束）一律返回 false 由调用方原样抛出。
     */
    private static boolean isUsernameConflict(DataIntegrityViolationException e) {
        boolean duplicateKey = false;
        for (Throwable t = e; t != null && t != t.getCause(); t = t.getCause()) {
            if (t instanceof SQLException sql && UNIQUE_VIOLATION_SQL_STATE.equals(sql.getSQLState())) {
                duplicateKey = true;
            }
            String msg = t.getMessage();
            if (msg != null && msg.contains(USERNAME_UNIQUE_CONSTRAINT)) {
                return true;
            }
        }
        if (!duplicateKey) {
            return false;
        }
        for (Throwable t = e; t != null && t != t.getCause(); t = t.getCause()) {
            String msg = t.getMessage();
            if (msg != null && msg.contains("username")) {
                return true;
            }
        }
        return false;
    }

    private static UserVO toVO(AppUser u, OffsetDateTime now) {
        boolean locked = u.getLockedUntil() != null && u.getLockedUntil().isAfter(now);
        return new UserVO(u.getId(), u.getUsername(), u.getDisplayName(), u.getRole(), u.getStaffId(),
                u.isEnabled(), locked, u.getLastLoginAt());
    }
}
