package com.starcon.channel.carrier;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 10.3.1.1 承运商轨迹适配层（统一接口 + vendor 独立包的纪律）。
 *
 * <p>蓝图 10.3.1.1 的输入句第二样是「承运商轨迹接口」，而「按承运商批量拉取」是
 * 职责句的第四样。本接口就是那条跳变的<b>唯一出口</b>。
 *
 * <p>⚠️ 与本包同族的 {@link CarrierHandoverAdapter}（10.3.2.1 交运批次）一样，
 * 本包<b>刻意不放任何指向真实域名的 HTTP 客户端，也刻意不放一个假的 vendor 实现</b>。
 * 理由不是图省事，而是一条具体的危害：一个「返回成功轨迹」的假适配器会让
 * 「这票货走到哪了」在数据上看起来真的发生过 —— 而轨迹是<b>举证凭据</b>，
 * 丢件纠纷与平台有效追踪率申诉都要拿它当证据。凭据一旦被伪造，后面全是脏账。
 *
 * <p>所以本模块的诚实做法是：
 * <ul>
 *   <li>承运商<b>推送</b>（{@code POST /channel/logistics-tracking/events}）是
 *       <b>真实可用</b>的写入口：事件真落 PostgreSQL 16，按承运商事件 ID 在 DB 层去重。</li>
 *   <li>承运商<b>拉取</b>（{@code POST /channel/logistics-tracking/pull-tasks}）是
 *       <b>真实可用</b>的入口，但当前<b>没有已接通的 vendor 实现</b>：此时拉取任务
 *       如实落 {@code NO_CHANNEL} 并写明理由，<b>不返回空轨迹冒充「承运商说没轨迹」</b>。</li>
 * </ul>
 *
 * <p>将来 10.1.1.1 交付承运商连接后，在本包下按 vendor 建独立子包
 * （{@code carrier/<vendor>/}），实现本接口即可，上层服务与数据模型不需要改动。
 */
public interface CarrierTrackingAdapter {

  /** 承运商代码，与 {@code logistics_tracking_subject.carrier_code} 对应。 */
  String carrierCode();

  /** 一次批量拉取请求：同一个承运商的一批运单号。 */
  record TrackingQuery(String carrierCode, List<String> waybillNos) {}

  /**
   * 承运商给的一条轨迹事件。
   *
   * <p>{@code carrierEventId} 是<b>去重键的一半</b>：同一个节点被重复推送时它是同一个值。
   * {@code occurredAt} 是<b>承运商给的发生时刻</b>，不是拉取时刻 —— 用拉取时刻冒充会让
   * 「轨迹停滞多久」永远算不出来。
   */
  record CarrierEvent(
      String carrierEventId,
      String waybillNo,
      String nodeType,
      String eventCode,
      String description,
      String location,
      OffsetDateTime occurredAt,
      String exceptionCode,
      String exceptionNote,
      OffsetDateTime estimatedDeliveryAt) {}

  /**
   * 一次拉取的结果。
   *
   * <p>「这票没有轨迹」是<b>一个明确的事实</b>（{@code noEventWaybills}），
   * 与「通道没接通」是两件事：前者进 {@code NO_EVENT} 桶，后者整轮落
   * {@code NO_CHANNEL}。把两者混成空列表，现场就会以为货没动。
   */
  record PullOutcome(List<CarrierEvent> events, List<String> noEventWaybills) {}

  /** 承运商轨迹读取失败。区分「未接通」与「被拒」：前者是能力缺口，后者是业务事实。 */
  class CarrierTrackingException extends RuntimeException {
    private final boolean connectionFailure;

    public CarrierTrackingException(String message, boolean connectionFailure) {
      super(message);
      this.connectionFailure = connectionFailure;
    }

    public CarrierTrackingException(String message, boolean connectionFailure, Throwable cause) {
      super(message, cause);
      this.connectionFailure = connectionFailure;
    }

    /** 通道未接通 / 凭据失效：任务应落 NO_CHANNEL，而不是记为「拉了但没拉到」。 */
    public boolean connectionFailure() {
      return connectionFailure;
    }
  }

  /**
   * 按承运商批量拉取轨迹。
   *
   * <p>实现必须：只负责与承运商交互，<b>不负责落库</b>（落库统一由服务层做，
   * 所以「事件按事件 ID 去重且不可改写」这条不变量在适配器层就无法被绕过 ——
   * 适配器根本拿不到写事件表的句柄）。
   *
   * @throws CarrierTrackingException 通道未接通或承运商侧出错
   */
  PullOutcome pull(TrackingQuery query);
}
