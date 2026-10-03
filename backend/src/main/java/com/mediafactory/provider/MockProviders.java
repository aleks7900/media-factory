package com.mediafactory.provider;

import com.mediafactory.provider.ProviderTypes.Media;
import com.mediafactory.provider.ProviderTypes.Request;
import com.mediafactory.provider.ProviderTypes.Result;
import com.mediafactory.provider.ProviderTypes.Usage;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.Map;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Component;

@Component
public class MockProviders implements ImageGenerationProvider, VideoGenerationProvider,
    VisionProvider, TextGenerationProvider, UpscaleProvider {

  private final ImageGenerationProperties properties;

  public MockProviders() {
    this.properties = null;
  }

  @org.springframework.beans.factory.annotation.Autowired
  public MockProviders(ImageGenerationProperties properties) {
    this.properties = properties;
  }

  public String providerId() {
    return "mock";
  }

  public boolean replaySafe() {
    return true;
  }

  public ProviderCapabilities capabilities() {
    return new ProviderCapabilities(java.util.Set.of(ImageOptions.AspectRatio.values()),
        java.util.Set.of(ImageOptions.Format.values()),
        java.util.Set.of(ImageOptions.Quality.values()), java.util.Set.of(), true, false, false,
        false, false, 1);
  }

  public Usage estimate(Request request) {
    return new Usage("mock", "studio-mock-v1", "image.generate", request.prompt().length(), 0,
        BigDecimal.ZERO, "USD");
  }

  private <T> Result<T> result(T output, String operation, long input) {
    return new Result<>(output,
        new Usage("mock", "studio-mock-v1", operation, input, 1, BigDecimal.ZERO, "USD"),
        Map.of("mode", "mock", "billable", "false"));
  }

  public Result<Media> generate(Request r) {
    if (properties != null) {
      var type = switch (properties.provider("mock").mockScenario()) {
        case "rate-limit" ->
            com.mediafactory.provider.resilience.ImageGenerationException.Type.RATE_LIMIT;
        case "timeout" ->
            com.mediafactory.provider.resilience.ImageGenerationException.Type.TIMEOUT;
        case "provider-error" ->
            com.mediafactory.provider.resilience.ImageGenerationException.Type.UNAVAILABLE;
        default -> null;
      };
      if (type != null) {
        throw new com.mediafactory.provider.resilience.ImageGenerationException(type,
            "Simulated " + type, java.time.Duration.ofSeconds(1), false, null);
      }
    }
    try {
      var image = new BufferedImage(r.width(), r.height(), BufferedImage.TYPE_INT_RGB);
      var g = image.createGraphics();
      int hash = r.operationId().hashCode();
      g.setPaint(new GradientPaint(0, 0, new Color(hash & 0xffffff), r.width(), r.height(),
          new Color(0x161b35)));
      g.fillRect(0, 0, r.width(), r.height());
      g.setColor(new Color(255, 255, 255, 70));
      for (int i = 0; i < 7; i++) {
        g.drawOval(r.width() / 8 + i * 22, r.height() / 8 + i * 22, r.width() / 2, r.height() / 2);
      }
      g.setFont(new Font("SansSerif", Font.BOLD, Math.max(16, r.width() / 28)));
      g.drawString("MEDIA / FACTORY", r.width() / 12, r.height() * 4 / 5);
      if (r.prompt().startsWith("Stock illustration:")) {
        // Free abstract stock fixture, with no text/logo baked into the image.
        var random = new java.util.Random(hash);
        g.setColor(new Color(random.nextInt(0xffffff)));
        g.fillRect(0, 0, r.width(), r.height());
        for (int i = 0; i < 36; i++) {
          g.setColor(new Color(random.nextInt(0xffffff)));
          g.fillOval(random.nextInt(r.width()), random.nextInt(r.height()),
              r.width()/8 + random.nextInt(r.width()/2),
              r.height()/8 + random.nextInt(r.height()/2));
        }
      }
      if (r.prompt().contains("Dominant pure black background")) {
        // Deterministic local AMOLED fixture for the wallpaper pipeline; never a paid operation.
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, r.width(), r.height());
        int diameter = r.width() / 3;
        int x = r.width() / 3 + Math.floorMod(hash, Math.max(1, r.width() / 8));
        int y = r.height() / 2 + Math.floorMod(hash / 31, Math.max(1, r.height() / 8));
        g.setColor(new Color(245, 250, 255));
        g.fillOval(x, y, diameter, diameter);
        g.setColor(new Color(80, 100 + Math.floorMod(hash, 100), 200));
        g.fillOval(x + diameter / 4, y + diameter / 4, diameter / 2, diameter / 2);
      }
      g.dispose();
      String format = r.options().format() == ImageOptions.Format.PNG ? "png" : "jpeg";
      var out = new ByteArrayOutputStream();
      ImageIO.write(image, format, out);
      return result(new Media(out.toByteArray(), "image/" + format), "IMAGE_GENERATION",
          r.prompt().length());
    } catch (IOException e) {
      throw new IllegalStateException("Mock rendering failed", e);
    }
  }

  public Result<Media> generateVideo(Request r) {
    try (var fixture = getClass().getResourceAsStream("/mock/mock-video.webm")) {
      if (fixture == null) {
        throw new IllegalStateException("Mock video fixture is missing");
      }
      return result(new Media(fixture.readAllBytes(), "video/webm"), "video.generate",
          r.prompt().length());
    } catch (IOException e) {
      throw new IllegalStateException("Mock video could not be read", e);
    }
  }

  public Result<String> inspect(Media media) {
    try {
      var image = ImageIO.read(new ByteArrayInputStream(media.bytes()));
      if (image == null) throw new IllegalArgumentException("Unreadable metadata image");
      long red=0,green=0,blue=0,count=0;
      for(int y=0;y<image.getHeight();y+=Math.max(1,image.getHeight()/64))
        for(int x=0;x<image.getWidth();x+=Math.max(1,image.getWidth()/64)) {
          var c=new Color(image.getRGB(x,y));red+=c.getRed();green+=c.getGreen();blue+=c.getBlue();count++;
        }
      String color=red>=green&&red>=blue?"red":green>=blue?"green":"blue";
      return result(com.mediafactory.processing.ProcessingJson.write(Map.of("mock",true,"dominantColor",color,"meanRgb",java.util.List.of(red/count,green/count,blue/count),"orientation",image.getWidth()==image.getHeight()?"square":image.getWidth()>image.getHeight()?"landscape":"portrait","visualDescription","Abstract color composition; subject semantics unavailable in mock Vision","keywords",java.util.List.of("abstract","background","color","composition","digital","illustration","texture","pattern","gradient","design",color))),"STOCK_VISION_ANALYSIS",media.bytes().length);
    } catch(IOException e){throw new IllegalArgumentException("Metadata image decode failed",e);}
  }

  public Map<String, String> textIdentity() { return Map.of("provider", "mock", "model", "studio-mock-v1"); }

  public Map<String, String> visionIdentity() { return Map.of("provider", "mock", "model", "pixel-observations-v1"); }

  public Result<String> generateText(Request r) {
    if(r.prompt().startsWith("FEEDBACK_HYPOTHESIS_V1")) {
      return result(com.mediafactory.processing.ProcessingJson.write(Map.of("title","Controlled visual attribute comparison","description","Test the selected visual attribute within the observed collection and platform using frozen control and treatment prompt versions.","rationale","The supplied observational evidence motivates an experiment; it does not establish causality.")),"HYPOTHESIS_GENERATION",r.prompt().length());
    }
    if(r.prompt().startsWith("STOCK_METADATA_V1")) {
      String color=r.prompt().contains("\"dominantColor\":\"red\"")?"Red":r.prompt().contains("\"dominantColor\":\"green\"")?"Green":"Blue";
      var words=java.util.List.of(color.toLowerCase(),"abstract","background","color composition","digital illustration","texture","pattern","gradient","design","visual composition","color field");
      return result(com.mediafactory.processing.ProcessingJson.write(Map.of("title",color+" abstract color composition","description","Abstract digital composition with "+color.toLowerCase()+" tones, layered shapes and a textured background.","keywords",words.stream().map(v->Map.of("value",v,"source","LLM","confidence",0.5)).toList(),"categories",java.util.List.of("ABSTRACT"),"contentType","UNDETERMINED","aiGenerated",true,"riskFlags",java.util.List.of("UNKNOWN_IP_RISK"))),"STOCK_METADATA_GENERATION",r.prompt().length());
    }
    return result("Mock concept: " + r.prompt(), "text.generate", r.prompt().length());
  }

  public Result<Media> upscale(Media media) {
    try {
      var source = ImageIO.read(new ByteArrayInputStream(media.bytes()));
      if (source == null || source.getWidth() > 2048 || source.getHeight() > 2048) {
        throw new IllegalArgumentException(
            "Mock upscale requires a valid image at most 2048 x 2048");
      }
      var target = new BufferedImage(source.getWidth() * 2, source.getHeight() * 2,
          BufferedImage.TYPE_INT_RGB);
      var graphics = target.createGraphics();
      graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
          RenderingHints.VALUE_INTERPOLATION_BICUBIC);
      graphics.drawImage(source, 0, 0, target.getWidth(), target.getHeight(), null);
      graphics.dispose();
      var output = new ByteArrayOutputStream();
      ImageIO.write(target, "png", output);
      return result(new Media(output.toByteArray(), "image/png"), "image.upscale",
          media.bytes().length);
    } catch (IOException e) {
      throw new IllegalStateException("Mock upscale failed", e);
    }
  }

  public Result<String> extractVisualAttributes(Media media, String versionedPrompt) {
    return result(com.mediafactory.processing.ProcessingJson.write(Map.of("features",java.util.List.of(Map.of("key","style","value","mock abstract fixture","confidence",0.25)),"warnings",java.util.List.of("MOCK_SEMANTICS: fixture labels are not real visual understanding"))),"VISUAL_FEATURE_EXTRACTION",media.bytes().length);
  }
}
