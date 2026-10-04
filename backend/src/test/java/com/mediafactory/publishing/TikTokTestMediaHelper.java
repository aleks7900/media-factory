package com.mediafactory.publishing;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class TikTokTestMediaHelper {

  private TikTokTestMediaHelper() {}

  public static byte[] createValidMp4(int durationSeconds, int width, int height, int payloadSize) {
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
    ByteBuffer mvhd = ByteBuffer.allocate(108).order(ByteOrder.BIG_ENDIAN);
    mvhd.putInt(108);
    mvhd.put(new byte[] {'m', 'v', 'h', 'd'});
    mvhd.put((byte) 0);
    mvhd.put(new byte[] {0, 0, 0});
    mvhd.putInt(0);
    mvhd.putInt(0);
    int timescale = 1000;
    mvhd.putInt(timescale);
    mvhd.putInt(durationSeconds * timescale);
    mvhd.putInt(0x00010000);
    mvhd.putShort((short) 0x0100);
    mvhd.put(new byte[10]);
    mvhd.putInt(0x00010000); mvhd.putInt(0); mvhd.putInt(0);
    mvhd.putInt(0); mvhd.putInt(0x00010000); mvhd.putInt(0);
    mvhd.putInt(0); mvhd.putInt(0); mvhd.putInt(0x40000000);
    mvhd.put(new byte[24]);
    mvhd.putInt(2);

    ByteBuffer tkhd = ByteBuffer.allocate(92).order(ByteOrder.BIG_ENDIAN);
    tkhd.putInt(92);
    tkhd.put(new byte[] {'t', 'k', 'h', 'd'});
    tkhd.put((byte) 0);
    tkhd.put(new byte[] {0, 0, 1});
    tkhd.putInt(0);
    tkhd.putInt(0);
    tkhd.putInt(1);
    tkhd.putInt(0);
    tkhd.putInt(durationSeconds * timescale);
    tkhd.put(new byte[8]);
    tkhd.putShort((short) 0);
    tkhd.putShort((short) 0);
    tkhd.putShort((short) 0);
    tkhd.putShort((short) 0);
    tkhd.putInt(0x00010000); tkhd.putInt(0); tkhd.putInt(0);
    tkhd.putInt(0); tkhd.putInt(0x00010000); tkhd.putInt(0);
    tkhd.putInt(0); tkhd.putInt(0); tkhd.putInt(0x40000000);
    tkhd.putInt(width << 16);
    tkhd.putInt(height << 16);

    int trakSize = 8 + 92;
    ByteBuffer trak = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN);
    trak.putInt(trakSize);
    trak.put(new byte[] {'t', 'r', 'a', 'k'});

    int moovSize = 8 + 108 + trakSize;
    ByteBuffer moov = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN);
    moov.putInt(moovSize);
    moov.put(new byte[] {'m', 'o', 'o', 'v'});

    out.writeBytes(moov.array());
    out.writeBytes(mvhd.array());
    out.writeBytes(trak.array());
    out.writeBytes(tkhd.array());

    // 3. mdat box
    int pad = Math.max(1024, payloadSize);
    ByteBuffer mdat = ByteBuffer.allocate(8 + pad).order(ByteOrder.BIG_ENDIAN);
    mdat.putInt(8 + pad);
    mdat.put(new byte[] {'m', 'd', 'a', 't'});
    out.writeBytes(mdat.array());

    return out.toByteArray();
  }

  public static byte[] zip(Map<String, byte[]> files) throws IOException {
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
}
