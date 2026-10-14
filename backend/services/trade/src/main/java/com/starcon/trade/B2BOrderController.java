package com.starcon.trade;

import static com.starcon.trade.B2BOrderModels.*;

import jakarta.validation.Valid;
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
 * 9.7.1.1 批发订单与账期。
 *
 * <p><b>命名空间是 {@code /trade/b2b-orders}。</b>刻意不是 {@code /trade/orders}： 该路径已被 {@code
 * TradeTenancyIntegrationTest} 的断言 2 显式预留给另一条车道， 占了它会让一条<b>已通过的测试</b>变红，而那个测试文件不属于本车道。
 *
 * <p>也刻意不与 9.1.2.2 的 {@code /trade/sales-orders} 合并：零售与批发的 订单类型 / 收款方式 /
 * 成本口径三者都不同，且生命周期不同（账期到期、开票需求）。 合并会让「哪个 tab 能改数据」与「这张单该收没收」两件事都变得不明显。
 *
 * <p>权限码用 {@code trade.b2b.read} / {@code trade.b2b.create} / {@code trade.b2b.update}：
 * 发起账期与登记发货等于让财务按它确认应收，不是读权限能带过去的动作。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限， 再在一个事务里绑定 {@code
 * app.current_tenant}，由 RLS 在数据库层强制隔离。 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 */
@RestController
@RequestMapping("/trade/b2b-orders")
public class B2BOrderController {

  private final AccessService access;
  private final B2BOrderService b2b;

  public B2BOrderController(AccessService access, B2BOrderService b2b) {
    this.access = access;
    this.b2b = b2b;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "trade.b2b." + permission);
    return access.run(actor, "trade.b2b." + permission, () -> work.apply(actor));
  }

  // ================================================================ 能力声明

  /** 本模块的能力与上下游可用性（含 05 价目 / 12 应收 / 12 发票 / 9.3 履约的缺口）。 */
  @GetMapping("/capabilities")
  public B2BCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> b2b.capabilities());
  }

  // ================================================================ 上游投影：客户

  /**
   * 客户档案投影 upsert（3.6.1.2 → trade 侧只读投影）。
   *
   * <p>这是**事件同步的写入面**：catalog 侧客户分级 / 账期变更后同步过来。 幂等键是 customerPartyId，版本号单调（乱序到达的旧事件会被 DB 拒绝覆盖新值）。
   */
  @PutMapping("/customer-terms")
  public B2BCustomerTermsRow upsertCustomerTerms(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody B2BCustomerTermsInput input) {
    return call(auth, "update", actor -> b2b.upsertCustomerTerms(input));
  }

  /** 客户投影列表：真分页 + 按分级 / 关键字筛选。 */
  @GetMapping("/customer-terms")
  public B2BCustomerTermsPage listCustomerTerms(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String customerLevel,
      @RequestParam(required = false) String keyword,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(auth, "read", actor -> b2b.pageCustomerTerms(customerLevel, keyword, page, size));
  }

  // ================================================================ 上游投影：阶梯价

  /**
   * 阶梯价投影 upsert（05 价目 → trade 侧投影），**整体替换**该 SKU × 分级的全部档位。
   *
   * <p>05 价目（5.1.1.2，D6）未交付，因此此刻由本端点维护；价目交付后由事件同步接管 同两张表，表结构与所有下游读法都不需要改。
   */
  @PutMapping("/price-books")
  public B2BPriceBookRow upsertPriceBook(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody B2BPriceBookRequest input) {
    return call(auth, "update", actor -> b2b.upsertPriceBook(input.book(), input.tiers()));
  }

  /** 阶梯价列表：真分页 + 按分级 / SKU 筛选。 */
  @GetMapping("/price-books")
  public B2BPriceBookPage listPriceBooks(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String customerLevel,
      @RequestParam(required = false) UUID skuId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(auth, "read", actor -> b2b.pagePriceBooks(customerLevel, skuId, page, size));
  }

  /** 单个阶梯价表头 + 全部档位。 */
  @GetMapping("/price-books/{id}")
  public B2BPriceBookRow priceBook(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "read", actor -> b2b.priceBook(id));
  }

  /**
   * 阶梯价试算（只读，不落库）。
   *
   * <p>下单要问「买 120 件多少钱、再买多少进下一档」，而建单是**先校验起订量再落档**的 —— 在下单页每改一次数量就吃一个 400 是坏体验。试算与建单走同一个落档函数。
   */
  @PostMapping("/price-quote")
  public B2BQuoteResponse quote(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody B2BPriceQuoteRequest request) {
    return call(auth, "read", actor -> new B2BQuoteResponse(b2b.quote(request)));
  }

  // ================================================================ 订单

  /** 订单列表：真分页 + 与分页无关的全量汇总（含账期应收与逾期）。 */
  @GetMapping
  public B2BOrderPage list(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String customerLevel,
      @RequestParam(required = false) UUID customerPartyId,
      @RequestParam(required = false) String paymentMethod,
      @RequestParam(required = false) String keyword,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(
        auth,
        "read",
        actor ->
            b2b.pageOrders(
                enumOf(status, B2BStatus.class, "订单状态"),
                enumOf(customerLevel, B2BCustomerLevel.class, "客户分级"),
                customerPartyId,
                enumOf(paymentMethod, B2BPaymentMethod.class, "收款方式"),
                keyword,
                page,
                size));
  }

  /** 订单详情：一次调用返回整屏（头 + 客户投影 + 行 + 发货 + 应收 + 事件流）。 */
  @GetMapping("/{id}")
  public B2BOrderDetail detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "read", actor -> b2b.order(id));
  }

  /**
   * 创建批发订单（草稿）。
   *
   * <p>行只收 SKU 与数量：单价、阶梯落档、起订量全部由系统算。 账期天数缺省时取客户档案投影上的账期（3.6.1.2）。
   */
  @PostMapping
  public B2BSaved create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody B2BOrderCreateInput input) {
    return call(auth, "create", actor -> b2b.create(input, actor));
  }

  /** 更新草稿（整体替换行）。确认后由 DB 触发器冻结。 */
  @PutMapping("/{id}")
  public B2BSaved update(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody B2BOrderUpdateInput input) {
    return call(auth, "update", actor -> b2b.update(id, input, actor));
  }

  /**
   * 确认订单：DRAFT → CONFIRMED。
   *
   * <p><b>确认不产生应收。</b>B2B 的账期从发货确认那天起算 —— 与零售「平台先收款后发货」的方向相反，这正是 12.1.2.2 要分开建模的原因。
   */
  @PostMapping("/{id}/confirm")
  public B2BSaved confirm(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "update", actor -> b2b.confirm(id, actor));
  }

  /**
   * 发货确认 —— 应收的<b>唯一</b>产生入口。
   *
   * <p>收款方式决定发不产生应收：下单结算不产生（款项已收），确认收货后结算 / 账期产生， 账期的到期日 = 发货确认时刻 + 账期天数。
   */
  @PostMapping("/{id}/shipments")
  public B2BSaved ship(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody B2BShipmentInput input) {
    return call(auth, "update", actor -> b2b.ship(id, input, actor));
  }

  /** 登记开票需求：把本单未结应收标记为「已请求开票」。不跨库写 12 财务。 */
  @PostMapping("/{id}/invoice-requests")
  public B2BSaved requestInvoice(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "update", actor -> b2b.requestInvoice(id, actor));
  }

  // ================================================================ 应收

  /**
   * 应收列表：真分页 + 账期资金汇总（未结 / 逾期 / 待开票）。
   *
   * <p>刻意与订单列表**分成两个端点**而不是合并成一张表：应收是「已发货才存在」的另一类单据， 它的主查询维度是到期日（资金计划按它排），与订单列表的创建时间维度不是一回事。
   */
  @GetMapping("/receivables")
  public B2BReceivablePage receivables(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String invoiceState,
      @RequestParam(required = false) UUID orderId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(
        auth,
        "read",
        actor ->
            b2b.pageReceivables(
                enumOf(status, B2BReceivableStatus.class, "应收状态"),
                enumOf(invoiceState, B2BInvoiceState.class, "开票需求状态"),
                orderId,
                page,
                size));
  }

  // ================================================================ 工具

  /** 查询串里的枚举：不合法时给 400 而不是让 DB 的 CHECK 报一个看不懂的错。 */
  private static <E extends Enum<E>> E enumOf(String raw, Class<E> type, String label) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return Enum.valueOf(type, raw.trim());
    } catch (IllegalArgumentException e) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.BAD_REQUEST, label + "不合法：" + raw);
    }
  }
}
