package com.hospital.pbb.user;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class AdminInitializer implements ApplicationRunner {

    /** 未配置初始密码时使用的默认值 */
    public static final String DEFAULT_PASSWORD = "admin";

    private static final Logger log = LoggerFactory.getLogger(AdminInitializer.class);

    private final AppUserRepository repo;
    private final PasswordEncoder encoder;
    private final String initPassword;

    public AdminInitializer(AppUserRepository repo, PasswordEncoder encoder,
                            @Value("${pbb.admin-init-password}") String initPassword) {
        this.repo = repo;
        this.encoder = encoder;
        this.initPassword = initPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (repo.existsByUsername("admin")) {
            return;
        }
        String pw = resolvePassword(initPassword);
        AppUser admin = new AppUser();
        admin.setUsername("admin");
        admin.setDisplayName("科长");
        admin.setRole(Role.ADMIN);
        admin.setPasswordHash(encoder.encode(pw));
        admin.setMustChangePassword(true);
        repo.save(admin);
        log.info("已创建初始管理员 admin");
    }

    /** 返回实际使用的初始密码：initPassword 为 null、空白、等于 "change-me" 或以 "<" 开头时返回 DEFAULT_PASSWORD，否则原样返回 */
    static String resolvePassword(String initPassword) {
        if (initPassword == null || initPassword.isBlank()
                || "change-me".equals(initPassword) || initPassword.startsWith("<")) {
            return DEFAULT_PASSWORD;
        }
        return initPassword;
    }
}
