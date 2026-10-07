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
 * 3.4.2.2 描述与卖点库：内容模板（详情描述 / 使用说明 / 注意事项 / 卖点段落）、
 * 多语言译文、卖点短语库、批量填充与段落引用。
 *
 * <p>路由挂在 {@code /catalog/descriptions} 之下，与 {@code /catalog/titles}（3.4.2.1）
 * 并列：两者是**两条独立的资产线**——标题有平台硬字符上限、核心矛盾是重复铺货；
 * 描述是长文本、核心矛盾是「变量缺失必须报告」。合进一个路由会让两套约束互相干扰。
 * 台账里的 {@code /products/content} 是**前端页面路径**（同一个页面上两个工作区）。
 *
 * <p>权限沿用 {@code catalog.spu.read} / {@code catalog.spu.update}：描述是挂在 SKU 上的
 * 商品文案、上游是 3.1.1.1 的 SPU/SKU，所以复用已有词表而不是新造
 * （新词表要改 identity 服务的迁移，属于另一个服务的写入所有权）。
 *
 * <p><b>填充接口是同步的「已完成」语义</b>，与 3.4.1.2 的异步「已受理」不同：
 * 这里的成本只与 SKU 数成正比（每个 SKU 一次字符串替换），几百个 SKU 是几十毫秒。
 */
@RestController
@RequestMapping("/catalog/descriptions")
public class DescriptionController {
  private final AccessService access;
  private final DescriptionService descriptions;
  private final DescriptionRenderService renders;

  public DescriptionController(
      AccessService access, DescriptionService descriptions, DescriptionRenderService renders) {
    this.access = access;
    this.descriptions = descriptions;
    this.renders = renders;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "catalog." + permission);
    return access.run(actor, "catalog." + permission, () -> work.apply(actor));
  }

  // ------------------------------------------------------------ 概览

  /** 描述与卖点库概览：模板/译文/短语存量，以及「有多少段落还没填好」。 */
  @GetMapping("/overview")
  public ContentOverview overview(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "spu.read", a -> descriptions.overview());
  }

  // ------------------------------------------------------------ 模板

  /** 模板列表（含变量与已配语言）。停用的也在列表里——要看历史配置。 */
  @GetMapping("/templates")
  public List<ContentTemplateRow> templates(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "spu.read", a -> descriptions.templates());
  }

  @GetMapping("/templates/{id}")
  public ContentTemplateRow template(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "spu.read", a -> descriptions.template(id));
  }

  /**
   * 建模板或改模板（id 为空即新建）。变量整体替换。
   *
   * <p>编码建后不可改：历史段落按这套变量绑定解释，改编码会让旧批次的溯源对不上。
   */
  @PostMapping("/templates")
  public ContentTemplateSaved createTemplate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody ContentTemplateInput input) {
    return call(auth, "spu.update", a -> descriptions.saveTemplate(a, null, input));
  }

  @PutMapping("/templates/{id}")
  public ContentTemplateSaved updateTemplate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody ContentTemplateInput input) {
    return call(auth, "spu.update", a -> descriptions.saveTemplate(a, id, input));
  }

  /**
   * 删除模板。仍被填充批次引用时返回 400 并说明被引用几次——
   * 要清理请改用停用（停用后不能再用它新建批次，历史段落与译文照常可读）。
   */
  @DeleteMapping("/templates/{id}")
  public Map<String, Boolean> deleteTemplate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam long version) {
    call(
        auth,
        "spu.update",
        a -> {
          descriptions.deleteTemplate(a, id, version);
          return Boolean.TRUE;
        });
    return Map.of("deleted", true);
  }

  @PutMapping("/templates/{id}/enabled")
  public ContentTemplateSaved setTemplateEnabled(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam boolean enabled) {
    return call(auth, "spu.update", a -> descriptions.setTemplateEnabled(a, id, enabled));
  }

  // ------------------------------------------------------------ 多语言译文

  /** 某模板的译文列表。 */
  @GetMapping("/templates/{id}/translations")
  public List<ContentTranslation> translations(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "spu.read", a -> descriptions.translations(id));
  }

  /**
   * 译文完整度：这几种语言里哪些能直接填。
   *
   * <p>用 query 传语言列表（不是请求体）：这是纯只读的 GET 查询，
   * 且语言列表短（最多 10 种）。
   */
  @GetMapping("/templates/{id}/translation-coverage")
  public ContentTranslationCoverage translationCoverage(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam List<String> locale) {
    return call(auth, "spu.read", a -> descriptions.translationCoverage(id, locale));
  }

  /** 建译文或改译文（translationId 为空即新建，按模板 + 语言定位）。 */
  @PostMapping("/templates/{templateId}/translations")
  public ContentTranslation createTranslation(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID templateId,
      @Valid @RequestBody ContentTranslationInput input) {
    return call(
        auth, "spu.update", a -> descriptions.saveTranslation(a, templateId, null, input));
  }

  @PutMapping("/templates/{templateId}/translations/{translationId}")
  public ContentTranslation updateTranslation(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID templateId,
      @PathVariable UUID translationId,
      @Valid @RequestBody ContentTranslationInput input) {
    return call(
        auth, "spu.update", a -> descriptions.saveTranslation(a, templateId, translationId, input));
  }

  @DeleteMapping("/translations/{translationId}")
  public Map<String, Boolean> deleteTranslation(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID translationId,
      @RequestParam long version) {
    call(
        auth,
        "spu.update",
        a -> {
          descriptions.deleteTranslation(a, translationId, version);
          return Boolean.TRUE;
        });
    return Map.of("deleted", true);
  }

  // ------------------------------------------------------------ 卖点短语库

  /** 短语列表：分页 + 关键词 + 类型 + 启用状态筛选。每行自带被引用数。 */
  @GetMapping("/phrases")
  public ContentPhrasePage phrases(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String phraseType,
      @RequestParam(required = false) String enabled,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(
        auth,
        "spu.read",
        a -> descriptions.phrases(new ContentPhraseQuery(keyword, phraseType, enabled, page, size)));
  }

  @PostMapping("/phrases")
  public ContentPhraseRow createPhrase(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody ContentPhraseInput input) {
    return call(auth, "spu.update", a -> descriptions.savePhrase(a, null, input));
  }

  @PutMapping("/phrases/{id}")
  public ContentPhraseRow updatePhrase(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody ContentPhraseInput input) {
    return call(auth, "spu.update", a -> descriptions.savePhrase(a, id, input));
  }

  @PutMapping("/phrases/{id}/enabled")
  public ContentPhraseRow setPhraseEnabled(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam boolean enabled) {
    return call(auth, "spu.update", a -> descriptions.setPhraseEnabled(a, id, enabled));
  }

  /** 删除短语。被段落引用时返回 400 并说明被引用几段——要清理请改用停用。 */
  @DeleteMapping("/phrases/{id}")
  public Map<String, Boolean> deletePhrase(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam long version) {
    call(
        auth,
        "spu.update",
        a -> {
          descriptions.deletePhrase(a, id, version);
          return Boolean.TRUE;
        });
    return Map.of("deleted", true);
  }

  // ------------------------------------------------------------ 批量填充

  /**
   * 批量填充描述（同步，返回即已完成）。
   *
   * <p>缺译文在提交时整批拒绝（不悄悄退回源语言正文）；
   * 商品字段为空与短语池为空**不拒**——它们逐 SKU 报告，正文照样产出，
   * 只是段落标成「缺变量」且占位符被剥掉。
   */
  @PostMapping("/renders")
  public ContentRender render(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody ContentRenderInput input) {
    return call(auth, "spu.update", a -> renders.render(a, input));
  }

  /** 批次列表：分页 + 关键词（搜批次与模板名）+ 状态筛选。 */
  @GetMapping("/renders")
  public ContentRenderPage listRenders(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String status,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(
        auth,
        "spu.read",
        a -> renders.listRenders(new ContentRenderQuery(keyword, status, page, size)));
  }

  /** 批次详情：批次 + 逐 SKU 结果 + 全部段落（含缺失报告）+ 按变量汇总。 */
  @GetMapping("/renders/{renderId}")
  public ContentRenderDetail renderDetail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID renderId) {
    return call(auth, "spu.read", a -> renders.renderDetail(renderId));
  }

  /** 某批次的逐 SKU 结果。 */
  @GetMapping("/renders/{renderId}/skus")
  public List<ContentRenderSkuRow> renderSkus(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID renderId) {
    return call(auth, "spu.read", a -> renders.renderSkus(renderId));
  }

  /** 某批次按变量汇总的缺失情况（「brand 缺 200 次」而不是 200 行罗列）。 */
  @GetMapping("/renders/{renderId}/missing-variables")
  public List<ContentMissingVariable> missingVariables(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID renderId) {
    return call(auth, "spu.read", a -> renders.missingVariablesOf(renderId));
  }

  // ------------------------------------------------------------ 段落

  /** 段落列表：04 刊登与 15 客服取「这个 SKU 的这段描述」的主查询形状。 */
  @GetMapping("/sections")
  public ContentSectionPage sections(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID skuId,
      @RequestParam(required = false) String locale,
      @RequestParam(required = false) String contentType,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) UUID renderId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(
        auth,
        "spu.read",
        a ->
            renders.listSections(
                new ContentSectionQuery(
                    skuId, locale, contentType, status, keyword, renderId, page, size)));
  }

  /**
   * 导出段落 CSV（供 04 刊登与 15 客服取用）。
   *
   * <p>StreamingResponseBody 分批写出，峰值内存与结果集大小无关。
   * 走 GET + query 而不是 POST：导出结果与当前筛选完全一致。
   */
  @GetMapping(value = "/sections/export", produces = "text/csv")
  public ResponseEntity<StreamingResponseBody> exportSections(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID skuId,
      @RequestParam(required = false) String locale,
      @RequestParam(required = false) String contentType,
      @RequestParam(required = false) String status) {
    var actor = access.require(auth, "catalog.spu.read");
    var query =
        new ContentSectionQuery(skuId, locale, contentType, status, null, null, 0, 0);
    // 先在租户事务里校验筛选条件，别等到已经开始写流才失败。
    access.run(
        actor,
        "catalog.spu.read",
        () -> {
          renders.listSections(query);
          return null;
        });
    StreamingResponseBody body = out -> renders.streamCsv(actor, query, chunk -> write(out, chunk));
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_TYPE, "text/csv; charset=UTF-8")
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=\"catalog-descriptions-"
                + Instant.now().toEpochMilli()
                + ".csv\"")
        .body(body);
  }

  private static void write(java.io.OutputStream out, String chunk) {
    try {
      out.write(chunk.getBytes(StandardCharsets.UTF_8));
      out.flush();
    } catch (IOException e) {
      // 客户端提前断开（关掉下载页）是正常行为，不该当成服务端错误往上抛。
      throw new org.springframework.web.client.RestClientException("导出已中断", e);
    }
  }
}
