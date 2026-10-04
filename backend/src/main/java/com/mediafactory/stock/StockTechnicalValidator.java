package com.mediafactory.stock;

import static com.mediafactory.processing.ProcessingJson.*;

import com.mediafactory.similarity.PerceptualHash;
import java.io.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Component;

@Component
public class StockTechnicalValidator {

  private static void check(
      List<Check> c, String type, boolean ok, Object actual, Object required) {
    c.add(new Check(type, ok ? "PASS" : "FAIL", actual, required));
  }

  public Result validate(
      byte[] bytes, String checksum, Map<String, Object> p, Map<String, Object> encoder) {
    var checks = new ArrayList<Check>();
    var warnings = new ArrayList<String>();
    check(
        checks,
        "CHECKSUM",
        PerceptualHash.sha(bytes).equals(checksum),
        PerceptualHash.sha(bytes),
        checksum);
    check(
        checks,
        "FILE_SIZE",
        bytes.length > 0 && bytes.length <= number(p, "maximumFileSize", 52428800),
        bytes.length,
        p.get("maximumFileSize"));
    try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
      var readers = ImageIO.getImageReaders(input);
      if (!readers.hasNext()) {
        throw new IOException("Unreadable image");
      }
      var reader = readers.next();
      try {
        reader.setInput(input);
        int w = reader.getWidth(0), h = reader.getHeight(0);
        if (w < 1 || h < 1 || w > 8192 || h > 8192 || (long) w * h > 64000000) {
          throw new IOException("Decode bounds exceeded");
        }
        String format = reader.getFormatName().toUpperCase(Locale.ROOT).replace("JPG", "JPEG");
        check(
            checks,
            "FORMAT",
            ((List<?>) p.get("acceptedFormats")).contains(format),
            format,
            p.get("acceptedFormats"));
        check(
            checks,
            "DIMENSIONS",
            w >= integer(p, "minimumWidth", 1)
                && h >= integer(p, "minimumHeight", 1)
                && w <= integer(p, "maximumWidth", 8192)
                && h <= integer(p, "maximumHeight", 8192),
            List.of(w, h),
            List.of(
                p.get("minimumWidth"),
                p.get("minimumHeight"),
                p.get("maximumWidth"),
                p.get("maximumHeight")));
        double mp = (long) w * h / 1000000.0;
        check(
            checks,
            "MEGAPIXELS",
            mp >= number(p, "minimumMegapixels", 4) && mp <= number(p, "maximumMegapixels", 64),
            mp,
            List.of(p.get("minimumMegapixels"), p.get("maximumMegapixels")));
        String orientation = w == h ? "SQUARE" : w > h ? "LANDSCAPE" : "PORTRAIT";
        check(
            checks,
            "ORIENTATION",
            "ANY".equals(p.get("orientation")) || orientation.equals(p.get("orientation")),
            orientation,
            p.get("orientation"));
        var image = reader.read(0);
        check(
            checks,
            "COLOR_SPACE",
            image.getColorModel().getColorSpace().isCS_sRGB(),
            image.getColorModel().getColorSpace().isCS_sRGB() ? "sRGB" : "OTHER",
            p.get("colorSpace"));
        check(
            checks,
            "ALPHA",
            !image.getColorModel().hasAlpha() || Boolean.TRUE.equals(p.get("allowAlpha")),
            image.getColorModel().hasAlpha(),
            p.get("allowAlpha"));
        check(
            checks,
            "INTEGRITY",
            !format.equals("JPEG")
                || (bytes.length > 2
                && bytes[bytes.length - 2] == (byte) 0xff
                && bytes[bytes.length - 1] == (byte) 0xd9),
            "DECODED",
            "COMPLETE");
        int quality =
            integer(
                encoder,
                "actualQuality",
                integer(encoder, "quality", integer(encoder, "jpegQuality", 0)));
        if (quality == 0) {
          warnings.add(
              "ENCODER_QUALITY_UNKNOWN: JPEG quality cannot be inferred exactly from decoded"
                  + " pixels");
          checks.add(new Check("COMPRESSION", "WARNING", "UNKNOWN", p.get("preferredQuality")));
        } else {
          checks.add(
              new Check(
                  "COMPRESSION",
                  quality >= integer(p, "preferredQuality", 95) ? "PASS" : "WARNING",
                  quality,
                  p.get("preferredQuality")));
        }
      } finally {
        reader.dispose();
      }
    } catch (Exception e) {
      checks.add(new Check("FILE_READABLE", "FAIL", "CORRUPT_OR_UNSUPPORTED", "DECODABLE"));
    }
    return new Result(checks.stream().noneMatch(c -> c.status().equals("FAIL")), checks, warnings);
  }

  public record Check(String type, String status, Object actual, Object required) {

  }

  public record Result(boolean valid, List<Check> checks, List<String> warnings) {

  }
}
