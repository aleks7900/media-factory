package com.mediafactory.security;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AdminUserRepository {

  private final JdbcClient db;

  public AdminUserRepository(JdbcClient db) {
    this.db = db;
  }

  public Optional<AdminUser> findByUsernameOrEmail(String identifier) {
    if (identifier == null || identifier.isBlank()) {
      return Optional.empty();
    }
    String cleaned = identifier.trim().toLowerCase(Locale.ROOT);
    return db.sql("""
        SELECT id, username, email, password_hash, role, created_at, updated_at
        FROM admin_users
        WHERE LOWER(username) = :identifier OR (email IS NOT NULL AND LOWER(email) = :identifier)
        LIMIT 1
        """)
        .param("identifier", cleaned)
        .query(this::mapRow)
        .optional();
  }

  public Optional<AdminUser> findById(UUID id) {
    if (id == null) {
      return Optional.empty();
    }
    return db.sql("""
        SELECT id, username, email, password_hash, role, created_at, updated_at
        FROM admin_users
        WHERE id = :id
        """)
        .param("id", id)
        .query(this::mapRow)
        .optional();
  }

  public long count() {
    return db.sql("SELECT count(*) FROM admin_users").query(Long.class).single();
  }

  public AdminUser save(AdminUser user) {
    db.sql("""
        INSERT INTO admin_users (id, username, email, password_hash, role, created_at, updated_at)
        VALUES (:id, :username, :email, :passwordHash, :role, :createdAt, :updatedAt)
        ON CONFLICT (username) DO UPDATE
        SET email = EXCLUDED.email,
            password_hash = EXCLUDED.password_hash,
            role = EXCLUDED.role,
            updated_at = EXCLUDED.updated_at
        """)
        .param("id", user.id())
        .param("username", user.username())
        .param("email", user.email())
        .param("passwordHash", user.passwordHash())
        .param("role", user.role())
        .param("createdAt", Timestamp.from(user.createdAt()))
        .param("updatedAt", Timestamp.from(user.updatedAt()))
        .update();
    return user;
  }

  private AdminUser mapRow(ResultSet rs, int rowNum) throws SQLException {
    Timestamp created = rs.getTimestamp("created_at");
    Timestamp updated = rs.getTimestamp("updated_at");
    return new AdminUser(
        rs.getObject("id", UUID.class),
        rs.getString("username"),
        rs.getString("email"),
        rs.getString("password_hash"),
        rs.getString("role"),
        created != null ? created.toInstant() : null,
        updated != null ? updated.toInstant() : null
    );
  }
}
