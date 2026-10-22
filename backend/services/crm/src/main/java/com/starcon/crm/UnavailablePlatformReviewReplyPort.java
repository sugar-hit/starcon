package com.starcon.crm;

import org.springframework.stereotype.Service;

/**
 * 15.2.1.2 评价回复的<b>平台投递端口 · 当前实现</b>：对所有平台都答「未接入」。
 *
 * <p>🔴 <b>这不是 mock，也不是占位实现</b>：它是「这个能力现在不存在」这件事的<b>确定性表达</b>。
 *
 * <ul>
 *   <li>它<b>不返回任何成功回执</b>（{@code platformReceipt} / {@code platformReplyId} 恒为 null）——
 *       编一条回执会让「评价已经回复给买家了」看起来发生过，而现场一条真回复都没发出去。</li>
 *   <li>它<b>不抛异常</b> —— 未接入是业务状态，不是故障；投递入口据此返回
 *       「平台评价回复适配器未交付」（<b>409</b>），同时<b>把这次尝试记下来</b>
 *       （{@code crm_review_reply_delivery} 里一条 {@code NOT_INTEGRATED}）——
 *       「我们试过、没发出去、为什么」必须查得到。</li>
 *   <li>它<b>说清责任方与影响</b>，让界面上那行提示能指向真正的缺口。</li>
 * </ul>
 *
 * <p>某个平台接上评价回复适配器之后，本类被替换成真正的实现
 * （{@link PlatformReviewReplyPort} 就是那条接缝），<b>服务层与前端一行都不用改</b>。
 *
 * <p>与同服务的 {@link UnavailablePlatformReviewPort}（15.2.1.1 评价**读取**）
 * **同构** —— 那条接缝处理的是「把平台评价读进来」，本条处理的是「把回复投递出去」，
 * 形状与纪律逐字相同。
 */
@Service
public class UnavailablePlatformReviewReplyPort implements PlatformReviewReplyPort {

  /** 未接入时的状态码。 */
  public static final String NOT_INTEGRATED = "NOT_INTEGRATED";

  /** 投递结果码：未接入。 */
  public static final String OUTCOME_NOT_INTEGRATED = "NOT_INTEGRATED";

  /**
   * 未接入的原因（人能读，界面上原样显示）。
   *
   * <p>🔴 措辞要说清「<b>缺什么</b>」与「<b>会怎样</b>」，不能只说「暂不支持」——
   * 「暂不支持」会让人以为换个账号就能用。
   */
  static final String NOT_INTEGRATED_REASON =
      "平台评价回复适配器未交付：channel 的平台适配层目前只有 OAuth 授权适配器，"
          + "没有任何平台的买家评价回复接口。回复内容已经落库（可查、可审、可改），"
          + "但**尚未发送到平台**——买家此刻还没收到任何回复。"
          + "平台适配器接上后本端点会自动放行，无需改前端。";

  @Override
  public Availability availability(String platformCode) {
    return new Availability(false, NOT_INTEGRATED, NOT_INTEGRATED_REASON);
  }

  @Override
  public DeliveryOutcome deliver(DeliveryRequest request, String bearer) {
    // 🔴 **绝不**返回任何回执：回执 = 平台确认我们发出去了。
    //   这里没有发出去，就没有回执 —— 编一条会让「已回复」看起来发生过。
    return new DeliveryOutcome(false, OUTCOME_NOT_INTEGRATED, NOT_INTEGRATED_REASON, null, null, null);
  }
}
