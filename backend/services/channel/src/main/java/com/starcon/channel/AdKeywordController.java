package com.starcon.channel;

import static com.starcon.channel.AdKeywordModels.*;

import jakarta.validation.Valid;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.*;

/**
 * 14.1.3.1 关键词管理。
 *
 * <p>蓝图：「关键词管理与分析：搜索词报告、关键词表现（展示、点击、转化、CPC、ACOS）、否定词建议、词频与排名、批量调价与否定。输入：搜索词与关键词数据同步；批量操作参数。输出：关键词报表 + 否定建议 + 批量调整任务。关系：上游 = 平台搜索词报告；下游 = 14.2.1.1 竞价、4.5.1.2 排名。<b>搜索词报告是发现浪费的入口</b>：高曝光零转化词应尽快否定，否则预算持续流失。」
 *
 * <p>端点按蓝图模块号标注，端点清单里**每一组**都对应职责句里的一个词：
 *
 * <ul>
 *   <li>{@code GET /capabilities} —— 逐维度能力声明 + 指标口径 + 词频与排名口径 + 否定依据口径
 *       + **护栏的具体数值**。四段未交付（14.2.1.1 竞价策略引擎 / 14.1.3.2 投放定位 /
 *       14.1.1.1 活动列表 / 平台否定推送）都 available=false + ownerModule，
 *       前端据此显示「这归谁管」，而不是显示一个必然失败的按钮。
 *   <li>{@code GET /filter-options} —— 筛选面板选项（平台 / 店铺 / 外部活动 / 归一类型 / 币种）。
 *   <li>{@code GET /} + {@code POST /} + {@code PUT /{keywordId}} + {@code DELETE /{keywordId}} +
 *       {@code GET /{keywordId}} —— 🔴「关键词管理与分析」的主体：登记、查询、归档。
 *       归档是**软删除** —— 表现与否定历史必须留着。
 *   <li>{@code POST /metrics} —— 🔴「输入：搜索词与关键词数据同步」的**关键词侧真实写入口**：
 *       一批指标落库（带数据截至时间 + 批次幂等）。
 *   <li>{@code POST /search-terms} + {@code GET /search-terms} —— 🔴 搜索词报告的写入口与读端点。
 *   <li>{@code GET /negative-suggestions} —— 🔴「输出：否定建议」：**带依据数值**。
 *   <li>{@code GET /negatives} + {@code POST /negatives} + {@code PUT /negatives/{id}/result} ——
 *       🔴 否定记录（依据由服务端现算）+ 平台回执登记。
 *   <li>{@code POST /bulk-actions} + {@code GET /bulk-actions} + {@code GET /bulk-actions/{id}} ——
 *       🔴「批量调价与否定」：护栏 + **逐条执行结果**。
 *   <li>{@code GET /report} —— 🔴「输出：关键词报表」：真 SQL 聚合（按币种分行）。
 *   <li>{@code GET /frequency} —— 🔴「词频与排名」：口径**引用 4.5.1.2**。
 * </ul>
 *
 * <p>🔴 路径前缀是 {@code /channel/ad-keywords}：与 14.1.2.2 的 {@code /channel/ad-type-diffs}、
 * 4.5.1.2 的 {@code /channel/listing-keyword-rankings} 都是**独立前缀** ——
 * 「广告关键词投放」与「商品关键词排名」是两条独立通道（一个管花钱、一个管曝光位置），
 * 口径、限流与重试互不干涉。
 *
 * <p>权限码<b>复用</b> identity 已有的 {@code channel.operation.*}（与 14.1.2.2、4.5.1.2 同族），
 * identity 服务零改动。
 */
@RestController
@RequestMapping("/channel/ad-keywords")
public class AdKeywordController {

  private final AccessService access;
  private final AdKeywordService service;

  public AdKeywordController(AccessService access, AdKeywordService service) {
    this.access = access;
    this.service = service;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, permission);
    return access.run(actor, permission, () -> work.apply(actor));
  }

  // ---- 能力声明 ----

  /**
   * 能力声明：14.2.1.1 竞价策略引擎 / 14.1.3.2 投放定位 / 14.1.1.1 活动列表 /
   * 平台否定推送 四个维度 {@code available=false} + ownerModule。
   *
   * <p>响应里同时给出<b>指标口径</b>、<b>词频与排名的口径</b>、<b>否定依据的口径</b>与
   * <b>护栏的具体数值</b> —— 前端据此显示「这个 ACOS 怎么算的」「凭什么否定它」
   * 「一次最多能调多少」。
   */
  @GetMapping("/capabilities")
  public AdKeywordCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.capabilities());
  }

  /** 筛选面板选项：平台 / 店铺 / 外部活动 / 归一类型 / 币种。 */
  @GetMapping("/filter-options")
  public AdKeywordFilterOptions filterOptions(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.filterOptions());
  }

  // ---- 🔴 关键词主体 ----

  /**
   * 关键词列表（真分页 + 筛选）。
   *
   * <p>🔴 响应带 {@code dataAsOf} 与 {@code syncStatus}：把「没跑这个词」与
   * 「还没同步到数据」分开 —— 页面上的 0 到底是哪个意思，答案在这个字段里。
   */
  @GetMapping
  public AdKeywordPage keywords(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String platformCode,
      @RequestParam(required = false) String shopCode,
      @RequestParam(required = false) String externalCampaignId,
      @RequestParam(required = false) String internalAdType,
      @RequestParam(required = false) String platformAdType,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String negated,
      @RequestParam(required = false) String listingKeywordId,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(required = false) String sort,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "channel.operation.read",
        a ->
            service.keywords(
                platformCode, shopCode, externalCampaignId, internalAdType, platformAdType, status,
                keyword, negated, listingKeywordId, from, to, sort, page, size));
  }

  /**
   * 🔴 登记一个投放关键词（**真实写入口**）。
   *
   * <p>🔴 归一类型由 <b>14.1.2.2 的两层解析</b>带出（请求里可以声明，不一致会被拒）——
   * 归一口径的真相在 2.1.2.1，不在请求里。
   */
  @PostMapping
  public AdKeywordRow createKeyword(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody AdKeywordInput input) {
    return call(auth, "channel.operation.create", a -> service.saveKeyword(null, input));
  }

  /** 改一个投放关键词（关键词原文 / 归一类型 / 状态 / 关联的 4.5.1.2 关键词）。 */
  @PutMapping("/{keywordId}")
  public AdKeywordRow updateKeyword(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String keywordId,
      @Valid @RequestBody AdKeywordInput input) {
    return call(
        auth, "channel.operation.update", a -> service.saveKeyword(parseId(keywordId), input));
  }

  /**
   * 🔴 归档一个关键词（**软删除**）。
   *
   * <p>表现、搜索词、否定历史**必须留着** —— 关键词停了不等于当初的花费与浪费不算数。
   */
  @DeleteMapping("/{keywordId}")
  public AdKeywordRow archiveKeyword(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String keywordId) {
    return call(auth, "channel.operation.delete", a -> service.archiveKeyword(keywordId));
  }

  /**
   * 🔴 单个关键词的明细（含搜索词浪费摘要、否定历史、4.5.1.2 的名次与词频）。
   *
   * <p>🔴 响应带 {@code emptyReason}：写明是「还没同步过任何指标」还是
   * 「同步过但这个区间内没有数据」—— 那是两回事。
   */
  @GetMapping("/{keywordId}")
  public AdKeywordDetail detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String keywordId) {
    return call(auth, "channel.operation.read", a -> service.detail(keywordId));
  }

  // ---- 🔴 关键词表现同步（蓝图输入项） ----

  /**
   * 🔴 接收一批关键词表现（**真实写入口**）。
   *
   * <p>本端点是**关键词表现数据进来的唯一入口**：平台回报（或人工补录）的
   * 展示 / 点击 / 转化 / 花费 / 销售额都走它。真实的同步调度器归 02 通道
   * （能力表里 available=false）—— 本模块不另建一条同步通道，
   * 两个入口会让同一件事有两个投递路径。
   *
   * <p>🔴 {@code syncedAt}（数据截至时间）与 {@code idempotencyKey} **必填**：
   * 没有截至时间的指标是不合格的指标数据；没有幂等键的重投会把 ACOS 变成两倍。
   */
  @PostMapping("/metrics")
  public AdKeywordMetricBatchResult ingestMetrics(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody AdKeywordMetricBatchRequest request) {
    return call(auth, "channel.operation.create", a -> service.ingestMetrics(request));
  }

  // ---- 🔴 搜索词报告 ----

  /**
   * 🔴 接收一批搜索词报告（**真实写入口**）。
   *
   * <p>蓝图加粗句「搜索词报告是发现浪费的入口」的物理落点就在这里：
   * 用户实际搜出来的词进来后，「高曝光零转化」才算得出来。
   */
  @PostMapping("/search-terms")
  public AdSearchTermBatchResult ingestSearchTerms(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody AdSearchTermBatchRequest request) {
    return call(auth, "channel.operation.create", a -> service.ingestSearchTerms(request));
  }

  /**
   * 🔴 搜索词报告（真分页 + 筛选）。
   *
   * <p>🔴 {@code wasted=true} 只看**高曝光零转化**的词；每一行都带实际数值。
   */
  @GetMapping("/search-terms")
  public AdSearchTermPage searchTerms(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String platformCode,
      @RequestParam(required = false) String shopCode,
      @RequestParam(required = false) String externalCampaignId,
      @RequestParam(required = false) String searchTerm,
      @RequestParam(required = false) String wasted,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "channel.operation.read",
        a ->
            service.searchTerms(
                platformCode, shopCode, externalCampaignId, searchTerm, wasted, from, to, page,
                size));
  }

  // ---- 🔴 否定词建议 ----

  /**
   * 🔴 否定词建议（**带依据数值**）。
   *
   * <p>🔴 三条规则在服务端判：① 高曝光零转化；② 花了钱没转化；③ ACOS 超标（给了目标值时）。
   * 每一行都带曝光 / 点击 / 转化 / 花费 / 销售额 / ACOS / 数据截至时间 ——
   * 「不能只丢一个词表」。
   */
  @GetMapping("/negative-suggestions")
  public AdNegativeSuggestionPage negativeSuggestions(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String platformCode,
      @RequestParam(required = false) String shopCode,
      @RequestParam(required = false) String externalCampaignId,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(required = false) Long minImpressions,
      @RequestParam(required = false) Long minClicks,
      @RequestParam(required = false) String targetAcos,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "channel.operation.read",
        a ->
            service.negativeSuggestions(
                platformCode, shopCode, externalCampaignId, from, to, minImpressions, minClicks,
                targetAcos, page, size));
  }

  // ---- 🔴 否定记录 ----

  /** 否定记录列表（真分页 + 筛选）。每一行都带**依据快照**。 */
  @GetMapping("/negatives")
  public AdNegativePage negatives(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String shopCode,
      @RequestParam(required = false) String externalCampaignId,
      @RequestParam(required = false) String negationText,
      @RequestParam(required = false) String status,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "channel.operation.read",
        a -> service.negatives(shopCode, externalCampaignId, negationText, status, page, size));
  }

  /**
   * 🔴 登记一条否定。
   *
   * <p>🔴 **权威依据由服务端现算**（与否定建议同一套口径），不采信调用方给的数字；
   * 调用方自述的依据原样存进 {@code claimedEvidence} 作审计留痕。
   */
  @PostMapping("/negatives")
  public AdNegativeRow createNegative(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody AdNegativeInput input) {
    return call(auth, "channel.operation.create", a -> service.createNegative(input));
  }

  /**
   * 🔴 登记平台对一条否定的回执。
   *
   * <p>本模块**不推平台**（推送归 02 通道 / 平台适配器），但回执必须能进来 ——
   * 否则这条否定永远停在「已决定」。
   */
  @PutMapping("/negatives/{negativeId}/result")
  public AdNegativeRow recordNegativeResult(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String negativeId,
      @Valid @RequestBody AdNegativeResultInput input) {
    return call(
        auth, "channel.operation.update", a -> service.recordNegativeResult(negativeId, input));
  }

  // ---- 🔴 批量调价与否定 ----

  /**
   * 🔴 批量调价与否定。
   *
   * <p>🔴 护栏四道：单次条目上限、幅度上限、绝对上限、**总上调上限**。
   * 越线**整批不动**，并逐条返回「为什么没动」。
   *
   * <p>🔴 批量**调价**是花钱的动作 ⇒ 批次级幂等（同一个 key 重投不重复执行）。
   */
  @PostMapping("/bulk-actions")
  public AdBulkActionResult bulkAction(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody AdBulkActionRequest request) {
    return call(auth, "channel.operation.update", a -> service.bulkAction(request));
  }

  /** 批量任务列表（真分页 + 筛选）。 */
  @GetMapping("/bulk-actions")
  public AdBulkActionPage bulkActions(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String actionType,
      @RequestParam(required = false) String status,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth, "channel.operation.read", a -> service.bulkActions(actionType, status, page, size));
  }

  /** 批量任务详情（含**逐条执行结果**）。 */
  @GetMapping("/bulk-actions/{actionId}")
  public AdBulkActionResult bulkActionDetail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String actionId) {
    return call(auth, "channel.operation.read", a -> service.bulkActionDetail(actionId));
  }

  // ---- 🔴 报表与词频排名 ----

  /**
   * 🔴 关键词报表（真 SQL 聚合）。
   *
   * <p>🔴 维度：PLATFORM / SHOP / CAMPAIGN / AD_TYPE / ALL；
   * 🔴 **按币种分行**（跨币种不可相加）；🔴 ACOS 等比率现算，分母为 0 给 null。
   */
  @GetMapping("/report")
  public AdKeywordReport report(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String dimension,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(required = false) String internalAdType,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "channel.operation.read",
        a -> service.report(dimension, from, to, internalAdType, page, size));
  }

  /**
   * 🔴 词频与排名。
   *
   * <p>🔴 口径**引用 4.5.1.2**：名次读的是它的 ranking 快照，词频读的是它的关键词集合。
   * 本模块**不新建排名表、不自算名次**。
   */
  @GetMapping("/frequency")
  public AdKeywordFrequencyPage frequency(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String platformCode,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "channel.operation.read", a -> service.frequency(platformCode, from, to, page, size));
  }

  private static UUID parseId(String raw) {
    try {
      return UUID.fromString(raw == null ? "" : raw.trim());
    } catch (RuntimeException e) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.BAD_REQUEST, "id 格式不正确");
    }
  }
}
