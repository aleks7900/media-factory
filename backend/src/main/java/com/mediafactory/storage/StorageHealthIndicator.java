package com.mediafactory.storage;
import org.springframework.stereotype.Component;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
@Component("mediaStorage")
public class StorageHealthIndicator implements HealthIndicator {
 private final MediaStorage storage;
 public StorageHealthIndicator(MediaStorage storage) { this.storage=storage; }
 public Health health() {
  try { storage.checkAvailable(); return Health.up().build(); }
  catch(Exception e) { return Health.down().build(); }
 }
}
