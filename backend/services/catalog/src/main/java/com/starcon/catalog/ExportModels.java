package com.starcon.catalog;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * 3.8.2.1 导出的对外契约记录。
 *
 * <p>端点与前端只认这些 record；数据源、字段白名单、排序白名单都在代码侧
 * （{@link ExportDataSource}），**刻意不用一个自由 map 承载筛选条件**：
 * 自由 map 生成不出可用的 OpenAPI（Map&lt;String,Object&gt; 落到 TS 上是一个空对象类型），
 * 而且那样就丢掉了「这个筛选条件对当前数据源是否成立」的校验——
 * {@link ExportFilterInput} 的字段名与各数据源 {@code filters()} 声明的键一一对应，
 * 服务层据此对「不属于本数据源的筛选条件」报 422，而不是静默忽略它。
 */
public final class ExportModels {
  private ExportModels() {}

  // ================================================================ 能力声明

  /**
   * 一个可导出字段。
   *
   * <p>{@code pii=true} 的字段就是「联系方式等敏感字段」：勾选它们的导出必须显式授权
   * 并写明理由（蓝图对 16.6.1 的要求在本模块内自行落地）。PII 标记来自字段定义本身，
   * 不接受客户端自称——否则「导出含 PII」这件事就成了客户端可以填的一个布尔值。
   */
  public record ExportFieldInfo(
      String key, String label, boolean pii, @Schema(nullable = true) String note) {}

  /** 一个可排序键。与字段白名单同理，排序键也是服务端声明的闭集。 */
  public record ExportSortInfo(String key, String label) {}

  /** 一个可用筛选条件。key 与 {@link ExportFilterInput} 的字段名一致。 */
  public record ExportFilterInfo(
      String key, String label, String kind, boolean available, String note) {}

  /**
   * 一个数据源的能力声明。
   *
   * <p>{@code available=false} 表示该数据源的上游列表模块在本阶段尚未交付
   * （如 3.6.2.1 报价列表由另一条车道开发、尚未并入本分支）。此时它仍然出现在列表里，
   * 并说明缺的是什么——前端据此禁用入口并显示原因，而不是让用户点下去撞一个 404。
   */
  public record ExportSourceInfo(
      String key,
      String label,
      String upstreamModule,
      String permission,
      boolean available,
      String note,
      List<ExportFieldInfo> fields,
      List<ExportSortInfo> sorts,
      List<ExportFilterInfo> filters,
      /** 默认排序键。与列表页一致；由后端声明，前端不自己挑第一个。 */
      String defaultSortKey,
      int maxRows,
      int downloadTtlHours) {}

  public record ExportSourcePage(List<ExportSourceInfo> items) {}

  // ================================================================ 输入

  /**
   * 列表筛选条件。
   *
   * <p>各字段服务于不同数据源（spuId/category/status/barcode/spec 属 SKU 列表，
   * brand 属 SPU 列表，partyId/partyType/primary/roleType 属联系人列表）。
   * 不属于当前数据源的字段会被显式拒绝并点名，见 {@code ExportRules.requireSupportedFilters}。
   *
   * <p>{@code onHandMin/onHandMax/priceMin/priceMax/outOfStock/onSale/platform}
   * 是 SKU 列表页上**依赖尚未接入的上游投影**的那几个筛选条件（库存归 07、售价归 05、
   * 在售平台归 04）。它们在数据源的筛选词表里被声明成
   * {@code available=false} 并写明归属，填了会被 422 拒绝 —— 与 3.1.2.1 列表本身的
   * 处置同一条纪律：不静默忽略一个用户填了的条件（那会让他以为筛过了，其实没筛）。
   */
  public record ExportFilterInput(
      @Schema(nullable = true) UUID spuId,
      @Schema(nullable = true) String category,
      @Schema(nullable = true) List<String> status,
      @Schema(nullable = true) String q,
      @Schema(nullable = true) String barcode,
      @Schema(nullable = true) String spec,
      @Schema(nullable = true) String brand,
      @Schema(nullable = true) UUID partyId,
      @Schema(nullable = true) String partyType,
      @Schema(nullable = true) Boolean primary,
      @Schema(nullable = true) String roleType,
      @Schema(nullable = true) BigDecimal onHandMin,
      @Schema(nullable = true) BigDecimal onHandMax,
      @Schema(nullable = true) BigDecimal priceMin,
      @Schema(nullable = true) BigDecimal priceMax,
      @Schema(nullable = true) Boolean outOfStock,
      @Schema(nullable = true) Boolean onSale,
      @Schema(nullable = true) String platform) {}

  /** 排序还原：把列表页当时的排序原样带过来，导出行的顺序才与用户看到的一致。 */
  public record ExportSortInput(
      @Schema(nullable = true) String key, @Schema(nullable = true) String order) {}

  /**
   * 发起一次导出。
   *
   * <p>{@code piiPurpose}（授权理由）在字段里含 PII 时必填，服务层与 DB CHECK 各拒一道。
   */
  public record ExportJobInput(
      @NotBlank String sourceKey,
      @NotEmpty List<String> fields,
      @Schema(nullable = true) @Valid ExportFilterInput filters,
      @Schema(nullable = true) @Valid ExportSortInput sort,
      @Schema(nullable = true) String piiPurpose) {}

  // ================================================================ 输出

  /**
   * 一次生效的筛选条件（审计用）。
   *
   * <p>导出记录要能回答「这批数据是按什么条件导出来的」，所以条件以
   * {@code 键 + 标签 + 值} 的形式**回显**，而不是让人去比对 JSON。
   */
  public record ExportAppliedFilter(String key, String label, String value) {}

  /**
   * 导出任务行 = 蓝图里那条「导出记录」。
   *
   * <p>{@code downloadable} 与 {@code downloadNote} 把「现在能不能下载、为什么不能」
   * 做成服务端可查的结论：未跑完、已过期、已作废、已失败各有各的话，
   * 前端不需要自己根据 status 猜一套规则（猜错就是给用户一个点了没反应的下载按钮）。
   */
  public record ExportJobRow(
      UUID id,
      String sourceKey,
      String sourceLabel,
      String status,
      String statusNote,
      List<String> fields,
      List<String> headers,
      List<ExportAppliedFilter> appliedFilters,
      String sortKey,
      String sortOrder,
      int rowCount,
      // 下面若干列在「还没跑出文件 / 还没起止时间」时为 null，契约必须如实标成可空，
      // 否则前端 TS 会把 null 当成 string 用，运行时炸在一个看似无关的地方。
      @Schema(nullable = true) String fileName,
      @Schema(nullable = true) BigDecimal byteSize,
      @Schema(nullable = true) String contentSha256,
      boolean includesPii,
      List<String> piiFields,
      String piiPurpose,
      UUID actorId,
      @Schema(nullable = true) UUID departmentId,
      String requestedAt,
      @Schema(nullable = true) String startedAt,
      @Schema(nullable = true) String finishedAt,
      @Schema(nullable = true) String expiresAt,
      boolean downloadable,
      String downloadNote,
      String error) {}

  public record ExportJobPage(List<ExportJobRow> items, long total, int page, int size) {}

  /** 导出文件本体：文件名 + UTF-8 字节。 */
  public record ExportFile(String fileName, long byteSize, byte[] content) {}
}