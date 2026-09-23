package com.mediafactory.provider.openai;
import com.mediafactory.provider.*;
import com.mediafactory.provider.ProviderTypes.*;
import com.mediafactory.provider.resilience.ImageGenerationException;
import com.mediafactory.provider.resilience.ImageGenerationException.Type;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import java.time.Duration;

final class OpenAiImageMapper {
 private static final JsonMapper JSON=JsonMapper.builder().build();
 private OpenAiImageMapper() {}
 static String request(Request request) {
  var o=request.options();var body=new LinkedHashMap<String,Object>();
  body.put("model",o.model());body.put("prompt",request.prompt());body.put("n",1);
  body.put("size",request.width()+"x"+request.height());body.put("quality",o.quality().name().toLowerCase(Locale.ROOT));
  body.put("output_format",o.format().name().toLowerCase(Locale.ROOT));body.put("background",o.transparentBackground()?"transparent":"opaque");
  return JSON.writeValueAsString(body);
 }
 static Result<Media> response(OpenAiImageClient.Response response,Request request,OpenAiImageProperties secrets) {
  try {
   var root=JSON.readTree(response.body());var images=root.path("data");
   if(!images.isArray() || images.size()!=1) throw new IllegalArgumentException();
   String encoded=images.get(0).path("b64_json").asText("");
   byte[] bytes=Base64.getDecoder().decode(encoded);
   if(bytes.length==0||bytes.length>25*1024*1024) throw new IllegalArgumentException();
   var metadata=new HashMap<>(response.headers());
   if(response.requestId()!=null) metadata.put("providerRequestId",response.requestId());
   String revised=images.get(0).path("revised_prompt").asText("");
   if(!revised.isBlank()) metadata.put("revisedPrompt",secrets.redact(revised.substring(0,Math.min(revised.length(),32000))));
   if(root.path("created").isIntegralNumber()) metadata.put("providerCreated",root.path("created").asText());
   var usage=root.path("usage");var details=usage.path("input_tokens_details");
   addNumber(metadata,"textInputTokens",details.path("text_tokens"));addNumber(metadata,"imageInputTokens",details.path("image_tokens"));
   addNumber(metadata,"outputTokens",usage.path("output_tokens"));addNumber(metadata,"inputTokens",usage.path("input_tokens"));
   metadata.put("format",request.options().format().name());metadata.put("quality",request.options().quality().name());
   return new Result<>(new Media(bytes,request.options().format()==ImageOptions.Format.PNG?"image/png":"image/jpeg"),
    new Usage("openai",request.options().model(),"IMAGE_GENERATION",usage.path("input_tokens").asLong(0),usage.path("output_tokens").asLong(0),null,"USD"),Map.copyOf(metadata));
  } catch(Exception e) { throw new ImageGenerationException(Type.UNEXPECTED,"Image provider returned an invalid image response",Duration.ZERO,true,response.requestId()); }
 }
 private static void addNumber(Map<String,String> target,String key,tools.jackson.databind.JsonNode node) {
  if(node.isIntegralNumber()&&node.asLong()>=0) target.put(key,node.asText());
 }
}
