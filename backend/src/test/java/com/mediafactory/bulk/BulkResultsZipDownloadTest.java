package com.mediafactory.bulk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mediafactory.provider.ImageGenerationProperties;
import com.mediafactory.provider.routing.ImageProviderRouter;
import com.mediafactory.provider.video.VideoProviderRouter;
import com.mediafactory.security.JwtAuthenticationFilter;
import com.mediafactory.security.JwtService;
import com.mediafactory.security.SecurityProperties;
import jakarta.servlet.http.Cookie;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class BulkResultsZipDownloadTest {

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

  @Test
  void resultsZipDownloadReturnsAppropriateHeadersAndStreamingContent() throws Exception {
    UUID batchId = UUID.randomUUID();
    when(bulkService.detail(batchId)).thenReturn(Map.of("archive_name", "nature-scenes.zip"));

    doAnswer(invocation -> {
      var out = invocation.getArgument(1, java.io.OutputStream.class);
      try (var zip = new ZipOutputStream(out)) {
        zip.putNextEntry(new ZipEntry("forest.png"));
        zip.write(new byte[]{1, 2, 3, 4});
        zip.closeEntry();

        zip.putNextEntry(new ZipEntry("manifest.json"));
        zip.write("{\"batchId\":\"test\"}".getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
      }
      return null;
    }).when(bulkService).export(eq(batchId), any());

    MvcResult mvcResult = mockMvc.perform(get("/api/v1/bulk/batches/{id}/results.zip", batchId))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.CONTENT_TYPE, MediaType.parseMediaType("application/zip").toString()))
        .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"nature-scenes-results.zip\""))
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-cache, no-store, must-revalidate, private"))
        .andExpect(header().string(HttpHeaders.PRAGMA, "no-cache"))
        .andExpect(header().string("Expires", "0"))
        .andExpect(header().string("X-Content-Type-Options", "nosniff"))
        .andReturn();

    // Perform async dispatch to evaluate StreamingResponseBody
    MvcResult finalResult = mockMvc.perform(asyncDispatch(mvcResult))
        .andExpect(status().isOk())
        .andReturn();

    byte[] zipBytes = finalResult.getResponse().getContentAsByteArray();
    assertThat(zipBytes).isNotEmpty();

    // Verify ZIP content
    Map<String, byte[]> extracted = new LinkedHashMap<>();
    try (var zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
      ZipEntry entry;
      while ((entry = zis.getNextEntry()) != null) {
        extracted.put(entry.getName(), zis.readAllBytes());
        zis.closeEntry();
      }
    }

    assertThat(extracted).containsKey("forest.png");
    assertThat(extracted).containsKey("manifest.json");
    assertThat(extracted.get("forest.png")).isEqualTo(new byte[]{1, 2, 3, 4});
    assertThat(new String(extracted.get("manifest.json"), StandardCharsets.UTF_8)).contains("test");
  }

  @Test
  void preservesFilenameWithSanitizationAndQuotes() throws Exception {
    UUID batchId = UUID.randomUUID();
    when(bulkService.detail(batchId)).thenReturn(Map.of("archive_name", "Cars & Bikes (2026).zip"));

    doAnswer(invocation -> {
      var out = invocation.getArgument(1, java.io.OutputStream.class);
      try (var zip = new ZipOutputStream(out)) {
        zip.putNextEntry(new ZipEntry("manifest.json"));
        zip.write("{}".getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
      }
      return null;
    }).when(bulkService).export(eq(batchId), any());

    mockMvc.perform(get("/api/v1/bulk/batches/{id}/results.zip", batchId))
        .andExpect(status().isOk())
        .andExpect(header().string(
            HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=\"Cars___Bikes__2026_-results.zip\""
        ));
  }

  @Test
  void largeZipStreamDownloadSucceedsWithoutTruncation() throws Exception {
    UUID batchId = UUID.randomUUID();
    when(bulkService.detail(batchId)).thenReturn(Map.of("archive_name", "large-dataset.zip"));

    byte[] largeBlob = new byte[1024 * 1024]; // 1 MB entry
    Arrays.fill(largeBlob, (byte) 42);

    doAnswer(invocation -> {
      var out = invocation.getArgument(1, java.io.OutputStream.class);
      try (var zip = new ZipOutputStream(out)) {
        for (int i = 1; i <= 3; i++) {
          zip.putNextEntry(new ZipEntry("image-" + i + ".jpg"));
          zip.write(largeBlob);
          zip.closeEntry();
        }
        zip.putNextEntry(new ZipEntry("manifest.json"));
        zip.write("{\"totalTasks\":3}".getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
      }
      return null;
    }).when(bulkService).export(eq(batchId), any());

    MvcResult mvcResult = mockMvc.perform(get("/api/v1/bulk/batches/{id}/results.zip", batchId))
        .andExpect(status().isOk())
        .andReturn();

    MvcResult finalResult = mockMvc.perform(asyncDispatch(mvcResult))
        .andExpect(status().isOk())
        .andReturn();

    byte[] content = finalResult.getResponse().getContentAsByteArray();
    // 3 entries of 1 MB each + manifest, compressed into ZIP:
    assertThat(content.length).isGreaterThan(1000);

    int entryCount = 0;
    try (var zis = new ZipInputStream(new ByteArrayInputStream(content))) {
      ZipEntry entry;
      while ((entry = zis.getNextEntry()) != null) {
        entryCount++;
        byte[] read = zis.readAllBytes();
        if (entry.getName().startsWith("image-")) {
          assertThat(read).hasSize(largeBlob.length);
        }
        zis.closeEntry();
      }
    }
    assertThat(entryCount).isEqualTo(4);
  }

  @Test
  void authenticatedDownloadSucceedsWithBearerTokenCookieOrQueryParam() throws Exception {
    UUID batchId = UUID.randomUUID();
    when(bulkService.detail(batchId)).thenReturn(Map.of("archive_name", "auth-test.zip"));

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
    // Generate expired token
    String expiredToken = jwtService.generateToken("admin", "ROLE_ADMIN", -3600);
    JwtService.JwtValidationResult expiredValidation = jwtService.validateToken(expiredToken);
    assertThat(expiredValidation.valid()).isFalse();

    // Invalid token
    JwtService.JwtValidationResult invalidValidation = jwtService.validateToken("invalid.token.here");
    assertThat(invalidValidation.valid()).isFalse();
  }
}
