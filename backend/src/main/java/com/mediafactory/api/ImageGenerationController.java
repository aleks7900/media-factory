package com.mediafactory.api;

import com.mediafactory.prompt.PromptModels.PromptRenderRequest;
import com.mediafactory.provider.ImageOptions;
import com.mediafactory.provider.ImageOptions.AspectRatio;
import com.mediafactory.provider.ImageOptions.Format;
import com.mediafactory.provider.ImageOptions.Quality;
import com.mediafactory.service.FactoryService;
import com.mediafactory.service.ProviderInfoService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1")
public class ImageGenerationController {

  private final FactoryService service;
  private final ProviderInfoService providers;

  public ImageGenerationController(FactoryService service, ProviderInfoService providers) {
    this.service = service;
    this.providers = providers;
  }

  @PostMapping("/generations/images")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public Object create(@Valid @RequestBody CreateImageRequest request,
      @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 200) String key) {
    if ((request.width() == null) != (request.height() == null)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "Both width and height must be supplied together");
    }
    if (request.aspectRatio() == AspectRatio.CUSTOM && request.width() == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "Custom aspect ratio requires width and height");
    }
    var input = new PromptRenderRequest(request.promptVersionId(), request.variables(),
        request.presets(), null, request.experimentId(), null, null, request.pipeline(),
        request.manualPositiveSuffix(), request.manualNegativeSuffix(), request.prompt(),
        request.negativePrompt());
    var generation = service.generatePrompt(request.conceptId(), request.resolvedWidth(),
        request.resolvedHeight(), key, null, request.options(), input);
    var detail = service.details((UUID) generation.get("id"));
    var job = (Map<?, ?>) detail.get("job");
    return Map.of("generationId", generation.get("id"), "jobId", job.get("id"), "status",
        generation.get("status"));
  }

  @GetMapping("/generations/{id}")
  public Object details(@PathVariable UUID id) {
    return service.details(id);
  }

  @GetMapping("/providers/image")
  public Object providers() {
    return providers.list();
  }

  public record CreateImageRequest(@NotNull UUID conceptId, @Size(max = 10000) String prompt,
                                   @Size(max = 80) String provider, @Size(max = 100) String model,
                                   AspectRatio aspectRatio, Quality quality, Format format,
                                   @Min(64) @Max(4096) Integer width,
                                   @Min(64) @Max(4096) Integer height,
                                   @Size(max = 10000) String negativePrompt, Long seed,
                                   @Size(max = 1000) String referenceImage,
                                   Boolean transparentBackground,
                                   @Min(1) @Max(1) Integer numberOfImages,
                                   UUID promptVersionId, Map<String, Object> variables,
                                   List<String> presets,
                                   UUID experimentId, String pipeline,
                                   @Size(max = 3000) String manualPositiveSuffix,
                                   @Size(max = 3000) String manualNegativeSuffix) {

    public ImageOptions options() {
      return new ImageOptions(provider, model,
          aspectRatio == null ? AspectRatio.SQUARE : aspectRatio, quality, format, negativePrompt,
          seed, referenceImage, Boolean.TRUE.equals(transparentBackground),
          numberOfImages == null ? 1 : numberOfImages);
    }

    public int resolvedWidth() {
      return width == null ? (aspectRatio == AspectRatio.LANDSCAPE ? 1536 : 1024) : width;
    }

    public int resolvedHeight() {
      return height == null ? (aspectRatio == AspectRatio.PORTRAIT ? 1536 : 1024) : height;
    }
  }
}
