package com.mediafactory.publishing.archive;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Lightweight, zero-dependency MP4 ISO-BMFF box parser for validating container signatures,
 * duration, and dimensions.
 */
public class Mp4MetadataParser {

  public record Mp4Info(
      boolean validContainer,
      String majorBrand,
      Double durationSeconds,
      Integer width,
      Integer height,
      String error) {}

  private static final byte[] FTYP = new byte[] {'f', 't', 'y', 'p'};

  public static boolean isMp4(byte[] bytes) {
    if (bytes == null || bytes.length < 12) {
      return false;
    }
    return bytes[4] == 'f' && bytes[5] == 't' && bytes[6] == 'y' && bytes[7] == 'p';
  }

  public static Mp4Info parse(byte[] bytes) {
    if (!isMp4(bytes)) {
      return new Mp4Info(false, null, null, null, null, "File lacks standard MP4/ftyp header");
    }

    String brand = new String(Arrays.copyOfRange(bytes, 8, 12), java.nio.charset.StandardCharsets.US_ASCII);

    Double duration = null;
    Integer width = null;
    Integer height = null;

    try {
      ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
      int offset = 0;
      int limit = bytes.length;

      while (offset + 8 <= limit) {
        long boxSize = Integer.toUnsignedLong(buf.getInt(offset));
        String boxType = new String(bytes, offset + 4, 4, java.nio.charset.StandardCharsets.US_ASCII);

        if (boxSize == 0) {
          // Box extends to end of file
          boxSize = limit - offset;
        } else if (boxSize == 1) {
          // 64-bit extended size
          if (offset + 16 > limit) break;
          boxSize = buf.getLong(offset + 8);
          if (boxSize < 16) break;
        } else if (boxSize < 8) {
          break;
        }

        if (offset + boxSize > limit) {
          // truncated or partial box
          break;
        }

        if ("moov".equals(boxType)) {
          // Search inside moov
          int moovOffset = offset + 8;
          int moovEnd = (int) (offset + boxSize);
          var insideMoov = parseMoov(bytes, moovOffset, moovEnd);
          if (insideMoov.durationSeconds() != null) duration = insideMoov.durationSeconds();
          if (insideMoov.width() != null) width = insideMoov.width();
          if (insideMoov.height() != null) height = insideMoov.height();
          break; // Usually moov is all we need
        }

        offset += (int) boxSize;
      }
    } catch (Exception e) {
      // Non-fatal if detailed boxes are non-standard or fragmented
    }

    return new Mp4Info(true, brand, duration, width, height, null);
  }

  private static Mp4Info parseMoov(byte[] bytes, int start, int end) {
    ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
    int offset = start;
    Double duration = null;
    Integer width = null;
    Integer height = null;

    while (offset + 8 <= end) {
      long boxSize = Integer.toUnsignedLong(buf.getInt(offset));
      if (boxSize < 8 || offset + boxSize > end) break;
      String boxType = new String(bytes, offset + 4, 4, java.nio.charset.StandardCharsets.US_ASCII);

      if ("mvhd".equals(boxType)) {
        // mvhd box
        int version = bytes[offset + 8] & 0xFF;
        if (version == 0 && offset + 8 + 24 <= end) {
          long timescale = Integer.toUnsignedLong(buf.getInt(offset + 8 + 12));
          long durationUnits = Integer.toUnsignedLong(buf.getInt(offset + 8 + 16));
          if (timescale > 0) {
            duration = (double) durationUnits / (double) timescale;
          }
        } else if (version == 1 && offset + 8 + 36 <= end) {
          long timescale = Integer.toUnsignedLong(buf.getInt(offset + 8 + 20));
          long durationUnits = buf.getLong(offset + 8 + 24);
          if (timescale > 0) {
            duration = (double) durationUnits / (double) timescale;
          }
        }
      } else if ("trak".equals(boxType)) {
        // trak box may contain tkhd
        var trackDims = parseTrak(bytes, offset + 8, (int) (offset + boxSize));
        if (trackDims != null && trackDims.width != null && trackDims.width > 0) {
          width = trackDims.width;
          height = trackDims.height;
        }
      }

      offset += (int) boxSize;
    }

    return new Mp4Info(true, null, duration, width, height, null);
  }

  private record Dims(Integer width, Integer height) {}

  private static Dims parseTrak(byte[] bytes, int start, int end) {
    ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
    int offset = start;

    while (offset + 8 <= end) {
      long boxSize = Integer.toUnsignedLong(buf.getInt(offset));
      if (boxSize < 8 || offset + boxSize > end) break;
      String boxType = new String(bytes, offset + 4, 4, java.nio.charset.StandardCharsets.US_ASCII);

      if ("tkhd".equals(boxType)) {
        int version = bytes[offset + 8] & 0xFF;
        // width and height are 16.16 fixed-point numbers at the end of tkhd
        if (version == 0 && offset + 8 + 84 <= end) {
          int w = buf.getInt(offset + 8 + 76) >> 16;
          int h = buf.getInt(offset + 8 + 80) >> 16;
          if (w > 0 && h > 0) return new Dims(w, h);
        } else if (version == 1 && offset + 8 + 96 <= end) {
          int w = buf.getInt(offset + 8 + 88) >> 16;
          int h = buf.getInt(offset + 8 + 92) >> 16;
          if (w > 0 && h > 0) return new Dims(w, h);
        }
      }
      offset += (int) boxSize;
    }
    return null;
  }
}
