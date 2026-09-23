package com.mediafactory.api;
import com.mediafactory.provider.*;
import com.mediafactory.provider.ImageOptions.*;
import com.mediafactory.service.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@RestController
@RequestMapping("/api/v1")
public class ImageGenerationController {
 private final FactoryService service;private final ProviderInfoService providers;
 public ImageGenerationController(FactoryService service,ProviderInfoService providers) {this.service=service;this.providers=providers;}
 public record CreateImageRequest(@NotNull UUID conceptId,@NotBlank @Size(max=10000) String prompt,
  @Size(max=80) String provider,@Size(max=100) String model,AspectRatio aspectRatio,Quality quality,Format format,
  @Min(64) @Max(4096) Integer width,@Min(64) @Max(4096) Integer height,
  @Size(max=10000) String negativePrompt,Long seed,@Size(max=1000) String referenceImage,
  Boolean transparentBackground,@Min(1) @Max(1) Integer numberOfImages) {
  public ImageOptions options() {return new ImageOptions(provider,model,aspectRatio==null?AspectRatio.SQUARE:aspectRatio,quality,format,negativePrompt,seed,referenceImage,Boolean.TRUE.equals(transparentBackground),numberOfImages==null?1:numberOfImages);}
  public int resolvedWidth() {return width==null?(aspectRatio==AspectRatio.LANDSCAPE?1536:1024):width;}
  public int resolvedHeight() {return height==null?(aspectRatio==AspectRatio.PORTRAIT?1536:1024):height;}
 }
 @PostMapping("/generations/images") @ResponseStatus(HttpStatus.ACCEPTED)
 public Object create(@Valid @RequestBody CreateImageRequest request,@RequestHeader("Idempotency-Key") @NotBlank @Size(max=200) String key) {
  if((request.width()==null)!=(request.height()==null)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Both width and height must be supplied together");
  if(request.aspectRatio()==AspectRatio.CUSTOM && request.width()==null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Custom aspect ratio requires width and height");
  var generation=service.generateImage(request.conceptId(),request.prompt(),request.resolvedWidth(),request.resolvedHeight(),key,null,request.options());
  var detail=service.details((UUID)generation.get("id"));var job=(Map<?,?>)detail.get("job");
  return Map.of("generationId",generation.get("id"),"jobId",job.get("id"),"status",generation.get("status"));
 }
 @GetMapping("/generations/{id}") public Object details(@PathVariable UUID id) {return service.details(id);}
 @GetMapping("/providers/image") public Object providers() {return providers.list();}
}
