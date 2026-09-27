package com.hospital.pbb.auth;

import com.hospital.pbb.user.AppUser;
import com.hospital.pbb.user.Role;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Date;
import java.util.Optional;

@Service
public class JwtService {
    public static final Duration TTL_DEFAULT = Duration.ofHours(12);
    public static final Duration TTL_SCREEN  = Duration.ofDays(30);

    private final SecretKey key;
    private final Clock clock;

    @Autowired
    public JwtService(@Value("${pbb.jwt-secret}") String secret) { this(secret, Clock.systemDefaultZone()); }

    /** 包级可见，供测试注入时钟 */
    JwtService(String secret, Clock clock) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException("PBB_JWT_SECRET 至少 32 字节");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
        this.clock = clock;
    }

    public String issue(AppUser user) {
        Role role = user.getRole();
        Duration ttl = role == Role.SCREEN ? TTL_SCREEN : TTL_DEFAULT;
        Date issuedAt = Date.from(clock.instant());
        Date expiresAt = new Date(issuedAt.getTime() + ttl.toMillis());
        return Jwts.builder()
                .subject(user.getId().toString())
                .claim("u", user.getUsername())
                .claim("r", role.name())
                .issuedAt(issuedAt)
                .expiration(expiresAt)
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    public Optional<JwtClaims> parse(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .clock(() -> Date.from(clock.instant()))
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return Optional.of(new JwtClaims(
                    Long.valueOf(claims.getSubject()),
                    claims.get("u", String.class),
                    Role.valueOf(claims.get("r", String.class))));
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
