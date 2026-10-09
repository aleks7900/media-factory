package com.mediafactory.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

  private final JwtService jwtService;
  private final SecurityProperties properties;

  public JwtAuthenticationFilter(JwtService jwtService, SecurityProperties properties) {
    this.jwtService = jwtService;
    this.properties = properties;
  }

  @Override
  protected boolean shouldNotFilterAsyncDispatch() {
    return true;
  }

  @Override
  protected boolean shouldNotFilterErrorDispatch() {
    return true;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request,
      HttpServletResponse response,
      FilterChain filterChain
  ) throws ServletException, IOException {

    String token = resolveToken(request);

    if (token != null && !token.isBlank()) {
      JwtService.JwtValidationResult validation = jwtService.validateToken(token);
      if (validation.valid()) {
        List<SimpleGrantedAuthority> authorities = validation.roles().stream()
            .map(role -> role.startsWith("ROLE_") ? role : "ROLE_" + role)
            .map(SimpleGrantedAuthority::new)
            .toList();

        UsernamePasswordAuthenticationToken authentication =
            new UsernamePasswordAuthenticationToken(validation.username(), null, authorities);
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

        SecurityContextHolder.getContext().setAuthentication(authentication);
      }
    }

    filterChain.doFilter(request, response);
  }

  private String resolveToken(HttpServletRequest request) {
    String authHeader = request.getHeader("Authorization");
    if (authHeader != null && authHeader.regionMatches(true, 0, "Bearer ", 0, 7)) {
      return authHeader.substring(7).trim();
    }

    Cookie[] cookies = request.getCookies();
    if (cookies != null) {
      String cookieName = properties.getCookie().getName();
      String cookieToken = Arrays.stream(cookies)
          .filter(c -> cookieName.equals(c.getName()))
          .map(Cookie::getValue)
          .filter(v -> v != null && !v.isBlank())
          .findFirst()
          .orElse(null);
      if (cookieToken != null) {
        return cookieToken;
      }
    }

    String paramToken = request.getParameter("token");
    if (paramToken != null && !paramToken.isBlank()) {
      return paramToken.trim();
    }

    return null;
  }
}
