package com.starcon.channel;

import static com.starcon.channel.LinkageRiskModels.*;

import jakarta.validation.Valid;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * 16.5.2.2 关联风险。
 *
 * <p>蓝图：「识别账号关联风险：同一支付方式/收款账户/设备/IP/地址/法人关联的店铺，
 * 输出关联图谱与风险提示，提示避免触发平台关联处罚。输入：店铺与支付/设备/地址/法人关系数据；
 * 平台政策要求。输出：关联图谱 + 风险提示 + 整改建议。关系：上游 = 2 店铺、12.7.1 资金账户；
 * 下游 = 1 工作台预警。<b>关联封号是跨境多店经营的头号风险</b>：官方 API 授权是规避关联的关键，
 * 本系统只做官方 API，绝不提供任何模拟登录/指纹浏览器能力。」
 *
 * <p>🔴 端点清单（命名空间 {@code /channel/linkage-risk}，与既有的
 * {@code /channel/access-security}、{@code /channel/buyer-risk-scores}、
 * {@code /channel/pii-access} 等<b>并列而不重叠</b>）：
 *
 * <ul>
 *   <li>{@code GET /capabilities} —— 能力声明：逐维度 available + autoDiscoverable + ownerModule + 影响面。</li>
 *   <li>{@code GET /policy} / {@code PUT /policy} —— 关联风险策略 + 逐维度口径。
 *       <b>没登记过返回 configured=false</b>，而不是 404 —— 404 会让界面把「没登记」误读成「读失败」。</li>
 *   <li>{@code GET /shops} —— 图谱节点来源（真实店铺 + 法人绑定 + 已关联数）。</li>
 *   <li>{@code GET /identifiers} / {@code POST /identifiers} / {@code PUT|DELETE /identifiers/{id}} ——
 *       关联事实登记（<b>只有设备 / 网络 IP / 地址 / 其他能登记</b>）。</li>
 *   <li>{@code POST /identifiers/{id}/shops} / {@code DELETE /identifiers/{id}/shops/{shopId}} ——
 *       把店铺挂到共享因子上 / 解除。</li>
 *   <li>{@code GET /graph} —— 关联图谱（节点 + 边 + 关联组，<b>实算</b>）。</li>
 *   <li>{@code POST /scan} —— 🔴 <b>跑一次扫描</b>：真去读事实、真算图谱、真落库、真出提示与整改建议。
 *       <b>没有策略一律 422</b>（阈值必须由租户显式选）。</li>
 *   <li>{@code GET /alerts} / {@code GET /alerts/{id}} —— 风险提示（真分页 + 真筛选 + 处置留痕）。</li>
 *   <li>{@code POST /alerts/{id}/acknowledge} / {@code /resolve} / {@code /dismiss} ——
 *       处置状态机（必填理由）。</li>
 *   <li>{@code GET /remediations} / {@code PUT /remediations/{id}/status} —— 整改建议及其状态机。</li>
 * </ul>
 *
 * <p>🔴 <b>刻意没有的形状</b>：没有「删除风险提示」端点（处置走状态机，不是删除）、
 * 没有「删除处置留痕」端点（append-only）、<b>没有「模拟登录 / 指纹浏览器」端点</b>
 * （蓝图明令禁止的能力，一个都不提供）、<b>没有「资金账户 / 支付方式登记」端点</b>
 * （那归 12.7.1.1，未交付）—— <b>放行一个不存在的入口比 404 更糟</b>（界面会以为功能已交付）。
 *
 * <p>权限码<b>复用</b> channel 已有的 {@code channel.operation.*}（读 / 建 / 改 / 删），
 * <b>不新增任何权限码</b>。
 * 🔴 <b>读店铺必须用 {@code channel.operation.read}</b>：{@code shop} 表上有一条 RESTRICTIVE
 * 的数据范围策略（V1 的 {@code data_scope}），用别的权限码读它会**静默读到零行**。
 * 租户<b>不</b>来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 FORCE RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 */
@RestController
@RequestMapping("/channel/linkage-risk")
public class LinkageRiskController {

  private final AccessService access;
  private final LinkageRiskService service;

  public LinkageRiskController(AccessService access, LinkageRiskService service) {
    this.access = access;
    this.service = service;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, permission);
    return access.run(actor, permission, () -> work.apply(actor));
  }

  // ---- 能力声明 ----

  /** 能力声明：逐维度 available + autoDiscoverable + ownerModule + 影响面。 */
  @GetMapping("/capabilities")
  public LinkageRiskCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.capabilities());
  }

  // ---- 策略 ----

  /** 关联风险策略。没登记过返回 configured=false（**不预填任何默认阈值**）。 */
  @GetMapping("/policy")
  public LinkageRiskPolicyView policy(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.policy());
  }

  /** 登记 / 修改策略 + 逐维度口径（upsert，一租户一条）。阈值必须由租户显式给。 */
  @PutMapping("/policy")
  public LinkageRiskPolicyView savePolicy(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody LinkageRiskPolicyInput input) {
    return call(auth, "channel.operation.update", a -> service.savePolicy(input, a));
  }

  // ---- 店铺（节点来源） ----

  /** 店铺列表（真分页 + 真搜索）。法人名称**读不到**时如实标 available=false。 */
  @GetMapping("/shops")
  public LinkageRiskShopPage shops(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String keyword,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(
        auth, "channel.operation.read", a -> service.shops(auth, keyword, page, size));
  }

  // ---- 关联事实登记 ----

  /** 关联标识列表（真分页 + 真筛选）。 */
  @GetMapping("/identifiers")
  public LinkageRiskIdentifierPage identifiers(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String dimension,
      @RequestParam(required = false) String status,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(
        auth,
        "channel.operation.read",
        a -> service.identifiers(dimension, status, page, size));
  }

  /**
   * 登记一个关联标识（共享因子）。
   *
   * <p>🔴 只有**设备 / 网络 IP / 地址 / 其他**能登记：法人维度的事实在 {@code shop.legal_entity_id}，
   * 支付方式 / 收款账户维度归 12.7.1.1（未交付）。
   */
  @PostMapping("/identifiers")
  public LinkageRiskIdentifierView createIdentifier(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody LinkageRiskIdentifierInput input) {
    return call(auth, "channel.operation.create", a -> service.createIdentifier(input, a));
  }

  /** 修改一个关联标识（标签 / 状态 / 备注）。 */
  @PutMapping("/identifiers/{id}")
  public LinkageRiskIdentifierView updateIdentifier(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody LinkageRiskIdentifierUpdateInput input) {
    return call(auth, "channel.operation.update", a -> service.updateIdentifier(id, input, a));
  }

  /** 删除一个关联标识（连同它的边）。 */
  @DeleteMapping("/identifiers/{id}")
  public void deleteIdentifier(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    call(
        auth,
        "channel.operation.delete",
        a -> {
          service.deleteIdentifier(id);
          return null;
        });
  }

  /** 把一家店铺挂到某个标识上（登记一条关联事实）。 */
  @PostMapping("/identifiers/{id}/shops")
  public LinkageRiskIdentifierView linkShop(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody LinkageRiskShopLinkInput input) {
    return call(auth, "channel.operation.create", a -> service.linkShop(id, input, a));
  }

  /** 解除一条共享事实。 */
  @DeleteMapping("/identifiers/{id}/shops/{shopId}")
  public void unlinkShop(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @PathVariable String shopId) {
    call(
        auth,
        "channel.operation.delete",
        a -> {
          service.unlinkShop(id, shopId);
          return null;
        });
  }

  // ---- 关联图谱 ----

  /** 关联图谱（实算）。**没有策略 ⇒ known=false**，不是「算过了，没有关联」。 */
  @GetMapping("/graph")
  public LinkageRiskGraph graph(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.graph(auth));
  }

  /**
   * 🔴 跑一次关联扫描：真去读事实、真算图谱、真落库、真出提示与整改建议。
   *
   * <p>🔴 <b>没有策略一律 422</b>：阈值必须由租户显式选，系统不给默认值。
   */
  @PostMapping("/scan")
  public LinkageRiskScanResult scan(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.create", a -> service.scan(auth, a));
  }

  // ---- 风险提示 ----

  /** 风险提示列表（真分页 + 真筛选 + 汇总）。 */
  @GetMapping("/alerts")
  public LinkageRiskAlertPage alerts(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String severity,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(
        auth, "channel.operation.read", a -> service.alerts(status, severity, page, size));
  }

  /** 风险提示详情（含处置留痕与整改建议）。 */
  @GetMapping("/alerts/{id}")
  public LinkageRiskAlertView alert(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.read", a -> service.alert(id));
  }

  /** 确认提示（待处置 → 已确认）。 */
  @PostMapping("/alerts/{id}/acknowledge")
  public LinkageRiskAlertView acknowledgeAlert(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody LinkageRiskAlertActionInput input) {
    return call(
        auth,
        "channel.operation.update",
        a -> service.acknowledgeAlert(id, input.reason(), a));
  }

  /** 整改完成（待处置 / 已确认 → 已整改）。 */
  @PostMapping("/alerts/{id}/resolve")
  public LinkageRiskAlertView resolveAlert(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody LinkageRiskAlertActionInput input) {
    return call(
        auth, "channel.operation.update", a -> service.resolveAlert(id, input.reason(), a));
  }

  /** 忽略提示（待处置 / 已确认 → 已忽略）。 */
  @PostMapping("/alerts/{id}/dismiss")
  public LinkageRiskAlertView dismissAlert(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody LinkageRiskAlertActionInput input) {
    return call(
        auth, "channel.operation.update", a -> service.dismissAlert(id, input.reason(), a));
  }

  // ---- 整改建议 ----

  /** 整改建议列表（真分页 + 真筛选）。 */
  @GetMapping("/remediations")
  public LinkageRiskRemediationPage remediations(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String status,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(auth, "channel.operation.read", a -> service.remediations(status, page, size));
  }

  /** 处置一条整改建议（已采纳 / 已忽略 / 已完成）。 */
  @PutMapping("/remediations/{id}/status")
  public LinkageRiskRemediationView updateRemediation(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody LinkageRiskRemediationStatusInput input) {
    return call(
        auth, "channel.operation.update", a -> service.updateRemediation(id, input, a));
  }
}
