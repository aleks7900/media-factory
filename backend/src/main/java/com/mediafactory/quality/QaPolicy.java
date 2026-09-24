package com.mediafactory.quality;
import java.util.*;
import com.mediafactory.quality.QualityModels.*;

public record QaPolicy(String id,String version,boolean alwaysHumanReview,int minWidth,int minHeight,long maxBytes,
 double aspectTolerance,boolean exactDimensions,boolean allowTransparency,double rejectConfidence,double reviewConfidence,
 Map<DimensionName,Double> minimumScores,Set<Code> rejectCodes) {
 public QaPolicy {
  minimumScores=Map.copyOf(minimumScores);rejectCodes=Set.copyOf(rejectCodes);
  if(id==null||id.isBlank()||version==null||minWidth<1||minHeight<1||maxBytes<1||aspectTolerance<0||rejectConfidence<reviewConfidence||rejectConfidence>1||reviewConfidence<0||minimumScores.values().stream().anyMatch(v->!Double.isFinite(v)||v<0||v>1))throw new IllegalArgumentException("Invalid QA policy");
 }
}
