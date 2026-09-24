package com.mediafactory.processing;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class SmartCropService {

  private final SubjectDetectionProvider provider;

  public SmartCropService(SubjectDetectionProvider provider) {
    this.provider = provider;
  }

  public Map<String, Object> preview(
      byte[] source, Map<String, Object> profile, List<Map<String, Object>> existing) {
    return provider.detect(source, profile, existing);
  }
}
