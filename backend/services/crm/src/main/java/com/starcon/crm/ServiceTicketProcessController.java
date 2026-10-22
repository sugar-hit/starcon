package com.starcon.crm;

import static com.starcon.crm.ServiceTicketProcessModels.*;

import jakarta.validation.Valid;
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
 * 15.4.1.2 工单处理。
 *
 * <p>命名空间 {@code /crm/service-ticket-process}：浏览器只经 {@code /api/crm} 代理访问；
 * 后端路径按<b>服务</b>命名空间走，与 catalog（/catalog/...）、trade（/trade/...）同一纪律。
 *
 * <p>⚠️ 与同 L3 兄弟 {@code /crm/service-tickets}（15.4.1.1 工单列表）<b>刻意不共用命名空间</b>：
 * 那个回答的是「<b>有哪些工单、还剩多少营业时间、归谁跟</b>」，
 * 本模块回答的是「<b>我现在怎么处理这一张</b>」——
 * 回复 / 备注 / 状态流转 / 关联单据 / <b>解决确认</b> / 满意度回收 / <b>SLA 暂停恢复</b>。
 *
 * <p>🔴 <b>关闭 ≠ 解决</b>：{@code POST /tickets/{id}/close} 与
 * {@code POST /tickets/{id}/resolutions} 是<b>两个端点</b>，响应里
 * 「已关闭」与「已确认解决」是<b>两个字段</b> —— 工单可以已关闭而没解决。
 *
 * <p>租户<b>不</b>来自任何请求头：先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 * 写上游 trade 时转发的是<b>调用方自己的</b> bearer —— 租户由上游按已认证成员关系解析，
 * 租户隔离因此是<b>两道</b>（crm 侧 RLS + trade 侧 RLS）。
 *
 * <p>🔴 <b>权限码</b>用 {@code crm.service_ticket.read} / {@code crm.service_ticket.update}
 * （与兄弟模块 15.4.1.1 同一对）：处理工单的人本来就是能看到工单列表的那批人，
 * <b>identity 服务零改动</b>（权限码是角色上自由组合的字符串，不是固定词表）。
 */
@RestController
@RequestMapping("/crm/service-ticket-process")
public class ServiceTicketProcessController {

  private final AccessService access;
  private final ServiceTicketProcessService tickets;

  public ServiceTicketProcessController(AccessService access, ServiceTicketProcessService tickets) {
    this.access = access;
    this.tickets = tickets;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "crm.service_ticket." + permission);
    return access.run(actor, "crm.service_ticket." + permission, () -> work.apply(actor));
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：前端在渲染前就知道哪些能力有、哪些没有，而不是靠试。
   *
   * <p>🔴 如实报出缺口：把处理结果<b>推给</b>上游售后单 / 订单这件事
   * {@code available=false} + ownerModule=11.1.1.1 ——
   * <b>不指向</b>任何不存在的入口。
   */
  @GetMapping("/capabilities")
  public CrmServiceTicketProcessCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> tickets.capabilities());
  }

  // ================================================================ 处理队列

  /**
   * 处理队列（真分页 + 真筛选 + <b>暂停感知</b>的 SLA + 解决确认状态）。
   *
   * <p>⚠️ {@code slaState} / {@code overdueOnly} / {@code pausedOnly} /
   * {@code resolutionState} 是<b>本服务</b>的判定，<b>不能</b>原样下推上游 ——
   * 上游没有「暂停」这个概念，也没有「是否已确认解决」这个概念。
   */
  @GetMapping("/tickets")
  public CrmServiceTicketProcessQueuePage queue(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String ticketType,
      @RequestParam(required = false) String priority,
      @RequestParam(required = false) String sourceChannel,
      @RequestParam(required = false) String assignedToRef,
      @RequestParam(required = false) UUID caseId,
      @RequestParam(required = false) String slaState,
      @RequestParam(required = false) Boolean overdueOnly,
      @RequestParam(required = false) Boolean pausedOnly,
      @RequestParam(required = false) String resolutionState,
      @RequestParam(required = false) Boolean unassignedOnly,
      @RequestParam(required = false) OffsetDateTime from,
      @RequestParam(required = false) OffsetDateTime to,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String sort) {
    return call(
        auth,
        "read",
        actor ->
            tickets.queue(
                new CrmServiceTicketProcessQueueQuery(
                    page, size, status, ticketType, priority, sourceChannel, assignedToRef, caseId,
                    slaState, overdueOnly, pausedOnly, resolutionState, unassignedOnly, from, to,
                    keyword, sort),
                auth));
  }

  /** 单张工单的处理视图（SLA + 暂停状态 + 解决确认状态 + 当前可执行动作）。 */
  @GetMapping("/tickets/{id}")
  public CrmServiceTicketProcessTicket ticket(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "read", actor -> tickets.ticket(id, auth));
  }

  // ================================================================ 处理记录

  /** 备注 / 回复列表（真分页）。 */
  @GetMapping("/tickets/{id}/notes")
  public CrmServiceTicketProcessNotePage notes(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    return call(auth, "read", actor -> tickets.notes(id, page, size));
  }

  /** 处理记录（动作时间线，真分页）。 */
  @GetMapping("/tickets/{id}/actions")
  public CrmServiceTicketProcessActionPage actions(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    return call(auth, "read", actor -> tickets.actions(id, page, size));
  }

  // ================================================================ 关联单据

  /** 关联单据列表（真分页 + 上游当前事实现取）。 */
  @GetMapping("/tickets/{id}/links")
  public CrmServiceTicketProcessLinkPage links(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    return call(auth, "read", actor -> tickets.links(id, page, size, auth));
  }

  /**
   * 关联订单或售后单。
   *
   * <p>⚠️ 服务端会**去上游核实**：查不到的订单号 / 售后单号一律拒绝关联。
   */
  @PostMapping("/tickets/{id}/links")
  public CrmServiceTicketProcessOutcome link(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody CrmServiceTicketProcessLinkInput input) {
    return call(
        auth, "update", actor -> tickets.link(id, input, actor.userId(), auth));
  }

  // ================================================================ 解决确认

  /** 解决确认列表（真分页）。 */
  @GetMapping("/tickets/{id}/resolutions")
  public CrmServiceTicketProcessResolutionPage resolutions(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    return call(auth, "read", actor -> tickets.resolutions(id, page, size));
  }

  /**
   * 🔴 解决确认（实际结果）。
   *
   * <p>⚠️ <b>要求工单已关闭</b>：先关后确认，顺序本身就是纪律的落点。
   */
  @PostMapping("/tickets/{id}/resolutions")
  public CrmServiceTicketProcessOutcome confirmResolution(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody CrmServiceTicketProcessResolutionInput input) {
    return call(
        auth, "update", actor -> tickets.confirmResolution(id, input, actor.userId(), auth));
  }

  /** 撤销解决确认（写错了要能改 —— 假的「退款已到账」比没有更糟）。 */
  @PostMapping("/tickets/{id}/resolutions/{resolutionId}/revoke")
  public CrmServiceTicketProcessOutcome revokeResolution(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @PathVariable UUID resolutionId) {
    return call(auth, "update", actor -> tickets.revokeResolution(id, resolutionId, actor.userId(), auth));
  }

  // ================================================================ 处理动作

  /** 回复（对买家）。首次回复会同时登记到上游（回复时限的完成时刻）。 */
  @PostMapping("/tickets/{id}/replies")
  public CrmServiceTicketProcessOutcome reply(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody CrmServiceTicketProcessReplyInput input) {
    return call(auth, "update", actor -> tickets.reply(id, input, actor.userId(), auth));
  }

  /** 内部备注（只追加）。 */
  @PostMapping("/tickets/{id}/notes")
  public CrmServiceTicketProcessOutcome note(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody CrmServiceTicketProcessNoteInput input) {
    return call(auth, "update", actor -> tickets.note(id, input, actor.userId(), auth));
  }

  /** 状态流转（待处理 → 处理中 ⇄ 待买家）。终态不走这里。 */
  @PostMapping("/tickets/{id}/status")
  public CrmServiceTicketProcessOutcome changeStatus(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody CrmServiceTicketProcessStatusInput input) {
    return call(auth, "update", actor -> tickets.changeStatus(id, input, actor.userId(), auth));
  }

  /**
   * 🔴 关闭工单（落终态）。
   *
   * <p>⚠️ <b>关闭不等于解决</b>：这里只让工单不再跟了；
   * 「实际结果到了没有」由**解决确认**回答（另一个端点、另一张表）。
   */
  @PostMapping("/tickets/{id}/close")
  public CrmServiceTicketProcessOutcome close(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody CrmServiceTicketProcessCloseInput input) {
    return call(auth, "update", actor -> tickets.close(id, input, actor.userId(), auth));
  }

  /** 满意度回收（1~5 分）。只转发上游，本服务不另存一份。 */
  @PostMapping("/tickets/{id}/satisfaction")
  public CrmServiceTicketProcessOutcome satisfaction(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody CrmServiceTicketProcessSatisfactionInput input) {
    return call(auth, "update", actor -> tickets.satisfaction(id, input, actor.userId(), auth));
  }

  /** 🔴 SLA 暂停（沿营业时间轴）。 */
  @PostMapping("/tickets/{id}/sla/pause")
  public CrmServiceTicketProcessOutcome pauseSla(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody CrmServiceTicketProcessSlaPauseInput input) {
    return call(auth, "update", actor -> tickets.pauseSla(id, input, actor.userId(), auth));
  }

  /** 🔴 SLA 恢复（沿营业时间轴）。 */
  @PostMapping("/tickets/{id}/sla/resume")
  public CrmServiceTicketProcessOutcome resumeSla(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody CrmServiceTicketProcessSlaResumeInput input) {
    return call(auth, "update", actor -> tickets.resumeSla(id, input, actor.userId(), auth));
  }

  /** 升级（转投诉 / 转纠纷 ⇒ 下游 11 售后）。 */
  @PostMapping("/tickets/{id}/escalate")
  public CrmServiceTicketProcessOutcome escalate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody CrmServiceTicketProcessEscalateInput input) {
    return call(auth, "update", actor -> tickets.escalate(id, input, actor.userId(), auth));
  }
}
