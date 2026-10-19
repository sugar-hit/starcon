package com.starcon.channel;

import static com.starcon.channel.ListingMigrationModels.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.*;

/**
 * 4.4.2.2 批量搬迁。
 *
 * <p>蓝图：「批量把一批 listing 从一个店铺搬到多个店铺，支持按规则筛选（类目、销量区间、价格区间），并对重复铺货风险给出提示。输入：
 * 筛选规则；目标店铺集合；重复度控制参数。输出：批量搬迁任务 + 结果明细；重复度风险提示。关系：上游 = 4.4.2.1；下游 = 4.3.1.2。
 * <b>搬迁后必须做标题差异化</b>，否则平台可能判定重复铺货并抑制 listing。」
 *
 * <p>端点按职责句逐项对应：
 *
 * <ul>
 *   <li>{@code GET /capabilities} —— 逐维度能力声明（销量 / 内部可售量 / 平台审核 / 内部内容草稿 /
 *       跨平台搬迁 available=false + ownerModule + impact），以及差异化与幂等两条保证的界面措辞。
 *   <li>{@code GET /filter-options} —— 筛选面板的选项（源店铺 + 源店铺下**真实存在**的类目）。
 *   <li>{@code POST /preview} —— 🔴 预览：筛选命中什么、每条的风险有多高、标题会怎么差异化
 *       （**不写任何数据**）。
 *   <li>{@code POST /} —— 🔴 执行搬迁：批量搬迁任务 + 结果明细 + 待发布任务（幂等）。
 *   <li>{@code GET /} / {@code GET /{id}} / {@code GET /{id}/items} —— 搬迁历史、批次详情、结果明细。
 *   <li>{@code GET /duplicate-risks} —— 「重复铺货风险」查询（分值 / 等级 / 判定依据）。
 *   <li>{@code GET /migrated} —— 「已搬迁」查询。**与上一条是两种事实、两种处置，不合并。**
 * </ul>
 *
 * <p>🔴 路径前缀是 {@code /channel/listing-migrations}：与 4.4.2.1 的 {@code /channel/listing-claims}
 * 并列但不同前缀 —— 「单条认领」与「批量搬迁」是两种操作，混在一个前缀下会让"已搬迁"与"已认领"
 * 在界面上分不清。
 *
 * <p>权限码<b>复用</b> identity 已有的 {@code channel.operation.*}（与 4.1.1.1 列表、4.2.1.2 批量编辑、
 * 4.3.1.2 批量发布、4.4.2.1 商品认领同族），identity 服务零改动。
 */
@RestController
@RequestMapping("/channel/listing-migrations")
public class ListingMigrationController {

  private final AccessService access;
  private final ListingMigrationService service;

  public ListingMigrationController(AccessService access, ListingMigrationService service) {
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
   * <p>前端据此显示「销量维度未接入」「价格取的是平台侧在售价快照，不是 05 定价的权威售价」，
   * <b>不把未交付的维度画成已具备</b>。
   */
  @GetMapping("/capabilities")
  public ListingMigrationCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.capabilities());
  }

  // ---- 筛选选项 ----

  /** 筛选面板的选项：可作为源店铺的店铺 + 源店铺下真实存在的类目（按 listing 条数倒序）。 */
  @GetMapping("/filter-options")
  public MigrationFilterOptions filterOptions(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID sourceShopId) {
    return call(auth, "channel.operation.read", a -> service.filterOptions(sourceShopId));
  }

  // ---- 预览 ----

  /**
   * 🔴 预览：筛选命中什么、每条到每个目标店铺的重复铺货风险、以及标题会怎么差异化。
   *
   * <p>**不写任何数据** —— 预览只算，不落库。
   */
  @PostMapping("/preview")
  public ListingMigrationPreview preview(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody MigrationPreviewRequest request) {
    return call(
        auth,
        "channel.operation.read",
        a -> service.preview(request.filter(), request.targets()));
  }

  // ---- 执行 ----

  /**
   * 🔴 批量搬迁：把筛选命中的源商品搬到目标店铺集合。
   *
   * <p>产出：批量搬迁任务 + 结果明细 + 逐条差异化留痕 + 逐条重复度评估 + 待发布任务
   * （复用 4.3.1.2 的 V183 队列）。
   *
   * <p>🔴 幂等：同一个幂等键重复触发只会得到同一个批次，不产生第二条搬迁结果。
   */
  @PostMapping
  public ListingMigrationBatchRow execute(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody MigrationExecuteRequest request) {
    return call(auth, "channel.operation.create", a -> service.execute(request, a.userId()));
  }

  // ---- 读取 ----

  /** 搬迁历史（真分页 + 状态与源店铺筛选）。 */
  @GetMapping
  public List<ListingMigrationBatchRow> batches(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String state,
      @RequestParam(required = false) String sourceShopCode,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth, "channel.operation.read", a -> service.listBatches(state, sourceShopCode, page, size));
  }

  /** 单个批次的详情。 */
  @GetMapping("/{id}")
  public ListingMigrationBatchRow batch(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.read", a -> service.batch(id));
  }

  /** 批次的结果明细（带逐条差异化留痕与重复度依据）。 */
  @GetMapping("/{id}/items")
  public List<ListingMigrationItemRow> items(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @RequestParam(required = false) String state,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "channel.operation.read", a -> service.items(id, state, page, size));
  }

  // ---- 重复铺货风险（与「已搬迁」分开） ----

  /** 「重复铺货风险」查询：分值、等级、判定依据、最像的那条既有 listing。 */
  @GetMapping("/duplicate-risks")
  public List<ListingMigrationDuplicateRow> duplicateRisks(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String level,
      @RequestParam(required = false) UUID targetShopId,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth, "channel.operation.read", a -> service.duplicateRisks(level, targetShopId, page, size));
  }

  /** 「已搬迁」查询：哪些源商品已经搬进哪些店铺了。 */
  @GetMapping("/migrated")
  public List<ListingMigrationMigratedRow> migrated(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID sourceListingId,
      @RequestParam(required = false) UUID targetShopId,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth, "channel.operation.read", a -> service.migrated(sourceListingId, targetShopId, page, size));
  }
}
