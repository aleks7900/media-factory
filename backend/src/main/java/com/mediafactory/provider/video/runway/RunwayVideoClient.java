package com.mediafactory.provider.video.runway;

import static com.mediafactory.processing.ProcessingJson.*;

import com.mediafactory.provider.resilience.ImageGenerationException;
import com.mediafactory.video.VideoFailure;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class RunwayVideoClient {

  final RunwayVideoProperties p;

  public RunwayVideoClient(RunwayVideoProperties p) {
    this.p = p;
  }

  public Map<String, Object> create(Map<String, Object> body) {
    return request("POST", "/v1/image_to_video", body, true);
  }

  public Map<String, Object> task(String id) {
    UUID.fromString(id);
    return request("GET", "/v1/tasks/" + id, null, false);
  }

  public void cancel(String id) {
    UUID.fromString(id);
    request("DELETE", "/v1/tasks/" + id, null, false);
  }

  Map<String, Object> request(String method, String path, Object body, boolean submission) {
    if (!p.configured()) {
      throw new VideoFailure("RUNWAY_NOT_CONFIGURED");
    }
    HttpURLConnection connection = null;
    try {
      URI uri = p.endpoint.resolve(path);
      // A loopback endpoint is useful for fake-server tests, but credentials never go to other HTTP
      // hosts.
      if (!uri.getScheme().equals("https")
          && !Set.of("localhost", "127.0.0.1").contains(uri.getHost())) {
        throw new VideoFailure("INSECURE_PROVIDER_ENDPOINT");
      }
      connection = (HttpURLConnection) uri.toURL().openConnection();
      connection.setInstanceFollowRedirects(false);
      connection.setConnectTimeout(10000);
      connection.setReadTimeout(45000);
      connection.setRequestMethod(method);
      connection.setRequestProperty("Authorization", "Bearer " + p.apiKey);
      connection.setRequestProperty("X-Runway-Version", "2024-11-06");
      if (body != null) {
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        byte[] bytes = write(body).getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(bytes.length);
        try (var out = connection.getOutputStream()) {
          out.write(bytes);
        }
      }
      int code = connection.getResponseCode();
      if (code < 200 || code >= 300) {
        throw RunwayVideoExceptionMapper.http(
            code, submission, connection.getHeaderField("Retry-After"));
      }
      try (var stream = connection.getInputStream()) {
        byte[] bytes = stream.readNBytes(1048577);
        if (bytes.length > 1048576) {
          throw new VideoFailure("PROVIDER_RESPONSE_LIMIT");
        }
        return bytes.length == 0 ? Map.of() : map(new String(bytes, StandardCharsets.UTF_8));
      }
    } catch (ImageGenerationException | VideoFailure e) {
      throw e;
    } catch (Exception e) {
      throw RunwayVideoExceptionMapper.transport(submission);
    } finally {
      if (connection != null) {
        connection.disconnect();
      }
    }
  }

  public byte[] download(String url) {
    HttpURLConnection c = null;
    try {
      URI uri = URI.create(url);
      if (!"https".equals(uri.getScheme())
          || uri.getUserInfo() != null
          || uri.getHost() == null
          || !p.downloadHosts.contains(uri.getHost())) {
        throw new VideoFailure("DOWNLOAD_HOST_NOT_ALLOWED");
      }
      for (var address : InetAddress.getAllByName(uri.getHost())) {
        if (address.isAnyLocalAddress()
            || address.isLoopbackAddress()
            || address.isLinkLocalAddress()
            || address.isSiteLocalAddress()
            || address.isMulticastAddress()) {
          throw new VideoFailure("PRIVATE_DOWNLOAD_ADDRESS");
        }
      }
      c = (HttpURLConnection) uri.toURL().openConnection();
      c.setInstanceFollowRedirects(false);
      c.setConnectTimeout(10000);
      c.setReadTimeout(60000);
      if (c.getResponseCode() != 200) {
        throw new VideoFailure("VIDEO_DOWNLOAD_FAILED");
      }
      if (!Objects.toString(c.getContentType(), "").split(";")[0].equals("video/mp4")) {
        throw new VideoFailure("INVALID_VIDEO_CONTENT_TYPE");
      }
      if (c.getContentLengthLong() > 134217728) {
        throw new VideoFailure("VIDEO_DOWNLOAD_LIMIT");
      }
      try (var stream = c.getInputStream()) {
        var output = new ByteArrayOutputStream();
        byte[] buffer = new byte[65536];
        long deadline = System.nanoTime() + java.time.Duration.ofMinutes(2).toNanos();
        int n;
        while ((n = stream.read(buffer)) != -1) {
          if (output.size() + n > 134217728 || System.nanoTime() > deadline) {
            throw new VideoFailure("VIDEO_DOWNLOAD_LIMIT");
          }
          output.write(buffer, 0, n);
        }
        return output.toByteArray();
      }
    } catch (VideoFailure e) {
      throw e;
    } catch (Exception e) {
      throw new VideoFailure("VIDEO_DOWNLOAD_FAILED");
    } finally {
      if (c != null) {
        c.disconnect();
      }
    }
  }
}
