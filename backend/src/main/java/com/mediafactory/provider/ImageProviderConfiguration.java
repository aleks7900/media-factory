package com.mediafactory.provider;

import com.mediafactory.provider.openai.OpenAiImageProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({ImageGenerationProperties.class, OpenAiImageProperties.class})
public class ImageProviderConfiguration {

  public ImageProviderConfiguration(ImageGenerationProperties properties) {
    for (var p : properties.providers().values()) {
      if (p.timeout().connect().isNegative() || p.timeout().connect().isZero() || p.timeout()
          .request().isNegative() || p.timeout().request().isZero()
          || p.timeout().request().plusSeconds(90).compareTo(properties.leaseDuration()) >= 0) {
        throw new IllegalArgumentException(
            "Provider timeouts must be positive and leave 90 seconds within the worker lease for persistence");
      }
      if (p.retry().initialDelay().isNegative()
          || p.retry().maxDelay().compareTo(p.retry().initialDelay()) < 0 || p.circuit().cooldown()
          .isNegative()) {
        throw new IllegalArgumentException("Invalid provider retry or circuit timing");
      }
    }
  }
}
