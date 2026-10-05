package com.mediafactory.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class JwtService {

  private static final Logger log = LoggerFactory.getLogger(JwtService.class);
  private final SecurityProperties properties;

  public JwtService(SecurityProperties properties) {
    this.properties = properties;
  }

  public String generateToken(String username, String role) {
    return generateToken(username, role, properties.getJwt().getExpirationSeconds());
  }

  public String generateToken(String username, String role, long expirationSeconds) {
    try {
      Instant now = Instant.now();
      Instant expiresAt = now.plusSeconds(expirationSeconds);

      JWTClaimsSet claims = new JWTClaimsSet.Builder()
          .subject(username)
          .issuer("media-factory")
          .issueTime(Date.from(now))
          .expirationTime(Date.from(expiresAt))
          .claim("role", role)
          .claim("roles", List.of(role))
          .build();

      SignedJWT signedJWT = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
      JWSSigner signer = new MACSigner(getSigningKeyBytes());
      signedJWT.sign(signer);

      return signedJWT.serialize();
    } catch (Exception e) {
      log.error("Failed to generate JWT for user {}", username, e);
      throw new RuntimeException("Could not generate JWT token", e);
    }
  }

  public JwtValidationResult validateToken(String token) {
    if (token == null || token.isBlank()) {
      return new JwtValidationResult(false, null, List.of(), null, "Missing token");
    }

    try {
      SignedJWT signedJWT = SignedJWT.parse(token);

      if (!JWSAlgorithm.HS256.equals(signedJWT.getHeader().getAlgorithm())) {
        return new JwtValidationResult(false, null, List.of(), null, "Algorithm mismatch");
      }

      JWSVerifier verifier = new MACVerifier(getSigningKeyBytes());
      if (!signedJWT.verify(verifier)) {
        return new JwtValidationResult(false, null, List.of(), null, "Invalid token signature");
      }

      JWTClaimsSet claims = signedJWT.getJWTClaimsSet();
      Date expiration = claims.getExpirationTime();
      if (expiration == null || new Date().after(expiration)) {
        return new JwtValidationResult(false, null, List.of(), null, "Token expired");
      }

      String username = claims.getSubject();
      List<String> roles = claims.getStringListClaim("roles");
      if (roles == null || roles.isEmpty()) {
        String singleRole = claims.getStringClaim("role");
        if (singleRole != null) {
          roles = List.of(singleRole);
        } else {
          roles = List.of();
        }
      }

      Instant expInstant = expiration.toInstant();
      return new JwtValidationResult(true, username, roles, expInstant, null);
    } catch (Exception e) {
      return new JwtValidationResult(false, null, List.of(), null, "Malformed token: " + e.getMessage());
    }
  }

  private byte[] getSigningKeyBytes() {
    String secret = properties.getJwt().getSecret();
    if (secret == null || secret.isBlank()) {
      throw new IllegalStateException("JWT secret must not be blank");
    }
    byte[] raw = secret.getBytes(StandardCharsets.UTF_8);
    if (raw.length >= 32) {
      return raw;
    }
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      return md.digest(raw);
    } catch (NoSuchAlgorithmException e) {
      throw new RuntimeException("SHA-256 not available", e);
    }
  }

  public record JwtValidationResult(
      boolean valid,
      String username,
      List<String> roles,
      Instant expiresAt,
      String errorMessage
  ) {}
}
