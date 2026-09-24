package com.mediafactory.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "media.storage.type", havingValue = "local", matchIfMissing = true)
public class LocalMediaStorage implements MediaStorage {

  private final Path root;

  public LocalMediaStorage(@Value("${media.storage.root}") String root) {
    this.root = Path.of(root).toAbsolutePath().normalize();
  }

  private Path resolve(String key) {
    var p = root.resolve(key).normalize();
    if (!p.startsWith(root) || p.equals(root)) {
      throw new IllegalArgumentException("Invalid storage key");
    }
    return p;
  }

  public void putOriginal(String key, byte[] bytes, String contentType) {
    try {
      var p = resolve(key);
      Files.createDirectories(p.getParent());
      Files.write(p, bytes, StandardOpenOption.CREATE_NEW);
    } catch (IOException e) {
      throw new IllegalStateException("Immutable storage write failed", e);
    }
  }

  public byte[] read(String key) {
    try {
      return Files.readAllBytes(resolve(key));
    } catch (IOException e) {
      throw new IllegalStateException("Media read failed", e);
    }
  }

  public void checkAvailable() {
    try {
      Files.createDirectories(root);
      if (!Files.isWritable(root)) {
        throw new IllegalStateException("Storage is not writable");
      }
    } catch (IOException e) {
      throw new IllegalStateException("Storage is unavailable", e);
    }
  }
}
