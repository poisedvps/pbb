package com.hospital.pbb.auth;

import com.hospital.pbb.user.AppUser;
import com.hospital.pbb.user.Role;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwtServiceTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private static final String OTHER_SECRET = "fedcba9876543210fedcba9876543210";
    private static final Instant ISSUE_TIME =
            Instant.parse("2026-10-01T01:00:00Z"); // 2026-10-01T09:00:00+08:00
    private static final ZoneOffset ZONE = ZoneOffset.ofHours(8);

    private static Clock fixedClock(Instant instant) {
        return Clock.fixed(instant, ZONE);
    }

    private static AppUser user(long id, String username, Role role) {
        AppUser u = new AppUser();
        u.setId(id);
        u.setUsername(username);
        u.setRole(role);
        return u;
    }

    private final JwtService service = new JwtService(SECRET, fixedClock(ISSUE_TIME));

    @Test
    void issueThenParseSameService() {
        String token = service.issue(user(1, "admin", Role.ADMIN));
        JwtClaims claims = service.parse(token).orElseThrow();
        assertEquals(new JwtClaims(1L, "admin", Role.ADMIN), claims);
    }

    @Test
    void parseGarbageReturnsEmpty() {
        assertTrue(service.parse("abc").isEmpty());
    }

    @Test
    void parseNullReturnsEmpty() {
        assertTrue(service.parse(null).isEmpty());
    }

    @Test
    void parseWithDifferentSecretReturnsEmpty() {
        String token = service.issue(user(1, "admin", Role.ADMIN));
        JwtService other = new JwtService(OTHER_SECRET, fixedClock(ISSUE_TIME));
        assertTrue(other.parse(token).isEmpty());
    }

    @Test
    void memberTokenValidAtPlus11Hours() {
        String token = service.issue(user(2, "nurse", Role.MEMBER));
        JwtService later = new JwtService(SECRET, fixedClock(ISSUE_TIME.plusSeconds(11 * 3600)));
        JwtClaims claims = later.parse(token).orElseThrow();
        assertEquals(new JwtClaims(2L, "nurse", Role.MEMBER), claims);
    }

    @Test
    void memberTokenExpiredAtPlus13Hours() {
        String token = service.issue(user(2, "nurse", Role.MEMBER));
        JwtService later = new JwtService(SECRET, fixedClock(ISSUE_TIME.plusSeconds(13 * 3600)));
        assertTrue(later.parse(token).isEmpty());
    }

    @Test
    void screenTokenValidAtPlus29Days() {
        String token = service.issue(user(3, "lobby", Role.SCREEN));
        JwtService later = new JwtService(SECRET,
                fixedClock(ISSUE_TIME.plusSeconds(29L * 24 * 3600)));
        JwtClaims claims = later.parse(token).orElseThrow();
        assertEquals(new JwtClaims(3L, "lobby", Role.SCREEN), claims);
    }

    @Test
    void screenTokenExpiredAtPlus31Days() {
        String token = service.issue(user(3, "lobby", Role.SCREEN));
        JwtService later = new JwtService(SECRET,
                fixedClock(ISSUE_TIME.plusSeconds(31L * 24 * 3600)));
        assertTrue(later.parse(token).isEmpty());
    }

    @Test
    void shortSecretThrows() {
        assertThrows(IllegalStateException.class, () -> new JwtService("short", fixedClock(ISSUE_TIME)));
    }
}
