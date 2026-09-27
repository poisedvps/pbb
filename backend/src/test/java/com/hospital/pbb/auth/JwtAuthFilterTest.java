package com.hospital.pbb.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospital.pbb.user.AppUser;
import com.hospital.pbb.user.AppUserRepository;
import com.hospital.pbb.user.AuthUser;
import com.hospital.pbb.user.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JwtAuthFilterTest {

    private JwtService jwtService;
    private AppUserRepository userRepo;
    private JwtAuthFilter filter;
    private MockHttpServletResponse resp;
    private MockFilterChain chain;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        jwtService = mock(JwtService.class);
        userRepo = mock(AppUserRepository.class);
        filter = new JwtAuthFilter(jwtService, userRepo, new JsonErrorWriter(new ObjectMapper()));
        resp = new MockHttpServletResponse();
        chain = new MockFilterChain();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static AppUser user(long id, String username, Role role, boolean enabled, boolean mustChange) {
        AppUser u = new AppUser();
        u.setId(id);
        u.setUsername(username);
        u.setRole(role);
        u.setEnabled(enabled);
        u.setMustChangePassword(mustChange);
        return u;
    }

    private void givenValidToken(String uri, AppUser user) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", uri);
        req.setRequestURI(uri);
        req.addHeader("Authorization", "Bearer tk");
        when(jwtService.parse(anyString())).thenReturn(Optional.of(new JwtClaims(user.getId(), user.getUsername(), user.getRole())));
        when(userRepo.findById(user.getId())).thenReturn(Optional.of(user));
        filter.doFilter(req, resp, chain);
    }

    @Test
    void noAuthHeaderPassesThroughWithoutAuthentication() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/staff");
        req.setRequestURI("/api/staff");

        filter.doFilter(req, resp, chain);

        assertNotNull(chain.getRequest(), "chain 应被调用");
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void validTokenSetsPrincipalAndRoleAuthority() throws Exception {
        givenValidToken("/api/staff", user(1L, "admin", Role.ADMIN, true, false));

        assertNotNull(chain.getRequest(), "chain 应被调用");
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertNotNull(auth);
        assertEquals(new AuthUser(1L, "admin", Role.ADMIN, null), auth.getPrincipal());
        assertTrue(auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority())));
        assertEquals(1, auth.getAuthorities().size());
    }

    @Test
    void disabledUserLeavesContextEmpty() throws Exception {
        givenValidToken("/api/staff", user(1L, "admin", Role.ADMIN, false, false));

        assertNotNull(chain.getRequest(), "chain 应被调用");
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void mustChangePasswordBlocksOtherApi() throws Exception {
        givenValidToken("/api/staff", user(2L, "it001", Role.MEMBER, true, true));

        assertNull(chain.getRequest(), "chain 不应被调用");
        assertEquals(403, resp.getStatus());
        assertTrue(resp.getContentAsString().contains("\"code\":4031"), resp.getContentAsString());
        assertTrue(resp.getContentAsString().contains("请先修改初始密码"));
        assertEquals("application/json;charset=UTF-8", resp.getContentType());
    }

    @Test
    void mustChangePasswordAllowsWhitelist() throws Exception {
        givenValidToken("/api/auth/me", user(2L, "it001", Role.MEMBER, true, true));

        assertNotNull(chain.getRequest(), "chain 应被调用");
        assertEquals(200, resp.getStatus());
    }

    @Test
    void invalidTokenPassesThroughWithoutAuthentication() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/staff");
        req.setRequestURI("/api/staff");
        req.addHeader("Authorization", "Bearer bad");
        when(jwtService.parse(anyString())).thenReturn(Optional.empty());

        filter.doFilter(req, resp, chain);

        assertNotNull(chain.getRequest(), "chain 应被调用");
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void nonBearerHeaderPassesThroughWithoutAuthentication() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/staff");
        req.setRequestURI("/api/staff");
        req.addHeader("Authorization", "Basic abc");

        filter.doFilter(req, resp, chain);

        assertNotNull(chain.getRequest(), "chain 应被调用");
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void missingUserLeavesContextEmpty() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/staff");
        req.setRequestURI("/api/staff");
        req.addHeader("Authorization", "Bearer tk");
        when(jwtService.parse(anyString())).thenReturn(Optional.of(new JwtClaims(9L, "ghost", Role.MEMBER)));
        when(userRepo.findById(9L)).thenReturn(Optional.empty());

        filter.doFilter(req, resp, chain);

        assertNotNull(chain.getRequest(), "chain 应被调用");
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }
}
