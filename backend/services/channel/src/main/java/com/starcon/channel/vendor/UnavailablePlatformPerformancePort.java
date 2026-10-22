package com.starcon.channel.vendor;

import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 16.1.1.1 账号健康的<b>平台账号绩效端口 · 当前实现</b>：对所有平台都答「未接入」。
 *
 * <p>🔴 <b>这不是 mock，也不是占位实现</b>：它是「这个能力现在不存在」这件事的
 * <b>确定性表达</b>。
 *
 * <ul>
 *   <li>它<b>不返回任何指标</b>（{@code metrics} 恒为空）—— 硬编码一组指标是假事实，
 *       界面上会出现「这个店铺 ODR 3.2%」的假读数，而现场一个真实指标都没有；</li>
 *   <li>它<b>不抛异常</b> —— 未接入是业务状态，不是故障；同步入口据此返回
 *       <b>409</b> 并<b>一条数据都不写</b>；</li>
 *   <li>它<b>说清责任方与影响</b>，让界面上那行提示能指向真正的缺口。</li>
 * </ul>
 *
 * <p>某个平台接上账号绩效适配器之后，本类被替换成真正的实现
 * （{@link PlatformPerformancePort} 就是那条接缝），<b>服务层与前端一行都不用改</b>。
 */
@Component
public class UnavailablePlatformPerformancePort implements PlatformPerformancePort {

  /** 未接入时的状态码。 */
  public static final String NOT_INTEGRATED = "NOT_INTEGRATED";

  /** 缺口的归属模块（卷 02 同步运行）。 */
  public static final String OWNER_MODULE = "2.4";

  /**
   * 未接入的原因（人能读，界面上原样显示）。
   *
   * <p>🔴 措辞要说清「<b>缺什么</b>」与「<b>会怎样</b>」，不能只说「暂不支持」——
   * 「暂不支持」会让人以为换个账号就能用。
   */
  static final String NOT_INTEGRATED_REASON =
      "平台账号绩效适配器未交付：channel 的平台适配层目前没有账号绩效接口，"
          + "ODR / 迟发率 / 取消率 / 有效追踪率**一个都拉不到**。"
          + "绩效数据目前只能**人工录入**（录入的条目一律标 MANUAL，不会冒充平台同步）；"
          + "平台适配器接上后本端点会自动放行，无需改前端。";

  @Override
  public Availability availability(String platformCode) {
    return new Availability(false, NOT_INTEGRATED, NOT_INTEGRATED_REASON, OWNER_MODULE);
  }

  @Override
  public FetchResult fetch(String platformCode, String shopCode, String bearer) {
    // 🔴 **不返回任何指标**：空集合会被读成「这个店铺的指标都是 0」，
    //   而真相是「我们查不到」。available=false + reason 才是正确表达。
    return new FetchResult(false, NOT_INTEGRATED_REASON, List.of());
  }
}
