package com.starcon.trade;

import static com.starcon.trade.ReturnReceiptAllocationModels.*;

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
 * 11.2.2.2 实收分配。
 *
 * <p>蓝图 11-aftersales-and-returns.md：「把实收退货分配到对应的退货授权行与原订单行，
 * 处理实收多于授权（<b>按实际退回</b>）、少收、超期退回等情况。」输出：「分配记录 +
 * 超收/少收说明；<b>订单行已退数量更新</b>。」加粗句：「<b>按收货事件去重分配</b>，
 * 同一批退货不重复记已收数量。」
 *
 * <p><b>命名空间独立于 {@code /trade/return-receipts} 与 {@code /trade/rma-authorizations}</b>：
 * 本模块的产物是<b>另一张单据</b>（一次分配 = 一张分配单），不是那两张单的另一个视图。
 * 本模块是 11.2.2.1 的<b>下游</b> —— 指引下发了不等于货到了，货到了也不等于已分配到订单行。
 *
 * <p><b>权限码沿用</b> {@code trade.aftersales.read} / {@code trade.aftersales.write}：
 * 本模块是 11.2 锚点下的退货侧动作，与售后单同一业务面。
 *
 * <p><b>刻意不提供</b>：
 * <ul>
 *   <li>任何「强制重复分配」入口 —— 蓝图加粗句「按收货事件去重分配，同一批退货不重复记已收数量」，
 *       而已退数量是可退余额的事实来源，多记一次就是资损。重复提交返回**同一张**分配单。</li>
 *   <li>任何「直接改已退数量」入口 —— 运行角色对该表的 UPDATE/DELETE 已在 DB 层收回，
 *       改已退数量只能经撤销分配（且必须写理由）。</li>
 *   <li>任何「把差额标记为已解决」入口 —— 处置结论只有「待人工核实 / 按实际退回认可」，
 *       编一个「已解决」会让财务侧以为这笔钱已经结清了。</li>
 * </ul>
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} / {@link #write} 先向 identity 确认已认证成员关系，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 FORCE RLS 在数据库层强制隔离。
 * 本模块的端点大多是<b>按 id 直达</b>的 URL —— 拿别的租户的分配单 ID 来读，
 * 得到的是与「不存在」<b>完全相同</b>的 NOT_FOUND。
 */
@RestController
@RequestMapping("/trade/receipt-allocations")
public class ReturnReceiptAllocationController {

  private static final String READ = "aftersales.read";
  private static final String WRITE = "aftersales.write";

  private final AccessService access;
  private final ReturnReceiptAllocationService allocations;

  public ReturnReceiptAllocationController(
      AccessService access, ReturnReceiptAllocationService allocations) {
    this.access = access;
    this.allocations = allocations;
  }

  /** 统一读入口：先确认身份与权限（租户<b>不</b>来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, Function<AccessService.Access, T> work) {
    AccessService.Access actor = access.require(bearer, "trade." + READ);
    return access.run(actor, "trade." + READ, () -> work.apply(actor));
  }

  /** 写入口：需要 {@code trade.aftersales.write}。租户来源与读入口完全一致。 */
  private <T> T write(String bearer, Function<AccessService.Access, T> work) {
    AccessService.Access actor = access.require(bearer, "trade." + WRITE);
    return access.run(actor, "trade." + WRITE, () -> work.apply(actor));
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：职责句逐块报可用性，下游逐个报 delivered=false 与归属模块号、
   * 本模块<b>刻意不做</b>的事、以及两条硬规则的口径。
   *
   * <p>路径刻意是 {@code capabilities}（租户级静态事实），不带 id 段。
   */
  @GetMapping("/capabilities")
  public ReturnAllocationCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, actor -> allocations.capabilities());
  }

  // ================================================================ 列表 / 详情

  /** 列表：真分页 + 多维筛选（含仅超期 / 仅差额）+ 排序白名单 + 全量汇总。 */
  @GetMapping
  public ReturnAllocationPage list(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) UUID receiptId,
      @RequestParam(required = false) UUID rmaId,
      @RequestParam(required = false) UUID caseId,
      @RequestParam(required = false) UUID orderId,
      @RequestParam(required = false) Boolean overdueOnly,
      @RequestParam(required = false) Boolean varianceOnly,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String sort) {
    return call(
        auth,
        actor ->
            allocations.list(
                new ReturnAllocationListQuery(
                    page, size, status, receiptId, rmaId, caseId, orderId,
                    overdueOnly, varianceOnly, keyword, sort)));
  }

  /** 单张分配全景。**不存在的 id 与不属于本租户的 id 返回同一条错误** —— 见类注释。 */
  @GetMapping("/{id}")
  public ReturnAllocationDetail detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, actor -> allocations.detail(id));
  }

  /**
   * 某张订单的<b>订单行已退数量</b>（蓝图输出句「订单行已退数量更新」）。
   *
   * <p>这是下游 9 订单的取数面（消费侧未交付）：本模块是已退数量的<b>唯一写入所有者</b>。
   *
   * <p>⚠️ <b>只列出有过退款的订单行</b>：没退过的行在这张表里<b>没有行</b>，
   * 刻意<b>不补 0 行</b> —— 补 0 会让「这行从来没退过」在重复退货面前失去唯一提醒。
   */
  @GetMapping("/order-returns")
  public OrderReturnedQtyPage orderReturns(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam UUID orderId) {
    return call(auth, actor -> allocations.orderReturns(orderId));
  }

  // ================================================================ 写：分配

  /**
   * 按收货事件分配（一次收货事件 = 一张分配单）。
   *
   * <p>★ <b>重复提交同一收货事件返回同一张分配单</b>，不新建、不重复累加已退数量
   * —— 蓝图加粗句「按收货事件去重分配，同一批退货不重复记已收数量」的服务层落点。
   * DB 层另有三道闸门（唯一约束 + 触发器 + 「状态变化才累加」的已退数量触发器）。
   *
   * <p>⚠️ <b>没有 force / allowDuplicate 之类的入参</b>，传输层也不提供。
   * ⚠️ <b>分配数不被截断</b>：授权 1 件、货来了 3 件时 allocatedQty 仍然是 3，
   * 超收 2 件整额落 {@code overReceivedQty} 并产出说明（硬规则②「按实际退回、不静默截断」）。
   *
   * <p>⚠️ 只有<b>已入库</b>的实收单能分配：实收还没入库时货没进待检，
   * 已退数量先走一步会让「待检分录」与「已退数量」变成两份真相。
   */
  @PostMapping
  public ReturnAllocationDetail create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody ReturnAllocationCreate request) {
    return write(
        auth,
        // ⚠️ allocatedBy 走 actor 而不是请求体：身份只认已认证的成员关系。
        actor -> allocations.create(request, actor.userId()));
  }

  // ================================================================ 写：状态流转

  /**
   * 对账：把超收 / 少收 / 超期退回三种情形结转成说明（蓝图输出句「超收/少收说明」）。
   *
   * <p>⚠️ <b>对账不改已退数量</b>：已退数量是累计口径，差额只是结转说明。
   * 把差额算进已退数量会让「已退」这个数同时包含「真实收到的」和「还在核对的」。
   *
   * <p>⚠️ 三种情形<b>各自独立</b>给出处置结论（认可 / 待人工核实），
   * 认可时<b>必须写清依据</b>。处置结论刻意<b>没有「已解决」</b>：
   * 超收多退的钱要追回还是认了是财务决定，编一个「已解决」会让财务以为钱已结清。
   */
  @PostMapping("/{id}/reconcile")
  public ReturnAllocationDetail reconcile(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody ReturnAllocationReconcile request) {
    return write(auth, actor -> allocations.reconcile(id, request, actor.userId()));
  }

  /**
   * 撤销分配（跨行分配选错商品行 / 隔壁包裹串了台）。
   *
   * <p><b>已退数量由 DB 触发器回冲</b>，服务层没有直接写它的代码路径。
   * 已分配量<b>保留</b>：两者之差就是「已退但已不在任何有效分配单上」的部分。
   *
   * <p>⚠️ <b>已对账的单不可撤销</b>，必须说明理由。
   */
  @PostMapping("/{id}/reverse")
  public ReturnAllocationDetail reverse(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody ReturnAllocationReverse request) {
    return write(auth, actor -> allocations.reverse(id, request, actor.userId()));
  }
}
