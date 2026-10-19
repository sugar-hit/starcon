package com.starcon.channel;

import static com.starcon.channel.ListingBulkEditModels.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.*;

/**
 * 4.2.1.2 批量编辑。
 *
 * <p>蓝图：「对一批 listing 执行统一的字段修改（追加/替换/正则替换标题、批量换图、统一加卖点、批量改类目），支持按平台差异化模板与操作预览（改前 vs 改后）。输入：目标
 * listing 集合；批量操作定义；预览确认。输出：批量修改草稿（待发布）；修改记录。关系：上游 = 4.1.1.1；下游 =
 * 4.3.1.2。<b>批量编辑只改草稿不直接改线上</b>，必须走发布流程。」端点按蓝图模块号标注，方便对照
 * 04-listing-and-content.md。
 *
 * <p>🔴 端点清单里**没有**任何一个「保存到线上 / 发布」的入口，这是刻意的：
 *
 * <ul>
 *   <li>{@code POST /preview} —— 「操作预览（改前 vs 改后）」。逐条真实 before/after，落库为
 *       {@code PREVIEWED}。<b>不改线上、也不改草稿</b>，可以随便点。
 *   <li>{@code POST /confirm} —— 「预览确认」。凭 operationId 推进，确认的就是刚才预览的那一份，
 *       产出是**草稿**（{@code listing_content_draft}）。
 *   <li>{@code GET /drafts} —— 「输出：批量修改草稿（待发布）」。待发布清单，可与线上并排看。
 *   <li>{@code GET /operations} —— 「输出：修改记录」。谁在什么时候对哪一批做了什么、改前改后各是什么。
 *   <li>{@code GET|POST /templates} —— 「按平台差异化模板」。
 * </ul>
 *
 * <p>权限码<b>复用</b> identity 已有的 {@code channel.operation.*}（与 4.1.1.1 listing 列表、
 * 10.1.1.2 渠道配置同族），identity 服务零改动。读操作用 read，写操作用 create / update。
 */
@RestController
@RequestMapping("/channel/listing-bulk-edit")
public class ListingBulkEditController {

  private final AccessService access;
  private final ListingBulkEditService service;

  public ListingBulkEditController(AccessService access, ListingBulkEditService service) {
    this.access = access;
    this.service = service;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, permission);
    return access.run(actor, permission, () -> work.apply(actor));
  }

  // ---- 能力声明 ----

  /**
   * 能力声明：卖点 / 图片的**线上**基线 available=false + ownerModule=4.2.1.1；
   * publish 维度 available=false + ownerModule=4.3.1.2。
   *
   * <p>前端据此禁用任何「直接上线」按钮，并对未接入的基线显示说明文字，<b>不编造线上原值</b>。
   */
  @GetMapping("/capabilities")
  public BulkEditCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.capabilities());
  }

  // ---- 平台差异化模板 ----

  /** 模板列表（按平台维度筛选）。 */
  @GetMapping("/templates")
  public List<BulkTemplateRow> templates(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String platformCode) {
    return call(auth, "channel.operation.read", a -> service.templates(platformCode));
  }

  /**
   * 新建 / 更新平台差异化模板（同平台同 code 幂等）。
   *
   * <p>模板带的是<b>取值规则与平台上限</b>，不是一次性拷贝 —— 所以它可以长期复用，
   * 批量执行时才按目标集合的平台切分套用。
   */
  @PostMapping("/templates")
  public BulkTemplateRow createTemplate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody BulkTemplateRequest request) {
    return call(auth, "channel.operation.create", a -> service.createTemplate(request));
  }

  // ---- 预览与确认 ----

  /**
   * 🔴 操作预览（改前 vs 改后）。
   *
   * <p>对目标集合<b>真实逐条</b>计算 before/after，并把结果落成 {@code PREVIEWED} 的修改记录。
   * 目标集合里混了跨租户 / 不在数据范围内的 listing 时，自动按租户切分：可见的照常算，
   * 不可见的 id 在 {@code outOfScopeIds} 里逐个回显。
   */
  @PostMapping("/preview")
  public BulkEditPreviewResponse preview(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody BulkEditRequest request) {
    return call(auth, "channel.operation.create", a -> service.preview(request));
  }

  /**
   * 🔴 预览确认：把刚才预览的那一批 before/after 写进<b>草稿</b>。
   *
   * <p>线上 listing 的内容列在这次事务里由 DB 触发器拒写 —— 界面提供的只有「确认到草稿」，
   * 发布是 4.3.1.2 的另一条路径。
   */
  @PostMapping("/confirm")
  public ListingBulkEditOperation confirm(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody BulkEditConfirmRequest request) {
    return call(auth, "channel.operation.update", a -> service.confirm(request));
  }

  /** 正则预检：让用户在点确认之前就知道这个模式会被 ReDoS 防线拒掉。 */
  @PostMapping("/regex-check")
  public RegexSafetyVerdict regexCheck(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam String pattern) {
    return call(auth, "channel.operation.read", a -> service.checkRegex(pattern));
  }

  // ---- 修改记录 ----

  /** 修改记录列表（「输出：修改记录」）。 */
  @GetMapping("/operations")
  public List<ListingBulkEditOperation> operations(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "channel.operation.read", a -> service.operations(page, size));
  }

  /** 单次操作的详情，含逐条 before/after。跨租户与不存在返回逐字相同的 404。 */
  @GetMapping("/operations/{id}")
  public ListingBulkEditOperation operation(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.read", a -> service.load(id));
  }

  // ---- 草稿（待发布）----

  /** 待发布草稿清单（「输出：批量修改草稿（待发布）」），可与线上值并排看。 */
  @GetMapping("/drafts")
  public List<ListingContentDraftRow> drafts(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String state,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "channel.operation.read", a -> service.drafts(page, size, state));
  }

  /** 单条 listing 的待发布草稿。 */
  @GetMapping("/drafts/{listingId}")
  public ListingContentDraftRow draft(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String listingId) {
    return call(auth, "channel.operation.read", a -> service.draft(listingId));
  }

  /**
   * 废弃草稿：允许重来，但**不删除** —— 废弃这件事本身是可回溯的事实。
   *
   * <p>注意这**不是**回滚线上：线上从未被改过，所以没有线上可回滚的东西。
   */
  @PostMapping("/drafts/{listingId}/discard")
  public ListingContentDraftRow discardDraft(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String listingId) {
    return call(auth, "channel.operation.update", a -> service.discardDraft(listingId));
  }

  /** 目标集合的预览前置检查：哪些 id 不在数据范围内（供前端在勾选时就提示）。 */
  @GetMapping("/scope-check")
  public List<UUID> scopeCheck(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam List<UUID> listingIds) {
    return call(auth, "channel.operation.read", a -> service.scopeCheck(listingIds));
  }
}
