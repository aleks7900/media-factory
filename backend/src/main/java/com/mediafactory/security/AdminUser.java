package com.mediafactory.security;

import java.time.Instant;
import java.util.UUID;

public record AdminUser(
    UUID id,
    String username,
    String email,
    String passwordHash,
    String role,
    Instant createdAt,
    Instant updatedAt
) {
  public static final String ROLE_ADMIN = "ROLE_ADMIN";
}
