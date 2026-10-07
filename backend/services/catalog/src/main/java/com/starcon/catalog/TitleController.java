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
 * 3.4.2.1 标题库：标题模板（变量占位）、标题词根字典、批量生成批次、候选选用与导出。
 *
 * <p>路由挂在 {@code /catalog/titles} 之下，与 {@code /catalog/media}（3.4.1.x）并列：
 * 素材是二进制资产、文案是文本资产，两者被 04 刊登同时引用，但维护节奏与责任人不同
 * （素材跟拍摄与供应商批次走，文案跟平台规则与关键词策略走）。
 * 台账里的 {@code /products/content} 是**前端页面路径**。
 *
 * <p>权限沿用 {@code catalog.spu.read} / {@code catalog.spu.update}：标题是挂在 SKU 上的
 * 商品文案、上游是 3.1.1.1 的 SPU/SKU，所以复用已有词表而不是新造
 * （新词表要改 identity 服务的迁移，属于另一个服务的写入所有权）。
 *
 * <p><b>生成接口是同步的「已完成」语义</b>，与 3.4.1.2 的异步「已受理」不同：
 * 这里的成本只与单个 SKU 内的候选数有关（重复度比较范围收在单 SKU 内），
 * 几百个 SKU 是几十毫秒。做成异步只会让运营为几百毫秒去盯进度条。
 */
@RestController
@RequestMapping("/catalog/titles")
public class TitleController {
  private final AccessService access;
  private final TitleService service;

  public TitleController(AccessService access, TitleService service) {
    this.access = access;
    this.service = service;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "catalog." + permission);
    return access.run(actor, "catalog." + permission, () -> work.apply(actor));
  }

  // ------------------------------------------------------------ 概览

  /** 标题库概览：模板数、词根数、候选规模与全局最严的重复度上限。 */
  @GetMapping("/overview")
  public TitleOverview overview(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "spu.read", a -> service.overview());
  }

  // ------------------------------------------------------------ 模板

  /** 模板列表（含变量与平台长度上限）。停用的也在列表里——要看历史配置。 */
  @GetMapping("/templates")
  public List<TitleTemplateRow> templates(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "spu.read", a -> service.templates());
  }

  @GetMapping("/templates/{id}")
  public TitleTemplateRow template(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "spu.read", a -> service.template(id));
  }

  /**
   * 建模板或改模板（id 为空即新建）。变量与平台上限整体替换。
   *
   * <p>编码建后不可改：历史候选按这套变量绑定解释，改编码会让旧批次的溯源对不上。
   */
  @PostMapping("/templates")
  public TitleTemplateSaved createTemplate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody TitleTemplateInput input) {
    return call(auth, "spu.update", a -> service.saveTemplate(a, null, input));
  }

  @PutMapping("/templates/{id}")
  public TitleTemplateSaved updateTemplate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody TitleTemplateInput input) {
    return call(auth, "spu.update", a -> service.saveTemplate(a, id, input));
  }

  /**
   * 删除模板。仍被生成批次引用时返回 400 并说明被引用几次——
   * 要清理请改用停用（停用后不能再用它新建批次，历史标题与候选照常可读）。
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
          service.deleteTemplate(a, id, version);
          return Boolean.TRUE;
        });
    return Map.of("deleted", true);
  }

  @PutMapping("/templates/{id}/enabled")
  public TitleTemplateSaved setTemplateEnabled(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam boolean enabled) {
    return call(auth, "spu.update", a -> service.setTemplateEnabled(a, id, enabled));
  }

  // ------------------------------------------------------------ 词根

  /** 词根列表：分页 + 关键词 + 类型筛选。每行自带被引用数（删除保护在列表上就看得见）。 */
  @GetMapping("/keywords")
  public TitleKeywordPage keywords(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String keywordType,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(
        auth,
        "spu.read",
        a -> service.keywords(new TitleKeywordQuery(keyword, keywordType, page, size)));
  }

  @PostMapping("/keywords")
  public TitleKeywordRow createKeyword(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody TitleKeywordInput input) {
    return call(auth, "spu.update", a -> service.saveKeyword(a, null, input));
  }

  @PutMapping("/keywords/{id}")
  public TitleKeywordRow updateKeyword(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody TitleKeywordInput input) {
    return call(auth, "spu.update", a -> service.saveKeyword(a, id, input));
  }

  @PutMapping("/keywords/{id}/enabled")
  public TitleKeywordRow setKeywordEnabled(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam boolean enabled) {
    return call(auth, "spu.update", a -> service.setKeywordEnabled(a, id, enabled));
  }

  /** 删除词根。被标题引用时返回 400 并说明被引用几条——要清理请改用停用。 */
  @DeleteMapping("/keywords/{id}")
  public Map<String, Boolean> deleteKeyword(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam long version) {
    call(
        auth,
        "spu.update",
        a -> {
          service.deleteKeyword(a, id, version);
          return Boolean.TRUE;
        });
    return Map.of("deleted", true);
  }

  // ------------------------------------------------------------ 批量生成

  /**
   * 批量生成标题候选（同步，返回即已完成）。
   *
   * <p>参数就是蓝图点名的三样：SKU 范围、平台、词根。
   * 相似度超限的组合会被**拒绝**并给出原因，不会放宽阈值凑数。
   */
  @PostMapping("/generations")
  public TitleGeneration generate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody TitleGenerationInput input) {
    return call(auth, "spu.update", a -> service.generate(a, input));
  }

  /** 批次列表：分页 + 关键词（搜批次与模板名）+ 状态筛选。 */
  @GetMapping("/generations")
  public TitleGenerationPage generations(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String status,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(
        auth,
        "spu.read",
        a -> service.listGenerations(new TitleGenerationQuery(keyword, status, page, size)));
  }

  /** 批次详情：批次 + 逐 SKU 结果（含未产出原因）+ 全部候选。 */
  @GetMapping("/generations/{generationId}")
  public TitleGenerationDetail generation(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID generationId) {
    return call(auth, "spu.read", a -> service.generationDetail(generationId));
  }

  /** 某批次的逐 SKU 结果。 */
  @GetMapping("/generations/{generationId}/skus")
  public List<TitleGenerationSkuRow> generationSkus(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID generationId) {
    return call(auth, "spu.read", a -> service.generationSkus(generationId));
  }

  // ------------------------------------------------------------ 候选

  /** 候选列表：分页 + SKU / 平台 / 状态 / 关键词筛选。 */
  @GetMapping("/records")
  public TitleRecordPage records(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID skuId,
      @RequestParam(required = false) String platform,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) UUID generationId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(
        auth,
        "spu.read",
        a -> service.listRecords(new TitleRecordQuery(skuId, platform, status, keyword, generationId, page, size)));
  }

  /**
   * 导出候选 CSV（供 04 选用或人工核对）。
   *
   * <p>StreamingResponseBody 分批写出，峰值内存与结果集大小无关。
   */
  @GetMapping(value = "/records/export", produces = "text/csv")
  public ResponseEntity<StreamingResponseBody> export(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID skuId,
      @RequestParam(required = false) String platform,
      @RequestParam(required = false) String status) {
    var actor = access.require(auth, "catalog.spu.read");
    var query = new TitleRecordQuery(skuId, platform, status, null, null, 0, 0);
    // 先在租户事务里校验筛选条件，别等到已经开始写流才失败。
    access.run(
        actor,
        "catalog.spu.read",
        () -> {
          service.listRecords(query);
          return null;
        });
    StreamingResponseBody body = out -> service.streamCsv(actor, query, chunk -> write(out, chunk));
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_TYPE, "text/csv; charset=UTF-8")
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=\"catalog-titles-" + Instant.now().toEpochMilli() + ".csv\"")
        .body(body);
  }

  /** 选用一条标题供 04 刊登使用。同一 SKU 同一平台的其它选用项会被降级为待选用。 */
  @PostMapping("/records/{id}/select")
  public TitleRecordSelected select(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "spu.update", a -> service.select(a, id));
  }

  /** 取消选用。已选用的标题内容不可改（V20 的触发器钉死），换标题要先取消再新建。 */
  @PostMapping("/records/{id}/deselect")
  public TitleRecordSelected deselect(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "spu.update", a -> service.deselect(a, id));
  }

  /** 手动淘汰一条候选。 */
  @PostMapping("/records/{id}/reject")
  public TitleRecordSelected reject(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "spu.update", a -> service.reject(a, id));
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