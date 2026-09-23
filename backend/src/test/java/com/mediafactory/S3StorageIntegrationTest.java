package com.mediafactory;
import com.mediafactory.storage.S3MediaStorage;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.*;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.regions.Region;
import java.net.URI;
import static org.assertj.core.api.Assertions.*;
@Tag("integration")
@Testcontainers
class S3StorageIntegrationTest {
 @Container static GenericContainer<?> minio=new GenericContainer<>("quay.io/minio/minio:RELEASE.2025-04-22T22-12-26Z")
  .withEnv("MINIO_ROOT_USER","testaccess").withEnv("MINIO_ROOT_PASSWORD","testsecret")
  .withCommand("server","/data").withExposedPorts(9000).waitingFor(Wait.forHttp("/minio/health/live").forPort(9000));
 @Test void s3OriginalsAreCreateOnly() {
  String endpoint="http://"+minio.getHost()+":"+minio.getMappedPort(9000);
  try(var client=S3Client.builder().endpointOverride(URI.create(endpoint)).region(Region.US_EAST_1).forcePathStyle(true)
    .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("testaccess","testsecret"))).build()) {
    client.createBucket(b->b.bucket("test-media"));
  }
  try(var storage=new S3MediaStorage(endpoint,"test-media","testaccess","testsecret")) {
   storage.putOriginal("originals/test.png",new byte[]{1,2,3},"image/png");
   assertThatThrownBy(()->storage.putOriginal("originals/test.png",new byte[]{4},"image/png"))
    .isInstanceOf(software.amazon.awssdk.services.s3.model.S3Exception.class);
   assertThat(storage.read("originals/test.png")).containsExactly(1,2,3);
  }
 }
}
