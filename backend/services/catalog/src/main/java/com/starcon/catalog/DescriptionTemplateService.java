package com.starcon.catalog;

import static com.starcon.catalog.CatalogModels.*;

import com.starcon.tenancy.Ids;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 4.2.2.2 描述模板：五段骨架（产品介绍 / 使用场景 / 规格参数 / 注意事项 / 售后承诺）、
 * 变量绑定、多语言版本与图片插入位。
 *
 * <p>蓝图原文：「职责：管理富文本描述模板：段落结构（产品介绍、使用场景、规格参数、
 * 注意事项、售后承诺）、变量占位、多语言版本、图片插入位。」
 * 「输出：描述模板 + 渲染后的描述 HTML（供编辑器与平台上传）。」
 * 「关系：上游 = 3.4.2.2 描述库、3.4.3 多语言；下游 = 4.2.1.1。
 * <b>变量未绑定必须显式提示</b>，不允许把 {@code {{尺寸}}} 原样发到平台。」
 *
 * <p><b>与 3.4.2.2（V21）的边界是这个类最重要的一件事</b>：段落正文、变量声明、
 * 译文、短语池**一律走 V21 那套表**（content_template / content_template_variable /
 * content_template_translation / content_phrase），本模块只新增「五段骨架」
 * （description_template / description_template_section / description_image_slot）。
 * 逐条理由写在 V96 文件头，代码层的落点就是本类的
 * {@link #saveSectionContent}：它建/改的是 content_template 行，不是另一张表。
 *
 * <p>所以「{{尺寸}} 的绑定源」在全仓只有一个答案：去 3.4.2.2 的内容模板编辑器改。
 * 04 刊登将来问「这个占位符取自哪里」时不会得到两个答案。
 *
 * <p>权限沿用 {@code catalog.spu.read} / {@code catalog.spu.update}：描述模板挂在
 * SKU 上、上游是 3.1.1.1 的 SPU/SKU，所以复用已有词表而不是新造
 * （新词表要改 identity 服务的迁移，属于另一个服务的写入所有权）。
 *
 * <p>渲染在 {@link DescriptionTemplateRenderService}，同一套纪律。
 */
@Service
public class DescriptionTemplateService {
  private final DSLContext sql;
  private final AccessService access;
  private final DescriptionService descriptions;

  public DescriptionTemplateService(
      DSLContext sql, AccessService access, DescriptionService descriptions) {
    this.sql = sql;
    this.access = access;
    this.descriptions = descriptions;
  }

  // ============================================================ 概览

  /**
   * 描述模板概览。
   *
   * <p>{@code missingSectionTypeCount}（有多少模板缺五类里的某一类）与
   * {@code issueCount}（渲染报告里累计缺了多少个变量 / 缺图）是这个模块
   * 唯一需要每天盯的两个数字。缺段落不拦保存——很多商品本来就没有独立售后承诺段——
   * 但它是「这个模板还不完整」的唯一可查询信号。
   */
  public DescriptionTemplateOverview overview() {
    UUID tenantId = currentTenantId();
    var templates =
        sql.fetchOne(
            "select count(*) as total,count(*) filter (where enabled) as enabled_count"
                + " from description_template where tenant_id=?",
            tenantId);
    var sections =
        sql.fetchOne(
            "select count(*) as total,"
                + "count(*) filter (where section_type='INTRO') as intro_count,"
                + "count(*) filter (where section_type='SCENE') as scene_count,"
                + "count(*) filter (where section_type='SPEC') as spec_count,"
                + "count(*) filter (where section_type='CAUTION') as caution_count,"
                + "count(*) filter (where section_type='SERVICE_PROMISE') as promise_count"
                + " from description_template_section where tenant_id=?",
            tenantId);
    long slotCount =
        sql.fetchOne("select count(*) from description_image_slot where tenant_id=?", tenantId)
            .get(0, Long.class);
    var renders =
        sql.fetchOne(
            "select count(*) as total,"
                + "count(*) filter (where status='SUCCEEDED') as succeeded_count,"
                + "count(*) filter (where status='PARTIAL') as partial_count,"
                + "count(*) filter (where status='FAILED') as failed_count"
                + " from description_render where tenant_id=?",
            tenantId);
    long issueCount =
        sql.fetchOne("select count(*) from description_render_issue where tenant_id=?", tenantId)
            .get(0, Long.class);

    // 「缺某一类段落」= 该类存在的模板数比模板总数少。停用的模板不参与统计，
    // 否则一个停用半年的半成品模板会永远占着一个缺口。
    long incompleteTemplates =
        sql.fetchOne(
                "select count(*) from description_template t where t.tenant_id=? and t.enabled"
                    + " and not exists (select 1 from description_template_section s"
                    + "   where s.tenant_id=t.tenant_id and s.template_id=t.id"
                    + "     and s.section_type=any(?))",
                tenantId,
                DescriptionTemplateRules.SECTION_TYPES.toArray(new String[0]))
            .get(0, Long.class);

    return new DescriptionTemplateOverview(
        templates.get("total", Long.class),
        templates.get("enabled_count", Long.class),
        sections.get("total", Long.class),
        slotCount,
        incompleteTemplates,
        renders.get("total", Long.class),
        renders.get("succeeded_count", Long.class),
        renders.get("partial_count", Long.class),
        renders.get("failed_count", Long.class),
        issueCount,
        "「缺段落模板数」是有启用中的描述模板还没配齐五类段落（产品介绍 / 使用场景 / "
            + "规格参数 / 注意事项 / 售后承诺）；缺段落不拦保存，因为很多商品本来就没有"
            + "独立的售后承诺段。「缺失报告数」是渲染时逐条落库的变量缺失与必填插图位缺图——"
            + "4.2.2.2 的硬要求是「变量未绑定必须显式提示」，所以它必须是一个能查到的数字，"
            + "而不是渲染失败时的一句报错。只有「五段全填好」（SUCCEEDED）的渲染结果才"
            + "可以直接交给 4.2.1.1 与平台上传。");
  }

  // ============================================================ 模板

  /** 模板列表（含逐段摘要）。按编码排序，停用的也在列表里（要看历史配置）。 */
  public List<DescriptionTemplateRow> templates() {
    var rows =
        sql.fetch(
            "select id,code,name,note,source_locale,enabled,version,created_at,updated_at"
                + " from description_template where tenant_id=? order by code",
            currentTenantId());
    if (rows.isEmpty()) return List.of();
    List<UUID> ids = rows.stream().map(r -> r.get("id", UUID.class)).toList();
    Map<UUID, List<DescriptionSectionSummary>> sections = sectionSummariesOf(ids);
    Map<UUID, Long> renders = renderCounts(ids);
    List<DescriptionTemplateRow> out = new ArrayList<>(rows.size());
    for (Record r : rows) {
      UUID id = r.get("id", UUID.class);
      List<DescriptionSectionSummary> summaries = sections.getOrDefault(id, List.of());
      out.add(
          new DescriptionTemplateRow(
              id,
              r.get("code", String.class),
              r.get("name", String.class),
              r.get("note", String.class),
              r.get("source_locale", String.class),
              Boolean.TRUE.equals(r.get("enabled", Boolean.class)),
              summaries,
              summaries.stream()
                  .mapToInt(s -> s.locales() == null ? 0 : s.locales().size())
                  .max()
                  .orElse(0),
              renders.getOrDefault(id, 0L),
              r.get("version", Long.class),
              instant(r.get("created_at", OffsetDateTime.class)),
              instant(r.get("updated_at", OffsetDateTime.class))));
    }
    return out;
  }

  /** 单个模板（含逐段摘要）。 */
  public DescriptionTemplateRow template(UUID id) {
    var row = templateRow(id);
    List<DescriptionSectionSummary> summaries =
        sectionSummariesOf(List.of(id)).getOrDefault(id, List.of());
    return new DescriptionTemplateRow(
        row.get("id", UUID.class),
        row.get("code", String.class),
        row.get("name", String.class),
        row.get("note", String.class),
        row.get("source_locale", String.class),
        Boolean.TRUE.equals(row.get("enabled", Boolean.class)),
        summaries,
        summaries.stream().mapToInt(s -> s.locales() == null ? 0 : s.locales().size()).max().orElse(0),
        renderCount(id),
        row.get("version", Long.class),
        instant(row.get("created_at", OffsetDateTime.class)),
        instant(row.get("updated_at", OffsetDateTime.class)));
  }

  /**
   * 建模板或改模板（id 为空即新建）。**段落与插图位整体替换。**
   *
   * <p>整体替换而不是增量增删：段落集合是「这个模板长什么样」的完整口径，
   * 增删式会让「当前有哪些段落」这个问题有两个答案
   * （与 3.4.2.2 的变量整体替换同一纪律）。
   *
   * <p>编码建后不可改：段落背后的 content_template 编码由它派生，
   * 改了会让「这段正文是哪一版模板」在历史渲染里对不上
   * （与 {@code MediaService.saveTag} / {@code TitleService.saveTemplate} 同一纪律）。
   */
  public DescriptionTemplateSaved saveTemplate(
      AccessService.Access actor, UUID id, DescriptionTemplateInput input) {
    List<DescriptionSectionInput> sectionInputs =
        input.sections() == null ? List.of() : input.sections();
    List<String> types = new ArrayList<>();
    for (DescriptionSectionInput section : sectionInputs) types.add(section.sectionType());
    String problem =
        firstNonNull(
            DescriptionTemplateRules.templateCodeProblem(input.code()),
            DescriptionTemplateRules.templateNameProblem(input.name()),
            DescriptionTemplateRules.localeProblem(input.sourceLocale()),
            DescriptionTemplateRules.sectionsSizeProblem(sectionInputs.size()),
            DescriptionTemplateRules.sectionTypesProblem(types));
    CatalogService.check(problem == null, problem);

    for (DescriptionSectionInput section : sectionInputs) sectionProblems(section);
    String sourceLocale = DescriptionTemplateRules.normalizeLocale(input.sourceLocale());
    String code = MediaRules.normalize(input.code()).toUpperCase(Locale.ROOT);
    CatalogService.check(
        sectionInputs.size() > 0,
        "一个描述模板至少要有一段：蓝图要求的是「段落结构」，"
            + "至少配一段（建议五段齐全）才能渲染出描述 HTML");

    UUID tenantId = currentTenantId();
    if (id != null) {
      var existing = templateRow(id);
      long version = existing.get("version", Long.class);
      CatalogService.check(
          version == input.version(),
          "这个描述模板已被他人改动（当前版本 " + version + "，你提交的是 " + input.version()
              + "）：请刷新后重新编辑，避免覆盖掉别人的修改");
      int updated =
          sql.execute(
              "update description_template set name=?,note=?,source_locale=?,enabled=?,"
                  + "version=version+1,updated_at=now() where tenant_id=? and id=? and version=?",
              MediaRules.normalize(input.name()),
              input.note() == null ? "" : input.note(),
              sourceLocale,
              input.enabled(),
              tenantId,
              id,
              input.version());
      if (updated != 1) throw conflict("描述模板已被他人修改，请刷新后重试");
    } else {
      id = Ids.next();
      sql.execute(
          "insert into description_template(id,tenant_id,code,name,note,source_locale,enabled,version)"
              + " values(?,?,?,?,?,?,?,0)",
          id,
          tenantId,
          code,
          MediaRules.normalize(input.name()),
          input.note() == null ? "" : input.note(),
          sourceLocale,
          input.enabled());
    }

    replaceSections(tenantId, id, code, sourceLocale, sectionInputs);
    audit(actor, id, "DescriptionTemplateSaved", code + "，" + sectionInputs.size() + " 段");
    return templateSaved(id);
  }

  /** 段落的逐条校验：类型、正文、变量双向绑定、插图位。 */
  private void sectionProblems(DescriptionSectionInput section) {
    if (section == null) {
      CatalogService.check(false, "段落列表里有一条是空的");
      return;
    }
    String problem =
        firstNonNull(
            DescriptionTemplateRules.sectionTypeProblem(section.sectionType()),
            DescriptionRules.bodyProblem(DescriptionRules.normalizeBody(section.body())),
            DescriptionRules.variablesSizeProblem(
                section.variables() == null ? 0 : section.variables().size()),
            DescriptionTemplateRules.imageSlotsSizeProblem(
                section.imageSlots() == null ? 0 : section.imageSlots().size()),
            variablesProblem(section));
    CatalogService.check(problem == null, problem);
    if (section.imageSlots() == null) return;
    for (int i = 0; i < section.imageSlots().size(); i++) {
      DescriptionImageSlotInput slot = section.imageSlots().get(i);
      if (slot == null) CatalogService.check(false, "图片插入位列表里有一条是空的");
      String slotProblem =
          firstNonNull(
              DescriptionTemplateRules.bindSourceProblem(slot.bindSource()),
              DescriptionTemplateRules.imageAltProblem(slot.caption()));
      CatalogService.check(slotProblem == null, slotProblem);
      CatalogService.check(
          slot.slotNo() == i,
          "图片插入位的编号必须从 0 连续递增（当前第 " + (i + 1) + " 个是 " + slot.slotNo()
              + "）：data-slot-no 是渲染出来给平台侧定位用的位置，编号不连续会让下游数不清图序");
      // assetId 有意**不**必填：RENDER_INPUT 的定义就是「这一位完全由渲染请求给 URL」，
      // 素材只是可选项（给了就能带上素材库探测出的宽高与 alt 兜底）。
    }
  }

  /**
   * 变量的双向校验：正文里的占位符与声明必须互相对得上。
   *
   * <p>与 3.4.2.2 的 {@code DescriptionService.variablesProblem} 同一纪律，
   * 连校验函数都用同一份（{@link DescriptionRules#variableBindingProblem}）。
   * 配置期的缺失比渲染期的缺失更早暴露，成本也更低。
   */
  private String variablesProblem(DescriptionSectionInput section) {
    List<String> names = new ArrayList<>();
    if (section.variables() != null)
      for (ContentTemplateVariableInput variable : section.variables()) {
        if (variable == null) return "变量列表里有一条是空的";
        String nameProblem = DescriptionRules.variableNameProblem(variable.name());
        if (nameProblem != null) return nameProblem;
        String sourceProblem = DescriptionRules.sourceProblem(variable.source());
        if (sourceProblem != null) return sourceProblem;
        if (names.contains(MediaRules.normalize(variable.name())))
          return "变量名重复："
              + MediaRules.normalize(variable.name())
              + "（同一个占位符只能有一个绑定源）";
        names.add(MediaRules.normalize(variable.name()));
      }
    return DescriptionRules.variableBindingProblem(DescriptionRules.normalizeBody(section.body()), names);
  }

  /** 段落整体替换：先删旧段落（连带插图位），再按输入顺序重建。 */
  private void replaceSections(
      UUID tenantId,
      UUID templateId,
      String templateCode,
      String sourceLocale,
      List<DescriptionSectionInput> sectionInputs) {
    // 旧段落背后的 content_template_id 必须在删段落**之前**取出来，
    // 删完就查不到了，而它们正是这次要改写的目标。
    List<UUID> oldContentTemplateIds = new ArrayList<>();
    for (Record r :
        sql.fetch(
            "select content_template_id from description_template_section"
                + " where tenant_id=? and template_id=?",
            tenantId, templateId))
      oldContentTemplateIds.add(r.get("content_template_id", UUID.class));
    sql.execute(
        "delete from description_template_section where tenant_id=? and template_id=?",
        tenantId, templateId);

    int order = 0;
    for (DescriptionSectionInput section : sectionInputs) {
      String type = MediaRules.normalizeUpper(section.sectionType());
      UUID contentTemplateId =
          saveSectionContent(tenantId, templateCode, type, sourceLocale, section);
      UUID sectionId = Ids.next();
      sql.execute(
          "insert into description_template_section(id,tenant_id,template_id,section_type,"
              + "sort_order,content_template_id,show_heading) values(?,?,?,?,?,?,?)",
          sectionId,
          tenantId,
          templateId,
          type,
          order++,
          contentTemplateId,
          section.showHeading());
      int slot = 0;
      if (section.imageSlots() != null)
        for (DescriptionImageSlotInput imageSlot : section.imageSlots())
          sql.execute(
              "insert into description_image_slot(id,tenant_id,section_id,slot_no,caption,"
                  + "bind_source,asset_id,required) values(?,?,?,?,?,?,?,?)",
              Ids.next(),
              tenantId,
              sectionId,
              slot++,
              MediaRules.normalize(imageSlot.caption()),
              MediaRules.normalizeUpper(imageSlot.bindSource()),
              imageSlot.assetId(),
              imageSlot.required());
    }

    // 被换掉（不再挂在任何段落上）的旧 content_template 一并删掉。
    // 不删会留下一堆「不再被任何描述模板引用」的内容模板，
    // 而 3.4.2.2 的模板列表里会出现一堆没人知道为什么在那儿的东西。
    List<UUID> kept = new ArrayList<>();
    for (Record r :
        sql.fetch(
            "select content_template_id from description_template_section where tenant_id=?",
            tenantId))
      kept.add(r.get("content_template_id", UUID.class));
    for (UUID oldId : oldContentTemplateIds)
      if (!kept.contains(oldId)) {
        long refs =
            sql.fetchOne("select count(*) from content_render where template_id=?", oldId)
                .get(0, Long.class);
        // 3.4.2.2 自己的填充批次引用过它时不删：V21 的 refuse_content_template_delete
        // 也会拦，这里先查是为了给出可读的 400 而不是裸的数据库异常。
        if (refs == 0)
          sql.execute("delete from content_template where tenant_id=? and id=?", tenantId, oldId);
      }
  }

  /**
   * 建/改一个段落背后的 content_template（3.4.2.2 的表，本模块**不复制**内容）。
   *
   * <p>段落编码由「模板编码 + 段落类型」派生而不是让调用方传：
   * 一个段落天然属于一个描述模板，让运营再填一遍编码只会产生两套不一致的编码。
   * 长度截断到 32（V21 的 content_template.code 硬上限），冲突时报可读的错。
   */
  private UUID saveSectionContent(
      UUID tenantId,
      String templateCode,
      String sectionType,
      String sourceLocale,
      DescriptionSectionInput section) {
    String code = sectionCode(templateCode, sectionType);
    String body = DescriptionRules.normalizeBody(section.body());
    UUID id =
        section.contentTemplateId() != null
            ? section.contentTemplateId()
            : findContentTemplateByCode(code).orElse(null);
    if (id != null) {
      int updated =
          sql.execute(
              "update content_template set name=?,body=?,source_locale=?,enabled=true,"
                  + "version=version+1,updated_at=now() where tenant_id=? and id=?",
              MediaRules.normalize(sectionTypeLabelForContent(sectionType)),
              body,
              sourceLocale,
              tenantId,
              id);
      if (updated != 1) throw CatalogService.missing();
    } else {
      id = Ids.next();
      sql.execute(
          "insert into content_template(id,tenant_id,code,name,note,content_type,body,"
              + "source_locale,enabled,version) values(?,?,?,?,?,'DESCRIPTION',?,?,true,0)",
          id,
          tenantId,
          code,
          MediaRules.normalize(sectionTypeLabelForContent(sectionType)),
          "4.2.2.2 描述模板「" + templateCode + "」的段落",
          body,
          sourceLocale);
    }
    replaceVariables(tenantId, id, section.variables());
    return id;
  }

  /** content_template 的 name 字段用中文段落名（页面列表直接显示它）。 */
  private static String sectionTypeLabelForContent(String sectionType) {
    return DescriptionTemplateRules.sectionTypeLabel(sectionType);
  }

  /** 段落编码：模板编码 + 段落类型缩写，截断到 32 位。 */
  static String sectionCode(String templateCode, String sectionType) {
    String suffix =
        switch (MediaRules.normalizeUpper(sectionType)) {
          case "INTRO" -> "INTRO";
          case "SCENE" -> "SCENE";
          case "SPEC" -> "SPEC";
          case "CAUTION" -> "CAUTION";
          case "SERVICE_PROMISE" -> "PROMISE";
          default -> "SEC";
        };
    String base = templateCode.length() + 1 + suffix.length() <= 32 ? templateCode : templateCode.substring(0, 32 - 1 - suffix.length());
    String code = base + "_" + suffix;
    return code.replaceAll("[^A-Z0-9_-]", "");
  }

  /** 变量整体替换：先删后插，保证「当前有哪些变量」只有一份答案。 */
  private void replaceVariables(UUID tenantId, UUID contentTemplateId, List<ContentTemplateVariableInput> variables) {
    sql.execute(
        "delete from content_template_variable where tenant_id=? and template_id=?",
        tenantId, contentTemplateId);
    if (variables == null || variables.isEmpty()) return;
    int slot = 0;
    for (ContentTemplateVariableInput variable : variables)
      sql.execute(
          "insert into content_template_variable(id,tenant_id,template_id,slot_no,variable_name,source)"
              + " values(?,?,?,?,?,?)",
          Ids.next(),
          tenantId,
          contentTemplateId,
          slot++,
          MediaRules.normalize(variable.name()),
          MediaRules.normalizeUpper(variable.source()));
  }

  public DescriptionTemplateSaved setEnabled(AccessService.Access actor, UUID id, boolean enabled) {
    int updated =
        sql.execute(
            "update description_template set enabled=?,version=version+1,updated_at=now()"
                + " where tenant_id=? and id=?",
            enabled, currentTenantId(), id);
    if (updated != 1) throw CatalogService.missing();
    audit(actor, id, "DescriptionTemplateEnabled", enabled ? "启用" : "停用");
    return templateSaved(id);
  }

  /**
   * 删除模板：连带段落、插图位与段落背后的内容模板一起删。
   *
   * <p>仍被渲染结果引用时返回 400 并说明被引用多少次——历史渲染的 HTML 是按当时的
   * 段落与变量绑定产出的，删掉之后「这份 HTML 是哪套模板渲染的」就答不上来了。
   * 要清理请改用停用（停用后不能再用它发起渲染，历史渲染照常可读）。
   */
  public void deleteTemplate(AccessService.Access actor, UUID id, long version) {
    var existing = templateRow(id);
    long renders = renderCount(id);
    CatalogService.check(
        renders == 0,
        "描述模板「"
            + existing.get("code", String.class)
            + "」已被 "
            + renders
            + " 次渲染引用，不可删除：历史渲染的 HTML 是按当时的段落与变量绑定产出的。"
            + "要清理请改用停用（停用后不能再用它发起渲染，历史渲染照常可读）");
    CatalogService.check(
        existing.get("version", Long.class) == version,
        "这个描述模板已被他人改动（当前版本 " + existing.get("version", Long.class)
            + "，你提交的是 " + version + "）：请刷新后重试");

    UUID tenantId = currentTenantId();
    // 段落背后的 content_template 要在删模板**之前**取出来：删模板会级联删掉段落，
    // 之后查不到它们，而这些行正是这次要连带清理的目标。
    List<UUID> contentTemplateIds = new ArrayList<>();
    for (Record r :
        sql.fetch(
            "select content_template_id from description_template_section"
                + " where tenant_id=? and template_id=?",
            tenantId, id))
      contentTemplateIds.add(r.get("content_template_id", UUID.class));

    // 被 3.4.2.2 的填充批次引用过的段落内容不能删，而留着它又会变成
    // 「不在任何描述模板里的孤儿模板」。所以在动手之前就报清楚，
    // 而不是删到一半撞 FK —— 那会留下一个骨架只剩一半的模板。
    for (UUID contentTemplateId : contentTemplateIds) {
      long refs =
          sql.fetchOne("select count(*) from content_render where template_id=?", contentTemplateId)
              .get(0, Long.class);
      CatalogService.check(
          refs == 0,
          "描述模板「"
              + existing.get("code", String.class)
              + "」的段落内容已被 3.4.2.2 的批量填充引用 "
              + refs
              + " 次，不能连带删除：那批填充出来的描述文本是按这套变量绑定填的，"
              + "删掉之后历史段落就失去了正文出处。请改用停用");
    }

    // 顺序要紧：先删描述模板（级联删段落与插图位，解除 description_template_section
    // 对 content_template 的 RESTRICT 外键），再删段落背后的内容模板。
    // 反过来会撞外键，留下一个骨架还在但正文没了的模板。
    try {
      sql.execute("delete from description_template where tenant_id=? and id=?", tenantId, id);
      for (UUID contentTemplateId : contentTemplateIds)
        sql.execute(
            "delete from content_template where tenant_id=? and id=?", tenantId, contentTemplateId);
    } catch (DataAccessException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, rootMessage(e));
    }
    audit(actor, id, "DescriptionTemplateDeleted", null);
  }

  // ============================================================ 段落读取

  /** 某模板的段落详情（含变量、译文与插图位）。段落编辑器的数据源。 */
  public List<DescriptionSectionRow> sections(UUID templateId) {
    var template = templateRow(templateId);
    String sourceLocale = template.get("source_locale", String.class);
    var rows =
        sql.fetch(
            "select s.id,s.section_type,s.sort_order,s.show_heading,s.content_template_id,"
                + "c.body from description_template_section s"
                + " join content_template c on c.tenant_id=s.tenant_id and c.id=s.content_template_id"
                + " where s.tenant_id=? and s.template_id=? order by s.sort_order,s.id",
            currentTenantId(), templateId);
    if (rows.isEmpty()) return List.of();
    List<UUID> sectionIds = rows.stream().map(r -> r.get("id", UUID.class)).toList();
    List<UUID> contentTemplateIds = rows.stream().map(r -> r.get("content_template_id", UUID.class)).toList();
    Map<UUID, List<ContentTemplateVariable>> variables = descriptions.variablesOf(contentTemplateIds);
    Map<UUID, List<ContentTranslation>> translations = descriptions.translationRows(contentTemplateIds);
    Map<UUID, List<DescriptionImageSlotRow>> slots = imageSlotsOf(sectionIds);

    List<DescriptionSectionRow> out = new ArrayList<>(rows.size());
    for (Record r : rows) {
      UUID sectionId = r.get("id", UUID.class);
      String sectionType = r.get("section_type", String.class);
      UUID contentTemplateId = r.get("content_template_id", UUID.class);
      List<ContentTranslation> all = translations.getOrDefault(contentTemplateId, List.of());
      List<ContentTranslation> withoutSource = new ArrayList<>();
      for (ContentTranslation translation : all)
        if (!translation.locale().equals(sourceLocale)) withoutSource.add(translation);
      out.add(
          new DescriptionSectionRow(
              sectionId,
              templateId,
              sectionType,
              DescriptionTemplateRules.sectionTypeLabel(sectionType),
              r.get("sort_order", Integer.class),
              contentTemplateId,
              r.get("body", String.class),
              Boolean.TRUE.equals(r.get("show_heading", Boolean.class)),
              variables.getOrDefault(contentTemplateId, List.of()),
              withoutSource,
              slots.getOrDefault(sectionId, List.of())));
    }
    return out;
  }

  /** 词表选项（前端下拉的数据源，与 {@link DescriptionTemplateRules} 同源）。 */
  public DescriptionTemplateOptions options() {
    return new DescriptionTemplateOptions(
        DescriptionTemplateRules.sectionTypeOptions(),
        DescriptionTemplateRules.imageBindSourceOptions(),
        DescriptionTemplateRules.renderStatusOptions(),
        DescriptionTemplateRules.issueKindOptions(),
        java.util.stream.Stream.concat(
                DescriptionRules.FACT_SOURCES.stream(), DescriptionRules.PHRASE_SOURCES.stream())
            .map(
                source ->
                    new DescriptionTemplateRules.Option(source, DescriptionRules.sourceLabel(source)))
            .toList(),
        DescriptionTemplateRules.defaultPhrasesBySectionType(),
        "变量绑定源与 3.4.2.2 完全同一份词表：{{尺寸}} 的绑定源在全仓只有一个答案，"
            + "所以描述模板不需要另造一套。五类段落建议的短语绑定源仅供参考——"
            + "规格参数段通常绑事实字段（SKU 规格 / 同系列型号清单）而不是短语池。");
  }

  // ============================================================ 行映射与工具

  Record templateRow(UUID id) {
    var row =
        sql.fetchOne(
            "select id,code,name,note,source_locale,enabled,version,created_at,updated_at"
                + " from description_template where id=?",
            id);
    if (row == null) throw CatalogService.missing();
    return row;
  }

  Record sectionRow(UUID id) {
    var row =
        sql.fetchOne(
            "select id,template_id,section_type,sort_order,show_heading,content_template_id"
                + " from description_template_section where id=?",
            id);
    if (row == null) throw CatalogService.missing();
    return row;
  }

  DescriptionTemplateSaved templateSaved(UUID id) {
    var row = templateRow(id);
    int sections =
        sql.fetchOne("select count(*) from description_template_section where template_id=?", id)
            .get(0, Integer.class);
    int slots =
        sql.fetchOne(
                "select count(*) from description_image_slot s join description_template_section d "
                    + "on d.tenant_id=s.tenant_id and d.id=s.section_id"
                    + " where d.template_id=?",
                id)
            .get(0, Integer.class);
    return new DescriptionTemplateSaved(
        id, row.get("code", String.class), row.get("version", Long.class), sections, slots, renderCount(id));
  }

  /** 段落摘要按模板分组（列表页一次查齐，避免逐模板查一次）。 */
  private Map<UUID, List<DescriptionSectionSummary>> sectionSummariesOf(List<UUID> templateIds) {
    if (templateIds.isEmpty()) return Map.of();
    Map<UUID, List<DescriptionSectionSummary>> byTemplate = new LinkedHashMap<>();
    Map<UUID, Long> slotCounts = new LinkedHashMap<>();
    for (Record r :
        sql.fetch(
            "select s.section_id,count(*) as total from description_image_slot s"
                + " where s.section_id in ("
                + DescriptionService.placeholders(templateIds)
                + ") group by s.section_id",
            templateIds.toArray()))
      slotCounts.put(r.get("section_id", UUID.class), r.get("total", Long.class));

    for (Record r :
        sql.fetch(
            "select s.template_id,s.id,s.section_type,s.sort_order,s.content_template_id,"
                + "(select count(*) from content_template_variable v"
                + "  where v.tenant_id=s.tenant_id and v.template_id=s.content_template_id) as variable_count,"
                + "(select string_agg(t.locale,'|' order by t.locale)"
                + "  from content_template_translation t"
                + "  where t.tenant_id=s.tenant_id and t.template_id=s.content_template_id) as locale_list"
                + " from description_template_section s where s.template_id in ("
                + DescriptionService.placeholders(templateIds)
                + ") order by s.template_id,s.sort_order,s.id",
            templateIds.toArray())) {
      String localeList = r.get("locale_list", String.class);
      List<String> locales =
          localeList == null || localeList.isBlank() ? List.of() : List.of(localeList.split("\\|"));
      String sectionType = r.get("section_type", String.class);
      byTemplate
          .computeIfAbsent(r.get("template_id", UUID.class), k -> new ArrayList<>())
          .add(
              new DescriptionSectionSummary(
                  sectionType,
                  DescriptionTemplateRules.sectionTypeLabel(sectionType),
                  r.get("sort_order", Integer.class),
                  r.get("content_template_id", UUID.class),
                  r.get("variable_count", Integer.class),
                  slotCounts.getOrDefault(r.get("id", UUID.class), 0L).intValue(),
                  locales));
    }
    return byTemplate;
  }

  /** 图片插入位按段落分组。 */
  Map<UUID, List<DescriptionImageSlotRow>> imageSlotsOf(List<UUID> sectionIds) {
    if (sectionIds.isEmpty()) return Map.of();
    Map<UUID, List<DescriptionImageSlotRow>> bySection = new LinkedHashMap<>();
    for (Record r :
        sql.fetch(
            "select id,section_id,slot_no,bind_source,caption,asset_id,required"
                + " from description_image_slot where section_id in ("
                + DescriptionService.placeholders(sectionIds)
                + ") order by section_id,slot_no",
            sectionIds.toArray())) {
      String bindSource = r.get("bind_source", String.class);
      bySection
          .computeIfAbsent(r.get("section_id", UUID.class), k -> new ArrayList<>())
          .add(
              new DescriptionImageSlotRow(
                  r.get("id", UUID.class),
                  r.get("section_id", UUID.class),
                  r.get("slot_no", Integer.class),
                  bindSource,
                  DescriptionTemplateRules.bindSourceLabel(bindSource),
                  r.get("caption", String.class),
                  r.get("asset_id", UUID.class),
                  Boolean.TRUE.equals(r.get("required", Boolean.class)),
                  r.get("asset_id", UUID.class) != null));
    }
    return bySection;
  }

  /** 各模板被渲染的次数（模板列表页的「引用数」，删除保护在列表上就看得见）。 */
  private Map<UUID, Long> renderCounts(List<UUID> templateIds) {
    if (templateIds.isEmpty()) return Map.of();
    Map<UUID, Long> out = new LinkedHashMap<>();
    for (Record r :
        sql.fetch(
            "select template_id,count(*) as total from description_render"
                + " where template_id in ("
                + DescriptionService.placeholders(templateIds)
                + ") group by template_id",
            templateIds.toArray()))
      out.put(r.get("template_id", UUID.class), r.get("total", Long.class));
    return out;
  }

  long renderCount(UUID templateId) {
    return sql.fetchOne("select count(*) from description_render where template_id=?", templateId)
        .get(0, Long.class);
  }

  java.util.Optional<UUID> findContentTemplateByCode(String code) {
    var row = sql.fetchOne("select id from content_template where code=?", code);
    return row == null ? java.util.Optional.empty() : java.util.Optional.of(row.get("id", UUID.class));
  }

  UUID currentTenantId() {
    return sql.fetchOne("select nullif(current_setting('app.current_tenant',true),'')::uuid")
        .get(0, UUID.class);
  }

  void audit(AccessService.Access actor, UUID resourceId, String action, String detail) {
    sql.execute(
        "insert into audit_event(id,tenant_id,actor_id,resource_id,action) values(?,?,?,?,?)",
        Ids.next(), actor.tenantId(), actor.userId(), resourceId, action);
  }

  static String instant(OffsetDateTime value) {
    return value == null ? null : value.toInstant().toString();
  }

  static ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }

  static String firstNonNull(String... values) {
    for (String value : values) if (value != null) return value;
    return null;
  }

  /** 取出数据库异常里最有信息量的原因（V96 的 CHECK 与触发器都有人话，没有约束名可用）。 */
  static String rootMessage(DataAccessException e) {
    var root = e.getRootCause();
    String message = root == null ? e.getMessage() : root.getMessage();
    return message == null ? e.getClass().getSimpleName() : message.replace('\n', ' ');
  }
}
