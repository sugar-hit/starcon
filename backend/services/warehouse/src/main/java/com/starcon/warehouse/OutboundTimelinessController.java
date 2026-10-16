package com.starcon.warehouse;

import static com.starcon.warehouse.OutboundTimelinessModels.*;

import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 8.5.3.1 出库时效。
 *
 * <p>蓝图：「<b>统计出库各环节时效：订单到出库时长、拣货时长、打包时长、交接时长，识别超时环节与瓶颈。</b>」输入：「订单时间戳；各作业环节时间戳。」输出：「<b>时效分布 + 瓶颈环节 + 超时明细</b>。」以及红线：「<b>时效数据直接对应平台迟发率指标</b>，超时会导致绩效违规与搜索降权。」
 *
 * <p><b>命名空间新起 {@code /warehouse/outbound-timeliness}，与兄弟模块刻意分开：</b>
 *
 * <ul>
 *   <li>不挂 {@code /warehouse/outbound-orders}（8.3.1.1 出库单列表）—— 那是<b>维护</b>出库单的命名空间，语义是「改」；本模块是<b>度量</b>，把度量挂在被度量对象的路径下，会让人以为改这里能改出库单；
 *   <li>不挂 {@code /trade/fulfillment/timeliness}（9.5.1.1）—— 那是 trade 服务<b>自己</b>的订单时效，与本模块正交（它算订单维度的承诺与迟发，本模块算仓库内部四个作业环节的用时），而且跨服务命名空间混用会让调用方以为两者是同一份数；
 *   <li>不挂 {@code /warehouse/labor}（8.5.1.1 人效）—— 人效按<b>人</b>聚合，时效按<b>环节</b>聚合，分母与结论对象都不同。
 * </ul>
 *
 * <p><b>字面量子路径排在任何 {@code {uuid}} 之前</b>：uuid 形状不能被当成通配吃掉（与 8.1.2.1 / 8.5.2.1 同一纪律）。
 *
 * <p><b>权限码复用 {@code warehouse.inventory.read} / {@code warehouse.inventory.update}</b>（identity 在 V8 已注册），<b>刻意不注册</b> {@code warehouse.timeliness.*}：identity 里没有对应码的话，用一个不存在的码会让连 OWNER 都 403（8.5.2.1 的同一条结论）。读用 read、配阈值与投递订单时刻用 update —— 投递订单时刻是写事实，不是纯配置。
 *
 * <p>租户**不**来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 */
@RestController
@RequestMapping("/warehouse/outbound-timeliness")
public class OutboundTimelinessController {

  private final AccessService access;
  private final OutboundTimelinessService timeliness;

  public OutboundTimelinessController(
      AccessService access, OutboundTimelinessService timeliness) {
    this.access = access;
    this.timeliness = timeliness;
  }

  /**
   * 统一入口：先向 identity 确认身份与权限（租户**不**来自任何客户端头），再在事务里绑定 RLS 租户上下文。
   *
   * <p>把已认证的 actor 交给 work：写端点要记录 {@code reported_by_user_id} / {@code created_by_user_id}， 而重新 {@code access.require} 一次会多打一次身份服务 —— 一笔写请求两次鉴权，既慢又让日志里的鉴权次数对不上真实请求数。
   */
  private <T> T call(
      String bearer,
      String permission,
      java.util.function.Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, permission);
    return access.run(actor, permission, () -> work.apply(actor));
  }

  /**
   * 能力与口径声明。
   *
   * <p>如实报出四个环节的阈值来源（仓库覆盖 / 租户默认 / 内置兜底）与全部口径说明 —— 本模块的数直接进绩效申诉，口径只活在文档里时，争议发生时没有人能查到。
   */
  @GetMapping("/capabilities")
  public OutboundTimelinessCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID warehouseId) {
    return call(auth, "warehouse.inventory.read", a -> timeliness.capabilities(warehouseId));
  }

  /**
   * 时效报表：分布 + 瓶颈 + 超时明细（蓝图点名的三样输出一个不少）。
   *
   * <p>{@code warehouseId} 不给时是全租户视角。仓库不存在与不属于本租户都返回 404 —— 两者可观测结果必须相同，否则「猜 id」就成了探测别的租户有没有仓库的侧信道。
   */
  @GetMapping("/report")
  public OutboundTimelinessReport report(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false) UUID warehouseId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(
        auth,
        "warehouse.inventory.read",
        a -> timeliness.report(from, to, warehouseId, page, size));
  }

  /**
   * 投递订单下单时刻（trade 服务的授权入口，幂等 upsert）。
   *
   * <p>这是本服务**唯一**能拿到「订单到出库时长」起点的途径：下单时刻属 9 订单，本服务读不到 trade 的表（禁止跨服务 SQL）。未投递时该环节时长为 null 并计入缺失样本数 —— <b>不代填 now()、也不用出库单创建时刻顶替</b>。
   */
  @PostMapping("/order-times")
  public OutboundTimelinessRow recordOrderTime(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody OutboundOrderTimeInput input) {
    return call(
        auth,
        "warehouse.inventory.update",
        a -> timeliness.recordOrderTime(input, a.userId()));
  }

  /**
   * 配置阈值（仓库覆盖 &gt; 租户默认 &gt; 内置兜底；幂等 upsert）。
   *
   * <p>{@code warehouseId} 为空 = 租户默认档。蓝图把时效挂在平台迟发率上，而迟发率按平台/店铺/仓库都不同，所以阈值必须是可配数据。
   */
  @PutMapping("/thresholds")
  public OutboundThresholdView configureThreshold(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody OutboundThresholdInput input) {
    return call(
        auth,
        "warehouse.inventory.update",
        a -> timeliness.configureThreshold(input, a.userId()));
  }
}
