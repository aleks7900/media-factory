package com.mediafactory.provider;

import com.mediafactory.provider.ImageOptions.AspectRatio;
import com.mediafactory.provider.ImageOptions.Format;
import com.mediafactory.provider.ImageOptions.Quality;
import com.mediafactory.provider.ProviderTypes.Request;
import com.mediafactory.provider.resilience.ImageGenerationException;
import com.mediafactory.provider.resilience.ImageGenerationException.Type;

import java.util.Set;

public record ProviderCapabilities(Set<AspectRatio> supportedAspectRatios,
                                   Set<Format> supportedFormats,
                                   Set<Quality> supportedQualities, Set<String> supportedSizes,
                                   boolean arbitraryDimensions,
                                   boolean supportsNegativePrompt, boolean supportsSeed,
                                   boolean supportsReferenceImage,
                                   boolean supportsTransparentBackground, int maximumImages) {

  private static void invalid(String message) {
    throw new ImageGenerationException(Type.INVALID_REQUEST, message);
  }

  public void validate(Request r) {
    var o = r.options();
    if (!supportedFormats.contains(o.format()) || !supportedQualities.contains(o.quality())
        || !supportedAspectRatios.contains(o.aspectRatio())) {
      invalid("Unsupported format, quality, or aspect ratio");
    }
    if (!arbitraryDimensions && !supportedSizes.contains(r.width() + "x" + r.height())) {
      invalid("Unsupported dimensions; supported sizes: " + String.join(", ", supportedSizes));
    }
    if (r.width() < 64 || r.width() > 4096 || r.height() < 64 || r.height() > 4096) {
      invalid("Dimensions must be between 64 and 4096");
    }
    if (o.negativePrompt() != null && !o.negativePrompt().isBlank() && !supportsNegativePrompt) {
      invalid("Negative prompts are not supported");
    }
    if (o.seed() != null && !supportsSeed) {
      invalid("Seed is not supported");
    }
    if (o.referenceImage() != null && !supportsReferenceImage) {
      invalid("Reference images are not supported by this generation endpoint");
    }
    if (o.transparentBackground() && (!supportsTransparentBackground || o.format() != Format.PNG)) {
      invalid("Transparency requires a supporting provider and PNG format");
    }
    if (o.numberOfImages() != 1 || maximumImages < 1) {
      invalid("This pipeline supports exactly one image per generation");
    }
    boolean matching = switch (o.aspectRatio()) {
      case SQUARE -> r.width() == r.height();
      case PORTRAIT -> r.width() * 3 == r.height() * 2;
      case LANDSCAPE -> r.width() * 2 == r.height() * 3;
      case CUSTOM -> true;
    };
    if (!matching) {
      invalid("Dimensions do not match the requested aspect ratio");
    }
  }
}
