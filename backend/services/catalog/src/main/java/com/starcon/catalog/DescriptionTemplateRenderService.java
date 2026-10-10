package com.starcon.catalog;

import static com.starcon.catalog.CatalogModels.*;

import com.starcon.tenancy.Ids;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 4.2.2.2 描述模板的渲染服务：把五段骨架 + 变量绑定 + 多语言 + 图片插入位
 * 拼成**一份可以直接上传的描述 HTML**，并把它与缺失报告一起落库。
 *
 * <p>蓝图输出：「描述模板 + <b>渲染后的描述 HTML</b>（供编辑器与平台上传）」。
 * 所以这里返回的不是模板字符串，而�� {@link DescriptionRenderProduct#getHtml()}——
 * 五段 {@code <section data-section-type="…">} 按 {@code sort_order} 串起来，
 * 段落内的 {@code <img data-position="…">} 落成可解析的位置。
 *
 * <p><b>与 3.4.2.2 的批量填充刻意分开</b>（理由见 V96 文件头差异 C）：
 * 那边是「一批 N 个 SKU × 一种文案类型 → 填充后的描述文本」，
 * 这边是「一个模板 × 一个 SKU × 一种语言 → 一整份详情页 HTML」。
 * 两者只共用 content_template 与同一套变量取值代码（{@link DescriptionRenderer}）。
 *
 * <p>「变量未绑定必须显式提示」在这里有三重落点：
 *
 * <ol>
 *   <li>{@link DescriptionTemplateRenderer} 剥空占位符 + 逐条产 Issue；
 *   <li>缺变量的段落落成 {@code status='INCOMPLETE'}（V96 的 CHECK 与触发器从 DB 层
 *       保证「既缺变量又标成填好了」这个组合不存在）；
 *   <li>批次只要有一段不完整就落成 {@code PARTIAL}，且
 *       {@code description_render} 上有 CHECK：{@code SUCCEEDED} 的整份 HTML
 *       里不许出现任何花括号。
 * </ol>
 */
@Service
public class DescriptionTemplateRenderService {
  private final DSLContext sql;
  private final AccessService access;
  private final DescriptionTemplateService templates;
  private final DescriptionService descriptions;

  public DescriptionTemplateRenderService(
      DSLContext sql,
      AccessService access,
      DescriptionTemplateService templates,
      DescriptionService descriptions) {
    this.sql = sql;
    this.access = access;
    this.templates = templates;
    this.descriptions = descriptions;
  }

  // ============================================================ 渲染

  /**
   * 渲染一次描述 HTML（同步，返回即已完成）。
   *
   * <p>同步而不是异步任务：成本只与「段落数 × 变量数」成正比（几个字符串替换），
   * 做成异步只会让运营为几十毫秒去盯进度条（与 3.4.2.2 同一判断）。
   *
   * <p>缺译文**不整批拒**：本模块一次渲染的是一个 SKU，缺一段译文按
   * 「该段落报 TRANSLATION_MISSING 并降级 INCOMPLETE」处理更有用——
   * 运营立刻看到「就差英文规格参数段」，而不是提交失败后不知道差在哪。
   */
  public DescriptionRenderProduct render(AccessService.Access actor, DescriptionRenderInput input) {
    String localeProblem = DescriptionTemplateRules.localeProblem(input.locale());
    CatalogService.check(localeProblem == null, localeProblem);
    String locale = DescriptionTemplateRules.normalizeLocale(input.locale());
    List<DescriptionRenderInputImage> images = input.images() == null ? List.of() : input.images();
    CatalogService.check(
        DescriptionTemplateRules.imagesSizeProblem(images.size()) == null,
        DescriptionTemplateRules.imagesSizeProblem(images.size()));
    for (DescriptionRenderInputImage image : images) {
      CatalogService.check(image != null, "图片列表里有一条是空的");
      String urlProblem = DescriptionTemplateRules.imageUrlProblem(image.url());
      CatalogService.check(urlProblem == null, urlProblem);
      String altProblem = DescriptionTemplateRules.imageAltProblem(image.alt());
      CatalogService.check(altProblem == null, altProblem);
    }

    UUID tenantId = templates.currentTenantId();
    var template = templates.templateRow(input.templateId());
    CatalogService.check(
        Boolean.TRUE.equals(template.get("enabled", Boolean.class)),
        "描述模板「"
            + template.get("code", String.class)
            + "」已停用，不能发起渲染：停用后不能新建渲染，历史渲染照常可读。请先启用它");
    String sourceLocale = template.get("source_locale", String.class);
    String templateCode = template.get("code", String.class);

    // ---- 取齐段落骨架、正文、变量与插图位
    var sectionRows =
        sql.fetch(
            "select s.id,s.section_type,s.sort_order,s.show_heading,s.content_template_id,c.body,"
                + "c.source_locale from description_template_section s"
                + " join content_template c on c.tenant_id=s.tenant_id and c.id=s.content_template_id"
                + " where s.tenant_id=? and s.template_id=? order by s.sort_order,s.id",
            tenantId, input.templateId());
    CatalogService.check(
        !sectionRows.isEmpty(),
        "描述模板「"
            + templateCode
            + "」一个段落都没有，没法渲染出描述 HTML。请先配上段落"
            + "（产品介绍 / 使用场景 / 规格参数 / 注意事项 / 售后承诺）");
    List<UUID> sectionIds = sectionRows.stream().map(r -> r.get("id", UUID.class)).toList();
    List<UUID> contentTemplateIds =
        sectionRows.stream().map(r -> r.get("content_template_id", UUID.class)).toList();
    Map<UUID, List<ContentTemplateVariable>> variables = descriptions.variablesOf(contentTemplateIds);
    Map<UUID, List<ContentTranslation>> translations = descriptions.translationRows(contentTemplateIds);
    Map<UUID, List<DescriptionImageSlotRow>> slots = templates.imageSlotsOf(sectionIds);

    // ---- SKU 事实与短语池（3.4.2.2 的取值口径，直接复用）
    DescriptionRenderer.SkuFacts sku = skuFacts(input.skuId());
    DescriptionRenderer.PhrasePools pools =
        new DescriptionRenderer.PhrasePools(
            descriptions.phrasePool("SELLING"),
            descriptions.phrasePool("FEATURE"),
            descriptions.phrasePool("SCENE"),
            descriptions.phrasePool("CAUTION"));

    // ---- 图片解析：每个插图位落到哪张素材、哪个 URL
    Map<Integer, DescriptionRenderInputImage> given = new LinkedHashMap<>();
    for (DescriptionRenderInputImage image : images) given.put(image.slotNo(), image);
    Map<String, DescriptionTemplateRenderer.ResolvedImage> resolved = new LinkedHashMap<>();
    Map<UUID, MediaAsset> assetCache = new LinkedHashMap<>();
    for (Record r : sectionRows) {
      UUID sectionId = r.get("id", UUID.class);
      for (DescriptionImageSlotRow slot : slots.getOrDefault(sectionId, List.of())) {
        DescriptionTemplateRenderer.ImageSlotSpec spec = toSlotSpec(slot);
        DescriptionTemplateRenderer.ResolvedImage image = resolveImage(spec, sku, given, assetCache);
        if (image != null) resolved.put(DescriptionTemplateRenderer.key(spec, sectionId), image);
      }
    }

    // ---- 逐段组装渲染输入
    List<DescriptionTemplateRenderer.SectionSpec> specs = new ArrayList<>(sectionRows.size());
    for (Record r : sectionRows) {
      UUID sectionId = r.get("id", UUID.class);
      UUID contentTemplateId = r.get("content_template_id", UUID.class);
      boolean source = locale.equals(sourceLocale);
      String body = r.get("body", String.class);
      boolean available = source;
      if (!source) {
        DescriptionTranslationHit hit = translationHit(translations.get(contentTemplateId), locale);
        // **缺译文不退回源语言**：悄悄退回会让运营以为发出去的是译文。
        if (hit != null) {
          body = hit.body();
          available = true;
        }
      }
      List<DescriptionRenderer.VariableSpec> variableSpecs = new ArrayList<>();
      for (ContentTemplateVariable variable : variables.getOrDefault(contentTemplateId, List.of()))
        variableSpecs.add(
            new DescriptionRenderer.VariableSpec(variable.name(), variable.source(), variable.slotNo()));
      List<DescriptionTemplateRenderer.ImageSlotSpec> slotSpecs = new ArrayList<>();
      for (DescriptionImageSlotRow slot : slots.getOrDefault(sectionId, List.of()))
        slotSpecs.add(toSlotSpec(slot));
      specs.add(
          new DescriptionTemplateRenderer.SectionSpec(
              sectionId,
              r.get("section_type", String.class),
              r.get("sort_order", Integer.class),
              Boolean.TRUE.equals(r.get("show_heading", Boolean.class)),
              locale,
              body,
              available,
              variableSpecs,
              slotSpecs));
    }

    DescriptionTemplateRenderer.RenderedDocument document =
        DescriptionTemplateRenderer.render(
            input.templateId(), templateCode, locale, specs, sku, pools, resolved);

    // ---- 落库
    boolean complete = document.complete();
    int completeSections = 0;
    for (var section : document.sections()) if (section.complete()) completeSections++;
    String status =
        complete ? "SUCCEEDED" : (completeSections > 0 ? "PARTIAL" : "FAILED");
    int imageCount =
        document.sections().stream().mapToInt(s -> s.images().size()).sum();
    int issueCount = document.issues().size();

    UUID renderId = Ids.next();
    sql.execute(
        "insert into description_render(id,tenant_id,template_id,sku_id,requested_by,locale,status,"
            + "html,section_count,complete_count,incomplete_count,image_count,issue_count,note,version)"
            + " values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,0)",
        renderId,
        tenantId,
        input.templateId(),
        input.skuId(),
        actor.userId(),
        locale,
        status,
        document.html(),
        document.sections().size(),
        completeSections,
        document.sections().size() - completeSections,
        imageCount,
        issueCount,
        renderNote(status, document.sections(), issueCount));
    for (var section : document.sections()) {
      UUID sectionRenderId = Ids.next();
      String sectionStatus = section.complete() ? "COMPLETE" : "INCOMPLETE";
      sql.execute(
          "insert into description_render_section(id,tenant_id,render_id,section_id,section_type,"
              + "sort_order,locale,heading,html,image_count,status)"
              + " values(?,?,?,?,?,?,?,?,?,?,?)",
          sectionRenderId,
          tenantId,
          renderId,
          section.sectionId(),
          section.sectionType(),
          section.sortOrder(),
          locale,
          section.heading(),
          section.html(),
          section.images().size(),
          sectionStatus);
      for (DescriptionTemplateRenderer.ResolvedImage image : section.images())
        sql.execute(
            "insert into description_render_image(id,tenant_id,render_id,render_section_id,position,"
                + "slot_no,image_slot_id,asset_id,src,alt,width_px,height_px)"
                + " values(?,?,?,?,?,?,?,?,?,?,?,?)",
            Ids.next(),
            tenantId,
            renderId,
            sectionRenderId,
            positionOf(document, image),
            image.slotNo(),
            image.imageSlotId(),
            image.assetId(),
            image.src(),
            image.alt() == null ? "" : image.alt(),
            image.widthPx(),
            image.heightPx());
      for (DescriptionRenderer.Issue issue : section.issues())
        sql.execute(
            "insert into description_render_issue(id,tenant_id,render_id,render_section_id,sku_id,"
                + "variable_name,issue_kind,detail) values(?,?,?,?,?,?,?,?)",
            Ids.next(),
            tenantId,
            renderId,
            sectionRenderId,
            input.skuId(),
            issue.variableName(),
            issue.issueKind(),
            issue.detail());
    }
    templates.audit(
        actor,
        renderId,
        "DescriptionTemplateRendered",
        "模板 "
            + templateCode
            + "，SKU "
            + sku.skuCode()
            + "，语言 "
            + locale
            + "，"
            + completeSections
            + "/"
            + document.sections().size()
            + " 段已填好，"
            + imageCount
            + " 张图，"
            + issueCount
            + " 条缺失报告");
    return product(renderId);
  }

  // ============================================================ 图片解析

  private record MediaAsset(UUID id, String storageKey, String altText, java.math.BigDecimal widthPx,
      java.math.BigDecimal heightPx) {}

  private record DescriptionTranslationHit(String body) {}

  private static DescriptionTranslationHit translationHit(List<ContentTranslation> translations, String locale) {
    if (translations == null) return null;
    for (ContentTranslation translation : translations)
      if (translation.locale().equals(locale)) return new DescriptionTranslationHit(translation.body());
    return null;
  }

  private static DescriptionTemplateRenderer.ImageSlotSpec toSlotSpec(DescriptionImageSlotRow slot) {
    return new DescriptionTemplateRenderer.ImageSlotSpec(
        slot.id(), slot.slotNo(), slot.bindSource(), slot.caption(), slot.assetId(), slot.required());
  }

  /**
   * 解析一个插图位。
   *
   * <p>三种来源都落成同一件事：一个真实的 media_asset + 一个能进 {@code <img src>} 的地址。
   * 没有素材时返回 null（渲染器据此产出 {@code IMAGE_UNBOUND} 报告），
   * **绝不**返回一张占位图——一张假图发到平台上比缺图更糟。
   */
  private DescriptionTemplateRenderer.ResolvedImage resolveImage(
      DescriptionTemplateRenderer.ImageSlotSpec slot,
      DescriptionRenderer.SkuFacts sku,
      Map<Integer, DescriptionRenderInputImage> given,
      Map<UUID, MediaAsset> cache) {
    DescriptionRenderInputImage provided = given.get(slot.slotNo());
    UUID assetId = slot.assetId() != null ? slot.assetId() : (provided == null ? null : provided.assetId());
    MediaAsset asset = null;
    if (assetId != null) {
      asset = asset(assetId, cache);
    } else if ("SKU_PRIMARY".equals(slot.bindSource())) {
      asset = skuAsset(sku, "PRIMARY", 0, cache);
    } else if ("SKU_GALLERY".equals(slot.bindSource())) {
      asset = skuAsset(sku, "GALLERY", slot.slotNo(), cache);
    } else if (provided != null) {
      // RENDER_INPUT 且调用方只给了 URL：没有素材就没有宽高与 alt 兜底，
      // 但地址本身是真的（调用方已经解析好的对外链接），照样落进 src。
      return new DescriptionTemplateRenderer.ResolvedImage(
          slot.imageSlotId(),
          slot.slotNo(),
          null,
          provided.url().trim(),
          provided.alt() == null ? "" : provided.alt().trim(),
          provided.widthPx(),
          provided.heightPx());
    }
    if (asset == null) return null;
    // 有已解析 URL 就用它；没有就退回存储键那条约定路径
    // （V18 的 media_asset 只有 storage_key，对外 URL 由读取端现签）。
    String src = provided != null ? provided.url().trim() : "/api/media/" + asset.storageKey();
    return new DescriptionTemplateRenderer.ResolvedImage(
        slot.imageSlotId(),
        slot.slotNo(),
        asset.id(),
        src,
        provided != null && provided.alt() != null && !provided.alt().isBlank()
            ? provided.alt().trim()
            : (asset.altText() == null ? "" : asset.altText()),
        asset.widthPx(),
        asset.heightPx());
  }

  /** 按 id 取素材。RLS 之下查不到就是「不属于当前租户」，直接拒。 */
  private MediaAsset asset(UUID assetId, Map<UUID, MediaAsset> cache) {
    if (cache.containsKey(assetId)) return cache.get(assetId);
    var row =
        sql.fetchOne(
            "select id,storage_key,alt_text,width_px,height_px from media_asset where id=?",
            assetId);
    CatalogService.check(row != null, "素材不存在或不属于当前租户：" + assetId);
    MediaAsset asset =
        new MediaAsset(
            row.get("id", UUID.class),
            row.get("storage_key", String.class),
            row.get("alt_text", String.class),
            row.get("width_px", java.math.BigDecimal.class),
            row.get("height_px", java.math.BigDecimal.class));
    cache.put(assetId, asset);
    return asset;
  }

  /** 取 SKU 名下的素材（按 purpose 与序号）。停用的素材不算。 */
  private MediaAsset skuAsset(
      DescriptionRenderer.SkuFacts sku, String purpose, int ordinal, Map<UUID, MediaAsset> cache) {
    var row =
        sql.fetchOne(
            "select a.id from media_reference r join media_asset a"
                + "  on a.tenant_id=r.tenant_id and a.id=r.asset_id"
                + " where r.tenant_id=? and r.owner_type='SKU' and r.sku_id=?"
                + "  and a.purpose=? and a.media_type='IMAGE' and a.enabled"
                + " order by a.id offset ? limit 1",
            templates.currentTenantId(), sku.skuId(), purpose, Math.max(0, ordinal));
    return row == null ? null : asset(row.get("id", UUID.class), cache);
  }

  private static int positionOf(DescriptionTemplateRenderer.RenderedDocument document, DescriptionTemplateRenderer.ResolvedImage image) {
    int position = 0;
    for (var section : document.sections())
      for (var candidate : section.images()) {
        if (candidate == image) return position;
        position++;
      }
    return position;
  }

  // ============================================================ SKU 事实

  /** 取一个 SKU 的事实（3.4.2.2 同一份查询口径，变量取值才有唯一答案）。 */
  private DescriptionRenderer.SkuFacts skuFacts(UUID skuId) {
    var row =
        sql.fetchOne(
            "select s.id,s.code,s.name,s.variant_signature,p.name as spu_name,p.brand,p.category,"
                + "(select string_agg(k.code,' / ' order by k.code) from sku k"
                + "  where k.tenant_id=s.tenant_id and k.spu_id=s.spu_id and k.id<>s.id)"
                + "  as sibling_codes"
                + " from sku s join spu p on p.tenant_id=s.tenant_id and p.id=s.spu_id"
                + " where s.tenant_id=? and s.id=?",
            templates.currentTenantId(), skuId);
    if (row == null)
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, "SKU 不存在或不属于当前租户：" + skuId);
    String siblings = row.get("sibling_codes", String.class);
    return new DescriptionRenderer.SkuFacts(
        row.get("id", UUID.class),
        MediaRules.normalize(row.get("code", String.class)),
        MediaRules.normalize(row.get("name", String.class)),
        MediaRules.normalize(row.get("spu_name", String.class)),
        MediaRules.normalize(row.get("brand", String.class)),
        MediaRules.normalize(row.get("category", String.class)),
        MediaRules.normalize(row.get("variant_signature", String.class)),
        siblings == null || siblings.isBlank() ? List.of() : List.of(siblings));
  }

  private static String renderNote(
      String status, List<DescriptionTemplateRenderer.SectionRendered> sections, int issueCount) {
    if (issueCount == 0)
      return status.equals("SUCCEEDED")
          ? "五段全部填好，这份 HTML 可以直接交给 4.2.1.1 编辑器或平台上传。"
          : "没有缺失报告，但也没有一段能填好——请检查模板有没有配段落、译文有没有生效。";
    List<String> reasons = new ArrayList<>();
    for (var section : sections)
      for (var issue : section.issues())
        reasons.add(
            DescriptionTemplateRules.sectionTypeLabel(section.sectionType())
                + " / "
                + issue.variableName()
                + "（"
                + DescriptionTemplateRules.issueKindLabel(issue.issueKind())
                + "）");
    return "有 "
        + issueCount
        + " 处没填上（"
        + String.join("、", reasons)
        + "）：占位符已从 HTML 里剥掉，缺的段落标成「缺变量或缺图」，"
        + "这份结果不能直接发到平台——请补上模板变量绑定、短语池或插图位后重新渲染。";
  }

  // ============================================================ 读取渲染结果

  /** 渲染详情：产物 HTML + 逐段 + 图片落点 + 缺失报告。 */
  public DescriptionRenderProduct product(UUID renderId) {
    var row = renderRow(renderId);
    UUID tenantId = templates.currentTenantId();
    var sectionRows =
        sql.fetch(
            "select id,section_type,sort_order,heading,html,image_count,status"
                + " from description_render_section where tenant_id=? and render_id=?"
                + " order by sort_order,id",
            tenantId, renderId);
    List<UUID> sectionRenderIds = sectionRows.stream().map(r -> r.get("id", UUID.class)).toList();
    Map<UUID, List<DescriptionRenderImageRow>> images = renderImagesOf(sectionRenderIds);
    Map<UUID, List<DescriptionRenderIssueRow>> issues = renderIssuesOf(sectionRenderIds);

    List<DescriptionRenderSectionRow> out = new ArrayList<>(sectionRows.size());
    for (Record r : sectionRows) {
      UUID id = r.get("id", UUID.class);
      String sectionType = r.get("section_type", String.class);
      String sectionStatus = r.get("status", String.class);
      out.add(
          new DescriptionRenderSectionRow(
              id,
              sectionType,
              DescriptionTemplateRules.sectionTypeLabel(sectionType),
              r.get("sort_order", Integer.class),
              r.get("heading", String.class),
              r.get("html", String.class),
              sectionStatus,
              DescriptionTemplateRules.sectionStatusLabel(sectionStatus),
              r.get("image_count", Long.class),
              images.getOrDefault(id, List.of()),
              issues.getOrDefault(id, List.of())));
    }
    String status = row.get("status", String.class);
    return new DescriptionRenderProduct(
        renderId,
        row.get("template_id", UUID.class),
        row.get("template_code", String.class),
        row.get("sku_id", UUID.class),
        row.get("sku_code", String.class),
        row.get("locale", String.class),
        status,
        DescriptionTemplateRules.renderStatusLabel(status),
        row.get("html", String.class),
        row.get("image_count", Long.class),
        row.get("issue_count", Long.class),
        out,
        renderIssuesOfFlat(renderId),
        row.get("note", String.class),
        DescriptionTemplateService.instant(row.get("created_at", OffsetDateTime.class)));
  }

  /** 批次列表：分页 + 关键词（搜模板名 / 编码与 SKU 编码）+ 状态筛选。 */
  public DescriptionRenderPage listRenders(DescriptionRenderQuery query) {
    int page = Math.max(0, query.page());
    int size = Math.min(Math.max(1, query.size()), 200);
    UUID tenantId = templates.currentTenantId();
    String keyword = query.keyword() == null ? "" : MediaRules.normalize(query.keyword());
    String status = MediaRules.normalizeUpper(query.status());
    if (!status.isEmpty())
      CatalogService.check(
          DescriptionTemplateRules.renderStatusProblem(status) == null,
          DescriptionTemplateRules.renderStatusProblem(status));

    List<Object> args = new ArrayList<>();
    StringBuilder where = new StringBuilder(" where r.tenant_id=?");
    args.add(tenantId);
    if (!keyword.isEmpty()) {
      where.append(
          " and (t.code ilike ? escape '!' or t.name ilike ? escape '!'"
              + " or s.code ilike ? escape '!' or s.name ilike ? escape '!')");
      String like = DescriptionService.escapeLike(keyword);
      args.add(like);
      args.add(like);
      args.add(like);
      args.add(like);
    }
    if (!status.isEmpty()) {
      where.append(" and r.status=?");
      args.add(status);
    }
    if (query.skuId() != null) {
      where.append(" and r.sku_id=?");
      args.add(query.skuId());
    }

    long total =
        sql.fetchOne(
                "select count(*) from description_render r"
                    + " join description_template t on t.tenant_id=r.tenant_id and t.id=r.template_id"
                    + " join sku s on s.tenant_id=r.tenant_id and s.id=r.sku_id"
                    + where,
                args.toArray())
            .get(0, Long.class);

    List<Object> pageArgs = new ArrayList<>(args);
    pageArgs.add(size);
    pageArgs.add((long) page * size);
    List<DescriptionRenderRow> items = new ArrayList<>();
    for (Record r :
        sql.fetch(
            "select r.id,r.template_id,t.code as template_code,t.name as template_name,r.sku_id,"
                + "s.code as sku_code,r.locale,r.status,r.section_count,r.complete_count,"
                + "r.incomplete_count,r.image_count,r.issue_count,r.note,r.created_at"
                + " from description_render r"
                + " join description_template t on t.tenant_id=r.tenant_id and t.id=r.template_id"
                + " join sku s on s.tenant_id=r.tenant_id and s.id=r.sku_id"
                + where
                + " order by r.created_at desc, r.id desc limit ? offset ?",
            pageArgs.toArray())) {
      String renderStatus = r.get("status", String.class);
      items.add(
          new DescriptionRenderRow(
              r.get("id", UUID.class),
              r.get("template_id", UUID.class),
              r.get("template_code", String.class),
              r.get("template_name", String.class),
              r.get("sku_id", UUID.class),
              r.get("sku_code", String.class),
              r.get("locale", String.class),
              renderStatus,
              DescriptionTemplateRules.renderStatusLabel(renderStatus),
              r.get("section_count", Long.class),
              r.get("complete_count", Long.class),
              r.get("incomplete_count", Long.class),
              r.get("image_count", Long.class),
              r.get("issue_count", Long.class),
              r.get("note", String.class),
              DescriptionTemplateService.instant(r.get("created_at", OffsetDateTime.class))));
    }
    return new DescriptionRenderPage(
        items,
        total,
        page,
        size,
        "列表只给摘要，不带 HTML（一份详情页 HTML 动辄几 KB，列表页全带会卡）。"
            + "点开看详情时才会取那份 HTML。只有「五段全填好」的结果可以直接交给平台上传。");
  }

  private Record renderRow(UUID renderId) {
    var row =
        sql.fetchOne(
            "select r.id,r.template_id,t.code as template_code,r.sku_id,s.code as sku_code,"
                + "r.locale,r.status,r.html,r.image_count,r.issue_count,r.note,r.created_at"
                + " from description_render r"
                + " join description_template t on t.tenant_id=r.tenant_id and t.id=r.template_id"
                + " join sku s on s.tenant_id=r.tenant_id and s.id=r.sku_id"
                + " where r.id=?",
            renderId);
    if (row == null) throw CatalogService.missing();
    return row;
  }

  Map<UUID, List<DescriptionRenderImageRow>> renderImagesOf(List<UUID> sectionRenderIds) {
    if (sectionRenderIds.isEmpty()) return Map.of();
    Map<UUID, List<DescriptionRenderImageRow>> bySection = new LinkedHashMap<>();
    for (Record r :
        sql.fetch(
            "select id,render_section_id,position,slot_no,image_slot_id,asset_id,src,alt,"
                + "width_px,height_px from description_render_image where render_section_id in ("
                + DescriptionService.placeholders(sectionRenderIds)
                + ") order by position",
            sectionRenderIds.toArray()))
      bySection
          .computeIfAbsent(r.get("render_section_id", UUID.class), k -> new ArrayList<>())
          .add(
              new DescriptionRenderImageRow(
                  r.get("id", UUID.class),
                  r.get("position", Integer.class),
                  r.get("slot_no", Integer.class),
                  r.get("image_slot_id", UUID.class),
                  r.get("asset_id", UUID.class),
                  r.get("src", String.class),
                  r.get("alt", String.class),
                  r.get("width_px", java.math.BigDecimal.class),
                  r.get("height_px", java.math.BigDecimal.class)));
    return bySection;
  }

  Map<UUID, List<DescriptionRenderIssueRow>> renderIssuesOf(List<UUID> sectionRenderIds) {
    if (sectionRenderIds.isEmpty()) return Map.of();
    Map<UUID, List<DescriptionRenderIssueRow>> bySection = new LinkedHashMap<>();
    for (Record r :
        sql.fetch(
            "select id,render_section_id,variable_name,issue_kind,detail,created_at"
                + " from description_render_issue where render_section_id in ("
                + DescriptionService.placeholders(sectionRenderIds)
                + ") order by variable_name",
            sectionRenderIds.toArray()))
      bySection
          .computeIfAbsent(r.get("render_section_id", UUID.class), k -> new ArrayList<>())
          .add(toIssue(r));
    return bySection;
  }

  private List<DescriptionRenderIssueRow> renderIssuesOfFlat(UUID renderId) {
    List<DescriptionRenderIssueRow> out = new ArrayList<>();
    for (Record r :
        sql.fetch(
            "select id,render_section_id,variable_name,issue_kind,detail,created_at"
                + " from description_render_issue where render_id=? order by variable_name",
            renderId))
      out.add(toIssue(r));
    return out;
  }

  private static DescriptionRenderIssueRow toIssue(Record r) {
    String kind = r.get("issue_kind", String.class);
    return new DescriptionRenderIssueRow(
        r.get("id", UUID.class),
        r.get("variable_name", String.class),
        kind,
        DescriptionTemplateRules.issueKindLabel(kind),
        r.get("detail", String.class),
        DescriptionTemplateService.instant(r.get("created_at", OffsetDateTime.class)));
  }
}
