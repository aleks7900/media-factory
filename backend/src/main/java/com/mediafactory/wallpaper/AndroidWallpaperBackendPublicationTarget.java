package com.mediafactory.wallpaper;

import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Disabled integration boundary until an actual, reviewed Android HTTP contract exists.
 */
@Component
public class AndroidWallpaperBackendPublicationTarget implements WallpaperPublicationTarget {

  public String key() {
    return "ANDROID";
  }

  public boolean available() {
    return false;
  }

  public Result publish(Request r) {
    throw new Failure("ANDROID_CONTRACT_NOT_CONFIGURED", false);
  }

  public Result update(Request r) {
    throw new Failure("ANDROID_CONTRACT_NOT_CONFIGURED", false);
  }

  public Result unpublish(Reference r) {
    throw new Failure("ANDROID_CONTRACT_NOT_CONFIGURED", false);
  }

  public interface Authentication {

    Map<String, String> requestHeaders();
  }

  public interface AssetTransfer {

    Receipt transfer(Asset asset, String idempotencyKey);

    record Asset(
        String immutableStorageReference, String sha256, long sizeBytes, String mediaType) {

    }

    record Receipt(String externalReference, String verifiedSha256) {

    }
  }
}
