package com.starcon.catalog;

import java.util.List;
import java.util.UUID;

/**
 * 「店铺启用站点 → 需要哪些语言」的读取端口。
 *
 * <p>蓝图 3.4.3.1 把这条写成了硬要求：「**需要哪些语言由店铺启用站点推导，不靠人肉记忆。**」 但站点是 <b>02 渠道</b>的事实（{@code shop} / {@code
 * shop_marketplace} / {@code marketplace.default_locale} 都在 channel 服务里）， catalog 禁止跨服务 SQL
 * 访问（AGENTS.md 模块边界纪律）。
 *
 * <p>所以这里只定义端口：02 渠道落地后用真实适配器替换实现即可， 推导语言清单的页面与合并视图无需改动。
 *
 * <p><b>未接入时取不到就是取不到。</b>{@link UnwiredTranslationLocaleProjectionPort} 显式声明未接入， 接口返回 {@code
 * available=false} 并写明归 02 渠道，而不是回 0 或空数组： 回 0 会被读成「这个租户没有店铺站点所以没有语言需求」，
 * 空数组会被读成「不需要做多语言」——两者都是编造出来的事实， 而错误的方向恰恰是本模块最危险的方向（让人以为不用做多语言）。
 */
public interface TranslationLocaleProjectionPort {

  /**
   * 某个站点推导出来的语言。
   *
   * @param locale 站点默认语言的 BCP-47 标签
   * @param localeName 站点/市场给出的语言名（若渠道侧没有，回空串由前端兜底）
   * @param shopCount 用到这种语言的**启用店铺数**（0 表示站点在用但没有启用店铺）
   * @param marketplaceCount 用到这种语言的**启用站点数**
   */
  record DerivedLocale(String locale, String localeName, int shopCount, int marketplaceCount) {}

  /**
   * 站点推导结果。
   *
   * @param available 02 渠道是否已接入；false 表示"取不到"，**不是**"没有语言需求"
   * @param rows 推导出的语言（未接入时为空，且必须配合 available=false 一起读）
   * @param note 未接入时说明归 02 渠道、哪个阶段
   */
  record Projection(boolean available, List<DerivedLocale> rows, String note) {

    /** 统一的构造：未接入时 rows 必须为空，避免「未接入但有数据」这种自相矛盾的组合。 */
    static Projection unwired(String note) {
      return new Projection(false, List.of(), note);
    }
  }

  /** 某租户由启用站点推导出的语言清单。未接入时必须返回 available=false。 */
  Projection derivedLocales(UUID tenantId);

  /** 未接入时给界面看的一句话：说明归属与为什么不显示语言列表。 */
  default String unwiredNote() {
    return "上游投影未接入：店铺启用站点 → 语言的推导，事实来源是 02 渠道（shop / shop_marketplace / "
        + "marketplace.default_locale），该服务本阶段尚未接入 catalog，catalog 无权威数据。"
        + "在此之前请用「人工维护」登记租户实际需要的语言——那是本模块可写入的一侧，"
        + "而不是编一份站点推导结果。";
  }
}
