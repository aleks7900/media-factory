package com.mediafactory.quality;
import org.springframework.stereotype.Component;
import javax.imageio.ImageIO;
import java.io.*;
import java.security.*;
import java.util.*;
@Component
public class TechnicalQa {
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
