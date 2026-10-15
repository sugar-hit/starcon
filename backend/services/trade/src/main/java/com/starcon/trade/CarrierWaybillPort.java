package com.starcon.trade;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * 10.2.1.2 运单申请的<b>承运商适配器端口</b>。
 *
 * <p>AGENTS.md 外部适配器纪律：「外部适配器（Shopify / Amazon / ...）放在
 * {@code backend/services/channel/}，每个 vendor 独立包，只暴露统一接口。」
 * 本接口就是那条「统一接口」在 trade 侧的形状 —— trade 拥有运单文档，
 * 于是运单申请这一跳的**端口**落在 trade，具体 vendor 包在 channel 侧接通后
 * 由本端口的实现类提供。
 *
 * <p><b>⚠️ 本端口在本车道没有已接通的实现，而且这是如实反映而不是缺口</b>：
 * 10.1 物流渠道全部未交付（10.1.1.1 承运商列表 / 10.1.1.2 渠道配置 / 10.1.1.3
 * 时效与限制 / 10.1.2.1 运费计算器 / 10.1.2.2 渠道比价，台账全「未开发」），
 * 也就是说**没有承运商连接配置**。蓝图卷 10 明写「channel 拥有物流商连接与轨迹」。
 *
 * <p>所以本模块刻意做到的三件事：
 *
 * <ol>
 *   <li><b>不凭空造一个「已接通的承运商」</b>，也<b>不写死一个假 API 返回运单号</b>。
 *       {@link #resolve} 在没有任何 vendor 实现时如实返回
 *       {@link Resolution#unavailable}，调用方据此把这一票置 FAILED 并写明
 *       「渠道未接通」，而不是发一个编出来的运单号出去。</li>
 *   <li>把<b>渠道登记</b>做成可扩展的本地事实（V128 的 {@code logistics_carrier}），
 *       接通 10.1 之后由 10.1 的连接配置喂进来即可，服务层不用改。</li>
 *   <li>承运商连接配置（endpoint / 凭据）<b>不落在本模块的表里</b> ——
 *       归 secret manager 与 10.1（AGENTS.md「安全」一节）。</li>
 * </ol>
 *
 * <p>测试里出现的实现是<b>承运商侧 HTTP 替身</b>（{@code com.sun.net.httpserver}），
 * 与 9.4.1.1 用 warehouse HTTP 替身同一手法：替的是**外部系统**，不是本模块的数据。
 * 待发货单元、包裹、承运商登记、运单申请、队列、尝试、事件<b>全部真落 PostgreSQL 16</b>。
 */
public interface CarrierWaybillPort {

  /** 一次「申请一个运单号」的入参。字段与 V128 的申请头对齐，重量尺寸来自实测读数。 */
  record WaybillSubmission(
      UUID requestId,
      String requestNo,
      String idempotencyKey,
      String carrierCode,
      String carrierChannel,
      String shipFromName,
      String shipFromCountry,
      String shipToName,
      String shipToCountry,
      String shipToAddressLine,
      String shipToCity,
      String shipToPostalCode,
      BigDecimal grossWeightKg,
      BigDecimal lengthMm,
      BigDecimal widthMm,
      BigDecimal heightMm,
      String declaredCurrency,
      BigDecimal declaredValueTotal,
      String labelTemplateRef,
      List<DeclarationLine> declarations) {}

  /** 申报明细行（HS 编码是承运商与海关唯一认的字段）。 */
  record DeclarationLine(
      UUID skuId,
      String skuRef,
      String productName,
      BigDecimal quantity,
      String unitCode,
      BigDecimal unitPrice,
      BigDecimal declaredValue,
      String hsCode,
      String originCountry,
      UUID destinationVersionId) {}

  /**
   * 承运商返回的结果。
   *
   * <p>⚠️ {@code waybillNo} 只在 {@code outcome == SUCCEEDED} 时非空，
   * 而 V128 的 CHECK {@code (status='ISSUED') = (btrim(waybill_no)<>'')}
   * 会在服务层把它落库前就拒绝「成功但没有单号」的组合。
   */
  record WaybillOutcome(
      WaybillModels.WaybillAttemptOutcome outcome,
      String waybillNo,
      String labelFormat,
      String labelUri,
      String carrierRequestRef,
      String failureReason) {

    public static WaybillOutcome issued(
        String waybillNo, String labelFormat, String labelUri, String carrierRequestRef) {
      return new WaybillOutcome(
          WaybillModels.WaybillAttemptOutcome.SUCCEEDED, waybillNo, labelFormat, labelUri, carrierRequestRef, "");
    }

    public static WaybillOutcome rejected(String carrierRequestRef, String reason) {
      return new WaybillOutcome(
          WaybillModels.WaybillAttemptOutcome.CARRIER_REJECTED, "", "", "", carrierRequestRef, reason);
    }

    public static WaybillOutcome rateLimited(String reason) {
      return new WaybillOutcome(WaybillModels.WaybillAttemptOutcome.RATE_LIMITED, "", "", "", "", reason);
    }

    public static WaybillOutcome unavailable(String reason) {
      return new WaybillOutcome(WaybillModels.WaybillAttemptOutcome.CARRIER_UNAVAILABLE, "", "", "", "", reason);
    }

    public static WaybillOutcome adapterMissing(String reason) {
      return new WaybillOutcome(WaybillModels.WaybillAttemptOutcome.ADAPTER_MISSING, "", "", "", "", reason);
    }
  }

  /**
   * 适配器解析结果。
   *
   * <p>刻意做成 sealed：本模块对「有没有 vendor 实现」只有两种答案，
   * 不给第三种「先当作有、真到调用时才炸」。
   */
  sealed interface Resolution {
    record Connected(CarrierWaybillPort port) implements Resolution {}

    /** 没有 vendor 实现：如实说没有，并带上 ownerModule 与影响。 */
    record Unavailable(String adapterKey, String ownerModule, String reason) implements Resolution {}
  }

  /** 这个适配器键支持的服务商代码（用于 capabilities 里逐条列「谁接通了」）。 */
  String adapterKey();

  /**
   * 解析某个承运商的适配器。
   *
   * <p>实现方（channel 侧 vendor 包）按 {@code adapterKey} 提供；
   * 本模块在没有实现时返回 {@link Resolution.Unavailable}。
   */
  Resolution resolve(String carrierCode, String adapterKey);

  /** 向承运商申请一个运单号。失败必须<b>如实抛</b>，不要吞掉返回空结果。 */
  WaybillOutcome submit(WaybillSubmission submission);
}
