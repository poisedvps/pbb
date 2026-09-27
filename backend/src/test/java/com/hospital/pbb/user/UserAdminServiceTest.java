package com.hospital.pbb.user;

import com.hospital.pbb.common.BizException;
import com.hospital.pbb.oplog.OpAction;
import com.hospital.pbb.oplog.OpLogService;
import com.hospital.pbb.user.dto.CreateUserRequest;
import com.hospital.pbb.user.dto.TempPasswordVO;
import com.hospital.pbb.user.dto.UserVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserAdminServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-01T01:00:00Z"), ZONE);
    private static final OffsetDateTime NOW = OffsetDateTime.now(CLOCK);

    private AppUserRepository repo;
    private PasswordEncoder encoder;
    private OpLogService opLog;
    private UserAdminService service;

    @BeforeEach
    void setUp() {
        repo = mock(AppUserRepository.class);
        encoder = new BCryptPasswordEncoder();
        opLog = mock(OpLogService.class);
        when(repo.save(any(AppUser.class))).thenAnswer(inv -> inv.getArgument(0));
        service = new UserAdminService(repo, encoder, opLog, CLOCK);
    }

    private AppUser user(long id, String username, Role role) {
        AppUser u = new AppUser();
        u.setId(id);
        u.setUsername(username);
        u.setDisplayName(username + "昵称");
        u.setRole(role);
        u.setPasswordHash(encoder.encode("oldpw1234"));
        u.setMustChangePassword(true);
        when(repo.findById(id)).thenReturn(Optional.of(u));
        return u;
    }

    private int bizCode(Runnable call) {
        BizException e = assertThrows(BizException.class, call::run);
        return e.getCode();
    }

    @Test
    void listMarksUserLockedWhenLockedUntilInFuture() {
        AppUser u = user(1L, "admin", Role.ADMIN);
        u.setLockedUntil(NOW.plus(Duration.ofMinutes(5)));
        when(repo.findAllByOrderByIdAsc()).thenReturn(List.of(u));

        List<UserVO> list = service.list();

        assertEquals(1, list.size());
        assertTrue(list.get(0).locked());
    }

    @Test
    void listMarksUserUnlockedWhenLockedUntilExpired() {
        AppUser u = user(1L, "admin", Role.ADMIN);
        u.setLockedUntil(NOW.minus(Duration.ofMinutes(5)));
        when(repo.findAllByOrderByIdAsc()).thenReturn(List.of(u));

        List<UserVO> list = service.list();

        assertFalse(list.get(0).locked());
    }

    @Test
    void listKeepsOrderAndMapsFields() {
        AppUser admin = user(1L, "admin", Role.ADMIN);
        admin.setLastLoginAt(NOW);
        AppUser screen = user(2L, "screen1", Role.SCREEN);
        screen.setEnabled(false);
        screen.setLockedUntil(null);
        when(repo.findAllByOrderByIdAsc()).thenReturn(List.of(admin, screen));

        List<UserVO> list = service.list();

        assertEquals(List.of(1L, 2L), list.stream().map(UserVO::id).toList());
        UserVO first = list.get(0);
        assertEquals("admin", first.username());
        assertEquals("admin昵称", first.displayName());
        assertEquals(Role.ADMIN, first.role());
        assertTrue(first.enabled());
        assertFalse(first.locked());
        assertEquals(NOW, first.lastLoginAt());
        assertNull(first.staffId());
        UserVO second = list.get(1);
        assertEquals(Role.SCREEN, second.role());
        assertFalse(second.enabled());
        assertFalse(second.locked());
    }

    @Test
    void createScreenUserRejectsNonScreenRole() {
        assertEquals(1102, bizCode(() ->
                service.createScreenUser(new CreateUserRequest("mem1", "成员", Role.MEMBER))));
        assertEquals(1102, bizCode(() ->
                service.createScreenUser(new CreateUserRequest("adm9", "科长", Role.ADMIN))));
        verify(repo, never()).save(any(AppUser.class));
    }

    @Test
    void createScreenUserRejectsExistingUsername() {
        when(repo.existsByUsername("screen1")).thenReturn(true);

        assertEquals(1201, bizCode(() ->
                service.createScreenUser(new CreateUserRequest("screen1", "值班大屏", Role.SCREEN))));

        verify(repo, never()).save(any(AppUser.class));
    }

    @Test
    void createScreenUserSavesScreenAccountWithTempPassword() {
        when(repo.existsByUsername("screen1")).thenReturn(false);

        TempPasswordVO vo = service.createScreenUser(new CreateUserRequest("screen1", "值班大屏", Role.SCREEN));

        assertEquals(10, vo.tempPassword().length());
        assertTrue(PasswordUtil.isStrong(vo.tempPassword()));

        ArgumentCaptor<AppUser> saved = ArgumentCaptor.forClass(AppUser.class);
        verify(repo).save(saved.capture());
        AppUser u = saved.getValue();
        assertEquals("screen1", u.getUsername());
        assertEquals("值班大屏", u.getDisplayName());
        assertEquals(Role.SCREEN, u.getRole());
        assertTrue(u.isEnabled());
        assertFalse(u.isMustChangePassword());
        assertTrue(encoder.matches(vo.tempPassword(), u.getPasswordHash()));
        assertEquals(NOW, u.getCreatedAt());
        assertEquals(NOW, u.getUpdatedAt());

        verify(opLog).record(eq(OpAction.CREATE_USER), eq("screen1"), anyString());
    }

    /** 临时密码绝不能进操作日志 */
    @Test
    void createScreenUserNeverLogsPassword() {
        TempPasswordVO vo = service.createScreenUser(new CreateUserRequest("screen1", "值班大屏", Role.SCREEN));

        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(opLog).record(eq(OpAction.CREATE_USER), eq("screen1"), detail.capture());
        assertFalse(detail.getAllValues().contains(vo.tempPassword()));
    }

    @Test
    void resetPasswordForMemberForcesChange() {
        AppUser u = user(3L, "member1", Role.MEMBER);
        u.setFailedAttempts(4);
        u.setLockedUntil(NOW.plusMinutes(10));

        TempPasswordVO vo = service.resetPassword(3L);

        assertTrue(PasswordUtil.isStrong(vo.tempPassword()));
        assertTrue(encoder.matches(vo.tempPassword(), u.getPasswordHash()));
        assertTrue(u.isMustChangePassword());
        assertEquals(0, u.getFailedAttempts());
        assertNull(u.getLockedUntil());
        assertEquals(NOW, u.getUpdatedAt());
        assertNotEquals("oldpw1234", vo.tempPassword());

        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(opLog).record(eq(OpAction.RESET_PASSWORD), eq("member1"), detail.capture());
        assertFalse(detail.getAllValues().contains(vo.tempPassword()));
    }

    @Test
    void resetPasswordForScreenKeepsNoForcedChange() {
        AppUser u = user(4L, "screen1", Role.SCREEN);

        TempPasswordVO vo = service.resetPassword(4L);

        assertTrue(encoder.matches(vo.tempPassword(), u.getPasswordHash()));
        assertFalse(u.isMustChangePassword());
    }

    @Test
    void resetPasswordUnknownUserReturns1100() {
        when(repo.findById(999L)).thenReturn(Optional.empty());

        assertEquals(1100, bizCode(() -> service.resetPassword(999L)));
    }

    @Test
    void unlockClearsAttemptsAndLock() {
        AppUser u = user(5L, "member2", Role.MEMBER);
        u.setFailedAttempts(3);
        u.setLockedUntil(NOW.plusMinutes(7));

        service.unlock(5L);

        assertEquals(0, u.getFailedAttempts());
        assertNull(u.getLockedUntil());
        assertEquals(NOW, u.getUpdatedAt());
        verify(opLog).record(OpAction.UNLOCK_USER, "member2", null);
    }

    @Test
    void unlockUnknownUserReturns1100() {
        when(repo.findById(999L)).thenReturn(Optional.empty());

        assertEquals(1100, bizCode(() -> service.unlock(999L)));
    }

    @Test
    void disableSelfReturns1101() {
        user(1L, "admin", Role.ADMIN);

        assertEquals(1101, bizCode(() -> service.disable(1L, 1L)));

        verify(repo, never()).save(any(AppUser.class));
    }

    @Test
    void disableOtherUserTurnsAccountOff() {
        AppUser target = user(2L, "member3", Role.MEMBER);

        service.disable(2L, 1L);

        assertFalse(target.isEnabled());
        assertEquals(NOW, target.getUpdatedAt());
        verify(opLog).record(OpAction.DISABLE_USER, "member3", null);
    }

    @Test
    void disableUnknownUserReturns1100() {
        when(repo.findById(999L)).thenReturn(Optional.empty());

        assertEquals(1100, bizCode(() -> service.disable(999L, 1L)));
    }

    @Test
    void enableTurnsAccountOn() {
        AppUser u = user(6L, "member4", Role.MEMBER);
        u.setEnabled(false);

        service.enable(6L);

        assertTrue(u.isEnabled());
        assertEquals(NOW, u.getUpdatedAt());
        verify(opLog).record(OpAction.ENABLE_USER, "member4", null);
    }

    @Test
    void enableUnknownUserReturns1100() {
        when(repo.findById(999L)).thenReturn(Optional.empty());

        assertEquals(1100, bizCode(() -> service.enable(999L)));
    }
}
