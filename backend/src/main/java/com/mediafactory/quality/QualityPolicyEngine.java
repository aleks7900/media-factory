package com.mediafactory.quality;

import com.mediafactory.quality.QualityModels.Decision;
import com.mediafactory.quality.QualityModels.Dimension;
import com.mediafactory.quality.QualityModels.Evaluation;
import com.mediafactory.quality.QualityModels.Finding;
import com.mediafactory.quality.QualityModels.Severity;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class QualityPolicyEngine {

  public Evaluation evaluate(QaPolicy policy, List<Finding> findings, List<Dimension> dimensions,
      boolean technicalComplete, boolean visualComplete) {
    var rules = new ArrayList<String>();
    Decision automatic = Decision.APPROVED;
    for (var f : findings) {
      if (!f.detected()) {
        continue;
      }
      if ((f.severity() == Severity.CRITICAL || policy.rejectCodes().contains(f.code()))
          && f.confidence() >= policy.rejectConfidence()) {
        automatic = Decision.REJECTED;
        rules.add("REJECT:" + f.code());
      } else if (f.severity().ordinal() >= Severity.MAJOR.ordinal()
          || f.confidence() < policy.reviewConfidence()) {
        if (automatic != Decision.REJECTED) {
          automatic = Decision.NEEDS_REVIEW;
        }
        rules.add("REVIEW:" + f.code());
      }
    }
    if (!technicalComplete || !visualComplete) {
      if (automatic == Decision.REJECTED && technicalComplete) {
        return new Evaluation(automatic, automatic, List.copyOf(rules));
      }
      return new Evaluation(Decision.NEEDS_REVIEW, Decision.NEEDS_REVIEW,
          List.of("INCOMPLETE_EXECUTION"));
    }
    for (var threshold : policy.minimumScores().entrySet()) {
      var d = dimensions.stream().filter(v -> v.dimension() == threshold.getKey()).findFirst();
      if (d.isEmpty() || d.get().applicable() && (d.get().confidence() < policy.reviewConfidence()
          || d.get().score() < threshold.getValue())) {
        if (automatic != Decision.REJECTED) {
          automatic = Decision.NEEDS_REVIEW;
        }
        rules.add("DIMENSION:" + threshold.getKey());
      }
    }
    if (rules.isEmpty()) {
      rules.add("ALL_REQUIRED_CHECKS_PASSED");
    }
    Decision finalDecision = automatic;
    if (policy.alwaysHumanReview() && automatic == Decision.APPROVED) {
      finalDecision = Decision.NEEDS_REVIEW;
      rules.add("ALWAYS_HUMAN_REVIEW");
    }
    return new Evaluation(automatic, finalDecision, List.copyOf(rules));
  }
}
