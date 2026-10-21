package com.starcon.channel;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 4.4.1.1 链接采集 的传输模型。
 *
 * <p>蓝图：「粘贴外部商品链接（1688/淘宝/供应商站/同行 listing），抓取标题、主图、规格、属性、描述等，生成采集草稿供认领为内部商品。输入：商品
 * URL（支持平台白名单）；抓取任务。输出：采集到的商品草稿（图片下载入媒体库）；来源 URL 与抓取时间。关系：上游 = 外部页面；下游 =
 * 4.4.1.3 采集箱、3.1.1.1（认领为 SPU）。<b>采集只产生草稿</b>，进入正式商品库必须走 03 的建档流程并补全属性与合规信息。」
 *
 * <p>🔴 模型层就把三条边界钉死，它们是本模块最容易在赶工时被做丢的：
 *
 * <ol>
 *   <li><b>只有草稿，没有商品</b>：本文件的 record 里**没有** skuId / spuId / internalSkuId
 *       之类的商品主数据字段，也**没有**"已发布"之类的商品生命周期状态 —— 采集任务的 state 只有
 *       {@code SUCCEEDED} / {@code FAILED}，说的是**抓取本身**成没成，不是商品上架没上架。
 *   <li><b>图片只记 URL</b>：{@code mainImageUrl} / {@code imageUrls} 是**外部图片地址**，不是媒体库
 *       条目。媒体库归 03 catalog，跨服务写表被 AGENTS.md 禁止。
 *   <li><b>没有我方售价</b>：{@code sourcePriceAmount} 是**外部页面原文标价**（源站上的价格），
 *       不是我方售价。售价归 05 定价（整卷未交付），本模块不产出任何我方售价。
 * </ol>
 *
 * <p>载荷一律用具名 {@code record}，不用 {@code Map<String,Object>}：Map 的值类型会被 springdoc 推成
 * object，codegen 出来是空对象 {@code {}}（9.6.1.3 已踩过）。
 */
public final class ListingCollectModels {
  private ListingCollectModels() {}

  /** 抓取结果状态的取值域：只说抓取成败，不说商品生命周期。 */
  public static final List<String> STATES = List.of("SUCCEEDED", "FAILED");

  /** 白名单行的平台类别。 */
  public static final List<String> SOURCE_KINDS = List.of("PLATFORM", "SUPPLIER", "PEER");

  // ================================================================ 能力声明

  public record ListingCollectCapabilityDimension(
      @Schema(description = "维度键") String dimension,
      @Schema(description = "维度说明") String description,
      @Schema(description = "本模块是否已交付") boolean available,
      @Schema(description = "数据来源（表 / 上游模块）") String source,
      @Schema(description = "未交付段的责任模块") String ownerModule,
      @Schema(description = "不交付的影响") String impact) {}

  /** 白名单里的一行（前端据此告诉用户「哪些站点可以采」）。 */
  public record ListingCollectSourceRow(
      @Schema(description = "白名单编码") String code,
      @Schema(description = "来源名称") String name,
      @Schema(description = "允许抓取的域名") String host,
      @Schema(description = "平台类别：PLATFORM / SUPPLIER / PEER") String kind,
      @Schema(description = "同一租户同一 host 的最小抓取间隔（秒）") int minIntervalSeconds) {}

  public record ListingCollectCapabilities(
      @Schema(description = "逐维度能力声明；未交付段 available=false")
          List<ListingCollectCapabilityDimension> dimensions,
      @Schema(description = "平台白名单（当前允许抓取的来源）") List<ListingCollectSourceRow> sources,
      @Schema(description = "抓取结果状态取值域") List<String> states,
      @Schema(description = "🔴 是否保证只产生草稿、不产生商品") boolean draftOnly,
      @Schema(description = "🔴 草稿边界的界面措辞（前端原样展示）") String draftNote,
      @Schema(description = "🔴 图片是否已下载入媒体库（恒为 false）") boolean mediaDownloaded,
      @Schema(description = "🔴 媒体库归属与未交付原因") String mediaNote,
      @Schema(description = "🔴 是否做同 URL 去重") boolean deduplicated,
      @Schema(description = "🔴 去重规则的界面措辞") String dedupNote,
      @Schema(description = "🔴 是否做同 host 限频") boolean rateLimited,
      @Schema(description = "🔴 限频规则的界面措辞") String rateLimitNote,
      @Schema(description = "上游依赖说明") String upstreamNote) {}

  // ================================================================ 请求

  /**
   * 发起一次链接采集。
   *
   * <p>🔴 请求体里只有**一个** URL：没有「顺便建商品」的字段，没有「顺便设价格」的字段。
   */
  public record CollectListingRequest(
      @Schema(description = "🔴 外部商品链接（http / https；主机必须命中平台白名单）")
          @NotBlank
          @Size(max = 2000)
          String url,
      @Schema(description = "备注") @Size(max = 500) String note) {}

  // ================================================================ 结果

  /** 采集历史的摘要行（列表页用；不含描述与图片列表，避免整页搬运大文本）。 */
  public record ListingCollectSummaryRow(
      @Schema(description = "采集任务 id") UUID taskId,
      @Schema(description = "来源 URL 原文") String sourceUrl,
      @Schema(description = "🔴 规范化 URL（去跟踪参数后的去重键）") String normalizedUrl,
      @Schema(description = "来源主机（去端口）") String host,
      @Schema(description = "命中的白名单编码") String sourceCode,
      @Schema(description = "命中的白名单名称") String sourceName,
      @Schema(description = "🔴 抓取结果：SUCCEEDED / FAILED（不是商品生命周期）") String state,
      @Schema(description = "失败原因（成功时为空串）") String errorMessage,
      @Schema(description = "外部站点的 HTTP 状态码（网络层失败时为 0）") int httpStatus,
      @Schema(description = "第几次尝试同一个规范化 URL") int attemptNo,
      @Schema(description = "🔴 抓到的标题（成功时非空）") String title,
      @Schema(description = "🔴 抓取时刻") OffsetDateTime capturedAt,
      @Schema(description = "创建时间") OffsetDateTime createdAt) {}

  /** 采集任务（不含草稿内容）。 */
  public record ListingCollectTaskRow(
      @Schema(description = "采集任务 id") UUID taskId,
      @Schema(description = "来源 URL 原文") String sourceUrl,
      @Schema(description = "🔴 规范化 URL（去跟踪参数后的去重键）") String normalizedUrl,
      @Schema(description = "跟随重定向后的最终 URL") String finalUrl,
      @Schema(description = "来源主机（去端口）") String host,
      @Schema(description = "命中的白名单编码") String sourceCode,
      @Schema(description = "命中的白名单名称") String sourceName,
      @Schema(description = "🔴 抓取结果：SUCCEEDED / FAILED（不是商品生命周期）") String state,
      @Schema(description = "失败原因（成功时为空串）") String errorMessage,
      @Schema(description = "外部站点的 HTTP 状态码（网络层失败时为 0）") int httpStatus,
      @Schema(description = "第几次尝试同一个规范化 URL") int attemptNo,
      @Schema(description = "🔴 采集草稿 id（失败时为 null）") UUID draftId,
      @Schema(description = "🔴 抓取时刻") OffsetDateTime capturedAt,
      @Schema(description = "抓取开始时刻") OffsetDateTime startedAt,
      @Schema(description = "抓取结束时刻") OffsetDateTime finishedAt,
      @Schema(description = "创建时间") OffsetDateTime createdAt) {}

  /**
   * 规格 / 属性的一个键值项。
   *
   * <p>🔴 **为什么是具名 record 而不是 {@code Map<String,String>}**：Map 的值类型会被
   * springdoc 推成 object，codegen 出来是空对象 {@code {}}（9.6.1.3 已踩过），
   * 前端在 strict 模式下按字符串键索引它会直接编译不过。键值项用具名 record，
   * codegen 出来是 {@code { "name": string; "value": string }}，前端能直接用。
   */
  public record ListingCollectKeyValue(
      @Schema(description = "键（如「材质」「品牌」）") String name,
      @Schema(description = "值（原文照抄）") String value) {}

  /**
   * 采集草稿的内容。
   *
   * <p>🔴 这**不是**商品：没有 sku / spu / 内部可售量 / 我方售价。
   */
  public record ListingCollectDraftRow(
      @Schema(description = "采集草稿 id") UUID draftId,
      @Schema(description = "所属采集任务 id") UUID taskId,
      @Schema(description = "来源 URL 原文") String sourceUrl,
      @Schema(description = "🔴 规范化 URL（去重键）") String normalizedUrl,
      @Schema(description = "🔴 抓到的标题") String title,
      @Schema(description = "🔴 主图 URL（只记 URL，不下载二进制）") String mainImageUrl,
      @Schema(description = "🔴 附图 URL 列表（只记 URL，不下载二进制）") List<String> imageUrls,
      @Schema(description = "🔴 规格（键值项列表）") List<ListingCollectKeyValue> specs,
      @Schema(description = "🔴 属性（键值项列表）") List<ListingCollectKeyValue> attributes,
      @Schema(description = "🔴 商品描述") String description,
      @Schema(description = "🔴 外部页面原文标价（**不是我方售价**）") BigDecimal sourcePriceAmount,
      @Schema(description = "🔴 外部页面原文标价币种") String sourcePriceCurrency,
      @Schema(description = "🔴 抓取时刻") OffsetDateTime capturedAt,
      @Schema(description = "创建时间") OffsetDateTime createdAt) {}

  /**
   * 一次链接采集的结果。
   *
   * @param task 采集任务（成功与失败都有）
   * @param draft 采集草稿；抓取失败时为 null
   * @param deduplicated 🔴 本次请求**没有**发出 HTTP 请求 —— 规范化 URL 已有成功记录，直接返回既有草稿
   */
  public record CollectListingResult(
      @Schema(description = "采集任务") ListingCollectTaskRow task,
      @Schema(description = "🔴 采集草稿（失败时为 null）") ListingCollectDraftRow draft,
      @Schema(description = "🔴 是否因同 URL 去重而未发出请求") boolean deduplicated) {}

  /** 采集历史列表（真分页 + 真筛选）。 */
  public record ListingCollectPage(
      @Schema(description = "当前页数据") List<ListingCollectSummaryRow> items,
      @Schema(description = "符合筛选条件的总条数（不受分页影响）") long total,
      @Schema(description = "页码，从 0 起") int page,
      @Schema(description = "每页条数") int size) {}
}
