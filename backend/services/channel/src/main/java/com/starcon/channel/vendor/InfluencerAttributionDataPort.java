package com.starcon.channel.vendor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 14.5.2.1 带货归因的<b>平台带货数据端口</b>：把蓝图输入句里的
 * 「<b>达人带货数据同步</b>」那一半表达成一个可插拔的接缝。
 *
 * <p>🔴🔴 <b>当前没有任何平台实现这个端口</b> —— channel 的适配层
 * （{@code com.starcon.channel.vendor}）目前<b>只有 OAuth 适配器</b>
 * （Amazon / Shopify / eBay / Walmart / Temu / Lazada / Shopee / TikTok Shop 共 13 个），
 * <b>没有达人带货数据适配器</b>。所以本端口的唯一实现
 * （{@link UnavailableInfluencerAttributionDataPort}）对所有平台都返回「未接入」。
 *
 * <p>为什么不能顺手编一个：蓝图写的是「输入：<b>达人带货数据同步</b>；订单归因；佣金数据」，
 * 而本仓库当前<b>没有订单事实</b>（订单在 trade 服务，跨服务不可读），
 * 所以归因数据**只能来自本地已有事实**：14.5.1.1 的 {@code influencer_cooperation}
 * 合作记录与达人档案。平台那一半没交付时，唯一诚实的做法是
 * <b>把缺口显式说出来</b>，而不是：
 *
 * <ul>
 *   <li>造一个假的平台端点（那会让界面以为「平台带货数据已经接上了」）；</li>
 *   <li>返回硬编码的归因记录（那是假事实 —— 那些订单不存在）；</li>
 *   <li>静默吞掉同步请求（那会让运营以为同步过了）。</li>
 * </ul>
 *
 * <p>🔴 端口<b>不传租户号</b>：调用方转发的是<b>自己的</b> {@code Authorization} bearer，
 * 平台侧仍按已认证成员关系解析租户并强制 RLS。租户隔离因此是<b>两道</b>
 * （channel 侧 RLS + 平台侧），不是本服务替对方决定租户。
 *
 * <p>🔴 端口<b>只负责取数，不负责落库</b>：落库是 {@code InfluencerAttributionService}
 * 的事。端口返回原始归因数据，由服务层决定怎么落、落成什么口径 ——
 * 否则「谁拥有写入」就说不清了。
 */
public interface InfluencerAttributionDataPort {

  /**
   * 某个平台的带货数据同步是否已接入。
   *
   * @param available 是否已接入（false ⇒ 同步不可用，**不是「没有数据」**）
   * @param status 状态码：{@code NOT_INTEGRATED} 未接入 / {@code AVAILABLE} 已接入
   * @param reason 人能读的原因（未接入时必填，界面上要显示出来）
   */
  record Availability(boolean available, String status, String reason) {}

  /**
   * 平台侧的一条原始带货归因数据。
   *
   * <p>🔴 全部是<b>原始值</b>：{@code orderGmv} / {@code orderCount} /
   * {@code refundRate} / {@code commissionAmount} 允许为 {@code null}，
   * 意思是<b>平台侧没给这个数</b>（不可比），**不是 0**。
   *
   * @param externalOrderId 平台侧订单标识
   * @param externalInfluencerId 平台侧达人标识
   * @param externalCampaignId 平台侧活动标识
   * @param orderGmv 订单金额
   * @param orderCount 订单量
   * @param refundRate 退款率（0 ~ 1）
   * @param commissionAmount 佣金金额
   * @param currency 币种
   * @param occurredAt 订单发生时刻
   */
  record RawAttribution(
      String externalOrderId,
      String externalInfluencerId,
      String externalCampaignId,
      BigDecimal orderGmv,
      BigDecimal orderCount,
      BigDecimal refundRate,
      BigDecimal commissionAmount,
      String currency,
      OffsetDateTime occurredAt) {}

  /**
   * 一次拉取的结论。
   *
   * @param available 是否真的取到了（false ⇒ {@code attributions} 必为空，
   *     **不得**用空集合冒充「这个店铺没有带货数据」）
   * @param reason 取不到的原因（人能读）
   * @param attributions 取到的原始数据（available=true 时才非空）
   */
  record FetchResult(boolean available, String reason, List<RawAttribution> attributions) {}

  /** 该平台的带货数据同步是否已接入。 */
  Availability availability(String platformCode);

  /** 拉取某个店铺在某个时间区间内的带货数据。取不到时返回 {@code available=false} + 原因，不抛异常。 */
  FetchResult fetch(
      String platformCode, String shopCode, OffsetDateTime from, OffsetDateTime to, String bearer);
}
