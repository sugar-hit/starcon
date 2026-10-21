package com.starcon.channel.vendor;

import java.math.BigDecimal;
import java.util.List;

/**
 * 14.5.1.1 达人库的<b>平台达人数据端口</b>：把「达人数据同步（平台接口或手动录入）」里的
 * <b>「平台接口」那一半</b>表达成一个可插拔的接缝。
 *
 * <p>🔴🔴 <b>当前没有任何平台实现这个端口</b> —— channel 的适配层（{@code com.starcon.channel.vendor}）
 * 目前<b>只有 OAuth 适配器</b>（Amazon / Shopify / eBay / Walmart / Temu / Lazada / Shopee /
 * TikTok Shop），<b>没有达人数据适配器</b>。所以本端口的唯一实现
 * （{@link UnavailableInfluencerDataPort}）对所有平台都返回「未接入」。
 *
 * <p>为什么不能顺手编一个：蓝图写的是「达人数据同步（<b>平台接口或</b>手动录入）」——
 * 「或」字给了手动录入这条**已交付**的路。平台接口那一半没交付时，
 * 唯一诚实的做法是<b>把缺口显式说出来</b>（{@link Availability#available()} = false +
 * 一句人能读的 {@link Availability#reason()}），而不是：
 *
 * <ul>
 *   <li>造一个假的平台端点（那会让界面以为「平台达人数据已经接上了」）；</li>
 *   <li>返回硬编码的达人列表（那是假事实 —— 那些达人不存在）；</li>
 *   <li>静默吞掉同步请求（那会让运营以为同步过了）。</li>
 * </ul>
 *
 * <p>🔴 端口<b>不传租户号</b>：调用方转发的是<b>自己的</b> {@code Authorization} bearer，
 * 平台侧仍按已认证成员关系解析租户并强制 RLS。租户隔离因此是<b>两道</b>
 * （channel 侧 RLS + 平台侧），不是本服务替对方决定租户。
 *
 * <p>🔴 端口<b>只负责取数，不负责落库</b>：落库是 {@code InfluencerService} 的事
 * （写进 {@code influencer} 表并触发评级重算）。端口返回原始档案，
 * 由服务层决定怎么落、落成什么口径 —— 否则「谁拥有写入」就说不清了。
 */
public interface InfluencerDataPort {

  /**
   * 某个平台的达人数据同步是否已接入。
   *
   * @param available 是否已接入（false ⇒ 同步不可用，**不是「没有数据」**）
   * @param status 状态码：{@code NOT_INTEGRATED} 未接入 / {@code AVAILABLE} 已接入
   * @param reason 人能读的原因（未接入时必填，界面上要显示出来）
   */
  record Availability(boolean available, String status, String reason) {}

  /**
   * 平台侧的一条原始达人档案。
   *
   * <p>🔴 全部是<b>原始值</b>：{@code engagementRate} / {@code gmv} / {@code refundRate}
   * 允许为 {@code null}，意思是<b>平台侧没给这个数</b>（不可比），**不是 0**。
   */
  record RawProfile(
      String externalInfluencerId,
      String displayName,
      String handle,
      String category,
      long followerCount,
      BigDecimal engagementRate,
      BigDecimal gmv,
      String gmvCurrency,
      BigDecimal refundRate) {}

  /**
   * 一次拉取的结论。
   *
   * @param available 是否真的取到了（false ⇒ {@code profiles} 必为空，
   *     **不得**用空集合冒充「这个店铺没有达人」）
   * @param reason 取不到的原因（人能读）
   * @param profiles 取到的原始档案（available=true 时才非空）
   */
  record FetchResult(boolean available, String reason, List<RawProfile> profiles) {}

  /** 该平台的达人数据同步是否已接入。 */
  Availability availability(String platformCode);

  /** 拉取某个店铺的达人清单。取不到时返回 {@code available=false} + 原因，不抛异常。 */
  FetchResult fetch(String platformCode, String shopCode, String bearer);
}
