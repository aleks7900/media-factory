package com.mediafactory.provider.openai;

import com.mediafactory.provider.ImageGenerationProperties;
import com.mediafactory.provider.resilience.ImageGenerationException;
import com.mediafactory.provider.resilience.ImageGenerationException.Type;
import java.io.ByteArrayOutputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Component;

/**
 * One HTTP exchange, no hidden client retries, redirects, response logging, or URL downloads.
 */
@Component
public class OpenAiImageClient implements AutoCloseable {

  private final OpenAiImageProperties secrets;
  private final ImageGenerationProperties properties;
  private final HttpClient client;

  public OpenAiImageClient(OpenAiImageProperties secrets, ImageGenerationProperties properties) {
    this.secrets = secrets;
    this.properties = properties;
    client = HttpClient.newBuilder()
        .connectTimeout(properties.provider("openai").timeout().connect())
        .followRedirects(HttpClient.Redirect.NEVER).build();
  }

  private static ImageGenerationException timeout() {
    return new ImageGenerationException(Type.TIMEOUT, "Image provider request timed out",
        Duration.ZERO, true, null);
  }

  public Response generate(String json, String operationId) {
    if (secrets.apiKey().isBlank()) {
      throw new ImageGenerationException(Type.AUTHENTICATION,
          "Image provider credentials are not configured");
    }
    Duration timeout = properties.provider("openai").timeout().request();
    var request = HttpRequest.newBuilder(secrets.endpoint()).timeout(timeout)
        .header("Authorization", "Bearer " + secrets.apiKey())
        .header("Content-Type", "application/json").header("X-Client-Request-Id", operationId)
        .POST(HttpRequest.BodyPublishers.ofString(json)).build();
    var future = client.sendAsync(request, info -> new BoundedBody(36 * 1024 * 1024));
    try {
      var response = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
      String id = secrets.redact(response.headers().firstValue("x-request-id").orElse(null));
      if (id != null && (id.length() > 200 || !id.matches("[a-zA-Z0-9_\\-\\[\\]]+"))) {
        id = null;
      }
      if (response.statusCode() != 200) {
        throw OpenAiImageExceptionMapper.map(response.statusCode(), response.body(),
            response.headers().firstValue("retry-after").orElse(null), id);
      }
      var headers = new HashMap<String, String>();
      response.headers().firstValue("x-ratelimit-remaining-requests")
          .filter(v -> v.matches("[0-9]{1,10}"))
          .ifPresent(v -> headers.put("remainingRequests", v));
      response.headers().firstValue("x-ratelimit-reset-requests")
          .filter(v -> v.matches("[0-9.hms]{1,40}")).ifPresent(v -> headers.put("requestReset", v));
      return new Response(response.body(), id, Map.copyOf(headers));
    } catch (TimeoutException e) {
      future.cancel(true);
      throw timeout();
    } catch (InterruptedException e) {
      future.cancel(true);
      Thread.currentThread().interrupt();
      throw timeout();
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof HttpTimeoutException) {
        throw timeout();
      }
      if (cause instanceof IllegalStateException) {
        throw new ImageGenerationException(Type.UNEXPECTED,
            "Provider response exceeds the permitted size", Duration.ZERO, true, null);
      }
      throw new ImageGenerationException(Type.UNAVAILABLE, "Image provider connection failed",
          Duration.ZERO, true, null);
    }
  }

  public void close() {
    client.close();
  }

  public record Response(byte[] body, String requestId, Map<String, String> headers) {

  }

  public static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {

    private final CompletableFuture<byte[]> body = new CompletableFuture<>();
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final int limit;
    private Flow.Subscription subscription;

    public BoundedBody(int limit) {
      this.limit = limit;
    }

    public CompletionStage<byte[]> getBody() {
      return body;
    }

    public void onSubscribe(Flow.Subscription subscription) {
      this.subscription = subscription;
      subscription.request(1);
    }

    public void onNext(List<ByteBuffer> buffers) {
      for (var buffer : buffers) {
        if (bytes.size() + (long) buffer.remaining() > limit) {
          subscription.cancel();
          body.completeExceptionally(new IllegalStateException("Response too large"));
          return;
        }
        byte[] chunk = new byte[buffer.remaining()];
        buffer.get(chunk);
        bytes.writeBytes(chunk);
      }
      subscription.request(1);
    }

    public void onError(Throwable throwable) {
      body.completeExceptionally(throwable);
    }

    public void onComplete() {
      body.complete(bytes.toByteArray());
    }
  }
}
