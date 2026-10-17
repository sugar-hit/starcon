package com.starcon.trade;

import static com.starcon.trade.AftersalesCaseModels.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 11.1.1.1 售后单列表。
 *
 * <p><b>命名空间是 {@code /trade/aftersales}，与 {@code /aftersales/knowledge}（11.4.2.2 客服知识库，
 * 已交付）并列。</b>前端路由同样是 {@code /aftersales} —— 蓝图 11 卷写明的 L3 入口，
 * 且它此前挂的是一个 {@code DomainPlaceholder} 占位页，本模块把它换成真实工作区。
 *
 * <p>刻意**不**占用以下路径（它们是别的车道或未交付模块的地盘）：
 *
 * <ul>
 *   <li>{@code /trade/orders} —— {@code TradeTenancyIntegrationTest} 第 221 行<b>显式断言</b>它必须 404（GET 与 POST 都算），
 *       占了它会让一条<b>已通过的</b>测试变红，而那个测试文件属于别的模块；
 *   <li>{@code /trade/aftersales/cases/{id}}（单条详情）—— 那是 11.1.1.2 的职责，未交付。
 *       本模块<b>不提供</b>它：列表行已经带着分页、筛选、时效、责任人、授权状态与工单号，
 *       运营在列表页能做的事不需要详情页；而一个点进去 404 的入口比没有这个入口更糟。
 * </ul>
 *
 * <p><b>权限码</b>：读 {@code trade.aftersales.read}，写 {@code trade.aftersales.write}。
 * 写权限只覆盖本模块自己的三张表（授权 / 备注 / 工单）与本模块的登记入口，
 * <b>不</b>覆盖 11.1.1.3 受理判定 —— 那四件事（售后期校验 / 条件判定 / 责任人分配 / 回复时限设置）
 * 未交付，见 {@link AftersalesCaseService#capabilities()} 的 {@code intakeDelivered=false}。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} / {@link #write} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 FORCE RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置；拿别的租户的售后单 ID 来批量操作，
 * 得到的是逐条 {@code NOT_FOUND} 而不是「静默成功」。
 */
@RestController
@RequestMapping("/trade/aftersales")
public class AftersalesCaseController {

  private static final String READ = "aftersales.read";
  private static final String WRITE = "aftersales.write";

  private final AccessService access;
  private final AftersalesCaseService cases;

  public AftersalesCaseController(AccessService access, AftersalesCaseService cases) {
    this.access = access;
    this.cases = cases;
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
   * 能力声明：哪些维度真有依据、上游通没通、下游交付没交付、三件批量操作各自落在哪张表。
   *
   * <p>让界面能说清「为什么没有店铺维度」「责任人是谁登记的」，而不是让它们显示成空白
   * 让人以为「这个租户没有售后单」。
   */
  @GetMapping("/capabilities")
  public AftersalesCaseCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, actor -> cases.capabilities());
  }

  /** 筛选项下拉用的租户级词表（币种 / 平台来源来自真实数据，不是硬编码词表）。 */
  @GetMapping("/filters")
  public AftersalesFilterOptions filters(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, actor -> cases.filterOptions());
  }

  // ================================================================ 列表

  /**
   * 售后单主列表：真分页 + 多维筛选 + 时效预警标记 + 与分页无关的全量汇总。
   *
   * <p>每个筛选参数都对应一个<b>真实存在</b>的列、索引或 DB 函数。刻意**不作为参数存在**的：
   * {@code shopCode}（{@code sales_order} 没有 shop_code 列、卷 2 店铺在 channel 独立库禁止跨服务读）
   * 与「平台退款实额」（11.3 + 12 财务的账）—— 提供一个永远筛不出东西的参数比不提供更糟。
   *
   * <p>金额区间是<b>闭区间</b>（两端都取），口径由响应里的 {@code amountRangeSemantics} 原样报出。
   */
  @GetMapping("/cases")
  public AftersalesCasePage list(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String caseType,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String reasonCode,
      @RequestParam(required = false) String platformSource,
      @RequestParam(required = false) String timelinessState,
      @RequestParam(required = false) String currency,
      @RequestParam(required = false) String authorizationState,
      @RequestParam(required = false) UUID orderId,
      @RequestParam(required = false) String orderNo,
      @RequestParam(required = false) String caseNo,
      @RequestParam(required = false) BigDecimal minAmount,
      @RequestParam(required = false) BigDecimal maxAmount,
      @RequestParam(required = false) OffsetDateTime receivedFrom,
      @RequestParam(required = false) OffsetDateTime receivedTo,
      @RequestParam(required = false) String ownerRef,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String sort,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(
        auth,
        actor ->
            cases.list(
                new AftersalesCaseQuery(
                    caseType,
                    status,
                    reasonCode,
                    platformSource,
                    timelinessState,
                    currency,
                    authorizationState,
                    orderId,
                    orderNo,
                    caseNo,
                    minAmount,
                    maxAmount,
                    receivedFrom,
                    receivedTo,
                    ownerRef,
                    keyword,
                    sort,
                    page,
                    size,
                    // 下面两个来自已认证身份与角色授权，**不是请求参数**。
                    actor.userId(),
                    selfOnly(actor))));
  }

  // ================================================================ 登记

  /**
   * 登记一张售后单。
   *
   * <p>⚠️ 这是 <b>11.1.1.3 售后受理未交付期间</b>的登记入口：只做「单据落库 + 平台时限登记」，
   * <b>不含</b>受理判定（售后期校验 / 条件判定 / 责任人自动分配 / 回复时限设置）。
   * 没有它，列表将永远为空 —— 那才是一条交付不出去的死链。
   */
  @PostMapping("/cases")
  public AftersalesCaseRow create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody AftersalesCaseCreate request) {
    return write(auth, actor -> cases.create(request, actor.userId()));
  }

  // ================================================================ 批量操作

  /**
   * 批量操作：授权 / 备注 / 转工单。三件共用一个端点（逐条判定与逐条回报的规则完全相同）。
   *
   * <p><b>单次上限 100 张</b>（超 400）；<b>逐条结果回报</b> —— 响应里的 {@code items} 与请求里的
   * {@code caseIds} 一一对应、同序，含每条的成功与否、结果码、人话原因与产物引用。
   * 「30 条批了 12 条」不是可接受的结果。
   */
  @PostMapping("/cases/bulk")
  public AftersalesBulkResult bulk(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody AftersalesBulkRequest request) {
    return write(auth, actor -> cases.bulk(request, actor.userId()));
  }

  // ================================================================ 数据范围

  /**
   * 数据范围是否为「仅本人」。
   *
   * <p>蓝图把「用户数据范围」列为本模块的输入之一，所以它必须<b>真的生效</b>，
   * 而不是只挂在页面上。取值规则与 {@code AccessService.require} 同一套授权：
   * 命中的授权里只要有一条 ALL 就按 ALL，否则按 SELF 收窄到责任人流转里有本人的售后单。
   *
   * <p><b>不接受请求参数传入</b> —— 否则「只看我自己的单」会变成一个能越权的开关。
   */
  static boolean selfOnly(AccessService.Access a) {
    String read = "trade." + READ;
    var grants = a.grants() == null ? List.<AccessService.Grant>of() : a.grants();
    boolean anyAll =
        grants.stream()
            .anyMatch(
                g ->
                    g.scopeMode().equals("ALL")
                        && g.permissions().contains(read)
                        && g.permissions().contains("trade." + READ));
    return !anyAll;
  }
}
