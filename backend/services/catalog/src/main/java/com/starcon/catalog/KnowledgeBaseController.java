package com.starcon.catalog;

import static com.starcon.catalog.KnowledgeBaseModels.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 11.4.2.2 客服知识库与话术：话术条目、多语言版本、平台规则版本挂钩、 待复核任务、引用记录与命中率统计。
 *
 * <p>路由挂在 {@code /catalog/kb} 之下，与 {@code /catalog/descriptions}（3.4.2.2
 * 商品侧文案）<b>并列而不合并</b>：那边是消费者在详情页读到的文案（工作流是 「批量填充 SKU」），这边是客服在会话窗口里一键引用的话术（工作流是
 * 「按买家的问题检索并记录使用效果」）。判据是<b>谁在什么时候读</b>，不是文本长短 —— 见 {@code V100} 文件头与 {@link KnowledgeBaseService}
 * 的类注释。
 *
 * <p>第一段是字面量 {@code kb}，与 {@code products/{uuid}}、{@code parties}、 {@code supplier-quotes}
 * 各自首段都不同，不存在路由冲突。
 *
 * <p>权限沿用素材域：读 {@code catalog.assets.read}，写 {@code catalog.assets.update} （identity V3 的 {@code
 * catalog.assets}「素材与翻译」词表）。不新造词表 —— 新词表要改 identity 服务的迁移，属于另一个服务的写入所有权。
 */
@RestController
@RequestMapping("/catalog/kb")
public class KnowledgeBaseController {
  private final AccessService access;
  private final KnowledgeBaseService kb;

  public KnowledgeBaseController(AccessService access, KnowledgeBaseService kb) {
    this.access = access;
    this.kb = kb;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "catalog." + permission);
    return access.run(actor, "catalog." + permission, () -> work.apply(actor));
  }

  // ------------------------------------------------------------ 概览与词表

  /** 知识库概览：存量 + 命中率 + 待复核待办量。 */
  @GetMapping("/overview")
  public KnowledgeBaseOverview overview(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "assets.read", a -> kb.overview());
  }

  /** 词表：平台、话术类型、引用结果、复核状态。与服务层同一份枚举。 */
  @GetMapping("/options")
  public KnowledgeBaseOptions options(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "assets.read", a -> kb.options());
  }

  // ------------------------------------------------------------ 话术条目

  /** 话术列表：分页 + 关键词（编码/标题/正文）+ 类型 + 启用状态 + 待复核筛选。 */
  @GetMapping("/entries")
  public KnowledgeEntryPage entries(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String entryType,
      @RequestParam(required = false) String enabled,
      @RequestParam(required = false) String reviewState,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(
        auth,
        "assets.read",
        a -> kb.listEntries(keyword, entryType, enabled, reviewState, page, size));
  }

  /** 单条话术详情：条目 + 译文 + 挂钩的规则版本 + 命中率 + 最近引用。 */
  @GetMapping("/entries/{id}")
  public KnowledgeEntryDetail entry(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "assets.read", a -> kb.detail(id));
  }

  /** 建话术或改话术（id 为空即新建）。编码建后不可改。 */
  @PostMapping("/entries")
  public KnowledgeSaved createEntry(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody KnowledgeEntryInput input) {
    return call(auth, "assets.update", a -> kb.saveEntry(a, null, input));
  }

  @PutMapping("/entries/{id}")
  public KnowledgeSaved updateEntry(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody KnowledgeEntryInput input) {
    return call(auth, "assets.update", a -> kb.saveEntry(a, id, input));
  }

  @PutMapping("/entries/{id}/enabled")
  public KnowledgeSaved setEntryEnabled(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam boolean enabled) {
    return call(auth, "assets.update", a -> kb.setEntryEnabled(a, id, enabled));
  }

  /** 删话术。仍被引用或挂有待复核任务时返回 409 并说明原因——请改用停用。 */
  @DeleteMapping("/entries/{id}")
  public ResponseEntity<DeletedResponse> deleteEntry(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam long version) {
    call(
        auth,
        "assets.update",
        a -> {
          kb.deleteEntry(a, id, version);
          return null;
        });
    return ResponseEntity.ok(new DeletedResponse(true));
  }

  // ------------------------------------------------------------ 多语言版本

  @GetMapping("/entries/{id}/translations")
  public List<EntryTranslation> translations(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "assets.read", a -> kb.translations(id));
  }

  /**
   * 译文完整度：这几种语言里哪些能直接发给买家。
   *
   * <p>query 传语言列表而不是请求体：纯只读的 GET 查询，且语言列表短。
   */
  @GetMapping("/entries/{id}/translation-coverage")
  public EntryTranslationCoverage translationCoverage(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam List<String> locale) {
    return call(auth, "assets.read", a -> kb.translationCoverage(id, locale));
  }

  /** 建译文或改译文（translationId 为空即新建，按条目 + 语言定位）。 */
  @PostMapping("/entries/{entryId}/translations")
  public EntryTranslation createTranslation(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID entryId,
      @Valid @RequestBody EntryTranslationInput input) {
    return call(auth, "assets.update", a -> kb.saveTranslation(a, entryId, null, input));
  }

  @PutMapping("/entries/{entryId}/translations/{translationId}")
  public EntryTranslation updateTranslation(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID entryId,
      @PathVariable UUID translationId,
      @Valid @RequestBody EntryTranslationInput input) {
    return call(auth, "assets.update", a -> kb.saveTranslation(a, entryId, translationId, input));
  }

  /** 删译文。仍被引用记录引用时返回 409——历史答复的溯源不能断。 */
  @DeleteMapping("/translations/{translationId}")
  public ResponseEntity<DeletedResponse> deleteTranslation(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID translationId,
      @RequestParam long version) {
    call(
        auth,
        "assets.update",
        a -> {
          kb.deleteTranslation(a, translationId, version);
          return null;
        });
    return ResponseEntity.ok(new DeletedResponse(true));
  }

  // ------------------------------------------------------------ 平台规则版本

  /** 规则版本列表：分页 + 关键词 + 平台 + 状态。 */
  @GetMapping("/rule-versions")
  public RuleVersionPage ruleVersions(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String platform,
      @RequestParam(required = false) String status,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(
        auth, "assets.read", a -> kb.listRuleVersions(keyword, platform, status, page, size));
  }

  /** 建规则版本（草稿）。草稿不参与挂钩判定。 */
  @PostMapping("/rule-versions")
  public ResponseEntity<KnowledgeSaved> createRuleVersion(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody RuleVersionInput input) {
    var saved = call(auth, "assets.update", a -> kb.createRuleVersion(a, input));
    return ResponseEntity.status(HttpStatus.CREATED).body(saved);
  }

  /**
   * 改规则版本（含发布生效与标记接替）。
   *
   * <p>标记为 {@code SUPERSEDED} 时，V100 的触发器会<b>自动</b>给挂在它上面的每条话术 生成一条待复核任务 ——
   * 蓝图「平台政策变化时能定位哪些话术需要更新」的落地点。
   */
  @PutMapping("/rule-versions/{id}")
  public KnowledgeSaved updateRuleVersion(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam(required = false) String status,
      @Valid @RequestBody RuleVersionInput input) {
    return call(auth, "assets.update", a -> kb.updateRuleVersion(a, id, input, status));
  }

  /** 删规则版本。被话术或待复核任务引用时返回 409——它是政策存档。 */
  @DeleteMapping("/rule-versions/{id}")
  public ResponseEntity<DeletedResponse> deleteRuleVersion(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    call(
        auth,
        "assets.update",
        a -> {
          kb.deleteRuleVersion(a, id);
          return null;
        });
    return ResponseEntity.ok(new DeletedResponse(true));
  }

  // ------------------------------------------------------------ 挂钩

  @GetMapping("/entries/{id}/rule-bindings")
  public List<EntryRuleBinding> bindings(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "assets.read", a -> kb.bindingsOf(id));
  }

  /** 把话术挂到某版平台规则上。一条话术可挂多条规则。 */
  @PostMapping("/entries/{entryId}/rule-bindings")
  public ResponseEntity<EntryRuleBinding> bindEntry(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID entryId,
      @Valid @RequestBody RuleBindingInput input) {
    var binding = call(auth, "assets.update", a -> kb.bindEntry(a, entryId, input));
    return ResponseEntity.status(HttpStatus.CREATED).body(binding);
  }

  @DeleteMapping("/entries/{entryId}/rule-bindings/{bindingId}")
  public ResponseEntity<DeletedResponse> unbindEntry(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID entryId,
      @PathVariable UUID bindingId) {
    call(
        auth,
        "assets.update",
        a -> {
          kb.unbindEntry(a, entryId, bindingId);
          return null;
        });
    return ResponseEntity.ok(new DeletedResponse(true));
  }

  // ------------------------------------------------------------ 待复核任务

  /**
   * 待复核任务列表 —— 蓝图「平台政策变化时能定位哪些话术需要更新」的读出口。
   *
   * <p>每行自带接替它的那一版（{@code supersedingRuleLabel}）与两版的变更摘要， 客服不用再去翻规则历史才知道该比对什么。
   */
  @GetMapping("/review-tasks")
  public ReviewTaskPage reviewTasks(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String reviewStatus,
      @RequestParam(required = false) String platform,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(
        auth, "assets.read", a -> kb.listReviewTasks(keyword, reviewStatus, platform, page, size));
  }

  /** 认领（PENDING→REVIEWING）或完成（→DONE）一条复核任务。 */
  @PutMapping("/review-tasks/{taskId}")
  public ReviewTaskRow updateReviewTask(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID taskId,
      @Valid @RequestBody ReviewTaskUpdateInput input) {
    return call(auth, "assets.update", a -> kb.updateReviewTask(a, taskId, input));
  }

  // ------------------------------------------------------------ 引用与统计

  /**
   * 记一次引用（客服一键引用话术）。
   *
   * <p>真的插一行 {@code kb_usage}：命中率的全部可信度来自明细行是唯一真相， 累计列会与明细分叉。引用记录<b>没有</b>改删端点（append-only，DB 层触发器
   * + REVOKE 双重兜底）。
   */
  @PostMapping("/entries/{id}/usages")
  public ResponseEntity<KbUsageRow> recordUsage(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody UsageInput input) {
    var usage = call(auth, "assets.update", a -> kb.recordUsage(a, id, input));
    return ResponseEntity.status(HttpStatus.CREATED).body(usage);
  }

  /** 引用记录列表：分页 + 关键词（会话引用/备注/话术编码）+ 结果类型 + 话术。 */
  @GetMapping("/usages")
  public KbUsagePage usages(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String outcome,
      @RequestParam(required = false) UUID entryId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(auth, "assets.read", a -> kb.listUsages(keyword, outcome, entryId, page, size));
  }

  /** 某条话术的使用历史。 */
  @GetMapping("/entries/{id}/usages")
  public List<KbUsageRow> entryUsages(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(auth, "assets.read", a -> kb.usagesOf(id, page, size));
  }

  /**
   * 命中率排行榜 —— 蓝图输出「命中率统计」的可查面。
   *
   * <p>默认按被引用次数倒序：先看哪些话术真的在用，再看命中率。 按命中率排会把「只被用过 1 次且那 1 次成功」的话术顶到最上面，那不是运营要的行动顺序。
   */
  @GetMapping("/hit-rates")
  public List<EntryHitRateRow> hitRates(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String sort,
      @RequestParam(defaultValue = "50") int limit) {
    return call(auth, "assets.read", a -> kb.hitRates(sort, limit));
  }
}
