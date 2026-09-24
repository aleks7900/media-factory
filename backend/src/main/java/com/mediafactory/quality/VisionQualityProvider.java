package com.mediafactory.quality;
import java.util.*;
import java.math.BigDecimal;

/** No SDK types, URLs, or provider response shapes enter the QA orchestration service. */
public interface VisionQualityProvider {
 String providerId();
 Set<String> capabilities();
 Result analyze(Request request);
 record Request(UUID assetId,UUID generationId,byte[] bytes,String mediaType,String model,String scenario,Map<String,Object> context) {}
 record Result(QualityModels.Evidence evidence,Long inputUsage,Long outputUsage,BigDecimal estimatedCost,String currency,String pricingVersion,String requestId,Map<String,String> metadata) {}
}
