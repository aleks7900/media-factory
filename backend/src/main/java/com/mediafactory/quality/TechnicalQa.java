package com.mediafactory.quality;
import org.springframework.stereotype.Component;
import javax.imageio.ImageIO;
import java.io.*;
import java.security.*;
import java.util.*;
@Component
public class TechnicalQa {
 public QualityModels.Evidence inspectStructured(byte[] bytes,String mime,int expectedWidth,int expectedHeight,boolean duplicate,QaPolicy policy) {
  var findings=new ArrayList<QualityModels.Finding>();
  check(findings,QualityModels.Code.FILE_SIZE,bytes.length==0||bytes.length>policy.maxBytes(),QualityModels.Severity.CRITICAL,1,"Bytes: "+bytes.length+"; maximum: "+policy.maxBytes());
  check(findings,QualityModels.Code.DUPLICATE_SHA256,duplicate,QualityModels.Severity.MAJOR,1,"Exact SHA-256 duplicate: "+duplicate);
  boolean supported=Set.of("image/png","image/jpeg").contains(mime);
  check(findings,QualityModels.Code.UNSUPPORTED_FORMAT,!supported,QualityModels.Severity.CRITICAL,1,"Supported technical decoders: PNG, JPEG. Declared MIME: "+mime);
  java.awt.image.BufferedImage image=null;
  if(bytes.length>0&&bytes.length<=policy.maxBytes()&&supported) {
   try(var stream=ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
    var readers=ImageIO.getImageReaders(stream);
    if(readers.hasNext()) {
     var reader=readers.next();
     try {reader.setInput(stream);int w=reader.getWidth(0),h=reader.getHeight(0);
      if(w>0&&h>0&&(long)w*h<=16777216) {
       String format=reader.getFormatName().toLowerCase(Locale.ROOT);
       boolean matches=mime.equals("image/png")?format.equals("png"):Set.of("jpg","jpeg").contains(format);
       if(matches)image=reader.read(0);
      }
     }finally{reader.dispose();}
    }
   }catch(IOException|RuntimeException ignored) { /* Decode failures are evidence, never raw exception text. */ }
  }
  check(findings,QualityModels.Code.CORRUPT_FILE,image==null,QualityModels.Severity.CRITICAL,1,"Decoded image matches MIME and is within the 16 megapixel safety limit: "+(image!=null));
  if(image!=null) {
   int w=image.getWidth(),h=image.getHeight();
   check(findings,QualityModels.Code.RESOLUTION_TOO_LOW,w<policy.minWidth()||h<policy.minHeight(),QualityModels.Severity.MAJOR,1,"Actual "+w+"x"+h+"; minimum "+policy.minWidth()+"x"+policy.minHeight());
   check(findings,QualityModels.Code.WRONG_DIMENSIONS,policy.exactDimensions()&&(w!=expectedWidth||h!=expectedHeight),QualityModels.Severity.MAJOR,1,"Actual "+w+"x"+h+"; expected "+expectedWidth+"x"+expectedHeight);
   check(findings,QualityModels.Code.WRONG_ASPECT_RATIO,Math.abs((double)w/h-(double)expectedWidth/expectedHeight)>policy.aspectTolerance(),QualityModels.Severity.MAJOR,1,"Actual ratio "+(double)w/h+"; expected "+(double)expectedWidth/expectedHeight);
   long n=0,transparent=0,extreme=0,border=0,borderBlack=0;double sum=0,squares=0,gradient=0;int step=Math.max(1,Math.max(w,h)/256);
   for(int y=0;y<h;y+=step)for(int x=0;x<w;x+=step){int rgb=image.getRGB(x,y);double l=luma(rgb);n++;sum+=l;squares+=l*l;
    if((rgb>>>24)<250)transparent++;if(l<2||l>253)extreme++;
    if(x<step||y<step||x>=w-step||y>=h-step){border++;if(l<5)borderBlack++;}
    if(x>=step)gradient+=Math.abs(l-luma(image.getRGB(x-step,y)));
   }
   double variance=squares/n-Math.pow(sum/n,2);
   check(findings,QualityModels.Code.UNEXPECTED_TRANSPARENCY,!policy.allowTransparency()&&transparent>0,QualityModels.Severity.MAJOR,1,"Sampled transparent pixels: "+transparent+"/"+n);
   check(findings,QualityModels.Code.NEAR_SOLID_IMAGE,variance<1,QualityModels.Severity.MAJOR,0.65,"Luminance variance "+variance+"; heuristic may describe intentional minimalism");
   check(findings,QualityModels.Code.EXTREME_EXPOSURE,(double)extreme/n>0.98,QualityModels.Severity.MAJOR,0.65,"Clipped luminance fraction "+(double)extreme/n+"; intentional backgrounds may trigger this heuristic");
   check(findings,QualityModels.Code.BLACK_BORDER,border>0&&(double)borderBlack/border>0.98&&sum/n>15,QualityModels.Severity.MINOR,0.65,"Near-black perimeter fraction "+(double)borderBlack/Math.max(1,border)+"; heuristic, framing may be intentional");
   check(findings,QualityModels.Code.BLUR,gradient/n<0.15&&variance>1,QualityModels.Severity.MAJOR,0.55,"Sampled edge contrast "+gradient/n+"; low-detail heuristic, not a definitive optical blur detector");
  }
  boolean critical=findings.stream().anyMatch(f->f.detected()&&f.severity()==QualityModels.Severity.CRITICAL);
  return new QualityModels.Evidence(findings,List.of(new QualityModels.Dimension(QualityModels.DimensionName.TECHNICAL_INTEGRITY,critical?0:1,1,true,"Deterministic file integrity checks; see individual technical findings")));
 }
 private static double luma(int rgb){return 0.2126*((rgb>>16)&255)+0.7152*((rgb>>8)&255)+0.0722*(rgb&255);}
 private static void check(List<QualityModels.Finding> out,QualityModels.Code code,boolean detected,QualityModels.Severity severity,double confidence,String evidence){out.add(new QualityModels.Finding(QualityModels.Category.TECHNICAL,code,severity,confidence,detected,QualityModels.Source.TECHNICAL,evidence,Map.of()));}
 public record Report(String sha256,int width,int height,List<String> failures) { public boolean passed() { return failures.isEmpty(); } }
 public static String checksum(byte[] bytes) {
   try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
   catch(NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
 }
 public Report inspect(byte[] bytes,int expectedWidth,int expectedHeight,boolean duplicate) {
   var errors=new ArrayList<String>(); int width=0,height=0;
   if(bytes.length==0 || bytes.length>25*1024*1024) errors.add("File size must be between 1 byte and 25 MiB");
   if(duplicate) errors.add("Duplicate SHA-256");
   if(bytes.length>25*1024*1024) return new Report(checksum(bytes),0,0,List.copyOf(errors));
   try {
     var image=ImageIO.read(new ByteArrayInputStream(bytes));
     if(image==null) errors.add("File integrity: unsupported or corrupt image");
     else {
       width=image.getWidth(); height=image.getHeight();
       if(width!=expectedWidth || height!=expectedHeight) errors.add("Resolution does not match request");
       if(Math.abs((double)width/height-(double)expectedWidth/expectedHeight)>0.01) errors.add("Aspect ratio does not match request");
     }
   } catch(IOException|RuntimeException e) { errors.add("File integrity: image cannot be decoded"); }
   return new Report(checksum(bytes),width,height,List.copyOf(errors));
 }
}
