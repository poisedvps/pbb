package com.hospital.pbb.auth;

import com.hospital.pbb.auth.dto.LoginRequest;
import com.hospital.pbb.auth.dto.LoginResponse;
import com.hospital.pbb.auth.dto.UserInfo;
import com.hospital.pbb.common.BizException;
import com.hospital.pbb.oplog.OpAction;
import com.hospital.pbb.oplog.OpLogService;
import com.hospital.pbb.user.AppUser;
import com.hospital.pbb.user.AppUserRepository;
import com.hospital.pbb.user.Role;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-01T01:00:00Z"), ZONE);
    private static final OffsetDateTime NOW = OffsetDateTime.now(CLOCK);
    private static final String PASSWORD = "abc12345";

    private AppUserRepository repo;
    private PasswordEncoder encoder;
    private JwtService jwt;
    private OpLogService opLog;
    private EntityManager entityManager;
    private AuthService service;
    private AppUser user;

    @BeforeEach
    void setUp() {
        repo = mock(AppUserRepository.class);
        encoder = new BCryptPasswordEncoder();
        jwt = mock(JwtService.class);
        opLog = mock(OpLogService.class);
        entityManager = mock(EntityManager.class);
        when(jwt.issue(any(AppUser.class))).thenReturn("token-abc");
        when(repo.save(any(AppUser.class))).thenAnswer(inv -> inv.getArgument(0));

        user = new AppUser();
        user.setId(1L);
        user.setUsername("admin");
        user.setDisplayName("科长");
        user.setRole(Role.ADMIN);
        user.setPasswordHash(encoder.encode(PASSWORD));
        user.setMustChangePassword(true);
        when(repo.findByUsername("admin")).thenReturn(Optional.of(user));
        when(repo.findByUsername("ghost")).thenReturn(Optional.empty());
        when(repo.findById(1L)).thenReturn(Optional.of(user));

        service = new AuthService(repo, encoder, jwt, opLog, CLOCK);
        // EntityManager 由 @PersistenceContext 字段注入，单元测试里手动塞一个 mock
        ReflectionTestUtils.setField(service, "entityManager", entityManager);
    }

    private int bizCode(Runnable call) {
        BizException e = assertThrows(BizException.class, call::run);
        return e.getCode();
    }

    @Test
    void loginWithCorrectPassword() {
        user.setFailedAttempts(2);

        LoginResponse resp = service.login("admin", PASSWORD);

        assertNotNull(resp.token());
        assertFalse(resp.token().isEmpty());
        UserInfo info = resp.user();
        assertEquals(1L, info.id());
        assertEquals("admin", info.username());
        assertEquals("科长", info.displayName());
        assertEquals(Role.ADMIN, info.role());
        assertTrue(info.mustChangePassword());
        assertEquals(0, user.getFailedAttempts());
        assertNull(user.getLockedUntil());
        assertEquals(NOW, user.getLastLoginAt());
        assertEquals(NOW, user.getUpdatedAt());
    }

    @Test
    void loginUnknownUserReturns1001() {
        assertEquals(1001, bizCode(() -> service.login("ghost", PASSWORD)));
    }

    /** 并发错误密码不能绕过锁定：读完后必须拿行级排他锁，再判断 enabled / lockedUntil / 密码 */
    @Test
    void loginLocksUserRowBeforeChecking() {
        service.login("admin", PASSWORD);

        verify(entityManager).refresh(user, LockModeType.PESSIMISTIC_WRITE);
    }

    @Test
    void wrongPasswordStillLocksUserRow() {
        assertEquals(1001, bizCode(() -> service.login("admin", "wrong123")));

        verify(entityManager).refresh(user, LockModeType.PESSIMISTIC_WRITE);
    }

    /** 用户名长度不在数据库字段范围内时，不能把超长值直接写进操作日志（否则 VARCHAR(32) 报 500） */
    @Test
    void unknownUserWithTooLongUsernameLogsTruncatedName() {
        String longName = "u".repeat(40);
        when(repo.findByUsername(longName)).thenReturn(Optional.empty());

        assertEquals(1001, bizCode(() -> service.login(longName, PASSWORD)));

        ArgumentCaptor<String> logged = ArgumentCaptor.forClass(String.class);
        verify(opLog).recordAs(isNull(), logged.capture(), eq(OpAction.LOGIN_FAIL), logged.capture(), eq("用户不存在"));
        assertEquals(2, logged.getAllValues().size());
        for (String value : logged.getAllValues()) {
            assertEquals(AuthService.USERNAME_MAX, value.length());
        }
        verify(entityManager, never()).refresh(any(), any(LockModeType.class));
    }

    /** 校验层就要拦掉超长用户名，接口返回参数错误而不是数据库异常 */
    @Test
    void loginRequestRejectsUsernameLongerThan32() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

        Set<ConstraintViolation<LoginRequest>> violations = validator.validate(new LoginRequest("u".repeat(33), PASSWORD));

        assertEquals(1, violations.size());
        assertEquals("username", violations.iterator().next().getPropertyPath().toString());
        assertTrue(validator.validate(new LoginRequest("admin", PASSWORD)).isEmpty());
    }

    @Test
    void wrongPasswordIncrementsFailedAttempts() {
        user.setFailedAttempts(3);

        assertEquals(1001, bizCode(() -> service.login("admin", "wrong123")));

        assertEquals(4, user.getFailedAttempts());
        assertNull(user.getLockedUntil());
    }

    @Test
    void fifthWrongPasswordLocksForFifteenMinutes() {
        user.setFailedAttempts(4);

        assertEquals(1001, bizCode(() -> service.login("admin", "wrong123")));

        assertEquals(0, user.getFailedAttempts());
        assertEquals(NOW.plus(Duration.ofMinutes(15)), user.getLockedUntil());
    }

    @Test
    void loginWhileLockedReturns1002() {
        user.setLockedUntil(NOW.plusMinutes(10));

        BizException e = assertThrows(BizException.class, () -> service.login("admin", PASSWORD));

        assertEquals(1002, e.getCode());
        assertTrue(e.getMessage().contains("10"), e.getMessage());
    }

    @Test
    void loginAfterLockExpiredSucceeds() {
        user.setLockedUntil(NOW.minusMinutes(1));

        LoginResponse resp = service.login("admin", PASSWORD);

        assertNotNull(resp.token());
        assertNull(user.getLockedUntil());
    }

    @Test
    void disabledAccountReturns1003() {
        user.setEnabled(false);

        assertEquals(1003, bizCode(() -> service.login("admin", PASSWORD)));
    }

    @Test
    void changePasswordWrongOldReturns1004() {
        assertEquals(1004, bizCode(() -> service.changePassword(1L, "wrong123", "xyz98765")));
    }

    @Test
    void changePasswordWeakNewReturns1005() {
        assertEquals(1005, bizCode(() -> service.changePassword(1L, PASSWORD, "abcdefgh")));
    }

    @Test
    void changePasswordSameAsOldReturns1006() {
        assertEquals(1006, bizCode(() -> service.changePassword(1L, PASSWORD, PASSWORD)));
    }

    @Test
    void changePasswordSucceeds() {
        service.changePassword(1L, PASSWORD, "xyz98765");

        assertTrue(encoder.matches("xyz98765", user.getPasswordHash()));
        assertFalse(user.isMustChangePassword());
        assertEquals(NOW, user.getUpdatedAt());
    }
}
