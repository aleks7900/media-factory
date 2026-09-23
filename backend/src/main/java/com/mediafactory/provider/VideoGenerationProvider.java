package com.mediafactory.provider;
import com.mediafactory.provider.ProviderTypes.*;
public interface VideoGenerationProvider { Result<Media> generateVideo(Request request); }
