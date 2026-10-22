package com.starcon.crm;

import static com.starcon.crm.ServiceTicketListModels.*;

import jakarta.validation.Valid;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 15.4.1.1 工单列表。
 *
 * <p>命名空间 {@code /crm/service-tickets}：浏览器只经 {@code /api/crm} 代理访问；
 * 后端路径按<b>服务</b>命名空间走，与 catalog（/catalog/...）、trade（/trade/...）同一纪律。
 *
 * <p>⚠️ 与同卷兄弟模块（{@code /crm/message-inbox} 15.1.1.1 消息收件箱、
 * {@code /crm/knowledge} 15.4.2.1 客服知识库、{@code /crm/care-reminders} 15.5.2.1
 * 关怀与催办）<b>刻意不共用命名空间</b>：那些回答的是「买家说了什么」
 * 「这条政策这一版变了没有」「什么时候对谁发过什么」，
 * 本模块回答的是「<b>现在有哪些客服工单在办、还剩多少营业时间、归谁跟、
 * 关联哪位买家与哪一单、转派过几次</b>」。
 *
 * <p>🔴 <b>本模块是只读列表</b>：工单的**写**动作（建单 / 派单 / 转派 / 关闭 / 升级）
 * 仍然只在 trade 服务（11.4.2.1），本模块**不提供**任何处理动作
 * （15.4.1.2 工单处理已交付 ⇒ 能力声明里如实标 {@code available=true} + ownerModule，
 * 处理入口是它**真实存在**的 {@code /crm/service-ticket-process}）。
 *
 * <p>租户<b>不</b>来自任何请求头：先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 * 读上游 trade 时转发的是<b>调用方自己的</b> bearer —— 租户由上游按已认证成员关系解析，
 * 租户隔离因此是<b>两道</b>（crm 侧 RLS + trade 侧 RLS）。
 *
 * <p>🔴 权限码用 {@code crm.service_ticket.*}，与兄弟族的 {@code crm.contact.*} /
 * {@code crm.message.*} / {@code crm.care_reminder.*} 同一命名习惯，
 * <b>identity 服务零改动</b>（权限码是角色上自由组合的字符串，不是固定词表）。
 */
@RestController
@RequestMapping("/crm/service-tickets")
public class ServiceTicketListController {

  private final AccessService access;
  private final ServiceTicketListService tickets;

  public ServiceTicketListController(AccessService access, ServiceTicketListService tickets) {
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
   * <p>🔴 里面显式说明下游：**15.4.1.2 工单处理已交付** ⇒
   * {@code ticket_processing} 为 {@code available=true} + ownerModule=15.4.1.2，
   * 指向**真实存在**的入口 {@code /crm/service-ticket-process}，
   * <b>不指向</b>任何不存在的入口。
   */
  @GetMapping("/capabilities")
  public CrmServiceTicketCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> tickets.capabilities());
  }

  // ================================================================ 列表

  /**
   * 工单列表（真分页 + 真筛选 + 营业时间口径的 SLA 剩余 + 关联买家 / 订单 / 转派与升级）。
   *
   * <p>🔴 {@code slaState} / {@code overdueOnly} 走的是<b>本服务</b>的营业时间口径，
   * <b>不能</b>原样下推上游 —— 上游的同名筛选是自然时间口径，
   * 直接下推会漏掉「自然时间还没到、营业时间已经超了」的单（夜间积压正是这类）。
   */
  @GetMapping
  public CrmServiceTicketPage list(
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
      @RequestParam(required = false) Boolean unassignedOnly,
      @RequestParam(required = false) OffsetDateTime from,
      @RequestParam(required = false) OffsetDateTime to,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String sort,
      @RequestParam(required = false) Boolean enrich,
      @RequestParam(required = false) UUID shopId) {
    return call(
        auth,
        "read",
        actor ->
            tickets.list(
                new CrmServiceTicketListQuery(
                    page, size, status, ticketType, priority, sourceChannel, assignedToRef, caseId,
                    slaState, overdueOnly, unassignedOnly, from, to, keyword, sort, enrich, shopId),
                auth));
  }

  /** 单张工单（含上游的转派历史）。 */
  @GetMapping("/{id}")
  public CrmServiceTicketRow detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "read", actor -> tickets.detail(id, auth));
  }

  // ================================================================ 营业时间口径

  /**
   * 读某个作用域的营业时间口径（时段 + 节假日例外）。
   *
   * <p>⚠️ 店铺作用域没配过时回落到<b>租户默认口径</b>，响应里写明回落到了哪一条 ——
   * 「悄悄用了一条别的口径」比「口径没配」更难发现。
   */
  @GetMapping("/business-hours")
  public CrmBusinessHoursConfig businessHours(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID shopId) {
    return call(auth, "read", actor -> tickets.businessHours(shopId));
  }

  /**
   * 改写某个作用域的营业时段（<b>整段替换</b>）。
   *
   * <p>🔴 每次改写都留一条只追加的审计（谁、何时、改成了什么）——
   * 营业时间直接决定客服绩效口径，「当时的口径是什么」必须查得到。
   */
  @PutMapping("/business-hours")
  public CrmBusinessHoursConfig updateBusinessHours(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody CrmBusinessHoursInput input) {
    return call(auth, "update", actor -> tickets.updateBusinessHours(input, actor.userId()));
  }

  /** 改写某个作用域的节假日例外（<b>整段替换</b>）。 */
  @PutMapping("/business-hours/holidays")
  public CrmBusinessHoursConfig updateBusinessHolidays(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody CrmBusinessHolidayInput input) {
    return call(auth, "update", actor -> tickets.updateBusinessHolidays(input, actor.userId()));
  }
}
