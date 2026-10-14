package com.starcon.trade;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * 9.6.1.2 **供应商下单端口**（蓝图：「自动/手工向 1688 等供应商代发平台下单」）。
 *
 * <p>AGENTS.md 模块边界纪律：「外部适配器（Shopify / Amazon / ...）放在对应服务里，
 * 每个 vendor 独立包，<b>只暴露统一接口</b>」。本接口就是那个统一接口，
 * 服务层只认它，不认任何 vendor 的报文形状。
 *
 * <p><b>本仓纪律里最要紧的一条：绝不伪造平台响应。</b>
 * 一个 {@code ALIBABA_1688} 适配器如果在没有凭证、没有 base_url 的情况下
 * 返回一个编出来的 {@code supplierOrderNo}，界面上这张单会显示「供应商已接单」，
 * 而供应商那边根本没有这张单 —— 这是本模块最危险的一种假数据，因为它恰好
 * 落在蓝图最在意的那条链上（成本回填 → 差价 → 财务结算）。
 * 因此接口的契约是：<b>接不上的 vendor 必须显式失败</b>（{@link SupplierOrderGateway#unwired}），
 * 而不是返回一个看起来成功的对象。
 *
 * <p>两个实现（各自独立，不互相引用对方的 vendor 概念）：
 * <ul>
 *   <li>{@link ManualEntrySupplierAdapter} —— <b>已接线</b>：运营在页面上把供应商
 *       单号与实际成本录进来（人工录入是蓝图输入句里明写的路径之一）。</li>
 *   <li>{@link Alibaba1688SupplierAdapter} —— <b>默认未接线</b>：真实 HTTP 客户端，
 *       但必须先在 {@code fulfillment_dropship_supplier_endpoint} 登记
 *       {@code base_url} 与 {@code credential_ref} 才能调用。未登记时返回
 *       {@link SupplierOrderGateway#unwired}，并说明「需要登记哪个字段」。</li>
 * </ul>
 */
public interface SupplierOrderGateway {

  /** vendor 标识，与 DB 的 {@code vendor_code} CHECK 同词表。 */
  String vendorCode();

  /** 展示名（能力声明用，如「1688 开放平台」/「人工录入」）。 */
  String displayName();

  /**
   * 本 vendor 当前**是否已接线**。未接线不是异常，是如实报出。
   *
   * <p>能力声明端点用它决定这一维度的 {@code available}：报 true 却实际调不通，
   * 比直接报 false 更糟 —— 前者会让人一直重试一个永远不存在的集成。
   */
  boolean wired();

  /** 未接线时给人看的「还差什么」（接上了返回空串）。 */
  String wiringRequirement();

  /** 下单结果：供应商单号与**实际**成本（采购价 + 运费）。 */
  record SupplierOrderAck(
      String supplierOrderNo,
      BigDecimal actualPurchaseAmount,
      BigDecimal actualSupplierShippingAmount,
      String trackingNo,
      String note) {}

  /** 下单请求：由服务层从代发单与销售订单组装，vendor 不再回查我方库。 */
  record PlacementRequest(
      UUID orderId,
      String orderNo,
      String supplierCode,
      String currencyCode,
      /** 收货地址快照（蓝图「传递地址与商品信息」的地址部分）。 */
      Recipient recipient,
      List<PlacedLine> lines) {}

  /** 收货人快照：姓名 / 电话 / 国家 / 省市区 / 详址（逐字取自 9.1 的地址快照）。 */
  record Recipient(
      String name,
      String phone,
      String countryCode,
      String province,
      String city,
      String district,
      String addressLine1,
      String addressLine2,
      String postcode) {}

  /** 一行商品：SKU 快照 + 数量 + 我方供应商货号（蓝图「传递商品信息」）。 */
  record PlacedLine(
      String skuRef, String skuName, String specSnapshot, BigDecimal quantity,
      String supplierSkuRef) {}

  /** 供应商明确回「无货」时抛出——这是蓝图点名的三类差异之一，必须显式告警。 */
  class SupplierOutOfStockException extends RuntimeException {
    private final String supplierMessage;

    public SupplierOutOfStockException(String supplierMessage) {
      super(supplierMessage);
      this.supplierMessage = supplierMessage;
    }

    public String supplierMessage() {
      return supplierMessage;
    }
  }

  /** 供应商拒单 / 平台返回失败时抛出（蓝图点名的第三类差异：失败）。 */
  class SupplierRejectedException extends RuntimeException {
    private final String supplierMessage;

    public SupplierRejectedException(String supplierMessage) {
      super(supplierMessage);
      this.supplierMessage = supplierMessage;
    }

    public String supplierMessage() {
      return supplierMessage;
    }
  }

  /** 未接线 / 平台不可达：调用方据此落 FAILED 状态并**如实**记下原因。 */
  SupplierOrderException unavailable(String reason);

  /**
   * 向供应商提交一笔代发单。
   *
   * @throws SupplierOutOfStockException 供应商明确无货（→ NO_STOCK 告警）
   * @throws SupplierRejectedException 供应商拒单或平台失败（→ ORDER_FAILED 告警）
   * @throws SupplierOrderException 未接线 / 平台不可达（→ ORDER_FAILED 告警）
   */
  SupplierOrderAck place(PlacementRequest request);

  /** 未接线时构造的那个异常，附带「还差什么」的人话说明。 */
  default SupplierOrderException unwired() {
    return unavailable(
        vendorCode() + " 供应商尚未接线：" + wiringRequirement()
            + "。请先在「供应商代发接口」登记 base_url 与凭证引用（credential_ref 指向 "
            + "secret manager 里的键，本系统不保存任何明文凭证），或改用「人工录入」路径"
            + "——蓝图输入句明写「供应商代发接口**或人工录入**」，人工录入这条路径是通的。");
  }
}