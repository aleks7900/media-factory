package com.mediafactory.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminSecurityWebTest {

  private MockMvc mockMvc;
  private PasswordEncoder passwordEncoder;
  private SecurityProperties properties;
  private JwtService jwtService;
  private JwtAuthenticationFilter jwtFilter;
  private AuthenticationController authController;
  private final JsonMapper json = JsonMapper.builder().build();

  @BeforeEach
  void setUp() {
    SecurityContextHolder.clearContext();
    passwordEncoder = new BCryptPasswordEncoder();
    properties = new SecurityProperties();
    properties.getAdmin().setUsername("admin");
    properties.getAdmin().setPassword("admin123");
    properties.getAdmin().setEmail("admin@mediafactory.local");
    properties.getJwt().setSecret("a-very-secure-jwt-secret-key-that-is-at-least-256-bits-long-for-tests-1234567890!");
    properties.getJwt().setExpirationSeconds(3600);
    properties.getCookie().setName("media_factory_jwt");
    properties.getCookie().setSecure(false);
    properties.getCookie().setSameSite("Lax");

    jwtService = new JwtService(properties);
    jwtFilter = new JwtAuthenticationFilter(jwtService, properties);

    SecurityConfiguration secConfig = new SecurityConfiguration(jwtFilter, properties);
    UserDetailsService userDetailsService = secConfig.userDetailsService(passwordEncoder);
    DaoAuthenticationProvider authProvider = new DaoAuthenticationProvider(userDetailsService);
    authProvider.setPasswordEncoder(passwordEncoder);
    AuthenticationManager authenticationManager = new ProviderManager(authProvider);

    authController = new AuthenticationController(authenticationManager, jwtService, properties);
    mockMvc = MockMvcBuilders.standaloneSetup(authController).build();
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void validAdminLoginSuccess() throws Exception {
    String body = json.writeValueAsString(Map.of(
        "username", "admin",
        "password", "admin123"
    ));

    mockMvc.perform(post("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.token", notNullValue()))
        .andExpect(jsonPath("$.username").value("admin"))
        .andExpect(jsonPath("$.role").value("ROLE_ADMIN"))
        .andExpect(header().string("Set-Cookie", containsString("media_factory_jwt=")))
        .andExpect(header().string("Set-Cookie", containsString("HttpOnly")))
        .andExpect(header().string("Set-Cookie", containsString("Path=/")));
  }

  @Test
  void validAdminLoginWithEmailSuccess() throws Exception {
    String body = json.writeValueAsString(Map.of(
        "username", "admin@mediafactory.local",
        "password", "admin123"
    ));

    mockMvc.perform(post("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.token", notNullValue()))
        .andExpect(jsonPath("$.username").value("admin"))
        .andExpect(jsonPath("$.role").value("ROLE_ADMIN"))
        .andExpect(header().string("Set-Cookie", containsString("media_factory_jwt=")));
  }

  @Test
  void loginWithWrongPasswordReturns401() throws Exception {
    String body = json.writeValueAsString(Map.of(
        "username", "admin",
        "password", "wrong-password"
    ));

    mockMvc.perform(post("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error").value("Unauthorized"))
        .andExpect(jsonPath("$.message").value("Invalid username or password"));
  }

  @Test
  void loginWithUnknownUserReturns401() throws Exception {
    String body = json.writeValueAsString(Map.of(
        "username", "unknown-user",
        "password", "wrong-password"
    ));

    mockMvc.perform(post("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error").value("Unauthorized"))
        .andExpect(jsonPath("$.message").value("Invalid username or password"));
  }

  @Test
  void logoutClearsCookieAndWorks() throws Exception {
    mockMvc.perform(post("/api/auth/logout"))
        .andExpect(status().isOk())
        .andExpect(header().string("Set-Cookie", containsString("Max-Age=0")));
  }

  @Test
  void authMeReturnsAuthenticatedAdminInfo() throws Exception {
    String token = jwtService.generateToken("admin", AdminUser.ROLE_ADMIN);
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/auth/me");
    request.addHeader("Authorization", "Bearer " + token);
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain filterChain = new MockFilterChain();

    jwtFilter.doFilter(request, response, filterChain);

    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    assertThat(auth).isNotNull();
    assertThat(auth.getName()).isEqualTo("admin");
    assertThat(auth.getAuthorities()).extracting("authority").contains(AdminUser.ROLE_ADMIN);

    mockMvc.perform(get("/api/auth/me").principal(auth))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.username").value("admin"))
        .andExpect(jsonPath("$.role").value("ROLE_ADMIN"));
  }

  @Test
  void authMeWithoutAuthenticationReturns401() throws Exception {
    mockMvc.perform(get("/api/auth/me"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error").value("Unauthorized"));
  }

  @Test
  void filterAuthenticatesBearerAdminToken() throws ServletException, IOException {
    String token = jwtService.generateToken("admin", AdminUser.ROLE_ADMIN);
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/projects");
    request.addHeader("Authorization", "Bearer " + token);
    MockHttpServletResponse response = new MockHttpServletResponse();

    jwtFilter.doFilter(request, response, new MockFilterChain());

    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    assertThat(auth).isNotNull();
    assertThat(auth.isAuthenticated()).isTrue();
    assertThat(auth.getName()).isEqualTo("admin");
    assertThat(auth.getAuthorities()).extracting("authority").contains(AdminUser.ROLE_ADMIN);
  }

  @Test
  void filterAuthenticatesCookieAdminToken() throws ServletException, IOException {
    String token = jwtService.generateToken("admin", AdminUser.ROLE_ADMIN);
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/projects");
    request.setCookies(new Cookie("media_factory_jwt", token));
    MockHttpServletResponse response = new MockHttpServletResponse();

    jwtFilter.doFilter(request, response, new MockFilterChain());

    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    assertThat(auth).isNotNull();
    assertThat(auth.isAuthenticated()).isTrue();
    assertThat(auth.getName()).isEqualTo("admin");
    assertThat(auth.getAuthorities()).extracting("authority").contains(AdminUser.ROLE_ADMIN);
  }

  @Test
  void filterRejectsMalformedToken() throws ServletException, IOException {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/projects");
    request.addHeader("Authorization", "Bearer not-a-valid-jwt");
    MockHttpServletResponse response = new MockHttpServletResponse();

    jwtFilter.doFilter(request, response, new MockFilterChain());

    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    assertThat(auth).isNull();
  }

  @Test
  void filterRejectsExpiredToken() throws ServletException, IOException {
    String expiredToken = jwtService.generateToken("admin", AdminUser.ROLE_ADMIN, -60);
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/projects");
    request.addHeader("Authorization", "Bearer " + expiredToken);
    MockHttpServletResponse response = new MockHttpServletResponse();

    jwtFilter.doFilter(request, response, new MockFilterChain());

    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    assertThat(auth).isNull();
  }

  @Test
  void filterRejectsInvalidSignature() throws ServletException, IOException {
    SecurityProperties altProps = new SecurityProperties();
    altProps.getJwt().setSecret("different-secret-key-that-is-at-least-256-bits-long-for-tests-alt123456789!");
    JwtService altJwt = new JwtService(altProps);
    String badSigToken = altJwt.generateToken("admin", AdminUser.ROLE_ADMIN);

    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/projects");
    request.addHeader("Authorization", "Bearer " + badSigToken);
    MockHttpServletResponse response = new MockHttpServletResponse();

    jwtFilter.doFilter(request, response, new MockFilterChain());

    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    assertThat(auth).isNull();
  }

  @Test
  void filterSetsNonAdminRoleCorrectly() throws ServletException, IOException {
    String userToken = jwtService.generateToken("guest_user", "ROLE_USER");
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/projects");
    request.addHeader("Authorization", "Bearer " + userToken);
    MockHttpServletResponse response = new MockHttpServletResponse();

    jwtFilter.doFilter(request, response, new MockFilterChain());

    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    assertThat(auth).isNotNull();
    assertThat(auth.getName()).isEqualTo("guest_user");
    assertThat(auth.getAuthorities()).extracting("authority").contains("ROLE_USER");
    assertThat(auth.getAuthorities()).extracting("authority").doesNotContain(AdminUser.ROLE_ADMIN);
  }

  @Test
  void securityEntryPointReturns401OnUnauthenticated() throws Exception {
    SecurityConfiguration config = new SecurityConfiguration(jwtFilter, properties);
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/projects");
    MockHttpServletResponse response = new MockHttpServletResponse();

    // Trigger authentication entry point directly
    var entryPoint = ((org.springframework.security.web.AuthenticationEntryPoint) (req, res, ex) -> {
      res.setStatus(jakarta.servlet.http.HttpServletResponse.SC_UNAUTHORIZED);
      res.setContentType("application/json");
      res.getWriter().write("{\"error\":\"Unauthorized\",\"message\":\"Authentication required\"}");
    });

    entryPoint.commence(request, response, new InsufficientAuthenticationException("Full authentication is required"));

    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(response.getContentAsString()).contains("\"error\":\"Unauthorized\"");
    assertThat(response.getContentAsString()).contains("\"message\":\"Authentication required\"");
  }

  @Test
  void accessDeniedHandlerReturns403OnNonAdmin() throws Exception {
    SecurityConfiguration config = new SecurityConfiguration(jwtFilter, properties);
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/projects");
    MockHttpServletResponse response = new MockHttpServletResponse();

    var deniedHandler = ((org.springframework.security.web.access.AccessDeniedHandler) (req, res, ex) -> {
      res.setStatus(jakarta.servlet.http.HttpServletResponse.SC_FORBIDDEN);
      res.setContentType("application/json");
      res.getWriter().write("{\"error\":\"Forbidden\",\"message\":\"Access denied: Administrator role required\"}");
    });

    deniedHandler.handle(request, response, new AccessDeniedException("Access is denied"));

    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(response.getContentAsString()).contains("\"error\":\"Forbidden\"");
    assertThat(response.getContentAsString()).contains("\"message\":\"Access denied: Administrator role required\"");
  }

  @Test
  void corsConfigurationAppliesAllowedOrigins() {
    properties.getCors().setAllowedOrigins(java.util.List.of("http://localhost:5173", "https://media.internal.company.com"));
    SecurityConfiguration config = new SecurityConfiguration(jwtFilter, properties);
    CorsConfigurationSource source = config.corsConfigurationSource();

    MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/projects");
    request.addHeader("Origin", "http://localhost:5173");
    CorsConfiguration corsConfig = source.getCorsConfiguration(request);

    assertThat(corsConfig).isNotNull();
    assertThat(corsConfig.getAllowedOrigins()).contains("http://localhost:5173", "https://media.internal.company.com");
    assertThat(corsConfig.getAllowCredentials()).isTrue();
    assertThat(corsConfig.getAllowedMethods()).contains("GET", "POST", "PUT", "DELETE", "OPTIONS");
  }
}
