package com.hospital.pbb.user;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminInitializerTest {

    private AppUserRepository repo;
    private PasswordEncoder encoder;

    @BeforeEach
    void setUp() {
        repo = mock(AppUserRepository.class);
        encoder = new BCryptPasswordEncoder();
        when(repo.save(any(AppUser.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private AdminInitializer initializer(String initPassword) {
        return new AdminInitializer(repo, encoder, initPassword);
    }

    private AppUser savedOnce() {
        ArgumentCaptor<AppUser> saved = ArgumentCaptor.forClass(AppUser.class);
        verify(repo, times(1)).save(saved.capture());
        return saved.getValue();
    }

    @Test
    void resolvePasswordFallsBackToDefaultForMissingOrPlaceholderValues() {
        assertEquals("admin", AdminInitializer.resolvePassword(null));
        assertEquals("admin", AdminInitializer.resolvePassword(""));
        assertEquals("admin", AdminInitializer.resolvePassword("   "));
        assertEquals("admin", AdminInitializer.resolvePassword("change-me"));
        assertEquals("admin", AdminInitializer.resolvePassword("<请填写初始密码>"));
        assertEquals(AdminInitializer.DEFAULT_PASSWORD, AdminInitializer.resolvePassword(null));
    }

    @Test
    void resolvePasswordKeepsConfiguredPassword() {
        assertEquals("Abc12345", AdminInitializer.resolvePassword("Abc12345"));
    }

    @Test
    void runSkipsWhenAdminAlreadyExists() {
        when(repo.existsByUsername("admin")).thenReturn(true);

        initializer("admin").run(null);

        verify(repo, never()).save(any(AppUser.class));
    }

    @Test
    void runCreatesAdminWithDefaultPasswordWhenNotConfigured() {
        when(repo.existsByUsername("admin")).thenReturn(false);

        initializer("").run(null);

        AppUser u = savedOnce();
        assertEquals("admin", u.getUsername());
        assertEquals("科长", u.getDisplayName());
        assertEquals(Role.ADMIN, u.getRole());
        assertTrue(u.isMustChangePassword());
        assertTrue(encoder.matches("admin", u.getPasswordHash()));
    }

    @Test
    void runCreatesAdminWithConfiguredPassword() {
        when(repo.existsByUsername("admin")).thenReturn(false);

        initializer("Abc12345").run(null);

        AppUser u = savedOnce();
        assertEquals("admin", u.getUsername());
        assertEquals(Role.ADMIN, u.getRole());
        assertTrue(u.isMustChangePassword());
        assertTrue(encoder.matches("Abc12345", u.getPasswordHash()));
        assertFalse(encoder.matches("admin", u.getPasswordHash()));
    }
}
