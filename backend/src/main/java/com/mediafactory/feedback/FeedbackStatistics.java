package com.mediafactory.feedback;

import java.util.*;

/**
 * Explainable descriptive statistics. Bootstrap intervals describe uncertainty, not causality.
 */
public final class FeedbackStatistics {

  private FeedbackStatistics() {
  }

  public static double quantile(double[] sorted, double p) {
    if (sorted.length == 0) {
      throw new IllegalArgumentException("Empty sample");
    }
    double position = (sorted.length - 1) * p;
    int lower = (int) position;
    return sorted[lower] + (sorted[(int) Math.ceil(position)] - sorted[lower]) * (position - lower);
  }

  public static Map<String, Object> distribution(double[] data) {
    var out = new LinkedHashMap<String, Object>();
    out.put("count", data.length);
    if (data.length == 0) {
      return out;
    }
    double[] sorted = data.clone();
    Arrays.sort(sorted);
    out.put("mean", Arrays.stream(sorted).average().orElseThrow());
    out.put("median", quantile(sorted, .5));
    out.put("p25", quantile(sorted, .25));
    out.put("p75", quantile(sorted, .75));
    out.put("p90", quantile(sorted, .9));
    return out;
  }

  public static Map<String, Object> compare(
      double[] control, double[] treatment, long seed, int minimum) {
    var result = new LinkedHashMap<String, Object>();
    result.put("control", distribution(control));
    result.put("treatment", distribution(treatment));
    result.put("sufficient", control.length >= minimum && treatment.length >= minimum);
    if (control.length == 0 || treatment.length == 0) {
      result.put("evidenceStatus", "INSUFFICIENT_DATA");
      return result;
    }
    double a = Arrays.stream(control).average().orElseThrow(),
        b = Arrays.stream(treatment).average().orElseThrow();
    result.put("absoluteDifference", b - a);
    result.put("relativeDifference", a == 0 ? null : (b - a) / Math.abs(a));
    var random = new Random(seed);
    double[] diffs = new double[1000];
    for (int i = 0; i < diffs.length; i++) {
      double x = 0, y = 0;
      for (int j = 0; j < control.length; j++) {
        x += control[random.nextInt(control.length)];
      }
      for (int j = 0; j < treatment.length; j++) {
        y += treatment[random.nextInt(treatment.length)];
      }
      diffs[i] = y / treatment.length - x / control.length;
    }
    Arrays.sort(diffs);
    double low = quantile(diffs, .025), high = quantile(diffs, .975);
    result.put("confidenceInterval", List.of(low, high));
    result.put("intervalMethod", "percentile-bootstrap-mean-1000-v1");
    result.put("randomSeed", seed);
    result.put("exploratory", true);
    result.put(
        "evidenceStatus",
        !(boolean) result.get("sufficient")
            ? "INSUFFICIENT_DATA"
            : low <= 0 && high >= 0 ? "INCONCLUSIVE" : "SUFFICIENT_EVIDENCE");
    return result;
  }

  public static Double correlation(double[] x, double[] y) {
    if (x.length != y.length || x.length < 2) {
      return null;
    }
    double mx = Arrays.stream(x).average().orElseThrow(),
        my = Arrays.stream(y).average().orElseThrow(),
        xy = 0,
        xx = 0,
        yy = 0;
    for (int i = 0; i < x.length; i++) {
      xy += (x[i] - mx) * (y[i] - my);
      xx += Math.pow(x[i] - mx, 2);
      yy += Math.pow(y[i] - my, 2);
    }
    return xx == 0 || yy == 0 ? null : xy / Math.sqrt(xx * yy);
  }

  public static Map<String, Object> compareCostPerApproved(
      double[][] control, double[][] treatment, long seed, int minimum) {
    var result = new LinkedHashMap<String, Object>();
    result.put("sufficient", control.length >= minimum && treatment.length >= minimum);
    Double a = ratio(control), b = ratio(treatment);
    result.put("absoluteDifference", a == null || b == null ? null : b - a);
    result.put(
        "relativeDifference", a == null || b == null || a == 0 ? null : (b - a) / Math.abs(a));
    result.put("evidenceStatus", "INSUFFICIENT_DATA");
    result.put("intervalMethod", "paired-percentile-bootstrap-ratio-of-sums-1000-v1");
    result.put("randomSeed", seed);
    result.put("exploratory", true);
    if (a == null || b == null) {
      return result;
    }
    Random random = new Random(seed);
    var differences = new ArrayList<Double>();
    for (int i = 0; i < 1000; i++) {
      double[][] x = new double[control.length][], y = new double[treatment.length][];
      for (int j = 0; j < x.length; j++) {
        x[j] = control[random.nextInt(control.length)];
      }
      for (int j = 0; j < y.length; j++) {
        y[j] = treatment[random.nextInt(treatment.length)];
      }
      Double xRatio = ratio(x), yRatio = ratio(y);
      if (xRatio != null && yRatio != null) {
        differences.add(yRatio - xRatio);
      }
    }
    result.put("validBootstrapReplicates", differences.size());
    if (differences.size() < 950) {
      return result;
    }
    double[] sorted = differences.stream().mapToDouble(Double::doubleValue).sorted().toArray();
    double low = quantile(sorted, .025), high = quantile(sorted, .975);
    result.put("confidenceInterval", List.of(low, high));
    result.put(
        "evidenceStatus",
        !(boolean) result.get("sufficient")
            ? "INSUFFICIENT_DATA"
            : low <= 0 && high >= 0 ? "INCONCLUSIVE" : "SUFFICIENT_EVIDENCE");
    return result;
  }

  private static Double ratio(double[][] observations) {
    double cost = 0, approved = 0;
    for (double[] row : observations) {
      cost += row[0];
      approved += row[1];
    }
    return approved == 0 ? null : cost / approved;
  }

  public static String saturation(double[] ordered, int minimum) {
    if (ordered.length < minimum * 3) {
      return "INSUFFICIENT_DATA";
    }
    int n = ordered.length / 3;
    double a = Arrays.stream(ordered, 0, n).average().orElseThrow(),
        b = Arrays.stream(ordered, n, n * 2).average().orElseThrow(),
        c = Arrays.stream(ordered, n * 2, ordered.length).average().orElseThrow();
    return a > b && b > c && c < a * .8 ? "DECLINING_MARGINAL_PERFORMANCE" : "NO_DECLINING_PATTERN";
  }
}
