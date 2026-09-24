package com.mediafactory.provider;

import com.mediafactory.provider.ProviderTypes.Media;
import com.mediafactory.provider.ProviderTypes.Result;

public interface UpscaleProvider {

  Result<Media> upscale(Media media);
}
