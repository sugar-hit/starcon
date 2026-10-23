package com.starcon.trade;

import static com.starcon.trade.ReturnInventoryDispositionModels.*;

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
 * 11.2.3.2 转可售/残次/报废。
 *
 * <p>蓝图 11-aftersales-and-returns.md：「按质检结论把退货转入对应状态：可再销售转可售库存、
 * 残次转残次库存（可用于清仓）、报废则核销并计入损耗。」输入：「质检结论；处置决定；
 * {@code expected_version}。」输出：「库存状态变更分录；可售量更新；报废损耗记录。」
 * 以及红线：「<b>报废是不可逆的</b>，需审批与原因，不能由客服随手处理。」
 *
 * <p><b>命名空间独立于 {@code /trade/return-quality}（11.2.3.1）</b>：本模块的产物是
 * <b>另一种单据</b>（处置决定 + 审批 + 执行回执 + 报废损耗），不是质检单的另一个视图。
 *
 * <p><b>权限码</b>：读 {@code trade.aftersales.read}，写 {@code trade.aftersales.write}，
 * <b>审批 {@code trade.aftersales.approve}</b>（独立权限码 —— 报废不可逆，不能由客服随手处理；
 * 批准还额外受「提交人 ≠ 审批人」与「审批意见必填」两道 DB 闸门约束）。
 *
 * <p>租户<b>不</b>来自任何请求头：先向 identity 确认已认证成员关系，再在一个事务里绑定
 * {@code app.current_tenant}，由 FORCE RLS 在数据库层强制隔离。伪造的 {@code X-Tenant-Id}
 * 头进不来也改不动它。跨服务调 warehouse 时转发的是<b>调用方自己的</b> bearer，
 * 租户隔离在两边各有一道。
 */
@RestController
@RequestMapping("/trade/return-dispositions")
public class ReturnDispositionController {

  private final AccessService access;
  private final ReturnDispositionService dispositions;

  public ReturnDispositionController(AccessService access, ReturnDispositionService dispositions) {
    this.access = access;
    this.dispositions = dispositions;
  }

  /** 统一读入口。 */
  private <T> T call(String bearer, Function<AccessService.Access, T> work) {
    AccessService.Access actor = access.require(bearer, "trade.aftersales.read");
    return access.run(actor, "trade.aftersales.read", () -> work.apply(actor));
  }

  /** 写入口。 */
  private <T> T write(String bearer, Function<AccessService.Access, T> work) {
    AccessService.Access actor = access.require(bearer, "trade.aftersales.write");
    return access.run(actor, "trade.aftersales.write", () -> work.apply(actor));
  }

  /** 审批入口（独立权限码）。 */
  private <T> T approveWith(String bearer, Function<AccessService.Access, T> work) {
    AccessService.Access actor = access.require(bearer, "trade.aftersales.approve");
    return access.run(actor, "trade.aftersales.approve", () -> work.apply(actor));
  }

  // ================================================================ 能力声明

  /** 能力与边界声明：职责句逐块报可用性，下游逐个报 delivered 与归属模块。 */
  @GetMapping("/capabilities")
  public ReturnInventoryDispositionCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, actor -> dispositions.capabilities());
  }

  // ================================================================ 待处置指令

  /**
   * 待处置的质量状态指令（11.2.3.1 产出，本模块消费）。
   *
   * <p>这是本模块的第一屏：<b>有多少件货判完了还没处置</b>（不是「一共判了多少」）。
   */
  @GetMapping("/instructions")
  public ReturnInventoryDispositionInstructionPage instructions(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String skuCode,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    return call(
        auth,
        actor ->
            dispositions.pendingInstructions(
                skuCode, page == null ? 0 : page, size == null ? 20 : size));
  }

  // ================================================================ 处置选项

  /**
   * 处置选项：候选来源库位（待检库存行）+ 候选目标库位。
   *
   * <p>数据来自 warehouse（库存事实的所在地），本服务只透传。
   */
  @GetMapping("/options")
  public ReturnInventoryDispositionOptions options(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID skuId,
      @RequestParam(required = false) String targetState) {
    return call(auth, actor -> dispositions.options(skuId, targetState, auth));
  }

  // ================================================================ 处置单

  /** 处置单列表（真分页 + 多维筛选 + 跨全表汇总）。 */
  @GetMapping
  public ReturnInventoryDispositionPage list(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String targetState,
      @RequestParam(required = false) String skuCode,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    return call(
        auth,
        actor ->
            dispositions.listDispositions(
                status, targetState, skuCode, keyword, page == null ? 0 : page, size == null ? 20 : size));
  }

  /** 处置单详情（头 + 留痕 + 损耗记录 + 当前可执行动作 + 上游指令）。 */
  @GetMapping("/{id}")
  public ReturnInventoryDispositionDetail detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, actor -> dispositions.detail(id));
  }

  /**
   * 建处置决定单（草稿）。
   *
   * <p><b>目标状态与数量不由调用方决定</b>：它们从 11.2.3.1 的质检指令读，
   * 处置单不能改判质检结论。
   */
  @PostMapping
  public ReturnInventoryDispositionDetail create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody ReturnInventoryDispositionCreate request) {
    return write(auth, actor -> dispositions.create(request, actor.userId(), auth));
  }

  /** 改草稿要素（只有 DRAFT 可改，必须带 expectedVersion）。 */
  @PutMapping("/{id}")
  public ReturnInventoryDispositionDetail update(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody ReturnInventoryDispositionUpdate request) {
    return write(auth, actor -> dispositions.update(id, request, actor.userId(), auth));
  }

  /** 提交待审。 */
  @PostMapping("/{id}/submit")
  public ReturnInventoryDispositionDetail submit(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody ReturnInventoryDispositionSubmit request) {
    return write(auth, actor -> dispositions.submit(id, request, actor.userId()));
  }

  /** 批准（独立权限码 + 必填意见 + 提交人 ≠ 审批人）。 */
  @PostMapping("/{id}/approve")
  public ReturnInventoryDispositionDetail approve(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody ReturnInventoryDispositionDecision request) {
    return approveWith(auth, actor -> dispositions.approve(id, request, actor.userId()));
  }

  /** 驳回（意见必填）。 */
  @PostMapping("/{id}/reject")
  public ReturnInventoryDispositionDetail reject(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody ReturnInventoryDispositionDecision request) {
    return approveWith(auth, actor -> dispositions.reject(id, request, actor.userId()));
  }

  /** 撤销（还没动过库存才能撤）。 */
  @PostMapping("/{id}/cancel")
  public ReturnInventoryDispositionDetail cancel(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody ReturnInventoryDispositionCancel request) {
    return write(auth, actor -> dispositions.cancel(id, request, actor.userId()));
  }

  /**
   * 执行：真正改库存的那一步。
   *
   * <p>执行经**授权 HTTP**送到 warehouse（库存事实的写入所有权在 warehouse）。
   * warehouse 不可达或拒绝时，处置单落「执行失败」并可原样重试，<b>绝不标成已执行</b>。
   *
   * <p>⚠️ <b>执行失败也返回 200</b>（而不是 5xx）：失败这件事本身要被记录下来，
   * 抛错会让事务把「执行失败」这条记录一起回滚掉 —— 结果就是货没动、账没动、
   * 单子还停在「已批待执行」，谁也看不出刚才发生过什么。
   * 调用方从 {@code disposition.status = 'EXECUTION_FAILED'} 与
   * {@code disposition.executionFailureReason} 里读失败原因。
   */
  @PostMapping("/{id}/execute")
  public ReturnInventoryDispositionDetail execute(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody ReturnInventoryDispositionExecute request) {
    return write(auth, actor -> dispositions.execute(id, request, actor.userId(), auth));
  }
}
