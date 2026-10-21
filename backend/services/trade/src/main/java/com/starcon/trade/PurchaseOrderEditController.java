package com.starcon.trade;

import static com.starcon.trade.PurchaseOrderEditModels.*;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 6.3.1.2 采购单创建与编辑（卷 06 采购与供应 / L3 6.3.1 采购单的第二格）。
 *
 * <p><b>命名空间是 {@code /trade/purchase-order-editor}</b>：6.3.1.1（采购单列表）已经占了
 * {@code /trade/purchase-orders}，而本模块是它的<b>兄弟</b>（同一个 L3 下的两个 L4）。
 * 两者各自成一个命名空间，是为了让<b>白名单能各自登记</b>：
 * {@code apps/web/src/lib/api-proxy.ts} 是多车道共用的累积文件，
 * 每条车道往同一个超长正则里并分支会让 git 必然判冲突（结构性的，retry 不会自愈），
 * 所以本模块走「新增一条 services[] 元素」这条路，首段是<b>独立字面量</b>
 * {@code purchase-order-editor}，与 {@code purchase-orders} 不会互相吃掉。
 *
 * <p><b>租户绝不来自任何请求头</b>：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 *
 * <p><b>数据范围由服务端从角色授权推导</b>（ALL / DEPARTMENT / SELF），
 * <b>不接受请求参数传入</b> —— 否则它会变成一个能越权的开关
 * （照 6.3.1.1 与 9.1.1.1 的既有做法）。
 *
 * <p><b>权限码复用 trade 既有体系</b>：{@code trade.purchase.read} / {@code trade.purchase.update}
 * —— 与 6.3.1.1、6.3.2.2 同一套，identity 零改动。
 */
@RestController
@RequestMapping("/trade/purchase-order-editor")
public class PurchaseOrderEditController {

  private final AccessService access;
  private final PurchaseOrderEditService editor;

  public PurchaseOrderEditController(AccessService access, PurchaseOrderEditService editor) {
    this.access = access;
    this.editor = editor;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, Function<AccessService.Access, T> work) {
    AccessService.Access actor = access.require(bearer, PurchaseOrderEditService.READ_PERMISSION);
    return access.run(actor, PurchaseOrderEditService.READ_PERMISSION, () -> work.apply(actor));
  }

  /** 写入口：同样先确认身份与权限（写权限码要求同一个 grant 里同时有 read）。 */
  private <T> T write(String bearer, Function<AccessService.Access, T> work) {
    AccessService.Access actor = access.require(bearer, PurchaseOrderEditService.UPDATE_PERMISSION);
    return access.run(actor, PurchaseOrderEditService.UPDATE_PERMISSION, () -> work.apply(actor));
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：逐条说清本模块能做什么、哪些归别的模块（6.3.1.3 审批 / 12 财务应付与税额）。
   *
   * <p>让界面能说清「为什么没有审批结论、为什么没有税额」，
   * 而不是让它们显示成空白让人以为「这单没有这些值」。
   */
  @GetMapping("/capabilities")
  public PurchaseOrderEditCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, actor -> editor.capabilities());
  }

  // ================================================================ 建单参照

  /** 建单参照（供应商 / 目标仓 / 法人 / 币种）：四样都是真实数据，不是硬编码词表。 */
  @GetMapping("/options")
  public PurchaseOrderEditOptions options(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, actor -> editor.options(auth));
  }

  // ================================================================ 带出报价与库存

  /**
   * 带出报价与库存快照（建单表单预览；<b>只读，无副作用</b>）。
   *
   * <p>🔴 歧义报价**不替用户挑**：并列的候选逐条返回，{@code selected} 为 null。
   */
  @PostMapping("/quote-preview")
  public PurchaseOrderEditQuotePreview quotePreview(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody PurchaseOrderEditQuotePreviewRequest request) {
    return call(auth, actor -> editor.quotePreview(request, auth));
  }

  // ================================================================ 新建草稿

  /** 新建采购单（草稿；{@code source_type=MANUAL}）。 */
  @PostMapping("/orders")
  public PurchaseOrderEditForm create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody PurchaseOrderEditCreateRequest request) {
    return write(
        auth,
        actor -> editor.create(request, actor.userId(), actor.departmentId(), auth));
  }

  // ================================================================ 读 / 改 / 提交

  /** 读一张采购单的编辑表单（头 + 行 + 冻结快照 + 地址 + 附件 + 金额结果）。 */
  @GetMapping("/orders/{id}")
  public PurchaseOrderEditForm form(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(
        auth,
        actor -> editor.form(id, actor.userId(), actor.departmentId(), scopeMode(actor), auth));
  }

  /**
   * 编辑保存（<b>只有草稿能改</b>；带 {@code expected_version} 乐观锁）。
   *
   * <p>表单是<b>整体替换</b>语义：少传一行 = 删掉那一行。
   */
  @PutMapping("/orders/{id}")
  public PurchaseOrderEditForm update(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody PurchaseOrderEditUpdateRequest request) {
    return write(
        auth,
        actor ->
            editor.update(
                id,
                request,
                actor.userId(),
                actor.departmentId(),
                scopeMode(actor, PurchaseOrderEditService.UPDATE_PERMISSION),
                auth));
  }

  /**
   * 提交（草稿 → 待审批）：<b>冻结单价、汇率与单位换算快照</b>就在这一刻。
   *
   * <p>🔴 不审批：6.3.1.3 未交付，本模块只把单推到待审批。
   */
  @PostMapping("/orders/{id}/submit")
  public PurchaseOrderEditForm submit(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody PurchaseOrderEditSubmitRequest request) {
    return write(
        auth,
        actor ->
            editor.submit(
                id,
                request,
                actor.userId(),
                actor.departmentId(),
                scopeMode(actor, PurchaseOrderEditService.UPDATE_PERMISSION),
                auth));
  }

  // ================================================================ 由 6.2.2.2 草稿建单

  /**
   * 由 6.2.2.2 的**采购单草稿**（合并草稿）建真正的采购单，并把草稿置为 CONVERTED。
   *
   * <p>被拒的草稿逐条带原因码返回（<b>不静默丢弃</b>：丢掉的草稿等于少订的货）。
   */
  @PostMapping("/from-drafts")
  public PurchaseOrderEditFromDraftResult fromDrafts(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody PurchaseOrderEditFromDraftRequest request) {
    return write(
        auth,
        actor -> editor.fromDrafts(request, actor.userId(), actor.departmentId(), auth));
  }

  // ================================================================ 数据范围

  /**
   * 数据范围：ALL / DEPARTMENT / SELF。
   *
   * <p>取值规则与 {@code AccessService.require} 同一套授权：<b>命中该权限的授权里</b>
   * 只要有一条 ALL 就按 ALL；否则有 DEPARTMENT 且当前成员有部门就按 DEPARTMENT；
   * 再否则按 SELF 收窄到本人负责的采购单。
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
      return PurchaseOrderEditService.SCOPE_ALL;
    }
    boolean department =
        grants.stream()
            .anyMatch(
                g -> "DEPARTMENT".equals(g.scopeMode()) && g.permissions().contains(permission));
    if (department && a.departmentId() != null) {
      return PurchaseOrderEditService.SCOPE_DEPARTMENT;
    }
    return PurchaseOrderEditService.SCOPE_SELF;
  }

  /** 读路径的数据范围。 */
  static String scopeMode(AccessService.Access a) {
    return scopeMode(a, PurchaseOrderEditService.READ_PERMISSION);
  }
}
