package com.starcon.warehouse;

import static com.starcon.warehouse.ReturnDispositionExecutionModels.*;

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
 * 11.2.3.2 转可售/残次/报废 —— warehouse 侧执行端点。
 *
 * <p>蓝图 11-aftersales-and-returns.md：「按质检结论把退货转入对应状态：可再销售转可售库存、
 * 残次转残次库存（可用于清仓）、报废则核销并计入损耗。」输出：「库存状态变更分录；可售量更新；
 * 报废损耗记录。」
 *
 * <p><b>命名空间独立于 {@code /warehouse/inventory/adjustments}（7.4.1.1 调整单）</b>：
 * 调整单是「仓库自己发起的一次库存调整」，本模块是「售后处置决定的执行」——
 * 处置有质检结论、有审批、报废不可逆，把它塞进通用调整单会让「谁批的、为什么报」被稀释成一行备注。
 *
 * <p><b>权限码</b>：读 {@code warehouse.inventory.read}，执行 {@code warehouse.inventory.update}。
 * 执行入口本身**不判审批**（审批在 trade 侧的处置单上，11.2.3.2 的处置决定单）——
 * 但 trade 侧只会在处置单 **APPROVED** 之后才调到这里，而报废的审批在 trade 侧被
 * 「提交人 ≠ 审批人 + 必填理由 + DB 触发器」钉死。
 *
 * <p>租户<b>不</b>来自任何请求头：先向 identity 确认已认证成员关系，再在一个事务里绑定
 * {@code app.current_tenant}，由 FORCE RLS 在数据库层强制隔离。伪造的 {@code X-Tenant-Id}
 * 头进不来也改不动它。
 */
@RestController
@RequestMapping("/warehouse/inventory/return-dispositions")
public class ReturnDispositionExecutionController {

  private final AccessService access;
  private final ReturnDispositionExecutionService executions;

  public ReturnDispositionExecutionController(
      AccessService access, ReturnDispositionExecutionService executions) {
    this.access = access;
    this.executions = executions;
  }

  /** 统一读入口。 */
  private <T> T call(String bearer, Function<AccessService.Access, T> work) {
    AccessService.Access actor = access.require(bearer, "warehouse.inventory.read");
    return access.run(actor, "warehouse.inventory.read", () -> work.apply(actor));
  }

  /** 写入口（执行）。 */
  private <T> T write(String bearer, Function<AccessService.Access, T> work) {
    AccessService.Access actor = access.require(bearer, "warehouse.inventory.update");
    return access.run(actor, "warehouse.inventory.update", () -> work.apply(actor));
  }

  // ================================================================ 能力声明

  /** 能力与边界：职责句逐块报可用性，未交付的下游逐个报归属模块。 */
  @GetMapping("/capabilities")
  public ReturnDispositionExecutionCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, actor -> executions.capabilities());
  }

  // ================================================================ 处置选项

  /**
   * 处置选项：候选来源库位（待检库存行）+ 候选目标库位（按目标状态的库位类型）。
   *
   * <p>两个候选集是同一个决定的两个维度，必须一起给：来源库位决定「货现在在哪」，
   * 目标库位决定「货要去哪」（可售进 STORAGE 位、残次进 DEFECTIVE 位 —— 权威函数
   * {@code location_allocation_required_type}）。
   */
  @GetMapping("/options")
  public ReturnDispositionExecutionOptions options(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID skuId,
      @RequestParam(required = false) String targetState) {
    return call(auth, actor -> executions.options(skuId, targetState));
  }

  // ================================================================ 执行

  /**
   * 执行一次库存状态变更（转可售 / 转残次 / 报废核销）。
   *
   * <p><b>幂等</b>：同一张处置单重复提交返回既有回执，**不会把库存扣两次**。
   *
   * <p><b>本端点不提供任何「反执行」形状</b>：报废不可逆，已执行的分录不删不改。
   */
  @PostMapping("/execute")
  public ReturnDispositionExecutionResult execute(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody ReturnDispositionExecuteRequest request) {
    return write(auth, actor -> executions.execute(request, actor.userId()));
  }

  // ================================================================ 执行回执

  /** 执行回执列表（真分页 + 目标状态筛选 + 关键字）。 */
  @GetMapping("/executions")
  public ReturnDispositionExecutionPage executions(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID dispositionId,
      @RequestParam(required = false) String targetState,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    return call(
        auth,
        actor ->
            executions.executions(
                dispositionId,
                targetState,
                keyword,
                page == null ? 0 : page,
                size == null ? 20 : size));
  }

  /** 单条执行回执。 */
  @GetMapping("/executions/{id}")
  public ReturnDispositionExecutionRow execution(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, actor -> executions.execution(id));
  }
}
