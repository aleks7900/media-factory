package com.mediafactory.video;

import static com.mediafactory.processing.ProcessingJson.*;

import java.util.*;

public final class VideoProfiles {

  private VideoProfiles() {
  }

  public static Map<String, Object> settings(
      Map<String, Object> base, Map<String, Object> changes) {
    var allowed =
        Set.of(
            "width",
            "height",
            "fps",
            "duration",
            "quality",
            "codec",
            "audioPolicy",
            "encodingMode",
            "cropMode",
            "focalX",
            "focalY",
            "loopStrategy",
            "crossfadeSeconds",
            "stabilize",
            "interpolate",
            "denoise",
            "sharpen",
            "saturation",
            "trimStart",
            "allowCpuFallback",
            "targetBitrate",
            "bitrateMode");
    if (!allowed.containsAll(changes.keySet())) {
      throw new IllegalArgumentException("Unknown video processing setting");
    }
    var p = new LinkedHashMap<>(base);
    p.putAll(changes);
    range(p, "width", 64, 4096);
    range(p, "height", 64, 4096);
    range(p, "fps", 1, 60);
    range(p, "duration", 1, 30);
    range(p, "quality", 0, 40);
    range(p, "targetBitrate", 100000, 30000000);
    range(p, "saturation", .5, 1.5);
    for (String axis : List.of("width", "height")) {
      if (number(p, axis, 0) % 2 != 0) {
        throw new IllegalArgumentException("Video dimensions must be even integers");
      }
    }
    if (number(p, "fps", 0) % 1 != 0
        || number(p, "width", 0) * number(p, "height", 0) > 9_000_000) {
      throw new IllegalArgumentException("Video processing bounds exceeded");
    }
    for (String k : List.of("focalX", "focalY")) {
      range(p, k, 0, 1);
    }
    range(p, "crossfadeSeconds", .1, 2);
    if (number(p, "crossfadeSeconds", .5) * 2 >= number(p, "duration", 5)) {
      throw new IllegalArgumentException("Crossfade is too long");
    }
    for (String k : List.of("denoise", "sharpen", "saturation", "trimStart")) {
      if (p.containsKey(k)) {
        range(
            p,
            k,
            0,
            k.equals("trimStart")
                ? 25
                : k.equals("denoise") ? 3 : k.equals("saturation") ? 1.5 : 1);
      }
    }
    for (var rule :
        Map.of(
                "codec",
                Set.of("H264", "H265", "AV1"),
                "audioPolicy",
                Set.of("KEEP", "REMOVE", "OPTIONAL"),
                "encodingMode",
                Set.of("AUTO", "CPU", "GPU"),
                "loopStrategy",
                Set.of("AUTO", "DIRECT", "CROSSFADE", "PING_PONG"),
                "cropMode",
                Set.of("FIT", "FILL"),
                "bitrateMode",
                Set.of("QUALITY", "BITRATE"))
            .entrySet()) {
      if (!rule.getValue().contains(p.get(rule.getKey()))) {
        throw new IllegalArgumentException("Unsupported " + rule.getKey());
      }
    }
    for (String k : List.of("stabilize", "interpolate", "allowCpuFallback")) {
      if (p.containsKey(k) && !(p.get(k) instanceof Boolean)) {
        throw new IllegalArgumentException("Invalid " + k);
      }
    }
    return p;
  }

  private static void range(Map<String, Object> p, String key, double low, double high) {
    if (!(p.get(key) instanceof Number n)
        || !Double.isFinite(n.doubleValue())
        || n.doubleValue() < low
        || n.doubleValue() > high) {
      throw new IllegalArgumentException("Invalid " + key);
    }
  }
}
