package com.mediafactory.analytics;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class AssetEconomicsTest {
  private static BigDecimal n(String s) {
    return new BigDecimal(s);
  }

  @Test
  void decimalEconomicsAndUndefinedDenominators() {
    assertThat(AssetEconomics.profit(n("0.30"), n("0.20"))).isEqualByComparingTo("0.10");
    assertThat(AssetEconomics.roi(n("0.30"), n("0.20"))).isEqualByComparingTo("0.5");
    assertThat(AssetEconomics.roi(n("0"), n("0"))).isNull();
    assertThat(AssetEconomics.ratio(n("0"), null)).isNull();
    assertThat(AssetEconomics.profit(null, n("5"))).isNull();
    assertThat(AssetEconomics.breakEvenRemaining(n("1"), n("3"))).isEqualByComparingTo("2");
  }

  @Test
  void sharedCostNeverDuplicatesOrLosesMoney() {
    var parts = AssetEconomics.allocate(n("0.01"), List.of(n("1"), n("1"), n("1"), n("0")), 8);
    assertThat(parts.stream().reduce(BigDecimal.ZERO, BigDecimal::add))
        .isEqualByComparingTo("0.01");
    assertThat(parts.get(3)).isEqualByComparingTo("0");
    assertThat(parts).allMatch(p -> p.signum() >= 0);
  }

  @Test
  void negativeAndZeroWeightsAreRejected() {
    assertThatThrownBy(() -> AssetEconomics.allocate(n("1"), List.of(n("0")), 8))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> AssetEconomics.allocate(n("1"), List.of(n("-1")), 8))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
