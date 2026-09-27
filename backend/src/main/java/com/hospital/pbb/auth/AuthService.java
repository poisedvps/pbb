package com.hospital.pbb.auth;

import com.hospital.pbb.auth.dto.LoginResponse;
import com.hospital.pbb.auth.dto.UserInfo;
import com.hospital.pbb.common.BizException;
import com.hospital.pbb.oplog.OpAction;
import com.hospital.pbb.oplog.OpLogService;
import com.hospital.pbb.user.AppUser;
import com.hospital.pbb.user.AppUserRepository;
import com.hospital.pbb.user.PasswordUtil;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;

/**
 * 登录 / 当前用户 / 修改密码。
 *
 * <p>密码错误累计 {@link #LOCK_THRESHOLD} 次锁定 {@link #LOCK_DURATION}。
 * <b>任何分支都不得把密码写进日志或操作日志。</b></p>
 */
@Service
public class AuthService {

    public static final int LOCK_THRESHOLD = 5;
    public static final Duration LOCK_DURATION = Duration.ofMinutes(15);

    private final AppUserRepository repo;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final OpLogService opLog;
    private final Clock clock;

    public AuthService(AppUserRepository repo, PasswordEncoder encoder, JwtService jwt,
                       OpLogService opLog, Clock clock) {
        this.repo = repo;
        this.encoder = encoder;
        this.jwt = jwt;
        this.opLog = opLog;
        this.clock = clock;
    }

    /**
     * noRollbackFor：登录失败要抛 BizException，但失败次数、锁定时间和登录失败留痕必须落库，
     * 按默认规则回滚会让账号永远锁不住（验收：错 5 次后第 6 次正确密码应返回 1002）。
     */
    @Transactional(noRollbackFor = BizException.class)
    public LoginResponse login(String username, String password) {
        AppUser user = repo.findByUsername(username).orElse(null);
        if (user == null) {
            opLog.recordAs(null, username, OpAction.LOGIN_FAIL, username, "用户不存在");
            throw new BizException(1001, "用户名或密码错误");
        }
        if (!user.isEnabled()) {
            throw new BizException(1003, "账号已停用");
        }

        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime lockedUntil = user.getLockedUntil();
        if (lockedUntil != null && lockedUntil.isAfter(now)) {
            throw new BizException(1002, "账号已锁定，请 " + remainingMinutes(now, lockedUntil) + " 分钟后再试");
        }

        if (!encoder.matches(password, user.getPasswordHash())) {
            int failed = user.getFailedAttempts() + 1;
            if (failed >= LOCK_THRESHOLD) {
                user.setLockedUntil(now.plus(LOCK_DURATION));
                user.setFailedAttempts(0);
            } else {
                user.setFailedAttempts(failed);
            }
            repo.save(user);
            opLog.recordAs(user.getId(), user.getUsername(), OpAction.LOGIN_FAIL, username, "密码错误");
            throw new BizException(1001, "用户名或密码错误");
        }

        user.setFailedAttempts(0);
        user.setLockedUntil(null);
        user.setLastLoginAt(now);
        user.setUpdatedAt(now);
        repo.save(user);
        opLog.recordAs(user.getId(), user.getUsername(), OpAction.LOGIN, username, null);
        return new LoginResponse(jwt.issue(user), UserInfo.of(user));
    }

    public UserInfo me(Long userId) {
        return UserInfo.of(loadUser(userId));
    }

    @Transactional
    public void changePassword(Long userId, String oldPassword, String newPassword) {
        AppUser user = loadUser(userId);
        if (!encoder.matches(oldPassword, user.getPasswordHash())) {
            throw new BizException(1004, "原密码错误");
        }
        if (!PasswordUtil.isStrong(newPassword)) {
            throw new BizException(1005, "新密码至少 8 位，需包含字母和数字");
        }
        if (encoder.matches(newPassword, user.getPasswordHash())) {
            throw new BizException(1006, "新密码不能与原密码相同");
        }
        user.setPasswordHash(encoder.encode(newPassword));
        user.setMustChangePassword(false);
        user.setUpdatedAt(OffsetDateTime.now(clock));
        repo.save(user);
        opLog.record(OpAction.CHANGE_PASSWORD, user.getUsername(), null);
    }

    private AppUser loadUser(Long userId) {
        return repo.findById(userId).orElseThrow(() -> new BizException(1100, "账号不存在"));
    }

    /** 锁定剩余时间，不足 1 分钟按 1 分钟算 */
    private static long remainingMinutes(OffsetDateTime now, OffsetDateTime lockedUntil) {
        long seconds = Duration.between(now, lockedUntil).getSeconds();
        return (seconds + 59) / 60;
    }
}
