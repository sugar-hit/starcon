package com.starcon.crm;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * {@link PlatformNegativeReviewPort} 的<b>当前实现</b>：对所有平台都答「未接入」。
 *
 * <p>🔴 <b>这不是 mock，也不是占位实现</b>：它是「这个能力现在不存在」这件事的
 * <b>确定性表达</b>。
 *
 * <ul>
 *   <li>它<b>不返回任何回执</b>（{@code platformAppealId} / {@code receipt} 恒为 null）——
 *       编一条回执会让「差评已经申诉掉了」看起来发生过，而现场一条真申诉都没提交，
 *       申诉配额却被白白消耗掉。</li>
 *   <li>它<b>不抛异常</b> —— 未接入是业务状态，不是故障；服务层据此返回
 *       「平台差评申诉适配器未交付」（<b>409</b>），并且<b>一行都不写</b>
 *       （连申诉状态都不会变成「已提交」）。</li>
 *   <li>它<b>说清责任方与影响</b>，让界面上那行提示能指向真正的缺口。</li>
 * </ul>
 *
 * <p>某个平台接上差评申诉适配器之后，本类被替换成真正的实现，
 * <b>服务层与前端一行都不用改</b>。
 *
 * <p>与同服务的 {@link UnavailablePlatformMessageSendPort}（15.1.1.2 站内信发送）
 * {@link UnavailablePlatformReviewReplyPort}（15.2.1.2 评价回复投递）
 * **同构** —— 三条接缝处理的是三件不同的事，纪律逐字相同。
 */
@Service
public class UnavailablePlatformNegativeReviewPort implements PlatformNegativeReviewPort {

  /** 未接入时的状态码。 */
  public static final String NOT_INTEGRATED = "NOT_INTEGRATED";

  /**
   * 未接入的**归属模块**：2 = 渠道与店铺（平台适配器都在 channel 服务的 vendor 包下）。
   *
   * <p>不是 15 —— 15 是客户与客服卷，而平台适配器按蓝图第 2 卷归 channel。
   */
  static final String OWNER_MODULE = "2";

  /**
   * 未接入的原因（人能读，界面上原样显示）。
   *
   * <p>🔴 措辞要说清「<b>缺什么</b>」与「<b>会怎样</b>」，不能只说「暂不支持」——
   * 「暂不支持」会让人以为换个账号就能用。
   */
  static final String NOT_INTEGRATED_REASON =
      "平台差评申诉适配器未交付：channel 的平台适配层目前只有 OAuth 授权适配器，"
          + "没有任何平台的买家评价申诉 / 修改申请接口。申诉任务已经落库（可查、可审、可撤），"
          + "但**尚未提交到平台**——平台此刻还没有收到任何申诉。"
          + "平台适配器接上后本端点会自动放行，无需改前端。";

  @Override
  public List<NegativeReviewAvailability> availability(List<String> platformCodes) {
    List<NegativeReviewAvailability> out = new ArrayList<>();
    if (platformCodes == null) {
      return out;
    }
    for (String code : platformCodes) {
      if (code == null || code.isBlank()) {
        continue;
      }
      out.add(new NegativeReviewAvailability(code, false, false, reason(code), OWNER_MODULE));
    }
    return out;
  }

  @Override
  public NegativeReviewAppealOutcome submitAppeal(
      NegativeReviewAppealRequest request, String bearer) {
    // 🔴 **绝不**返回任何回执：回执 = 平台确认我们提交了申诉。
    //   这里没有提交出去，就没有回执 —— 编一条会让「申诉已提交」看起来发生过。
    String platform = request == null ? "" : request.platformCode();
    return new NegativeReviewAppealOutcome(
        false, false, null, null, reason(platform), OWNER_MODULE);
  }

  /** 逐平台的原因文案：说清「缺什么、归谁、现在怎么办」。 */
  private String reason(String platformCode) {
    return "平台「"
        + platformCode
        + "」的买家评价申诉接口**未接入**："
        + "channel 服务的 vendor 包下当前只有 OAuth 适配器，没有差评申诉 / 修改申请适配器，"
        + "所以平台侧还没有申诉通道。"
        + "在此之前只能走**人工交接**：运营在平台侧实际提交后回本服务登记提交时刻与平台判定，"
        + "申诉任务、配额占用与结果都已经可用。";
  }
}
