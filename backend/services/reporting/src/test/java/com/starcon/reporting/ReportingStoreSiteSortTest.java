package com.starcon.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import com.starcon.reporting.ReportingStoreSiteModels.StoreSiteMetricValue;
import com.starcon.reporting.ReportingStoreSiteModels.StoreSiteRow;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 13.1.1.2 店铺与站点对比的**排序口径**单测。
 *
 * <p>🔴 这组用例守的是一条本类开头就写死的红线：
 * <b>「算不出来」与「等于 0」必须分开</b>，基期缺失、基期为 0、事实不存在三种情况
 * 一律返回 {@code null} / {@code NO_DATA} / {@code NOT_COMPARABLE}，<b>不用 0 冒充</b>。
 *
 * <p>排序这条线特别容易在重构里丢：一旦用 0 顶掉「没有销量事实」，
 * 「没卖过」与「卖了 0 件」就并列了 —— 看的人分不出哪个是真没数据。
 * 所以「算不出来」的行必须排在**最后**，而不是混在 0 里。
 */
class ReportingStoreSiteSortTest {

  private static final String QUANTITY = ReportingStoreSiteCatalog.STORE_SITE_SALES_QUANTITY;

  /** 造一行：salesQuantity 为 null 表示「算不出来」。 */
  private static StoreSiteRow row(String key, BigDecimal salesQuantity) {
    List<StoreSiteMetricValue> metrics = new ArrayList<>();
    metrics.add(
        new StoreSiteMetricValue(QUANTITY, "", salesQuantity, null, null, "AVAILABLE", null));
    return new StoreSiteRow(
        key, key, null, null, null, null, null, null, null, 0, 0, metrics, null);
  }

  private static List<String> sortedKeys(StoreSiteRow... rows) {
    List<StoreSiteRow> list = new ArrayList<>(List.of(rows));
    list.sort(ReportingStoreSiteService.bySalesQuantityDesc());
    return list.stream().map(StoreSiteRow::dimensionKey).toList();
  }

  @Test
  @DisplayName("销量倒序：大的在前")
  void 销量倒序() {
    assertThat(sortedKeys(row("b", new BigDecimal("3")), row("a", new BigDecimal("9"))))
        .containsExactly("a", "b");
  }

  @Test
  @DisplayName("🔴 算不出来的行排在最后，不与卖 0 件的行并列")
  void 算不出来的排在最后() {
    var keys =
        sortedKeys(
            row("no-data", null),
            row("zero", BigDecimal.ZERO),
            row("some", new BigDecimal("5")));

    assertThat(keys).containsExactly("some", "zero", "no-data");
  }

  @Test
  @DisplayName("🔴 整行都没有销量指标时，同样算「算不出来」而不是 0")
  void 没有销量指标也算算不出来() {
    StoreSiteRow noMetric =
        new StoreSiteRow(
            "empty", "empty", null, null, null, null, null, null, null, 0, 0, List.of(), null);
    assertThat(sortedKeys(row("zero", BigDecimal.ZERO), noMetric))
        .containsExactly("zero", "empty");
  }

  @Test
  @DisplayName("销量相同时按维度键稳定排序")
  void 销量相同时按维度键() {
    assertThat(sortedKeys(row("b", new BigDecimal("4")), row("a", new BigDecimal("4"))))
        .containsExactly("a", "b");
  }
}
