package com.starcon.trade;

import static com.starcon.trade.OrderSplitModels.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
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
 * 9.3.1.1 拆单。
 *
 * <p>命名空间 {@code /trade/fulfillment/split}：与 9.3.1.2 的 {@code /trade/fulfillment/consolidation}、
 * 9.3.2.1 的 {@code /trade/fulfillment/allocation}、9.3.2.2 的 {@code /trade/fulfillment/routing}
 * 并列 —— 拆、合、分仓、选履约路径是四件事，权限与日志分得开。
 *
 * <p>权限码用 {@code trade.fulfillment.read} / {@code trade.fulfillment.update}：落拆单、
 * 登记预占回执与发货回传都直接改变包裹形态与平台可见的履约状态，不是读权限能带过去的动作。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 FORCE RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 */
@RestController
@RequestMapping("/trade/fulfillment/split")
public class OrderSplitController {

  private final AccessService access;
  private final OrderSplitService split;

  public OrderSplitController(AccessService access, OrderSplitService split) {
    this.access = access;
    this.split = split;
  }

  /** 统一入口：先确认身份与权限（租户<b>不</b>来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "trade." + permission);
    return access.run(actor, "trade." + permission, () -> work.apply(actor));
  }

  // ============================================================ 能力声明

  /**
   * 本模块的能力与上下游可用性。
   *
   * <p>存在的理由：拆单的上游 9.2 审单整条线未交付、库存预占的下游 7 在 warehouse 独立库、
   * 9.3.3.1 是本批并行车道 —— 「本模块到底预占没预占上游库存」「有没有真的接上审单」
   * 必须由接口自己说，而不是让界面从空数组里猜。
   */
  @GetMapping("/capabilities")
  public OrderSplitCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "fulfillment.read", a -> split.capabilities());
  }

  // ============================================================ 拆单规则

  /** 拆单规则读取。没登记过就是 null —— 不是一份带默认值的规则。 */
  @GetMapping("/rule")
  public SplitRule rule(@RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "fulfillment.read", a -> split.rule());
  }

  /** 登记 / 更新拆单规则（upsert，一租户一条）。 */
  @PutMapping("/rule")
  public SplitRule saveRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody SplitRuleInput input) {
    return call(auth, "fulfillment.update", a -> split.saveRule(input, a.membershipId()));
  }

  // ============================================================ 发货仓

  /** 发货仓清单（按 warehouse_ref 字典序；activeOnly=true 时只列参与拆单候选的仓）。 */
  @GetMapping("/warehouses")
  public List<SplitWarehouseRow> warehouses(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false, defaultValue = "true") Boolean activeOnly) {
    return call(auth, "fulfillment.read", a -> split.warehouses(Boolean.TRUE.equals(activeOnly)));
  }

  /** 登记 / 更新发货仓（upsert，按 warehouse_ref）。 */
  @PostMapping("/warehouses")
  public SplitWarehouseRow saveWarehouse(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody SplitWarehouseInput input) {
    return call(auth, "fulfillment.update", a -> split.saveWarehouse(input, a.membershipId()));
  }

  // ============================================================ 库存分布

  /** 可用库存分布清单（7.1.1.3 的租户可维护替身）。 */
  @GetMapping("/stock-snapshots")
  public List<SplitStockSnapshotRow> stockSnapshots(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String warehouseRef,
      @RequestParam(required = false) String skuRef) {
    return call(
        auth, "fulfillment.read", a -> split.stockSnapshots(warehouseRef, skuRef));
  }

  /** 登记 / 更新可用库存分布（upsert，同仓同 SKU 一条当前值）。 */
  @PostMapping("/stock-snapshots")
  public SplitStockSnapshotRow saveStockSnapshot(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody SplitStockSnapshotInput input) {
    return call(
        auth, "fulfillment.update", a -> split.saveStockSnapshot(input, a.membershipId()));
  }

  // ============================================================ 拆单

  /**
   * 拆单预览（<b>无副作用</b>：不写 order_split* 任何一张表）。
   *
   * <p>与真正落库走的是同一段判定逻辑 —— 两边各算一次必然出现
   * 「预览说是 3 个单元、落库变 2 个」这种最消耗信任的偏差。
   */
  @PostMapping("/preview")
  public SplitPreview preview(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody SplitInput input) {
    return call(auth, "fulfillment.read", a -> split.preview(input));
  }

  /** 拆单列表（真分页 + 状态/维度/订单/仓筛选；三组汇总额不受分页影响）。 */
  @GetMapping("/splits")
  public SplitPage splits(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String strategy,
      @RequestParam(required = false) String orderRef,
      @RequestParam(required = false) String warehouseRef,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "fulfillment.read",
        a ->
            split.pageSplits(
                new SplitQuery(keyword, status, strategy, orderRef, warehouseRef, page, size)));
  }

  /**
   * 落一次拆单（头 + 履约单元 + 行级明细 + 候选依据 + 预占指令 + 回传任务）。
   *
   * <p><b>金额守恒在这一刻被 DB 独立复核</b>：各单元分摊额 + 尾差必须恰好等于订单总金额，
   * 不等则整个事务被拒。拆单全程不产生新的销售订单。
   */
  @PostMapping("/splits")
  public SplitSaved split(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody SplitInput input) {
    return call(auth, "fulfillment.update", a -> split.split(input, a.membershipId()));
  }

  /** 拆单详情：头 + 单元 + 行级明细 + 候选依据 + 预占指令 + 回传任务 + 事件流。跨租户 → 404。 */
  @GetMapping("/splits/{splitId}")
  public SplitDetail splitDetail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID splitId) {
    return call(auth, "fulfillment.read", a -> split.split(splitId));
  }

  /**
   * 推进拆单状态（PLANNED → CONFIRMED → DISPATCHED，或 → CANCELLED）。
   *
   * <p>终态不可离开；作废必须给原因，并连带撤销未落定的预占指令
   * （方案没了还让 warehouse 去锁货就是在锁一堆没人会取的货）。
   */
  @PostMapping("/splits/{splitId}/status")
  public SplitSaved advanceStatus(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID splitId,
      @Valid @RequestBody SplitStatusInput input) {
    return call(
        auth, "fulfillment.update", a -> split.advanceStatus(splitId, input, a.membershipId()));
  }

  // ============================================================ 预占指令

  /** 预占指令列表（真分页）：这是「哪些货等着 warehouse 去锁」的查询入口。 */
  @GetMapping("/reservation-commands")
  public SplitReservationPage reservations(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID splitId,
      @RequestParam(required = false) UUID unitId,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String skuRef,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "fulfillment.read",
        a -> split.pageReservations(new SplitReservationQuery(splitId, unitId, status, skuRef, page,
            size)));
  }

  /**
   * 登记 warehouse 侧对预占指令的<b>回执</b>。
   *
   * <p>这里只登记回执，<b>不把 upstream_reserved 置真</b>（DB CHECK 恒钉为 false）：
   * 真实预占住在 warehouse 独立库，本服务没有资格断言「这批货被锁住了」。
   */
  @PostMapping("/splits/{splitId}/units/{unitId}/reservation")
  public SplitReservationCommand recordReservation(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID splitId,
      @PathVariable UUID unitId,
      @Valid @RequestBody SplitReservationInput input) {
    return call(
        auth,
        "fulfillment.update",
        a -> split.recordReservation(splitId, unitId, input, a.membershipId()));
  }

  // ============================================================ 发货回传

  /** 回传任务列表（真分页）：这是「拆分后哪部分货还没回传物流号」的查询入口。 */
  @GetMapping("/writeback-tasks")
  public SplitWritebackPage writebacks(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID splitId,
      @RequestParam(required = false) String platformCode,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String platformOrderRef,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "fulfillment.read",
        a ->
            split.pageWritebacks(
                new SplitWritebackQuery(splitId, platformCode, status, platformOrderRef, page, size)));
  }

  /**
   * 登记<b>某个履约单元</b>的发货回传结果（蓝图输出③：回传按拆分执行）。
   *
   * <p>按单元逐条登记，不存在「给整单记一个物流号」的形状 ——
   * 表上 UNIQUE(tenant_id, unit_id) 已经把这件事钉死。
   */
  @PostMapping("/splits/{splitId}/units/{unitId}/writeback")
  public SplitWritebackTask recordWriteback(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID splitId,
      @PathVariable UUID unitId,
      @Valid @RequestBody SplitWritebackInput input) {
    return call(
        auth,
        "fulfillment.update",
        a -> split.recordWriteback(splitId, unitId, input, a.membershipId()));
  }
}
