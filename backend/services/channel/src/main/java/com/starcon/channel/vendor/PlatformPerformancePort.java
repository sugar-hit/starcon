package com.starcon.channel.vendor;

import java.math.BigDecimal;
import java.util.List;

/**
 * 16.1.1.1 账号健康的<b>平台账号绩效端口</b>：把「平台绩效数据同步」里的
 * <b>「平台接口」那一半</b>表达成一个可插拔的接缝。
 *
 * <p>🔴🔴 <b>当前没有任何平台实现这个端口</b> —— channel 的适配层
 * （{@code com.starcon.channel.vendor}）目前<b>只有 OAuth 适配器</b>（Amazon / Shopify /
 * eBay / Walmart / Temu / Lazada / Shopee / TikTok Shop）与广告 / 达人等适配器，
 * <b>没有账号绩效适配器</b>：ODR / 迟发率 / 取消率 / 有效追踪率**一个都拉不到**。
 * 所以本端口的唯一实现（{@link UnavailablePlatformPerformancePort}）对所有平台都返回
 * 「未接入」。
 *
 * <p>为什么不能顺手编一个：蓝图写的是「平台绩效数据同步」——
 * 而一条返回「ODR = 3.2%」的假同步会让运营以为账号真的在掉号，
 * 而现场**一个真实指标都没有**。所以本端口的三条纪律是：
 *
 * <ul>
 *   <li>不返回任何指标（{@code metrics} 恒为空）—— 空集合会被读成「这个店铺指标都是 0」，
 *       而真相是「我们查不到」；</li>
 *   <li>不抛异常 —— 未接入是业务状态，不是故障；同步入口据此返回 <b>409</b> 并
 *       <b>一条数据都不写</b>；</li>
 *   <li>说清责任方与影响，让界面上那行提示能指向真正的缺口。</li>
 * </ul>
 *
 * <p>🔴 端口<b>不传租户号</b>：调用方转发的是<b>自己的</b> {@code Authorization} bearer，
 * 平台侧仍按已认证成员关系解析租户并强制 RLS。租户隔离因此是<b>两道</b>。
 *
 * <p>🔴 端口<b>只负责取数，不负责落库</b>：落库是 {@code AccountHealthService} 的事。
 */
public interface PlatformPerformancePort {

  /**
   * 某个平台的账号绩效同步是否已接入。
   *
   * @param available 是否已接入（false ⇒ 同步不可用，**不是「指标都是 0」**）
   * @param status 状态码：{@code NOT_INTEGRATED} 未接入 / {@code AVAILABLE} 已接入
   * @param reason 人能读的原因（未接入时必填，界面上要显示出来）
   * @param ownerModule 缺口的归属模块（未接入时必填）
   */
  record Availability(
      boolean available, String status, String reason, String ownerModule) {}

  /**
   * 平台侧的一条原始绩效指标。
   *
   * <p>🔴 全部是<b>原始值</b>：{@code value} 是 <b>0..1 比值</b>，
   * 允许为 {@code null}，意思是<b>平台侧没给这个数</b>（不可比），<b>不是 0</b>。
   */
  record RawMetric(
      String metricCode, BigDecimal value, String periodStart, String periodEnd) {}

  /**
   * 一次拉取的结论。
   *
   * @param available 是否真的取到了（false ⇒ {@code metrics} 必为空，
   *     <b>不得</b>用空集合冒充「这个店铺指标都是 0」）
   * @param reason 取不到的原因（人能读）
   * @param metrics 取到的原始指标（available=true 时才非空）
   */
  record FetchResult(boolean available, String reason, List<RawMetric> metrics) {}

  /** 该平台的账号绩效同步是否已接入。 */
  Availability availability(String platformCode);

  /** 拉取某个店铺的绩效指标。取不到时返回 {@code available=false} + 原因，不抛异常。 */
  FetchResult fetch(String platformCode, String shopCode, String bearer);
}
