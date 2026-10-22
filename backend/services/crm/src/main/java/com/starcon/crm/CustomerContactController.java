package com.starcon.crm;

import static com.starcon.crm.CustomerContactModels.*;

import jakarta.validation.Valid;
import java.util.List;
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
 * 15.3.2.2 接触记录。
 *
 * <p>命名空间 {@code /crm/customer-contacts}：浏览器只经 {@code /api/crm} 代理访问；
 * 后端路径按<b>服务</b>命名空间走，与 catalog（/catalog/...）、trade（/trade/...）同一纪律。
 *
 * <p>⚠️ 与同卷兄弟模块（{@code /crm/customer-tags} 15.3.2.1 客户标签、
 * {@code /crm/message-inbox} 15.1.1.1 消息收件箱、{@code /crm/buyer-emails} 15.1.2.1 买家邮件）
 * <b>刻意不共用命名空间</b>：那些回答的是「这位买家属于哪一类人」「平台来信说了什么」，
 * 本模块回答的是「我们与这位客户什么时候、以什么方式接触过，以及他现在还能不能被营销」。
 *
 * <p>租户<b>不</b>来自任何请求头：先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 *
 * <p>🔴 权限码用 {@code crm.contact.*}，与兄弟族的 {@code crm.tag.*} / {@code crm.message.*} /
 * {@code crm.buyer_email.*} 同一命名习惯，<b>identity 服务零改动</b>。
 */
@RestController
@RequestMapping("/crm/customer-contacts")
public class CustomerContactController {

  private final AccessService access;
  private final CustomerContactService contacts;

  public CustomerContactController(AccessService access, CustomerContactService contacts) {
    this.access = access;
    this.contacts = contacts;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "crm.contact." + permission);
    return access.run(actor, "crm.contact." + permission, () -> work.apply(actor));
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：前端在渲染前就知道哪些动作存在，而不是靠试。
   *
   * <p>🔴 里面显式说明「本模块<b>不发送</b>任何触达」，以及
   * 11 售后自动写入 / 15.5.2.1 营销触达 / 15.3.1.2 客户详情<b>未交付</b>。
   */
  @GetMapping("/capabilities")
  public CrmCustomerContactCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", a -> contacts.capabilities());
  }

  // ================================================================ 接触记录

  /** 接触记录列表（真分页 + 真筛选）。 */
  @GetMapping("/contacts")
  public CrmCustomerContactPage contacts(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String customerRef,
      @RequestParam(required = false) String contactType,
      @RequestParam(required = false) String direction,
      @RequestParam(required = false) String origin,
      @RequestParam(required = false) String sourceModule,
      @RequestParam(required = false) String complianceState,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "read",
        a ->
            contacts.contactPage(
                customerRef,
                contactType,
                direction,
                origin,
                sourceModule,
                complianceState,
                from,
                to,
                keyword,
                page,
                size));
  }

  /** 单条接触记录。 */
  @GetMapping("/contacts/{contactId}")
  public CrmCustomerContactRow contact(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID contactId) {
    return call(auth, "read", a -> contacts.contact(contactId));
  }

  /**
   * 手工补录一条接触记录。
   *
   * <p>🔴 {@code contactType} **不含** OPT_OUT：退订是合规状态，
   * 只能由退订端点（显式动作）改 —— 退订不可被普通接触记录悄悄覆盖或撤销。
   */
  @PostMapping("/contacts")
  public CrmCustomerContactRow createContact(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody CrmCustomerContactInput input) {
    return call(auth, "update", a -> contacts.createManual(input, a.userId()));
  }

  /**
   * 接触时间线（蓝图输出句的第一项）。
   *
   * <p>🔴 时间线带**当前退订判定** —— 这是 15.3.1.2 客户详情将来要消费的东西。
   */
  @GetMapping("/timeline")
  public CrmCustomerTimelinePage timeline(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam String customerRef,
      @RequestParam(required = false) String contactType,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth, "read", a -> contacts.timeline(customerRef, contactType, from, to, page, size));
  }

  /**
   * 上游模块自动写入一条接触记录（<b>幂等</b>）。
   *
   * <p>同一条上游记录重复写入只记一次（{@code sourceModule} + {@code sourceRef} 是幂等键）。
   * 同服务的 15.1 / 15.1.2.1 走<b>库内物化</b>（V299 触发器），
   * 跨服务的 11 售后走本入口（授权 HTTP）。
   */
  @PostMapping("/ingest")
  public CrmCustomerContactIngestResult ingest(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody CrmCustomerContactIngestInput input) {
    return call(auth, "update", a -> contacts.ingest(input, a.userId()));
  }

  // ================================================================ 退订 / 免打扰

  /** 退订记录列表（真分页 + 真筛选）。 */
  @GetMapping("/opt-outs")
  public CrmCustomerOptOutPage optOuts(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String customerRef,
      @RequestParam(required = false) String scope,
      @RequestParam(required = false) String state,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "read", a -> contacts.optOutPage(customerRef, scope, state, page, size));
  }

  /** 单条退订记录。 */
  @GetMapping("/opt-outs/{optOutId}")
  public CrmCustomerOptOutRow optOut(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID optOutId) {
    return call(auth, "read", a -> contacts.optOut(optOutId));
  }

  /** 退订审计流水（只追加：谁在什么时候登记 / 撤销、依据是什么）。 */
  @GetMapping("/opt-outs/{optOutId}/events")
  public CrmCustomerOptOutEventPage optOutEvents(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID optOutId,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "read", a -> contacts.optOutEvents(optOutId, page, size));
  }

  /**
   * 登记退订 / 免打扰（<b>显式动作</b>）。
   *
   * <p>🔴 必须写明依据；登记同时留下审计事件与一条时间线记录。
   */
  @PostMapping("/opt-outs")
  public CrmCustomerOptOutRow grantOptOut(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody CrmCustomerOptOutInput input) {
    return call(auth, "update", a -> contacts.grantOptOut(input, a.userId()));
  }

  /**
   * 显式撤销退订（<b>必须留痕</b>）。
   *
   * <p>🔴 撤销必须写原因；撤销后追加一条只追加的审计事件与一条时间线记录。
   * 已撤销的退订不能被改回生效（DB 触发器强制）。
   */
  @PostMapping("/opt-outs/{optOutId}/revoke")
  public CrmCustomerOptOutRow revokeOptOut(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID optOutId,
      @Valid @RequestBody CrmCustomerOptOutRevokeInput input) {
    return call(auth, "update", a -> contacts.revokeOptOut(optOutId, input, a.userId()));
  }

  // ================================================================ 豁免判定

  /**
   * 可查询的豁免判定 —— <b>某个客户当前是否处于「不可营销」状态</b>。
   *
   * <p>🔴 这是加粗句「接触记录是自动化的豁免依据」的对外接口：
   * 15.5.2 将来在发起营销触达前问它一句「这个人现在还能发吗」。
   *
   * <p>给 {@code customerRef} 查单个；给 {@code customerRefs}（逗号分隔）查一批。
   */
  @GetMapping("/opt-out-check")
  public CrmCustomerOptOutCheckResult optOutCheck(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String customerRef,
      @RequestParam(required = false) List<String> customerRefs) {
    return call(
        auth,
        "read",
        a -> {
          List<String> refs = new java.util.ArrayList<>();
          if (customerRef != null && !customerRef.isBlank()) refs.add(customerRef.trim());
          if (customerRefs != null) {
            for (String value : customerRefs) {
              for (String part : value.split(",")) {
                if (!part.isBlank()) refs.add(part.trim());
              }
            }
          }
          if (refs.isEmpty()) throw new IllegalArgumentException("必须给出 customerRef 或 customerRefs");
          if (refs.size() == 1) {
            CrmCustomerOptOutCheck single = contacts.optOutCheck(refs.get(0));
            return new CrmCustomerOptOutCheckResult(
                1,
                single.suppressed() ? 1 : 0,
                List.of(single),
                "本次判定 1 位客户，其中 " + (single.suppressed() ? 1 : 0) + " 位处于生效中的退订（不可营销）。");
          }
          return contacts.optOutCheckBatch(refs);
        });
  }
}
