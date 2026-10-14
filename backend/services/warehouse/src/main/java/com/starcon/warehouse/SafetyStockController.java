package com.starcon.warehouse;

import static com.starcon.warehouse.SafetyStockModels.*;

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
 * 7.2.2.2 安全库存：阈值配置、日均销量登记、评估生成预警、预警流转与补货建议。
 *
 * <p>命名空间 {@code /warehouse/inventory/safety-stock}：与 catalog 的 {@code /catalog}、 channel 的 {@code
 * /channel} 同一套约定，每个服务暴露自己的前缀，浏览器只经 /api/warehouse 代理访问。
 *
 * <p>权限码前缀 {@code warehouse.}，复用 V8 已注册的 {@code warehouse.inventory.read} / {@code
 * warehouse.inventory.update} —— **刻意不新起权限码**：identity 只注册了 {@code warehouse.inventory} 与 {@code
 * warehouse.facilities} 两个页面资源， 用一个没注册的码会让连 OWNER 都 403。
 *
 * <ul>
 *   <li>读（配置/销量/预警/建议/评估/能力声明）用 {@code inventory.read}；
 *   <li>写（新建与换版阈值、作废、登记销量、发起评估、认领与处置预警、采纳与忽略建议） 用 {@code inventory.update}。
 * </ul>
 *
 * <p><b>为什么「发起评估」是 update 而不是 read</b>：它有副作用（落评估批次 + 生成预警 + 建建议）， 挂在读权限上会让「看一眼库存健不健康」变成一个写动作。
 *
 * <p>刻意**不提供 DELETE**：阈值作废走 retire（关区间），预警与建议都要留档复盘。 删除防线在服务端（无 DELETE 端点 → 405）+ V114 的 REVOKE
 * DELETE + append-only 触发器。
 */
@RestController
@RequestMapping("/warehouse/inventory/safety-stock")
public class SafetyStockController {

  private final AccessService access;
  private final SafetyStockService safetyStock;

  public SafetyStockController(AccessService access, SafetyStockService safetyStock) {
    this.access = access;
    this.safetyStock = safetyStock;
  }

  /** 统一入口：先向 identity 确认身份与权限（租户**不**来自任何客户端头）， 再在一个事务里绑定 RLS 租户上下文执行查询。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "warehouse." + permission);
    return access.run(actor, "warehouse." + permission, () -> work.apply(actor));
  }

  // ============================================================ 能力声明

  /**
   * 能力声明：三种范围档、三档预警级别、两种预警类型、三种预警状态、两种仓种类、建议状态， 以及四条**必须如实报出**的边界（销量需登记 / 分类展开需调用方给 / 6.2.1.2 未交付 /
   * 工作台未交付）。
   *
   * <p>前端据此渲染选项与提示，而不是把规则硬编码在界面上。
   */
  @GetMapping("/capabilities")
  public SafetyStockCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "inventory.read", a -> safetyStock.capabilities());
  }

  // ============================================================ 阈值配置

  /** 阈值分档列表（真分页 + effectiveOnly + 关键字 + 按仓过滤）。 */
  @GetMapping("/policies")
  public SafetyStockPolicyPage policies(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String scopeType,
      @RequestParam(required = false) UUID categoryId,
      @RequestParam(required = false) UUID skuId,
      @RequestParam(required = false) String alertSeverity,
      @RequestParam(required = false) UUID warehouseId,
      @RequestParam(required = false, defaultValue = "false") boolean effectiveOnly,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "inventory.read",
        a ->
            safetyStock.page(
                new SafetyStockQuery(
                    scopeType,
                    categoryId,
                    skuId,
                    alertSeverity,
                    warehouseId,
                    effectiveOnly,
                    keyword,
                    page,
                    size)));
  }

  /**
   * 生效值解析：SKU &gt; CATEGORY &gt; DEFAULT 优先级，命中即止。
   *
   * <p>回退到兜底档是**如实报出**的状态（usedFallback）；连兜底档都没配时如实报「未配置」， 而不是静默按阈值 0 处理 —— 那会让「没人受保护」显示成「库存健康」。
   */
  @GetMapping("/policies/effective")
  public SafetyStockPolicyEffective effective(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String scopeType,
      @RequestParam(required = false) UUID categoryId,
      @RequestParam(required = false) UUID skuId,
      @RequestParam(required = false) String at) {
    return call(
        auth,
        "inventory.read",
        a -> safetyStock.effective(scope(scopeType), categoryId, skuId, moment(at)));
  }

  /** 阈值详情（含它吃哪几个仓、哪些算平台仓）。跨租户在 RLS 下查不到 → 404，不泄露存在性。 */
  @GetMapping("/policies/{id}")
  public SafetyStockPolicyDetail policy(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "inventory.read", a -> safetyStock.detail(id));
  }

  /**
   * 新建一档阈值。
   *
   * <p>权限码是 {@code warehouse.inventory.update} 而不是 read：阈值与预警级别是经营决策， 配错了直接断货或积压 ——
   * 把「改预警依据」挂在读权限上，那道闸门等于没有。
   */
  @PostMapping("/policies")
  public SafetyStockPolicySaved createPolicy(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody SafetyStockPolicyInput input) {
    return call(auth, "inventory.update", a -> safetyStock.save(null, input, a.userId()));
  }

  /**
   * 换版：收口当前在效的那一档，再开新档。
   *
   * <p>刻意不给「改这一档」的 PUT —— 阈值内容写定后不可改（V114 触发器在 DB 层挡住）， 改阈值只能通过收口 + 新开，历史那一档因此永远是「当时那条预警依据长什么样」的凭据。
   */
  @PostMapping("/policies/{id}/versions")
  public SafetyStockPolicySaved versionPolicy(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody SafetyStockPolicyInput input) {
    return call(auth, "inventory.update", a -> safetyStock.save(id, input, a.userId()));
  }

  /** 作废一档阈值（收口它的生效区间）。刻意不提供 DELETE —— 作废是审计事件，档位行必须永久保留。 */
  @PostMapping("/policies/{id}/retire")
  public SafetyStockPolicySaved retirePolicy(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam(required = false) String at) {
    return call(auth, "inventory.update", a -> safetyStock.retire(id, moment(at)));
  }

  // ============================================================ 日均销量

  /**
   * 登记一条 SKU 日均销量。
   *
   * <p>13 销量域未交付、库存流水没有渠道维度，日均销量**无法自动派生**，因此登记进来 （与 7.8.1.1 的 allocation_shop_sales 同一纪律）。同一来源同一
   * SKU 的登记区间不得重叠： DB 触发器会拒绝并报出冲突的那一段。
   */
  @PostMapping("/sales")
  public SafetyStockSalesSaved recordSales(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody SafetyStockSalesInput input) {
    return call(auth, "inventory.update", a -> safetyStock.recordSales(input, a.userId()));
  }

  /** 销量登记列表（按 SKU 聚合，真分页）—— 日均销量的来源可核对。 */
  @GetMapping("/sales")
  public SafetyStockSalesPage sales(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID skuId,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "inventory.read", a -> safetyStock.sales(skuId, keyword, page, size));
  }

  // ============================================================ 评估与预警

  /**
   * 发起一次评估：扫在效阈值 → 比可用量 → 生成预警 → 关联补货建议。
   *
   * <p>用 POST：它有副作用，不是查询。权限码是 update —— 「看一眼库存健不健康」在这里是写动作。
   *
   * <p>请求体带 SKU 清单及分类上下文（分类 → SKU 的展开在 catalog 服务，本服务跨库读不到）。 清单为空时只扫 SKU 档，其余档计入
   * policiesNotScannable 并在响应里如实报出。
   */
  @PostMapping("/evaluate")
  public SafetyStockEvaluationRow evaluate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody(required = false) SafetyStockEvaluationRequest request) {
    return call(auth, "inventory.update", a -> safetyStock.evaluate(request, a.userId()));
  }

  /** 评估批次列表（真分页）。零结果的评估也在列表里 —— 「扫了但没风险」要能回答。 */
  @GetMapping("/evaluations")
  public SafetyStockEvaluationPage evaluations(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "inventory.read", a -> safetyStock.evaluations(page, size));
  }

  /**
   * 预警列表（真分页，按提级后的严重度降序 —— 最该处理的排最前）。
   *
   * <p>{@code platformRiskOnly=true} 只看平台断货风险；默认不展示已处置的。
   */
  @GetMapping("/alerts")
  public SafetyStockAlertPage alerts(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID warehouseId,
      @RequestParam(required = false) UUID skuId,
      @RequestParam(required = false) String alertType,
      @RequestParam(required = false) String severity,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) UUID policyId,
      @RequestParam(required = false) UUID evaluationId,
      @RequestParam(required = false, defaultValue = "false") boolean platformRiskOnly,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "inventory.read",
        a ->
            safetyStock.alerts(
                new SafetyStockAlertQuery(
                    warehouseId,
                    skuId,
                    alertType,
                    severity,
                    status,
                    policyId,
                    evaluationId,
                    platformRiskOnly,
                    keyword,
                    page,
                    size)));
  }

  /** 预警详情（含关联的补货建议，可能为 null）。跨租户在 RLS 下查不到 → 404。 */
  @GetMapping("/alerts/{id}")
  public SafetyStockAlertDetail alert(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "inventory.read", a -> safetyStock.alert(id));
  }

  /** 认领一条预警（OPEN → 已认领）。 */
  @PostMapping("/alerts/{id}/acknowledge")
  public SafetyStockAlertActionResult acknowledgeAlert(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "inventory.update", a -> safetyStock.acknowledge(id, a.userId()));
  }

  /** 处置完成一条预警（已认领 → 已处置）。已处置的预警不允许复活。 */
  @PostMapping("/alerts/{id}/resolve")
  public SafetyStockAlertActionResult resolveAlert(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "inventory.update", a -> safetyStock.resolve(id));
  }

  // ============================================================ 补货建议

  /**
   * 补货建议列表（真分页）。
   *
   * <p>{@code unquantifiedOnly=true} 只看「算不出量」的那些 —— 它们最容易被忽略， 而拿 0 兜底会变成「不用补货」。
   */
  @GetMapping("/suggestions")
  public SafetyStockSuggestionPage suggestions(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID warehouseId,
      @RequestParam(required = false) UUID skuId,
      @RequestParam(required = false) String status,
      @RequestParam(required = false, defaultValue = "false") boolean unquantifiedOnly,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "inventory.read",
        a ->
            safetyStock.suggestions(
                new SafetyStockSuggestionQuery(
                    warehouseId, skuId, status, unquantifiedOnly, page, size)));
  }

  /** 采纳一条补货建议。6.2.1.2 未交付，采购单号留空等它回写。 */
  @PostMapping("/suggestions/{id}/acknowledge")
  public SafetyStockSuggestionAction acknowledgeSuggestion(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "inventory.update", a -> safetyStock.acknowledgeSuggestion(id));
  }

  /** 忽略一条补货建议。忽略必须留理由，否则会反复生成幽灵建议。 */
  @PostMapping("/suggestions/{id}/dismiss")
  public SafetyStockSuggestionAction dismissSuggestion(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam(required = false) String reason) {
    return call(
        auth, "inventory.update", a -> safetyStock.dismissSuggestion(id, reason, a.userId()));
  }

  // ============================================================ 工具

  private static OffsetDateTime moment(String value) {
    return InventoryController.moment(value);
  }

  /** 范围档的字符串解析；留空时按 SKU 档（最具体）解析，与 {@code /policies/effective} 的默认值一致。 */
  private static SafetyStockScopeType scope(String raw) {
    if (raw == null || raw.isBlank()) {
      return SafetyStockScopeType.SKU;
    }
    return SafetyStockScopeType.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
  }
}
