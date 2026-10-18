package com.starcon.trade;

import static com.starcon.trade.ExchangeAuthorizationModels.*;

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
 * 11.3.2.1 换货授权。
 *
 * <p>蓝图 11-aftersales-and-returns.md：「<b>批准换货：确认可换数量与库存（要真实预占替换货）、
 * 生成新出库安排，保留原单追溯关系。</b>」
 *
 * <p><b>命名空间与 11.1 售后族并列</b>：{@code /trade/aftersales} 之下新开 {@code /exchanges}，
 * 与 11.3.1.2 的 {@code /trade/refunds} 同一个 L2（11.3 退款与换货）下的两个分支。
 * 前端路由 {@code /aftersales/exchanges}。
 *
 * <p><b>权限码沿用</b> {@code trade.aftersales.read} / {@code trade.aftersales.write}：
 * 本模块是售后族的一个动作，与列表 / 详情同一批权限位 ——
 * 「能看换货单但不能授权换货」这种权限位在售后场景里没有业务含义。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} / {@link #write} 先向 identity 确认已认证成员关系，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 FORCE RLS 在数据库层强制隔离。
 * 伪造 {@code X-Tenant-Id} 头改不动这个设置。
 *
 * <p>换货授权与取消都要向 warehouse 转发<b>调用方自己的</b> bearer 去登记 / 释放预占，
 * 所以控制器把凭证原样传进服务层（与 {@code ShipmentRecordController} 同一做法）。
 */
@RestController
@RequestMapping("/trade/aftersales/exchanges")
public class ExchangeAuthorizationController {

  private static final String READ = "aftersales.read";
  private static final String WRITE = "aftersales.write";

  private final AccessService access;
  private final ExchangeAuthorizationService exchanges;

  public ExchangeAuthorizationController(AccessService access, ExchangeAuthorizationService exchanges) {
    this.access = access;
    this.exchanges = exchanges;
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
   * 能力声明：预占是否已接入、下游 9.3.3.1 与 11.2.2.2 是否已交付、两条硬规则原文。
   *
   * <p>路径是租户级静态事实，挂在 {@code /exchanges/capabilities} 而不是
   * {@code /{id}/capabilities} —— 后者会让人以为它随单变化。
   */
  @GetMapping("/capabilities")
  public ExchangeCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, actor -> exchanges.capabilities());
  }

  /** 下拉项：可授权的换货售后单（已授权 / 已结案的刻意不出现在这里）。 */
  @GetMapping("/filters")
  public ExchangeFilterOptions filters(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, actor -> exchanges.filters());
  }

  // ================================================================ 列表与详情

  /** 列表：真分页 + 汇总 + 筛选 + 排序白名单。 */
  @GetMapping
  public ExchangePage list(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID caseId,
      @RequestParam(required = false) UUID orderId,
      @RequestParam(required = false) UUID skuId,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String decision,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String sort,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(
        auth,
        actor ->
            exchanges.list(
                new ExchangeQuery(
                    str(caseId), str(orderId), str(skuId), status, decision, keyword, sort,
                    page, size)));
  }

  /** 详情：头 + 逐行（退什么 → 换成什么 → 换几件 → 预占在哪一笔 → 旧货等不等）+ 可执行动作。 */
  @GetMapping("/{id}")
  public ExchangeDetail detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, actor -> exchanges.detail(id));
  }

  // ================================================================ 授权与撤销

  /**
   * <b>批准换货</b>：确认可换数量 → 向 warehouse <b>真实预占</b>替换货 → 生成新发货需求 + 等待旧货回寄。
   *
   * <p>整件事在一个事务里完成，且预占是真的远程写：拿不到 warehouse 的预占应答就整笔失败。
   */
  @PostMapping
  public ExchangeDetail authorize(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody ExchangeAuthorizeRequest req) {
    return write(auth, actor -> exchanges.authorize(req, actor.userId(), auth));
  }

  /**
   * 撤销换货：真去 warehouse 释放预占，然后把新发货需求取消、旧货回寄要求免回。
   *
   * <p>必须带 {@code expectedVersion}（蓝图输入句点名的乐观锁）：两个人同时点撤销时，
   * 后到的那个必须被 409 拒掉。
   */
  @PostMapping("/{id}/cancel")
  public ExchangeDetail cancel(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody ExchangeCancelRequest req) {
    return write(auth, actor -> exchanges.cancel(id, req, actor.userId(), auth));
  }

  private static String str(UUID v) {
    return v == null ? null : v.toString();
  }
}
