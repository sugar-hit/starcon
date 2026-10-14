package com.starcon.catalog;

import static com.starcon.catalog.CatalogModels.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.*;

/**
 * 4.2.2.2 描述模板：五段骨架（产品介绍 / 使用场景 / 规格参数 / 注意事项 / 售后承诺）、 变量绑定、多语言版本、图片插入位，以及**渲染后的描述 HTML**。
 *
 * <p>路由挂在 {@code /catalog/description-templates} 之下，与 {@code /catalog/descriptions} （3.4.2.2
 * 描述与卖点库）**并列而不是嵌套**：两者共享 content_template 一张模板表， 但形状不同——那边是「一堆独立的长文本模板」，这边是「一个模板里排好序的五段骨架 +
 * 一次产出整份详情页 HTML」。合进一个前缀会让「取一个描述模板的段落结构」 与「批量填充一批长文本」看起来是同一个东西，而它们的服务端落点、渲染产物、 缺失报告口径全都不一样。
 *
 * <p>台账里的 {@code /listings/templates} 是**前端页面路径**（与 4.2.2.1 刊登模板同页签）， 不是这个后端前缀。
 *
 * <p>权限沿用 {@code catalog.spu.read} / {@code catalog.spu.update}，与 3.4.2.2 同一词表： 描述模板挂在 SKU 上、上游是
 * 3.1.1.1 的 SPU/SKU，不新造词表 （新词表要改 identity 服务的迁移，属于另一个服务的写入所有权）。
 *
 * <p><b>渲染是同步的「已完成」语义</b>：成本只与「段落数 × 变量数」成正比， 做成异步只会让运营为几十毫秒去盯进度条。
 */
@RestController
@RequestMapping("/catalog/description-templates")
public class DescriptionTemplateController {
  private final AccessService access;
  private final DescriptionTemplateService templates;
  private final DescriptionTemplateRenderService renders;

  public DescriptionTemplateController(
      AccessService access,
      DescriptionTemplateService templates,
      DescriptionTemplateRenderService renders) {
    this.access = access;
    this.templates = templates;
    this.renders = renders;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "catalog." + permission);
    return access.run(actor, "catalog." + permission, () -> work.apply(actor));
  }

  // ------------------------------------------------------------ 概览与词表

  /** 概览：模板 / 段落 / 插图位存量，以及「有多少渲染还带着缺失」。 */
  @GetMapping("/overview")
  public DescriptionTemplateOverview overview(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "spu.read", a -> templates.overview());
  }

  /**
   * 词表选项（前端下拉的数据源）。
   *
   * <p>与 3.4.2.2 的变量绑定源**同一份词表**：{{尺寸}} 的绑定源在全仓只有一个答案， 所以前端不该在这里自己硬编码一份。
   */
  @GetMapping("/options")
  public DescriptionTemplateOptions options(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "spu.read", a -> templates.options());
  }

  // ------------------------------------------------------------ 模板

  /** 模板列表（含逐段摘要）。停用的也在列表里——要看历史配置。 */
  @GetMapping("/templates")
  public List<DescriptionTemplateRow> templates(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "spu.read", a -> templates.templates());
  }

  @GetMapping("/templates/{id}")
  public DescriptionTemplateRow template(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "spu.read", a -> templates.template(id));
  }

  /** 段落详情（含变量、译文与图片插入位）——段落编辑器的数据源。 */
  @GetMapping("/templates/{id}/sections")
  public List<DescriptionSectionRow> sections(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "spu.read", a -> templates.sections(id));
  }

  /**
   * 建模板或改模板（id 为空即新建）。段落与插图位整体替换。
   *
   * <p>编码建后不可改：段落背后的内容模板编码由它派生， 改了会让历史渲染的「这份 HTML 是哪套模板渲染的」对不上。
   */
  @PostMapping("/templates")
  public DescriptionTemplateSaved createTemplate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody DescriptionTemplateInput input) {
    return call(auth, "spu.update", a -> templates.saveTemplate(a, null, input));
  }

  @PutMapping("/templates/{id}")
  public DescriptionTemplateSaved updateTemplate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody DescriptionTemplateInput input) {
    return call(auth, "spu.update", a -> templates.saveTemplate(a, id, input));
  }

  /**
   * 删除模板（连带段落、插图位与段落背后的内容模板）。
   *
   * <p>仍被渲染结果或 3.4.2.2 的批量填充引用时返回 400 并说明被引用多少次—— 要清理请改用停用。
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
          templates.deleteTemplate(a, id, version);
          return Boolean.TRUE;
        });
    return Map.of("deleted", true);
  }

  @PutMapping("/templates/{id}/enabled")
  public DescriptionTemplateSaved setEnabled(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam boolean enabled) {
    return call(auth, "spu.update", a -> templates.setEnabled(a, id, enabled));
  }

  // ------------------------------------------------------------ 渲染

  /**
   * 渲染一份描述 HTML（同步，返回即已完成）。
   *
   * <p>响应里的 {@code html} 就是可以直接交给 4.2.1.1 编辑器与平台上传的产物： 五个 {@code <section data-section-type="…">}
   * 按顺序串起来， 段落内的 {@code <img data-position="…">} 位置可解析， <b>绝不含任何占位符</b>。
   *
   * <p>缺变量 / 缺译文 / 必填插图位缺图都不会让请求失败，而是把对应段落标成 「缺变量或缺图」并逐条落报告——「这段里 brand 没填上」比「这次渲染失败了」有用得多。
   */
  @PostMapping("/renders")
  public DescriptionRenderProduct render(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody DescriptionRenderInput input) {
    return call(auth, "spu.update", a -> renders.render(a, input));
  }

  /** 渲染结果列表：分页 + 关键词（模板名 / 编码与 SKU 编码）+ 状态筛选。列表不带 HTML。 */
  @GetMapping("/renders")
  public DescriptionRenderPage listRenders(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) UUID skuId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(
        auth,
        "spu.read",
        a -> renders.listRenders(new DescriptionRenderQuery(keyword, status, skuId, page, size)));
  }

  /** 渲染详情：整份 HTML + 逐段 + 图片落点 + 逐条缺失报告。 */
  @GetMapping("/renders/{renderId}")
  public DescriptionRenderProduct renderDetail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID renderId) {
    return call(auth, "spu.read", a -> renders.product(renderId));
  }
}
