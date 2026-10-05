package com.mediafactory.bulk;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.mediafactory.provider.*;
import com.mediafactory.provider.ProviderTypes.*;
import com.mediafactory.provider.routing.ImageProviderRouter;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GeminiImageBatchTest {

  private ImageProviderRouter router;
  private ImageGenerationProvider geminiProvider;
  private GeminiImageProcessor processor;

  @BeforeEach
  void setUp() {
    router = mock(ImageProviderRouter.class);
    geminiProvider = mock(ImageGenerationProvider.class);
    processor = new GeminiImageProcessor(router);

    when(router.provider(anyString())).thenReturn(geminiProvider);
    when(router.validate(any(), any())).thenAnswer(inv -> inv.getArgument(1));
  }

  @Test
  void testKind() {
    assertThat(processor.kind()).isEqualTo("GEMINI_IMAGE");
  }

  @Test
  void testValidateWithValidGeminiOptionsAndReferences() {
    var ref = new BulkArchiveParser.Reference("input.png", "image/png", new byte[]{1, 2, 3});
    var input = new BulkProcessor.Input(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        "gemini",
        "gemini-3.1-flash-image",
        "Generate a fantasy castle",
        Map.of("width", 1024, "height", 1024, "quality", "HIGH", "format", "PNG"),
        List.of(ref)
    );

    assertThatCode(() -> processor.validate(input)).doesNotThrowAnyException();
  }

  @Test
  void testValidateRejectsReferencesForOpenAi() {
    var ref = new BulkArchiveParser.Reference("input.png", "image/png", new byte[]{1, 2, 3});
    var input = new BulkProcessor.Input(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        "openai",
        "gpt-image-2",
        "Generate a fantasy castle",
        Map.of("width", 1024, "height", 1024),
        List.of(ref)
    );

    assertThatThrownBy(() -> processor.validate(input))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("REFERENCE_IMAGES_UNSUPPORTED");
  }

  @Test
  void testValidateRejectsUnsupportedOptions() {
    var input = new BulkProcessor.Input(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        "gemini",
        "gemini-3.1-flash-image",
        "A cyberpunk city",
        Map.of("unsupportedOption", "invalidValue"),
        List.of()
    );

    assertThatThrownBy(() -> processor.validate(input))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unsupported image option");
  }

  @Test
  void testEstimateReturnsQuote() {
    var usage = new Usage("gemini", "gemini-3.1-flash-image", "generate", 100L, 200L, new BigDecimal("0.05"), "USD");
    when(geminiProvider.estimate(any())).thenReturn(usage);

    var input = new BulkProcessor.Input(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        "gemini",
        "gemini-3.1-flash-image",
        "Prompt",
        Map.of(),
        List.of()
    );

    var quote = processor.estimate(input);
    assertThat(quote.amount()).isEqualTo(new BigDecimal("0.05"));
    assertThat(quote.currency()).isEqualTo("USD");
  }

  @Test
  void testResultGeneratesOutput() {
    byte[] testImageBytes = new byte[]{(byte) 0x89, 'P', 'N', 'G'};
    var generationResult = new Result<>(
        new Media(testImageBytes, "image/png"),
        new Usage("gemini", "gemini-3.1-flash-image", "generate", 50L, 1000L, new BigDecimal("0.03"), "USD"),
        Map.of("provider", "gemini", "model", "gemini-3.1-flash-image")
    );
    when(geminiProvider.generate(any())).thenReturn(generationResult);

    var input = new BulkProcessor.Input(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        "gemini",
        "gemini-3.1-flash-image",
        "Prompt",
        Map.of("width", 1024, "height", 1024),
        List.of()
    );

    var output = processor.result("job-123", input);

    assertThat(output.bytes()).isEqualTo(testImageBytes);
    assertThat(output.mediaType()).isEqualTo("image/png");
    assertThat(output.inputUsage()).isEqualTo(50L);
    assertThat(output.outputUsage()).isEqualTo(1000L);
    assertThat(output.estimatedCost()).isEqualTo(new BigDecimal("0.03"));
    assertThat(output.metadata()).containsEntry("provider", "gemini");
  }
}
