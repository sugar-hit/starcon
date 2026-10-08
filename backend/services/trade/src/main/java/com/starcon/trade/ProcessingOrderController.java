package com.starcon.trade;

import static com.starcon.trade.ProcessingModels.*;

import jakarta.validation.Valid;
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
 * 6.4.1.1 加工单。
 *
 * <p>命名空间 {@code /trade/processing}：卷 06 的前端入口是 {@code /purchase/processing}，
 * 浏览器只经 /api/trade 代理访问；后端路径按**服务**命名空间走，与 9.3.2.2
 * （前端 /orders、后端 /trade/fulfillment/routing）同一纪律。
 *
 * <p>权限码用 {@code trade.processing.read} / {@code trade.processing.update}：
 * 下达一张加工单等于让仓库按它领料、让工厂按它备料，不是读权限能带过去的动作。
 *
 * <p>租户**不**来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 */
@RestController
@RequestMapping("/trade/processing")
public class ProcessingOrderController {

  private final AccessService access;
  private final ProcessingOrderService processing;

  public ProcessingOrderController(AccessService access, ProcessingOrderService processing) {
    this.access = access;
    this.processing = processing;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "trade." + permission);
    return access.run(actor, "trade." + permission, () -> work.apply(actor));
  }

  // ================================================================ 能力声明

  /**
   * 本模块的能力与上下游可用性。
   *
   * <p>存在的理由与 9.3.2.2 一致：把「哪些输入真有依据」「库存动作归哪个服务」
   * 「哪些下游还没交付」显式报出来，而不是让调用方从 404 或空数组里去猜。
   */
  @GetMapping("/capabilities")
  public ProcessingCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "processing.read", a -> processing.capabilities());
  }

  // ================================================================ 加工厂

  /** 加工厂列表（真分页 + 全量汇总）。 */
  @GetMapping("/factories")
  public FactoryPage factories(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String factoryKind,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "processing.read",
        a -> processing.pageFactories(new FactoryQuery(factoryKind, status, keyword, page, size)));
  }

  /** 加工厂详情。跨租户在 RLS 下查不到 → 404。 */
  @GetMapping("/factories/{factoryId}")
  public FactorySaved factory(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID factoryId) {
    return call(auth, "processing.read", a -> processing.factory(factoryId));
  }

  /** 登记一家加工厂（编码租户内唯一）。 */
  @PostMapping("/factories")
  public FactorySaved createFactory(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody FactoryInput input) {
    return call(auth, "processing.update", a -> processing.saveFactory(input));
  }

  /** 维护加工厂档案（名称 / 厂别 / 启停 / 备注）。编码与租户不可改。 */
  @PutMapping("/factories/{factoryId}")
  public FactorySaved updateFactory(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID factoryId,
      @Valid @RequestBody FactoryInput input) {
    return call(auth, "processing.update", a -> processing.updateFactory(factoryId, input));
  }

  // ================================================================ 组件需求量试算

  /**
   * 按 BOM 版本试算组件需求量（只读，不落库）。
   *
   * <p>口径来自 catalog 3.5.2.1 的规则 R7（单位用量 × 产量 × (1 + 损耗率/100)，
   * 6 位小数 HALF_UP，损耗率是<b>百分数</b>），本服务不重算、不取整。
   */
  @PostMapping("/requirements")
  public RequirementPreview previewRequirement(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody RequirementPreviewInput input) {
    return call(auth, "processing.read", a -> processing.previewRequirement(input, auth));
  }

  // ================================================================ 加工单

  /** 加工单列表（真分页 + 全量汇总 + 筛选；排序固定为交期升序，逾期一眼可见）。 */
  @GetMapping("/orders")
  public ProcessingOrderPage orders(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID factoryId,
      @RequestParam(required = false) UUID outputSkuId,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "false") Boolean overdueOnly,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "processing.read",
        a ->
            processing.pageOrders(
                new ProcessingOrderQuery(
                    factoryId, outputSkuId, status, keyword, overdueOnly, page, size)));
  }

  /** 加工单详情：单头 + 组件领用记录 + 入库需求通知 + 事件流 + 当前状态的合法下一步。 */
  @GetMapping("/orders/{orderId}")
  public ProcessingOrderDetail order(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID orderId) {
    return call(auth, "processing.read", a -> processing.order(orderId));
  }

  /**
   * 新建加工单（草稿）。
   *
   * <p>组件领用记录在创建时就按 catalog 的 R7 算好并冻结：冻结必须发生在
   * 「这一版 BOM 还说得清是谁」的时刻，草稿与下达之间 BOM 可能已接替新版本。
   */
  @PostMapping("/orders")
  public ProcessingOrderSaved createOrder(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody ProcessingOrderInput input) {
    return call(auth, "processing.update", a -> processing.createOrder(input, a.userId(), auth));
  }

  /**
   * 推进加工单状态（DRAFT → RELEASED → IN_PROCESS → COMPLETED → RECEIVED，外加作废）。
   *
   * <p>下达时同时生成入库需求通知（蓝图输出句的硬输出「入库需求通知 8」）。
   */
  @PostMapping("/orders/{orderId}/status")
  public ProcessingOrderSaved advanceStatus(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID orderId,
      @Valid @RequestBody ProcessingStatusInput input) {
    return call(auth, "processing.update", a -> processing.advanceStatus(orderId, input, a.userId()));
  }

  /** 进度登记（不改生命周期，只记「推进到哪、报了多少」）。 */
  @PostMapping("/orders/{orderId}/progress")
  public ProcessingOrderSaved registerProgress(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID orderId,
      @Valid @RequestBody ProcessingProgressInput input) {
    return call(auth, "processing.update", a -> processing.registerProgress(orderId, input, a.userId()));
  }

  /**
   * 登记组件领用进度。
   *
   * <p>记的是「已领了多少」，<b>不改库存</b> —— 组件出库与成品入库由 warehouse
   * 在它自己的一个本地事务里完成（蓝图硬要求）。
   */
  @PostMapping("/orders/{orderId}/components/issue")
  public ProcessingOrderSaved issueComponent(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID orderId,
      @Valid @RequestBody IssueInput input) {
    return call(auth, "processing.update", a -> processing.issueComponent(orderId, input, a.userId()));
  }

  // 裸路径 /trade/processing **刻意不放行**到 api-proxy：后端没有「一条不存在的
  // 汇总列表接口」，放行它会让界面以为可用。
}
