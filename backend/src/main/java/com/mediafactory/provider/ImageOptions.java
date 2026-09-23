package com.mediafactory.provider;

/** Canonical parameters shared by the API, router and adapters. No vendor DTOs or secrets. */
public record ImageOptions(String provider, String model, AspectRatio aspectRatio, Quality quality,
                           Format format, String negativePrompt, Long seed, String referenceImage,
                           boolean transparentBackground, int numberOfImages) {
    public enum AspectRatio { SQUARE, PORTRAIT, LANDSCAPE, CUSTOM }
    public enum Quality { AUTO, LOW, MEDIUM, HIGH }
    public enum Format { PNG, JPEG }
    public ImageOptions {
        quality = quality == null ? Quality.AUTO : quality;
        format = format == null ? Format.PNG : format;
        aspectRatio = aspectRatio == null ? AspectRatio.CUSTOM : aspectRatio;
        numberOfImages = numberOfImages == 0 ? 1 : numberOfImages;
    }
    public static ImageOptions defaults() { return new ImageOptions(null,null,null,null,null,null,null,null,false,1); }
    public ImageOptions withModel(String selected) {
        return new ImageOptions(provider,selected,aspectRatio,quality,format,negativePrompt,seed,referenceImage,transparentBackground,numberOfImages);
    }
}
