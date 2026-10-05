package com.mediafactory.security;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AdminUserBootstrapService implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(AdminUserBootstrapService.class);

  private final AdminUserRepository adminUserRepository;
  private final PasswordEncoder passwordEncoder;
  private final SecurityProperties properties;

  public AdminUserBootstrapService(
      AdminUserRepository adminUserRepository,
      PasswordEncoder passwordEncoder,
      SecurityProperties properties
  ) {
    this.adminUserRepository = adminUserRepository;
    this.passwordEncoder = passwordEncoder;
    this.properties = properties;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (adminUserRepository.count() == 0) {
      String username = properties.getAdmin().getUsername();
      if (username == null || username.isBlank()) {
        username = "admin";
      }

      String email = properties.getAdmin().getEmail();
      String rawPassword = properties.getAdmin().getPassword();

      if (rawPassword == null || rawPassword.isBlank()) {
        byte[] randomBytes = new byte[18];
        new SecureRandom().nextBytes(randomBytes);
        rawPassword = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
        log.warn("==================================================================");
        log.warn("No ADMIN_PASSWORD configured. Generated bootstrap admin account:");
        log.warn("Username: {}", username);
        log.warn("Password: {}", rawPassword);
        log.warn("Store this password securely or set ADMIN_PASSWORD in environment.");
        log.warn("==================================================================");
      } else {
        log.info("Bootstrapping initial admin user '{}' from configured properties.", username);
      }

      String passwordHash = passwordEncoder.encode(rawPassword);
      AdminUser admin = new AdminUser(
          UUID.randomUUID(),
          username,
          email,
          passwordHash,
          AdminUser.ROLE_ADMIN,
          Instant.now(),
          Instant.now()
      );

      adminUserRepository.save(admin);
      log.info("Admin user '{}' successfully registered.", username);
    }
  }
}
