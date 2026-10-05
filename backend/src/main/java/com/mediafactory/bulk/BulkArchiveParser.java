package com.mediafactory.bulk;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.nio.file.*;
import java.text.Normalizer;
import java.util.*;
import java.util.zip.*;
import javax.imageio.ImageIO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Reads archive contents as bounded data. Archive paths are never filesystem destinations.
 */
@Component
public class BulkArchiveParser {

  final Limits limits;

  @org.springframework.beans.factory.annotation.Autowired
  public BulkArchiveParser(
      @Value("${bulk.zip.max-archive-bytes:104857600}") long archive,
      @Value("${bulk.zip.max-extracted-bytes:268435456}") long extracted,
      @Value("${bulk.zip.max-entry-bytes:16777216}") int entry,
      @Value("${bulk.zip.max-files:5000}") int files,
      @Value("${bulk.zip.max-tasks:1000}") int tasks,
      @Value("${bulk.zip.max-depth:2}") int depth,
      @Value("${bulk.zip.max-ratio:200}") int ratio,
      @Value("${bulk.zip.max-prompt-bytes:40000}") int prompt,
      @Value("${bulk.zip.max-image-pixels:40000000}") long pixels) {
    this.limits = new Limits(archive, extracted, entry, files, tasks, depth, ratio, prompt, pixels);
  }

  BulkArchiveParser(Limits limits) {
    this.limits = limits;
  }

  static void require(boolean value, String message) {
    if (!value) {
      throw new IllegalArgumentException(message);
    }
  }

  static boolean isIgnoredMetadataOrDocFile(String fileName) {
    String lower = fileName.toLowerCase(Locale.ROOT).trim();
    return lower.equals("readme")
        || lower.matches("^readme\\.(md|txt|markdown|rst|html|pdf)$")
        || lower.equals("license")
        || lower.matches("^license\\.(md|txt)$")
        || lower.equals("metadata.json")
        || lower.equals("manifest.json")
        || lower.equals(".gitignore")
        || lower.equals("package.json")
        || lower.equals("info.txt")
        || lower.equals("notes.txt")
        || lower.equals("notes.md");
  }

  static boolean isIgnoredMetadataOrDocPath(String path) {
    for (String part : path.split("/", -1)) {
      if (isIgnoredMetadataOrDocFile(part)) {
        return true;
      }
    }
    return false;
  }

  static boolean isOsMetadataPart(String part) {
    String lower = part.toLowerCase(Locale.ROOT);
    return lower.equals(".ds_store")
        || lower.equals("thumbs.db")
        || lower.equals("desktop.ini")
        || lower.startsWith("._")
        || lower.equals("__macosx");
  }

  static boolean isOsMetadataPath(String path) {
    for (String part : path.split("/", -1)) {
      if (isOsMetadataPart(part)) {
        return true;
      }
    }
    return false;
  }

  static void copy(InputStream input, OutputStream out, long limit) throws IOException {
    byte[] buffer = new byte[8192];
    long size = 0;
    int n;
    while ((n = input.read(buffer)) != -1) {
      size += n;
      require(size <= limit, "Archive byte limit exceeded");
      out.write(buffer, 0, n);
    }
  }

  public Parsed parse(InputStream input) throws IOException {
    Path spool = Files.createTempFile("media-bulk-", ".zip");
    try {
      try (var out = Files.newOutputStream(spool)) {
        copy(input, out, limits.archiveBytes());
      }
      var entries = new LinkedHashMap<String, byte[]>();
      var names = new HashSet<String>();
      var topDirectories = new LinkedHashSet<String>();
      long total = 0;
      int count = 0;
      try (var zip = new ZipFile(spool.toFile(), StandardCharsets.UTF_8)) {
        var all = zip.entries();
        while (all.hasMoreElements()) {
          var e = all.nextElement();
          require(++count <= limits.files(), "Archive file count exceeded");
          String name = Normalizer.normalize(e.getName(), Normalizer.Form.NFC);
          if (isOsMetadataPath(name) || isIgnoredMetadataOrDocPath(name)) {
            continue;
          }
          require(
              !name.isBlank()
                  && !name.startsWith("/")
                  && !name.contains("\\")
                  && !name.contains(":")
                  && !name.chars().anyMatch(c -> c < 32 || c == 127),
              "Unsafe archive path");
          String path = e.isDirectory() ? name.substring(0, name.length() - 1) : name;
          var parts = path.split("/", -1);
          require(parts.length <= limits.depth(), "Archive nesting limit exceeded");
          for (String part : parts) {
            require(
                !part.isBlank() && !part.equals(".") && !part.equals("..") && part.length() <= 200,
                "Unsafe archive path");
          }
          require(names.add(path.toLowerCase(Locale.ROOT)), "Duplicate archive path");
          if (e.isDirectory()) {
            topDirectories.add(parts[0]);
            continue;
          }
          require(
              e.getSize() >= 0 && e.getSize() <= limits.entryBytes(),
              "Archive entry size exceeded");
          require(
              e.getCompressedSize() >= 0 || e.getCompressedSize() == -1 || e.getSize() == 0,
              "Invalid compressed entry");
          if (e.getCompressedSize() > 0) {
            require(
                e.getSize() <= Math.max(1, e.getCompressedSize()) * limits.ratio(),
                "Archive compression ratio exceeded");
          }
          total += e.getSize();
          require(total <= limits.extractedBytes(), "Extracted archive size exceeded");
          var out = new ByteArrayOutputStream();
          try (var stream = zip.getInputStream(e)) {
            copy(stream, out, Math.min(limits.entryBytes(), e.getSize()));
          }
          byte[] bytes = out.toByteArray();
          var crc = new CRC32();
          crc.update(bytes);
          require(
              bytes.length == e.getSize() && crc.getValue() == e.getCrc(),
              "Archive entry integrity failed");
          entries.put(path, bytes);
        }
      } catch (ZipException error) {
        throw new IllegalArgumentException("Malformed or unsupported ZIP", error);
      }
      require(!entries.isEmpty() || !topDirectories.isEmpty(), "Archive contains no tasks");
      boolean directories =
          !topDirectories.isEmpty() || entries.keySet().stream().anyMatch(n -> n.contains("/"));
      var groups = new LinkedHashMap<String, Map<String, byte[]>>();
      topDirectories.forEach(name -> groups.put(name, new LinkedHashMap<>()));
      for (var e : entries.entrySet()) {
        int slash = e.getKey().indexOf('/');
        if (directories) {
          require(slash > 0, "Do not mix task directories with root files");
          String task = e.getKey().substring(0, slash);
          groups
              .computeIfAbsent(task, k -> new LinkedHashMap<>())
              .put(e.getKey().substring(slash + 1), e.getValue());
        } else {
          String name = e.getKey();
          int dot = name.lastIndexOf('.');
          String task = dot > 0 ? name.substring(0, dot) : name;
          require(!groups.containsKey(task), "Duplicate task name");
          groups.put(task, Map.of(name, e.getValue()));
        }
      }
      require(groups.size() <= limits.tasks(), "Task count exceeded");
      var result = new ArrayList<Task>();
      int references = 0;
      var taskNames = new HashSet<String>();
      for (var group : groups.entrySet()) {
        require(taskNames.add(group.getKey().toLowerCase(Locale.ROOT)), "Duplicate task name");
        var refs = new ArrayList<Reference>();
        String prompt = null, error = null;
        try {
          // Sort files so task.md is processed before task.txt if both exist
          var sortedFiles = new ArrayList<>(group.getValue().entrySet());
          sortedFiles.sort(Comparator.comparingInt(f -> {
            String l = f.getKey().toLowerCase(Locale.ROOT);
            if (l.equals("task.md") || l.equals("prompt.md")) {
              return 0;
            }
            if (l.equals("task.txt") || l.equals("prompt.txt")) {
              return 1;
            }
            return 2;
          }));
          for (var file : sortedFiles) {
            String name = file.getKey(), lower = name.toLowerCase(Locale.ROOT);
            if (isOsMetadataPart(name)) {
              continue;
            }
            byte[] bytes = file.getValue();
            boolean promptFile =
                directories
                    ? (lower.equals("task.md")
                       || lower.equals("task.txt")
                       || lower.equals("prompt.md")
                       || lower.equals("prompt.txt"))
                    : lower.endsWith(".md") || lower.endsWith(".txt");
            if (promptFile) {
              if (prompt != null && (lower.equals("task.txt") || lower.equals("prompt.txt"))) {
                // Keep earlier prompt if task.md was already parsed
                continue;
              }
              require(bytes.length <= limits.promptBytes(), "Prompt exceeds size limit");
              prompt =
                  StandardCharsets.UTF_8
                      .newDecoder()
                      .onMalformedInput(CodingErrorAction.REPORT)
                      .decode(ByteBuffer.wrap(bytes))
                      .toString()
                      .replaceFirst("^\\uFEFF", "");
              require(
                  !prompt.isBlank() && !prompt.contains("\u0000"), "Prompt is empty or invalid");
            } else if (directories
                && (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg"))) {
              String type = imageType(bytes);
              refs.add(new Reference(name, type, bytes));
            } else {
              throw new IllegalArgumentException("Unsupported task file: " + name);
            }
          }
          require(prompt != null, "Missing task.md or prompt file");
        } catch (IllegalArgumentException | IOException invalid) {
          error =
              invalid instanceof CharacterCodingException
                  ? "Prompt must be UTF-8"
                  : invalid.getMessage();
        }
        references += refs.size();
        result.add(
            new Task(group.getKey(), prompt == null ? "" : prompt, List.copyOf(refs), error));
      }
      return new Parsed(List.copyOf(result), total, references);
    } finally {
      Files.deleteIfExists(spool);
    }
  }

  String imageType(byte[] bytes) throws IOException {
    try (var stream = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
      var readers = ImageIO.getImageReaders(stream);
      require(readers.hasNext(), "Reference is not a supported image");
      var reader = readers.next();
      try {
        reader.setInput(stream);
        String format = reader.getFormatName().toLowerCase(Locale.ROOT);
        require(Set.of("png", "jpeg", "jpg").contains(format), "Unsupported image format");
        require(
            (long) reader.getWidth(0) * reader.getHeight(0) <= limits.pixels(),
            "Reference pixel limit exceeded");
        require(reader.read(0) != null, "Invalid reference image");
        return format.equals("png") ? "image/png" : "image/jpeg";
      } finally {
        reader.dispose();
      }
    }
  }

  public record Limits(
      long archiveBytes,
      long extractedBytes,
      int entryBytes,
      int files,
      int tasks,
      int depth,
      int ratio,
      int promptBytes,
      long pixels) {

  }

  public record Reference(String name, String mediaType, byte[] bytes) {

  }

  public record Task(String name, String prompt, List<Reference> references, String error) {

    public boolean valid() {
      return error == null;
    }
  }

  public record Parsed(List<Task> tasks, long extractedBytes, int referenceCount) {

  }
}
