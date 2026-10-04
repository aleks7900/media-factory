package com.mediafactory.bulk;

import static org.assertj.core.api.Assertions.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import org.junit.jupiter.api.Test;

class BulkArchiveParserTest {
  BulkArchiveParser parser =
      new BulkArchiveParser(
          new BulkArchiveParser.Limits(
              1_000_000, 2_000_000, 100_000, 1000, 500, 2, 500, 10_000, 1_000_000));

  byte[] zip(Map<String, byte[]> files) throws IOException {
    var out = new ByteArrayOutputStream();
    try (var zip = new ZipOutputStream(out)) {
      for (var file : files.entrySet()) {
        zip.putNextEntry(new ZipEntry(file.getKey()));
        zip.write(file.getValue());
        zip.closeEntry();
      }
    }
    return out.toByteArray();
  }

  byte[] text(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  BulkArchiveParser.Parsed parse(Map<String, byte[]> files) throws IOException {
    return parser.parse(new ByteArrayInputStream(zip(files)));
  }

  @Test
  void importsMoreThanOneHundredIndependentTasksAndIsolatesInvalid() throws Exception {
    var files = new LinkedHashMap<String, byte[]>();
    for (int n = 0; n < 125; n++) files.put("car-" + n + "/task.md", text("Generate car " + n));
    files.put("invalid/readme.exe", text("Unsupported"));
    var result = parse(files);
    assertThat(result.tasks()).hasSize(126);
    assertThat(result.tasks().stream().filter(BulkArchiveParser.Task::valid)).hasSize(125);
    assertThat(result.tasks().getLast().error()).contains("Unsupported");
  }

  @Test
  void flatFilesAreTasksAndInvalidUtf8IsIsolated() throws Exception {
    var result =
        parse(
            Map.of(
                "a.md",
                text("Wolf"),
                "b.txt",
                text("Car"),
                "bad.md",
                new byte[] {(byte) 0xc3, 0x28}));
    assertThat(result.tasks()).hasSize(3);
    assertThat(result.tasks().stream().filter(BulkArchiveParser.Task::valid)).hasSize(2);
  }

  @Test
  void rejectsTraversalAbsoluteNamesAndDeepPaths() {
    for (String path :
        List.of(
            "../evil.md",
            "/evil.md",
            "C:/evil.md",
            "task/../evil.md",
            "task/nested/task.md",
            "task\\task.md"))
      assertThatThrownBy(() -> parse(Map.of(path, text("prompt"))))
          .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsDuplicateTaskNamesAndMixedLayouts() {
    assertThatThrownBy(() -> parse(Map.of("car.md", text("a"), "CAR.txt", text("b"))))
        .hasMessageContaining("Duplicate task");
    assertThatThrownBy(() -> parse(Map.of("car/task.md", text("a"), "root.md", text("b"))))
        .hasMessageContaining("mix");
  }

  @Test
  void rejectsBombAndByteLimits() throws Exception {
    parser =
        new BulkArchiveParser(
            new BulkArchiveParser.Limits(
                1_000_000, 2_000_000, 100_000, 1000, 500, 2, 2, 10_000, 1_000_000));
    assertThatThrownBy(() -> parse(Map.of("a.md", text("a".repeat(10000)))))
        .hasMessageContaining("compression ratio");
    parser =
        new BulkArchiveParser(new BulkArchiveParser.Limits(20, 200, 100, 2, 2, 2, 200, 100, 100));
    assertThatThrownBy(() -> parse(Map.of("a.md", text("hello"))))
        .hasMessageContaining("byte limit");
  }

  @Test
  void rejectsMalformedArchiveAndMissingPrompt() throws Exception {
    assertThatThrownBy(() -> parser.parse(new ByteArrayInputStream(text("not a zip"))))
        .isInstanceOf(IllegalArgumentException.class);
    var result = parse(Map.of("task/image.jpg", text("not an image")));
    assertThat(result.tasks().getFirst().valid()).isFalse();
  }
}
