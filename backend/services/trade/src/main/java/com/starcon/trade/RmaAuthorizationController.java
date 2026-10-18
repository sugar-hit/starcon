package com.starcon.trade;

import static com.starcon.trade.RmaAuthorizationModels.*;

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
 * 11.2.1.1 退货授权单。
 *
 * <p>蓝图 11-aftersales-and-returns.md：「审批退货申请：确认可退数量（原订单行已退/已发数量约束）、
 * 退货地址、买家回寄物流、目标处理方式；部分退货需说明理由。」
 * 输出：「退货授权单（授权/部分授权/拒绝）+ 拒绝理由。」
 * 加粗句：「<b>授权不等于已收到</b>——授权后货可能在路上或根本没寄回，需要超时未寄自动关闭。」
 *
 * <p><b>命名空间独立于 {@code /trade/aftersales}</b>：本模块的产物是<b>另一张单据</b>
 * （行级授权数量 + 退货地址 + 回寄物流 + 寄回时限），不是售后单的另一个视图。
 * 塞进 {@code /trade/aftersales/cases/{uuid}/returns} 会让人以为它是 11.1.1.2 详情的一个子资源，
 * 而 11.2.1.1 的下游是 11.2.1.2 退货指令，不是售后详情页。
 *
 * <p><b>权限码沿用</b> {@code trade.aftersales.read} / {@code trade.aftersales.write}：
 * 本模块是 11.2.1 锚点下的退货审批动作，与售后单同一业务面。一个「能看售后单但不能批退货」
 * 的权限位在售后场景里没有任何业务含义，而退货授权带着<b>收货地址</b>与<b>买家运单号</b>。
 *
 * <p><b>刻意不提供</b>：{@code DELETE /{id}}、以及任何「登记已收到」的入口 ——
 * 登记退回实物属 11.2.2.1（未交付）。蓝图加粗句说的正是这件事：
 * 授权不等于已收到，货可能根本没寄回，所以本模块只有<b>寄回时限倒计时</b>与<b>超时自动关闭</b>。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} / {@link #write} 先向 identity 确认已认证成员关系，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 FORCE RLS 在数据库层强制隔离。
 * 本模块的端点大多是**按 id 直达**的 URL —— 拿别的租户的授权单 ID 来读，
 * 得到的是与「不存在」**完全相同**的 NOT_FOUND（不告诉调用方「这个 ID 存在但不属于你」）。
 */
@RestController
@RequestMapping("/trade/rma-authorizations")
public class RmaAuthorizationController {

  private static final String READ = "aftersales.read";
  private static final String WRITE = "aftersales.write";

  private final AccessService access;
  private final RmaAuthorizationService rma;

  public RmaAuthorizationController(AccessService access, RmaAuthorizationService rma) {
    this.access = access;
    this.rma = rma;
  }

  /** 统一读入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
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
   * 能力声明：职责句的五个区块逐块报可用性，下游逐个报 Delivered=false 与归属模块号、
   * 本模块**刻意不做**的事、以及「授权不等于已收到」这条口径。
   *
   * <p>路径刻意是 {@code capabilities}（租户级静态事实），不带 id 段。
   */
  @GetMapping("/capabilities")
  public RmaCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, actor -> rma.capabilities());
  }

  // ================================================================ 可退量校验

  /**
   * 原订单行可退量校验（职责句输入句）。
   *
   * <p>三个来源全部服务端实时查：订购量（V99）、已发量（V119）、其他在途授权累计（本模块）。
   * <b>已退数量恒为 null 并标注 11.2.2.2 未交付</b>，不填 0 冒充「没退过」。
   */
  @GetMapping("/returnable-quantities")
  public RmaReturnableReport returnableQuantities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam UUID caseId) {
    return call(auth, actor -> rma.returnableQuantities(caseId));
  }

  // ================================================================ 列表 / 详情

  /** 列表：真分页 + 多维筛选 + 排序白名单 + 全量汇总。 */
  @GetMapping
  public RmaPage list(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String decision,
      @RequestParam(required = false) String disposition,
      @RequestParam(required = false) UUID caseId,
      @RequestParam(required = false) Boolean overdueOnly,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String sort) {
    return call(
        auth,
        actor ->
            rma.list(
                new RmaListQuery(
                    page, size, status, decision, disposition, caseId, overdueOnly, keyword, sort)));
  }

  /** 单张授权单全景。**不存在的 id 与不属于本租户的 id 返回同一条错误** —— 见类注释。 */
  @GetMapping("/{id}")
  public RmaDetail detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, actor -> rma.detail(id));
  }

  // ================================================================ 写

  /**
   * 登记退货授权单（草稿）。
   *
   * <p><b>建单不是审批</b>：行只带申请量，授权量恒为 0。审批是 {@code /decide} 的事 ——
   * 两者合成一个动作会让「谁批的、什么时候批的」在留痕里消失。
   */
  @PostMapping
  public RmaDetail create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody RmaCreate request) {
    return write(
        auth,
        // ⚠️ createdBy 走 actor 而不是请求体：请求体里没有这个字段，
        //   身份只认已认证的成员关系。
        actor -> rma.create(request, actor.userId()));
  }

  /**
   * 改草稿要素（目标处理方式 / 退货地址 / 逐行说明）。
   *
   * <p><b>只允许 DRAFT 改</b>：授权已发出后地址就是给买家的承诺，买家可能已经照着旧地址寄了。
   * 乐观锁 {@code expectedVersion} 必填。
   */
  @PutMapping("/{id}")
  public RmaDetail update(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody RmaUpdate request) {
    return write(auth, actor -> rma.update(id, request));
  }

  /**
   * 审批：授权 / 部分授权 / 拒绝。
   *
   * <p>可退量在<b>审批这一刻重算</b>，不信页面传来的数；DB 触发器还会再算一遍。
   * 部分授权与拒绝必须说明理由；授权与部分授权必须给出买家寄回时限。
   */
  @PostMapping("/{id}/decide")
  public RmaDetail decide(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody RmaDecide request) {
    return write(auth, actor -> rma.decide(id, request, actor.userId()));
  }

  /** 登记买家回寄物流（承运商 / 运单号 / 寄出时刻三件套同进同出）。 */
  @PostMapping("/{id}/return-logistics")
  public RmaDetail recordReturnLogistics(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody RmaReturnLogistics request) {
    return write(auth, actor -> rma.recordReturnLogistics(id, request, actor.userId()));
  }

  /** 人工撤销：货还没回来，操作员发现批错了。可退量立刻还给订单行。 */
  @PostMapping("/{id}/cancel")
  public RmaDetail cancel(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody RmaCancel request) {
    return write(auth, actor -> rma.cancel(id, request, actor.userId()));
  }

  /**
   * 超时未寄自动关闭（蓝图加粗句的直接落点）。
   *
   * <p>判据只有一条：{@code return_by_at < 现在} 且状态仍在途。
   * <b>已登记回寄物流的单也照常关闭</b> —— 登记只说明买家报了个运单号，
   * 货到没到库是 11.2.2.1 的事。
   *
   * <p>这是显式端点而不是后台定时器：定时器要另起 worker 进程与调度配置，那是另一个模块的事；
   * 语义与判据在这一处定死，调度侧以后定时调它即可。
   */
  @PostMapping("/expire-overdue")
  public RmaExpireResult expireOverdue(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return write(auth, actor -> rma.expireOverdue());
  }
}
