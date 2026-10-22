package com.starcon.channel.vendor;

/**
 * 14.5.1.2 建联与样品的<b>平台官方私信端口</b>：把「模板化私信邀约（走官方接口）」里的
 * <b>「走官方接口」那一半</b>表达成一个可插拔的接缝。
 *
 * <p>🔴🔴 <b>当前没有任何平台实现这个端口</b> —— channel 的适配层
 * （{@code com.starcon.channel.vendor}）目前<b>只有 OAuth 适配器</b>（Amazon / Shopify /
 * eBay / Walmart / Temu / Lazada / Shopee / TikTok Shop），<b>没有达人私信适配器</b>。
 * 所以本端口的唯一实现（{@link UnavailableInfluencerOutreachPort}）对所有平台都返回
 * 「未接入」。
 *
 * <p>🔴 <b>为什么不能顺手编一个</b>：蓝图把「建联必须走官方接口」写成加粗句，并说
 * 「批量私信是平台风控重点，第三方工具易导致账号风险」。所以：
 *
 * <ul>
 *   <li>造一个假的平台私信端点 ⇒ 界面会以为「平台私信已经接上了」，运营真去用它，
 *       私信发不出去还查不出原因；</li>
 *   <li>返回硬编码的「发送成功」⇒ 那是假事实（消息根本没发出去）；</li>
 *   <li>静默吞掉发送请求 ⇒ 运营以为私信发出去了，达人根本没收到，
 *       而「达人没收到」正是建联流程最贵的一种失败。</li>
 * </ul>
 *
 * <p>所以唯一的诚实做法是<b>把缺口显式说出来</b>：{@link Availability#available()} = false
 * + 一句人能读的 {@link Availability#reason()}，并且<b>不写任何数据</b>。
 *
 * <p>🔴 端口<b>不传租户号</b>：调用方转发的是<b>自己的</b> {@code Authorization} bearer，
 * 平台侧仍按已认证成员关系解析租户并强制 RLS。租户隔离因此是<b>两道</b>
 * （channel 侧 RLS + 平台侧），不是本服务替对方决定租户。
 *
 * <p>🔴 端口<b>只负责发，不负责落库</b>：落库是 {@code InfluencerOutreachService} 的事
 * （写进 {@code influencer_outreach} 表并推进状态）。端口返回发送结论，
 * 由服务层决定怎么落 —— 否则「谁拥有写入」就说不清了。
 *
 * <p>🔴 端口<b>没有批量形状</b>：蓝图的红线是「建联必须走官方接口，批量私信是平台风控重点」。
 * 本端口<b>刻意不提供</b>任何「一次发 N 条」的入口 —— 批量能力只做到
 * 「按规则批量生成草稿」，每一条仍要**逐条**登记平台回执才变成 SENT。
 */
public interface InfluencerOutreachPort {

  /**
   * 某个平台的达人私信接口是否已接入。
   *
   * @param available 是否已接入（false ⇒ 私信发不出去，**不是「今天没有可发的人」**）
   * @param status 状态码：{@code NOT_INTEGRATED} 未接入 / {@code AVAILABLE} 已接入
   * @param reason 人能读的原因（未接入时必填，界面上要显示出来）
   */
  record Availability(boolean available, String status, String reason) {}

  /**
   * 一次发送的结论。
   *
   * @param sent 是否真的发出去了（false ⇒ {@code platformMessageId} 必为空串）
   * @param reason 人能读的原因（未发出时必填）
   * @param platformMessageId 平台侧消息 id（发出时必填）
   */
  record SendResult(boolean sent, String reason, String platformMessageId) {}

  /**
   * 一次发送请求。
   *
   * @param platformCode 平台编码
   * @param shopCode 店铺编码
   * @param externalInfluencerId 平台侧达人 id（空串 = 还没登记，**发不出去**）
   * @param subject 标题
   * @param body 正文
   * @param idempotencyKey 幂等键（重放不重复发）
   * @param bearer 调用方自己的 bearer（转发给平台侧，**不传租户号**）
   */
  record SendRequest(
      String platformCode,
      String shopCode,
      String externalInfluencerId,
      String subject,
      String body,
      String idempotencyKey,
      String bearer) {}

  /** 该平台的达人私信接口是否已接入。 */
  Availability availability(String platformCode);

  /**
   * 发送一条私信。
   *
   * <p>🔴 <b>一次只发一条</b>：本端口<b>刻意没有</b>批量形状。
   *
   * @return 发送结论（未接入时返回 {@code sent=false} + 人能读原因，**不抛异常**）
   */
  SendResult send(SendRequest request);
}
