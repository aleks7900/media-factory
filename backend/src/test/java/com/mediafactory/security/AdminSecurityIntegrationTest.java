package com.mediafactory.security;

import tools.jackson.databind.json.JsonMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "media.worker.enabled=false",
    "media.similarity.enabled=false"
})
class AdminSecurityIntegrationTest {

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
      DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

  @DynamicPropertySource
  static void properties(org.springframework.test.context.DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", postgres::getJdbcUrl);
    r.add("spring.datasource.username", postgres::getUsername);
    r.add("spring.datasource.password", postgres::getPassword);
    r.add("media.storage.root", () -> "build/test-media/security-" + UUID.randomUUID());
  }

  @LocalServerPort
  private int port;

  @Autowired
  private JwtService jwtService;

  @Autowired
  private AdminUserRepository adminUserRepository;

  @Autowired
  private PasswordEncoder passwordEncoder;

  @Autowired
  private RequestMappingHandlerMapping requestMappingHandlerMapping;

  private HttpClient client;
  private JsonMapper json;

  @BeforeEach
  void setUp() {
    client = HttpClient.newHttpClient();
    json = JsonMapper.builder().build();

    if (adminUserRepository.findByUsernameOrEmail("admin").isEmpty()) {
      adminUserRepository.save(new AdminUser(
          UUID.randomUUID(),
          "admin",
          "admin@mediafactory.local",
          passwordEncoder.encode("admin123"),
          AdminUser.ROLE_ADMIN,
          Instant.now(),
          Instant.now()
      ));
    }
  }

  private String baseUrl() {
    return "http://localhost:" + port;
  }

  @Test
  void validAdminLoginSuccess() throws Exception {
    String body = json.writeValueAsString(Map.of(
        "username", "admin",
        "password", "admin123"
    ));

    HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/auth/login"))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build();

    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(200);

    Map<?, ?> respBody = json.readValue(response.body(), Map.class);
    assertThat(respBody.get("token")).isNotNull();
    assertThat(respBody.get("username")).isEqualTo("admin");
    assertThat(respBody.get("role")).isEqualTo("ROLE_ADMIN");

    Optional<String> setCookie = response.headers().firstValue("Set-Cookie");
    assertThat(setCookie).isPresent();
    assertThat(setCookie.get()).contains("media_factory_jwt=");
    assertThat(setCookie.get()).contains("HttpOnly");
    assertThat(setCookie.get()).contains("Path=/");
  }

  @Test
  void loginWithWrongPasswordReturns401() throws Exception {
    String body = json.writeValueAsString(Map.of(
        "username", "admin",
        "password", "wrong-password"
    ));

    HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/auth/login"))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build();

    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(401);
    assertThat(response.body()).contains("Invalid username or password");
    assertThat(response.body()).doesNotContain("password is wrong");
  }

  @Test
  void loginWithUnknownUserReturns401() throws Exception {
    String body = json.writeValueAsString(Map.of(
        "username", "unknown-user",
        "password", "wrong-password"
    ));

    HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/auth/login"))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build();

    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(401);
    assertThat(response.body()).contains("Invalid username or password");
  }

  @Test
  void protectedEndpointWithoutJwtReturns401() throws Exception {
    HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/projects"))
        .GET()
        .build();

    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(401);
    assertThat(response.body()).contains("Authentication required");
  }

  @Test
  void protectedEndpointWithMalformedJwtReturns401() throws Exception {
    HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/projects"))
        .header("Authorization", "Bearer invalid-malformed-token-string")
        .GET()
        .build();

    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(401);
  }

  @Test
  void protectedEndpointWithExpiredJwtReturns401() throws Exception {
    String expiredToken = jwtService.generateToken("admin", AdminUser.ROLE_ADMIN, -60);

    HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/projects"))
        .header("Authorization", "Bearer " + expiredToken)
        .GET()
        .build();

    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(401);
  }

  @Test
  void protectedEndpointWithInvalidSignatureReturns401() throws Exception {
    SecurityProperties altProps = new SecurityProperties();
    altProps.getJwt().setSecret("different-secret-key-that-is-at-least-256-bits-long-for-hmac-sha256-alt!");
    JwtService altJwt = new JwtService(altProps);
    String badSigToken = altJwt.generateToken("admin", AdminUser.ROLE_ADMIN);

    HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/projects"))
        .header("Authorization", "Bearer " + badSigToken)
        .GET()
        .build();

    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(401);
  }

  @Test
  void protectedEndpointWithNonAdminJwtReturns403() throws Exception {
    String userToken = jwtService.generateToken("guest_user", "ROLE_USER");

    HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/projects"))
        .header("Authorization", "Bearer " + userToken)
        .GET()
        .build();

    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(403);
    assertThat(response.body()).contains("Access denied");
  }

  @Test
  void protectedEndpointWithValidAdminJwtSucceeds() throws Exception {
    String adminToken = jwtService.generateToken("admin", AdminUser.ROLE_ADMIN);

    HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/projects"))
        .header("Authorization", "Bearer " + adminToken)
        .GET()
        .build();

    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(200);
  }

  @Test
  void protectedEndpointWithValidAdminCookieSucceeds() throws Exception {
    String adminToken = jwtService.generateToken("admin", AdminUser.ROLE_ADMIN);

    HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/projects"))
        .header("Cookie", "media_factory_jwt=" + adminToken)
        .GET()
        .build();

    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(200);
  }

  @Test
  void publicLoginEndpointWithoutJwtIsAccessible() throws Exception {
    HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/auth/login"))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString("{}"))
        .build();

    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
    // Should be handled by controller (400 validation error or 401 bad credentials), not denied 401 unauthenticated
    assertThat(response.statusCode()).isIn(400, 401);
  }

  @Test
  void actuatorHealthEndpointIsPublic() throws Exception {
    HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + "/actuator/health"))
        .GET()
        .build();

    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(200);
  }

  @Test
  void logoutClearsCookieAndWorks() throws Exception {
    HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/auth/logout"))
        .POST(HttpRequest.BodyPublishers.noBody())
        .build();

    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(200);

    Optional<String> setCookie = response.headers().firstValue("Set-Cookie");
    assertThat(setCookie).isPresent();
    assertThat(setCookie.get()).contains("Max-Age=0");
  }

  @Test
  void routeEnumerationSecurityAuditEnsuresNoEndpointAccidentallyPublic() throws Exception {
    Set<String> publicPrefixes = Set.of(
        "/api/auth/login",
        "/api/auth/logout",
        "/actuator/health"
    );

    Set<String> testedEndpoints = new HashSet<>();

    var handlerMethods = requestMappingHandlerMapping.getHandlerMethods();
    for (var entry : handlerMethods.entrySet()) {
      var patternCondition = entry.getKey().getPathPatternsCondition();
      if (patternCondition == null) {
        continue;
      }

      for (var pattern : patternCondition.getPatterns()) {
        String path = pattern.getPatternString();

        // Replace variable templates like {id} with concrete values
        String testPath = path
            .replaceAll("\\{[^}]+:?[^}]*\\}", UUID.randomUUID().toString());

        if (publicPrefixes.stream().anyMatch(testPath::startsWith)) {
          continue;
        }

        if (testedEndpoints.add(testPath)) {
          HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + testPath))
              .GET()
              .build();

          HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
          assertThat(response.statusCode())
              .as("Endpoint '%s' must require authentication by default (deny-by-default)", testPath)
              .isEqualTo(401);
        }
      }
    }

    assertThat(testedEndpoints.size())
        .as("Security audit should verify at least 15 protected routes")
        .isGreaterThanOrEqualTo(15);
  }
}
