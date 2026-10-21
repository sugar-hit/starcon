package com.starcon.trade;

import static com.starcon.trade.ServiceTicketModels.*;

import java.time.OffsetDateTime;
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
 * 11.4.2.1 工单管理。
 *
 * <p>蓝图 11-aftersales-and-returns.md：「管理非售后类的客户服务工单：咨询、投诉、催单、
 * 平台政策咨询；支持分配、SLA 计时、转派、关闭与满意度评价。」
 * 输入：「工单创建（人工/来自消息/自动触发）；SLA 规则；分配规则。」
 * 输出：「工单（待处理/处理中/待买家/已关闭）+ SLA 达成统计。」
 *
 * <p><b>命名空间独立于 {@code /trade/aftersales}（11.1.1.x）与
 * {@code /trade/platform-disputes}（11.4.1.1）</b>：本模块的产物是**另一种单据**
 * （一张客服交互工单 + 它的分配过程 + 它的 SLA 计时 + 它的评价），
 * 不是售后单或平台纠纷的另一个视图。
 *
 * <p><b>权限码</b>：读 {@code trade.aftersales.read}；写 {@code trade.aftersales.write}。
 * 没有第三个权限 —— 本模块的每一个动作都只影响工单本身，不牵涉别的模块的审批。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} / {@link #write} 先向 identity
 * 确认已认证成员关系，再在一个事务里绑定 {@code app.current_tenant}，
 * 由 FORCE RLS 在数据库层强制隔离。
 * 本模块的端点大多是<b>按 id 直达</b>的 URL —— 拿别的租户的工单 ID 来读，
 * 得到的是与「不存在」<b>完全相同</b>的错误。
 */
@RestController
@RequestMapping("/trade/service-tickets")
public class ServiceTicketController {

  private static final String READ = "aftersales.read";
  private static final String WRITE = "aftersales.write";

  private final AccessService access;
  private final ServiceTicketService tickets;

  public ServiceTicketController(AccessService access, ServiceTicketService tickets) {
    this.access = access;
    this.tickets = tickets;
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
   * 能力声明（租户级静态事实，不带 id 段）。
   *
   * <p>⚠️ 特别地，这里**如实报出上游缺口**：15 客户与客服的消息 / 工单 / 客户模块
   * **全部未开发** ⇒ 本模块提供**自己的建单入口**，不假装存在一个平台推送接口。
   */
  @GetMapping("/capabilities")
  public ServiceTicketCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, actor -> tickets.capabilities());
  }

  // ================================================================ 工单

  /**
   * 工单列表（真分页 + 多维筛选 + 排序白名单 + 跨全表汇总 + SLA 达成统计）。
   *
   * <p>⚠️ 响应里的 {@code replySlaState} / {@code resolveSlaState} 与
   * {@code secondsToReplyDue} 都是**本次读取时刻**算出来的派生态 ——
   * 库里没有会自己过期的「剩余小时数」列。
   */
  @GetMapping
  public ServiceTicketPage list(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String ticketType,
      @RequestParam(required = false) String priority,
      @RequestParam(required = false) String sourceChannel,
      @RequestParam(required = false) String assignedToRef,
      @RequestParam(required = false) String caseId,
      @RequestParam(required = false) String slaState,
      @RequestParam(required = false) Boolean overdueOnly,
      @RequestParam(required = false) Boolean unassignedOnly,
      @RequestParam(required = false) OffsetDateTime from,
      @RequestParam(required = false) OffsetDateTime to,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String sort) {
    return call(
        auth,
        actor ->
            tickets.listTickets(
                new ServiceTicketListQuery(
                    page, size, status, ticketType, priority, sourceChannel, assignedToRef,
                    caseId, slaState, overdueOnly, unassignedOnly, from, to, keyword, sort)));
  }

  /** 单张工单全景（头 + 转派历史 + 事件流 + 评价 + SLA 快照 + 当前可执行动作）。 */
  @GetMapping("/{id}")
  public ServiceTicketDetail detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, actor -> tickets.ticketDetail(id));
  }

  /**
   * 创建工单。
   *
   * <p>⚠️ 必须给 {@code caseId}：工单永远挂在一张售后单上（预留表 {@code case_id NOT NULL}）。
   * ⚠️ 重复转**不是静默忽略**，而是报错并**附上已有工单号**。
   * ⚠️ SLA 档没配时**明确报错**（本服务不替租户编一个默认小时数）。
   */
  @PostMapping
  public ServiceTicketDetail create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody ServiceTicketCreate request) {
    return write(auth, actor -> tickets.createTicket(request, actor.userId()));
  }

  // ================================================================ 分配 / 转派

  /** 人工派单：指定一个责任人（必须在名册里）。 */
  @PostMapping("/{id}/assign")
  public ServiceTicketDetail assign(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody ServiceTicketAssign request) {
    return write(auth, actor -> tickets.assignTicket(id, request, actor.userId()));
  }

  /** 按名册**加权最少在办**自动派单（分配规则）。名册为空时如实报「没人可派」。 */
  @PostMapping("/{id}/auto-assign")
  public ServiceTicketDetail autoAssign(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody ServiceTicketAutoAssign request) {
    return write(auth, actor -> tickets.autoAssignTicket(id, request, actor.userId()));
  }

  /** 转派给另一个人。必须写理由。 */
  @PostMapping("/{id}/transfer")
  public ServiceTicketDetail transfer(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody ServiceTicketTransfer request) {
    return write(auth, actor -> tickets.transferTicket(id, request, actor.userId()));
  }

  /** 认领：只能认领**当前无责任人**的工单。 */
  @PostMapping("/{id}/claim")
  public ServiceTicketDetail claim(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody ServiceTicketClaim request) {
    return write(auth, actor -> tickets.claimTicket(id, request, actor.userId()));
  }

  /** 释放责任人：把单放回待处理池。必须写理由。 */
  @PostMapping("/{id}/release")
  public ServiceTicketDetail release(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody ServiceTicketRelease request) {
    return write(auth, actor -> tickets.releaseTicket(id, request, actor.userId()));
  }

  // ================================================================ 处理与关闭

  /** 记录首次回复（回复时限的完成时刻，只能记一次）。 */
  @PostMapping("/{id}/first-reply")
  public ServiceTicketDetail firstReply(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody ServiceTicketFirstReply request) {
    return write(auth, actor -> tickets.recordFirstReply(id, request, actor.userId()));
  }

  /** 状态流转（待处理 → 处理中 ⇄ 待买家）。终态不走这里 —— 关闭必须写原因。 */
  @PostMapping("/{id}/status")
  public ServiceTicketDetail changeStatus(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody ServiceTicketStatusChange request) {
    return write(auth, actor -> tickets.changeStatus(id, request, actor.userId()));
  }

  /**
   * 关闭工单。
   *
   * <p>⚠️ 必须写关闭原因：「关闭」与「办完」是两件事 ——
   * 「买家未回应」「不再跟进」也落终态，但落的是 CANCELLED 而不是 RESOLVED。
   * 关闭那一刻会把 SLA 达成**冻结**成快照。
   */
  @PostMapping("/{id}/close")
  public ServiceTicketDetail close(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody ServiceTicketClose request) {
    return write(auth, actor -> tickets.closeTicket(id, request, actor.userId()));
  }

  /** 满意度评价（1~5 分）。只对**已关闭**的工单开放，一张工单一条。 */
  @PostMapping("/{id}/satisfaction")
  public ServiceTicketDetail satisfaction(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody ServiceTicketSatisfactionCreate request) {
    return write(auth, actor -> tickets.recordSatisfaction(id, request, actor.userId()));
  }

  // ================================================================ 升级

  /**
   * 升级：咨询 → 投诉（新建售后单）/ 投诉 → 纠纷（新建平台纠纷）。
   *
   * <p>⚠️ 两条通道都落在**同服务同库**的真实表上，并由真外键钉住。
   * 上下文（工单号 / 售后单号 / 主题 / 诉求）在升级物里原文保留。
   */
  @PostMapping("/{id}/escalate")
  public ServiceTicketDetail escalate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody ServiceTicketEscalate request) {
    return write(auth, actor -> tickets.escalateTicket(id, request, actor.userId()));
  }

  // ================================================================ SLA 统计

  /**
   * SLA 达成统计（独立报表口径，按**关闭时间窗**）。
   *
   * <p>⚠️ 只认**关闭时冻结的快照**：达标结论是一个已经发生的事实，
   * 不随规则改档翻来覆去。
   */
  @GetMapping("/sla-stats")
  public ServiceTicketSlaStats slaStats(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) OffsetDateTime from,
      @RequestParam(required = false) OffsetDateTime to) {
    return call(auth, actor -> tickets.slaStatistics(from, to));
  }
}
