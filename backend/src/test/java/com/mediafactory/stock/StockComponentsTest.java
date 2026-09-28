package com.mediafactory.stock;

import static org.assertj.core.api.Assertions.*;

import com.mediafactory.similarity.PerceptualHash;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.List;
import javax.imageio.ImageIO;
import org.apache.commons.csv.*;
import org.junit.jupiter.api.Test;

class StockComponentsTest {
  static Map<String, Object> profile() {
    var p = new HashMap<String, Object>();
    p.put("minimumMegapixels", 4);
    p.put("maximumMegapixels", 64);
    p.put("minimumWidth", 1);
    p.put("minimumHeight", 1);
    p.put("maximumWidth", 8192);
    p.put("maximumHeight", 8192);
    p.put("maximumFileSize", 52428800);
    p.put("acceptedFormats", List.of("JPEG"));
    p.put("colorSpace", "sRGB");
    p.put("allowAlpha", false);
    p.put("orientation", "ANY");
    p.put("preferredQuality", 95);
    p.put("categories", List.of("ABSTRACT", "NATURE"));
    p.put("minimumKeywords", 2);
    p.put("maximumKeywords", 49);
    p.put("forbiddenTerms", List.of("shot on"));
    return p;
  }

  static byte[] image(int width, int height, String format, int type) throws Exception {
    var image = new BufferedImage(width, height, type);
    var g = image.createGraphics();
    g.setColor(Color.BLUE);
    g.fillRect(0, 0, width, height);
    g.dispose();
    var out = new ByteArrayOutputStream();
    ImageIO.write(image, format, out);
    return out.toByteArray();
  }

  StockTechnicalValidator.Result validate(byte[] b, Map<String, Object> p) {
    return new StockTechnicalValidator()
        .validate(b, PerceptualHash.sha(b), p, Map.of("quality", 95));
  }

  @Test
  void exactFourMegapixelJpegPasses() throws Exception {
    var b = image(2000, 2000, "jpeg", BufferedImage.TYPE_INT_RGB);
    assertThat(validate(b, profile()).valid()).isTrue();
  }

  @Test
  void compressionUsesActualEncoderEvidenceAndReportsUnknownWithoutIt() throws Exception {
    var bytes = image(2000, 2000, "jpeg", BufferedImage.TYPE_INT_RGB);
    var validator = new StockTechnicalValidator();
    var actual =
        validator.validate(
            bytes,
            PerceptualHash.sha(bytes),
            profile(),
            Map.of("quality", 98, "actualQuality", 90));
    assertThat(actual.checks())
        .anyMatch(
            c ->
                c.type().equals("COMPRESSION")
                    && c.status().equals("WARNING")
                    && c.actual().equals(90));
    var unknown = validator.validate(bytes, PerceptualHash.sha(bytes), profile(), Map.of());
    assertThat(unknown.warnings()).anyMatch(w -> w.startsWith("ENCODER_QUALITY_UNKNOWN"));
  }

  @Test
  void unroundedBelowFourFails() throws Exception {
    var r = validate(image(1999, 2000, "jpeg", BufferedImage.TYPE_INT_RGB), profile());
    assertThat(r.checks())
        .anyMatch(c -> c.type().equals("MEGAPIXELS") && c.status().equals("FAIL"));
  }

  @Test
  void pngAndAlphaFail() throws Exception {
    var r = validate(image(2000, 2000, "png", BufferedImage.TYPE_INT_ARGB), profile());
    assertThat(r.checks())
        .anyMatch(c -> c.type().equals("FORMAT") && c.status().equals("FAIL"))
        .anyMatch(c -> c.type().equals("ALPHA") && c.status().equals("FAIL"));
  }

  @Test
  void grayscaleIsNotSrgb() throws Exception {
    var r = validate(image(2000, 2000, "jpeg", BufferedImage.TYPE_BYTE_GRAY), profile());
    assertThat(r.checks())
        .anyMatch(c -> c.type().equals("COLOR_SPACE") && c.status().equals("FAIL"));
  }

  @Test
  void oversizedFileFails() throws Exception {
    var p = profile();
    p.put("maximumFileSize", 10);
    assertThat(validate(image(2000, 2000, "jpeg", BufferedImage.TYPE_INT_RGB), p).valid())
        .isFalse();
  }

  @Test
  void corruptAndChecksumFail() {
    var r =
        new StockTechnicalValidator()
            .validate(new byte[] {1, 2, 3}, "0".repeat(64), profile(), Map.of());
    assertThat(r.valid()).isFalse();
    assertThat(r.checks()).anyMatch(c -> c.type().equals("CHECKSUM") && c.status().equals("FAIL"));
  }

  @Test
  void truncatedJpegFails() throws Exception {
    var b = image(2000, 2000, "jpeg", BufferedImage.TYPE_INT_RGB);
    assertThat(validate(Arrays.copyOf(b, b.length - 2), profile()).valid()).isFalse();
  }

  @Test
  void keywordOrderAndMultiwordSurviveConservativeDedup() {
    var k =
        StockKeywords.normalize(
            List.of("  Cybernetic   Wolf! ", "wolf", "WOLVES", "neon blue", "wolf"), 49, "MANUAL");
    assertThat(k)
        .extracting(StockKeywords.Keyword::value)
        .containsExactly("cybernetic wolf", "wolf", "neon blue");
    assertThat(k.get(2).rank()).isEqualTo(3);
  }

  @Test
  void keywordLimitKeepsProviderRelevanceOrder() {
    assertThat(StockKeywords.normalize(List.of("zebra", "animal", "black and white"), 2, "LLM"))
        .extracting(StockKeywords.Keyword::value)
        .containsExactly("zebra", "animal");
  }

  @Test
  void filenameStableUniqueAndBounded() {
    UUID a = UUID.randomUUID(), m = UUID.randomUUID();
    var f = StockFilenameStrategy.filename("Éléphant / .. " + "long".repeat(100), a, m);
    assertThat(f).matches("[a-z0-9-]+\\.jpg").hasSizeLessThan(160);
    assertThat(f)
        .isEqualTo(StockFilenameStrategy.filename("Éléphant / .. " + "long".repeat(100), a, m));
    assertThat(f)
        .isNotEqualTo(
            StockFilenameStrategy.filename(
                "Éléphant / .. " + "long".repeat(100), UUID.randomUUID(), m));
  }

  static Map<String, Object> metadata() {
    return new LinkedHashMap<>(
        Map.of(
            "title",
            "Abstract blue texture",
            "description",
            "Blue abstract texture with soft visual patterns.",
            "keywords",
            List.of(Map.of("value", "blue"), Map.of("value", "texture")),
            "categories",
            List.of("ABSTRACT"),
            "contentType",
            "UNDETERMINED",
            "aiGenerated",
            true,
            "riskFlags",
            List.of()));
  }

  @Test
  void metadataConstraintsAreIndividualIssues() {
    var d = metadata();
    d.put("title", "x".repeat(181));
    d.put("aiGenerated", false);
    var r =
        new StockMetadataValidator().validate(d, profile(), Map.of("description", "blue texture"));
    assertThat(r.status()).isEqualTo("FAIL");
    assertThat(r.issues())
        .extracting(StockMetadataValidator.Issue::code)
        .contains("LENGTH", "AI_DISCLOSURE_REQUIRED");
  }

  @Test
  void metadataRiskCannotBecomeCommercialAutomatically() {
    var d = metadata();
    d.put("riskFlags", List.of("VISIBLE_LOGO"));
    d.put("contentType", "COMMERCIAL");
    assertThat(new StockMetadataValidator().validate(d, profile(), Map.of()).valid()).isFalse();
  }

  @Test
  void validMetadataAndUnsupportedClaims() {
    var d = metadata();
    assertThat(
            new StockMetadataValidator()
                .validate(d, profile(), Map.of("description", "blue texture"))
                .valid())
        .isTrue();
    d.put("description", "An image shot on an invented camera in Paris.");
    assertThat(new StockMetadataValidator().validate(d, profile(), Map.of()).valid()).isFalse();
  }

  @Test
  void csvRoundTripFiftyRowsEscapesUnicodeQuotesAndNewlines() throws Exception {
    var d = metadata();
    d.put("title", "Blue, \"quiet\" café");
    d.put("description", "First line\r\nSecond line – 雪");
    d.put("keywords", List.of(Map.of("value", "blue sky"), Map.of("value", "quiet, calm")));
    var items = new ArrayList<Map<String, Object>>();
    for (int i = 0; i < 50; i++) items.add(Map.of("filename", i + ".jpg", "metadata", d));
    var p =
        Map.<String, Object>of(
            "columns",
            List.of(
                "filename",
                "title",
                "description",
                "keywords",
                "category",
                "ai_generated",
                "content_type"),
            "delimiter",
            ",",
            "keywordSeparator",
            "|",
            "categoryMapping",
            Map.of("ABSTRACT", "Abstract art"));
    byte[] csv = new GenericStockExportAdapter().csv(items, p);
    try (var parser =
        CSVParser.parse(
            new String(csv, StandardCharsets.UTF_8),
            CSVFormat.RFC4180.builder().setHeader().setSkipHeaderRecord(true).get())) {
      var rows = parser.getRecords();
      assertThat(rows).hasSize(50);
      assertThat(rows.getFirst().get("title")).isEqualTo(d.get("title"));
      assertThat(rows.getFirst().get("description")).isEqualTo(d.get("description"));
      assertThat(rows.getFirst().get("keywords")).isEqualTo("blue sky|quiet, calm");
      assertThat(rows.getFirst().get("category")).isEqualTo("Abstract art");
    }
  }

  @Test
  void csvNeutralizesSpreadsheetFormulaAndEmptyOptional() {
    var d = metadata();
    d.put("title", "=SUM(A1:A2)");
    d.put("description", "");
    byte[] csv =
        new GenericStockExportAdapter()
            .csv(
                List.of(Map.of("filename", "safe.jpg", "metadata", d)),
                Map.of("columns", List.of("filename", "title", "description"), "delimiter", ";"));
    assertThat(new String(csv, StandardCharsets.UTF_8)).contains("'=SUM(A1:A2)").contains("\r\n");
  }
}
