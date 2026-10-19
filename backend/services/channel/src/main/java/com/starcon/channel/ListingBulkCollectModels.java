package com.starcon.channel;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 4.4.1.2 批量/插件采集 的传输模型。
 *
 * <p>蓝图：「支持按分类页、关键词搜索结果批量采集，以及浏览器插件在用户浏览时抓取当前页商品，解决大批量找货场景。输入：分类页 URL /
 * 关键词 + 页数范围；插件上报的页面数据。输出：批量采集任务 + 结果清单。关系：上游 = 4.4.1.1；下游 = 4.4.1.3。**抓取必须有频率限制与去重**
 * （同 URL 不重复抓、已存在的商品跳过），避免重复劳动与外部站点压力。」
 *
 * <p>🔴 模型层就把边界钉死，它们是本模块最容易在赶工时被做丢的：
 *
 * <ol>
 *   <li><b>只有草稿，没有商品</b>：本文件的 record 里**没有** skuId / spuId / internalSkuId
 *       之类的商品主数据字段，也**没有**「已发布」之类的商品生命周期状态。批量任务的 state
 *       说的是<b>这次批量采集跑到哪了</b>（PENDING / RUNNING / SUCCEEDED / PARTIAL / FAILED /
 *       RATE_LIMITED），不是商品上架没上架。
 *   <li><b>图片只记 URL</b>：{@code imageUrl} 是**外部图片地址**，不是媒体库条目。媒体库归
 *       03 catalog，跨服务写表被 AGENTS.md 禁止。
 *   <li><b>没有我方售价</b>：{@code priceAmount} 是**外部页面原文标价**（源站上的价格），
 *       不是我方售价。售价归 05 定价（整卷未交付）。
 * </ol>
 *
 * <p>载荷一律用具名 {@code record}，不用 {@code Map<String,Object>}：Map 的值类型会被 springdoc 推成
 * object，codegen 出来是空对象 {@code {}}（9.6.1.3 已踩过）。
 */
public final class ListingBulkCollectModels {
  private ListingBulkCollectModels() {}

  /** 批量采集的三种入口模式。 */
  public static final List<String> MODES = List.of("CATEGORY_PAGE", "KEYWORD", "PLUGIN");

  /** 批量采集任务的状态取值域（说的是**采集跑到哪了**，不是商品生命周期）。 */
  public static final List<String> STATES =
      List.of("PENDING", "RUNNING", "SUCCEEDED", "PARTIAL", "FAILED", "RATE_LIMITED");

  /** 结果清单每一条的取值域。 */
  public static final List<String> RESULT_STATES =
      List.of("FETCHED", "SKIPPED_DUPLICATE", "FAILED", "SKIPPED_RATE_LIMITED");

  // ================================================================ 能力声明

  public record BulkCollectCapabilityDimension(
      @Schema(description = "维度键") String dimension,
      @Schema(description = "维度说明") String description,
      @Schema(description = "本模块是否已交付") boolean available,
      @Schema(description = "数据来源（表 / 上游模块）") String source,
      @Schema(description = "未交付段的责任模块") String ownerModule,
      @Schema(description = "不交付的影响") String impact) {}

  /**
   * 平台白名单行 + 该平台的批量采集参数。
   *
   * <p>前端据此告诉用户「这个平台能怎么批量采」「每页怎么翻」「两次抓取至少隔多久」。
   */
  public record BulkCollectSourceRow(
      @Schema(description = "白名单编码") String code,
      @Schema(description = "来源名称") String name,
      @Schema(description = "允许抓取的域名") String host,
      @Schema(description = "平台类别：PLATFORM / SUPPLIER / PEER") String kind,
      @Schema(description = "4.4.1.1 单条采集的最小抓取间隔（秒）") int minIntervalSeconds,
      @Schema(description = "🔴 批量采集的最小抓取间隔（秒）") int bulkMinIntervalSeconds,
      @Schema(description = "🔴 关键词搜索页 URL 模板（{keyword} / {page} 占位符；空串 = 该平台不支持关键词批量）")
          String searchUrlTemplate,
      @Schema(description = "🔴 分类页翻页参数名") String categoryPageParam,
      @Schema(description = "🔴 站点第一页的页码偏移（淘宝的 s=0，1688 的 beginPage=1）") int pageOrigin) {}

  /** 限频策略（前端显示「当前限频阈值」，运营据此知道为什么被拒）。 */
  public record BulkCollectPolicyRow(
      @Schema(description = "策略编码") String code,
      @Schema(description = "🔴 同一租户同时可进行的批量任务数") int maxConcurrentTasks,
      @Schema(description = "🔴 同一租户两次发起采集任务的最小间隔（秒）") int minTaskStartIntervalSeconds,
      @Schema(description = "单任务最多翻页数") int maxPagesPerTask,
      @Schema(description = "单任务最多落草稿条数") int maxItemsPerTask,
      @Schema(description = "单次插件上报最多带商品条数") int maxItemsPerReport,
      @Schema(description = "🔴 单次运行的时间预算（秒）") int maxTaskSeconds) {}

  public record ListingBulkCollectCapabilities(
      @Schema(description = "逐维度能力声明；未交付段 available=false")
          List<BulkCollectCapabilityDimension> dimensions,
      @Schema(description = "平台白名单 + 各平台批量采集参数") List<BulkCollectSourceRow> sources,
      @Schema(description = "🔴 当前限频策略") BulkCollectPolicyRow policy,
      @Schema(description = "入口模式取值域") List<String> modes,
      @Schema(description = "任务状态取值域") List<String> states,
      @Schema(description = "结果状态取值域") List<String> resultStates,
      @Schema(description = "🔴 是否保证只产生草稿、不产生商品") boolean draftOnly,
      @Schema(description = "🔴 草稿边界的界面措辞（前端原样展示）") String draftNote,
      @Schema(description = "🔴 是否做同 URL 去重") boolean deduplicated,
      @Schema(description = "🔴 去重规则的界面措辞") String dedupNote,
      @Schema(description = "🔴 是否做频率限制") boolean rateLimited,
      @Schema(description = "🔴 限频规则的界面措辞") String rateLimitNote,
      @Schema(description = "上游依赖说明") String upstreamNote) {}

  // ================================================================ 请求

  /**
   * 发起一次批量采集。
   *
   * <p>🔴 请求体里**没有**「顺便建商品」「顺便设价格」「顺便设库存」的字段 —— 采集只产生草稿。
   */
  public record StartBulkCollectRequest(
      @Schema(description = "🔴 入口模式：CATEGORY_PAGE / KEYWORD") @NotBlank @Size(max = 32) String mode,
      @Schema(description = "🔴 平台白名单编码（如 ALI_1688 / TAOBAO）") @NotBlank @Size(max = 32)
          String sourceCode,
      @Schema(description = "🔴 CATEGORY_PAGE 模式必填：分类页 URL（主机必须在平台白名单内）")
          @Size(max = 2000)
          String categoryUrl,
      @Schema(description = "🔴 KEYWORD 模式必填：关键词") @Size(max = 200) String keyword,
      @Schema(description = "起始页码，从 1 起") Integer pageFrom,
      @Schema(description = "结束页码（含），从 1 起") Integer pageTo,
      @Schema(description = "🔴 可选：覆盖平台默认的分页 URL 模板（含 {page} / {keyword} 占位符）")
          @Size(max = 2000)
          String pageUrlTemplate,
      @Schema(description = "备注") @Size(max = 500) String note) {}

  /**
   * 浏览器插件上报当前页商品。
   *
   * <p>🔴 插件上报的是**它自己在用户浏览器里抓到的数据** —— 本服务**不会**再去抓一遍这些商品页
   * （那会把外部站点的压力翻倍，正是蓝图要避免的）。服务端只做三件事：白名单校验、去重、落成采集草稿。
   */
  public record PluginReportRequest(
      @Schema(description = "🔴 插件当前所在页面的 URL（主机必须在平台白名单内）") @NotBlank @Size(max = 2000)
          String pageUrl,
      @Schema(description = "页面标题（原文照抄）") @Size(max = 500) String pageTitle,
      @Schema(description = "🔴 当前页上的商品") @NotEmpty @Valid List<PluginReportItem> items,
      @Schema(description = "备注") @Size(max = 500) String note) {}

  /** 插件上报的单条商品。 */
  public record PluginReportItem(
      @Schema(description = "🔴 商品链接（主机必须在平台白名单内）") @NotBlank @Size(max = 2000) String url,
      @Schema(description = "🔴 商品标题（插件读到的原文）") @Size(max = 1000) String title,
      @Schema(description = "🔴 主图 URL（只记 URL，不下载二进制）") @Size(max = 2000) String imageUrl,
      @Schema(description = "🔴 外部页面原文标价（**不是我方售价**）") BigDecimal priceAmount,
      @Schema(description = "🔴 标价币种") @Size(max = 16) String priceCurrency) {}

  // ================================================================ 结果

  /** 批量采集任务行。 */
  public record BulkCollectTaskRow(
      @Schema(description = "批量采集任务 id") UUID taskId,
      @Schema(description = "🔴 入口模式：CATEGORY_PAGE / KEYWORD / PLUGIN") String mode,
      @Schema(description = "命中的白名单编码") String sourceCode,
      @Schema(description = "命中的白名单名称") String sourceName,
      @Schema(description = "分类页 URL 原文") String categoryUrl,
      @Schema(description = "关键词原文") String keyword,
      @Schema(description = "起始页码") int pageFrom,
      @Schema(description = "结束页码") int pageTo,
      @Schema(description = "🔴 任务状态：PENDING / RUNNING / SUCCEEDED / PARTIAL / FAILED / RATE_LIMITED")
          String state,
      @Schema(description = "失败 / 中断原因（成功时为空串）") String errorMessage,
      @Schema(description = "计划页数") int totalPages,
      @Schema(description = "发现的条目数") int discoveredCount,
      @Schema(description = "🔴 成功落成采集草稿的条数") int fetchedCount,
      @Schema(description = "🔴 因已存在被跳过的条数") int skippedDuplicateCount,
      @Schema(description = "抓取失败的条数") int failedCount,
      @Schema(description = "🔴 因限频被跳过的条数") int skippedRateLimitedCount,
      @Schema(description = "🔴 断点：下次运行从第几页接着采") int nextPage,
      @Schema(description = "运行开始时刻") OffsetDateTime startedAt,
      @Schema(description = "运行结束时刻") OffsetDateTime finishedAt,
      @Schema(description = "创建时间") OffsetDateTime createdAt) {}

  /** 结果清单的一行。 */
  public record BulkCollectResultRow(
      @Schema(description = "结果行 id") UUID resultId,
      @Schema(description = "所属批量采集任务 id") UUID bulkTaskId,
      @Schema(description = "🔴 来源：BULK 批量链路 / PLUGIN 插件上报") String origin,
      @Schema(description = "来自第几页（插件上报恒为 0）") int pageNo,
      @Schema(description = "该页内第几条") int itemIndex,
      @Schema(description = "来源 URL 原文") String sourceUrl,
      @Schema(description = "🔴 规范化 URL（去重键）") String normalizedUrl,
      @Schema(description = "🔴 标题") String title,
      @Schema(description = "🔴 主图 URL（只记 URL，不下载二进制）") String imageUrl,
      @Schema(description = "🔴 外部页面原文标价（**不是我方售价**）") BigDecimal priceAmount,
      @Schema(description = "🔴 标价币种") String priceCurrency,
      @Schema(description = "🔴 状态：FETCHED / SKIPPED_DUPLICATE / FAILED / SKIPPED_RATE_LIMITED") String state,
      @Schema(description = "跳过原因（SKIPPED_* 时非空）") String skipReason,
      @Schema(description = "失败原因（FAILED 时非空）") String errorMessage,
      @Schema(description = "🔴 对应的 4.4.1.1 采集任务 id") UUID collectTaskId,
      @Schema(description = "🔴 对应的 4.4.1.1 采集草稿 id（下游 4.4.1.3 认领的就是它）") UUID draftId,
      @Schema(description = "🔴 抓取时刻") OffsetDateTime capturedAt,
      @Schema(description = "创建时间") OffsetDateTime createdAt) {}

  /** 批量采集任务列表（真分页 + 真筛选）。 */
  public record BulkCollectPage(
      @Schema(description = "当前页数据") List<BulkCollectTaskRow> items,
      @Schema(description = "符合筛选条件的总条数（不受分页影响）") long total,
      @Schema(description = "页码，从 0 起") int page,
      @Schema(description = "每页条数") int size) {}

  /** 结果清单（真分页 + 真筛选）。 */
  public record BulkCollectResultPage(
      @Schema(description = "当前页数据") List<BulkCollectResultRow> items,
      @Schema(description = "符合筛选条件的总条数（不受分页影响）") long total,
      @Schema(description = "页码，从 0 起") int page,
      @Schema(description = "每页条数") int size) {}

  /** 插件上报的结果（任务行 + 落成 / 跳过的条数）。 */
  public record PluginReportResult(
      @Schema(description = "批量采集任务（模式 = PLUGIN）") BulkCollectTaskRow task,
      @Schema(description = "🔴 落成采集草稿的条数") int fetchedCount,
      @Schema(description = "🔴 因已存在被跳过的条数") int skippedDuplicateCount,
      @Schema(description = "🔴 无效条目数（链接不在白名单 / 链接非法）") int rejectedCount) {}
}
