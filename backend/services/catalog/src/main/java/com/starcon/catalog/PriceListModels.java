package com.starcon.catalog;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 5.1.1.1 价目表列表。
 *
 * <p>蓝图原文：
 *
 * <blockquote>
 *
 * **职责**：管理价目表（如"北美自发货零售价"），每张表绑定一个币种、适用法人/店铺/站点、生效区间，展示表内价目项数量与最近更新时间。
 *
 * **输入**：价目表增删改；币种与适用范围选择；生效区间设置。
 *
 * **输出**：价目表记录 + 覆盖范围摘要。
 *
 * **关系**：上游 = —（价目表自身的增删改只依赖法人/店铺/站点主数据，卷 02/03 已交付）；下游 = 5.1.1.2 价目项、5.3.2.1 定价模板。**一价目一币种**是硬约束——混币种的价目表会让利润计算失效。
 *
 * </blockquote>
 *
 * <h2>🔴 本类的四条纪律</h2>
 *
 * <ol>
 *   <li><b>🔴 一价目一币种</b>：价目表头只有<b>一个</b>币种字段（{@code currency}），
 *       不是数组、不是明细表、不是 JSON。混币种在 DB 层被命名约束
 *       {@code price_list_one_currency} 挡住（23514）。价目项（5.1.1.2）**不得**自带币种列 ——
 *       币种只能来自这里。
 *   <li><b>🔴 生效区间是半开区间</b>：{@code [validFrom, validTo)} ——
 *       起点含、终点不含。所以 {@code validFrom == 此刻} 是生效的，
 *       而 {@code validTo == 此刻} 已经不生效了。边界值有测试钉死。
 *   <li><b>🔴 表内价目项数量来自计数器</b>：{@code itemCount} 由 5.1.1.2 的价目项写入
 *       经触发器 {@code price_list_sync_item_count()} 维护。价目项模块未交付前它恒为 0 ——
 *       那是<b>真实的 0</b>（表里确实还没有价目项），不是「取不到」。
 *   <li><b>🔴 适用范围用编码而不是外键</b>：法人在 identity、店铺与站点在 channel，
 *       跨服务 FK 被 AGENTS.md 禁止。适用范围为空 = <b>适用于全租户</b>（显式语义）。
 * </ol>
 */
public final class PriceListModels {

  private PriceListModels() {}

  // ================================================================ 词表

  /** 适用对象的类型（蓝图输入句：币种与适用范围选择；范围 = 法人 / 店铺 / 站点）。 */
  public enum ScopeType {
    /** 法人：17.1.2.2 identity 的 legal_entity 编码。 */
    LEGAL_ENTITY("法人"),
    /** 店铺：2.3.1.1 channel 的 shop 编码。 */
    SHOP("店铺"),
    /** 站点：2.1.1.1 channel 的 marketplace（站点）码。 */
    SITE("站点");

    private final String label;

    ScopeType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static ScopeType parse(String raw) {
      if (raw == null) return null;
      for (var t : values()) {
        if (t.name().equalsIgnoreCase(raw.trim())) return t;
      }
      return null;
    }
  }

  // ================================================================ 输入

  /** 一条适用对象（输入）。编码来自上游主数据，服务层会核对它真实存在。 */
  public record ScopeInput(
      @NotNull ScopeType type,
      @NotBlank String ref,
      /** 平台码：站点必填（站点一定挂在某个平台下），店铺可填，法人必须为空。 */
      @Schema(nullable = true) String platformCode) {}

  /**
   * 价目表定义（新建与修改共用同一个形状）。
   *
   * <p><b>修改是整体替换</b>：适用范围在同一个事务里「要么全换要么全不换」
   * （与 5.3.2.1 定价模板的适用对象同一手法）。
   *
   * <p>🔴 这里**没有** {@code itemCount} 字段：表内价目项数量是**读出来的事实**，
   * 由 5.1.1.2 的价目项写入维护。客户端不能自己写（Jackson 遇到未知字段直接 400）。
   */
  public record PriceListInput(
      @NotBlank String code,
      @NotBlank String name,
      String description,
      /** 币种：**一张表一个币种**（ISO 4217 三位大写字母）。 */
      @NotNull @Pattern(regexp = "^[A-Za-z]{3}$", message = "币种必须是三位大写字母") String currency,
      /** 生效起点（**含**）。 */
      @NotNull OffsetDateTime validFrom,
      /** 生效终点（**不含**）。空 = 长期有效。 */
      @Schema(nullable = true) OffsetDateTime validTo,
      Boolean enabled,
      String note,
      @Valid List<ScopeInput> scopes,
      /** 乐观锁版本（修改时必填；新建时忽略）。 */
      Long version) {}

  // ================================================================ 输出

  /** 价目表列表的一行。 */
  public record PriceListSummary(
      UUID id,
      String code,
      String name,
      String currency,
      OffsetDateTime validFrom,
      @Schema(nullable = true) OffsetDateTime validTo,
      boolean enabled,
      /** 表内价目项数量（由 5.1.1.2 维护；未交付前恒为 0 = 真实的 0）。 */
      int itemCount,
      /** 覆盖范围摘要。 */
      CoverageSummary coverage,
      /** 此刻是否生效（半开区间判定）。 */
      boolean effectiveNow,
      @Schema(nullable = true) String createdBy,
      OffsetDateTime updatedAt) {}

  /** 一条适用对象（读）。 */
  public record ScopeView(
      UUID id,
      ScopeType type,
      String typeLabel,
      String ref,
      String name,
      @Schema(nullable = true) String platformCode) {}

  /**
   * 覆盖范围摘要（蓝图的输出句：「价目表记录 + 覆盖范围摘要」）。
   *
   * @param scopeTotal 适用对象总条数
   * @param legalEntityCount 其中法人条数
   * @param shopCount 其中店铺条数
   * @param siteCount 其中站点条数
   * @param appliesToWholeTenant <b>没有任何适用对象</b> ⇒ 这张表对全租户生效（显式语义，不是漏填）
   * @param text 一句话摘要（页面上直接显示）
   */
  public record CoverageSummary(
      int scopeTotal,
      int legalEntityCount,
      int shopCount,
      int siteCount,
      boolean appliesToWholeTenant,
      String text) {}

  /** 价目表详情 = 表头 + 适用范围 + 覆盖范围摘要。 */
  public record PriceListDetail(
      PriceListSummary summary,
      String description,
      String currency,
      OffsetDateTime validFrom,
      @Schema(nullable = true) OffsetDateTime validTo,
      boolean enabled,
      String note,
      long version,
      List<ScopeView> scopes,
      CoverageSummary coverage,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 价目表列表（分页）。 */
  public record PriceListPage(List<PriceListSummary> items, int page, int size, long total) {}

  // ================================================================ 适用范围目录（上游主数据）

  /** 一个法人（identity 的 legal_entity）。 */
  public record PriceListLegalEntity(
      UUID id, String code, String name, @Schema(nullable = true) String baseCurrency, String status) {}

  /** 一个店铺（channel 的 shop）。 */
  public record PriceListShop(
      String code,
      String name,
      String platformCode,
      @Schema(nullable = true) String legalEntityCode,
      @Schema(nullable = true) String legalEntityName,
      String status,
      List<String> siteCodes) {}

  /** 一个站点（channel 的 marketplace）。 */
  public record PriceListSite(
      String platformCode,
      String platformName,
      String siteCode,
      String siteName,
      @Schema(nullable = true) String countryCode,
      @Schema(nullable = true) String defaultCurrency) {}

  /**
   * 适用范围目录：法人 + 店铺 + 站点。
   *
   * <p>🔴 三个上游读不到时**分别**报「读不到」，绝不用空列表冒充「没有法人 / 没有店铺 / 没有站点」。
   */
  public record ScopeDirectory(
      List<PriceListLegalEntity> legalEntities,
      List<PriceListShop> shops,
      List<PriceListSite> sites,
      String sourceNote) {}

  // ================================================================ 覆盖范围摘要（模块级）

  /** 按币种分布的一档。 */
  public record CurrencyCoverage(String currency, int listCount) {}

  /** 按范围类型分布的一档。 */
  public record ScopeTypeCoverage(ScopeType type, String typeLabel, int listCount) {}

  /** 覆盖范围摘要里的一张价目表。 */
  public record CoverageEntry(
      UUID id,
      String code,
      String name,
      String currency,
      boolean enabled,
      boolean effectiveNow,
      int itemCount,
      CoverageSummary coverage) {}

  /** 模块级覆盖范围摘要。 */
  public record CoverageOverview(
      int listCount,
      int enabledCount,
      int effectiveNowCount,
      int totalItemCount,
      List<CurrencyCoverage> currencies,
      List<ScopeTypeCoverage> scopeTypes,
      List<CoverageEntry> lists,
      String note) {}

  // ================================================================ 能力声明

  /** 能力声明里的一条口径说明。 */
  public record CapabilityNote(String title, String content) {}

  /** 能力声明。页面顶部固定显示它。 */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record PriceListCapabilities(
      List<ScopeType> scopeTypes,
      List<CapabilityNote> notes,      String currencyNote,
      String validityNote,
      String itemCountNote,
      String scopeNote,
      String upstreamNote,
      String downstreamNote,
      String conflictNote) {}
}
