package com.mediafactory.analytics;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Decimal arithmetic shared by analytics projections; null means unavailable, never zero.
 */
public final class AssetEconomics {

  private AssetEconomics() {
  }

  public static BigDecimal ratio(BigDecimal numerator, BigDecimal denominator) {
    if (numerator == null || denominator == null || denominator.signum() == 0) {
      return null;
    }
    return numerator.divide(denominator, 12, RoundingMode.HALF_EVEN);
  }

  public static BigDecimal profit(BigDecimal revenue, BigDecimal cost) {
    return revenue == null || cost == null ? null : revenue.subtract(cost);
  }

  public static BigDecimal roi(BigDecimal revenue, BigDecimal cost) {
    return ratio(profit(revenue, cost), cost);
  }

  public static BigDecimal breakEvenRemaining(BigDecimal revenue, BigDecimal cost) {
    var profit = profit(revenue, cost);
    return profit == null ? null : profit.negate().max(BigDecimal.ZERO);
  }

  /**
   * Allocate the residual to the final positive weight, preserving the exact input total.
   */
  public static List<BigDecimal> allocate(BigDecimal total, List<BigDecimal> weights, int scale) {
    if (total == null || total.signum() < 0 || weights.isEmpty() || scale < 0 || scale > 12) {
      throw new IllegalArgumentException("Invalid allocation");
    }
    total = total.setScale(scale, RoundingMode.UNNECESSARY);
    if (weights.stream().anyMatch(w -> w == null || w.signum() < 0)) {
      throw new IllegalArgumentException("Weights must be nonnegative");
    }
    BigDecimal sum = weights.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    if (sum.signum() == 0) {
      throw new IllegalArgumentException("A positive weight is required");
    }
    int last = weights.size() - 1;
    while (weights.get(last).signum() == 0) {
      last--;
    }
    var result = new ArrayList<BigDecimal>();
    BigDecimal allocated = BigDecimal.ZERO;
    for (int i = 0; i < weights.size(); i++) {
      BigDecimal part =
          i == last
              ? total.subtract(allocated)
              : total.multiply(weights.get(i)).divide(sum, scale, RoundingMode.DOWN);
      result.add(part);
      allocated = allocated.add(part);
    }
    return List.copyOf(result);
  }
}
