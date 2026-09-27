package com.hospital.pbb.auth;

import com.hospital.pbb.auth.dto.LoginResponse;
import com.hospital.pbb.auth.dto.UserInfo;
import com.hospital.pbb.common.BizException;
import com.hospital.pbb.oplog.OpLogService;
import com.hospital.pbb.user.AppUser;
import com.hospital.pbb.user.AppUserRepository;
import com.hospital.pbb.user.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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
    private AuthService service;
    private AppUser user;

    @BeforeEach
    void setUp() {
        repo = mock(AppUserRepository.class);
        encoder = new BCryptPasswordEncoder();
        jwt = mock(JwtService.class);
        opLog = mock(OpLogService.class);
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
