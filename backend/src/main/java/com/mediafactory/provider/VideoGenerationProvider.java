package com.mediafactory.provider;

import com.mediafactory.provider.ProviderTypes.Media;
import com.mediafactory.provider.ProviderTypes.Request;
import com.mediafactory.provider.ProviderTypes.Result;

public interface VideoGenerationProvider {

  Result<Media> generateVideo(Request request);
}
