package com.mediafactory.provider;
import com.mediafactory.provider.ProviderTypes.*;
import org.springframework.stereotype.Component;
import java.awt.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.*;
import java.math.BigDecimal;
import java.util.Map;

@Component
public class MockProviders implements ImageGenerationProvider, VideoGenerationProvider, VisionProvider, TextGenerationProvider, UpscaleProvider {
 private final ImageGenerationProperties properties;
 public MockProviders() { this.properties=null; }
 @org.springframework.beans.factory.annotation.Autowired
 public MockProviders(ImageGenerationProperties properties) { this.properties=properties; }
 public String providerId() { return "mock"; }
 public boolean replaySafe() { return true; }
 public ProviderCapabilities capabilities() {
  return new ProviderCapabilities(java.util.Set.of(ImageOptions.AspectRatio.values()),java.util.Set.of(ImageOptions.Format.values()),java.util.Set.of(ImageOptions.Quality.values()),java.util.Set.of(),true,false,false,false,false,1);
 }
 public Usage estimate(Request request) { return new Usage("mock","studio-mock-v1","image.generate",request.prompt().length(),0,BigDecimal.ZERO,"USD"); }
 private <T> Result<T> result(T output, String operation, long input) {
   return new Result<>(output,new Usage("mock","studio-mock-v1",operation,input,1,BigDecimal.ZERO,"USD"),Map.of("mode","mock","billable","false"));
 }
 public Result<Media> generate(Request r) {
   if(properties!=null) {
    var type=switch(properties.provider("mock").mockScenario()) {
     case "rate-limit" -> com.mediafactory.provider.resilience.ImageGenerationException.Type.RATE_LIMIT;
     case "timeout" -> com.mediafactory.provider.resilience.ImageGenerationException.Type.TIMEOUT;
     case "provider-error" -> com.mediafactory.provider.resilience.ImageGenerationException.Type.UNAVAILABLE;
     default -> null;
    };
    if(type!=null) throw new com.mediafactory.provider.resilience.ImageGenerationException(type,"Simulated "+type,java.time.Duration.ofSeconds(1),false,null);
   }
   try {
     var image = new BufferedImage(r.width(),r.height(),BufferedImage.TYPE_INT_RGB);
     var g = image.createGraphics();
     int hash = r.operationId().hashCode();
     g.setPaint(new GradientPaint(0,0,new Color(hash & 0xffffff),r.width(),r.height(),new Color(0x161b35)));
     g.fillRect(0,0,r.width(),r.height());
     g.setColor(new Color(255,255,255,70));
     for(int i=0;i<7;i++) g.drawOval(r.width()/8+i*22,r.height()/8+i*22,r.width()/2,r.height()/2);
     g.setFont(new Font("SansSerif",Font.BOLD,Math.max(16,r.width()/28)));
     g.drawString("MEDIA / FACTORY",r.width()/12,r.height()*4/5); g.dispose();
     String format=r.options().format()==ImageOptions.Format.PNG?"png":"jpeg";
     var out = new ByteArrayOutputStream(); ImageIO.write(image,format,out);
     return result(new Media(out.toByteArray(),"image/"+format),"IMAGE_GENERATION",r.prompt().length());
   } catch(IOException e) { throw new IllegalStateException("Mock rendering failed",e); }
 }
 public Result<Media> generateVideo(Request r) {
   try(var fixture=getClass().getResourceAsStream("/mock/mock-video.webm")) {
     if(fixture==null) throw new IllegalStateException("Mock video fixture is missing");
     return result(new Media(fixture.readAllBytes(),"video/webm"),"video.generate",r.prompt().length());
   } catch(IOException e) { throw new IllegalStateException("Mock video could not be read",e); }
 }
 public Result<String> inspect(Media media) { return result("Mock visual inspection passed","vision.inspect",media.bytes().length); }
 public Result<String> generateText(Request r) { return result("Mock concept: "+r.prompt(),"text.generate",r.prompt().length()); }
 public Result<Media> upscale(Media media) {
   try {
     var source=ImageIO.read(new ByteArrayInputStream(media.bytes()));
     if(source==null || source.getWidth()>2048 || source.getHeight()>2048) throw new IllegalArgumentException("Mock upscale requires a valid image at most 2048 x 2048");
     var target=new BufferedImage(source.getWidth()*2,source.getHeight()*2,BufferedImage.TYPE_INT_RGB);
     var graphics=target.createGraphics();
     graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC);
     graphics.drawImage(source,0,0,target.getWidth(),target.getHeight(),null);graphics.dispose();
     var output=new ByteArrayOutputStream();ImageIO.write(target,"png",output);
     return result(new Media(output.toByteArray(),"image/png"),"image.upscale",media.bytes().length);
   } catch(IOException e) { throw new IllegalStateException("Mock upscale failed",e); }
 }
}
