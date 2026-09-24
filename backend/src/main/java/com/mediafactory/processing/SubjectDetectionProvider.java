package com.mediafactory.processing;

import java.util.List;
import java.util.Map;

public interface SubjectDetectionProvider {

  Map<String, Object> detect(
      byte[] source, Map<String, Object> profile, List<Map<String, Object>> existingRegions);

  record FocalRegion(
      double x, double y, double width, double height, String type, double confidence) {

  }
}
