package com.hospital.pbb.auth;

import com.hospital.pbb.user.AppUser;
import com.hospital.pbb.user.AppUserRepository;
import com.hospital.pbb.user.AuthUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 从 Authorization: Bearer &lt;token&gt; 解析当前用户并放入 SecurityContext。
 * 不加 @Component，由 SecurityConfig 手动 new，避免被注册成两次 Servlet 过滤器。
 */
public class JwtAuthFilter extends OncePerRequestFilter {

    /** 未修改初始密码时仍然允许访问的接口 */
    public static final Set<String> MUST_CHANGE_WHITELIST =
            Set.of("/api/auth/me", "/api/auth/change-password", "/api/auth/logout");

    private static final String BEARER = "Bearer ";

    private final JwtService jwtService;
    private final AppUserRepository userRepo;
    private final JsonErrorWriter writer;

    public JwtAuthFilter(JwtService jwtService, AppUserRepository userRepo, JsonErrorWriter writer) {
        this.jwtService = jwtService;
        this.userRepo = userRepo;
        this.writer = writer;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws ServletException, IOException {
        String header = req.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER)) {
            chain.doFilter(req, resp);
            return;
        }
        Optional<JwtClaims> claims = jwtService.parse(header.substring(BEARER.length()).trim());
        if (claims.isEmpty()) {
            chain.doFilter(req, resp);
            return;
        }
        AppUser user = userRepo.findById(claims.get().userId()).orElse(null);
        if (user == null || !user.isEnabled()) {
            // 不放行认证信息，后续由 authenticationEntryPoint 返回 401
            chain.doFilter(req, resp);
            return;
        }

        AuthUser principal = new AuthUser(user.getId(), user.getUsername(), user.getRole(), user.getStaffId());
        List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, authorities));

        if (user.isMustChangePassword() && !MUST_CHANGE_WHITELIST.contains(req.getRequestURI())) {
            writer.write(resp, 403, 4031, "请先修改初始密码");
            return;
        }
        chain.doFilter(req, resp);
    }
}
