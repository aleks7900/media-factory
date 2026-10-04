package com.mediafactory.publishing.archive;

import static org.assertj.core.api.Assertions.*;

import com.mediafactory.publishing.model.VideoMetadata;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TikTokArchiveParserTest {

  private TikTokArchiveParser parser;

  @BeforeEach
  void setUp() {
    parser = new TikTokArchiveParser(10_000_000, 50_000_000, 10_000_000, 1000, 500);
  }

  static byte[] createValidMp4(int durationSeconds, int width, int height, int payloadSize) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();

    // 1. ftyp box (24 bytes)
    ByteBuffer ftyp = ByteBuffer.allocate(24).order(ByteOrder.BIG_ENDIAN);
    ftyp.putInt(24);
    ftyp.put(new byte[] {'f', 't', 'y', 'p'});
    ftyp.put(new byte[] {'i', 's', 'o', 'm'});
    ftyp.putInt(512);
    ftyp.put(new byte[] {'i', 's', 'o', 'm', 'm', 'p', '4', '2'});
    out.writeBytes(ftyp.array());

    // 2. moov box containing mvhd and trak/tkhd
    // mvhd: version 0, 108 bytes total
    ByteBuffer mvhd = ByteBuffer.allocate(108).order(ByteOrder.BIG_ENDIAN);
    mvhd.putInt(108); // size
    mvhd.put(new byte[] {'m', 'v', 'h', 'd'});
    mvhd.put((byte) 0); // version 0
    mvhd.put(new byte[] {0, 0, 0}); // flags
    mvhd.putInt(0); // creation time
    mvhd.putInt(0); // mod time
    int timescale = 1000;
    mvhd.putInt(timescale); // timescale
    mvhd.putInt(durationSeconds * timescale); // duration
    mvhd.putInt(0x00010000); // rate 1.0
    mvhd.putShort((short) 0x0100); // volume 1.0
    mvhd.put(new byte[10]); // reserved
    // 36 bytes matrix
    mvhd.putInt(0x00010000); mvhd.putInt(0); mvhd.putInt(0);
    mvhd.putInt(0); mvhd.putInt(0x00010000); mvhd.putInt(0);
    mvhd.putInt(0); mvhd.putInt(0); mvhd.putInt(0x40000000);
    mvhd.put(new byte[24]); // pre-defined
    mvhd.putInt(2); // next track id

    // tkhd: version 0, 92 bytes total
    ByteBuffer tkhd = ByteBuffer.allocate(92).order(ByteOrder.BIG_ENDIAN);
    tkhd.putInt(92);
    tkhd.put(new byte[] {'t', 'k', 'h', 'd'});
    tkhd.put((byte) 0); // version
    tkhd.put(new byte[] {0, 0, 1}); // flags enabled
    tkhd.putInt(0); // creation
    tkhd.putInt(0); // mod
    tkhd.putInt(1); // track id
    tkhd.putInt(0); // reserved
    tkhd.putInt(durationSeconds * timescale); // duration
    tkhd.put(new byte[8]); // reserved
    tkhd.putShort((short) 0); // layer
    tkhd.putShort((short) 0); // alternate group
    tkhd.putShort((short) 0); // volume
    tkhd.putShort((short) 0); // reserved
    // 36 bytes matrix
    tkhd.putInt(0x00010000); tkhd.putInt(0); tkhd.putInt(0);
    tkhd.putInt(0); tkhd.putInt(0x00010000); tkhd.putInt(0);
    tkhd.putInt(0); tkhd.putInt(0); tkhd.putInt(0x40000000);
    tkhd.putInt(width << 16); // width 16.16
    tkhd.putInt(height << 16); // height 16.16

    // trak box wraps tkhd
    int trakSize = 8 + 92;
    ByteBuffer trak = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN);
    trak.putInt(trakSize);
    trak.put(new byte[] {'t', 'r', 'a', 'k'});

    // moov box wraps mvhd and trak
    int moovSize = 8 + 108 + trakSize;
    ByteBuffer moov = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN);
    moov.putInt(moovSize);
    moov.put(new byte[] {'m', 'o', 'o', 'v'});

    out.writeBytes(moov.array());
    out.writeBytes(mvhd.array());
    out.writeBytes(trak.array());
    out.writeBytes(tkhd.array());

    // 3. mdat box with dummy bytes to meet min size
    int pad = Math.max(1024, payloadSize);
    ByteBuffer mdat = ByteBuffer.allocate(8 + pad).order(ByteOrder.BIG_ENDIAN);
    mdat.putInt(8 + pad);
    mdat.put(new byte[] {'m', 'd', 'a', 't'});
    out.writeBytes(mdat.array());

    return out.toByteArray();
  }

  static byte[] zip(Map<String, byte[]> files) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (ZipOutputStream zos = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
      for (var entry : files.entrySet()) {
        zos.putNextEntry(new ZipEntry(entry.getKey()));
        zos.write(entry.getValue());
        zos.closeEntry();
      }
    }
    return out.toByteArray();
  }

  @Test
  void parsesValidArchiveWithMetadataJson() throws Exception {
    byte[] validVideo1 = createValidMp4(15, 1080, 1920, 2048);
    byte[] validVideo2 = createValidMp4(30, 720, 1280, 2048);

    String metadataJson = """
        {
          "bmw_m4.mp4": {
            "caption": "BMW M4 Competition cinematic #bmw #cars",
            "privacy_level": "PUBLIC_TO_EVERYONE",
            "disable_comment": false,
            "disable_duet": true
          },
          "audi_rs6.mp4": {
            "caption": "Audi RS6 Avant #audi",
            "privacy_level": "SELF_ONLY"
          }
        }
        """;

    Map<String, byte[]> files = new LinkedHashMap<>();
    files.put("bmw_m4.mp4", validVideo1);
    files.put("audi_rs6.mp4", validVideo2);
    files.put("metadata.json", metadataJson.getBytes(StandardCharsets.UTF_8));

    var parsed = parser.parse(new ByteArrayInputStream(zip(files)));

    assertThat(parsed.items()).hasSize(2);

    var item1 = parsed.items().stream().filter(i -> i.filename().equals("bmw_m4.mp4")).findFirst().orElseThrow();
    assertThat(item1.isValid()).isTrue();
    assertThat(item1.metadata().caption()).isEqualTo("BMW M4 Competition cinematic #bmw #cars");
    assertThat(item1.metadata().privacyLevel()).isEqualTo("PUBLIC_TO_EVERYONE");
    assertThat(item1.metadata().disableDuet()).isTrue();
    assertThat(item1.mp4Info().durationSeconds()).isEqualTo(15.0);
    assertThat(item1.mp4Info().width()).isEqualTo(1080);
    assertThat(item1.mp4Info().height()).isEqualTo(1920);

    var item2 = parsed.items().stream().filter(i -> i.filename().equals("audi_rs6.mp4")).findFirst().orElseThrow();
    assertThat(item2.isValid()).isTrue();
    assertThat(item2.metadata().caption()).isEqualTo("Audi RS6 Avant #audi");
    assertThat(item2.metadata().privacyLevel()).isEqualTo("SELF_ONLY");
  }

  @Test
  void parsesArchiveWithoutMetadataUsingFriendlyFallbacks() throws Exception {
    byte[] validVideo = createValidMp4(10, 1080, 1920, 2048);

    Map<String, byte[]> files = Map.of(
        "porsche_911_gt3.mp4", validVideo
    );

    var parsed = parser.parse(new ByteArrayInputStream(zip(files)));

    assertThat(parsed.items()).hasSize(1);
    var item = parsed.items().get(0);
    assertThat(item.isValid()).isTrue();
    assertThat(item.metadata().caption()).isEqualTo("Porsche 911 gt3");
    assertThat(item.metadata().privacyLevel()).isEqualTo("SELF_ONLY");
    assertThat(item.thumbnailBytes()).isNotEmpty();
  }

  @Test
  void identifiesAndIsolatesInvalidVideosWithoutFailingEntireBatch() throws Exception {
    byte[] validVideo = createValidMp4(15, 1080, 1920, 2048);
    byte[] shortVideo = createValidMp4(1, 1080, 1920, 2048); // < 3 seconds
    byte[] nonMp4 = "This is not an mp4".getBytes(StandardCharsets.UTF_8);
    byte[] corruptMp4 = new byte[2048]; // >= 1024 bytes without ftyp box

    Map<String, byte[]> files = new LinkedHashMap<>();
    files.put("good.mp4", validVideo);
    files.put("too_short.mp4", shortVideo);
    files.put("document.pdf", nonMp4);
    files.put("corrupt.mp4", corruptMp4);

    var parsed = parser.parse(new ByteArrayInputStream(zip(files)));

    assertThat(parsed.items()).hasSize(4);

    long validCount = parsed.items().stream().filter(TikTokArchiveParser.ParsedVideoItem::isValid).count();
    assertThat(validCount).isEqualTo(1);

    var shortItem = parsed.items().stream().filter(i -> i.filename().equals("too_short.mp4")).findFirst().orElseThrow();
    assertThat(shortItem.isValid()).isFalse();
    assertThat(shortItem.validationError()).contains("below TikTok minimum requirement of 3 seconds");

    var pdfItem = parsed.items().stream().filter(i -> i.filename().equals("document.pdf")).findFirst().orElseThrow();
    assertThat(pdfItem.isValid()).isFalse();
    assertThat(pdfItem.validationError()).contains("Unsupported video format");

    var corruptItem = parsed.items().stream().filter(i -> i.filename().equals("corrupt.mp4")).findFirst().orElseThrow();
    assertThat(corruptItem.isValid()).isFalse();
    assertThat(corruptItem.validationError()).contains("missing standard ftyp box");
  }

  @Test
  void rejectsPathTraversalAndZipSlip() {
    Map<String, byte[]> evilFiles = Map.of(
        "../escape.mp4", createValidMp4(10, 1080, 1920, 2048)
    );

    assertThatThrownBy(() -> parser.parse(new ByteArrayInputStream(zip(evilFiles))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Illegal path traversal");
  }

  @Test
  void parsesMoreThanOneHundredVideosEfficiently() throws Exception {
    byte[] validVideo = createValidMp4(12, 1080, 1920, 1024);
    Map<String, byte[]> files = new LinkedHashMap<>();

    for (int i = 1; i <= 105; i++) {
      files.put(String.format("video_%03d.mp4", i), validVideo);
    }

    var parsed = parser.parse(new ByteArrayInputStream(zip(files)));

    assertThat(parsed.items()).hasSize(105);
    assertThat(parsed.items().stream().allMatch(TikTokArchiveParser.ParsedVideoItem::isValid)).isTrue();
  }
}
