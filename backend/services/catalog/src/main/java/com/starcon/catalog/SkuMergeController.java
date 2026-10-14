package com.starcon.catalog;

import static com.starcon.catalog.SkuMergeModels.*;

import jakarta.validation.Valid;
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
 * 3.9.1.2 SKU 合并的端点。
 *
 * <p>蓝图：「执行 SKU/SPU 合并：把重复记录归并到主记录，迁移关联……原记录标记合并并停用而非物理删除； 可回溯合并来源。」「输入：合并决定（主记录、被合并记录）；{@code
 * expected_version}。」 「输出：合并结果；引用迁移报告；原记录合并标记。」端点按这条链路排： <b>看治理概览 → 看待执行的合并 → 预演 → 执行 → 回看某条 SKU
 * 的合并来源</b>。
 *
 * <pre>
 *   GET  /catalog/sku-merges/overview                        治理概览（已合并数 / 待执行决定 / 冲突与冻结引用）
 *   GET  /catalog/sku-merges                                 合并历史（分页，治理动作的审计视角）
 *   GET  /catalog/sku-merges/pending                         待执行的 MERGE 决定（登记了还没并的）
 *   GET  /catalog/duplicate-candidates/{id}/merge-preview     执行前推演（逐维度引用迁移报告，零写入）
 *   POST /catalog/duplicate-candidates/{id}/merge            执行合并（confirm=true 才写）
 *   GET  /catalog/sku-merges/by-candidate/{id}               按候选读回那次合并
 *   GET  /catalog/sku-merges/{id}                            单次合并详情（结果 + 报告 + 合并标记）
 *   GET  /catalog/sku-merges/by-sku/{id}                     某条 SKU 的合并血缘（可回溯合并来源）
 * </pre>
 *
 * <p><b>执行入口挂在候选下面</b>而不是挂在决定下面：本模块<b>不重新判断「像不像」</b>， 它执行的是 3.9.1.1
 * 登记在某条候选上的决定。挂在候选下能让页面上「就这一对执行合并」 与「先看这一对的证据」在同一个资源树上；而 {@code by-candidate} 那个出口让
 * 「这一对合并过了吗」在多次扫描之间也能查到。
 *
 * <p><b>权限沿用 {@code catalog.spu.read} / {@code catalog.spu.update}</b>，与 3.9.1.1 同一口径：
 * 合并读的是商品主数据、写的是商品主数据自己的派生数据，不引入新词表 （新词表要改 identity 服务的迁移，属于另一个服务的写入所有权）。 注意 V3 的 {@code
 * sku_read_scope}：以 {@code catalog.spu.read} 身份读 SKU 行时额外要求 {@code
 * catalog.sku.read}，所以合并页的只读角色要同时有这两个权限。
 *
 * <p><b>租户只来自已认证成员关系</b>：本控制器没有任何参数是 tenantId， 一律取 {@code AccessService.Access.tenantId()}。客户端传
 * {@code X-Tenant-Id} 不会被读， 带伪造头访问别人的候选/合并记录得到的是 404（RLS 已过滤跨租户的行）。
 */
@RestController
@RequestMapping("/catalog")
public class SkuMergeController {
  private final AccessService access;
  private final SkuMergeService merges;

  public SkuMergeController(AccessService access, SkuMergeService merges) {
    this.access = access;
    this.merges = merges;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "catalog." + permission);
    return access.run(actor, "catalog." + permission, () -> work.apply(actor));
  }

  /**
   * 治理概览。
   *
   * <p>没合并过时返回<b>空态而不是 404</b>：页面首屏要能直接渲染「还没有执行过合并」， 拿到 404 就得先区分「没合并过」与「无权访问」，而这两者对运营是同一件事（都是空的）。
   */
  @GetMapping("/sku-merges/overview")
  public SkuMergeOverview overview(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "spu.read", actor -> merges.overview());
  }

  /** 合并历史：每一次归并都是一条不可变的事实（按执行时间倒序，分页）。 */
  @GetMapping("/sku-merges")
  public SkuMergePage merges(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(auth, "spu.read", actor -> merges.list(page, size));
  }

  /**
   * 执行前推演（蓝图的「输入：合并决定；expected_version」在这里变成可核对的一屏）。
   *
   * <p><b>零写入</b>：它只跑 SELECT，所以运营可以在按确认之前看到 「会搬多少行、哪几行搬不动、哪些是刻意不动的历史」。 返回里的 {@code versionCheck} 就是
   * {@code expected_version} 的比对结论： 不是 {@code MATCHED} 时 {@code executable=false}，执行端点也会用同一套判定拒绝。
   */
  @GetMapping("/duplicate-candidates/{id}/merge-preview")
  public SkuMergePreview preview(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "spu.read", actor -> merges.preview(id));
  }

  /**
   * 执行合并。
   *
   * <p>请求体必须带 {@code confirm=true}：合并是本服务里少数会改写十几张表的操作， 缺省不确认时按预演处理（不写任何东西），这样「误调一次执行端点」的代价是零。
   *
   * <p>整个执行在一个事务里：12 个维度的引用迁移、被合并记录的停用与合并标记、 源商品的停产、合并记录与报告的落库要么全成要么全不成。
   */
  @PostMapping("/duplicate-candidates/{id}/merge")
  public SkuMergeRow merge(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody(required = false) SkuMergeInput input) {
    return call(
        auth,
        "spu.update",
        actor -> merges.execute(actor, id, input == null ? new SkuMergeInput(null, null) : input));
  }

  /** 按候选读回那次合并（页面上「这一对合并过了吗」，跨多次扫描也能查）。 */
  @GetMapping("/sku-merges/by-candidate/{id}")
  public SkuMergeRow byCandidate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "spu.read", actor -> merges.mergeByCandidate(id));
  }

  /** 单次合并详情：合并结果 + 逐维度引用迁移报告 + 原记录此刻的合并标记。 */
  @GetMapping("/sku-merges/{id}")
  public SkuMergeRow merge(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "spu.read", actor -> merges.mergeById(id));
  }

  /**
   * 某条 SKU 的合并血缘（蓝图的「可回溯合并来源」）。
   *
   * <p>双向：既答「这条被并进了哪一条」，也答「有哪几条被并进了它」。 只查前者的话，运营站在主记录上看不到「我这条底下归并了几条重复」，而那正是治理成果本身。
   */
  @GetMapping("/sku-merges/by-sku/{id}")
  public SkuMergeTrace bySku(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "spu.read", actor -> merges.trace(id));
  }
}
