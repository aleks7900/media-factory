package com.mediafactory.security;

import jakarta.validation.Valid;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthenticationController {

  private final AuthenticationManager authenticationManager;
  private final JwtService jwtService;
  private final SecurityProperties properties;

  public AuthenticationController(
      AuthenticationManager authenticationManager,
      JwtService jwtService,
      SecurityProperties properties
  ) {
    this.authenticationManager = authenticationManager;
    this.jwtService = jwtService;
    this.properties = properties;
  }

  @PostMapping("/login")
  public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request) {
    Authentication authentication;
    try {
      authentication = authenticationManager.authenticate(
          new UsernamePasswordAuthenticationToken(request.username(), request.password())
      );
    } catch (AuthenticationException ex) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
          .body(Map.of("error", "Unauthorized", "message", "Invalid username or password"));
    }

    String principalName = authentication.getName();
    String username = (properties.getAdmin().getEmail() != null
        && properties.getAdmin().getEmail().equalsIgnoreCase(principalName))
        ? properties.getAdmin().getUsername()
        : principalName;

    String role = authentication.getAuthorities().stream()
        .map(GrantedAuthority::getAuthority)
        .findFirst()
        .orElse(AdminUser.ROLE_ADMIN);

    String token = jwtService.generateToken(username, role);
    long expirationSeconds = properties.getJwt().getExpirationSeconds();

    ResponseCookie cookie = ResponseCookie.from(properties.getCookie().getName(), token)
        .httpOnly(true)
        .secure(properties.getCookie().isSecure())
        .path("/")
        .maxAge(expirationSeconds)
        .sameSite(properties.getCookie().getSameSite())
        .build();

    LoginResponse responseBody = new LoginResponse(
        token,
        username,
        role,
        Instant.now().plusSeconds(expirationSeconds)
    );

    return ResponseEntity.ok()
        .header(HttpHeaders.SET_COOKIE, cookie.toString())
        .body(responseBody);
  }

  @PostMapping("/logout")
  public ResponseEntity<?> logout() {
    ResponseCookie cookie = ResponseCookie.from(properties.getCookie().getName(), "")
        .httpOnly(true)
        .secure(properties.getCookie().isSecure())
        .path("/")
        .maxAge(0)
        .sameSite(properties.getCookie().getSameSite())
        .build();

    return ResponseEntity.ok()
        .header(HttpHeaders.SET_COOKIE, cookie.toString())
        .body(Map.of("message", "Logged out successfully"));
  }

  @GetMapping("/me")
  public ResponseEntity<?> me(Authentication authentication) {
    if (authentication == null || !authentication.isAuthenticated()) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
          .body(Map.of("error", "Unauthorized", "message", "Not authenticated"));
    }

    return ResponseEntity.ok(Map.of(
        "username", authentication.getName(),
        "role", "ROLE_ADMIN"
    ));
  }
}
