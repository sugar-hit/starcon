package com.starcon.catalog;

import static com.starcon.catalog.CatalogModels.*;

import jakarta.validation.Valid;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * 3.4.3.1 多语言内容：SPU/SKU 的多语言名称与描述、租户语言需求（人工维护 ∪ 店铺站点推导）、
 * AI 翻译建议的采纳/驳回、翻译完整度与批量操作。
 *
 * <p>路由挂在 {@code /catalog/translations} 之下，与 {@code /catalog/descriptions}（3.4.2.2）
 * {@code /catalog/titles}（3.4.2.1）**并列而不合并**：那两套也带 locale，但那是
 * 「模板与其渲染段落」的多语言（见 V22 文件头边界），挂在商品档案上的多语言名称与描述是
 * 另一套口径，合在一个路由下会让两边的 locale 迟早被当成同一个东西。
 * 台账里的 {@code /products/translations} 是**前端页面路径**。
 *
 * <p>权限沿用 {@code catalog.spu.read} / {@code catalog.spu.update}：译文挂在商品上、
 * 上游是 3.1.1.1 的 SPU/SKU，所以复用已有词表而不是新造
 * （新词表要改 identity 服务的迁移，属于另一个服务的写入所有权）。
 *
 * <p><b>批量接口是同步的「已完成」语义</b>，与 3.4.1.2 的异步「已受理」不同：
 * 补占位、采纳建议、标校对都是几百行的短事务，几十毫秒。
 */
@RestController
@RequestMapping("/catalog/translations")
public class TranslationController {
  private final AccessService access;
  private final TranslationService translations;
  private final TranslationBatchService batches;

  public TranslationController(
      AccessService access, TranslationService translations, TranslationBatchService batches) {
    this.access = access;
    this.translations = translations;
    this.batches = batches;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "catalog." + permission);
    return access.run(actor, "catalog." + permission, () -> work.apply(actor));
  }

  // ------------------------------------------------------------ 概览

  /** 多语言内容概览：语言需求存量、三态译文存量、待处置建议数、批次数。 */
  @GetMapping("/overview")
  public TranslationOverview overview(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "spu.read", a -> translations.overview());
  }

  // ------------------------------------------------------------ 语言需求（人工维护）

  /** 语言需求列表：分页 + 关键词 + 来源 + 启用状态。每行自带该语言的译文覆盖统计。 */
  @GetMapping("/locales")
  public TranslationLocalePage locales(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String source,
      @RequestParam(required = false) String enabled,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(
        auth,
        "spu.read",
        a -> translations.locales(new TranslationLocaleQuery(keyword, source, enabled, page, size)));
  }

  @GetMapping("/locales/{id}")
  public TranslationLocaleRow locale(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "spu.read", a -> translations.locale(id));
  }

  /** 建语言需求或改语言需求（id 为空即新建）。语言标签建后不可改。 */
  @PostMapping("/locales")
  public TranslationLocaleSaved createLocale(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody TranslationLocaleInput input) {
    return call(auth, "spu.update", a -> translations.saveLocale(a, null, input));
  }

  @PutMapping("/locales/{id}")
  public TranslationLocaleSaved updateLocale(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody TranslationLocaleInput input) {
    return call(auth, "spu.update", a -> translations.saveLocale(a, id, input));
  }

  @PutMapping("/locales/{id}/enabled")
  public TranslationLocaleRow setLocaleEnabled(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam boolean enabled) {
    return call(auth, "spu.update", a -> translations.setLocaleEnabled(a, id, enabled));
  }

  /** 删除语言需求。仍被商品译文使用时返回 400 并说明被用了几条——要清理请改用停用。 */
  @DeleteMapping("/locales/{id}")
  public Map<String, Boolean> deleteLocale(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam long version) {
    call(
        auth,
        "spu.update",
        a -> {
          translations.deleteLocale(a, id, version);
          return Boolean.TRUE;
        });
    return Map.of("deleted", true);
  }

  // ------------------------------------------------------------ 「需要哪些语言」

  /**
   * 合并视图：人工维护 ∪ 店铺启用站点推导。
   *
   * <p>02 渠道未接入时 {@code siteLocaleAvailable=false} 且 {@code siteNote} 写明归属，
   * 而不是返回空语言列表（空列表会被读成「不需要做多语言」）。
   */
  @GetMapping("/locales/requirements")
  public TranslationRequirementView requirements(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "spu.read", a -> translations.requirementView());
  }

  /**
   * 从店铺启用站点同步语言需求。
   *
   * <p>02 渠道未接入时返回 409 并写明归属，**不写入任何行**。
   */
  @PostMapping("/locales/sync")
  public TranslationRequirementView syncFromSites(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "spu.update", a -> translations.syncFromSites(a));
  }

  // ------------------------------------------------------------ 商品工作台

  /** 某个 SPU 的多语言工作台：需要哪些语言 × 翻到什么程度，同一行对上。 */
  @GetMapping("/spus/{spuId}")
  public TranslationProductView spuWorkspace(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID spuId) {
    return call(auth, "spu.read", a -> translations.productView("SPU", spuId));
  }

  /** 某个 SKU 的多语言工作台。 */
  @GetMapping("/skus/{skuId}")
  public TranslationProductView skuWorkspace(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID skuId) {
    return call(auth, "spu.read", a -> translations.productView("SKU", skuId));
  }

  // ------------------------------------------------------------ 译文

  /** 译文列表：分页 + 关键词 + 状态 + 语言 + 主体类型 + 具体主体。 */
  @GetMapping("/entries")
  public TranslationEntryPage entries(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String targetType,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String locale,
      @RequestParam(required = false) String spuId,
      @RequestParam(required = false) String skuId,
      @RequestParam(required = false) String keyword,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(
        auth,
        "spu.read",
        a ->
            translations.entries(
                new TranslationEntryQuery(
                    targetType, status, locale, spuId, skuId, keyword, page, size)));
  }

  @GetMapping("/entries/{id}")
  public TranslationEntry entry(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "spu.read", a -> translations.entry(id));
  }

  /** 给某个 SPU 建译文。语言唯一——同语言再建会返回 409。 */
  @PostMapping("/spus/{spuId}/entries")
  public TranslationEntry createSpuEntry(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID spuId,
      @Valid @RequestBody TranslationEntryInput input) {
    return call(auth, "spu.update", a -> translations.saveEntry(a, "SPU", spuId, null, input));
  }

  @PostMapping("/skus/{skuId}/entries")
  public TranslationEntry createSkuEntry(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID skuId,
      @Valid @RequestBody TranslationEntryInput input) {
    return call(auth, "spu.update", a -> translations.saveEntry(a, "SKU", skuId, null, input));
  }

  /** 改译文。语言不可改——「把德语译文改成法语」是删一条建一条。 */
  @PutMapping("/entries/{id}")
  public TranslationEntry updateEntry(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody TranslationEntryEdit input) {
    return call(auth, "spu.update", a -> translations.saveEntry(a, null, null, id, input));
  }

  /** 翻译状态流转（人工校对 / 退回）。会留下校对人与校对时刻。 */
  @PutMapping("/entries/{id}/status")
  public TranslationEntry setEntryStatus(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody TranslationStatusInput input) {
    return call(auth, "spu.update", a -> translations.setEntryStatus(a, id, input));
  }

  /** 删除译文。删一条待翻占位行是日常操作（不再需要这种语言）。 */
  @DeleteMapping("/entries/{id}")
  public Map<String, Boolean> deleteEntry(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam long version) {
    call(
        auth,
        "spu.update",
        a -> {
          translations.deleteEntry(a, id, version);
          return Boolean.TRUE;
        });
    return Map.of("deleted", true);
  }

  /**
   * 翻译完整度：这个商品在这些语言上能不能直接刊登。
   *
   * <p>04 刊登填充的读形状。用 query 传语言列表（只读 GET 查询，且语言列表短）。
   */
  @GetMapping("/coverage")
  public TranslationCoverage coverage(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam String targetType,
      @RequestParam UUID targetId,
      @RequestParam(required = false) List<String> locale) {
    return call(
        auth, "spu.read", a -> translations.coverage(targetType, targetId, locale));
  }

  /**
   * 导出译文 CSV（供 04 刊登与 18 批量校对取用）。
   *
   * <p>StreamingResponseBody 分批写出，峰值内存与结果集大小无关。
   * 走 GET + query 而不是 POST：导出结果与当前筛选完全一致。
   */
  @GetMapping(value = "/entries/export", produces = "text/csv")
  public ResponseEntity<StreamingResponseBody> exportEntries(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String targetType,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String locale,
      @RequestParam(required = false) String spuId,
      @RequestParam(required = false) String skuId,
      @RequestParam(required = false) String keyword) {
    var actor = access.require(auth, "catalog.spu.read");
    var query =
        new TranslationEntryQuery(targetType, status, locale, spuId, skuId, keyword, 0, 0);
    // 先在租户事务里校验筛选条件，别等到已经开始写流才失败。
    access.run(
        actor,
        "catalog.spu.read",
        () -> {
          translations.entries(query);
          return null;
        });
    StreamingResponseBody body =
        out -> {
          java.io.Writer writer =
              new java.io.OutputStreamWriter(out, StandardCharsets.UTF_8);
          translations.streamEntriesCsv(query, chunk -> writeChunk(writer, chunk));
          writer.flush();
        };
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_TYPE, "text/csv; charset=UTF-8")
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=\"catalog-multilingual-"
                + Instant.now().toEpochMilli()
                + ".csv\"")
        .body(body);
  }

  private static void writeChunk(java.io.Writer writer, String chunk) {
    try {
      writer.write(chunk);
      writer.flush();
    } catch (IOException e) {
      // 客户端提前断开（关掉下载页）是正常行为，不该当成服务端错误往上抛。
      throw new org.springframework.web.client.RestClientException("导出已中断", e);
    }
  }

  // ------------------------------------------------------------ AI 翻译建议

  /** 建议列表：分页 + 关键词 + 状态 + 语言 + 主体 + batch_ref。 */
  @GetMapping("/suggestions")
  public TranslationSuggestionPage suggestions(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String targetType,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String locale,
      @RequestParam(required = false) String spuId,
      @RequestParam(required = false) String skuId,
      @RequestParam(required = false) String batchRef,
      @RequestParam(required = false) String keyword,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(
        auth,
        "spu.read",
        a ->
            translations.suggestions(
                new TranslationSuggestionQuery(
                    targetType, status, locale, spuId, skuId, batchRef, keyword, page, size)));
  }

  @GetMapping("/suggestions/{id}")
  public TranslationSuggestionRow suggestion(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "spu.read", a -> translations.suggestion(id));
  }

  /**
   * 登记一批 AI 翻译建议（18 AI 翻译回写）。
   *
   * <p>catalog 不调 AI——调它等于跨服务依赖调用顺序。18 翻完调这里写回。
   */
  @PostMapping("/suggestions")
  public TranslationSuggestionPage submitSuggestions(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody TranslationSuggestionSubmit input) {
    return call(auth, "spu.update", a -> translations.submitSuggestions(a, input));
  }

  /**
   * 采纳一条 AI 建议。译文落在「已翻」而不是「已校对」——
   * 机器译文拿去发平台等于没做校对这一步。
   */
  @PostMapping("/suggestions/{id}/adopt")
  public TranslationEntry adoptSuggestion(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "spu.update", a -> translations.adoptSuggestion(a, id));
  }

  /** 驳回一条 AI 建议。理由必填：不给理由的驳回让下一轮翻译无从改进。 */
  @PostMapping("/suggestions/{id}/reject")
  public TranslationSuggestionRow rejectSuggestion(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody TranslationSuggestionReject input) {
    return call(auth, "spu.update", a -> translations.rejectSuggestion(a, id, input));
  }

  /** 删除一条建议。只允许删已驳回的。 */
  @DeleteMapping("/suggestions/{id}")
  public Map<String, Boolean> deleteSuggestion(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam long version) {
    call(
        auth,
        "spu.update",
        a -> {
          translations.deleteSuggestion(a, id, version);
          return Boolean.TRUE;
        });
    return Map.of("deleted", true);
  }

  // ------------------------------------------------------------ 批量操作

  /**
   * 执行一次批量操作（同步，返回即已完成）。
   *
   * <p>补齐待翻占位 / 批量采纳 AI 建议 / 批量标记已校对。
   * 逐条结果在批次详情里，不是一个汇总数字。
   */
  @PostMapping("/batches")
  public TranslationBatchDetail runBatch(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody TranslationBatchInput input) {
    return call(auth, "spu.update", a -> batches.run(a, input));
  }

  /** 批次列表：分页 + 动作 + 主体类型 + 状态。 */
  @GetMapping("/batches")
  public TranslationBatchPage batches(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String action,
      @RequestParam(required = false) String targetType,
      @RequestParam(required = false) String status,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(
        auth,
        "spu.read",
        a -> batches.batches(new TranslationBatchQuery(action, targetType, status, page, size)));
  }

  /** 批次详情：批次 + 逐条结果（含目标编码与未执行原因）。 */
  @GetMapping("/batches/{batchId}")
  public TranslationBatchDetail batchDetail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID batchId) {
    return call(auth, "spu.read", a -> batches.detail(batchId));
  }
}