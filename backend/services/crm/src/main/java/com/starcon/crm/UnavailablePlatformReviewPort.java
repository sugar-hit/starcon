package com.starcon.crm;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 15.2.1.1 评价列表的<b>平台评价数据端口 · 当前实现</b>：对所有平台都答「未接入」。
 *
 * <p>🔴 <b>这不是 mock，也不是占位实现</b>：它是「这个能力现在不存在」这件事的<b>确定性表达</b>。
 *
 * <ul>
 *   <li>它<b>不返回任何评价</b>（{@code reviews} 恒为空）—— 硬编码评价列表是假事实，
 *       界面上会出现一条查无此人的评价。</li>
 *   <li>它<b>不抛异常</b> —— 未接入是业务状态，不是故障；同步入口据此返回
 *       「平台评价适配器未交付」（<b>409</b>）并<b>不写任何数据</b>。</li>
 *   <li>它<b>说清责任方与影响</b>，让界面上那行提示能指向真正的缺口。</li>
 * </ul>
 *
 * <p>某个平台接上评价适配器之后，本类被替换成真正的实现
 * （{@link PlatformReviewPort} 就是那条接缝），<b>服务层与前端一行都不用改</b>。
 *
 * <p>与同服务的 {@link UnavailablePlatformEmailPort}（15.1.2.1 买家邮件）**同构** ——
 * 那条接缝处理的是「买家邮件」，本条处理的是「买家评价」，形状与纪律逐字相同。
 */
@Service
public class UnavailablePlatformReviewPort implements PlatformReviewPort {

  /** 未接入时的状态码。 */
  public static final String NOT_INTEGRATED = "NOT_INTEGRATED";

  /**
   * 未接入的原因（人能读，界面上原样显示）。
   *
   * <p>🔴 措辞要说清「<b>缺什么</b>」与「<b>会怎样</b>」，不能只说「暂不支持」——
   * 「暂不支持」会让人以为换个账号就能用。
   */
  static final String NOT_INTEGRATED_REASON =
      "平台评价适配器未交付：channel 的平台适配层目前只有 OAuth 授权适配器，"
          + "没有任何平台的买家评价读取接口。评价目前只能**人工登记**；"
          + "平台适配器接上后本端点会自动放行，无需改前端。";

  @Override
  public Availability availability(String platformCode) {
    return new Availability(false, NOT_INTEGRATED, NOT_INTEGRATED_REASON);
  }

  @Override
  public FetchResult fetch(String platformCode, UUID shopId, String bearer) {
    // 🔴 **不返回任何评价**：空集合会被读成「这个店铺没有评价」，
    //   而真相是「我们拉不到」。available=false + reason 才是正确表达。
    return new FetchResult(false, NOT_INTEGRATED_REASON, List.of());
  }
}
