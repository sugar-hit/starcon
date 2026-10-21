package com.starcon.trade;

import static com.starcon.trade.PurchaseOrderListModels.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 6.3.1.1 采购单列表（卷 06 采购与供应 / L3 6.3.1 采购单的锚点模块）。
 *
 * <p><b>命名空间是 {@code /trade/purchase-orders}</b>：本仓 trade 服务下已存在
 * {@code /trade/purchase-requests}（6.2.2.1 采购申请）、{@code /trade/purchase-arrivals}
 * （6.3.2.2 到货跟踪）、{@code /trade/purchase-plans}（6.2.1.3 采购计划）、
 * {@code /trade/purchase-analytics}（6.5.2.1 采购分析）、{@code /trade/purchase-reconciliations}，
 * 但 {@code /trade/purchase-orders} 是空的（且没有别的模块在它上面做过断言），
 * 而它正是蓝图给 6.3 采购执行写的路由前缀（{@code 6.3 采购执行 → /purchase/orders}）。
 *
 * <p><b>租户绝不来自任何请求头</b>：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 *
 * <p><b>数据范围由服务端从角色授权推导</b>（ALL / DEPARTMENT / SELF），
 * <b>不接受请求参数传入</b> —— 否则它会变成一个能越权的开关
 * （照 9.1.1.1 SalesOrderList 的既有做法）。
 *
 * <p><b>权限码复用 trade 既有体系</b>：{@code trade.purchase.read} / {@code trade.purchase.update}
 * —— 与 6.3.2.2 到货跟踪同一套，identity 零改动。
 */
@RestController
@RequestMapping("/trade/purchase-orders")
public class PurchaseOrderListController {

  private final AccessService access;
  private final PurchaseOrderListService orders;

  public PurchaseOrderListController(AccessService access, PurchaseOrderListService orders) {
    this.access = access;
    this.orders = orders;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, Function<AccessService.Access, T> work) {
    AccessService.Access actor = access.require(bearer, PurchaseOrderListService.READ_PERMISSION);
    return access.run(actor, PurchaseOrderListService.READ_PERMISSION, () -> work.apply(actor));
  }

  /** 写入口：同样先确认身份与权限（写权限码要求同一个 grant 里同时有 read）。 */
  private <T> T write(String bearer, Function<AccessService.Access, T> work) {
    AccessService.Access actor = access.require(bearer, PurchaseOrderListService.UPDATE_PERMISSION);
    return access.run(actor, PurchaseOrderListService.UPDATE_PERMISSION, () -> work.apply(actor));
  }

  // ================================================================ 能力声明

  /**
   * 蓝图要而本服务拿不到的维度，以及批量操作的落地情况。
   *
   * <p>让界面能说清「为什么没有审批状态、为什么没有应付状态」，
   * 而不是让它们显示成空白让人以为「这单没有这些值」。
   */
  @GetMapping("/capabilities")
  public PurchaseOrderListCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, actor -> orders.capabilities());
  }

  /** 筛选项下拉用的租户级词表（币种 / 供应商 / 目标仓都来自真实数据，不是硬编码）。 */
  @GetMapping("/filters")
  public PurchaseOrderListFilterOptions filters(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, actor -> orders.filterOptions());
  }

  // ================================================================ 列表

  /**
   * 采购单主列表：真分页 + 与分页无关的全量汇总。
   *
   * <p>每个筛选参数都对应一个<b>真实存在</b>的列或聚合。
   */
  @GetMapping
  public PurchaseOrderListPage list(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID supplierRefId,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) UUID targetWarehouseId,
      @RequestParam(required = false) String currency,
      @RequestParam(required = false) UUID skuId,
      @RequestParam(required = false) OffsetDateTime createdFrom,
      @RequestParam(required = false) OffsetDateTime createdTo,
      @RequestParam(required = false) LocalDate promisedFrom,
      @RequestParam(required = false) LocalDate promisedTo,
      @RequestParam(required = false) Boolean overdueOnly,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String sort,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(
        auth,
        actor ->
            orders.list(
                new PurchaseOrderListQuery(
                    supplierRefId,
                    status,
                    targetWarehouseId,
                    currency,
                    skuId,
                    createdFrom,
                    createdTo,
                    promisedFrom,
                    promisedTo,
                    overdueOnly,
                    keyword,
                    sort,
                    page,
                    size,
                    // 下面三个来自已认证身份与角色授权，**不是请求参数**。
                    actor.userId(),
                    actor.departmentId(),
                    scopeMode(actor))));
  }

  /**
   * 导出当前筛选结果（CSV）。
   *
   * <p>与列表<b>同一套筛选条件</b>。超过上限时截断，并在响应头里如实标出 ——
   * 静默少给一份导出，比不给更危险。
   */
  @GetMapping("/export")
  public ResponseEntity<String> export(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID supplierRefId,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) UUID targetWarehouseId,
      @RequestParam(required = false) String currency,
      @RequestParam(required = false) UUID skuId,
      @RequestParam(required = false) OffsetDateTime createdFrom,
      @RequestParam(required = false) OffsetDateTime createdTo,
      @RequestParam(required = false) LocalDate promisedFrom,
      @RequestParam(required = false) LocalDate promisedTo,
      @RequestParam(required = false) Boolean overdueOnly,
      @RequestParam(required = false) String keyword) {
    var result =
        call(
            auth,
            actor ->
                orders.exportCsv(
                    new PurchaseOrderListQuery(
                        supplierRefId,
                        status,
                        targetWarehouseId,
                        currency,
                        skuId,
                        createdFrom,
                        createdTo,
                        promisedFrom,
                        promisedTo,
                        overdueOnly,
                        keyword,
                        null,
                        0,
                        10000,
                        actor.userId(),
                        actor.departmentId(),
                        scopeMode(actor))));
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.parseMediaType("text/csv; charset=UTF-8"));
    headers.set("X-Export-Row-Count", String.valueOf(result.rowCount()));
    headers.set("X-Export-Truncated", String.valueOf(result.truncated()));
    headers.set(
        "Content-Disposition",
        "attachment; filename=\"purchase-orders.csv\"; filename*=UTF-8''purchase-orders.csv");
    return new ResponseEntity<>(result.csv(), headers, org.springframework.http.HttpStatus.OK);
  }

  // ================================================================ 建采购单

  /**
   * 由 6.2.2.1 的转单事实生成采购单。
   *
   * <p>6.3.1.2（创建与编辑）未交付 ⇒ 本仓此前**没有任何采购单产生路径**，
   * 列表会永远是空的。6.2.2.1 的 {@code purchase_request_conversion} 正是
   * 「转成采购单时的参数」的事实（V246 明写「6.3.1.2 交付后，由它读这份转单事实去建真正的采购单」），
   * 本模块把这条路径先落地；6.3.1.2 交付后接手<b>手工建单与编辑</b>。
   *
   * <p>被拒的转单事实逐条带原因码返回（<b>不静默丢弃</b>：丢掉的行等于少订的货）。
   */
  @PostMapping("/from-conversions")
  public PurchaseOrderListConversionResult fromConversions(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody PurchaseOrderListConversionRequest request) {
    return write(
        auth,
        actor ->
            orders.createFromConversions(
                request.conversionIds(), actor.userId(), actor.departmentId(), auth));
  }

  // ================================================================ 批量操作

  /**
   * 批量催单：留痕 + 催单计数 + 最近催单时刻。<b>不改承诺到货日</b>（交期变更历史归 6.3.2.1）。
   */
  @PostMapping("/batch/urge")
  public PurchaseOrderListBatchResult urge(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody PurchaseOrderListUrgeRequest request) {
    return write(
        auth,
        actor ->
            orders.urge(
                request.orderIds(),
                request.note(),
                actor.userId(),
                actor.departmentId(),
                scopeMode(actor, PurchaseOrderListService.UPDATE_PERMISSION)));
  }

  /** 批量关闭：必须填理由；关闭后整单冻结。 */
  @PostMapping("/batch/close")
  public PurchaseOrderListBatchResult close(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody PurchaseOrderListCloseRequest request) {
    return write(
        auth,
        actor ->
            orders.close(
                request.orderIds(),
                request.reason(),
                actor.userId(),
                actor.departmentId(),
                scopeMode(actor, PurchaseOrderListService.UPDATE_PERMISSION)));
  }

  // ================================================================ 数据范围

  /**
   * 数据范围：ALL / DEPARTMENT / SELF。
   *
   * <p>蓝图把「用户数据范围」列为本模块的输入之一，所以它必须<b>真的生效</b>，
   * 而不是只挂在页面上。取值规则与 {@code AccessService.require} 同一套授权：
   * <b>命中该权限的授权里</b>只要有一条 ALL 就按 ALL；否则有 DEPARTMENT 且当前成员有部门就按
   * DEPARTMENT；再否则按 SELF 收窄到本人负责的采购单。
   *
   * <p>⚠️ 「操作和范围必须由同一分配满足」（ADR-0005）：这里只看<b>含有本权限</b>的授权，
   * 别的权限的授权（哪怕是 ALL）不参与这个模块的数据范围判定。
   * 写操作按<b>写权限</b>判定范围，读操作按<b>读权限</b>判定范围 ——
   * 「能读全量但只能改自己的单」是常见且合理的授权组合。
   */
  static String scopeMode(AccessService.Access a, String permission) {
    List<AccessService.Grant> grants =
        a.grants() == null ? List.<AccessService.Grant>of() : a.grants();
    boolean all =
        grants.stream()
            .anyMatch(g -> "ALL".equals(g.scopeMode()) && g.permissions().contains(permission));
    if (all) {
      return PurchaseOrderListService.SCOPE_ALL;
    }
    boolean department =
        grants.stream()
            .anyMatch(
                g -> "DEPARTMENT".equals(g.scopeMode()) && g.permissions().contains(permission));
    if (department && a.departmentId() != null) {
      return PurchaseOrderListService.SCOPE_DEPARTMENT;
    }
    return PurchaseOrderListService.SCOPE_SELF;
  }

  /** 读路径的数据范围。 */
  static String scopeMode(AccessService.Access a) {
    return scopeMode(a, PurchaseOrderListService.READ_PERMISSION);
  }

  /** 供测试引用：金额口径说明（避免浮点）。 */
  static BigDecimal money(String raw) {
    return new BigDecimal(raw);
  }
}
