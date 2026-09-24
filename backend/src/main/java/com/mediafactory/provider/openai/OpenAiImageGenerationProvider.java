package com.mediafactory.provider.openai;

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
public class OpenAiImageGenerationProvider implements ImageGenerationProvider {

  private final OpenAiImageClient client;
  private final OpenAiImageProperties secrets;

  public OpenAiImageGenerationProvider(OpenAiImageClient client, OpenAiImageProperties secrets) {
    this.client = client;
    this.secrets = secrets;
  }

  public String providerId() {
    return "openai";
  }

  public boolean configured() {
    return secrets.apiKey() != null && !secrets.apiKey().isBlank();
  }

  public ProviderCapabilities capabilities() {
    return new ProviderCapabilities(Set.of(AspectRatio.values()), Set.of(Format.values()),
        Set.of(Quality.values()), Set.of("1024x1024", "1024x1536", "1536x1024"), false, false,
        false, false, true, 1);
  }

  public Usage estimate(Request r) {
    return new Usage(providerId(), r.options().model(), "IMAGE_GENERATION", 0, 0, null, "USD");
  }

  public Result<Media> generate(Request r) {
    capabilities().validate(r);
    return OpenAiImageMapper.response(
        client.generate(OpenAiImageMapper.request(r), r.operationId()), r, secrets);
  }
}
