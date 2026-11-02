package com.starcon.platform;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 18.4.1.2「智能查询」的传输模型。
 *
 * <p>蓝图 18-integration-and-automation.md 18.4.1.2 原文：
 *
 * <blockquote>
 *
 * 职责：结构化智能查询：自然语言转查询条件（筛选、聚合、排序），生成报表草稿，支持保存为常用查询。
 * 输入：自然语言查询意图；可用指标与维度清单。 输出：查询结果表 / 报表配置草稿。
 * 关系：上游 = 18.4.1.1；下游 = 13.6.1.1 自定义报表。
 * <b>自然语言只映射到受控语义层指标，不生成任意 SQL</b>。
 *
 * </blockquote>
 *
 * <p><b>命名。</b>统一用 {@code SmartQuery*} 前缀 —— 与兄弟模块 {@code Assistant*}（18.4.1.1）
 * 刻意区分：那一条是「问一句话得一个答案」，本条是「把一句话变成**可执行的查询条件**」。
 *
 * <p><b>🔴 本模块一个 {@code double} 都没有</b>：金额与数量一律 {@link BigDecimal}
 * （库里 {@code NUMERIC(19,6)}），比率口径 {@code NUMERIC(7,4)}，时间一律 {@link OffsetDateTime}。
 *
 * <p><b>🔴 数值对外一律是十进制文本</b>（{@code String}），不是 JSON number ——
 * 走 JSON number 会在浏览器里被解析成 double 而掉精度，而金额与比率是判定口径
 * （同 18.4.1.1 的 {@code AssistantEvidence.metricValue} 同一纪律）。
 *
 * <p><b>🔴 「不支持这个问法」是一种结果形态</b>：{@link SmartQueryResult#resolved()} 为
 * {@code false} 时，{@code rows} 为空、{@code evidence} 为空、{@code conditions} 为 null，
 * 并且 {@code unavailableReason} 写明「缺什么」、{@code ownerModule} 写明「归谁」。
 * <b>它不是一个「查过了」的结果</b> —— 一条假的成功回执会让「AI 已经查过了」
 * 在数据上看起来发生过，而现场一个字都没查出来。
 */
public final class SmartQueryModels {

  private SmartQueryModels() {}

  // ================================================================ 能力声明

  /**
   * 一项能力（或一项「还没接通」的能力）。
   *
   * @param available 该能力**现在**能不能用（不是「将来会有」）
   * @param evidence 凭什么这么判（端点 / 表 / 规则的具体名字）
   * @param ownerModule 还没接通的能力，**缺在哪个模块**
   */
  public record SmartQueryCapability(
      String moduleId, String name, boolean available, String evidence, String ownerModule, String note) {}

  /** 一个「还没交付」的下游 / 兄弟能力。 */
  public record SmartQueryGap(
      String moduleId, String name, String service, boolean available, String relation, String note) {}

  /** 能力声明总览。 */
  public record SmartQueryCapabilities(List<SmartQueryCapability> capabilities, List<SmartQueryGap> gaps) {}

  // ================================================================ 受控语义层目录

  /**
   * 受控语义层里的一个**指标**。
   *
   * @param available 这个指标在**当前租户**的口径表里登记了吗（没登记 ⇒ 未接通）
   * @param unavailableReason 没登记时写明「缺什么 + 归谁」
   */
  public record SmartQueryMetricView(
      String metricCode,
      String displayName,
      String unit,
      String aggregation,
      boolean currencyAware,
      List<String> strongKeywords,
      List<String> weakKeywords,
      String sourceService,
      String sourceEndpoint,
      String sourceCaliber,
      String detailRoute,
      String ownerModule,
      boolean available,
      String unavailableReason) {}

  /**
   * 受控语义层里的一个**维度**（13.6.1.1 的同一套 5 个白名单码）。
   *
   * @param valueKind TEXT / UUID / DATE —— 决定「筛选值能从自然语言里解析出什么」
   */
  public record SmartQueryDimensionView(
      String dimensionCode,
      String displayName,
      String valueKind,
      List<String> allowedOperators,
      List<String> strongKeywords,
      List<String> weakKeywords,
      String ownerModule,
      boolean available,
      String unavailableReason) {}

  /** 受控语义层目录总览（**这是「能问什么」的唯一真相**）。 */
  public record SmartQuerySemanticLayer(
      List<SmartQueryMetricView> metrics,
      List<SmartQueryDimensionView> dimensions,
      List<String> rules,
      List<SmartQueryGap> gaps) {}

  // ================================================================ 查询条件

  /**
   * 一条筛选条件（**词表里的码 + 绑定值**，不是 SQL 片段）。
   *
   * @param operator 13.6.1.1 的同一套白名单：EQ / IN / GTE / LTE / BETWEEN
   */
  public record SmartQueryFilter(
      String dimensionCode, String operator, String valueText, String valueText2) {}

  /**
   * 排序。
   *
   * @param by METRIC（按指标值）/ DIMENSION（按维度值）
   * @param direction ASC / DESC
   */
  public record SmartQuerySort(String by, String direction) {}

  /**
   * 解析出来的**受控查询条件**（筛选 + 聚合 + 排序 + 时间窗口）。
   *
   * @param aggregation 聚合方式：口径表登记的那个（SUM / LAST / DERIVED_DIFF / DERIVED_RATIO）——
   *     本模块**不自己定**聚合口径，只把它显示出来
   */
  public record SmartQueryConditions(
      String metricCode,
      String metricName,
      String unit,
      String aggregation,
      boolean currencyAware,
      String dimensionCode,
      String dimensionName,
      List<SmartQueryFilter> filters,
      SmartQuerySort sort,
      String periodCode,
      LocalDate periodFrom,
      LocalDate periodTo,
      String caliber) {}

  // ================================================================ 提问

  /**
   * 提问输入。
   *
   * <p>🔴 **没有 tenantId / userId / membershipId**：租户与用户来自**已认证会话**
   * （{@code AccessService.require}），请求体里出现这三个字段会被
   * {@code fail-on-unknown-properties} 直接 400 —— 客户端自称的租户不是租户。
   *
   * @param metricCode 显式指定受控指标码（可选；不传则按封闭词表确定性匹配）
   * @param dimensionCode 显式指定受控维度码（可选；不传则按封闭词表确定性匹配）
   */
  public record SmartQueryAskInput(
      @Schema(description = "中文问句（受控问法；解析不到受控条目会如实答「不支持这个问法」）")
          String question,
      @Schema(description = "显式指定受控指标码（可选）") String metricCode,
      @Schema(description = "显式指定受控维度码（可选）") String dimensionCode) {}

  // ================================================================ 结果

  /** 一个维度列。 */
  public record SmartQueryDimensionColumn(String dimensionCode, String label) {}

  /** 一个度量列（**已登记的 metric_code** + 口径说明）。 */
  public record SmartQueryMetricColumn(
      String metricCode, String displayName, String unit, boolean currencyAware, String description) {}

  /** 一个维度取值。 */
  public record SmartQueryDimensionValue(String dimensionCode, String label, String value) {}

  /** 一个币种上的取值（**十进制文本**；空串 = 与货币无关的跨币种合计）。 */
  public record SmartQueryCurrencyValue(
      String currency,
      @Schema(nullable = true) String value,
      String availability,
      String availabilityNote) {}

  /** 一个度量的取值。 */
  public record SmartQueryMeasureValue(
      String metricCode,
      String displayName,
      String unit,
      boolean currencyAware,
      List<SmartQueryCurrencyValue> values,
      String availability,
      String availabilityNote) {}

  /** 结果表的一行 = 一个维度组合。 */
  public record SmartQueryResultRow(
      int seq, List<SmartQueryDimensionValue> dimensions, List<SmartQueryMeasureValue> measures) {}

  /**
   * 一条明细依据（**可下钻**的事实）。
   *
   * @param factTable **真表名**（下钻的第一跳）
   * @param factField **真字段名**（下钻的第二跳）
   * @param detailRoute 前端**真实存在**的页面（可点的明细链接）
   * @param metricValue {@code null} = 这个数算不出来（**不是 0**）
   */
  public record SmartQueryEvidence(
      String factService,
      String factTable,
      String factField,
      String factRef,
      String factLabel,
      String metricCode,
      @Schema(nullable = true) String metricValue,
      String metricUnit,
      String detailRoute,
      String detailLabel,
      String caliberNote,
      OffsetDateTime snapshotAt) {}

  /** 建议的下一步操作（**只显示后端给的**，前端不自己编路由）。 */
  public record SmartQueryNextAction(String label, String route, String moduleId) {}

  /**
   * 一次智能查询的结果。
   *
   * @param resolved 这一轮**出没出结论**。解析不到受控条目 ⇒ {@code false}，
   *     {@code rows} 与 {@code evidence} 都为空，{@code unavailableReason} 写明「缺什么 + 归谁」。
   */
  public record SmartQueryResult(
      String queryId,
      String question,
      String matchMode,
      boolean resolved,
      SmartQueryConditions conditions,
      String reportId,
      String reportCode,
      List<SmartQueryDimensionColumn> dimensionColumns,
      List<SmartQueryMetricColumn> metricColumns,
      List<SmartQueryResultRow> rows,
      long total,
      int page,
      int size,
      String status,
      String statusNote,
      @Schema(nullable = true) OffsetDateTime dataUpdatedAt,
      String dataUpdatedNote,
      List<String> caliberNotes,
      List<SmartQueryEvidence> evidence,
      List<SmartQueryNextAction> nextActions,
      String unavailableReason,
      String ownerModule) {}

  // ================================================================ 台账与常用查询

  /**
   * 保存为常用查询的入参。
   *
   * <p>🔴 **没有 tenantId / userId**：归属一律来自**已认证会话**
   * （请求体里出现这两个字段会被 {@code fail-on-unknown-properties} 直接 400）。
   */
  public record SmartQuerySaveInput(
      @Schema(description = "常用查询的名字（1–120 字）") String displayName) {}

  /** 查询台账 / 常用查询清单里的一行。 */
  public record SmartQueryQueryView(
      String queryId,
      String ownerUserId,
      String question,
      String displayName,
      boolean saved,
      String matchMode,
      boolean resolved,
      String metricCode,
      String metricName,
      String dimensionCode,
      String dimensionName,
      String periodCode,
      LocalDate periodFrom,
      LocalDate periodTo,
      String reportId,
      String reportCode,
      String unavailableReason,
      String ownerModule,
      int evidenceCount,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 查询台账 / 常用查询清单（**真分页 + 真计数**）。 */
  public record SmartQueryPage(
      List<SmartQueryQueryView> rows, long total, int page, int size, String keyword, Boolean saved) {}
}
