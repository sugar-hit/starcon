package com.starcon.catalog;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 6.1.1.2 供应商详情的 DTO。
 *
 * <p>刻意<b>独立成文件</b>而不是并进 {@code CatalogModels}（那个文件被并行开发的多个模块共享），
 * 与 {@code VendorListModels} 同一处理方式。
 *
 * <p><b>类型名一律带 {@code Vendor} 前缀</b>：{@code tools/codegen/catalog-contracts.py} 把六个服务的 schemas
 * 拍平进同一个命名空间，重名的类型后者直接覆盖前者。本文件的类型名与 {@code VendorListModels} 里已有的
 * （VendorRow / VendorPurchaseView / VendorFactSaved / …）<b>逐字不重</b>，就是这条约束的落点。
 *
 * <p><b>本模块不建供应商主数据</b>：带 partyCode / partyName / cooperationLevel 的字段全部
 * <b>只读</b>，读自 3.6.1.1 的 party 与 3.6.1.2 的 party_supplier_profile；
 * 写入所有权逐段对照见 {@code V142__vendor_detail_terms_qualifications.sql} 第二节。
 */
public final class VendorDetailModels {
  private VendorDetailModels() {}

  // ================================================================ 账期与开票信息（可写，D1）

  /**
   * 账期与开票信息（{@code GET/PUT /catalog/supply/vendors/{roleId}/settlement-terms}）。
   *
   * <p><b>这是付款审批的输入</b>，蓝图对它的加粗要求是「真实可维护的数据，不是只读展示」：
   * 缺失时 12 财务无法自动生成付款申请，所以它有自己的表与自己的写端点。
   *
   * <p>{@code paymentTermsDays}（本采购线约定）与 {@code masterPaymentTermsDays}（3.6.1.2 的主体默认）
   * <b>刻意同时返回且分开标注</b>：两者不同是正常的（一个主体可以同时有 USD 预付与 EUR 月结），
   * 用其中一个覆盖另一个会让付款审批拿错口径。
   */
  public record VendorTermsView(
      UUID roleId,
      /** 采购结算币种（ISO 4217）。本约定只对这个币种生效，跨币种不可比（D3）。 */
      String currency,
      /** 本采购线约定账期（**整数天**，D2）。 */
      int paymentTermsDays,
      /** 3.6.1.2 的主体默认账期（只读参考，null = 该主体没建角色档案）。 */
      @Schema(nullable = true) Integer masterPaymentTermsDays,
      /** 3.6.1.2 的默认结算方式（只读参考）。 */
      @Schema(nullable = true) String masterSettlementMethod,
      String settlementMethod,
      String invoiceType,
      String invoiceTitle,
      String taxId,
      /** 税率，**0~1 的比例**（0.13 = 13%）。 */
      BigDecimal taxRate,
      /** 采购信用额度；null = 未设额度（**与 0 不是一回事**，前者不拦截、后者已用尽）。 */
      @Schema(nullable = true) BigDecimal creditLimit,
      /** 预付比例（0~1）。只对 PREPAY 有意义。 */
      BigDecimal depositRatio,
      LocalDate effectiveFrom,
      String note,
      @PositiveOrZero long version,
      /** 账期与开票信息是否已建档。没建时本 record 整体为 null（见 panorama 的 termsFound）。 */
      boolean found) {}

  /**
   * 账期与开票信息的保存请求。
   *
   * <p><b>入参里没有 code / name / cooperationLevel / partyStatus</b>：那些字段的写入所有权在
   * 3.6.1.1 与 3.6.1.2。「顺手把采购线的账期写回主体档案」很方便，但那就是蓝图点名要避免的
   * 「同一实体两处可编辑」，改完之后 12 应付上按主体默认账期算出来的到期日
   * 会和界面上看到的采购线账期对不上，而且没有任何东西会报错。
   *
   * <p>{@code version} 是行级乐观锁（新建时传 0）。
   */
  public record VendorTermsInput(
      @NotBlank @Size(min = 3, max = 3) String currency,
      @NotNull @PositiveOrZero Integer paymentTermsDays,
      @NotBlank @Size(max = 24) String settlementMethod,
      @NotBlank @Size(max = 24) String invoiceType,
      @NotBlank @Size(max = 200) String invoiceTitle,
      @Size(max = 64) String taxId,
      @NotNull @DecimalMin("0") @DecimalMax("1") BigDecimal taxRate,
      @Schema(nullable = true) @DecimalMin("0") BigDecimal creditLimit,
      @NotNull @DecimalMin("0") @DecimalMax("1") BigDecimal depositRatio,
      @NotNull LocalDate effectiveFrom,
      @Schema(nullable = true) @Size(max = 2000) String note,
      @PositiveOrZero long version) {}

  // ================================================================ 附件资质（可写，D5）

  /**
   * 一份附件资质。
   *
   * <p>{@code validity} <b>不落库</b>，由 SQL 按 expires_on 与今天现算（D7）：存一列就要一个每日任务去维护，
   * 而它与读出口算出的那天差一天时，「这张证到底过期没有」会答错 —— 而这个答案直接决定能不能下单。
   */
  public record VendorQualificationRow(
      UUID id,
      UUID roleId,
      String qualificationType,
      String title,
      String certNo,
      String issuer,
      @Schema(nullable = true) LocalDate issuedOn,
      @Schema(nullable = true) LocalDate expiresOn,
      /** VALID / EXPIRING_SOON（30 天内到期）/ EXPIRED / UNKNOWN（未填到期日）。 */
      String validity,
      /** 距到期天数；UNKNOWN 时为 null。EXPIRED 时是负数。 */
      @Schema(nullable = true) Long daysToExpiry,
      String fileKey,
      String fileName,
      @Schema(nullable = true) String mimeType,
      @Schema(nullable = true) Long fileSizeBytes,
      String note,
      @PositiveOrZero long version) {}

  /** 附件资质的保存请求。{@code fileKey} 是对象存储的 key，不是 URL。 */
  public record VendorQualificationInput(
      @NotBlank @Size(max = 24) String qualificationType,
      @NotBlank @Size(max = 200) String title,
      @Size(max = 64) String certNo,
      @Size(max = 120) String issuer,
      @Schema(nullable = true) LocalDate issuedOn,
      @Schema(nullable = true) LocalDate expiresOn,
      @NotBlank @Size(max = 256) String fileKey,
      @Size(max = 160) String fileName,
      @Size(max = 80) String mimeType,
      @Schema(nullable = true) @PositiveOrZero Long fileSizeBytes,
      @Schema(nullable = true) @Size(max = 2000) String note,
      @PositiveOrZero long version) {}

  /** 附件资质的新建 / 修改结果。 */
  public record VendorQualificationSaved(UUID id, @PositiveOrZero long version) {}

  /** 附件资质清单。{@code expiringCount} / {@code expiredCount} 是全景页的治理警告来源。 */
  public record VendorQualificationList(
      List<VendorQualificationRow> items,
      long total,
      long expiredCount,
      long expiringCount,
      String note) {}

  // ================================================================ 联系人（只读，3.6.x 的写入所有权）

  /**
   * 一个联系人。
   *
   * <p><b>只读</b>：party_contact（V31）的写入所有权在往来单位模块（3.6.x）。
   * 本模块在详情页把它列出来，是因为「问交期找谁」是采购每天都要用的信息，
   * 但**不在这里提供编辑入口** —— 在这里能改，就会出现两个入口改同一行联系人。
   */
  public record VendorContactRow(
      UUID id,
      String name,
      @Schema(nullable = true) String position,
      @Schema(nullable = true) String email,
      @Schema(nullable = true) String phone,
      @Schema(nullable = true) String mobile,
      boolean primary,
      String note) {}

  // ================================================================ 供货 SKU 与报价（只读，3.6.2.1 的写入所有权）

  /**
   * 一条供货报价。
   *
   * <p><b>只读</b>：supplier_quote（V32）的写入所有权在 3.6.2.1 报价台账。
   * 「更新报价」这个操作入口指向 3.6.2.1 的页面，而不是在本模块里第二份编辑能力。
   *
   * <p>{@code firstTierUnitPrice} 是**起订量最小的那一档**的单价（起订门槛价），
   * 档位明细在 3.6.2.1 的报价详情里 —— 全景页只给「从多少钱起订」这一个数，
   * 因为把全部阶梯摊在列表里会让一行报价变成一屏数字而读不出结论。
   */
  public record VendorQuoteRow(
      UUID id,
      UUID skuId,
      String skuCode,
      String skuName,
      String supplierSkuCode,
      String purchaseUnitCode,
      String currency,
      BigDecimal moqQuantity,
      int leadTimeDays,
      @Schema(nullable = true) BigDecimal firstTierUnitPrice,
      int tierCount,
      LocalDate validFrom,
      @Schema(nullable = true) LocalDate validTo,
      int priority,
      String status,
      /** 是否在给定日期（缺省今天）有效：status='ACTIVE' 且落在 valid_from..valid_to 闭区间内。 */
      boolean effective,
      String note,
      @PositiveOrZero long version) {}

  /** 报价清单的分页结果。{@code effectiveOn} 是「有效」判定所用的那一天，会回显。 */
  public record VendorQuotePage(
      List<VendorQuoteRow> items,
      long total,
      int page,
      int size,
      /** 报价覆盖的不同 SKU 数（**只数有效报价**，与 6.1.1.1 的 M1 同口径）。 */
      long effectiveSkuCount,
      LocalDate effectiveOn,
      String note) {}

  // ================================================================ 采购历史与金额（只读事实 + 聚合）

  /** 一条到货历史。字段与 6.1.1.1 的 VendorDeliveryRow 同源，**不重复计算**准时与否。 */
  public record VendorPurchaseHistoryRow(
      UUID id,
      String deliveryRef,
      @Schema(nullable = true) UUID skuId,
      @Schema(nullable = true) String skuCode,
      @Schema(nullable = true) String skuName,
      @Schema(nullable = true) String purchaseUnitCode,
      BigDecimal quantity,
      BigDecimal amount,
      String currency,
      @Schema(nullable = true) String orderedAt,
      LocalDate dueDate,
      LocalDate receivedDate,
      boolean onTime,
      int daysLate,
      boolean isVoid,
      @Schema(nullable = true) String voidReason,
      @Schema(nullable = true) String note) {}

  /** 一个月、一种币种上的到货金额。 */
  public record VendorMonthlyAmount(String month, String currency, BigDecimal amount, long deliveryCount) {}

  /**
   * 采购历史分页 + 聚合。
   *
   * <p><b>金额按币种分组，绝不相加</b>（D3）：本库没有汇率表，100 USD + 90 EUR = 190 是一个
   * 看起来对、实际错、且极难被发现的数字。
   */
  public record VendorPurchaseHistoryPage(
      List<VendorPurchaseHistoryRow> items,
      long total,
      int page,
      int size,
      /** 窗口内（不含作废）到货笔数。 */
      long deliveryCount,
      /** 窗口内到货数量合计（跨单位不可加，所以按采购单位分组返回，见 quantities）。 */
      BigDecimal totalQuantity,
      /** 按「采购单位 + 币种」分组的数量与金额，避免把「3 箱 + 200 件」加成一个数。 */
      List<VendorHistoryBucket> buckets,
      /** 按月的到货金额序列（近 12 个月），供详情页画趋势。 */
      List<VendorMonthlyAmount> monthly,
      VendorListModels.VendorMetricWindow metricWindow,
      String note) {}

  /** 按采购单位与币种分组的到货汇总。 */
  public record VendorHistoryBucket(String purchaseUnitCode, String currency, BigDecimal quantity, BigDecimal amount, long deliveryCount) {}

  // ================================================================ 全景

  /** 交期表现（窗口内）。全部是 SQL 聚合，**没有一个是存下来的字段**。 */
  public record VendorDeliveryPerformance(
      /** 到货笔数（窗口内，不含作废）。 */
      long deliveryCount,
      /** 准时笔数：received_date <= due_date。 */
      long onTimeCount,
      /** 准时交付率；**分母为 0 时是 null 而不是 0**（D4）。 */
      @Schema(nullable = true) BigDecimal onTimeRate,
      /** 平均延期天数：received_date - due_date 的平均值，**提前到货是负数**。
       *  口径是「有晚有早互相抵消」—— 一个总是早到 5 天的供应商会算出 -5 而不是 0，
       *  而 0 会被读成「准时率还行但毫无提前能力」，那是两种不同的供应商。 */
      @Schema(nullable = true) BigDecimal averageDaysLate,
      /** 最大延期天数（只统计晚到的，早到的记 0）。 */
      @Schema(nullable = true) Integer maxDaysLate,
      /** 早于承诺到货的笔数。 */
      long earlyCount,
      @Schema(nullable = true) String lastDeliveryAt) {}

  /** 质量表现（窗口内）。按**数量**比而不是批次数比（与 6.1.1.1 的 M4 同口径）。 */
  public record VendorQualityPerformance(
      long inspectionCount,
      BigDecimal inspectedQuantity,
      BigDecimal qualifiedQuantity,
      /** 合格率；**分母为 0 时是 null**（D4）。 */
      @Schema(nullable = true) BigDecimal qualifiedRate,
      /** 不合格数量 = 检验数 - 合格数。采购真正承担的风险就是这个数。 */
      BigDecimal unqualifiedQuantity,
      @Schema(nullable = true) String lastInspectionAt) {}

  /**
   * 一个操作入口。
   *
   * <p>{@code available=false} 时 {@code reason} 必须给出**为什么现在不能做** ——
   * 灰掉一个按钮而不说理由，运营只会以为系统坏了。
   */
  public record VendorOperationEntry(String key, String label, boolean available, @Schema(nullable = true) String href, @Schema(nullable = true) String reason) {}

  /**
   * 供应商全景（{@code GET /catalog/supply/vendors/{roleId}/panorama}）。
   *
   * <p>七段职责逐段对应：基础信息（identity）、账期与开票（terms）、
   * 供货 SKU 与报价（quoteSummary）、采购历史与金额（historySummary）、
   * 交期与质量表现（delivery / quality）、联系人（contacts）、附件资质（qualificationSummary），
   * 加上输出要求里的操作入口（operations）。
   */
  public record VendorPanorama(
      UUID roleId,
      UUID partyId,
      // ---- 基础信息：只读 3.6.1.1 party
      String partyCode,
      String partyName,
      String partyType,
      @Schema(nullable = true) String countryCode,
      @Schema(nullable = true) String email,
      @Schema(nullable = true) String phone,
      @Schema(nullable = true) String website,
      String partyStatus,
      @Schema(nullable = true) String statusReason,
      // ---- 只读 3.6.1.2 party_supplier_profile
      @Schema(nullable = true) String cooperationLevel,
      @Schema(nullable = true) String masterSettlementMethod,
      @Schema(nullable = true) String moqUnitCode,
      @Schema(nullable = true) BigDecimal moqQuantity,
      boolean supplierProfileFound,
      // ---- 只读 6.1.1.1 vendor_supply_profile（采购侧合作状态，本模块不改）
      @Schema(nullable = true) String supplyStatus,
      @Schema(nullable = true) UUID categoryId,
      @Schema(nullable = true) String categoryName,
      @Schema(nullable = true) String supplyNote,
      boolean supplyProfileFound,
      // ---- 账期与开票（本模块可写）
      @Schema(nullable = true) VendorTermsView terms,
      boolean termsFound,
      // ---- 供货 SKU 与报价（只读 3.6.2.1 的摘要，明细在 /quotes）
      long quoteCount,
      long effectiveQuoteCount,
      long quotedSkuCount,
      @Schema(nullable = true) String quoteCurrency,
      // ---- 采购历史与金额（只读事实的聚合，明细在 /purchase-history）
      long historyDeliveryCount,
      List<VendorListModels.VendorPurchaseAmount> purchaseAmounts,
      @Schema(nullable = true) String lastDeliveryAt,
      // ---- 交期与质量表现
      VendorDeliveryPerformance delivery,
      VendorQualityPerformance quality,
      // ---- 联系人（只读）
      List<VendorContactRow> contacts,
      // ---- 附件资质（摘要，明细在 /qualifications）
      long qualificationCount,
      long qualificationExpiredCount,
      long qualificationExpiringCount,
      @Schema(nullable = true) LocalDate nearestExpiry,
      // ---- 操作入口
      List<VendorOperationEntry> operations,
      // ---- 治理警告与窗口
      List<String> warnings,
      VendorListModels.VendorMetricWindow metricWindow,
      String note) {}
}
