package com.mediafactory.storage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.core.sync.RequestBody;
import java.net.URI;
@Component
@ConditionalOnProperty(name="media.storage.type",havingValue="s3")
public class S3MediaStorage implements MediaStorage, AutoCloseable {
 private final S3Client client; private final String bucket;
 public S3MediaStorage(@Value("${media.storage.endpoint}") String endpoint,@Value("${media.storage.bucket}") String bucket,
 @Value("${media.storage.access-key}") String access,@Value("${media.storage.secret-key}") String secret) {
   this.bucket=bucket;
   client=S3Client.builder().endpointOverride(URI.create(endpoint)).region(Region.US_EAST_1).forcePathStyle(true)
     .overrideConfiguration(c->c.apiCallTimeout(java.time.Duration.ofSeconds(30)).apiCallAttemptTimeout(java.time.Duration.ofSeconds(10)))
     .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(access,secret))).build();
 }
 public void putOriginal(String key,byte[] bytes,String type) {
   client.putObject(PutObjectRequest.builder().bucket(bucket).key(key).contentType(type).ifNoneMatch("*").build(),RequestBody.fromBytes(bytes));
 }
 public byte[] read(String key) { return client.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).build()).asByteArray(); }
 public void checkAvailable() { client.headBucket(b->b.bucket(bucket)); }
 public void close() { client.close(); }
}
