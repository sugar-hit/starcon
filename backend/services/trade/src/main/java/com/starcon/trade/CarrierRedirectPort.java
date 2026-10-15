package com.starcon.trade;

import java.util.UUID;

/**
 * 10.2.1.3 改址与取消的<b>承运商适配器端口</b>。
 *
 * <p>与 10.2.1.2 的 {@link CarrierWaybillPort} <b>刻意分成两个接口</b>，而不是在
 * {@code CarrierWaybillPort} 上加两个方法。理由：运单申请是「<b>要号</b>」，
 * 改址 / 取消是「<b>改 / 撤已有的号</b>」，两者的能力矩阵在现实里**独立** ——
 * 绝大多数承运商支持申请运单号，但只支持极少数场景的中途改址，
 * 有的甚至完全不接受已出票的撤销。把它们合到一个接口里，
 * 任何 vendor 都必须为不支持的能力编一个假实现（那正是最贵的一种错：
 * 界面上显示「改址成功」，承运商那边根本没这票货的第二次派送）。
 *
 * <p>AGENTS.md 外部适配器纪律：「每个 vendor 独立包、只暴露统一接口。」
 * 本接口就是那条统一接口在 trade 侧的形状 —— trade 拥有运单文档，
 * 于是改址 / 取消这一跳的<b>端口</b>落在 trade，具体 vendor 包在 channel 侧
 * （10.1 物流渠道）接通后由本端口的实现类提供。
 *
 * <p><b>⚠️ 本端口在本车道没有已接通的实现，而且这是如实反映而不是缺口</b>：
 * 10.1 物流渠道全部未交付（台账确认 10.1.1.1 / 10.1.1.2 / 10.1.1.3 / 10.1.2.1 /
 * 10.1.2.2 全「未开发」），也就是说<b>没有承运商连接配置</b>。
 * 蓝图卷 10 明写「channel 拥有物流商连接与轨迹」。
 *
 * <p>所以本模块刻意做到：
 *
 * <ol>
 *   <li><b>不凭空造一个「已接通的承运商」</b>，也<b>不写死一个假接口返回「改址受理」</b>。
 *       没有 vendor 实现时 {@link #resolve} 如实返回
 *       {@link Resolution#unavailable}，调用方据此把该单置为
 *       {@code outcome = NOT_ATTEMPTED} 并写明「渠道未接通」——
 *       <b>而不是</b>发一个编出来的受理结果出去。</li>
 *   <li>把<b>渠道规则与时限</b>做成租户可维护的本地事实（V135 的
 *       {@code logistics_carrier_redirect_rule}），接通 10.1 之后由 10.1 的
 *       连接配置喂进来即可，服务层不用改。</li>
 *   <li>承运商连接配置（endpoint / 凭据）<b>不落在本模块的表里</b> ——
 *       归 secret manager 与 10.1（AGENTS.md「安全」一节）。</li>
 * </ol>
 *
 * <p>测试里出现的实现是<b>承运商侧替身</b>（与 9.4.1.1 用 warehouse HTTP 替身、
 * 10.2.1.2 用 carrier 替身同一手法）：替的是<b>外部系统</b>，不是本模块的数据。
 * 渠道规则、改址单、取消单、通知任务<b>全部真落 PostgreSQL 16</b>。
 */
public interface CarrierRedirectPort {

  /** 一次「改址」的入参。地址字段与本模块落库的快照逐字一致（承运商看到的就是这一份）。 */
  record RedirectCommand(
      UUID redirectId,
      String redirectNo,
      String waybillNo,
      String carrierCode,
      String carrierChannel,
      String recipientName,
      String recipientCountry,
      String addressLine,
      String city,
      String postalCode) {}

  /** 一次「取消已出号运单」的入参。 */
  record CancelCommand(
      UUID cancelId,
      String cancelNo,
      String waybillNo,
      String carrierCode,
      String carrierChannel,
      String reason) {}

  /**
   * 承运商返回的结果。
   *
   * <p>⚠️ 只有 {@code outcome == ACCEPTED} 才表示<b>承运商真实受理</b>，
   * 而 V135 的触发器会在服务层把它落库前就拒绝「受理了却没有派发时刻」
   * 以及「未派发却带回了执号」这两类自相矛盾的组合。
   */
  record RedirectOutcome(WaybillRedirectModels.CarrierOutcome outcome, String carrierRef,
      String reason) {

    public static RedirectOutcome accepted(String carrierRef) {
      return new RedirectOutcome(WaybillRedirectModels.CarrierOutcome.ACCEPTED, carrierRef, "");
    }

    public static RedirectOutcome rejected(String carrierRef, String reason) {
      return new RedirectOutcome(WaybillRedirectModels.CarrierOutcome.REJECTED, carrierRef, reason);
    }

    public static RedirectOutcome unavailable(String reason) {
      return new RedirectOutcome(WaybillRedirectModels.CarrierOutcome.CARRIER_UNAVAILABLE, "",
          reason);
    }

    public static RedirectOutcome adapterMissing(String reason) {
      return new RedirectOutcome(WaybillRedirectModels.CarrierOutcome.ADAPTER_MISSING, "", reason);
    }
  }

  /** 取消的结果。与改址同一形状。 */
  record CancelOutcome(WaybillRedirectModels.CarrierOutcome outcome, String carrierRef,
      String reason) {

    public static CancelOutcome accepted(String carrierRef) {
      return new CancelOutcome(WaybillRedirectModels.CarrierOutcome.ACCEPTED, carrierRef, "");
    }

    public static CancelOutcome rejected(String carrierRef, String reason) {
      return new CancelOutcome(WaybillRedirectModels.CarrierOutcome.REJECTED, carrierRef, reason);
    }

    public static CancelOutcome unavailable(String reason) {
      return new CancelOutcome(WaybillRedirectModels.CarrierOutcome.CARRIER_UNAVAILABLE, "",
          reason);
    }

    public static CancelOutcome adapterMissing(String reason) {
      return new CancelOutcome(WaybillRedirectModels.CarrierOutcome.ADAPTER_MISSING, "", reason);
    }
  }

  /**
   * 适配器解析结果。
   *
   * <p>刻意做成 sealed：本模块对「有没有 vendor 实现」只有两种答案，
   * 不给第三种「先当作有、真到调用时才炸」。
   */
  sealed interface Resolution {
    record Connected(CarrierRedirectPort port) implements Resolution {}

    /** 没有 vendor 实现：如实说没有，并带上 ownerModule 与影响。 */
    record Unavailable(String adapterKey, String ownerModule, String reason) implements Resolution {}
  }

  /** 这个适配器键支持的服务商代码（用于 capabilities 里逐条列「谁接通了」）。 */
  String adapterKey();

  /** 解析某个承运商的改址适配器。本模块在没有实现时返回 {@link Resolution.Unavailable}。 */
  Resolution resolve(String carrierCode, String adapterKey);

  /** 承运商侧是否**支持**改址。规则表的登记是商务事实，这里是运行时确认。 */
  boolean supportsRedirect(String carrierCode);

  /** 承运商侧是否**支持**取消已出号运单。 */
  boolean supportsCancel(String carrierCode);

  /** 向承运商提交一次改址。失败必须<b>如实抛</b>或返回 REJECTED，不要吞掉返回空结果。 */
  RedirectOutcome redirect(RedirectCommand command);

  /** 向承运商提交一次取消。失败必须<b>如实抛</b>或返回 REJECTED。 */
  CancelOutcome cancel(CancelCommand command);
}
