package com.starcon.catalog;

import static com.starcon.catalog.CatalogModels.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * 3.4.2.2 描述与卖点库：内容模板（详情描述 / 使用说明 / 注意事项 / 卖点段落）、 多语言译文、卖点短语库。
 *
 * <p>蓝图原文：「职责：管理详情描述模板、卖点短语库、使用说明、注意事项， 支持变量占位与多语言版本，供刊登批量填充。」 「输入：模板定义；变量绑定；多语言翻译（可由 18 的 AI
 * 翻译预填）。」 「输出：填充后的描述文本（分语言）；供 04 引用。」 「上游 = 3.4.2.1；下游 = 04 刊登、15 客服。<b>变量缺失必须在填充时报告</b>，
 * 不能静默留下占位符。」
 *
 * <p>本类的纪律：
 *
 * <ol>
 *   <li><b>模板与译文的占位符集合必须相等。</b>两个方向都查（与 {@link DescriptionRules#variableBindingProblem} 同一纪律）：
 *       译文漏一个占位符 → 那句话对所有 SKU 都一样；译文多一个 → 每批都降级成缺变量。
 *   <li><b>四类内容共用一张模板表</b>，用 content_type 区分。拆成四张形状一样的表 会让变量、翻译、填充这三套机制各写四遍。
 *   <li><b>编码建后不可改</b>：历史段落按模板解释，改编码会让旧批次溯源对不上 （与 {@code MediaService.saveTag} / {@code
 *       TitleService.saveTemplate} 同一纪律）。
 * </ol>
 *
 * <p>权限口径：读 {@code catalog.spu.read}，写 {@code catalog.spu.update}。 描述是挂在 SKU 上的商品文案、上游是 3.1.1.1 的
 * SPU/SKU，所以沿用 spu 词表 （不新造——新词表要改 identity 服务的迁移，属于另一个服务的写入所有权）。
 *
 * <p>批量填充与段落查询在 {@link DescriptionRenderService}，同一套纪律。
 */
@Service
public class DescriptionService {
  /** 单页最大行数。 */
  static final int MAX_PAGE_SIZE = 200;

  private final DSLContext sql;
  private final AccessService access;
  private final ObjectMapper json = new ObjectMapper();

  public DescriptionService(DSLContext sql, AccessService access) {
    this.sql = sql;
    this.access = access;
  }

  // ============================================================ 概览

  /**
   * 描述与卖点库概览。
   *
   * <p>{@code incompleteSectionCount} 与 {@code issueCount} 是这个库唯一需要每天盯的数字： 模板配不全时它们是唯一会提醒运营去补的信号。
   */
  public ContentOverview overview() {
    UUID tenantId = currentTenantId();
    var templates =
        sql.fetchOne(
            "select count(*) as total,"
                + "count(*) filter (where enabled) as enabled_count,"
                + "count(*) filter (where content_type='DESCRIPTION') as description_count,"
                + "count(*) filter (where content_type='INSTRUCTION') as instruction_count,"
                + "count(*) filter (where content_type='CAUTION') as caution_count,"
                + "count(*) filter (where content_type='SELLING_POINT') as selling_point_count"
                + " from content_template where tenant_id=?",
            tenantId);
    var translations =
        sql.fetchOne(
            "select count(*) as total,count(*) filter (where status='DRAFT') as draft_count"
                + " from content_template_translation where tenant_id=?",
            tenantId);
    var phrases =
        sql.fetchOne(
            "select count(*) as total,"
                + "count(*) filter (where phrase_type='SELLING') as selling_count,"
                + "count(*) filter (where phrase_type='FEATURE') as feature_count,"
                + "count(*) filter (where phrase_type='SCENE') as scene_count,"
                + "count(*) filter (where phrase_type='CAUTION') as caution_count"
                + " from content_phrase where tenant_id=?",
            tenantId);
    var renders =
        sql.fetchOne("select count(*) from content_render where tenant_id=?", tenantId)
            .get(0, Long.class);
    var sections =
        sql.fetchOne(
            "select count(*) as total,"
                + "count(*) filter (where status='COMPLETE') as complete_count,"
                + "count(*) filter (where status='INCOMPLETE') as incomplete_count"
                + " from content_render_section where tenant_id=?",
            tenantId);
    long issueCount =
        sql.fetchOne("select count(*) from content_render_issue where tenant_id=?", tenantId)
            .get(0, Long.class);
    return new ContentOverview(
        templates.get("total", Long.class),
        templates.get("enabled_count", Long.class),
        templates.get("description_count", Long.class),
        templates.get("instruction_count", Long.class),
        templates.get("caution_count", Long.class),
        templates.get("selling_point_count", Long.class),
        translations.get("total", Long.class),
        translations.get("draft_count", Long.class),
        phrases.get("total", Long.class),
        phrases.get("selling_count", Long.class),
        phrases.get("feature_count", Long.class),
        phrases.get("scene_count", Long.class),
        phrases.get("caution_count", Long.class),
        renders,
        sections.get("total", Long.class),
        sections.get("complete_count", Long.class),
        sections.get("incomplete_count", Long.class),
        issueCount,
        "变量缺失写在「缺变量段落 / 缺失变量数」两个数上：填充时取不到值的变量会被逐条报告，"
            + "占位符同时从正文里剥掉——3.4.2.2 的硬要求是「变量缺失必须在填充时报告，"
            + "不能静默留下占位符」。已填好的段落（COMPLETE）才会被 04 刊登与 15 客服引用。");
  }

  // ============================================================ 模板

  /** 模板列表（含变量与已配语言）。按编码排序，停用的也在列表里（要看历史配置）。 */
  public List<ContentTemplateRow> templates() {
    var rows =
        sql.fetch(
            "select id,code,name,note,content_type,body,source_locale,enabled,version,"
                + "created_at,updated_at from content_template where tenant_id=? order by code",
            currentTenantId());
    if (rows.isEmpty()) return List.of();
    List<UUID> ids = rows.stream().map(r -> r.get("id", UUID.class)).toList();
    Map<UUID, List<ContentTemplateVariable>> variables = variablesOf(ids);
    Map<UUID, List<String>> locales = localesOf(ids);
    Map<UUID, Long> renders = renderCounts(ids);
    List<ContentTemplateRow> out = new ArrayList<>(rows.size());
    for (Record r : rows) {
      UUID id = r.get("id", UUID.class);
      String contentType = r.get("content_type", String.class);
      out.add(
          new ContentTemplateRow(
              id,
              r.get("code", String.class),
              r.get("name", String.class),
              r.get("note", String.class),
              contentType,
              DescriptionRules.contentTypeLabel(contentType),
              r.get("body", String.class),
              r.get("source_locale", String.class),
              Boolean.TRUE.equals(r.get("enabled", Boolean.class)),
              variables.getOrDefault(id, List.of()),
              locales.getOrDefault(id, List.of()),
              renders.getOrDefault(id, 0L),
              r.get("version", Long.class),
              instant(r.get("created_at", OffsetDateTime.class)),
              instant(r.get("updated_at", OffsetDateTime.class))));
    }
    return out;
  }

  /** 单个模板（含变量与已配语言）。 */
  public ContentTemplateRow template(UUID id) {
    var row = templateRow(id);
    String contentType = row.get("content_type", String.class);
    return new ContentTemplateRow(
        row.get("id", UUID.class),
        row.get("code", String.class),
        row.get("name", String.class),
        row.get("note", String.class),
        contentType,
        DescriptionRules.contentTypeLabel(contentType),
        row.get("body", String.class),
        row.get("source_locale", String.class),
        Boolean.TRUE.equals(row.get("enabled", Boolean.class)),
        variablesOf(List.of(id)).getOrDefault(id, List.of()),
        localesOf(List.of(id)).getOrDefault(id, List.of()),
        templateRenderCount(id),
        row.get("version", Long.class),
        instant(row.get("created_at", OffsetDateTime.class)),
        instant(row.get("updated_at", OffsetDateTime.class)));
  }

  /**
   * 建模板或改模板（id 为空即新建）。变量**整体替换**。
   *
   * <p>整体替换而不是增量增删：变量集合是「这段正文怎么拼」的完整口径， 增删式会让「当前有哪些变量」这个问题有两个答案。
   *
   * <p>编码建后不可改：历史段落按模板解释，改编码会让旧批次溯源对不上。
   */
  public ContentTemplateSaved saveTemplate(
      AccessService.Access actor, UUID id, ContentTemplateInput input) {
    String body = DescriptionRules.normalizeBody(input.body());
    String problem =
        firstNonNull(
            DescriptionRules.templateCodeProblem(input.code()),
            DescriptionRules.templateNameProblem(input.name()),
            DescriptionRules.contentTypeProblem(input.contentType()),
            DescriptionRules.bodyProblem(body),
            DescriptionRules.localeProblem(input.sourceLocale()),
            DescriptionRules.variablesSizeProblem(
                input.variables() == null ? 0 : input.variables().size()),
            variablesProblem(body, input.variables()));
    CatalogService.check(problem == null, problem);
    String sourceLocale = DescriptionRules.normalizeLocale(input.sourceLocale());

    UUID tenantId = currentTenantId();
    if (id != null) {
      var existing = templateRow(id);
      long version = existing.get("version", Long.class);
      CatalogService.check(
          version == input.version(),
          "这个模板已被他人改动（当前版本 " + version + "，你提交的是 " + input.version() + "）：请刷新后重新编辑，避免覆盖掉别人的修改");
      // 源语言改了正文而已有译文的占位符集合可能对不上了 —— 这里**不**级联删译文，
      // 而是由下面的 translationPlaceholdersProblem 在保存译文时拦住，
      // 并在改动源语言时明确提醒已配语言需要复核。
      int updated =
          sql.execute(
              "update content_template set name=?,note=?,content_type=?,body=?,source_locale=?,"
                  + "enabled=?,version=version+1,updated_at=now() where tenant_id=? and id=? and version=?",
              MediaRules.normalize(input.name()),
              input.note() == null ? "" : input.note(),
              MediaRules.normalizeUpper(input.contentType()),
              body,
              sourceLocale,
              input.enabled(),
              tenantId,
              id,
              input.version());
      if (updated != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "模板已被他人修改，请刷新后重试");
    } else {
      id = Ids.next();
      sql.execute(
          "insert into content_template(id,tenant_id,code,name,note,content_type,body,"
              + "source_locale,enabled,version) values(?,?,?,?,?,?,?,?,?,0)",
          id,
          tenantId,
          MediaRules.normalize(input.code()).toUpperCase(Locale.ROOT),
          MediaRules.normalize(input.name()),
          input.note() == null ? "" : input.note(),
          MediaRules.normalizeUpper(input.contentType()),
          body,
          sourceLocale,
          input.enabled());
    }
    replaceVariables(id, input.variables());
    audit(
        actor, id, "ContentTemplateSaved", DescriptionRules.contentTypeLabel(input.contentType()));
    return templateSaved(id);
  }

  /**
   * 变量的双向校验：正文里的占位符与声明必须互相对得上。
   *
   * <p>源语言正文没声明绑定源的占位符在这里就拒——因为蓝图的硬要求是 「变量缺失必须在填充时报告，不能静默留下占位符」， 配置期的缺失比填充期的缺失更早暴露，成本也更低。
   */
  private String variablesProblem(String body, List<ContentTemplateVariableInput> variables) {
    List<String> names = new ArrayList<>();
    if (variables != null)
      for (ContentTemplateVariableInput variable : variables) {
        if (variable == null) return "变量列表里有一条是空的";
        String nameProblem = DescriptionRules.variableNameProblem(variable.name());
        if (nameProblem != null) return nameProblem;
        String sourceProblem = DescriptionRules.sourceProblem(variable.source());
        if (sourceProblem != null) return sourceProblem;
        if (names.contains(MediaRules.normalize(variable.name())))
          return "变量名重复：" + MediaRules.normalize(variable.name()) + "（同一个占位符只能有一个绑定源）";
        names.add(MediaRules.normalize(variable.name()));
      }
    return DescriptionRules.variableBindingProblem(body, names);
  }

  /** 变量整体替换：先删后插，保证「当前有哪些变量」只有一份答案。 */
  private void replaceVariables(UUID templateId, List<ContentTemplateVariableInput> variables) {
    sql.execute(
        "delete from content_template_variable where tenant_id=? and template_id=?",
        currentTenantId(),
        templateId);
    if (variables == null || variables.isEmpty()) return;
    int slot = 0;
    for (ContentTemplateVariableInput variable : variables)
      sql.execute(
          "insert into content_template_variable(id,tenant_id,template_id,slot_no,variable_name,source)"
              + " values(?,?,?,?,?,?)",
          Ids.next(),
          currentTenantId(),
          templateId,
          slot++,
          MediaRules.normalize(variable.name()),
          MediaRules.normalizeUpper(variable.source()));
  }

  public ContentTemplateSaved setTemplateEnabled(
      AccessService.Access actor, UUID id, boolean enabled) {
    int updated =
        sql.execute(
            "update content_template set enabled=?,version=version+1,updated_at=now()"
                + " where tenant_id=? and id=?",
            enabled,
            currentTenantId(),
            id);
    if (updated != 1) throw CatalogService.missing();
    audit(actor, id, "ContentTemplateEnabled", enabled ? "启用" : "停用");
    return templateSaved(id);
  }

  /** 删除模板。仍被填充批次引用时返回 400 并说明被引用几次—— 要清理请改用停用（停用后不能再用它新建批次，历史段落照常可读）。 */
  public void deleteTemplate(AccessService.Access actor, UUID id, long version) {
    var existing = templateRow(id);
    long uses = templateRenderCount(id);
    CatalogService.check(
        uses == 0,
        "模板「"
            + existing.get("code", String.class)
            + "」已被 "
            + uses
            + " 次批量填充引用，不可删除：要清理请改用停用"
            + "（停用后不能再用它新建批次，历史段落与译文照常可读）");
    CatalogService.check(
        existing.get("version", Long.class) == version,
        "这个模板已被他人改动（当前版本 "
            + existing.get("version", Long.class)
            + "，你提交的是 "
            + version
            + "）：请刷新后重试");
    sql.execute("delete from content_template where tenant_id=? and id=?", currentTenantId(), id);
    audit(actor, id, "ContentTemplateDeleted", null);
  }

  // ============================================================ 多语言译文

  /** 某模板的译文列表（按语言排序）。 */
  public List<ContentTranslation> translations(UUID templateId) {
    templateRow(templateId);
    return translationRows(List.of(templateId)).getOrDefault(templateId, List.of());
  }

  /**
   * 译文完整度：这几种语言里哪些能直接填。
   *
   * <p>「缺译文」不是错误而是常态（多语言本来逐语言补）， 但它会让填充整批报 {@code TRANSLATION_MISSING}，所以要在开跑之前答清楚。
   */
  public ContentTranslationCoverage translationCoverage(UUID templateId, List<String> locales) {
    var row = templateRow(templateId);
    String sourceLocale = row.get("source_locale", String.class);
    CatalogService.check(locales == null || !locales.isEmpty(), "请至少指定一种语言来检查译文完整度");
    CatalogService.check(
        DescriptionRules.localesSizeProblem(locales.size()) == null,
        DescriptionRules.localesSizeProblem(locales.size()));

    List<String> normalized = new ArrayList<>();
    for (String raw : locales) {
      String localeProblem = DescriptionRules.localeProblem(raw);
      CatalogService.check(localeProblem == null, localeProblem);
      normalized.add(DescriptionRules.normalizeLocale(raw));
    }
    List<ContentTranslation> existing = translations(templateId);
    var byLocale = new LinkedHashMap<String, ContentTranslation>();
    for (ContentTranslation translation : existing) byLocale.put(translation.locale(), translation);

    List<String> missing = new ArrayList<>();
    for (String locale : normalized) {
      // 源语言不需要译文行：它直接用模板 body 渲染，所以不计入「缺」。
      if (locale.equals(sourceLocale)) continue;
      if (!byLocale.containsKey(locale)) missing.add(locale);
    }
    return new ContentTranslationCoverage(
        existing,
        normalized,
        missing,
        missing.isEmpty()
            ? "这 " + normalized.size() + " 种语言都能直接填充。"
            : "还缺 "
                + missing.size()
                + " 种语言的译文（"
                + String.join(" / ", missing)
                + "）：用这些语言填充时会整批报「该语言没有译文」，"
                + "而不是悄悄退回源语言正文——那会让运营以为发出去的是译文。");
  }

  /**
   * 建译文或改译文（id 为空即新建，按 templateId + locale 定位）。
   *
   * <p>源语言本身不建译文行：它就是模板 body。给源语言建一条会让「这段英文是谁写的」 有两个答案（body 还是译文行），而填充时两者可能不一致。
   */
  public ContentTranslation saveTranslation(
      AccessService.Access actor, UUID templateId, UUID id, ContentTranslationInput input) {
    var template = templateRow(templateId);
    String body = DescriptionRules.normalizeBody(input.body());
    String problem =
        firstNonNull(
            DescriptionRules.localeProblem(input.locale()),
            DescriptionRules.translationNameProblem(input.name()),
            DescriptionRules.bodyProblem(body),
            DescriptionRules.translationOriginProblem(input.origin()),
            DescriptionRules.translationStatusProblem(input.status()));
    CatalogService.check(problem == null, problem);
    String locale = DescriptionRules.normalizeLocale(input.locale());
    CatalogService.check(
        !locale.equals(template.get("source_locale", String.class)),
        "语言「" + locale + "」是模板的源语言，不需要译文行：" + "源语言直接用模板正文渲染。建一条译文会让「这段内容来自哪里」有两个答案，" + "而两者可能不一致");

    // 译文与源语言的占位符集合必须完全相等 —— 这是「变量缺失必须报告」的前置保障。
    String placeholderProblem =
        DescriptionRules.translationPlaceholdersProblem(template.get("body", String.class), body);
    CatalogService.check(placeholderProblem == null, placeholderProblem);

    UUID tenantId = currentTenantId();
    if (id != null) {
      var existing = translationRow(id);
      CatalogService.check(
          existing.get("version", Long.class) == input.version(),
          "这条译文已被他人改动（当前版本 "
              + existing.get("version", Long.class)
              + "，你提交的是 "
              + input.version()
              + "）：请刷新后重试");
      sql.execute(
          "update content_template_translation set locale=?,name=?,body=?,origin=?,status=?,"
              + "version=version+1,updated_at=now()"
              + " where tenant_id=? and id=? and version=?",
          locale,
          MediaRules.normalize(input.name()),
          body,
          MediaRules.normalizeUpper(input.origin()),
          MediaRules.normalizeUpper(input.status()),
          tenantId,
          id,
          input.version());
    } else {
      id = Ids.next();
      try {
        sql.execute(
            "insert into content_template_translation(id,tenant_id,template_id,locale,name,body,"
                + "origin,status,version) values(?,?,?,?,?,?,?,?,0)",
            id,
            tenantId,
            templateId,
            locale,
            MediaRules.normalize(input.name()),
            body,
            MediaRules.normalizeUpper(input.origin()),
            MediaRules.normalizeUpper(input.status()));
      } catch (DataAccessException e) {
        throw conflict(
            "这个模板下已经有语言「"
                + locale
                + "」的译文了："
                + "同一语言只能有一条译文，否则 04 刊登按 (模板, 语言) 取段时会不知道取哪条。"
                + "要改请直接编辑那条译文");
      }
    }
    audit(actor, id, "ContentTranslationSaved", locale);
    return toTranslation(translationRow(id));
  }

  /** 删除译文。被填充段落引用时返回 400—— 段落上快照的是填充当时的正文，删掉译文行之后「这段是哪一版译文填的」就答不上来了。 */
  public void deleteTranslation(AccessService.Access actor, UUID id, long version) {
    var existing = translationRow(id);
    UUID tenantId = currentTenantId();
    long refs =
        sql.fetchOne(
                "select count(*) from content_render_section where tenant_id=? and render_id in"
                    + " (select id from content_render where tenant_id=? and template_id=? and locale=?)",
                tenantId,
                tenantId,
                existing.get("template_id", UUID.class),
                existing.get("locale", String.class))
            .get(0, Long.class);
    CatalogService.check(
        refs == 0,
        "语言「"
            + existing.get("locale", String.class)
            + "」的译文已被 "
            + refs
            + " 段填充结果引用，不可删除：段落上快照的是填充当时的正文，"
            + "删掉译文行之后「这段是哪一版译文填的」就答不上来了");
    CatalogService.check(
        existing.get("version", Long.class) == version,
        "这条译文已被他人改动（当前版本 "
            + existing.get("version", Long.class)
            + "，你提交的是 "
            + version
            + "）：请刷新后重试");
    sql.execute(
        "delete from content_template_translation where tenant_id=? and id=?", tenantId, id);
    audit(actor, id, "ContentTranslationDeleted", existing.get("locale", String.class));
  }

  // ============================================================ 卖点短语库

  /** 短语列表：分页 + 关键词 + 类型 + 启用状态筛选。每行自带被引用数。 */
  public ContentPhrasePage phrases(ContentPhraseQuery query) {
    int page = Math.max(0, query.page());
    int size = Math.min(Math.max(query.size(), 1), MAX_PAGE_SIZE);
    UUID tenantId = currentTenantId();

    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    binds.add(tenantId);
    String keyword = MediaRules.normalize(query.keyword());
    if (!keyword.isEmpty()) {
      where.add("(p.text ilike ? escape '!' or p.code ilike ? escape '!')");
      binds.add(escapeLike(keyword));
      binds.add(escapeLike(keyword.toUpperCase(Locale.ROOT)));
    }
    String phraseType = MediaRules.normalizeUpper(query.phraseType());
    if (!phraseType.isEmpty()) {
      CatalogService.check(
          DescriptionRules.PHRASE_TYPES.contains(phraseType),
          "短语类型只能是 " + String.join(" / ", DescriptionRules.PHRASE_TYPES));
      where.add("p.phrase_type=?");
      binds.add(phraseType);
    }
    String enabled = MediaRules.normalize(query.enabled());
    if (!enabled.isEmpty()) {
      CatalogService.check(
          "true".equals(enabled) || "false".equals(enabled), "启用状态只能是 true 或 false");
      where.add("p.enabled=?");
      binds.add(Boolean.valueOf(enabled));
    }
    String clause = where.isEmpty() ? "" : " and " + String.join(" and ", where);

    var counts =
        sql.fetchOne(
            "select count(*) as total,"
                + "count(*) filter (where phrase_type='SELLING') as selling_count,"
                + "count(*) filter (where phrase_type='FEATURE') as feature_count,"
                + "count(*) filter (where phrase_type='SCENE') as scene_count,"
                + "count(*) filter (where phrase_type='CAUTION') as caution_count"
                + " from content_phrase where tenant_id=?",
            tenantId);
    long total =
        sql.fetchOne(
                "select count(*) from content_phrase p where p.tenant_id=?" + clause,
                binds.toArray())
            .get(0, Long.class);

    var bindsPage = new ArrayList<>(binds);
    bindsPage.add(size);
    bindsPage.add(page * size);
    var rows =
        sql.fetch(
            "select p.id,p.code,p.text,p.phrase_type,p.enabled,p.version,p.created_at,p.updated_at"
                + " from content_phrase p where p.tenant_id=?"
                + clause
                + " order by p.phrase_type,p.code,p.id limit ? offset ?",
            bindsPage.toArray());

    List<ContentPhraseListRow> items = new ArrayList<>();
    for (Record r : rows)
      items.add(
          new ContentPhraseListRow(
              toPhraseRow(r), phraseReferenceCount(r.get("text", String.class))));
    return new ContentPhrasePage(
        items,
        total,
        page,
        size,
        counts.get("selling_count", Long.class),
        counts.get("feature_count", Long.class),
        counts.get("scene_count", Long.class),
        counts.get("caution_count", Long.class),
        "短语池是模板变量的取值来源：某类短语一条都没有时，绑到这一类的变量会在填充时" + "被报成「短语池为空」，而不是渲染成空白。这里是四条池子各自的存量。");
  }

  /**
   * 建短语或改短语（id 为空即新建）。
   *
   * <p>同一类型内文本唯一是刻意的产品约束：同一句卖点写成两条会让短语池「看起来更大」， 而批量填充出来的描述还是那几种 —— 这正是「变量缺失被误判为已填」的温床。
   */
  public ContentPhraseRow savePhrase(
      AccessService.Access actor, UUID id, ContentPhraseInput input) {
    String problem =
        firstNonNull(
            DescriptionRules.phraseCodeProblem(input.code()),
            DescriptionRules.phraseTextProblem(input.text()),
            DescriptionRules.phraseTypeProblem(input.phraseType()));
    CatalogService.check(problem == null, problem);

    UUID tenantId = currentTenantId();
    String type = MediaRules.normalizeUpper(input.phraseType());
    String text = MediaRules.normalize(input.text());
    if (id != null) {
      var existing = phraseRow(id);
      CatalogService.check(
          existing.get("version", Long.class) == input.version(),
          "这条短语已被他人改动（当前版本 "
              + existing.get("version", Long.class)
              + "，你提交的是 "
              + input.version()
              + "）：请刷新后重试");
      sql.execute(
          "update content_phrase set code=?,text=?,phrase_type=?,enabled=?,version=version+1,"
              + "updated_at=now() where tenant_id=? and id=? and version=?",
          MediaRules.normalize(input.code()).toUpperCase(Locale.ROOT),
          text,
          type,
          input.enabled(),
          tenantId,
          id,
          input.version());
    } else {
      id = Ids.next();
      sql.execute(
          "insert into content_phrase(id,tenant_id,code,text,phrase_type,enabled,version)"
              + " values(?,?,?,?,?,?,0)",
          id,
          tenantId,
          MediaRules.normalize(input.code()).toUpperCase(Locale.ROOT),
          text,
          type,
          input.enabled());
    }
    audit(actor, id, "ContentPhraseSaved", type);
    return toPhraseRow(phraseRow(id));
  }

  public ContentPhraseRow setPhraseEnabled(AccessService.Access actor, UUID id, boolean enabled) {
    int updated =
        sql.execute(
            "update content_phrase set enabled=?,version=version+1,updated_at=now()"
                + " where tenant_id=? and id=?",
            enabled,
            currentTenantId(),
            id);
    if (updated != 1) throw CatalogService.missing();
    audit(actor, id, "ContentPhraseEnabled", enabled ? "启用" : "停用");
    return toPhraseRow(phraseRow(id));
  }

  /** 删除短语。被段落引用时返回 400 并说明被引用几段——要清理请改用停用。 */
  public void deletePhrase(AccessService.Access actor, UUID id, long version) {
    var existing = phraseRow(id);
    long refs = phraseReferenceCount(existing.get("text", String.class));
    CatalogService.check(
        refs == 0,
        "短语「"
            + abbreviate(existing.get("text", String.class))
            + "」已被 "
            + refs
            + " 段填充结果引用，不可删除：段落快照的是它的文本，删掉短语库行那段话就成了"
            + "没有出处的孤儿。请改用停用");
    CatalogService.check(
        existing.get("version", Long.class) == version,
        "这条短语已被他人改动（当前版本 "
            + existing.get("version", Long.class)
            + "，你提交的是 "
            + version
            + "）：请刷新后重试");
    sql.execute("delete from content_phrase where tenant_id=? and id=?", currentTenantId(), id);
    audit(actor, id, "ContentPhraseDeleted", null);
  }

  /**
   * 被多少段填充结果引用。
   *
   * <p>用 {@code like '%文本%'} 而不是记一列短语外键：V20 的 title_record 快照的是
   * **词根文本**（因为一条标题里同一个词根可能出现两次，一次外键表达不了）， 这里同理——一个短语池可能同时贡献给好几种模板，一次外键表达不了。 代价是扫文本，所以 V21 给
   * content_render_section 建了 trigram 索引。
   */
  private long phraseReferenceCount(String text) {
    if (text == null || text.isEmpty()) return 0;
    return sql.fetchOne(
            "select count(*) from content_render_section where tenant_id=? and text like ?",
            currentTenantId(),
            "%" + text + "%")
        .get(0, Long.class);
  }

  // ============================================================ 行映射与工具

  Record templateRow(UUID id) {
    var row =
        sql.fetchOne(
            "select id,code,name,note,content_type,body,source_locale,enabled,version,"
                + "created_at,updated_at from content_template where id=?",
            id);
    if (row == null) throw CatalogService.missing();
    return row;
  }

  Record translationRow(UUID id) {
    var row =
        sql.fetchOne(
            "select id,template_id,locale,name,body,origin,status,version,created_at,updated_at"
                + " from content_template_translation where id=?",
            id);
    if (row == null) throw CatalogService.missing();
    return row;
  }

  Record phraseRow(UUID id) {
    var row =
        sql.fetchOne(
            "select id,code,text,phrase_type,enabled,version,created_at,updated_at"
                + " from content_phrase where id=?",
            id);
    if (row == null) throw CatalogService.missing();
    return row;
  }

  ContentTemplateSaved templateSaved(UUID id) {
    var row = templateRow(id);
    return new ContentTemplateSaved(
        id,
        row.get("code", String.class),
        row.get("version", Long.class),
        variablesOf(List.of(id)).getOrDefault(id, List.of()).size(),
        localesOf(List.of(id)).getOrDefault(id, List.of()).size(),
        templateRenderCount(id));
  }

  /** 变量按模板分组（一次查询取齐，避免逐模板查一次）。 */
  Map<UUID, List<ContentTemplateVariable>> variablesOf(List<UUID> templateIds) {
    if (templateIds.isEmpty()) return Map.of();
    Map<UUID, List<ContentTemplateVariable>> byTemplate = new LinkedHashMap<>();
    for (Record r :
        sql.fetch(
            "select id,template_id,variable_name,source,slot_no from content_template_variable"
                + " where template_id in ("
                + placeholders(templateIds)
                + ")"
                + " order by template_id,slot_no,id",
            templateIds.toArray()))
      byTemplate
          .computeIfAbsent(r.get("template_id", UUID.class), k -> new ArrayList<>())
          .add(
              new ContentTemplateVariable(
                  r.get("id", UUID.class),
                  r.get("variable_name", String.class),
                  r.get("source", String.class),
                  DescriptionRules.sourceLabel(r.get("source", String.class)),
                  r.get("slot_no", Integer.class)));
    return byTemplate;
  }

  /** 已配语言按模板分组（列表页要显示「这个模板配了几种语言」，源语言算一种）。 */
  Map<UUID, List<String>> localesOf(List<UUID> templateIds) {
    if (templateIds.isEmpty()) return Map.of();
    Map<UUID, List<String>> byTemplate = new LinkedHashMap<>();
    for (Record r :
        sql.fetch(
            "select template_id,locale from content_template_translation"
                + " where template_id in ("
                + placeholders(templateIds)
                + ") order by template_id,locale",
            templateIds.toArray()))
      byTemplate
          .computeIfAbsent(r.get("template_id", UUID.class), k -> new ArrayList<>())
          .add(r.get("locale", String.class));
    return byTemplate;
  }

  Map<UUID, List<ContentTranslation>> translationRows(List<UUID> templateIds) {
    if (templateIds.isEmpty()) return Map.of();
    Map<UUID, List<ContentTranslation>> byTemplate = new LinkedHashMap<>();
    for (Record r :
        sql.fetch(
            "select id,template_id,locale,name,body,origin,status,version,created_at,updated_at"
                + " from content_template_translation"
                + " where template_id in ("
                + placeholders(templateIds)
                + ") order by template_id,locale",
            templateIds.toArray()))
      byTemplate
          .computeIfAbsent(r.get("template_id", UUID.class), k -> new ArrayList<>())
          .add(toTranslation(r));
    return byTemplate;
  }

  private ContentTranslation toTranslation(Record r) {
    String origin = r.get("origin", String.class);
    String status = r.get("status", String.class);
    return new ContentTranslation(
        r.get("id", UUID.class),
        r.get("template_id", UUID.class),
        r.get("locale", String.class),
        r.get("name", String.class),
        r.get("body", String.class),
        origin,
        DescriptionRules.originLabel(origin),
        status,
        DescriptionRules.translationStatusLabel(status),
        r.get("version", Long.class),
        instant(r.get("created_at", OffsetDateTime.class)),
        instant(r.get("updated_at", OffsetDateTime.class)));
  }

  private ContentPhraseRow toPhraseRow(Record r) {
    String phraseType = r.get("phrase_type", String.class);
    return new ContentPhraseRow(
        r.get("id", UUID.class),
        r.get("code", String.class),
        r.get("text", String.class),
        phraseType,
        DescriptionRules.phraseTypeLabel(phraseType),
        Boolean.TRUE.equals(r.get("enabled", Boolean.class)),
        r.get("version", Long.class),
        instant(r.get("created_at", OffsetDateTime.class)),
        instant(r.get("updated_at", OffsetDateTime.class)));
  }

  /** 各模板被填充的次数（模板列表页的「引用数」，删除保护在列表上就看得见）。 */
  private Map<UUID, Long> renderCounts(List<UUID> templateIds) {
    if (templateIds.isEmpty()) return Map.of();
    Map<UUID, Long> out = new LinkedHashMap<>();
    for (Record r :
        sql.fetch(
            "select template_id,count(*) as total from content_render"
                + " where template_id in ("
                + placeholders(templateIds)
                + ") group by template_id",
            templateIds.toArray()))
      out.put(r.get("template_id", UUID.class), r.get("total", Long.class));
    return out;
  }

  private long templateRenderCount(UUID templateId) {
    return sql.fetchOne("select count(*) from content_render where template_id=?", templateId)
        .get(0, Long.class);
  }

  /** 短语池按类型取（每次填充都要按类型读四遍，所以一次查齐）。 */
  List<String> phrasePool(String phraseType) {
    return sql
        .fetch(
            "select text from content_phrase where tenant_id=? and phrase_type=? and enabled"
                + " order by code,id",
            currentTenantId(),
            phraseType)
        .stream()
        .map(r -> r.get(0, String.class))
        .toList();
  }

  UUID currentTenantId() {
    return sql.fetchOne("select nullif(current_setting('app.current_tenant',true),'')::uuid")
        .get(0, UUID.class);
  }

  String jsonArray(List<String> values) {
    try {
      return json.writeValueAsString(values == null ? List.of() : values);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("短语快照序列化失败", e);
    }
  }

  List<String> parseJsonArray(String raw) {
    if (raw == null || raw.isBlank()) return List.of();
    try {
      return json.readValue(
          raw, json.getTypeFactory().constructCollectionType(List.class, String.class));
    } catch (JsonProcessingException e) {
      // 快照读不出来时返回空列表而不是抛：溯源字段坏了不该让整个列表页打不开。
      return List.of();
    }
  }

  /** 占位符串。与 {@code TitleService} 同一形状：参数个数由调用方保证，SQL 里不会拼进用户输入。 */
  static String placeholders(List<?> ids) {
    return String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
  }

  /** 取第一个非空原因：多个校验同时不合法时，先报最贴近用户输入的那一条。 */
  static String firstNonNull(String... values) {
    for (String value : values) if (value != null) return value;
    return null;
  }

  static String escapeLike(String value) {
    return "%" + value.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
  }

  static String abbreviate(String value) {
    String text = value == null ? "" : value;
    return text.length() <= 40 ? text : text.substring(0, 40) + "…";
  }

  /** CSV 转义按 RFC 4180；前导 =/+/-/@ 会被 Excel 当成公式执行，加前缀单引号切断。 */
  static String csv(String value) {
    String text = value == null ? "" : value;
    if (!text.isEmpty() && "=+-@".indexOf(text.charAt(0)) >= 0) text = "'" + text;
    return '"' + text.replace("\"", "\"\"") + '"';
  }

  void audit(AccessService.Access actor, UUID resourceId, String action, String detail) {
    sql.execute(
        "insert into audit_event(id,tenant_id,actor_id,resource_id,action) values(?,?,?,?,?)",
        Ids.next(),
        actor.tenantId(),
        actor.userId(),
        resourceId,
        action);
  }

  static String instant(OffsetDateTime value) {
    return value == null ? null : value.toInstant().toString();
  }

  static ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }

  /** 取出数据库异常里最有信息量的原因（V21 的 CHECK 有人话，没有约束名可用）。 */
  static String rootMessage(DataAccessException e) {
    var root = e.getRootCause();
    String message = root == null ? e.getMessage() : root.getMessage();
    return message == null ? e.getClass().getSimpleName() : message.replace('\n', ' ');
  }
}
