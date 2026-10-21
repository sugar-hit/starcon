package com.starcon.channel;

import static com.starcon.channel.ListingCollectBoxModels.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.function.Function;
import org.springframework.web.bind.annotation.*;

/**
 * 4.4.1.3 采集箱。
 *
 * <p>蓝图：「采集结果的暂存区，按平台/来源/标签分组，支持勾选后批量认领为内部商品（生成 SPU/SKU 草稿并预填内容）、批量删除、批量打标。输入：勾选操作；认领规则（目标分类、品牌映射、命名规则）。输出：认领后的商品草稿；采集箱状态更新。关系：上游 = 4.4.1.1/4.4.1.2；下游 = 3.1.1.1 商品建档、4.2.1.1 编辑器。<b>认领时必须做品牌与分类映射</b>，否则采集来的商品归属混乱，后续无法按品牌做合规与绩效分析。」
 *
 * <p>端点按职责句逐项对应：
 *
 * <ul>
 *   <li>{@code GET /capabilities} —— 逐维度能力声明（未交付段 available=false + ownerModule + impact）。
 *   <li>{@code GET /items} —— 采集箱条目（真分页 + 平台/来源、标签、认领状态、关键字筛选）。
 *   <li>{@code GET /items/{id}} —— 单个条目详情。
 *   <li>{@code GET /groups} —— 分组统计（按平台/来源、按标签、按认领状态）。
 *   <li>{@code GET /candidates} —— 还没进箱的采集草稿（4.4.1.1 / 4.4.1.2 的产物）。
 *   <li>{@code POST /items} —— 收录：把选中的采集草稿放进采集箱。
 *   <li>{@code POST /items/claim} —— 🔴 批量认领（品牌与分类映射 + 命名规则 ⇒ 商品草稿载荷）。
 *   <li>{@code POST /items/tags} —— 批量打标（挂 / 摘）。
 *   <li>{@code POST /items/remove} —— 批量删除（移出采集箱）。
 *   <li>{@code GET|POST /tags}、{@code DELETE /tags/{id}} —— 标签词表。
 *   <li>{@code GET|POST /rules}、{@code DELETE /rules/{id}} —— 认领规则（目标分类 + 品牌映射 + 命名规则）。
 * </ul>
 *
 * <p>🔴 路径前缀刻意是 {@code /channel/listing-collect-box}：
 * <b>首 token 与既有白名单里的每一条都不同</b>（既有的是 {@code listing-collects}（4.4.1.1）、
 * {@code listing-bulk-collects}（4.4.1.2）、{@code listing-keyword-rankings}（4.5.1.2）、
 * {@code listing-hijackers}（4.5.2.1）、{@code listing-claims}、{@code listing-compliance-precheck}、
 * {@code listing-performance}）—— 白名单里的正则都是**锚定**的（{@code /^token(...)?$/}），
 * 首 token 不同 ⇒ 互不覆盖。
 *
 * <p>权限码<b>复用</b> identity 已有的 {@code channel.operation.*}（与 4.1.1.1 列表、4.4.1.1 链接采集、
 * 4.4.1.2 批量采集、4.4.2.1 商品认领同族），identity 服务零改动。
 */
@RestController
@RequestMapping("/channel/listing-collect-box")
public class ListingCollectBoxController {

  private final AccessService access;
  private final ListingCollectBoxService service;

  public ListingCollectBoxController(AccessService access, ListingCollectBoxService service) {
    this.access = access;
    this.service = service;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, permission);
    return access.run(actor, permission, () -> work.apply(actor));
  }

  // ---- 能力声明 ----

  /**
   * 能力声明：未交付的维度 available=false + ownerModule + impact。
   *
   * <p>前端据此显示「认领只产出草稿」「商品草稿落库归 03 建档」「图片只透传 URL」，
   * <b>不把未交付的步骤画成已完成</b>。
   */
  @GetMapping("/capabilities")
  public ListingCollectBoxCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.capabilities());
  }

  // ---- 读取 ----

  /** 采集箱条目（真分页 + 平台/来源、标签、认领状态、关键字筛选）。 */
  @GetMapping("/items")
  public ListingCollectBoxPage items(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String sourceCode,
      @RequestParam(required = false) String tagId,
      @RequestParam(required = false) String state,
      @RequestParam(required = false) String q,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "channel.operation.read",
        a -> service.listItems(sourceCode, tagId, state, q, page, size));
  }

  /** 单个采集箱条目详情。 */
  @GetMapping("/items/{id}")
  public ListingCollectBoxItemRow item(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.read", a -> service.itemDetail(id));
  }

  /** 分组统计（按平台/来源、按标签、按认领状态）。 */
  @GetMapping("/groups")
  public ListingCollectBoxGroups groups(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.groups());
  }

  /** 还没进箱的采集草稿（收录时的候选来源）。 */
  @GetMapping("/candidates")
  public ListingCollectBoxCandidatePage candidates(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String sourceCode,
      @RequestParam(required = false) String q,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "channel.operation.read", a -> service.candidates(sourceCode, q, page, size));
  }

  // ---- 收录 ----

  /** 收录：把选中的采集草稿放进采集箱（一份草稿最多入箱一次）。 */
  @PostMapping("/items")
  public ListingCollectBoxEnrollResult enroll(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody EnrollBoxItemsRequest request) {
    return call(auth, "channel.operation.create", a -> service.enroll(request.draftIds()));
  }

  // ---- 批量认领 ----

  /**
   * 🔴 批量认领：按认领规则生成商品草稿载荷。
   *
   * <p>映射不过的那一条**认领失败**并写明原因（品牌没映射 / 类目没映射 / 命名规则取不到值），
   * 不会悄悄认领成一个无品牌无分类的商品。
   */
  @PostMapping("/items/claim")
  public ListingCollectBoxClaimResult claim(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody ClaimBoxItemsRequest request) {
    return call(
        auth,
        "channel.operation.update",
        a -> service.claim(request.itemIds(), request.ruleId().toString()));
  }

  // ---- 批量打标 ----

  /** 批量打标：挂标签（ADD）或摘标签（REMOVE）。 */
  @PostMapping("/items/tags")
  public ListingCollectBoxTagResult tag(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody TagBoxItemsRequest request) {
    return call(auth, "channel.operation.update", a -> service.tag(request));
  }

  // ---- 批量删除 ----

  /** 批量删除：把选中的条目移出采集箱。 */
  @PostMapping("/items/remove")
  public ListingCollectBoxRemoveResult remove(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody RemoveBoxItemsRequest request) {
    return call(auth, "channel.operation.delete", a -> service.remove(request));
  }

  // ---- 标签词表 ----

  /** 标签列表（带每个标签挂着的条目数）。 */
  @GetMapping("/tags")
  public List<ListingCollectBoxTagRow> tags(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.listTags());
  }

  /** 建标签。 */
  @PostMapping("/tags")
  public ListingCollectBoxTagRow createTag(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody CreateBoxTagRequest request) {
    return call(auth, "channel.operation.create", a -> service.createTag(request));
  }

  /** 删标签（连带摘掉所有条目上的这个标签）。 */
  @DeleteMapping("/tags/{id}")
  public void deleteTag(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    call(
        auth,
        "channel.operation.delete",
        a -> {
          service.deleteTag(id);
          return null;
        });
  }

  // ---- 认领规则 ----

  /** 认领规则列表（真分页）。 */
  @GetMapping("/rules")
  public ListingCollectBoxClaimRulePage rules(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "channel.operation.read", a -> service.listRules(page, size));
  }

  /**
   * 新建认领规则。
   *
   * <p>🔴 <b>没有品牌映射就没有规则</b>：蓝图加粗句「认领时必须做品牌与分类映射」——
   * 服务层直接 400 拒绝，DB 上还有一道 CHECK。
   */
  @PostMapping("/rules")
  public ListingCollectBoxClaimRuleRow createRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody CreateClaimRuleRequest request) {
    return call(auth, "channel.operation.create", a -> service.createRule(request));
  }

  /** 删认领规则（已被认领结果引用的规则不许删）。 */
  @DeleteMapping("/rules/{id}")
  public void deleteRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    call(
        auth,
        "channel.operation.delete",
        a -> {
          service.deleteRule(id);
          return null;
        });
  }
}
