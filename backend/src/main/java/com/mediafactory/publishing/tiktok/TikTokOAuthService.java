package com.mediafactory.publishing.tiktok;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class TikTokOAuthService {

  private static final Logger log = LoggerFactory.getLogger(TikTokOAuthService.class);
  private static final SecureRandom SECURE_RANDOM = new SecureRandom();
  private static final long STATE_TTL_SECONDS = 600; // 10 minutes
  private static final int MAX_ATTEMPTS_PER_WINDOW = 5;
  private static final long RATE_LIMIT_WINDOW_SECONDS = 60;

  private final TikTokProperties properties;
  private final TikTokClient tikTokClient;

  // In-memory thread-safe registry of active OAuth attempts
  private final Map<String, OAuthAttempt> attemptsByState = new ConcurrentHashMap<>();
  private final Map<UUID, OAuthAttempt> attemptsById = new ConcurrentHashMap<>();

  // Set of SHA-256 hashes of exchanged authorization codes (to prevent code reuse)
  private final Set<String> exchangedCodeHashes = ConcurrentHashMap.newKeySet();

  // Rate limiting timestamps for OAuth initiation
  private final List<Instant> initiationTimestamps = Collections.synchronizedList(new ArrayList<>());

  public TikTokOAuthService(TikTokProperties properties, TikTokClient tikTokClient) {
    this.properties = properties;
    this.tikTokClient = tikTokClient;
  }

  public enum AttemptStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
    FAILED,
    EXPIRED
  }

  public record OAuthAttempt(
      UUID attemptId,
      String stateToken,
      String stateHash,
      String redirectUri,
      Instant createdAt,
      Instant expiresAt,
      AttemptStatus status,
      String failureReason
  ) {
    public boolean isExpired() {
      return Instant.now().isAfter(expiresAt);
    }
  }

  public record InitiationResult(
      UUID attemptId,
      String authorizationUrl,
      String state,
      Instant expiresAt
  ) {}

  /**
   * Generates a cryptographically secure OAuth state and authorization URL.
   * Enforces rate limiting against rapid-fire clicks.
   */
  public synchronized InitiationResult initiateAuthorization(String clientProvidedState) {
    cleanExpiredAttempts();

    // 1. Enforce rate limiting: max 5 initiations per 60 seconds
    Instant now = Instant.now();
    initiationTimestamps.removeIf(ts -> ts.isBefore(now.minusSeconds(RATE_LIMIT_WINDOW_SECONDS)));
    if (initiationTimestamps.size() >= MAX_ATTEMPTS_PER_WINDOW) {
      log.warn("TikTok OAuth initiation rate limit reached: {} attempts in past {}s",
          initiationTimestamps.size(), RATE_LIMIT_WINDOW_SECONDS);
      throw new TikTokApiException(429, "rate_limit_exceeded",
          "Too many TikTok authorization attempts. Please wait 60 seconds before trying again.", true, 60000L);
    }
    initiationTimestamps.add(now);

    // 2. Generate secure random state token (32 bytes Base64URL)
    String stateToken;
    if (clientProvidedState != null && !clientProvidedState.isBlank()) {
      stateToken = clientProvidedState.trim();
    } else {
      byte[] randomBytes = new byte[32];
      SECURE_RANDOM.nextBytes(randomBytes);
      stateToken = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }

    UUID attemptId = UUID.randomUUID();
    String stateHash = sha256Hex(stateToken);
    String redirectUri = properties.getRedirectUri();
    Instant expiresAt = now.plusSeconds(STATE_TTL_SECONDS);

    OAuthAttempt attempt = new OAuthAttempt(
        attemptId,
        stateToken,
        stateHash,
        redirectUri,
        now,
        expiresAt,
        AttemptStatus.PENDING,
        null
    );

    attemptsByState.put(stateToken, attempt);
    attemptsById.put(attemptId, attempt);

    // 3. Construct URL
    String authUrl = tikTokClient.getAuthorizationUrl(stateToken);

    // Safe diagnostic log: state is logged only as SHA-256 hash
    log.info("TikTok OAuth attempt created: attemptId={}, redirectUri={}, stateHash={}, expiresAt={}",
        attemptId, redirectUri, stateHash, expiresAt);

    return new InitiationResult(attemptId, authUrl, stateToken, expiresAt);
  }

  /**
   * Validates state, guarantees single-use, verifies the code was not previously exchanged,
   * and transitions attempt to IN_PROGRESS.
   */
  public synchronized OAuthAttempt validateAndConsumeState(String state, String code) {
    if (state == null || state.isBlank()) {
      throw new TikTokApiException(400, "invalid_state", "Missing OAuth state parameter", false, null);
    }
    if (code == null || code.isBlank()) {
      throw new TikTokApiException(400, "invalid_code", "Missing authorization code", false, null);
    }

    String codeHash = sha256Hex(code);
    if (exchangedCodeHashes.contains(codeHash)) {
      log.warn("Rejecting duplicate code exchange attempt for codeHash={}", codeHash);
      throw new TikTokApiException(409, "code_already_used",
          "This TikTok authorization code has already been exchanged and cannot be reused.", false, null);
    }

    OAuthAttempt attempt = attemptsByState.get(state.trim());
    if (attempt == null) {
      log.warn("Rejecting callback with unknown/forged state: stateHash={}", sha256Hex(state));
      throw new TikTokApiException(400, "invalid_state",
          "Invalid or unrecognized OAuth state parameter. Please restart authorization.", false, null);
    }

    if (attempt.isExpired()) {
      attemptsByState.remove(state);
      attemptsById.remove(attempt.attemptId());
      log.warn("Rejecting callback with expired state: attemptId={}", attempt.attemptId());
      throw new TikTokApiException(400, "state_expired",
          "OAuth authorization attempt expired. Please restart authorization.", false, null);
    }

    if (attempt.status() == AttemptStatus.COMPLETED) {
      log.warn("Rejecting callback with already completed state: attemptId={}", attempt.attemptId());
      throw new TikTokApiException(409, "state_already_completed",
          "This authorization session has already been completed.", false, null);
    }

    if (attempt.status() == AttemptStatus.IN_PROGRESS) {
      log.warn("Concurrent callback exchange in progress for attemptId={}", attempt.attemptId());
      throw new TikTokApiException(409, "exchange_in_progress",
          "Token exchange is already in progress for this authorization attempt.", false, null);
    }

    // Mark attempt as IN_PROGRESS
    OAuthAttempt inProgress = new OAuthAttempt(
        attempt.attemptId(),
        attempt.stateToken(),
        attempt.stateHash(),
        attempt.redirectUri(),
        attempt.createdAt(),
        attempt.expiresAt(),
        AttemptStatus.IN_PROGRESS,
        null
    );

    attemptsByState.put(state.trim(), inProgress);
    attemptsById.put(attempt.attemptId(), inProgress);

    // Track exchanged code hash
    exchangedCodeHashes.add(codeHash);

    log.info("TikTok token exchange started: attemptId={}", attempt.attemptId());
    return inProgress;
  }

  /**
   * Completes the attempt lifecycle.
   */
  public synchronized void recordAttemptCompletion(UUID attemptId, boolean success, String failureReason) {
    OAuthAttempt existing = attemptsById.get(attemptId);
    if (existing != null) {
      OAuthAttempt finished = new OAuthAttempt(
          existing.attemptId(),
          existing.stateToken(),
          existing.stateHash(),
          existing.redirectUri(),
          existing.createdAt(),
          existing.expiresAt(),
          success ? AttemptStatus.COMPLETED : AttemptStatus.FAILED,
          failureReason
      );

      attemptsById.put(attemptId, finished);
      attemptsByState.put(existing.stateToken(), finished);
      if (success) {
        log.info("TikTok token exchange completed: attemptId={}", attemptId);
      } else {
        log.warn("TikTok token exchange failed: attemptId={}, reason={}", attemptId, failureReason);
      }
    }
  }

  public Optional<OAuthAttempt> getAttempt(UUID attemptId) {
    return Optional.ofNullable(attemptsById.get(attemptId));
  }

  private void cleanExpiredAttempts() {
    Instant now = Instant.now();
    attemptsByState.entrySet().removeIf(entry -> entry.getValue().isExpired());
    attemptsById.entrySet().removeIf(entry -> entry.getValue().isExpired());
  }

  private static String sha256Hex(String input) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder();
      for (byte b : digest) {
        hex.append(String.format("%02x", b));
      }
      return hex.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new RuntimeException("SHA-256 not available", e);
    }
  }
}
