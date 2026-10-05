package com.mediafactory.security;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

class AdminSecurityUnitTest {

  private PasswordEncoder passwordEncoder;
  private SecurityProperties properties;
  private JwtService jwtService;

  @BeforeEach
  void setUp() {
    passwordEncoder = new BCryptPasswordEncoder();
    properties = new SecurityProperties();
    properties.getJwt().setSecret("test-secret-key-that-is-at-least-256-bits-long-for-hmac-sha256-algorithms!");
    properties.getJwt().setExpirationSeconds(3600);
    jwtService = new JwtService(properties);
  }

  @Test
  void bcryptPasswordHashingAndVerification() {
    String rawPassword = "correct-admin-password-123";
    String hash = passwordEncoder.encode(rawPassword);

    assertThat(hash).isNotEqualTo(rawPassword);
    assertThat(passwordEncoder.matches(rawPassword, hash)).isTrue();
    assertThat(passwordEncoder.matches("wrong-password", hash)).isFalse();
    assertThat(passwordEncoder.matches("", hash)).isFalse();
  }

  @Test
  void jwtGenerationContainsRequiredClaims() {
    String token = jwtService.generateToken("admin", AdminUser.ROLE_ADMIN);
    assertThat(token).isNotBlank();

    JwtService.JwtValidationResult result = jwtService.validateToken(token);
    assertThat(result.valid()).isTrue();
    assertThat(result.username()).isEqualTo("admin");
    assertThat(result.roles()).contains(AdminUser.ROLE_ADMIN);
    assertThat(result.expiresAt()).isNotNull();
    assertThat(result.errorMessage()).isNull();
  }

  @Test
  void jwtRejectsExpiredToken() throws InterruptedException {
    // Generate token with negative or 0 expiration
    String expiredToken = jwtService.generateToken("admin", AdminUser.ROLE_ADMIN, -10);

    JwtService.JwtValidationResult result = jwtService.validateToken(expiredToken);
    assertThat(result.valid()).isFalse();
    assertThat(result.errorMessage()).contains("expired");
  }

  @Test
  void jwtRejectsMalformedToken() {
    JwtService.JwtValidationResult result = jwtService.validateToken("not-a-valid-jwt-token");
    assertThat(result.valid()).isFalse();
    assertThat(result.errorMessage()).contains("Malformed");
  }

  @Test
  void jwtRejectsInvalidSignature() {
    SecurityProperties otherProps = new SecurityProperties();
    otherProps.getJwt().setSecret("different-secret-key-that-is-also-at-least-256-bits-long-for-hmac-sha256!");
    JwtService otherJwtService = new JwtService(otherProps);

    String tokenSignedByOtherKey = otherJwtService.generateToken("admin", AdminUser.ROLE_ADMIN);

    JwtService.JwtValidationResult result = jwtService.validateToken(tokenSignedByOtherKey);
    assertThat(result.valid()).isFalse();
    assertThat(result.errorMessage()).contains("Invalid token signature");
  }

  @Test
  void jwtRecognizesNonAdminRole() {
    String userToken = jwtService.generateToken("regular_user", "ROLE_USER");

    JwtService.JwtValidationResult result = jwtService.validateToken(userToken);
    assertThat(result.valid()).isTrue();
    assertThat(result.username()).isEqualTo("regular_user");
    assertThat(result.roles()).contains("ROLE_USER");
    assertThat(result.roles()).doesNotContain(AdminUser.ROLE_ADMIN);
  }
}
