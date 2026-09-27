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
        if (initPassword == null || initPassword.isEmpty()
                || "change-me".equals(initPassword) || initPassword.startsWith("<")) {
            throw new IllegalStateException("PBB_ADMIN_INIT_PASSWORD 未配置");
        }
        AppUser admin = new AppUser();
        admin.setUsername("admin");
        admin.setDisplayName("科长");
        admin.setRole(Role.ADMIN);
        admin.setPasswordHash(encoder.encode(initPassword));
        admin.setMustChangePassword(true);
        repo.save(admin);
        log.info("已创建初始管理员 admin");
    }
}
