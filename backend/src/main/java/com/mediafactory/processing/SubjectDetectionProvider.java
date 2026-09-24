package com.mediafactory.processing;

import java.util.*;

public interface SubjectDetectionProvider {
  record FocalRegion(
      double x, double y, double width, double height, String type, double confidence) {}

  Map<String, Object> detect(
      byte[] source, Map<String, Object> profile, List<Map<String, Object>> existingRegions);
}
