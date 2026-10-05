package com.mediafactory.provider.gemini;

import com.mediafactory.provider.ImageGenerationProvider;
import com.mediafactory.provider.ImageOptions.AspectRatio;
import com.mediafactory.provider.ImageOptions.Format;
import com.mediafactory.provider.ImageOptions.Quality;
import com.mediafactory.provider.ProviderCapabilities;
import com.mediafactory.provider.ProviderTypes.Media;
import com.mediafactory.provider.ProviderTypes.Request;
import com.mediafactory.provider.ProviderTypes.Result;
import com.mediafactory.provider.ProviderTypes.Usage;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class GeminiImageGenerationProvider implements ImageGenerationProvider {

  private final GeminiImageClient client;
  private final GeminiImageProperties secrets;

  public GeminiImageGenerationProvider(GeminiImageClient client, GeminiImageProperties secrets) {
    this.client = client;
    this.secrets = secrets;
  }

  @Override
  public String providerId() {
    return "gemini";
  }

  @Override
  public boolean configured() {
    return secrets.apiKey() != null && !secrets.apiKey().isBlank();
  }

  @Override
  public ProviderCapabilities capabilities() {
    return new ProviderCapabilities(
        Set.of(AspectRatio.values()),
        Set.of(Format.values()),
        Set.of(Quality.values()),
        Set.of(
            "1024x1024",
            "1536x1024",
            "1024x1536",
            "1920x1080",
            "1080x1920",
            "1408x1056",
            "1056x1408",
            "1280x720",
            "720x1280",
            "2048x2048",
            "4096x4096"),
        true,  // arbitraryDimensions
        false, // supportsNegativePrompt
        false, // supportsSeed
        true,  // supportsReferenceImage (multimodal)
        false, // supportsTransparentBackground
        1      // maximumImages
    );
  }

  @Override
  public Usage estimate(Request r) {
    return new Usage(providerId(), r.options().model(), "IMAGE_GENERATION", 0, 0, null, "USD");
  }

  @Override
  public Result<Media> generate(Request r) {
    capabilities().validate(r);
    String json = GeminiImageMapper.request(r);
    var response = client.generate(json, r.options().model(), r.operationId());
    return GeminiImageMapper.response(response, r, secrets);
  }
}
