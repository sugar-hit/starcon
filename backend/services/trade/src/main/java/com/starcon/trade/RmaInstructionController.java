package com.starcon.trade;

import static com.starcon.trade.RmaInstructionModels.*;

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
 * 11.2.1.2 退货指令下发。
 *
 * <p>蓝图 11-aftersales-and-returns.md：「向买家发出退货指引（地址、要求、时限），
 * 生成退货标签，跟踪买家回寄物流。」
 * 输出：「退货指令 + 标签文件 + 回寄物流轨迹跟踪。」
 * 加粗句：「<b>多数平台退货由买家寄回</b>，本系统只负责指引与跟踪，<b>不控制实物</b>。」
 *
 * <p><b>命名空间独立于 {@code /trade/rma-authorizations}</b>：本模块的产物是
 * <b>另一张单据</b>（指引正文 + 标签 + 轨迹），不是退货授权单的另一个视图。
 * 本模块是 11.2.1.1 的<b>下游</b>——授权单批准之后才轮得到这里。
 *
 * <p><b>权限码沿用</b> {@code trade.aftersales.read} / {@code trade.aftersales.write}：
 * 本模块是 11.2.1 锚点下的退货侧动作，与售后单同一业务面。一个「能看退货授权但不能发退货指引」
 * 的权限位在售后场景里没有任何业务含义，而指引带着<b>退货地址</b>与<b>买家邮箱</b>。
 *
 * <p><b>刻意不提供</b>：{@code DELETE /{id}}、以及任何「登记已收到」的入口 ——
 * 登记退回实物属 11.2.2.1（未交付）。蓝图加粗句说的正是这件事：
 * 指引已下发不等于货已到手，本模块只有<b>寄回时限倒计时</b>与<b>时限到期自动失效</b>。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} / {@link #write} 先向 identity 确认已认证成员关系，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 FORCE RLS 在数据库层强制隔离。
 * 本模块的端点大多是**按 id 直达**的 URL —— 拿别的租户的指令 ID 来读，
 * 得到的是与「不存在」**完全相同**的 NOT_FOUND（不告诉调用方「这个 ID 存在但不属于你」）。
 */
@RestController
@RequestMapping("/trade/rma-instructions")
public class RmaInstructionController {

  private static final String READ = "aftersales.read";
  private static final String WRITE = "aftersales.write";

  private final AccessService access;
  private final RmaInstructionService instructions;

  public RmaInstructionController(AccessService access, RmaInstructionService instructions) {
    this.access = access;
    this.instructions = instructions;
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
   * 能力声明：职责句的三个动词逐块报可用性，下游逐个报 Delivered=false 与归属模块号、
   * 本模块**刻意不做**的事、以及「已下发 ≠ 已寄回」这条口径。
   *
   * <p>路径刻意是 {@code capabilities}（租户级静态事实），不带 id 段。
   */
  @GetMapping("/capabilities")
  public RmaInstructionCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, actor -> instructions.capabilities());
  }

  // ================================================================ 列表 / 详情

  /** 列表：真分页 + 多维筛选（含按承运商 / 运单号反查）+ 排序白名单 + 全量汇总。 */
  @GetMapping
  public RmaInstructionPage list(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String channel,
      @RequestParam(required = false) UUID rmaId,
      @RequestParam(required = false) Boolean overdueOnly,
      @RequestParam(required = false) String carrier,
      @RequestParam(required = false) String trackingNo,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String sort) {
    return call(
        auth,
        actor ->
            instructions.list(
                new RmaInstructionListQuery(
                    page, size, status, channel, rmaId, overdueOnly, carrier, trackingNo, keyword,
                    sort)));
  }

  /** 单张退货指令全景。**不存在的 id 与不属于本租户的 id 返回同一条错误** —— 见类注释。 */
  @GetMapping("/{id}")
  public RmaInstructionDetail detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, actor -> instructions.detail(id));
  }

  /** 回寄物流轨迹时间线（追加式事件 + 按运单号分组的当前状态）。 */
  @GetMapping("/{id}/tracking")
  public RmaTrackingTimeline tracking(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, actor -> instructions.tracking(id));
  }

  // ================================================================ 写：建单 / 改草稿

  /**
   * 登记退货指引草稿（挂在已授权 / 部分授权的退货授权单下）。
   *
   * <p><b>建单不是下发</b>：草稿还没出过门。真正送达是 {@code /issue} 的事 ——
   * 两者合成一个动作会让「谁下发的、什么时候下发的」在留痕里消失，
   * 而买家投诉「你们根本没通知过我」时这就是唯一的证据。
   */
  @PostMapping
  public RmaInstructionDetail create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody RmaInstructionCreate request) {
    return write(
        auth,
        // ⚠️ createdBy 走 actor 而不是请求体：请求体里没有这个字段，身份只认已认证的成员关系。
        actor -> instructions.create(request, actor.userId()));
  }

  /**
   * 改草稿要素（收件方 / 渠道 / 包装要求 / 逐行说明）。
   *
   * <p><b>只允许 DRAFT 改</b>：指引下发后内容即冻结（DB 触发器）—— 买家可能已经照着旧地址寄了。
   * 乐观锁 {@code expectedVersion} 必填。
   */
  @PutMapping("/{id}")
  public RmaInstructionDetail update(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody RmaInstructionUpdate request) {
    return write(auth, actor -> instructions.update(id, request));
  }

  // ================================================================ 写：状态流转

  /** 下发指引：内容在这一刻冻结。 */
  @PostMapping("/{id}/issue")
  public RmaInstructionDetail issue(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody RmaInstructionIssue request) {
    return write(auth, actor -> instructions.issue(id, request, actor.userId()));
  }

  /** 登记买家已确认收到指引（**主动回执**，不是我们推测他看到了）。 */
  @PostMapping("/{id}/acknowledge")
  public RmaInstructionDetail acknowledge(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody RmaInstructionAcknowledge request) {
    return write(auth, actor -> instructions.acknowledge(id, request, actor.userId()));
  }

  /** 人工撤销指引（授权被撤销 / 买家改主意了）。 */
  @PostMapping("/{id}/cancel")
  public RmaInstructionDetail cancel(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody RmaInstructionCancel request) {
    return write(auth, actor -> instructions.cancel(id, request, actor.userId()));
  }

  /**
   * 寄回时限到期自动失效。
   *
   * <p>判据只有一条：{@code return_by_at < 现在} 且状态仍在途。
   * <b>刻意不含「有没有轨迹」</b>：时限到了而货在路上的单子，在承运商口径里仍可能签收成功 ——
   * 实收判定归 11.2.2.1 按收货事件去重做。
   *
   * <p>这是显式端点而不是后台定时器：定时器要另起 worker 进程与调度配置，那是另一个模块的事；
   * 语义与判据在这一处定死，调度侧以后定时调它即可。
   */
  @PostMapping("/expire-overdue")
  public RmaInstructionExpireResult expireOverdue(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return write(auth, actor -> instructions.expireOverdue());
  }

  // ================================================================ 写：退货标签

  /**
   * 生成退货标签（真字节落库 + SHA-256 校验和）。
   *
   * <p>{@code SELF_GENERATED_TEMPLATE} 的内容由服务端按指令真值渲染，
   * 不接受调用方传入 —— 一张内容由前端拼出来的标签，地址少一段城市照样能生成，而货会寄丢。
   */
  @PostMapping("/{id}/label")
  public RmaInstructionDetail generateLabel(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody RmaLabelGenerate request) {
    return write(auth, actor -> instructions.generateLabel(id, request, actor.userId()));
  }

  /** 交付退货标签（贴纸真的到买家手上了 —— 「生成」不等于「交付」）。 */
  @PostMapping("/{id}/label/deliver")
  public RmaInstructionDetail deliverLabel(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody RmaLabelDeliver request) {
    return write(auth, actor -> instructions.deliverLabel(id, request, actor.userId()));
  }

  /** 作废退货标签（承运商取消 / 地址改了重出）。作废后可重新生成一张。 */
  @PostMapping("/{id}/label/void")
  public RmaInstructionDetail voidLabel(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody RmaLabelVoid request) {
    return write(auth, actor -> instructions.voidLabel(id, request, actor.userId()));
  }

  /** 取标签文件内容，并在服务端<b>重算 SHA-256 与库里比对</b>（{@code checksumVerified}）。 */
  @GetMapping("/{id}/label")
  public RmaLabelContent labelContent(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, actor -> instructions.labelContent(id));
  }

  // ================================================================ 写：回寄轨迹

  /**
   * 登记一条回寄物流轨迹事件（<b>追加式</b>，运行角色 UPDATE/DELETE 已在 DB 层收回）。
   *
   * <p>承运商重复推送同一条扫描由 DB 唯一索引去重 —— 重复登记会让「这单退了几件」出现两个答案。
   */
  @PostMapping("/{id}/tracking")
  public RmaInstructionDetail appendTracking(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody RmaTrackingAppend request) {
    return write(auth, actor -> instructions.appendTracking(id, request, actor.userId()));
  }
}
