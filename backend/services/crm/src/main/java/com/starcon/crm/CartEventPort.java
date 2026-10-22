package com.starcon.crm;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 15.6.2.2 购物车与弃单挽回的<b>平台购物车事件数据端口</b>：把「采集加购 / 结账未完成事件
 * （Shopify 等提供 cart/checkout 事件）」表达成一个可插拔的接缝。
 *
 * <p>🔴🔴 <b>当前没有任何平台实现这个端口</b> —— channel 的适配层
 * （{@code com.starcon.channel.vendor}）目前<b>只有 OAuth 适配器</b>
 * （Amazon / Shopify / eBay / Walmart / Temu / Lazada / Shopee / TikTok Shop 共 19 个文件），
 * <b>没有购物车 / 结账事件适配器</b>。所以本端口的唯一实现
 * （{@link UnavailableCartEventPort}）对所有平台都答「未接入」。
 *
 * <p>为什么不能顺手编一个：蓝图写的是「输入：购物车/结账事件（2 渠道 Shopify 等）」——
 * 人工登记这一半<b>本模块已交付</b>（{@code POST /crm/cart-recovery/abandonments}），
 * 平台接口那一半没交付时，唯一诚实的做法是<b>把缺口显式说出来</b>
 * （{@code available=false} + 一句人能读的 {@code reason}），而不是：
 *
 * <ul>
 *   <li>造一个假的平台端点（那会让界面以为「购物车事件已经接上了」）；</li>
 *   <li>返回硬编码的购物车列表（那是假事实 —— 那些购物车不存在）；</li>
 *   <li>静默吞掉同步请求（那会让运营以为同步过了，而真相是「一条都没拉到」——
 *       而挽回率报表的分母会因此算错，整张报表失真）。</li>
 * </ul>
 *
 * <p>🔴 端口<b>不传租户号</b>：调用方转发的是<b>自己的</b> {@code Authorization} bearer，
 * 平台侧仍按已认证成员关系解析租户并强制 RLS。租户隔离因此是<b>两道</b>
 * （crm 侧 RLS + 平台侧），不是本服务替对方决定租户。
 *
 * <p>🔴 端口<b>只负责取数，不负责落库</b>：落库是 {@link CartRecoveryService} 的事。
 * 端口返回原始事件，由服务层决定怎么落、落成什么口径 —— 否则「谁拥有写入」就说不清了。
 */
public interface CartEventPort {

  /**
   * 某个平台的购物车事件能力是否已接入。
   *
   * @param available 是否已接入（false ⇒ 同步不可用，<b>不是「没有购物车」</b>）
   * @param status 状态码：{@code NOT_INTEGRATED} 未接入 / {@code AVAILABLE} 已接入
   * @param reason 人能读的原因（未接入时必填，界面上要显示出来）
   */
  record Availability(boolean available, String status, String reason) {}

  /**
   * 平台侧的一条原始购物车 / 结账事件。
   *
   * <p>全部是<b>原始值</b>：{@code buyerEmail} / {@code buyerPhone} 允许为 {@code null}，
   * 意思是<b>平台侧没给这个值</b>，<b>不是</b>空字符串。
   *
   * @param stage 加购阶段：CART（只加购）/ CHECKOUT（进了结账）
   * @param amount 购物车金额（BigDecimal，禁浮点）
   * @param itemCount 商品件数（BigDecimal，禁浮点）
   */
  record RawCartEvent(
      String externalEventId,
      String cartRef,
      String stage,
      String buyerRef,
      String buyerEmail,
      String buyerPhone,
      String currency,
      BigDecimal amount,
      BigDecimal itemCount,
      String itemSummary,
      OffsetDateTime abandonedAt) {}

  /**
   * 一次拉取的结论。
   *
   * @param available 是否真的取到了（false ⇒ {@code events} 必为空，
   *     <b>不得</b>用空集合冒充「这个店铺没有购物车」）
   * @param reason 取不到的原因（人能读）
   * @param events 取到的原始事件（available=true 时才非空）
   */
  record FetchResult(boolean available, String reason, List<RawCartEvent> events) {}

  /** 该平台的购物车事件能力是否已接入。 */
  Availability availability(String platformCode);

  /**
   * 拉某个店铺的购物车 / 结账事件。取不到时返回 {@code available=false} + 原因，<b>不抛异常</b> ——
   * 未接入是业务状态，不是故障。
   */
  FetchResult fetch(String platformCode, UUID shopId, String bearer);
}
