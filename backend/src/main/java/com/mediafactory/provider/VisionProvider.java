package com.mediafactory.provider;

import com.mediafactory.provider.ProviderTypes.Media;
import com.mediafactory.provider.ProviderTypes.Result;

public interface VisionProvider {

  default java.util.Map<String, String> visionIdentity() {
    throw new IllegalStateException("Vision provider must declare its provider/model identity");
  }

  Result<String> inspect(Media media);
}
