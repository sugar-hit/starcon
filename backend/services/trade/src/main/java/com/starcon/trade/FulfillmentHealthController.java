package com.starcon.trade;

import static com.starcon.trade.FulfillmentHealthModels.*;

import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 1.1.2.3 履约健康。
 *
 * <p>蓝图：「<b>展示履约链路的健康水位：待发货积压、平均出库时长、超时发货率、物流异常件占比、
 * 平台绩效指标（ODR、迟发率、有效追踪率）。</b>输入：08 仓储作业的出库时效投影；10 物流的轨迹异常投影；
 * 02 渠道同步的绩效指标。输出：<b>健康度卡片 + 红灯项明细跳转</b>。」
 *
 * <p><b>命名空间 {@code /trade/fulfillment/health}，与兄弟模块刻意分开</b>：
 *
 * <ul>
 *   <li>不挂 {@code /trade/fulfillment/pending-shipment}（9.4.1.1）—— 那是<b>维护</b>待发货的命名空间，
 *       本模块是<b>度量</b>；把度量挂在被度量对象的路径下，会让人以为改这里能改待发货单；
 *   <li>不挂 {@code /trade/fulfillment/shipment-records}（9.4.1.2）—— 那是发货记录的事实与状态机，
 *       本模块只读它的投影做汇总；
 *   <li>不挂 {@code /trade/fulfillment/timeliness}（9.5.1.1）—— 那是订单时效分析（按维度拆解），
 *       本模块是工作台的一屏水位。两者正交：**本模块的超时发货率直接引用 9.4.1.2 的绩效口径，
 *       不另立第二套算法**，否则同一个词会有两个分母。
 * </ul>
 *
 * <p><b>三个形状，刻意逐个列出、不做通配</b>：
 *
 * <ul>
 *   <li>{@code capabilities} 能力与上游可用性（含 ODR 无上游 ⇒ available=false + ownerModule + impact）
 *   <li>{@code summary} 健康度卡片（每张卡带公式、分母、口径与灯色判定依据）
 *   <li>{@code red-flags} 红灯项明细（真分页 + 真筛选；口径与卡片**同源**）
 * </ul>
 *
 * <p><b>刻意没有 POST / PUT / DELETE</b>：卷 01 的红线是「工作台只读 + 跳转；一切状态变更回到归属系统完成」。
 * 要登记发货时限回 9.4.1.1，要改轨迹状态回 9.4.1.2，要处理代发异常回 9.6.1.3。
 *
 * <p><b>权限码复用 {@code trade.fulfillment.read}</b>（identity 已注册），<b>刻意不新起
 * {@code trade.health.*}</b>：identity 里没有对应码的话，用一个不存在的码会让连 OWNER 都 403
 * （8.1.2.1 / 8.5.2.1 车道都踩过这个坑）。
 *
 * <p>租户**不**来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 */
@RestController
@RequestMapping("/trade/fulfillment/health")
public class FulfillmentHealthController {

  private final AccessService access;
  private final FulfillmentHealthService health;

  public FulfillmentHealthController(AccessService access, FulfillmentHealthService health) {
    this.access = access;
    this.health = health;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, permission);
    return access.run(actor, permission, () -> work.apply(actor));
  }

  /**
   * 能力与口径声明。
   *
   * <p>逐维度报出量得到 / 量不到、量不到时归谁、对本模块的影响、计算公式与分母。
   * 「平均出库时长」在这里只是一条**声明**（数据由 08 仓储作业的服务自己出数），
   * 「平台 ODR」在这里是 available=false（仓内没有该上游）。
   */
  @GetMapping("/capabilities")
  public FulfillmentHealthCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "trade.fulfillment.read", a -> health.capabilities());
  }

  /**
   * 健康度汇总：每张卡带公式、分母、口径、灯色与灯色判定依据。
   *
   * <p>「平均出库时长」不在本响应里 —— 出库时效由 08 仓储作业的服务自己出数
   * （{@code /warehouse/outbound-timeliness/report}），前端合并时补上那张卡；
   * 本服务**不**去查 warehouse 的库（跨服务 SQL / 跨库 FK / 共享视图都被禁止）。
   */
  @GetMapping("/summary")
  public FulfillmentHealthSummary summary(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "trade.fulfillment.read", a -> health.summary(a.tenantId()));
  }

  /**
   * 红灯项明细（真分页 + 真筛选）。
   *
   * <p>{@code kind} 不给时是全部四类；给了必须是 {@code capabilities.redFlagKinds} 里的一个 ——
   * 放行一个不存在的类型只会得到一个空列表，看起来像「没有问题」。
   */
  @GetMapping("/red-flags")
  public FulfillmentHealthRedFlagPage redFlags(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String kind,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    if (kind != null && !kind.isBlank() && !FulfillmentHealthService.RED_FLAG_KINDS.contains(kind.trim())) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "未知的红灯类型："
              + kind
              + "。可用类型："
              + String.join(" / ", FulfillmentHealthService.RED_FLAG_KINDS));
    }
    return call(auth, "trade.fulfillment.read", a -> health.redFlags(a.tenantId(), kind, page, size));
  }
}
