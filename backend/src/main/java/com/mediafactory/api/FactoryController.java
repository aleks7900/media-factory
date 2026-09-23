package com.mediafactory.api;
import com.mediafactory.service.FactoryService;
import com.mediafactory.storage.MediaStorage;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import java.util.*;

@RestController
@RequestMapping("/api")
public class FactoryController {
 private final FactoryService service; private final MediaStorage storage;private final com.mediafactory.service.ProviderInfoService providers;
 public FactoryController(FactoryService service,MediaStorage storage,com.mediafactory.service.ProviderInfoService providers) { this.service=service;this.storage=storage;this.providers=providers; }
 public record ProjectRequest(@NotBlank @Size(max=200) String name,@Size(max=10000) String description) {}
 public record CollectionRequest(@NotNull UUID projectId,@NotBlank @Size(max=200) String name) {}
 public record ConceptRequest(@NotNull UUID collectionId,@NotBlank @Size(max=200) String name,@NotBlank @Size(max=10000) String prompt) {}
 public record GenerationRequest(@NotNull UUID conceptId,@NotBlank @Size(max=10000) String prompt,@Min(64) @Max(4096) int width,@Min(64) @Max(4096) int height) {}
 public record ReviewRequest(@NotNull UUID assetId,@NotNull @Pattern(regexp="APPROVED|REJECTED") String decision,@Size(max=2000) String reason) {}
 @GetMapping("/projects") public Object projects() { return service.list("projects"); }
 @GetMapping("/projects/{id}") public Object project(@PathVariable UUID id) { return service.one("projects",id); }
 @PostMapping("/projects") @ResponseStatus(HttpStatus.CREATED) public Object project(@Valid @RequestBody ProjectRequest r) { return service.project(r.name(),Objects.toString(r.description(),"")); }
 @GetMapping("/collections") public Object collections() { return service.list("collections"); }
 @GetMapping("/collections/{id}") public Object collection(@PathVariable UUID id) { return service.one("collections",id); }
 @PostMapping("/collections") @ResponseStatus(HttpStatus.CREATED) public Object collection(@Valid @RequestBody CollectionRequest r) { return service.collection(r.projectId(),r.name()); }
 @GetMapping("/concepts") public Object concepts() { return service.list("concepts"); }
 @GetMapping("/concepts/{id}") public Object concept(@PathVariable UUID id) { return service.one("concepts",id); }
 @PostMapping("/concepts") @ResponseStatus(HttpStatus.CREATED) public Object concept(@Valid @RequestBody ConceptRequest r) { return service.concept(r.collectionId(),r.name(),r.prompt()); }
 @GetMapping("/generations") public Object generations() { return service.list("generations"); }
 @GetMapping("/generations/{id}") public Object generation(@PathVariable UUID id) { return service.one("generations",id); }
 @PostMapping("/generations") @ResponseStatus(HttpStatus.ACCEPTED) public Object generation(@RequestHeader("Idempotency-Key") @NotBlank @Size(max=200) String key,@Valid @RequestBody GenerationRequest r) { return service.generate(r.conceptId(),r.prompt(),r.width(),r.height(),key,null); }
 @GetMapping("/assets") public Object assets() { return service.list("assets"); }
 @GetMapping("/assets/{id}") public Object asset(@PathVariable UUID id) { return service.one("assets",id); }
 @GetMapping("/assets/{id}/content") public ResponseEntity<byte[]> content(@PathVariable UUID id) {
   var a=service.one("assets",id);
   return ResponseEntity.ok().contentType(MediaType.parseMediaType((String)a.get("media_type"))).header("X-Content-Type-Options","nosniff")
     .cacheControl(CacheControl.maxAge(java.time.Duration.ofDays(365)).cachePrivate().immutable()).body(storage.read((String)a.get("storage_key")));
 }
 @PostMapping("/assets/{id}/regenerate") @ResponseStatus(HttpStatus.ACCEPTED) public Object regenerate(@PathVariable UUID id,@RequestHeader("Idempotency-Key") @NotBlank @Size(max=200) String key) { return service.regenerate(id,key); }
 @GetMapping("/reviews") public Object reviews() { return service.list("quality_reviews"); }
 @GetMapping("/reviews/{id}") public Object review(@PathVariable UUID id) { return service.one("quality_reviews",id); }
 @PostMapping("/reviews") @ResponseStatus(HttpStatus.CREATED) public Object review(@Valid @RequestBody ReviewRequest r) { return service.review(r.assetId(),r.decision(),Objects.toString(r.reason(),"")); }
 @GetMapping("/jobs") public Object jobs() { return service.list("jobs"); }
 @GetMapping("/jobs/{id}") public Object job(@PathVariable UUID id) { return service.one("jobs",id); }
 public record RetryRequest(boolean acknowledgeDuplicateRisk) {}
 @PostMapping("/jobs/{id}/retry") public Object retry(@PathVariable UUID id,@RequestBody(required=false) RetryRequest request) { return service.retry(id,request!=null&&request.acknowledgeDuplicateRisk()); }
 @GetMapping("/dashboard") public Object dashboard() { return service.dashboard(); }
 @GetMapping("/costs") public Object costs() { return service.list("generation_costs"); }
 @GetMapping("/providers") public Object providers() { return providers.list(); }
}
