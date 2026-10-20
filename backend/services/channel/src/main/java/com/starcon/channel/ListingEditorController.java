package com.starcon.channel;

import static com.starcon.channel.ListingEditorModels.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.*;

/**
 * 4.2.1.1 刊登编辑器。
 *
 * <p>蓝图：「单商品的平台内容编辑表单：按目标平台动态渲染字段（标题、要点、描述、变体维度、图片集、视频、A+
 * 模块、类目属性、合规声明），左侧编辑右侧实时预览，支持多语言 Tab、保存草稿、提交审核。输入：源商品（03
 * SKU/SPU）、目标店铺与站点、平台字段要求（02 能力矩阵）；用户编辑内容；已填内容的草稿。输出：内容草稿（按平台 +
 * 站点 + 语言分别保存）；提交发布或保存模板的动作；校验结果。关系：上游 = 03 商品主数据、03 媒体与文案、02 平台字段要求；下游 =
 * 4.3.1.1 发布。<b>表单字段由平台要求驱动生成</b>，不是固定一套表单套所有平台——平台能力矩阵变化时表单自动变化。」
 * 端点按蓝图模块号标注，方便对照 04-listing-and-content.md。
 *
 * <p>🔴 端点清单里**没有**任何一个「保存到线上 / 发布」的入口，这是刻意的：
 *
 * <ul>
 *   <li>{@code GET /form-schema} —— 🔴 **动态表单的契约**。字段集、必填性、长度 / 条数上限全部
 *       由后端从平台字段要求推导；前端**不自己判断必填**，照这份 schema 渲染。
 *   <li>{@code POST /drafts} —— 「保存草稿」。产出是草稿行，**线上 listing 一个字节都不动**。
 *   <li>{@code POST /drafts/{id}/submit} —— 「提交审核」。产出是**入队**记录（QUEUED），
 *       真正发给平台是 4.3.1.1 一键刊登（尚未交付）。
 *   <li>{@code POST /validate} —— 「校验结果」。真算：必填缺失 / 长度超限 / 条数超限，逐条指到具体字段。
 *   <li>{@code GET /drafts} / {@code GET /drafts/{id}} —— 草稿与线上内容的左右对照。
 *   <li>{@code GET /submissions} —— 入队队列视图。
 *   <li>{@code GET /source-product} / {@code GET /media-assets} —— 03 上游只读（商品主数据 / 媒体库）。
 * </ul>
 *
 * <p>权限码<b>复用</b> identity 已有的 {@code channel.operation.*}（与 4.1.1.1 listing 列表、
 * 4.2.1.2 批量编辑、4.4.2.1 商品认领同族），identity 服务零改动。读操作用 read，写操作用 create / update。
 */
@RestController
@RequestMapping("/channel/listing-editor")
public class ListingEditorController {

  private final AccessService access;
  private final ListingEditorService service;

  public ListingEditorController(AccessService access, ListingEditorService service) {
    this.access = access;
    this.service = service;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, permission);
    return access.run(actor, permission, () -> work.apply(actor));
  }

  // ---- 能力声明 ----

  /**
   * 能力声明：刊登模板套用（4.2.2.1）、一键刊登派发（4.3.1.1）、合规预检（4.2.3.2）、
   * 类目属性细则（4.2.3.1）、03 文案库套用（3.4.2.2）**均 available=false** + ownerModule + 影响。
   *
   * <p>前端据此禁用「一键套用刊登模板」与任何「直接上线」按钮，并原样显示未接入说明，<b>不编造已交付</b>。
   */
  @GetMapping("/capabilities")
  public EditorCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.capabilities());
  }

  // ---- 动态表单 ----

  /**
   * 🔴 表单 schema：**字段集由平台要求推导**。
   *
   * <p>返回的每一栏都带 requirement（必填性）、constraintState（长度 / 条数上限知不知道）、
   * capabilityState（平台支不支持这一栏）—— 前端照这三项渲染，**不写死一套字段表**。
   */
  @GetMapping("/form-schema")
  public EditorFormSchema formSchema(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam UUID shopId,
      @RequestParam UUID marketplaceId,
      @RequestParam(required = false) String categoryPath,
      @RequestParam(required = false) String locale) {
    return call(
        auth,
        "channel.operation.read",
        a -> service.formSchema(shopId, marketplaceId, categoryPath, locale));
  }

  /** 试算校验（不落库）：逐条违规指到具体字段。 */
  @PostMapping("/validate")
  public EditorValidation validate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody EditorValidateRequest request) {
    return call(auth, "channel.operation.read", a -> service.validate(request));
  }

  // ---- 草稿 ----

  /** 草稿列表（按店铺 / 语言筛）。 */
  @GetMapping("/drafts")
  public List<EditorDraftRow> drafts(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID shopId,
      @RequestParam(required = false) String locale,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "channel.operation.read", a -> service.drafts(shopId, locale, page, size));
  }

  /** 单条草稿（含线上对照列）。跨租户与不存在返回逐字相同的 404。 */
  @GetMapping("/drafts/{id}")
  public EditorDraftRow draft(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.read", a -> service.draft(id));
  }

  /**
   * 🔴 保存草稿。
   *
   * <p>产出是草稿行。**线上 listing 的内容列在这次事务里由 DB 触发器拒写** ——
   * 界面提供的只有「保存草稿」，发布是 4.3.1.1 的另一条路径。
   */
  @PostMapping("/drafts")
  public EditorDraftRow saveDraft(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody EditorDraftRequest request) {
    return call(auth, "channel.operation.create", a -> service.save(request));
  }

  /**
   * 🔴 提交审核 = **入队**。
   *
   * <p>内容与校验证据冻结进 {@code listing_editor_submission}（QUEUED）。真正发给平台是
   * 4.3.1.1 一键刊登（尚未交付），所以这个动作**不是**"发布"。
   */
  @PostMapping("/drafts/{id}/submit")
  public EditorSubmissionRow submit(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody EditorSubmitRequest request) {
    return call(auth, "channel.operation.update", a -> service.submit(id, request.idempotencyKey()));
  }

  /** 入队队列（"提交发布动作"的可查视图）。 */
  @GetMapping("/submissions")
  public List<EditorSubmissionRow> submissions(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "channel.operation.read", a -> service.submissions(page, size));
  }

  // ---- 03 上游只读 ----

  /** 源商品（03 SKU 主数据，跨服务只读端口）。 */
  @GetMapping("/source-product")
  public EditorSourceProduct sourceProduct(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam String skuId) {
    return call(auth, "channel.operation.read", a -> service.sourceProduct(skuId, auth));
  }

  /** 媒体库素材（03 媒体库，跨服务只读端口）。 */
  @GetMapping("/media-assets")
  public List<EditorMediaAsset> mediaAssets(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String keyword) {
    return call(auth, "channel.operation.read", a -> service.mediaAssets(keyword, auth));
  }
}
