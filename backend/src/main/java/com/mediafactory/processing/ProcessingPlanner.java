package com.mediafactory.processing;

import static com.mediafactory.processing.ProcessingJson.canonical;
import static com.mediafactory.processing.ProcessingJson.integer;
import static com.mediafactory.processing.ProcessingJson.map;
import static com.mediafactory.processing.ProcessingJson.number;

import com.mediafactory.similarity.PerceptualHash;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class ProcessingPlanner {

  public static final String ENGINE = "processing-v1-pillow11.3-spandrel0.4.1";

  public static String hash(Object value) {
    return PerceptualHash.sha(canonical(value).getBytes(StandardCharsets.UTF_8));
  }

  public static void dimensions(int w, int h) {
    if (w < 1 || h < 1 || w > 8192 || h > 8192 || (long) w * h > 64000000) {
      throw new IllegalArgumentException("Output exceeds processing dimensions limit");
    }
  }

  public void validate(Map<String, Object> p) {
    if (!Set.of("FIT", "FILL", "SMART_FILL", "EXACT", "PRESERVE").contains(p.get("mode"))
        || !Set.of("JPEG", "PNG", "WEBP").contains(p.get("format"))) {
      throw new IllegalArgumentException("Unsupported processing mode or format");
    }
    dimensions(integer(p, "width", 0), integer(p, "height", 0));
    int quality = integer(p, "quality", 95), minimum = integer(p, "minimumQuality", 88);
    if (minimum < 1
        || quality > 100
        || minimum > quality
        || integer(p, "maxBytes", 20971520) < 1
        || integer(p, "maxBytes", 20971520) > 67108864) {
      throw new IllegalArgumentException("Invalid output quality or byte limit");
    }
    for (String key : List.of("padding", "safeTop", "safeBottom", "denoise", "sharpen")) {
      if (!Double.isFinite(number(p, key, 0)) || number(p, key, 0) < 0 || number(p, key, 0) > 1) {
        throw new IllegalArgumentException("Invalid " + key);
      }
    }
    if (number(p, "safeTop", 0) + number(p, "safeBottom", 0) >= 1
        || number(p, "minimumMegapixels", 0) < 0
        || number(p, "minimumMegapixels", 0) > 64) {
      throw new IllegalArgumentException("Invalid safe zone or megapixels");
    }
    if (!Set.of("LETTERBOX", "NEEDS_REVIEW", "SKIP")
        .contains(p.getOrDefault("unsafeCrop", "NEEDS_REVIEW"))) {
      throw new IllegalArgumentException("Invalid crop policy");
    }
    if (integer(p, "compressionLevel", 6) < 0
        || integer(p, "compressionLevel", 6) > 9
        || !Set.of(0, 1, 2).contains(integer(p, "subsampling", 0))) {
      throw new IllegalArgumentException("Invalid encoder options");
    }
    if (!"sRGB".equals(p.getOrDefault("colorSpace", "sRGB"))
        || !"PUBLIC_STRIP".equals(p.getOrDefault("metadataPolicy", "PUBLIC_STRIP"))) {
      throw new IllegalArgumentException(
          "Only sRGB and PUBLIC_STRIP metadata policy are supported");
    }
  }

  public int scale(int w, int h, Map<String, Object> p) {
    validate(p);
    double need =
        Math.max(
            (double) integer(p, "minimumWidth", 0) / w,
            (double) integer(p, "minimumHeight", 0) / h);
    need =
        Math.max(need, Math.sqrt(number(p, "minimumMegapixels", 0) * 1000000 / ((double) w * h)));
    String mode = p.get("mode").toString();
    if (!mode.equals("PRESERVE")) {
      double x = (double) integer(p, "width", w) / w, y = (double) integer(p, "height", h) / h;
      need = Math.max(need, mode.equals("FIT") ? Math.min(x, y) : Math.max(x, y));
    }
    int factor = need <= 1 ? 1 : need <= 2 ? 2 : need <= 4 ? 4 : 0;
    if (factor == 0) {
      throw new IllegalArgumentException("Target requires more than 4x upscale");
    }
    dimensions(w * factor, h * factor);
    return factor;
  }

  public Map<String, Object> plan(
      UUID source,
      String checksum,
      int w,
      int h,
      List<Map<String, Object>> profiles,
      Map<String, Object> manual) {
    if (!profiles.stream().map(p -> p.get("key").toString()).toList()
        .containsAll(manual.keySet())) {
      throw new IllegalArgumentException("Manual crop must match a selected profile");
    }
    for (var p : profiles) {
      if (manual.containsKey(p.get("key"))) {
        var r = map(manual.get(p.get("key")));
        double x = number(r, "x", -1),
            y = number(r, "y", -1),
            cw = number(r, "width", 0),
            ch = number(r, "height", 0);
        if (!Double.isFinite(x + y + cw + ch)
            || x < 0
            || y < 0
            || cw <= 0
            || ch <= 0
            || x + cw > 1.000001
            || y + ch > 1.000001) {
          throw new IllegalArgumentException("Invalid normalized manual crop");
        }
        var definition = map(p.get("definition"));
        if (!Set.of("FILL", "SMART_FILL").contains(definition.get("mode"))
            || Math.abs(
            cw * w / (ch * h)
                - (double) integer(definition, "width", 1)
                / integer(definition, "height", 1))
            > .01) {
          throw new IllegalArgumentException("Manual crop must have the target aspect ratio");
        }
      }
    }
    int factor =
        profiles.stream().mapToInt(p -> branchScale(w, h, p, manual)).max().orElse(1);
    dimensions(w * factor, h * factor);
    var nodes = new ArrayList<Map<String, Object>>();
    String upscaleKey = "upscale-" + factor;
    boolean gpu =
        profiles.stream()
            .anyMatch(p -> Boolean.TRUE.equals(map(p.get("definition")).get("requireGpu")));
    nodes.add(
        Map.of(
            "key",
            upscaleKey,
            "operation",
            "UPSCALE",
            "dependsOn",
            List.of(),
            "scale",
            factor,
            "requireGpu",
            gpu,
            "model",
            factor == 2
                ? "RealESRGAN_x2plus:v0.2.1:49fafd45f8fd7aa8d31ab2a22d14d91b536c34494a5cfe31eb5d89c2fa266abb"
                : "realesr-general-x4v3:v0.2.5.0:8dc7edb9ac80ccdc30c3a5dca6616509367f05fbc184ad95b731f05bece96292"));
    for (var p : profiles) {
      var node = new LinkedHashMap<String, Object>();
      node.put("key", p.get("key"));
      node.put("operation", "DERIVE");
      // Small previews derive directly from the original, avoiding unnecessary neural modification.
      int needed = branchScale(w, h, p, manual);
      node.put("dependsOn", needed > 1 ? List.of(upscaleKey) : List.of());
      node.put("profile", map(p.get("definition")));
      node.put("profileVersionId", p.get("id").toString());
      node.put("profileVersion", p.get("version"));
      if (manual.containsKey(p.get("key"))) {
        node.put("manualCrop", manual.get(p.get("key")));
      }
      nodes.add(node);
    }
    return Map.of(
        "sourceAssetId",
        source.toString(),
        "sourceChecksum",
        checksum,
        "sourceWidth",
        w,
        "sourceHeight",
        h,
        "engineVersion",
        ENGINE,
        "nodes",
        nodes,
        "humanOverride",
        !manual.isEmpty());
  }

  private int branchScale(int width, int height, Map<String, Object> profile,
      Map<String, Object> manual) {
    var definition = map(profile.get("definition"));
    if (!manual.containsKey(profile.get("key"))) {
      return scale(width, height, definition);
    }
    var rectangle = map(manual.get(profile.get("key")));
    return scale(Math.max(1, (int) Math.floor(width * number(rectangle, "width", 1))),
        Math.max(1, (int) Math.floor(height * number(rectangle, "height", 1))), definition);
  }
}
