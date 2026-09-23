package com.mediafactory.provider.openai;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import java.net.URI;

@ConfigurationProperties("media-factory.openai")
public record OpenAiImageProperties(@DefaultValue("") String apiKey,
 @DefaultValue("https://api.openai.com/v1/images/generations") URI endpoint,
 @DefaultValue("false") boolean allowLocalHttp) {
 public OpenAiImageProperties {
  if(endpoint!=null && (endpoint.getUserInfo()!=null || endpoint.getQuery()!=null || endpoint.getFragment()!=null ||
    !("https".equals(endpoint.getScheme()) || (allowLocalHttp && "http".equals(endpoint.getScheme()) && java.util.Set.of("localhost","127.0.0.1","[::1]").contains(endpoint.getHost())))))
   throw new IllegalArgumentException("Image provider endpoint requires HTTPS; local HTTP is test-only");
 }
 public String redact(String value) { return value==null?null:apiKey==null||apiKey.isBlank()?value:value.replace(apiKey,"[REDACTED]"); }
 @Override public String toString() { return "OpenAiImageProperties[credentials=REDACTED]"; }
}
