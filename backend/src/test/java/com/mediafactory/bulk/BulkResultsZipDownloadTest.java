package com.mediafactory.bulk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mediafactory.provider.ImageGenerationProperties;
import com.mediafactory.provider.routing.ImageProviderRouter;
import com.mediafactory.provider.video.VideoProviderRouter;
import com.mediafactory.security.JwtAuthenticationFilter;
import com.mediafactory.security.JwtService;
import com.mediafactory.security.SecurityProperties;
import com.mediafactory.storage.MediaStorage;
import jakarta.servlet.http.Cookie;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;

class BulkResultsZipDownloadTest {

  @TempDir
  Path tempDir;

  private BulkGenerationService bulkService;
  private BulkGenerationController controller;
  private JwtService jwtService;
  private SecurityProperties securityProperties;
  private JwtAuthenticationFilter jwtFilter;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    bulkService = mock(BulkGenerationService.class);
    ImageProviderRouter images = mock(ImageProviderRouter.class);
    ImageGenerationProperties imageConfig = mock(ImageGenerationProperties.class);
    VideoProviderRouter videos = mock(VideoProviderRouter.class);

    controller = new BulkGenerationController(bulkService, images, imageConfig, videos);

    securityProperties = new SecurityProperties();
    securityProperties.getJwt().setSecret("very-secret-test-key-of-at-least-32-chars-length!");
    securityProperties.getCookie().setName("media_factory_jwt_token");

    jwtService = new JwtService(securityProperties);
    jwtFilter = new JwtAuthenticationFilter(jwtService, securityProperties);

    mockMvc = MockMvcBuilders.standaloneSetup(controller)
        .addFilters(jwtFilter)
        .build();
  }

  private static String sha256(byte[] bytes) {
    try {
      var md = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(md.digest(bytes));
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private Path createValidTestZip(String entryName, byte[] entryData) throws IOException {
    Path zipFile = tempDir.resolve("sample-results-" + UUID.randomUUID() + ".zip");
    try (var zos = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(zipFile)))) {
      zos.putNextEntry(new ZipEntry(entryName));
      try (var in = new ByteArrayInputStream(entryData)) {
        in.transferTo(zos);
      }
      zos.closeEntry();

      if (!"manifest.json".equals(entryName)) {
        zos.putNextEntry(new ZipEntry("manifest.json"));
        byte[] manifest = "{\"batchId\":\"test-batch\"}".getBytes(StandardCharsets.UTF_8);
        try (var in = new ByteArrayInputStream(manifest)) {
          in.transferTo(zos);
        }
        zos.closeEntry();
      }
      zos.finish();
    }
    return zipFile;
  }

  private void mockJdbcRows(JdbcClient db, List<Map<String, Object>> rows) {
    var statementSpec = mock(JdbcClient.StatementSpec.class);
    var resultQuerySpec = mock(JdbcClient.ResultQuerySpec.class);
    when(db.sql(anyString())).thenReturn(statementSpec);
    when(statementSpec.param(any())).thenReturn(statementSpec);
    when(statementSpec.param(anyString(), any())).thenReturn(statementSpec);
    when(statementSpec.params(any(), any())).thenReturn(statementSpec);
    when(statementSpec.params(any(), any(), any(), any(), any(), any(), any())).thenReturn(statementSpec);
    when(statementSpec.query()).thenReturn(resultQuerySpec);
    when(resultQuerySpec.listOfRows()).thenReturn(rows);
  }

  // --- Controller Tests ---

  @Test
  void resultsZipDownloadReturnsAppropriateHeadersAndFileContent() throws Exception {
    UUID batchId = UUID.randomUUID();
    Path testZip = createValidTestZip("forest.png", new byte[]{1, 2, 3, 4});
    long zipSize = Files.size(testZip);

    when(bulkService.getOrBuildResultsZip(batchId))
        .thenReturn(new BulkGenerationService.ExportedZip(testZip, zipSize, "nature-scenes-results.zip"));

    MvcResult mvcResult = mockMvc.perform(get("/api/v1/bulk/batches/{id}/results.zip", batchId))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.CONTENT_TYPE, MediaType.parseMediaType("application/zip").toString()))
        .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"nature-scenes-results.zip\""))
        .andExpect(header().string(HttpHeaders.CONTENT_LENGTH, String.valueOf(zipSize)))
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-cache, no-store, must-revalidate, private"))
        .andExpect(header().string(HttpHeaders.PRAGMA, "no-cache"))
        .andExpect(header().string("Expires", "0"))
        .andExpect(header().string("X-Content-Type-Options", "nosniff"))
        .andReturn();

    byte[] downloadedBytes = mvcResult.getResponse().getContentAsByteArray();
    assertThat(downloadedBytes).hasSize((int) zipSize);

    // Verify ZIP integrity and content
    Map<String, byte[]> extracted = new LinkedHashMap<>();
    try (var zis = new ZipInputStream(new ByteArrayInputStream(downloadedBytes))) {
      ZipEntry entry;
      while ((entry = zis.getNextEntry()) != null) {
        extracted.put(entry.getName(), zis.readAllBytes());
        zis.closeEntry();
      }
    }

    assertThat(extracted).containsKey("forest.png");
    assertThat(extracted).containsKey("manifest.json");
    assertThat(extracted.get("forest.png")).isEqualTo(new byte[]{1, 2, 3, 4});
    assertThat(new String(extracted.get("manifest.json"), StandardCharsets.UTF_8)).contains("test-batch");
  }

  @Test
  void preservesFilenameWithSanitizationAndQuotes() throws Exception {
    UUID batchId = UUID.randomUUID();
    Path testZip = createValidTestZip("manifest.json", "{}".getBytes(StandardCharsets.UTF_8));
    long zipSize = Files.size(testZip);

    when(bulkService.getOrBuildResultsZip(batchId))
        .thenReturn(new BulkGenerationService.ExportedZip(testZip, zipSize, "Cars___Bikes__2026_-results.zip"));

    mockMvc.perform(get("/api/v1/bulk/batches/{id}/results.zip", batchId))
        .andExpect(status().isOk())
        .andExpect(header().string(
            HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=\"Cars___Bikes__2026_-results.zip\""
        ));
  }

  @Test
  void authenticatedDownloadSucceedsWithBearerTokenCookieOrQueryParam() throws Exception {
    UUID batchId = UUID.randomUUID();
    Path testZip = createValidTestZip("auth.txt", "ok".getBytes(StandardCharsets.UTF_8));
    when(bulkService.getOrBuildResultsZip(batchId))
        .thenReturn(new BulkGenerationService.ExportedZip(testZip, Files.size(testZip), "auth-results.zip"));

    String validToken = jwtService.generateToken("admin", "ROLE_ADMIN");

    // 1. With Authorization header
    mockMvc.perform(get("/api/v1/bulk/batches/{id}/results.zip", batchId)
            .header("Authorization", "Bearer " + validToken))
        .andExpect(status().isOk());

    // 2. With Cookie
    mockMvc.perform(get("/api/v1/bulk/batches/{id}/results.zip", batchId)
            .cookie(new Cookie("media_factory_jwt_token", validToken)))
        .andExpect(status().isOk());

    // 3. With Query parameter ?token=
    mockMvc.perform(get("/api/v1/bulk/batches/{id}/results.zip?token=" + validToken, batchId))
        .andExpect(status().isOk());
  }

  @Test
  void expiredOrInvalidAuthenticationProducesExpectedError() {
    String expiredToken = jwtService.generateToken("admin", "ROLE_ADMIN", -3600);
    JwtService.JwtValidationResult expiredValidation = jwtService.validateToken(expiredToken);
    assertThat(expiredValidation.valid()).isFalse();

    JwtService.JwtValidationResult invalidValidation = jwtService.validateToken("invalid.token.here");
    assertThat(invalidValidation.valid()).isFalse();
  }

  @Test
  void largeZipDownload200MBPlusSucceedsWithExactContentLengthAndValidIntegrity() throws Exception {
    Path largeZip = tempDir.resolve("results-large-215mb.zip");
    long payloadTarget = 215L * 1024 * 1024; // 215 MB
    byte[] chunk = new byte[1024 * 1024]; // 1 MB buffer
    Arrays.fill(chunk, (byte) 'Z');

    // Create 215 MB ZIP with NO_COMPRESSION (completes in ~200ms)
    try (var zos = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(largeZip)))) {
      zos.setLevel(Deflater.NO_COMPRESSION);
      for (int i = 0; i < 215; i++) {
        zos.putNextEntry(new ZipEntry("image-chunk-" + i + ".bin"));
        try (var in = new ByteArrayInputStream(chunk)) {
          in.transferTo(zos);
        }
        zos.closeEntry();
      }
      zos.putNextEntry(new ZipEntry("manifest.json"));
      byte[] manifest = "{\"batchId\":\"large-batch\",\"totalTasks\":215}".getBytes(StandardCharsets.UTF_8);
      try (var in = new ByteArrayInputStream(manifest)) {
        in.transferTo(zos);
      }
      zos.closeEntry();
      zos.finish();
    }

    long actualSize = Files.size(largeZip);
    assertThat(actualSize).isGreaterThanOrEqualTo(payloadTarget);

    UUID batchId = UUID.randomUUID();
    when(bulkService.getOrBuildResultsZip(batchId))
        .thenReturn(new BulkGenerationService.ExportedZip(largeZip, actualSize, "large-dataset-results.zip"));

    MvcResult result = mockMvc.perform(get("/api/v1/bulk/batches/{id}/results.zip", batchId))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/zip"))
        .andExpect(header().string(HttpHeaders.CONTENT_LENGTH, String.valueOf(actualSize)))
        .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"large-dataset-results.zip\""))
        .andReturn();

    byte[] downloaded = result.getResponse().getContentAsByteArray();
    assertThat(downloaded.length).isEqualTo(actualSize);

    // Verify ZIP integrity (Central Directory) via java.util.zip.ZipFile
    try (var zf = new ZipFile(largeZip.toFile())) {
      assertThat(zf.size()).isEqualTo(216); // 215 image chunks + manifest.json
    }

    // Verify ZIP integrity via external unzip.exe if installed
    Path gitUnzip = Path.of("C:\\Program Files\\Git\\usr\\bin\\unzip.exe");
    if (Files.exists(gitUnzip)) {
      Process process = new ProcessBuilder(gitUnzip.toString(), "-t", largeZip.toAbsolutePath().toString()).start();
      String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      int exitCode = process.waitFor();
      assertThat(exitCode).isZero();
      assertThat(output).contains("No errors detected in compressed data");
    }
  }

  // --- BulkGenerationService Export & Caching Tests ---

  @Test
  void exportWithSingleResultProducesValidZipWithManifest() throws Exception {
    JdbcClient db = mock(JdbcClient.class);
    MediaStorage storage = mock(MediaStorage.class);
    BulkGenerationService service = new BulkGenerationService(
        db, mock(TransactionTemplate.class), storage, null, mock(BulkArchiveParser.class), List.of());

    UUID batchId = UUID.randomUUID();
    UUID taskId = UUID.randomUUID();
    byte[] imageBytes = new byte[]{10, 20, 30, 40};
    String imageSha = sha256(imageBytes);

    Map<String, Object> taskRow = new HashMap<>();
    taskRow.put("id", taskId);
    taskRow.put("name", "hero_banner");
    taskRow.put("status", "COMPLETED");
    taskRow.put("storage_key", "bulk/results/hero.png");
    taskRow.put("sha256", imageSha);
    taskRow.put("media_type", "image/png");

    mockJdbcRows(db, List.of(taskRow));
    when(storage.read("bulk/results/hero.png")).thenReturn(imageBytes);

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    service.export(batchId, out);

    Map<String, byte[]> extracted = new HashMap<>();
    try (var zis = new ZipInputStream(new ByteArrayInputStream(out.toByteArray()))) {
      ZipEntry entry;
      while ((entry = zis.getNextEntry()) != null) {
        extracted.put(entry.getName(), zis.readAllBytes());
        zis.closeEntry();
      }
    }

    assertThat(extracted).containsKey("hero_banner.png");
    assertThat(extracted.get("hero_banner.png")).isEqualTo(imageBytes);
    assertThat(extracted).containsKey("manifest.json");
    assertThat(new String(extracted.get("manifest.json"), StandardCharsets.UTF_8))
        .contains("hero_banner.png")
        .contains(imageSha);
  }

  @Test
  void exportWith100ResultsProducesCompleteZip() throws Exception {
    JdbcClient db = mock(JdbcClient.class);
    MediaStorage storage = mock(MediaStorage.class);
    BulkGenerationService service = new BulkGenerationService(
        db, mock(TransactionTemplate.class), storage, null, mock(BulkArchiveParser.class), List.of());

    UUID batchId = UUID.randomUUID();
    List<Map<String, Object>> rows = new ArrayList<>();
    byte[] testImage = new byte[]{1, 2, 3};
    String testSha = sha256(testImage);

    for (int i = 1; i <= 100; i++) {
      UUID taskId = UUID.randomUUID();
      String key = "bulk/results/item-" + i + ".jpg";
      Map<String, Object> row = new HashMap<>();
      row.put("id", taskId);
      row.put("name", "scene_" + i);
      row.put("status", "COMPLETED");
      row.put("storage_key", key);
      row.put("sha256", testSha);
      row.put("media_type", "image/jpeg");
      rows.add(row);
      when(storage.read(key)).thenReturn(testImage);
    }

    mockJdbcRows(db, rows);

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    service.export(batchId, out);

    int entryCount = 0;
    boolean manifestFound = false;
    try (var zis = new ZipInputStream(new ByteArrayInputStream(out.toByteArray()))) {
      ZipEntry entry;
      while ((entry = zis.getNextEntry()) != null) {
        entryCount++;
        if ("manifest.json".equals(entry.getName())) {
          manifestFound = true;
        }
        zis.closeEntry();
      }
    }

    // 100 images + 1 manifest = 101 entries
    assertThat(entryCount).isEqualTo(101);
    assertThat(manifestFound).isTrue();
  }

  @Test
  void exportWithMissingOrUnreadableStorageObjectContinuesCleanlyWithoutTruncation() throws Exception {
    JdbcClient db = mock(JdbcClient.class);
    MediaStorage storage = mock(MediaStorage.class);
    BulkGenerationService service = new BulkGenerationService(
        db, mock(TransactionTemplate.class), storage, null, mock(BulkArchiveParser.class), List.of());

    UUID batchId = UUID.randomUUID();
    byte[] goodImage = new byte[]{11, 22, 33};
    String goodSha = sha256(goodImage);

    // Task 1: Good
    Map<String, Object> t1 = new HashMap<>();
    t1.put("id", UUID.randomUUID());
    t1.put("name", "task_good_1");
    t1.put("status", "COMPLETED");
    t1.put("storage_key", "bulk/good1.jpg");
    t1.put("sha256", goodSha);
    t1.put("media_type", "image/jpeg");
    when(storage.read("bulk/good1.jpg")).thenReturn(goodImage);

    // Task 2: Missing / Storage throws MinioException / RuntimeException
    Map<String, Object> t2 = new HashMap<>();
    t2.put("id", UUID.randomUUID());
    t2.put("name", "task_broken_2");
    t2.put("status", "COMPLETED");
    t2.put("storage_key", "bulk/missing.jpg");
    t2.put("sha256", "some-sha");
    t2.put("media_type", "image/jpeg");
    when(storage.read("bulk/missing.jpg")).thenThrow(new RuntimeException("MinIO: Object not found"));

    // Task 3: Good
    Map<String, Object> t3 = new HashMap<>();
    t3.put("id", UUID.randomUUID());
    t3.put("name", "task_good_3");
    t3.put("status", "COMPLETED");
    t3.put("storage_key", "bulk/good3.jpg");
    t3.put("sha256", goodSha);
    t3.put("media_type", "image/jpeg");
    when(storage.read("bulk/good3.jpg")).thenReturn(goodImage);

    mockJdbcRows(db, List.of(t1, t2, t3));

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    // export must NOT throw
    service.export(batchId, out);

    Map<String, byte[]> extracted = new HashMap<>();
    try (var zis = new ZipInputStream(new ByteArrayInputStream(out.toByteArray()))) {
      ZipEntry entry;
      while ((entry = zis.getNextEntry()) != null) {
        extracted.put(entry.getName(), zis.readAllBytes());
        zis.closeEntry();
      }
    }

    // Good tasks are written, missing task is omitted from file entries but recorded in manifest
    assertThat(extracted).containsKey("task_good_1.jpg");
    assertThat(extracted).containsKey("task_good_3.jpg");
    assertThat(extracted).doesNotContainKey("task_broken_2.jpg");
    assertThat(extracted).containsKey("manifest.json");

    String manifestJson = new String(extracted.get("manifest.json"), StandardCharsets.UTF_8);
    assertThat(manifestJson).contains("STORAGE_READ_ERROR");
    assertThat(manifestJson).contains("Object not found");
  }

  @Test
  void exportWithChecksumMismatchRecordsErrorInManifest() throws Exception {
    JdbcClient db = mock(JdbcClient.class);
    MediaStorage storage = mock(MediaStorage.class);
    BulkGenerationService service = new BulkGenerationService(
        db, mock(TransactionTemplate.class), storage, null, mock(BulkArchiveParser.class), List.of());

    UUID batchId = UUID.randomUUID();
    byte[] corruptedData = new byte[]{99, 99};

    Map<String, Object> taskRow = new HashMap<>();
    taskRow.put("id", UUID.randomUUID());
    taskRow.put("name", "corrupted_asset");
    taskRow.put("status", "COMPLETED");
    taskRow.put("storage_key", "bulk/corrupt.png");
    taskRow.put("sha256", "expected-different-sha");
    taskRow.put("media_type", "image/png");

    mockJdbcRows(db, List.of(taskRow));
    when(storage.read("bulk/corrupt.png")).thenReturn(corruptedData);

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    service.export(batchId, out);

    Map<String, byte[]> extracted = new HashMap<>();
    try (var zis = new ZipInputStream(new ByteArrayInputStream(out.toByteArray()))) {
      ZipEntry entry;
      while ((entry = zis.getNextEntry()) != null) {
        extracted.put(entry.getName(), zis.readAllBytes());
        zis.closeEntry();
      }
    }

    assertThat(extracted).containsKey("manifest.json");
    String manifestJson = new String(extracted.get("manifest.json"), StandardCharsets.UTF_8);
    assertThat(manifestJson).contains("CHECKSUM_MISMATCH");
  }

  @Test
  void exportWithDuplicateFilenamesDisambiguatesCleanly() throws Exception {
    JdbcClient db = mock(JdbcClient.class);
    MediaStorage storage = mock(MediaStorage.class);
    BulkGenerationService service = new BulkGenerationService(
        db, mock(TransactionTemplate.class), storage, null, mock(BulkArchiveParser.class), List.of());

    UUID batchId = UUID.randomUUID();
    byte[] data = new byte[]{1, 2, 3};
    String sha = sha256(data);

    UUID id1 = UUID.randomUUID();
    UUID id2 = UUID.randomUUID();

    Map<String, Object> t1 = new HashMap<>();
    t1.put("id", id1);
    t1.put("name", "sunset");
    t1.put("status", "COMPLETED");
    t1.put("storage_key", "bulk/1.jpg");
    t1.put("sha256", sha);
    t1.put("media_type", "image/jpeg");

    Map<String, Object> t2 = new HashMap<>();
    t2.put("id", id2);
    t2.put("name", "sunset");
    t2.put("status", "COMPLETED");
    t2.put("storage_key", "bulk/2.jpg");
    t2.put("sha256", sha);
    t2.put("media_type", "image/jpeg");

    mockJdbcRows(db, List.of(t1, t2));
    when(storage.read(anyString())).thenReturn(data);

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    // Must NOT throw ZipException: duplicate entry
    service.export(batchId, out);

    List<String> entryNames = new ArrayList<>();
    try (var zis = new ZipInputStream(new ByteArrayInputStream(out.toByteArray()))) {
      ZipEntry entry;
      while ((entry = zis.getNextEntry()) != null) {
        entryNames.add(entry.getName());
        zis.closeEntry();
      }
    }

    assertThat(entryNames).contains("sunset.jpg");
    assertThat(entryNames).contains("sunset-" + id2 + ".jpg");
  }

  @Test
  void exportWithUnusualFilenamesSanitizesCleanly() throws Exception {
    JdbcClient db = mock(JdbcClient.class);
    MediaStorage storage = mock(MediaStorage.class);
    BulkGenerationService service = new BulkGenerationService(
        db, mock(TransactionTemplate.class), storage, null, mock(BulkArchiveParser.class), List.of());

    UUID batchId = UUID.randomUUID();
    byte[] data = new byte[]{7, 8, 9};
    String sha = sha256(data);

    Map<String, Object> taskRow = new HashMap<>();
    taskRow.put("id", UUID.randomUUID());
    taskRow.put("name", "../../weird:name*?<test>|#1");
    taskRow.put("status", "COMPLETED");
    taskRow.put("storage_key", "bulk/weird.png");
    taskRow.put("sha256", sha);
    taskRow.put("media_type", "image/png");

    mockJdbcRows(db, List.of(taskRow));
    when(storage.read("bulk/weird.png")).thenReturn(data);

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    service.export(batchId, out);

    List<String> entryNames = new ArrayList<>();
    try (var zis = new ZipInputStream(new ByteArrayInputStream(out.toByteArray()))) {
      ZipEntry entry;
      while ((entry = zis.getNextEntry()) != null) {
        entryNames.add(entry.getName());
        zis.closeEntry();
      }
    }

    assertThat(entryNames).hasSize(2); // sanitized entry + manifest.json
    String sanitized = entryNames.stream().filter(n -> !n.equals("manifest.json")).findFirst().orElseThrow();
    assertThat(sanitized).doesNotContain("..");
    assertThat(sanitized).doesNotContain("/");
    assertThat(sanitized).doesNotContain(":");
    assertThat(sanitized).doesNotContain("*");
    assertThat(sanitized).doesNotContain("?");
    assertThat(sanitized).endsWith(".png");
  }

  @Test
  void cleanCachedZipsRemovesCacheFilesForBatch() throws IOException {
    BulkGenerationService service = new BulkGenerationService(
        null, null, null, null, null, List.of());

    Path cacheDir = Path.of(System.getProperty("java.io.tmpdir"), "media-factory-bulk-exports");
    Files.createDirectories(cacheDir);

    UUID batchId = UUID.randomUUID();
    Path file1 = cacheDir.resolve("batch-" + batchId + "-c5.zip");
    Path file2 = cacheDir.resolve("temp-batch-" + batchId + "-1234.tmp");
    Files.writeString(file1, "dummy");
    Files.writeString(file2, "dummy");

    assertThat(Files.exists(file1)).isTrue();
    assertThat(Files.exists(file2)).isTrue();

    service.cleanCachedZips(batchId);

    assertThat(Files.exists(file1)).isFalse();
    assertThat(Files.exists(file2)).isFalse();
  }
}
