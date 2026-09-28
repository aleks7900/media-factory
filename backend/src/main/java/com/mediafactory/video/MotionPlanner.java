package com.mediafactory.video;

import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class MotionPlanner {
  public Map<String, Object> plan(Map<String, Object> source, Map<String, Object> input) {
    var motion = new LinkedHashMap<String, Object>();
    motion.put("subjectMotion", "subtle floating motion, preserving the supplied subject");
    motion.put("environmentMotion", "minimal ambient movement");
    motion.put("cameraMotion", "STATIC");
    motion.put("motionStrength", "LOW");
    motion.put("motionSpeed", "SLOW");
    motion.put("depthMotion", "NONE");
    motion.put("particleMotion", "none");
    motion.put("lightingMotion", "stable lighting");
    motion.put("loopIntent", true);
    motion.put("reversible", false);
    motion.put("mainSubject", Objects.toString(source.get("concept_name"), "the supplied image"));
    motion.put("style", "preserve the source image style");
    var allowed = new HashSet<>(motion.keySet());
    allowed.add("acknowledgeRiskyMotion");
    if (!allowed.containsAll(input.keySet()))
      throw new IllegalArgumentException("Unknown motion option");
    motion.putAll(input);
    for(String key:List.of("subjectMotion","environmentMotion","cameraMotion","motionStrength","motionSpeed","depthMotion","particleMotion","lightingMotion","mainSubject","style"))
      if(!(motion.get(key) instanceof String))throw new IllegalArgumentException("Motion text must be a string");
    for (var e : motion.entrySet())
      if (e.getValue() instanceof String text && (text.isBlank() || text.length() > 240))
        throw new IllegalArgumentException("Motion text must have 1–240 characters");
    if (!Set.of("STATIC", "PAN_LEFT", "PAN_RIGHT", "PUSH_IN", "PULL_OUT", "ORBIT")
        .contains(motion.get("cameraMotion")))
      throw new IllegalArgumentException("Invalid camera motion");
    if (!Set.of("LOW", "MEDIUM", "HIGH").contains(motion.get("motionStrength"))
        || !Set.of("SLOW", "MEDIUM", "FAST").contains(motion.get("motionSpeed")))
      throw new IllegalArgumentException("Invalid motion intensity");
    for (String k : List.of("loopIntent", "reversible"))
      if (!(motion.get(k) instanceof Boolean))
        throw new IllegalArgumentException("Invalid boolean motion option");
    if ((motion.get("cameraMotion").equals("ORBIT")
            || motion.get("motionStrength").equals("HIGH")
            || motion.get("motionSpeed").equals("FAST"))
        && !Boolean.TRUE.equals(motion.get("acknowledgeRiskyMotion")))
      throw new IllegalArgumentException("Explicit acknowledgement required for risky motion");
    if (Boolean.TRUE.equals(motion.get("reversible"))
        && !motion.get("cameraMotion").equals("STATIC"))
      throw new IllegalArgumentException(
          "Directional camera motion cannot be automatically reversed");
    return motion;
  }

  public Map<String, Object> variables(Map<String, Object> motion) {
    return Map.of(
        "main_subject",
        motion.get("mainSubject"),
        "subject_motion",
        motion.get("subjectMotion"),
        "environment_motion",
        motion.get("environmentMotion")
            + "; depth: "
            + motion.get("depthMotion")
            + "; particles: "
            + motion.get("particleMotion")
            + "; lighting: "
            + motion.get("lightingMotion"),
        "camera_motion",
        motion.get("cameraMotion"),
        "motion_strength",
        motion.get("motionStrength"),
        "motion_speed",
        motion.get("motionSpeed"),
        "loop_intent",
        motion.get("loopIntent").toString(),
        "style",
        motion.get("style"));
  }
}
