package com.starcon.catalog;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 4.2.2.2 描述模板的纯函数渲染引擎：把「五段骨架 + 变量绑定 + 图片插入位」拼成
 * **一份可以直接交给 4.2.1.1 编辑器与平台上传的描述 HTML**。
 *
 * <p>蓝图输出写的是「描述模板 + <b>渲染后的描述 HTML</b>」——所以本类返回的是拼装结果，
 * 不是模板字符串。模板字符串原样返回等于把拼装规则推给每个下游，
 * 而 04 刊登与平台上传对「这段 HTML 长什么样」必须只有一个答案。
 *
 * <p>与 {@link DescriptionRenderer}（3.4.2.2）的分工：那边负责「一个占位符取到什么值、
 * 取不到怎么报告」，本类只负责「五段按什么顺序拼、图片落在哪、HTML 怎么转义」。
 * 变量取值**直接调用**那边，不复制一份绑定逻辑——这是 V96 文件头「变量机制复用 V21」
 * 在代码层的落点。
 *
 * <p>行为上钉死四件事：
 *
 * <ol>
 *   <li><b>HTML 里绝不出现占位符。</b>取不到值的位置被剥空（复用 3.4.2.2 的剥空 + 空白整理），
 *       同时产出一条 {@link DescriptionRenderer.Issue}；段落被标成 INCOMPLETE。
 *   <li><b>缺译文不退回源语言。</b>该段落报 TRANSLATION_MISSING 并降级，
 *       悄悄退回源语言会让运营以为发出去的是译文。
 *   <li><b>图片落成 &lt;img&gt; 且位置可解析</b>：{@code data-position} 是渲染全局序号，
 *       {@code data-section-type} + {@code data-slot-no} 指出「第几段第几个插图位」。
 *   <li><b>同样的输入永远同样的输出</b>：不碰数据库、不读时钟、不掷骰子。
 * </ol>
 */
public final class DescriptionTemplateRenderer {
  private DescriptionTemplateRenderer() {}

  /** 一个图片插入位的声明（与 {@code description_image_slot} 一行对应）。 */
  public record ImageSlotSpec(
      UUID imageSlotId, int slotNo, String bindSource, String caption, UUID assetId, boolean required) {}

  /** 渲染时解析出来的一张图（与 {@code description_render_image} 一行对应）。 */
  public record ResolvedImage(
      UUID imageSlotId,
      int slotNo,
      UUID assetId,
      String src,
      String alt,
      BigDecimal widthPx,
      BigDecimal heightPx) {}

  /**
   * 一个段落的渲染输入。
   *
   * @param body 该语言的段落正文（源语言取 content_template.body，其余取译文行）
   * @param translationAvailable 该语言有没有正文。false 时报 TRANSLATION_MISSING
   *     并降级，**不**退回源语言
   */
  public record SectionSpec(
      UUID sectionId,
      String sectionType,
      int sortOrder,
      boolean showHeading,
      String locale,
      String body,
      boolean translationAvailable,
      List<DescriptionRenderer.VariableSpec> variables,
      List<ImageSlotSpec> imageSlots) {}

  /**
   * 一段渲染结果。
   *
   * @param html 这一段的完整 {@code <section>} 标签（可解析的 data-section-type）
   * @param complete 是否全部变量取到值且必填插图位都有图
   */
  public record SectionRendered(
      UUID sectionId,
      String sectionType,
      int sortOrder,
      String heading,
      String html,
      boolean complete,
      List<DescriptionRenderer.Issue> issues,
      List<ResolvedImage> images) {}

  /** 整份文档：{@code html} 是拼装好的详情页 HTML。 */
  public record RenderedDocument(
      String html, List<SectionRendered> sections, List<DescriptionRenderer.Issue> issues, boolean complete) {}

  /**
   * 渲染一份描述 HTML。
   *
   * @param templateCode 模板编码（落进根节点的 {@code data-template-code}）
   * @param sku 变量取值的事实来源（3.4.2.2 的取值口径，本类不重新实现）
   * @param pools 四类短语池。空池是常态，那时对应变量会被报成 EMPTY_PHRASE_POOL
   * @param resolvedImages 已经解析好的图片（服务层按 SKU 素材 / 调用方给图解析完传进来）。
   *     本类不查数据库，所以它可以逐条断言而不需要 PG。
   */
  public static RenderedDocument render(
      UUID templateId,
      String templateCode,
      String locale,
      List<SectionSpec> sections,
      DescriptionRenderer.SkuFacts sku,
      DescriptionRenderer.PhrasePools pools,
      Map<String, ResolvedImage> resolvedImages) {

    // 段落按 sort_order 排一次，后面不再重排：
    // 「这个模板有哪几段、按什么顺序」在模板保存时已经定死，
    // 渲染时再按枚举顺序排会让「保存时看到的顺序」与「渲染出来的顺序」可能不同。
    List<SectionSpec> ordered = new ArrayList<>(sections == null ? List.of() : sections);
    ordered.sort((a, b) -> a.sortOrder() != b.sortOrder()
        ? Integer.compare(a.sortOrder(), b.sortOrder())
        : a.sectionType().compareTo(b.sectionType()));

    List<SectionRendered> rendered = new ArrayList<>(ordered.size());
    List<DescriptionRenderer.Issue> allIssues = new ArrayList<>();
    int position = 0;

    for (SectionSpec spec : ordered) {
      SectionRendered section = renderSection(spec, sku, pools, resolvedImages, position);
      position += section.images().size();
      rendered.add(section);
      allIssues.addAll(section.issues());
    }

    boolean complete = !rendered.isEmpty() && rendered.stream().allMatch(SectionRendered::complete);
    StringBuilder html = new StringBuilder();
    html.append("<div class=\"description-template\" data-template-code=\"")
        .append(DescriptionTemplateRules.escapeAttribute(templateCode))
        .append("\" data-locale=\"")
        .append(DescriptionTemplateRules.escapeAttribute(locale))
        .append("\">");
    for (SectionRendered section : rendered) html.append(section.html());
    html.append("</div>");
    String document = html.toString();

    // 三重不变量，与 3.4.2.2 的 DescriptionRenderer 同一纪律。
    // 「宁可让渲染失败，也不能把 {{尺寸}} 原样发到平台」——V96 的 CHECK 与触发器
    // 在 DB 层钉了同一条，这里是第一道。
    if (document.indexOf('{') >= 0)
      throw new IllegalStateException(
          "渲染出的描述 HTML 里残留了占位符，这违反 4.2.2.2 的硬要求（变量未绑定必须显式提示，"
              + "不允许把 {{尺寸}} 原样发到平台）。多半是某个变量取值里带了花括号："
              + "请改掉那个值（SKU 规格 / 品牌名 / 短语）后重新渲染");
    if (complete && document.length() <= 0)
      throw new IllegalStateException("五段都标成填好了，但渲染结果是空的");
    if (!complete && rendered.isEmpty())
      throw new IllegalStateException("模板一个段落都没有却发起了渲染，状态无法解释");
    return new RenderedDocument(document, rendered, allIssues, complete);
  }

  /** 渲染一段。{@code positionRef} 是图片在整份文档里的起始序号（跨段落连续）。 */
  private static SectionRendered renderSection(
      SectionSpec spec,
      DescriptionRenderer.SkuFacts sku,
      DescriptionRenderer.PhrasePools pools,
      Map<String, ResolvedImage> resolvedImages,
      int startPosition) {

    String heading = DescriptionTemplateRules.defaultHeading(spec.sectionType());
    List<DescriptionRenderer.Issue> issues = new ArrayList<>();
    List<ResolvedImage> images = new ArrayList<>();

    String inner;
    boolean bodyOk;
    if (!spec.translationAvailable()) {
      // 缺译文：产出空正文 + 一条报告，段落降级。
      // **刻意不退回源语言**——那会让运营以为发出去的是译文（与 3.4.2.2 同一纪律）。
      inner = "";
      bodyOk = false;
      issues.add(
          new DescriptionRenderer.Issue(
              spec.locale(),
              "TRANSLATION_MISSING",
              "语言「"
                  + spec.locale()
                  + "」没有这一段的译文（段落类型「"
                  + DescriptionTemplateRules.sectionTypeLabel(spec.sectionType())
                  + "」）：本模块不会悄悄退回源语言，请先补上这一段的译文再渲染"));
    } else {
      var rendered =
          DescriptionRenderer.render(
              new DescriptionRenderer.TemplateSpec(
                  "DESCRIPTION", spec.body(), spec.variables()),
              sku,
              pools,
              // 短语池按段落序号取下标：同一 SKU 的五段会错开，
              // 读起来有变化又完全可复现（3.4.2.2 的 ordinal 同一口径）。
              spec.sortOrder());
      inner = DescriptionTemplateRules.escapeHtml(rendered.text());
      bodyOk = rendered.complete();
      issues.addAll(rendered.issues());
    }

    // ---- 图片插入位：按 slot_no 升序落在正文之后
    //
    // 落点是**确定的**：正文在先，插图位按 slot_no 从小到大跟在后面。
    // 之所以不追求「插在正文中间某个词后面」：那需要调用方在正文里插标记，
    // 而标记一旦没被替换就会变成又一处静默泄漏（比占位符更难发现）。
    int position = startPosition;
    boolean imagesOk = true;
    List<ImageSlotSpec> slots = new ArrayList<>(spec.imageSlots() == null ? List.of() : spec.imageSlots());
    slots.sort(java.util.Comparator.comparingInt(ImageSlotSpec::slotNo));
    StringBuilder flow = new StringBuilder();
    if (!inner.isEmpty()) flow.append("<p>").append(inner).append("</p>");
    for (ImageSlotSpec slot : slots) {
      ResolvedImage image = resolvedImages == null ? null : resolvedImages.get(key(slot, spec.sectionId()));
      if (image == null) {
        // 缺图不是「留个空位就算了」：required 的插图位没图时发布出去的详情页少一张图，
        // 同样是事故，所以它和缺变量一样把段落降级成 INCOMPLETE 并逐条报告。
        if (slot.required()) {
          imagesOk = false;
          issues.add(
              new DescriptionRenderer.Issue(
                  slotIssueName(slot),
                  "IMAGE_UNBOUND",
                  "必填的图片插入位（第 "
                      + (slot.slotNo() + 1)
                      + " 个，"
                      + DescriptionTemplateRules.bindSourceLabel(slot.bindSource())
                      + "）没有取到图："
                      + "渲染时补上这张图，或把这个插图位改成非必填。缺图的详情页不该发到平台"));
        }
        continue;
      }
      flow.append(imgTag(spec, slot, image, position));
      images.add(image);
      position++;
    }

    boolean complete = bodyOk && imagesOk;
    StringBuilder html = new StringBuilder();
    html.append("<section data-section-type=\"")
        .append(DescriptionTemplateRules.escapeAttribute(spec.sectionType()))
        .append("\">");
    if (spec.showHeading())
      html.append("<h3>").append(DescriptionTemplateRules.escapeText(heading)).append("</h3>");
    html.append(flow);
    html.append("</section>");
    return new SectionRendered(
        spec.sectionId(), spec.sectionType(), spec.sortOrder(), heading, html.toString(), complete, issues, images);
  }

  /**
   * 渲染出 {@code <img>}。
   *
   * <p>三个 data 属性是「可解析的位置」的全部约定：
   * {@code data-position} 全文档第几张、{@code data-section-type} 哪一段、
   * {@code data-slot-no} 这段第几个插图位。平台侧要改图序、下游要抽图，
   * 都能按属性取而不用正则数第几个 {@code <img>}。
   */
  private static String imgTag(
      SectionSpec spec, ImageSlotSpec slot, ResolvedImage image, int position) {
    StringBuilder tag = new StringBuilder("<img");
    tag.append(" data-position=\"").append(position).append('"');
    tag.append(" data-section-type=\"")
        .append(DescriptionTemplateRules.escapeAttribute(spec.sectionType()))
        .append('"');
    tag.append(" data-slot-no=\"").append(slot.slotNo()).append('"');
    tag.append(" src=\"").append(DescriptionTemplateRules.escapeAttribute(image.src())).append('"');
    // alt 优先用图注（运营在模板里写的），没写才用渲染时传入的那份。
    String alt = slot.caption() != null && !slot.caption().isEmpty() ? slot.caption() : image.alt();
    tag.append(" alt=\"").append(DescriptionTemplateRules.escapeAttribute(alt)).append('"');
    // 宽高来自素材库（NUMERIC，禁浮点）。调用方没指素材时留空——
    // **不猜、不填假值**：宽度写错会让平台裁图，而空属性浏览器会按原图渲染。
    if (image.widthPx() != null) tag.append(" width=\"").append(plain(image.widthPx())).append('"');
    if (image.heightPx() != null) tag.append(" height=\"").append(plain(image.heightPx())).append('"');
    return tag.append('>').toString();
  }

  /** NUMERIC → HTML 属性：toPlainString 保证不出现科学计数法（{@code 1.0E+3} 不是合法 CSS 像素）。 */
  private static String plain(BigDecimal value) {
    return value == null ? null : value.stripTrailingZeros().toPlainString();
  }

  /** 图片解析结果的键：一个插图位在一次渲染里唯一。 */
  public static String key(ImageSlotSpec slot, UUID sectionId) {
    return sectionId + "#" + slot.slotNo();
  }

  /** 缺失报告里用的插图位标识（DB 上 variable_name 只有 32 字符）。 */
  public static String slotIssueName(ImageSlotSpec slot) {
    return "slot-" + slot.slotNo();
  }
}
