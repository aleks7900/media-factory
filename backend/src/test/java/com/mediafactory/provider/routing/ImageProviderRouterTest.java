package com.mediafactory.provider.routing;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.mediafactory.provider.*;
import com.mediafactory.provider.ProviderTypes.*;
import com.mediafactory.provider.resilience.ImageGenerationException;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ImageProviderRouterTest {

  private ImageGenerationProvider mockProvider;
  private ImageGenerationProvider openAiProvider;
  private ImageGenerationProvider geminiProvider;
  private ImageGenerationProperties properties;
  private ImageProviderRouter router;

  @BeforeEach
  void setUp() {
    mockProvider = mock(ImageGenerationProvider.class);
    when(mockProvider.providerId()).thenReturn("mock");
    when(mockProvider.configured()).thenReturn(true);
    when(mockProvider.capabilities()).thenReturn(new ProviderCapabilities(
        Set.of(ImageOptions.AspectRatio.SQUARE),
        Set.of(ImageOptions.Format.PNG),
        Set.of(ImageOptions.Quality.HIGH),
        Set.of("1024x1024"),
        false, false, false, false, false, 1
    ));

    openAiProvider = mock(ImageGenerationProvider.class);
    when(openAiProvider.providerId()).thenReturn("openai");
    when(openAiProvider.configured()).thenReturn(true);
    when(openAiProvider.capabilities()).thenReturn(new ProviderCapabilities(
        Set.of(ImageOptions.AspectRatio.SQUARE),
        Set.of(ImageOptions.Format.PNG),
        Set.of(ImageOptions.Quality.HIGH),
        Set.of("1024x1024"),
        false, false, false, false, false, 1
    ));

    geminiProvider = mock(ImageGenerationProvider.class);
    when(geminiProvider.providerId()).thenReturn("gemini");
    when(geminiProvider.configured()).thenReturn(true);
    when(geminiProvider.capabilities()).thenReturn(new ProviderCapabilities(
        Set.of(ImageOptions.AspectRatio.SQUARE, ImageOptions.AspectRatio.WIDE_16_9),
        Set.of(ImageOptions.Format.PNG),
        Set.of(ImageOptions.Quality.HIGH),
        Set.of(),
        true, false, false, true, false, 1
    ));

    var mockConfig = new ImageGenerationProperties.Provider(
        true, "mock-v1", Set.of("mock-v1"), null, null, null, null, "success", Map.of()
    );
    var openAiConfig = new ImageGenerationProperties.Provider(
        true, "gpt-image-2", Set.of("gpt-image-2", "dall-e-3"), null, null, null, null, "success", Map.of()
    );
    var geminiConfig = new ImageGenerationProperties.Provider(
        true, "gemini-3.1-flash-image", Set.of("gemini-3.1-flash-image"), null, null, null, null, "success", Map.of()
    );

    properties = new ImageGenerationProperties(
        "mock",
        "test",
        4,
        Duration.ofMinutes(5),
        new ImageGenerationProperties.Routing(false, false, List.of()),
        Map.of("mock", mockConfig, "openai", openAiConfig, "gemini", geminiConfig)
    );

    router = new ImageProviderRouter(
        List.of(mockProvider, openAiProvider, geminiProvider),
        properties
    );
  }

  @Test
  void testResolveProviderIdAliases() {
    assertThat(ImageProviderRouter.resolveProviderId("GPT_IMAGE_2")).isEqualTo("openai");
    assertThat(ImageProviderRouter.resolveProviderId("gpt_image_2")).isEqualTo("openai");
    assertThat(ImageProviderRouter.resolveProviderId("openai")).isEqualTo("openai");
    assertThat(ImageProviderRouter.resolveProviderId("GEMINI_3_1_FLASH_IMAGE")).isEqualTo("gemini");
    assertThat(ImageProviderRouter.resolveProviderId("gemini-3.1-flash-image")).isEqualTo("gemini");
    assertThat(ImageProviderRouter.resolveProviderId("gemini")).isEqualTo("gemini");
    assertThat(ImageProviderRouter.resolveProviderId("google")).isEqualTo("gemini");
    assertThat(ImageProviderRouter.resolveProviderId("mock")).isEqualTo("mock");
    assertThat(ImageProviderRouter.resolveProviderId("MOCK")).isEqualTo("mock");
  }

  @Test
  void testRoutesToOpenAiProvider() {
    assertThat(router.provider("openai")).isSameAs(openAiProvider);
    assertThat(router.provider("GPT_IMAGE_2")).isSameAs(openAiProvider);
    assertThat(router.provider("gpt_image_2")).isSameAs(openAiProvider);
  }

  @Test
  void testRoutesToGeminiProvider() {
    assertThat(router.provider("gemini")).isSameAs(geminiProvider);
    assertThat(router.provider("GEMINI_3_1_FLASH_IMAGE")).isSameAs(geminiProvider);
    assertThat(router.provider("gemini-3.1-flash-image")).isSameAs(geminiProvider);
    assertThat(router.provider("google")).isSameAs(geminiProvider);
  }

  @Test
  void testRoutesToMockProvider() {
    assertThat(router.provider("mock")).isSameAs(mockProvider);
    assertThat(router.provider("MOCK")).isSameAs(mockProvider);
  }

  @Test
  void testUnconfiguredOrDisabledProviderThrows() {
    when(geminiProvider.configured()).thenReturn(false);
    assertThatThrownBy(() -> router.provider("GEMINI_3_1_FLASH_IMAGE"))
        .isInstanceOf(ImageGenerationException.class)
        .satisfies(e -> assertThat(((ImageGenerationException) e).type())
            .isEqualTo(ImageGenerationException.Type.AUTHENTICATION));

    assertThatThrownBy(() -> router.provider("unknown_provider"))
        .isInstanceOf(ImageGenerationException.class)
        .satisfies(e -> assertThat(((ImageGenerationException) e).type())
            .isEqualTo(ImageGenerationException.Type.AUTHENTICATION));
  }

  @Test
  void testValidateNormalizesHopAndCapabilities() {
    var hop = new ProviderRoute.Hop("GEMINI_3_1_FLASH_IMAGE", "gemini-3.1-flash-image");
    var req = new Request(
        "op-1",
        "A beautiful mountain sunset",
        1024,
        1024,
        new ImageOptions("gemini", "gemini-3.1-flash-image", ImageOptions.AspectRatio.SQUARE,
            ImageOptions.Quality.HIGH, ImageOptions.Format.PNG, null, null, null, false, 1)
    );

    var validated = router.validate(hop, req);

    assertThat(validated.options().provider()).isEqualTo("gemini");
    assertThat(validated.options().model()).isEqualTo("gemini-3.1-flash-image");
  }

  @Test
  void testValidateRejectsUnallowlistedModel() {
    var hop = new ProviderRoute.Hop("gemini", "unsupported-model-v99");
    var req = new Request(
        "op-2",
        "Test prompt",
        1024,
        1024,
        new ImageOptions("gemini", "unsupported-model-v99", ImageOptions.AspectRatio.SQUARE,
            ImageOptions.Quality.HIGH, ImageOptions.Format.PNG, null, null, null, false, 1)
    );

    assertThatThrownBy(() -> router.validate(hop, req))
        .isInstanceOf(ImageGenerationException.class)
        .satisfies(e -> assertThat(((ImageGenerationException) e).type())
            .isEqualTo(ImageGenerationException.Type.INVALID_REQUEST));
  }
}
