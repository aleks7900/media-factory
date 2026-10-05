package com.mediafactory.bulk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.mediafactory.security.JwtService;
import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "media.worker.enabled=false",
      "media.similarity.enabled=false",
      "media-factory.auth.admin.username=admin",
      "media-factory.auth.admin.password=admin123",
      "media-factory.auth.admin.email=admin@mediafactory.local"
    })
class BulkResultsZipHttpDeliveryIntegrationTest {

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(
          DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", postgres::getJdbcUrl);
    r.add("spring.datasource.username", postgres::getUsername);
    r.add("spring.datasource.password", postgres::getPassword);
    r.add("media.storage.root", () -> "build/test-media/zip-http-" + UUID.randomUUID());
  }

  @LocalServerPort private int port;

  @Autowired private JwtService jwtService;

  @MockitoBean private BulkGenerationService bulkService;

  @TempDir Path tempDir;

  @Test
  void realHttpEndpointReturnsExactContentLengthWithoutChunkedEncoding() throws Exception {
    Path sampleZip = tempDir.resolve("sample.zip");
    byte[] data = "Hello World ZIP payload test".getBytes(StandardCharsets.UTF_8);
    try (var zos = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(sampleZip)))) {
      zos.putNextEntry(new ZipEntry("test.txt"));
      zos.write(data);
      zos.closeEntry();
      zos.finish();
    }
    long size = Files.size(sampleZip);

    UUID batchId = UUID.randomUUID();
    when(bulkService.getOrBuildResultsZip(batchId))
        .thenReturn(new BulkGenerationService.ExportedZip(sampleZip, size, "test-results.zip"));

    String token = jwtService.generateToken("admin", "ROLE_ADMIN");

    HttpClient client = HttpClient.newHttpClient();
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + "/api/v1/bulk/batches/" + batchId + "/results.zip"))
            .header("Authorization", "Bearer " + token)
            .GET()
            .build();

    HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());

    System.out.println("=== REAL HTTP RESPONSE HEADERS ===");
    response.headers().map().forEach((k, v) -> System.out.println(k + ": " + v));
    System.out.println("=== END HEADERS ===");

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue("Transfer-Encoding")).isEmpty();
    assertThat(response.headers().firstValue("Content-Length")).isPresent();
    assertThat(Long.parseLong(response.headers().firstValue("Content-Length").get())).isEqualTo(size);
    assertThat(response.body().length).isEqualTo(size);
  }

  @Test
  void realHttpEndpointDeliversLarge220MbZipWithExactContentLengthAndCurlExitsZero() throws Exception {
    Path largeZip = tempDir.resolve("large-batch-results.zip");
    long targetPayloadBytes = 222_231_638L; // Exact size from production report

    byte[] chunk = new byte[64 * 1024];
    Arrays.fill(chunk, (byte) 0x42);

    try (var zos = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(largeZip)))) {
      zos.setLevel(Deflater.NO_COMPRESSION);
      zos.putNextEntry(new ZipEntry("manifest.json"));
      zos.write("{\"batch_id\":\"test\",\"items\":100}".getBytes(StandardCharsets.UTF_8));
      zos.closeEntry();

      zos.putNextEntry(new ZipEntry("data.bin"));
      long written = 0;
      while (written < targetPayloadBytes) {
        int toWrite = (int) Math.min(chunk.length, targetPayloadBytes - written);
        zos.write(chunk, 0, toWrite);
        written += toWrite;
      }
      zos.closeEntry();
      zos.finish();
    }
    long totalSize = Files.size(largeZip);
    assertThat(totalSize).isGreaterThanOrEqualTo(targetPayloadBytes);

    UUID batchId = UUID.randomUUID();
    when(bulkService.getOrBuildResultsZip(batchId))
        .thenReturn(new BulkGenerationService.ExportedZip(largeZip, totalSize, "large-results.zip"));

    String token = jwtService.generateToken("admin", "ROLE_ADMIN");

    // 1. Verify via HttpClient
    HttpClient client = HttpClient.newHttpClient();
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + "/api/v1/bulk/batches/" + batchId + "/results.zip"))
            .header("Authorization", "Bearer " + token)
            .GET()
            .build();

    Path downloadedViaClient = tempDir.resolve("downloaded-client.zip");
    HttpResponse<Path> response = client.send(request, HttpResponse.BodyHandlers.ofFile(downloadedViaClient));

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue("Transfer-Encoding")).isEmpty();
    assertThat(response.headers().firstValue("Content-Length")).isPresent();
    long declaredLength = Long.parseLong(response.headers().firstValue("Content-Length").get());
    assertThat(declaredLength).isEqualTo(totalSize);
    assertThat(Files.size(downloadedViaClient)).isEqualTo(totalSize);

    // 2. Verify via real external curl.exe process
    Path downloadedViaCurl = tempDir.resolve("downloaded-curl.zip");
    String url = "http://127.0.0.1:" + port + "/api/v1/bulk/batches/" + batchId + "/results.zip";

    ProcessBuilder pb =
        new ProcessBuilder(
            "curl.exe",
            "-v",
            "-sS",
            "-H",
            "Authorization: Bearer " + token,
            "-o",
            downloadedViaCurl.toAbsolutePath().toString(),
            url);
    pb.redirectErrorStream(false);
    Process process = pb.start();

    String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
    int exitCode = process.waitFor();

    System.out.println("=== CURL STDERR OUTPUT ===");
    System.out.println(stderr);
    System.out.println("=== END CURL STDERR ===");

    assertThat(exitCode).as("curl exit code must be 0 (not 18)").isEqualTo(0);
    assertThat(stderr).containsIgnoringCase("Content-Length: " + totalSize);
    assertThat(stderr).doesNotContainIgnoringCase("Transfer-Encoding: chunked");
    assertThat(stderr).doesNotContainIgnoringCase("transfer closed with outstanding read data remaining");
    assertThat(Files.size(downloadedViaCurl)).isEqualTo(totalSize);

    // 3. Verify ZIP integrity of downloaded file
    try (ZipFile zipFile = new ZipFile(downloadedViaCurl.toFile())) {
      assertThat(zipFile.getEntry("manifest.json")).isNotNull();
      assertThat(zipFile.getEntry("data.bin")).isNotNull();
      assertThat(zipFile.getEntry("data.bin").getSize()).isEqualTo(targetPayloadBytes);
    }
  }
}
