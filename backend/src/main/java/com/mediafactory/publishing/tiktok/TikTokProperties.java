package com.mediafactory.publishing.tiktok;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "tiktok")
public class TikTokProperties {

  private boolean enabled = false;
  private String clientKey = "";
  private String clientSecret = "";
  private String redirectUri = "http://localhost:3000/publishing/tiktok/callback";
  private String apiBaseUrl = "https://open.tiktokapis.com";
  private String authUrl = "https://www.tiktok.com/v2/auth/authorize/";
  private String mockScenario = "SUCCESS";
  private int rpmLimit = 6;
  private int maxAttempts = 5;

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public String getClientKey() {
    return clientKey;
  }

  public void setClientKey(String clientKey) {
    this.clientKey = clientKey;
  }

  public String getClientSecret() {
    return clientSecret;
  }

  public void setClientSecret(String clientSecret) {
    this.clientSecret = clientSecret;
  }

  public String getRedirectUri() {
    return redirectUri;
  }

  public void setRedirectUri(String redirectUri) {
    this.redirectUri = redirectUri;
  }

  public String getApiBaseUrl() {
    return apiBaseUrl;
  }

  public void setApiBaseUrl(String apiBaseUrl) {
    this.apiBaseUrl = apiBaseUrl;
  }

  public String getAuthUrl() {
    return authUrl;
  }

  public void setAuthUrl(String authUrl) {
    this.authUrl = authUrl;
  }

  public String getMockScenario() {
    return mockScenario;
  }

  public void setMockScenario(String mockScenario) {
    this.mockScenario = mockScenario;
  }

  public int getRpmLimit() {
    return rpmLimit;
  }

  public void setRpmLimit(int rpmLimit) {
    this.rpmLimit = rpmLimit;
  }

  public int getMaxAttempts() {
    return maxAttempts;
  }

  public void setMaxAttempts(int maxAttempts) {
    this.maxAttempts = maxAttempts;
  }

  @Override
  public String toString() {
    return "TikTokProperties{"
        + "enabled=" + enabled
        + ", clientKey='" + (clientKey.isBlank() ? "NOT_SET" : clientKey) + '\''
        + ", clientSecret='[REDACTED]'"
        + ", redirectUri='" + redirectUri + '\''
        + ", apiBaseUrl='" + apiBaseUrl + '\''
        + ", mockScenario='" + mockScenario + '\''
        + ", rpmLimit=" + rpmLimit
        + '}';
  }
}
