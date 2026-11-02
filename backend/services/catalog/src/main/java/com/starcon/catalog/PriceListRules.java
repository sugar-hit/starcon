package com.starcon.catalog;

import com.starcon.catalog.PriceListModels.CoverageSummary;
import com.starcon.catalog.PriceListModels.ScopeInput;
import com.starcon.catalog.PriceListModels.ScopeType;
import com.starcon.catalog.PriceListModels.ScopeView;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * 5.1.1.1 价目表：**纯规则**部分（无 IO、无租户上下文，可单测）。
 *
 * <p>本类回答四个问题：
 *
 * <ol>
 *   <li>编码 / 币种怎么归一化（大小写、空白、字符集）；
 *   <li>生效区间怎么判定（<b>半开区间</b>）；
 *   <li>适用范围怎么去重与排序；
 *   <li>覆盖范围摘要怎么算（蓝图输出句：「价目表记录 + 覆盖范围摘要」）。
 * </ol>
 *
 * <h2>🔴 纪律一：生效区间是半开区间 [from, to)</h2>
 *
 * <p>起点<b>含</b>、终点<b>不含</b>。这样两张区间首尾相接的价目表
 * （A 到 2026-10-01 止、B 从 2026-10-01 起）不会在 2026-10-01 那一刻同时生效 ——
 * 那正是 5.1.2.1 冲突检测要成立的前提。边界值由
 * {@link #effectiveNow(OffsetDateTime, OffsetDateTime, OffsetDateTime)} 与集成测试钉死。
 *
 * <h2>🔴 纪律二：适用范围为空 = 适用于全租户</h2>
 *
 * <p>这是<b>显式语义</b>，不是漏填：一张没有范围行的价目表对全租户生效。
 * 摘要里必须写出来（{@link CoverageSummary#appliesToWholeTenant()}），
 * 否则运营分不清「全局价目表」与「漏配了范围」。
 *
 * <h2>🔴 纪律三：币种只有一种形态</h2>
 *
 * <p>归一化之后必须<b>正好是三个大写字母</b>。任何多币种写法（{@code USD,CNY} /
 * {@code USDCNY} / {@code usd}）在归一化阶段就被拒 —— DB 层还有命名约束
 * {@code price_list_one_currency} 兜底（两层都挡，不是只有一层）。
 */
public final class PriceListRules {

  private PriceListRules() {}

  /** 价目表编码的字符集：大写字母、数字、短横线与下划线。 */
  private static final String CODE_PATTERN = "^[A-Z0-9][A-Z0-9_-]{0,63}$";

  /** 币种的字符集：ISO 4217 三位大写字母。 */
  private static final String CURRENCY_PATTERN = "^[A-Z]{3}$";

  /**
   * 归一化编码：去空白 + 转大写。
   *
   * @throws IllegalArgumentException 编码为空或字符集不合法
   */
  public static String normalizeCode(String raw) {
    var code = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
    if (code.isEmpty()) throw new IllegalArgumentException("价目表编码必填");
    if (!code.matches(CODE_PATTERN)) {
      throw new IllegalArgumentException(
          "价目表编码只能是大写字母、数字、短横线与下划线（最多 64 位，首位不能是分隔符）");
    }
    return code;
  }

  /**
   * 归一化币种：去空白 + 转大写 + 校验正好三个字母。
   *
   * @throws IllegalArgumentException 币种为空、不是三个字母、或写成了多个币种
   */
  public static String normalizeCurrency(String raw) {
    var currency = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
    if (currency.isEmpty()) throw new IllegalArgumentException("必须指定价目表币种");
    if (!currency.matches(CURRENCY_PATTERN)) {
      throw new IllegalArgumentException(
          "币种必须是三个大写字母（一张价目表只能有一个币种，不能写 '"
              + currency
              + "' 这样的多币种值）");
    }
    return currency;
  }

  /**
   * 生效区间是否合法。
   *
   * <p>规则：终点为空 = 长期有效（合法）；终点存在时必须**晚于**起点
   * （空区间没有任何意义：一张在某一刻同时生效又失效的价目表会制造「此刻该用哪张表」的歧义）。
   */
  public static boolean validInterval(OffsetDateTime from, OffsetDateTime to) {
    if (from == null) return false;
    return to == null || to.isAfter(from);
  }

  /**
   * 此刻是否生效（半开区间 [from, to)）。
   *
   * @param from 生效起点（含）
   * @param to 生效终点（不含）；空 = 长期有效
   * @param now 判定时刻
   */
  public static boolean effectiveNow(OffsetDateTime from, OffsetDateTime to, OffsetDateTime now) {
    if (from == null || now == null) return false;
    if (now.isBefore(from)) return false;
    return to == null || now.isBefore(to);
  }

  /**
   * 归一化适用范围：去空白、去重（同一张表里同一个对象只登记一次）、排序。
   *
   * <p>排序口径：法人 → 店铺 → 站点（业务层级从粗到细），同类按编码排 ——
   * 页面上的顺序与运营的思维顺序一致，diff 也不会因为插入顺序而抖动。
   *
   * @throws IllegalArgumentException 缺类型、缺编码，或法人/站点带了不该有的平台码
   */
  public static List<ScopeInput> normalizeScopes(List<ScopeInput> scopes) {
    if (scopes == null || scopes.isEmpty()) return List.of();
    var unique = new LinkedHashMap<String, ScopeInput>();
    for (var scope : scopes) {
      if (scope == null || scope.type() == null) throw new IllegalArgumentException("适用对象缺类型");
      var ref = scope.ref() == null ? "" : scope.ref().trim();
      if (ref.isEmpty()) {
        throw new IllegalArgumentException("适用对象必须给编码（法人编码 / 店铺编码 / 站点码）");
      }
      var platform =
          scope.platformCode() == null || scope.platformCode().isBlank()
              ? null
              : scope.platformCode().trim().toUpperCase(Locale.ROOT);
      if (scope.type() == ScopeType.LEGAL_ENTITY && platform != null) {
        throw new IllegalArgumentException("法人没有平台 —— 平台码只属于店铺与站点");
      }
      if (scope.type() == ScopeType.SITE && platform == null) {
        throw new IllegalArgumentException("站点必须给平台码（站点一定挂在某个平台下）");
      }
      var key = scope.type().name() + "|" + ref;
      if (unique.containsKey(key)) {
        throw new IllegalArgumentException(
            "适用对象重复：" + scope.type().label() + " " + ref + "（同一张价目表里只登记一次）");
      }
      unique.put(key, new ScopeInput(scope.type(), ref, platform));
    }
    var out = new ArrayList<>(unique.values());
    out.sort(
        (a, b) -> {
          var byType = Integer.compare(typeOrder(a.type()), typeOrder(b.type()));
          return byType != 0 ? byType : a.ref().compareToIgnoreCase(b.ref());
        });
    return out;
  }

  /** 范围类型的排序权重：法人 → 店铺 → 站点。 */
  private static int typeOrder(ScopeType type) {
    return switch (type) {
      case LEGAL_ENTITY -> 0;
      case SHOP -> 1;
      case SITE -> 2;
    };
  }

  /**
   * 覆盖范围摘要（蓝图输出句）。
   *
   * @param scopes 这张价目表的适用范围（读出来的形态）
   */
  public static CoverageSummary coverageOf(List<ScopeView> scopes) {
    int legalEntities = 0, shops = 0, sites = 0;
    if (scopes != null) {
      for (var scope : scopes) {
        if (scope == null || scope.type() == null) continue;
        switch (scope.type()) {
          case LEGAL_ENTITY -> legalEntities++;
          case SHOP -> shops++;
          case SITE -> sites++;
        }
      }
    }
    var total = legalEntities + shops + sites;
    return new CoverageSummary(
        total,
        legalEntities,
        shops,
        sites,
        total == 0,
        coverageText(legalEntities, shops, sites));
  }

  /** 一句话覆盖摘要（页面上直接显示）。 */
  public static String coverageText(int legalEntities, int shops, int sites) {
    if (legalEntities + shops + sites == 0) return "全租户（未限定法人 / 店铺 / 站点）";
    var parts = new ArrayList<String>();
    if (legalEntities > 0) parts.add(legalEntities + " 法人");
    if (shops > 0) parts.add(shops + " 店铺");
    if (sites > 0) parts.add(sites + " 站点");
    return String.join(" / ", parts);
  }
}
