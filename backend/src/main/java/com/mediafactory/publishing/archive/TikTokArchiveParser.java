package com.mediafactory.publishing.archive;

import com.mediafactory.publishing.model.VideoMetadata;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.imageio.ImageIO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class TikTokArchiveParser {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final long maxArchiveBytes;
  private final long maxExtractedBytes;
  private final int maxEntryBytes;
  private final int maxFiles;
  private final int maxTasks;

  public TikTokArchiveParser(
      @Value("${publishing.zip.max-archive-bytes:104857600}") long maxArchiveBytes,
      @Value("${publishing.zip.max-extracted-bytes:536870912}") long maxExtractedBytes,
      @Value("${publishing.zip.max-entry-bytes:104857600}") int maxEntryBytes,
      @Value("${publishing.zip.max-files:500}") int maxFiles,
      @Value("${publishing.zip.max-tasks:200}") int maxTasks) {
    this.maxArchiveBytes = maxArchiveBytes;
    this.maxExtractedBytes = maxExtractedBytes;
    this.maxEntryBytes = maxEntryBytes;
    this.maxFiles = maxFiles;
    this.maxTasks = maxTasks;
  }

  public record ParsedVideoItem(
      String filename,
      byte[] videoBytes,
      String sha256,
      long sizeBytes,
      Mp4MetadataParser.Mp4Info mp4Info,
      VideoMetadata metadata,
      byte[] thumbnailBytes,
      String validationError) {
    public boolean isValid() {
      return validationError == null;
    }
  }

  public record ParsedArchive(
      Path spoolPath,
      byte[] archiveBytes,
      String archiveSha256,
      int totalEntries,
      List<ParsedVideoItem> items) {}

  static boolean isOsMetadataPath(String path) {
    for (String part : path.split("/", -1)) {
      String lower = part.toLowerCase(Locale.ROOT);
      if (lower.equals(".ds_store")
          || lower.equals("thumbs.db")
          || lower.equals("desktop.ini")
          || lower.startsWith("._")
          || lower.equals("__macosx")) {
        return true;
      }
    }
    return false;
  }

  @SuppressWarnings("unchecked")
  public ParsedArchive parse(InputStream input) throws IOException {
    Path spool = Files.createTempFile("tiktok-bulk-", ".zip");
    ByteArrayOutputStream memoryCopy = new ByteArrayOutputStream();

    long size = 0;
    byte[] buffer = new byte[8192];
    int n;
    try (var fileOut = Files.newOutputStream(spool)) {
      while ((n = input.read(buffer)) != -1) {
        size += n;
        if (size > maxArchiveBytes) {
          Files.deleteIfExists(spool);
          throw new IllegalArgumentException("ZIP archive exceeds size limit of " + maxArchiveBytes + " bytes");
        }
        fileOut.write(buffer, 0, n);
        memoryCopy.write(buffer, 0, n);
      }
    }

    byte[] allArchiveBytes = memoryCopy.toByteArray();
    String archiveSha256 = sha256Hex(allArchiveBytes);

    Map<String, byte[]> rawFiles = new LinkedHashMap<>();
    int fileCount = 0;
    long totalExtracted = 0;

    try (var zip = new ZipFile(spool.toFile(), StandardCharsets.UTF_8)) {
      var entries = zip.entries();
      while (entries.hasMoreElements()) {
        ZipEntry entry = entries.nextElement();
        if (++fileCount > maxFiles) {
          throw new IllegalArgumentException("Archive file count exceeds limit of " + maxFiles);
        }

        String name = Normalizer.normalize(entry.getName(), Normalizer.Form.NFC).replace('\\', '/');
        if (isOsMetadataPath(name) || entry.isDirectory()) {
          continue;
        }

        if (name.startsWith("/") || name.contains("..") || name.contains(":")) {
          throw new IllegalArgumentException("Illegal path traversal detected in ZIP entry: " + name);
        }

        long entrySize = entry.getSize();
        if (entrySize > maxEntryBytes) {
          throw new IllegalArgumentException("ZIP entry " + name + " exceeds maximum entry size");
        }

        byte[] entryBytes;
        try (var is = zip.getInputStream(entry)) {
          entryBytes = is.readNBytes(maxEntryBytes + 1);
        }
        if (entryBytes.length > maxEntryBytes) {
          throw new IllegalArgumentException("ZIP entry " + name + " exceeded maximum size limit");
        }

        totalExtracted += entryBytes.length;
        if (totalExtracted > maxExtractedBytes) {
          throw new IllegalArgumentException("Archive total extracted bytes exceed safety threshold");
        }

        rawFiles.put(name, entryBytes);
      }
    }

    // 1. Check for metadata.json
    Map<String, VideoMetadata> metadataMap = new HashMap<>();
    for (var entry : rawFiles.entrySet()) {
      String base = getBasename(entry.getKey());
      if ("metadata.json".equalsIgnoreCase(base)) {
        try {
          Map<String, Object> rawMap = JSON.readValue(entry.getValue(), Map.class);
          if (rawMap != null) {
            for (var metaEntry : rawMap.entrySet()) {
              if (metaEntry.getValue() instanceof Map<?, ?> m) {
                Map<String, Object> valMap = (Map<String, Object>) m;
                String caption = Objects.toString(valMap.get("caption"), null);
                String privacy = Objects.toString(valMap.get("privacy_level"), null);
                Boolean comment = valMap.containsKey("disable_comment") ? Boolean.valueOf(String.valueOf(valMap.get("disable_comment"))) : null;
                Boolean duet = valMap.containsKey("disable_duet") ? Boolean.valueOf(String.valueOf(valMap.get("disable_duet"))) : null;
                Boolean stitch = valMap.containsKey("disable_stitch") ? Boolean.valueOf(String.valueOf(valMap.get("disable_stitch"))) : null;
                Long coverMs = valMap.containsKey("cover_timestamp_ms") ? ((Number) valMap.get("cover_timestamp_ms")).longValue() : null;

                metadataMap.put(
                    getBasename(metaEntry.getKey()).toLowerCase(Locale.ROOT),
                    new VideoMetadata(caption, privacy, comment, duet, stitch, coverMs));
              }
            }
          }
        } catch (Exception e) {
          throw new IllegalArgumentException("Failed to parse metadata.json in ZIP: " + e.getMessage());
        }
      }
    }

    // 2. Identify video files
    List<ParsedVideoItem> items = new ArrayList<>();
    for (var entry : rawFiles.entrySet()) {
      String path = entry.getKey();
      String filename = getBasename(path);
      if ("metadata.json".equalsIgnoreCase(filename)) {
        continue;
      }

      byte[] bytes = entry.getValue();
      String lower = filename.toLowerCase(Locale.ROOT);
      String validationError = null;

      if (!lower.endsWith(".mp4")) {
        validationError = "Unsupported video format: only .mp4 files are currently supported by TikTok bulk publishing";
      } else if (bytes.length < 1024) {
        validationError = "Video file is too small (minimum 1 KB required)";
      } else if (!Mp4MetadataParser.isMp4(bytes)) {
        validationError = "Invalid MP4 container: missing standard ftyp box";
      }

      Mp4MetadataParser.Mp4Info mp4Info = null;
      if (validationError == null) {
        mp4Info = Mp4MetadataParser.parse(bytes);
        if (mp4Info.durationSeconds() != null) {
          if (mp4Info.durationSeconds() < 3.0) {
            validationError = String.format("Video duration (%.1fs) is below TikTok minimum requirement of 3 seconds", mp4Info.durationSeconds());
          } else if (mp4Info.durationSeconds() > 600.0) {
            validationError = String.format("Video duration (%.1fs) exceeds TikTok maximum duration of 10 minutes", mp4Info.durationSeconds());
          }
        }
        if (validationError == null && mp4Info.width() != null && mp4Info.height() != null) {
          if (mp4Info.width() < 360 || mp4Info.height() < 360) {
            validationError = String.format("Video resolution (%dx%d) is below TikTok minimum of 360p", mp4Info.width(), mp4Info.height());
          }
        }
      }

      VideoMetadata videoMeta = metadataMap.get(lower);
      if (videoMeta == null) {
        String friendlyCaption = friendlyTitle(filename);
        videoMeta = new VideoMetadata(friendlyCaption, "SELF_ONLY", false, false, false, 1000L);
      }

      byte[] thumb = generateThumbnail(filename);

      items.add(
          new ParsedVideoItem(
              filename,
              bytes,
              sha256Hex(bytes),
              bytes.length,
              mp4Info,
              videoMeta,
              thumb,
              validationError));

      if (items.size() > maxTasks) {
        throw new IllegalArgumentException("Number of videos in archive exceeds limit of " + maxTasks);
      }
    }

    if (items.isEmpty()) {
      throw new IllegalArgumentException("No video files found in archive. Please include at least one .mp4 file.");
    }

    return new ParsedArchive(spool, allArchiveBytes, archiveSha256, fileCount, items);
  }

  private static String getBasename(String path) {
    int idx = path.lastIndexOf('/');
    return idx >= 0 ? path.substring(idx + 1) : path;
  }

  private static String friendlyTitle(String filename) {
    String name = filename;
    if (name.toLowerCase(Locale.ROOT).endsWith(".mp4")) {
      name = name.substring(0, name.length() - 4);
    }
    name = name.replace('_', ' ').replace('-', ' ');
    if (name.isBlank()) return "#video";
    return Character.toUpperCase(name.charAt(0)) + name.substring(1);
  }

  public static byte[] generateThumbnail(String title) {
    try {
      int width = 320;
      int height = 180;
      BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
      Graphics2D g2 = img.createGraphics();
      g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

      g2.setColor(new Color(24, 24, 28));
      g2.fillRect(0, 0, width, height);

      g2.setColor(new Color(45, 45, 55));
      g2.drawRect(2, 2, width - 4, height - 4);

      g2.setColor(new Color(254, 44, 85)); // TikTok Pink accent
      g2.fillOval(width / 2 - 20, height / 2 - 25, 40, 40);

      g2.setColor(Color.WHITE);
      int[] xPoints = {width / 2 - 6, width / 2 - 6, width / 2 + 10};
      int[] yPoints = {height / 2 - 15, height / 2 + 5, height / 2 - 5};
      g2.fillPolygon(xPoints, yPoints, 3);

      g2.setFont(new Font("SansSerif", Font.PLAIN, 12));
      String label = title.length() > 30 ? title.substring(0, 27) + "..." : title;
      g2.setColor(new Color(200, 200, 210));
      g2.drawString(label, 12, height - 14);

      g2.dispose();
      ByteArrayOutputStream baos = new ByteArrayOutputStream();
      ImageIO.write(img, "PNG", baos);
      return baos.toByteArray();
    } catch (Exception e) {
      return new byte[0];
    }
  }

  public static String sha256Hex(byte[] data) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] hash = md.digest(data);
      StringBuilder hex = new StringBuilder();
      for (byte b : hash) {
        hex.append(String.format("%02x", b));
      }
      return hex.toString();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
