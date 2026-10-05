package com.mediafactory.publishing.tiktok;

import static org.assertj.core.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TikTokOAuthServiceTest {

  private TikTokProperties properties;
  private TikTokClient client;
  private TikTokOAuthService oauthService;

  @BeforeEach
  void setUp() {
    properties = new TikTokProperties();
    properties.setEnabled(true);
    properties.setClientKey("test_client_key");
    properties.setClientSecret("test_client_secret");
    properties.setRedirectUri("http://localhost:3000/publishing/tiktok/callback");
    properties.setAuthUrl("https://www.tiktok.com/v2/auth/authorize/");

    client = new TikTokClient(properties);
    oauthService = new TikTokOAuthService(properties, client);
  }

  @Test
  void singleAuthorizationInitiationCreatesUniqueStateAndAttempt() {
    var result = oauthService.initiateAuthorization(null);

    assertThat(result.attemptId()).isNotNull();
    assertThat(result.state()).isNotBlank();
    assertThat(result.authorizationUrl()).contains("client_key=test_client_key");
    assertThat(result.authorizationUrl()).contains("state=" + result.state());
    assertThat(result.authorizationUrl()).contains("redirect_uri=http%3A%2F%2Flocalhost%3A3000%2Fpublishing%2Ftiktok%2Fcallback");
    assertThat(result.expiresAt()).isNotNull();

    var attemptOpt = oauthService.getAttempt(result.attemptId());
    assertThat(attemptOpt).isPresent();
    assertThat(attemptOpt.get().status()).isEqualTo(TikTokOAuthService.AttemptStatus.PENDING);
    assertThat(attemptOpt.get().stateHash()).isNotBlank();
  }

  @Test
  void doubleClickOrRapidInitiationsExceedingWindowTriggerRateLimit() {
    // 5 attempts within 60s are allowed
    for (int i = 0; i < 5; i++) {
      oauthService.initiateAuthorization(null);
    }

    // 6th attempt must trigger rate limit
    assertThatThrownBy(() -> oauthService.initiateAuthorization(null))
        .isInstanceOf(TikTokApiException.class)
        .satisfies(e -> {
          TikTokApiException te = (TikTokApiException) e;
          assertThat(te.getStatusCode()).isEqualTo(429);
          assertThat(te.getErrorCode()).isEqualTo("rate_limit_exceeded");
          assertThat(te.getMessage()).contains("Too many TikTok authorization attempts");
        });
  }

  @Test
  void callbackExchangesCodeExactlyOnce() {
    var init = oauthService.initiateAuthorization(null);

    var consumed = oauthService.validateAndConsumeState(init.state(), "code_abc_123");
    assertThat(consumed.status()).isEqualTo(TikTokOAuthService.AttemptStatus.IN_PROGRESS);

    oauthService.recordAttemptCompletion(consumed.attemptId(), true, null);

    var finalAttempt = oauthService.getAttempt(consumed.attemptId());
    assertThat(finalAttempt).isPresent();
    assertThat(finalAttempt.get().status()).isEqualTo(TikTokOAuthService.AttemptStatus.COMPLETED);
  }

  @Test
  void duplicateCodeExchangeIsRejectedImmediately() {
    var init1 = oauthService.initiateAuthorization(null);
    oauthService.validateAndConsumeState(init1.state(), "same_reused_code");

    var init2 = oauthService.initiateAuthorization(null);

    // Attempting to exchange the exact same authorization code again must fail
    assertThatThrownBy(() -> oauthService.validateAndConsumeState(init2.state(), "same_reused_code"))
        .isInstanceOf(TikTokApiException.class)
        .satisfies(e -> {
          TikTokApiException te = (TikTokApiException) e;
          assertThat(te.getStatusCode()).isEqualTo(409);
          assertThat(te.getErrorCode()).isEqualTo("code_already_used");
          assertThat(te.getMessage()).contains("already been exchanged");
        });
  }

  @Test
  void duplicateCallbackWithSameStateIsRejectedSafely() {
    var init = oauthService.initiateAuthorization(null);

    // First consumption
    oauthService.validateAndConsumeState(init.state(), "code_1");

    // Second consumption with same state while in progress
    assertThatThrownBy(() -> oauthService.validateAndConsumeState(init.state(), "code_2"))
        .isInstanceOf(TikTokApiException.class)
        .satisfies(e -> {
          TikTokApiException te = (TikTokApiException) e;
          assertThat(te.getStatusCode()).isEqualTo(409);
          assertThat(te.getErrorCode()).isEqualTo("exchange_in_progress");
        });

    // Complete first attempt
    oauthService.recordAttemptCompletion(init.attemptId(), true, null);

    // Third consumption after completion
    assertThatThrownBy(() -> oauthService.validateAndConsumeState(init.state(), "code_3"))
        .isInstanceOf(TikTokApiException.class)
        .satisfies(e -> {
          TikTokApiException te = (TikTokApiException) e;
          assertThat(te.getStatusCode()).isEqualTo(409);
          assertThat(te.getErrorCode()).isEqualTo("state_already_completed");
        });
  }

  @Test
  void invalidOrForgedStateIsRejected() {
    assertThatThrownBy(() -> oauthService.validateAndConsumeState("random_forged_state", "code_1"))
        .isInstanceOf(TikTokApiException.class)
        .satisfies(e -> {
          TikTokApiException te = (TikTokApiException) e;
          assertThat(te.getStatusCode()).isEqualTo(400);
          assertThat(te.getErrorCode()).isEqualTo("invalid_state");
          assertThat(te.getMessage()).contains("Invalid or unrecognized OAuth state");
        });
  }

  @Test
  void missingStateOrCodeIsRejected() {
    assertThatThrownBy(() -> oauthService.validateAndConsumeState(null, "code_1"))
        .isInstanceOf(TikTokApiException.class)
        .hasMessageContaining("Missing OAuth state");

    assertThatThrownBy(() -> oauthService.validateAndConsumeState("state_1", ""))
        .isInstanceOf(TikTokApiException.class)
        .hasMessageContaining("Missing authorization code");
  }

  @Test
  void tokenExchangeFailureRecordsFailureWithoutRetryingCode() {
    var init = oauthService.initiateAuthorization(null);
    var consumed = oauthService.validateAndConsumeState(init.state(), "code_fail");

    oauthService.recordAttemptCompletion(consumed.attemptId(), false, "TikTok token error: invalid_grant");

    var attempt = oauthService.getAttempt(consumed.attemptId());
    assertThat(attempt).isPresent();
    assertThat(attempt.get().status()).isEqualTo(TikTokOAuthService.AttemptStatus.FAILED);
    assertThat(attempt.get().failureReason()).contains("invalid_grant");
  }
}
