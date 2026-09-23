package com.mediafactory;
import com.mediafactory.domain.GenerationStatus;
import com.mediafactory.quality.TechnicalQa;
import com.mediafactory.provider.MockProviders;
import com.mediafactory.provider.ProviderTypes.Request;
import com.mediafactory.storage.LocalMediaStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.*;
class FoundationTest {
 @Test void lifecycleRejectsInvalidTransitions() {
   assertThat(GenerationStatus.CREATED.canTransitionTo(GenerationStatus.QUEUED)).isTrue();
   assertThat(GenerationStatus.QA_PENDING.canTransitionTo(GenerationStatus.APPROVED)).isTrue();
   assertThat(GenerationStatus.QUEUED.canTransitionTo(GenerationStatus.PUBLISHED)).isFalse();
   assertThat(GenerationStatus.PUBLISHED.canTransitionTo(GenerationStatus.GENERATING)).isFalse();
 }
 @Test void technicalQaChecksIntegrityResolutionRatioAndDuplicates() {
   var qa=new TechnicalQa();
   var media=new MockProviders().generate(new Request("operation-1","test",128,128)).output();
   assertThat(qa.inspect(media.bytes(),128,128,false).passed()).isTrue();
   assertThat(qa.inspect(media.bytes(),256,128,true).failures()).contains("Duplicate SHA-256","Resolution does not match request","Aspect ratio does not match request");
   assertThat(qa.inspect(new byte[0],128,128,false).failures()).hasSize(2);
   assertThat(TechnicalQa.checksum("abc".getBytes())).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
 }
 @Test void originalsCannotBeOverwrittenOrEscapeRoot(@TempDir Path root) {
   var storage=new LocalMediaStorage(root.toString());
   storage.putOriginal("originals/a.png",new byte[]{1,2},"image/png");
   assertThatThrownBy(()->storage.putOriginal("originals/a.png",new byte[]{3},"image/png")).isInstanceOf(IllegalStateException.class);
   assertThat(storage.read("originals/a.png")).containsExactly(1,2);
   assertThatThrownBy(()->storage.read("../outside")).isInstanceOf(IllegalArgumentException.class);
 }
 @Test void mocksAreDeterministicAndFree() {
   var provider=new MockProviders();var request=new Request("same-id","prompt",128,128);
   assertThat(provider.generate(request).output().bytes()).isEqualTo(provider.generate(request).output().bytes());
   assertThat(provider.generate(request).usage().estimatedCost()).isZero();
 }
 @Test void allMockProviderPortsReturnFreeResults() throws Exception {
   var provider=new MockProviders();var request=new Request("all-ports","A quiet studio",128,128);
   var image=provider.generate(request).output();
   var video=provider.generateVideo(request);
   assertThat(video.output().contentType()).isEqualTo("video/webm");
   assertThat(video.output().bytes()).startsWith((byte)0x1a,(byte)0x45,(byte)0xdf,(byte)0xa3);
   assertThat(video.usage().estimatedCost()).isZero();
   assertThat(provider.generateText(request).output()).contains("A quiet studio");
   assertThat(provider.inspect(image).usage().estimatedCost()).isZero();
   var upscaled=provider.upscale(image);
   assertThat(javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(upscaled.output().bytes())).getWidth()).isEqualTo(256);
   assertThat(upscaled.usage().estimatedCost()).isZero();
 }
}
