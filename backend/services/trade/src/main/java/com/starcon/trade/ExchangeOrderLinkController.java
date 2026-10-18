package com.starcon.trade;

import static com.starcon.trade.ExchangeOrderLinkModels.*;

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
 * 11.3.2.2 换货新单。
 *
 * <p>蓝图 11-aftersales-and-returns.md：「<b>建立换货关联（原售后 ↔ 新销售单/发货单），
 * 跟踪换货闭环状态，防止重复换货。</b>」
 *
 * <p><b>命名空间</b>：{@code /trade/aftersales} 之下新开 {@code /exchange-orders}，
 * 与 11.3.2.1 的 {@code /exchanges} 并列 —— 授权是「批不批」，新单是「发出去了没有」，
 * 两者是换货闭环上的两个不同动作，混在一个资源下就会出现「授权单」既当审批单又当履约单的
 * 两义。前端路由 {@code /aftersales/exchange-orders}。
 *
 * <p><b>权限码沿用</b> {@code trade.aftersales.read} / {@code trade.aftersales.write}：
 * 与同 L3 的 11.3.2.1 同一批权限位 —— 售后场景里「能看换货但不能建换货新单」
 * 没有业务含义。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} / {@link #write} 先向 identity 确认已认证成员关系，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 FORCE RLS 在数据库层强制隔离。
 * 伪造 {@code X-Tenant-Id} 头改不动这个设置。
 */
@RestController
@RequestMapping("/trade/aftersales/exchange-orders")
public class ExchangeOrderLinkController {

  private static final String READ = "aftersales.read";
  private static final String WRITE = "aftersales.write";

  private final AccessService access;
  private final ExchangeOrderLinkService links;

  public ExchangeOrderLinkController(AccessService access, ExchangeOrderLinkService links) {
    this.access = access;
    this.links = links;
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
   * 能力声明：上游 11.3.2.1 是否已接入、下游 9 订单 / 8 出库 / 11.2 的真实状态、
   * 闭环口径与两条硬规则原文。
   *
   * <p>路径是租户级静态事实，挂在 {@code /exchange-orders/capabilities} 而不是
   * {@code /{id}/capabilities} —— 后者会让人以为它随单变化。
   */
  @GetMapping("/capabilities")
  public ExchangeOrderCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, actor -> links.capabilities());
  }

  /** 下拉项：可建换货新单的换货授权单（已建过新单的 / 已撤销的刻意不出现在这里）。 */
  @GetMapping("/filters")
  public ExchangeOrderFilterOptions filters(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, actor -> links.filters());
  }

  // ================================================================ 列表与详情

  /** 列表：真分页 + 汇总 + 筛选 + 排序白名单。 */
  @GetMapping
  public ExchangeOrderPage list(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID authorizationId,
      @RequestParam(required = false) UUID caseId,
      @RequestParam(required = false) UUID orderId,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String sort,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(
        auth,
        actor ->
            links.list(
                new ExchangeOrderQuery(
                    str(authorizationId), str(caseId), str(orderId), status, keyword, sort,
                    page, size)));
  }

  /**
   * 详情：头 + 逐行 + 闭环事件时间线 + 可执行动作 + <b>没闭环的真实原因</b>。
   *
   * <p>{@code closureBlockers} 是刻意做的一列：只给「已建立」而不说清还差什么，
   * 页面就只能显示灰状态，而灰状态会被读成「系统在处理中」。
   */
  @GetMapping("/{id}")
  public ExchangeOrderDetail detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, actor -> links.detail(id));
  }

  // ================================================================ 建单与闭环动作

  /**
   * <b>建立换货新单</b>：真在 sales_order 建单 + 写齐快照 + 落换货关联。
   *
   * <p>同一张换货授权只能建一次，第二次 409 —— 那是蓝图职责句点名的「防止重复换货」。
   */
  @PostMapping
  public ExchangeOrderDetail create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody ExchangeOrderCreateRequest req) {
    return write(auth, actor -> links.create(req, actor.userId(), actor.membershipId()));
  }

  /**
   * 提交换货新单进审单队列：真把 sales_order 从 DRAFT 推到 SUBMITTED。
   *
   * <p>必须带 {@code expectedVersion}：两个人同时点提交时，后到的那个必须被 409 拒掉。
   */
  @PostMapping("/{id}/submit")
  public ExchangeOrderDetail submit(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody ExchangeOrderSubmitRequest req) {
    return write(auth, actor -> links.submit(id, req, actor.membershipId()));
  }

  /**
   * <b>同步闭环状态</b>：只去读真实发货记录与旧货回寄要求，然后据此推进。
   *
   * <p>刻意不接受任何状态入参 —— 「请把状态置成已发货」这种形状不存在于本端点，
   * 那正是假交付的入口。
   */
  @PostMapping("/{id}/sync")
  public ExchangeOrderDetail sync(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody ExchangeOrderSyncRequest req) {
    return write(auth, actor -> links.sync(id, req, actor.membershipId()));
  }

  /**
   * 撤销换货新单：只在<b>新单仍是草稿且从未发货</b>时允许。
   *
   * <p>撤销会一并取消 11.3.2.1 的新发货需求（新单都不发了，需求不能还挂在那儿 PENDING）；
   * 但替换货预占的释放走 11.3.2.1 的撤销授权 —— 预占的写入所有者是 warehouse。
   */
  @PostMapping("/{id}/cancel")
  public ExchangeOrderDetail cancel(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody ExchangeOrderCancelRequest req) {
    return write(auth, actor -> links.cancel(id, req, actor.userId(), actor.membershipId()));
  }

  private static String str(UUID v) {
    return v == null ? null : v.toString();
  }
}
