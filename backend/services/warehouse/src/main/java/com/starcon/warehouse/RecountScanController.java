package com.starcon.warehouse;

import static com.starcon.warehouse.RecountModels.*;

import jakarta.validation.Valid;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 8.3.3.1 复核扫描入口。
 *
 * <p>命名空间 {@code /warehouse/recount-scans}。<b>刻意不挂在 {@code outbound-orders} 之下</b>：
 * 出库单是履约单据（8.3.1.x），复核是现场核对工单 —— 一张出库单可以复核多次 （判异常 → 退回拣货 → 重拣 → 再复核），每次是一张独立的复核单，
 * 挂在出库单底下会让契约看起来像「一张单只有一次复核」。 也不挂在 {@code pick-tasks} 底下：拣货完成 ≠ 可以复核，中间还隔着「出库单进入待复核」。
 *
 * <p>十四个形状，刻意逐个列出、不做通配：
 *
 * <ul>
 *   <li>{@code capabilities} 能力与上下游声明（含「复核不动库存」「高价值只取件数」两条口径）
 *   <li>{@code POST /rules}、{@code GET /rules}、{@code GET /rules/{id}}、{@code POST
 *       /rules/{id}/retire}
 *   <li>{@code POST /} 开复核单（读 8.3.2.1 的已完成拣货任务接手 + 冻结订单明细为基线）
 *   <li>{@code GET /} 列表（真分页 / 真筛选 / 排序白名单 / 汇总）
 *   <li>{@code GET /{id}} 详情（单头 + 基线行 + 扫描记录 + 异常 + 时间线 + 结论报告）
 *   <li>{@code POST /{id}/open-box} 开箱复核（高价值订单）
 *   <li>{@code POST /{id}/scans} 扫一件（条码 / 序列号）
 *   <li>{@code POST /{id}/settle} 结算（检出漏件 / 多件 / 序列号不符）
 *   <li>{@code POST /{id}/complete} 完成复核（通过 → 出库单待打包，<b>不是已发货</b>）
 *   <li>{@code POST /{id}/escalate} 异常单转人工处理
 *   <li>{@code POST /{id}/exceptions/{exceptionId}/resolve} 处置单条异常
 *   <li>{@code POST /{id}/cancel} 取消（<b>不动库存</b>）
 * </ul>
 *
 * <p>刻意<b>没有</b> PUT / DELETE：扫描记录与复核流水是事实（V117 的 append-only 守卫会直接拒），异常只能处置一次。要更正就再扫一次，要放弃就取消留原因。
 *
 * <p>权限码<b>复用</b> {@code warehouse.inventory.read} / {@code warehouse.inventory.update}， 不新起 {@code
 * warehouse.recount.*}：identity 只注册了 {@code warehouse.inventory} 与 {@code warehouse.facilities}
 * 两个页面资源的权限码，用一个<b>没有被注册</b>的码会让 连 OWNER 都 403（8.1.2.1 车道踩过这个坑并在台账里记了原因）。
 */
@RestController
@RequestMapping("/warehouse/recount-scans")
public class RecountScanController {

  private final AccessService access;
  private final RecountScanService recounts;

  public RecountScanController(AccessService access, RecountScanService recounts) {
    this.access = access;
    this.recounts = recounts;
  }

  /** 统一入口：先确认身份与权限（租户<b>不</b>来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "warehouse." + permission);
    return access.run(actor, "warehouse." + permission, () -> work.apply(actor));
  }

  /** 能力声明：序列号逐件核对的集合相等判据、检出哪几类异常、开箱复核口径、 与「复核不动库存」这条边界，以及未交付的 8.3.3.2。 */
  @GetMapping("/capabilities")
  public RecountCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "inventory.read", a -> recounts.capabilities());
  }

  /** 建复核规则（序列号逐件核对开关 + 高价值件数阈值）。 */
  @PostMapping("/rules")
  public RecountRuleRow createRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody RecountRuleInput input) {
    return call(auth, "inventory.update", a -> recounts.createRule(input, a.userId()));
  }

  /** 复核规则列表（真分页 + 真筛选）。 */
  @GetMapping("/rules")
  public RecountRulePage rules(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID warehouseId,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth, "inventory.read", a -> recounts.rules(warehouseId, status, keyword, page, size));
  }

  /** 规则详情：规则 + 被它复核过的会话数与状态分布。 */
  @GetMapping("/rules/{id}")
  public RecountRuleDetail rule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "inventory.read", a -> recounts.rule(id));
  }

  /** 停用规则。停用后不能复活（口径的历史必须可追）。 */
  @PostMapping("/rules/{id}/retire")
  public RecountRuleRow retireRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody RecountRetireInput input) {
    return call(auth, "inventory.update", a -> recounts.retireRule(id, input, a.userId()));
  }

  /**
   * 开一份复核单：读 8.3.2.1 的<b>已完成</b>拣货任务接手，把订单明细冻结成复核基线。
   *
   * <p>三道校验：出库单在「待复核」、拣货任务已完成、基线至少一行。
   */
  @PostMapping
  public RecountCreated open(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody RecountOpenInput input) {
    return call(auth, "inventory.update", a -> recounts.open(input, a.userId()));
  }

  /** 复核单列表（真分页 + 真筛选 + 排序白名单 + 汇总，汇总不受筛选影响）。 */
  @GetMapping
  public RecountPage page(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID warehouseId,
      @RequestParam(required = false) UUID outboundOrderId,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) UUID reviewerUserId,
      @RequestParam(required = false, defaultValue = "false") boolean openBoxOnly,
      @RequestParam(required = false, defaultValue = "false") boolean exceptionsOnly,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "STARTED_AT") String sort,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "inventory.read",
        a ->
            recounts.page(
                warehouseId,
                outboundOrderId,
                status,
                reviewerUserId,
                openBoxOnly,
                exceptionsOnly,
                keyword,
                sort,
                page,
                size));
  }

  /** 详情：基线行 + 扫描记录 + 异常 + 时间线 + 结论报告。 */
  @GetMapping("/{id}")
  public RecountDetail detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "inventory.read", a -> recounts.detail(id));
  }

  /** 开箱复核（高价值订单）。原因必填。 */
  @PostMapping("/{id}/open-box")
  public RecountCreated openBox(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody RecountOpenBoxInput input) {
    return call(auth, "inventory.update", a -> recounts.openBox(id, input, a.userId()));
  }

  /** 扫一件（条码 / 序列号）：逐件核对的核心动作。 */
  @PostMapping("/{id}/scans")
  public RecountScanRow scan(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody RecountScanInput input) {
    return call(auth, "inventory.update", a -> recounts.scan(id, input, a.userId()));
  }

  /**
   * 结算复核：逐行比订单明细，检出漏件 / 多件 / 序列号不符，给出通过或异常的结论。
   *
   * <p>漏件与多件<b>只能</b>在这里报 —— 扫描过程中「还没扫齐」和「扫完发现少了」 根本区分不出来。
   */
  @PostMapping("/{id}/settle")
  public RecountVerdictReport settle(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "inventory.update", a -> recounts.settle(id, a.userId()));
  }

  /**
   * 完成复核：结算 + 判定。判通过的四个前提（每一行核过、序列号集合相等、 无未处置异常、出库单本来就在待复核）同时在 DB 层拒一遍。
   *
   * <p>通过时把出库单推到「待打包」交给 8.3.3.2，<b>从不碰已发货</b>。
   */
  @PostMapping("/{id}/complete")
  public RecountVerdictReport complete(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "inventory.update", a -> recounts.complete(id, a.userId()));
  }

  /** 异常单转人工处理（蓝图输出原话）。处置意见必填。 */
  @PostMapping("/{id}/escalate")
  public RecountExceptionRow escalate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody RecountEscalateInput input) {
    return call(auth, "inventory.update", a -> recounts.escalate(id, input, a.userId()));
  }

  /** 处置单条异常。已处置的不许再改（事实只能处置一次）。 */
  @PostMapping("/{id}/exceptions/{exceptionId}/resolve")
  public RecountExceptionRow resolveException(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @PathVariable String exceptionId,
      @Valid @RequestBody RecountResolveInput input) {
    return call(
        auth,
        "inventory.update",
        a -> recounts.resolveException(id, exceptionId, input, a.userId()));
  }

  /** 取消复核（<b>不动库存</b>，复核本来就不碰库存）。原因必填。 */
  @PostMapping("/{id}/cancel")
  public RecountCreated cancel(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody RecountCancelInput input) {
    return call(auth, "inventory.update", a -> recounts.cancel(id, input, a.userId()));
  }
}
