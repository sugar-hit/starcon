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
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 3.4.2.2 描述与卖点库的批量填充与段落查询。
 *
 * <p>蓝图硬要求落在这里：「<b>变量缺失必须在填充时报告，不能静默留下占位符。</b>」
 * 具体做法由 {@link DescriptionRenderer}（纯函数）负责产出，
 * 本类负责把它落成三张表：批次 → 逐 SKU 结果 → 段落 + 缺失变量报告。
 *
 * <p>本类的纪律：
 *
 * <ol>
 *   <li><b>SKU 范围、短语池与译文在提交时快照。</b>批量跑到一半商品改名、短语库被改，
 *       这一批段落的口径都必须仍是提交那一刻的，否则「这段当时填了什么」答不出来。
 *   <li><b>缺变量是一种输出，不是一种失败。</b>照样产出正文（占位符被剥掉），
 *       段落标成 INCOMPLETE，缺了什么逐变量落在 {@code content_render_issue} 上。
 *       批次状态因此是三态：SUCCEEDED（全部填好）/ PARTIAL（有段落缺变量）/
 *       FAILED（一个可用段落都没有）。
 *   <li><b>填充是同步的「已完成」语义</b>，与 3.4.2.1 一致：成本只与 SKU 数成正比
 *       （每个 SKU 一次字符串替换），几百个 SKU 是几十毫秒。
 *       做成异步只会让运营为几十毫秒去盯进度条。
 * </ol>
 */
@Service
public class DescriptionRenderService {
  private final DSLContext sql;
  private final AccessService access;
  private final DescriptionService descriptions;

  public DescriptionRenderService(DSLContext sql, AccessService access, DescriptionService descriptions) {
    this.sql = sql;
    this.access = access;
    this.descriptions = descriptions;
  }

  // ============================================================ 批量填充

  /**
   * 批量填充（同步，返回即已完成）。
   *
   * <p>流程刻意是「先把输入查齐并校验，再开始渲染」：用户点了提交之后才发现
   * 「这个语言根本没配译文」，而批次已经建好了，这体验很糟。
   *
   * <p>译文缺失在提交时就拒（整批拒，不产出段落）——因为「缺译文」的正确修法是去补译文，
   * 而按源语言正文填出来的段落会被误当成译文发到平台上，那比不填更糟。
   * 相比之下，<b>商品字段为空</b>与<b>短语池为空</b>不拒：它们是逐 SKU / 逐类型的正常状态，
   * 交由渲染器逐变量报告，让运营看到「哪 30 个 SKU 因为哪个变量没填上」。
   */
  public ContentRender render(AccessService.Access actor, ContentRenderInput input) {
    String problem =
        DescriptionRules.skusSizeProblem(input.skuIds() == null ? 0 : input.skuIds().size());
    CatalogService.check(problem == null, problem);
    CatalogService.check(
        DescriptionRules.localeProblem(input.locale()) == null,
        DescriptionRules.localeProblem(input.locale()));
    String locale = DescriptionRules.normalizeLocale(input.locale());
    CatalogService.check(input.templateId() != null, "请选择一个内容模板");

    var template = descriptions.templateRow(input.templateId());
    CatalogService.check(
        Boolean.TRUE.equals(template.get("enabled", Boolean.class)),
        "模板「" + template.get("name", String.class) + "」已停用，不能用它填充。"
            + "请换一个启用的模板，或先启用它");

    String sourceLocale = template.get("source_locale", String.class);
    String sourceBody = template.get("body", String.class);
    String renderBody = sourceBody;
    if (!locale.equals(sourceLocale)) {
      // 缺译文在提交时就拒，且**不**悄悄退回源语言正文：
      // 那会让运营以为发出去的是译文，而 04 刊登引用的是段落里的 text。
      Record translation = translationRow(input.templateId(), locale);
      CatalogService.check(
          translation != null,
          "模板没有语言「" + locale + "」的译文，不能用这个语言填充："
              + "缺译文时按源语言正文填出来的那段会被当成译文发到平台上。"
              + "请先补一条 " + locale + " 的译文（AI 预填后请复核占位符），或改用源语言 " + sourceLocale);
      renderBody = translation.get("body", String.class);
    }

    Map<String, DescriptionRenderer.VariableSpec> variables =
        templateVariables(input.templateId());
    DescriptionRenderer.TemplateSpec spec =
        new DescriptionRenderer.TemplateSpec(
            template.get("content_type", String.class),
            renderBody,
            List.copyOf(variables.values()));

    // ---- 提交时快照：SKU 事实与短语池都取齐了才开始渲染
    List<DescriptionRenderer.SkuFacts> skus = skuFacts(input.skuIds());
    DescriptionRenderer.PhrasePools pools =
        new DescriptionRenderer.PhrasePools(
            descriptions.phrasePool("SELLING"),
            descriptions.phrasePool("FEATURE"),
            descriptions.phrasePool("SCENE"),
            descriptions.phrasePool("CAUTION"));

    UUID renderId = Ids.next();
    UUID tenantId = descriptions.currentTenantId();

    List<ContentRenderSkuRow> skuRows = new ArrayList<>();
    List<SectionDraft> drafts = new ArrayList<>();
    int complete = 0;
    int incomplete = 0;
    int withText = 0;
    int issueCount = 0;
    List<String> errors = new ArrayList<>();

    for (int i = 0; i < skus.size(); i++) {
      DescriptionRenderer.SkuFacts sku = skus.get(i);
      DescriptionRenderer.Rendered rendered = DescriptionRenderer.render(spec, sku, pools, i);
      if (rendered.complete()) complete++;
      else incomplete++;
      if (!rendered.text().isEmpty()) withText++;
      issueCount += rendered.issues().size();

      // 逐 SKU 的原因（列表上要显示）：把该 SKU 的缺失报告压成一句可读的话。
      String reason =
          rendered.issues().isEmpty()
              ? ""
              : rendered.issues().stream()
                  .map(issue -> issue.variableName() + "（"
                      + DescriptionRules.issueKindLabel(issue.issueKind()) + "）")
                  .collect(java.util.stream.Collectors.joining("、"));
      UUID renderSkuId = Ids.next();
      String outcome =
          rendered.complete() ? "RENDERED" : (rendered.text().isEmpty() ? "REJECTED" : "INCOMPLETE");
      skuRows.add(
          new ContentRenderSkuRow(
              renderSkuId, renderId, sku.skuId(), sku.skuCode(), sku.skuName(), sku.spuName(),
              sku.brand(), sku.category(), outcome,
              DescriptionRules.outcomeLabel(outcome),
              rendered.complete() ? 1 : 0,
              rendered.complete() ? 0 : 1,
              rendered.issues().size(),
              reason));
      drafts.add(new SectionDraft(renderSkuId, sku, rendered));
      if (outcome.equals("REJECTED"))
        errors.add(sku.skuCode() + "：" + reason);
    }

    // 批次三态。判据是「有没有产出正文」，不是「有没有全部变量都有值」：
    //   SUCCEEDED 每段都填好了
    //   PARTIAL    有段落缺变量，但正文照样产出了（可能一段都没完全干净，
    //               但运营手上拿到的是能看懂的文本 + 一份缺变量清单）
    //   FAILED     一个字的正文都没产出（通常是四个短语池全空，模板里全绑短语）
    // 把「有正文但缺变量」判成 FAILED 会让运营以为要重来一次，
    // 而实际上他只要补上 brand 就能用。
    String status =
        (complete > 0 || withText > 0) ? (incomplete > 0 ? "PARTIAL" : "SUCCEEDED") : "FAILED";

    sql.execute(
        "insert into content_render(id,tenant_id,template_id,requested_by,locale,status,sku_count,"
            + "section_count,complete_count,incomplete_count,issue_count,error,finished_at)"
            + " values(?,?,?,?,?,?,?,?,?,?,?,?,now())",
        renderId,
        tenantId,
        input.templateId(),
        actor.userId(),
        locale,
        status,
        skus.size(),
        skus.size(),
        complete,
        incomplete,
        issueCount,
        errors.isEmpty() ? "" : String.join("；", errors));
    for (ContentRenderSkuRow row : skuRows)
      sql.execute(
          "insert into content_render_sku(id,tenant_id,render_id,sku_id,outcome,complete_count,"
              + "incomplete_count,issue_count,reason) values(?,?,?,?,?,?,?,?,?)",
          row.id(), tenantId, renderId, row.skuId(), row.outcome(), row.completeCount(),
          row.incompleteCount(), row.issueCount(), row.reason());

    // 段落与缺失报告。渲染时算出的 issues 与短语文本快照原样写入，不在落库时重算——
    // 重算就等于「落库的值」与「决定它算不算缺的那个判断」可能不是一回事。
    for (SectionDraft draft : drafts) {
      DescriptionRenderer.Rendered rendered = draft.rendered();
      String status0 = rendered.complete() ? "COMPLETE" : "INCOMPLETE";
      UUID sectionId = Ids.next();
      // INCOMPLETE 的正文里也绝不能有占位符（渲染器已经剥掉了，这里是纵深校验）：
      // 写入时若真的残留，V21 的 guard_content_render_section_text 会直接拒绝整批事务，
      // 而不是把半成品写进表里。
      sql.execute(
          "insert into content_render_section(id,tenant_id,render_id,render_sku_id,sku_id,"
              + "content_type,locale,text,char_length,used_phrases,status)"
              + " values(?,?,?,?,?,?,?,?,?,?::jsonb,?)",
          sectionId,
          tenantId,
          renderId,
          draft.renderSkuId(),
          draft.sku().skuId(),
          spec.contentType(),
          locale,
          rendered.text(),
          rendered.charLength(),
          descriptions.jsonArray(rendered.usedPhrases()),
          status0);
      for (DescriptionRenderer.Issue issue : rendered.issues())
        sql.execute(
            "insert into content_render_issue(id,tenant_id,render_id,section_id,render_sku_id,sku_id,"
                + "variable_name,issue_kind,detail) values(?,?,?,?,?,?,?,?,?)",
            Ids.next(), tenantId, renderId, sectionId, draft.renderSkuId(),
            draft.sku().skuId(), issue.variableName(), issue.issueKind(), issue.detail());
    }

    descriptions.audit(actor, renderId, "ContentRenderSubmitted",
        "模板 " + template.get("code", String.class) + "，语言 " + locale + "，" + skus.size()
            + " 个 SKU，" + complete + " 段已填好，" + incomplete + " 段缺变量");
    return render(renderId);
  }

  /** 一段待落库的填充结果（渲染完成、还没写进 content_render_section）。 */
  private record SectionDraft(
      UUID renderSkuId, DescriptionRenderer.SkuFacts sku, DescriptionRenderer.Rendered rendered) {}

  // ============================================================ 批次查询

  /** 批次列表：分页 + 关键词（搜批次与模板名）+ 状态筛选。 */
  public ContentRenderPage listRenders(ContentRenderQuery query) {
    int page = Math.max(0, query.page());
    int size = Math.min(Math.max(query.size(), 1), DescriptionService.MAX_PAGE_SIZE);
    UUID tenantId = descriptions.currentTenantId();

    var counts =
        sql.fetchOne(
            "select count(*) as total,"
                + "count(*) filter (where status='SUCCEEDED') as succeeded_count,"
                + "count(*) filter (where status='PARTIAL') as partial_count,"
                + "count(*) filter (where status='FAILED') as failed_count,"
                + "coalesce(sum(issue_count),0) as issue_count"
                + " from content_render where tenant_id=?",
            tenantId);

    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    binds.add(tenantId);
    String keyword = MediaRules.normalize(query.keyword());
    if (!keyword.isEmpty()) {
      where.add("(t.name ilike ? escape '!' or t.code ilike ? escape '!')");
      binds.add(DescriptionService.escapeLike(keyword));
      binds.add(DescriptionService.escapeLike(keyword.toUpperCase(Locale.ROOT)));
    }
    String status = MediaRules.normalizeUpper(query.status());
    if (!status.isEmpty()) {
      CatalogService.check(
          DescriptionRules.RENDER_STATUSES.contains(status),
          "批次状态只能是 " + String.join(" / ", DescriptionRules.RENDER_STATUSES));
      where.add("r.status=?");
      binds.add(status);
    }
    String clause = where.isEmpty() ? "" : " and " + String.join(" and ", where);

    long total =
        sql.fetchOne(
                "select count(*) from content_render r join content_template t"
                    + " on t.tenant_id=r.tenant_id and t.id=r.template_id where r.tenant_id=?"
                    + clause,
                binds.toArray())
            .get(0, Long.class);
    var bindsPage = new ArrayList<>(binds);
    bindsPage.add(size);
    bindsPage.add(page * size);
    var rows =
        sql.fetch(
            "select r.id,r.template_id,r.locale,r.status,r.sku_count,r.section_count,"
                + "r.complete_count,r.incomplete_count,r.issue_count,r.error,r.created_at,"
                + "r.finished_at,r.version,t.name as template_name,t.code as template_code,"
                + "t.content_type"
                + " from content_render r join content_template t"
                + " on t.tenant_id=r.tenant_id and t.id=r.template_id"
                + " where r.tenant_id=?" + clause
                + " order by r.created_at desc,r.id desc limit ? offset ?",
            bindsPage.toArray());
    List<ContentRenderRow> items = new ArrayList<>();
    for (Record r : rows) items.add(toRenderRow(r));
    return new ContentRenderPage(
        items,
        total,
        page,
        size,
        counts.get("succeeded_count", Long.class),
        counts.get("partial_count", Long.class),
        counts.get("failed_count", Long.class),
        counts.get("issue_count", Long.class),
        "缺变量不是失败：「部分段落缺变量」表示正文照样产出了，只是其中若干变量没填上，"
            + "缺了什么在批次详情里逐条列出。补完模板变量或短语池后重跑这一批即可。");
  }

  /** 批次详情：批次 + 逐 SKU 结果 + 全部段落（含缺失报告）+ 按变量汇总。 */
  public ContentRenderDetail renderDetail(UUID renderId) {
    ContentRenderRow row = toRenderRow(renderRow(renderId));
    List<ContentRenderSkuRow> skus = renderSkus(renderId);
    List<ContentSection> sections = sectionsOfRender(renderId);
    List<ContentMissingVariable> missing = missingVariablesOf(renderId);
    String note =
        missing.isEmpty()
            ? "这一批的变量都填上了：" + sections.size() + " 段正文可直接被 04 刊登与 15 客服引用。"
            : "有 " + missing.size() + " 个变量在填充时取不到值（合计 " + row.issueCount()
                + " 次）。正文里对应的占位符已被剥掉，不会带着 {brand} 这样的字面量发到平台上；"
                + "把变量补上后重跑这一批即可。";
    return new ContentRenderDetail(row, skus, sections, missing, note);
  }

  /** 某批次的逐 SKU 结果。 */
  public List<ContentRenderSkuRow> renderSkus(UUID renderId) {
    return sql.fetch(
            "select r.id,r.render_id,r.sku_id,s.code,s.name,p.name as spu_name,p.brand,p.category,"
                + "r.outcome,r.complete_count,r.incomplete_count,r.issue_count,r.reason"
                + " from content_render_sku r"
                + " join sku s on s.tenant_id=r.tenant_id and s.id=r.sku_id"
                + " join spu p on p.tenant_id=s.tenant_id and p.id=s.spu_id"
                + " where r.tenant_id=? and r.render_id=? order by s.code,r.id",
            descriptions.currentTenantId(), renderId)
        .stream()
        .map(
            r -> {
              String outcome = r.get("outcome", String.class);
              return new ContentRenderSkuRow(
                  r.get("id", UUID.class),
                  r.get("render_id", UUID.class),
                  r.get("sku_id", UUID.class),
                  r.get("code", String.class),
                  r.get("name", String.class),
                  r.get("spu_name", String.class),
                  r.get("brand", String.class),
                  r.get("category", String.class),
                  outcome,
                  DescriptionRules.outcomeLabel(outcome),
                  r.get("complete_count", Integer.class),
                  r.get("incomplete_count", Integer.class),
                  r.get("issue_count", Integer.class),
                  r.get("reason", String.class));
            })
        .toList();
  }

  /**
   * 按变量汇总的缺失情况。
   *
   * <p>逐变量加总而不是逐段落罗列：200 个 SKU 各缺 brand 时，
   * 「brand 缺 200 次」比 200 行「brand 缺了」有用得多，
   * 而且它直接对应「该去补品牌字段还是该去补短语库」这个决定。
   */
  public List<ContentMissingVariable> missingVariablesOf(UUID renderId) {
    return sql.fetch(
            "select i.variable_name,i.issue_kind,count(*) as occurrences,min(i.detail) as detail"
                + " from content_render_issue i where i.tenant_id=? and i.render_id=?"
                + " group by i.variable_name,i.issue_kind"
                + " order by count(*) desc,i.variable_name",
            descriptions.currentTenantId(), renderId)
        .stream()
        .map(
            r -> {
              String kind = r.get("issue_kind", String.class);
              return new ContentMissingVariable(
                  r.get("variable_name", String.class),
                  kind,
                  DescriptionRules.issueKindLabel(kind),
                  r.get("occurrences", Long.class),
                  r.get("detail", String.class));
            })
        .toList();
  }

  // ============================================================ 段落查询

  /** 段落列表：分页 + SKU / 语言 / 内容类型 / 状态 / 关键词 / 批次筛选。 */
  public ContentSectionPage listSections(ContentSectionQuery query) {
    int page = Math.max(0, query.page());
    int size = Math.min(Math.max(query.size(), 1), DescriptionService.MAX_PAGE_SIZE);

    var counts =
        sql.fetchOne(
            "select count(*) as total,"
                + "count(*) filter (where status='COMPLETE') as complete_count,"
                + "count(*) filter (where status='INCOMPLETE') as incomplete_count"
                + " from content_render_section where tenant_id=?",
            descriptions.currentTenantId());
    long issueCount =
        sql.fetchOne(
                "select count(*) from content_render_issue where tenant_id=?",
                descriptions.currentTenantId())
            .get(0, Long.class);
    String where = sectionWhere(query);
    List<Object> binds = sectionBinds(query);
    // 计数查询也必须带 s 别名：sectionWhere 生成的是 `s.xxx=?` 片段，
    // 而不带别名的 `from content_render_section` 里没有 s —— jOOQ 会报
    // 「未设定参数值 3 的内容」（参数在，位置对不上）。
    long total =
        sql.fetchOne(
                "select count(*) from content_render_section s where s.tenant_id=?"
                    + (where.isEmpty() ? "" : " and " + where),
                concat(List.of(descriptions.currentTenantId()), binds).toArray())
            .get(0, Long.class);

    var rows =
        sql.fetch(
            SECTION_SQL + whereClause(where) + SECTION_ORDER + " limit ? offset ?",
            // 绑定顺序必须与 SQL 里的 '?' 逐字对应：tenant_id → 筛选条件 → 分页。
            paged(concat(List.of(descriptions.currentTenantId()), binds), size, page * size));
    // 缺失报告一次查齐整页（N+1 会让 200 条的列表页发 200 条查询）。
    Map<UUID, List<ContentRenderIssue>> issueMap = issuesOfSections(rows);
    List<ContentSection> items = new ArrayList<>();
    for (Record r : rows) items.add(toSection(r, issueMap));
    return new ContentSectionPage(
        items,
        total,
        page,
        size,
        counts.get("complete_count", Long.class),
        counts.get("incomplete_count", Long.class),
        issueCount,
        "只有「已填好」的段落才会被 04 刊登与 15 客服引用；缺变量的段落正文里没有占位符，"
            + "但标着缺变量，不能当成可发布内容。");
  }

  /**
   * 导出段落 CSV（供 04 刊登与 15 客服取用）。
   *
   * <p>每批在流式线程里用 {@code access.run} 重新开租户事务：那批连接上没有
   * {@code app.current_tenant}，FORCE RLS 会把所有行藏起来，
   * 于是导出只有一个表头（3.4.2.1 的 {@code TitleService.streamCsv} 踩过这个坑，
   * 症状是「查出来 0 行还不报错」）。
   *
   * <p>筛选条件与 {@code listSections} 共用同一段 SQL 拼装，
   * 所以「导出结果」与「当前筛选」永远一致。
   */
  public void streamCsv(
      AccessService.Access actor, ContentSectionQuery query, java.util.function.Consumer<String> emit) {
    int size = 200;
    int offset = 0;
    String where = sectionWhere(query);
    List<Object> filterBinds = sectionBinds(query);
    // 流式线程的连接上没有 app.current_tenant，tenant_id 只能由 actor 显式拼进 WHERE。
    // 顺序必须与 SECTION_SQL 里的 '?' 逐字对应：先是 tenant_id，再是筛选条件，再是分页。
    List<Object> binds = new ArrayList<>();
    binds.add(actor.tenantId());
    binds.addAll(filterBinds);
    emit.accept(
        "SKU编码,SPU名称,商品名称,品牌,分类,内容类型,语言,状态,字符数,缺失变量,缺失原因,正文\n");
    while (true) {
      int at = offset;
      List<Record> batch =
          access.run(
              actor,
              "catalog.spu.read",
              () ->
                  sql.fetch(
                      SECTION_SQL
                          + whereClause(where)
                          + " order by s.sku_id,s.locale,s.id limit ? offset ?",
                      paged(binds, size, at)));
      if (batch.isEmpty()) break;
      Map<UUID, List<ContentRenderIssue>> issueMap = issuesOfSections(batch);
      var sb = new StringBuilder();
      for (Record r : batch) {
        ContentSection section = toSection(r, issueMap);
        var line = new ArrayList<String>();
        line.add(section.skuCode());
        line.add(section.spuName());
        line.add(section.skuName());
        line.add(r.get("brand", String.class));
        line.add(r.get("category", String.class));
        line.add(section.contentTypeLabel());
        line.add(section.locale());
        line.add(section.statusLabel());
        line.add(String.valueOf(section.charLength()));
        line.add(
            section.issues().isEmpty()
                ? ""
                : section.issues().stream()
                    .map(ContentRenderIssue::variableName)
                    .collect(java.util.stream.Collectors.joining(" ")));
        line.add(
            section.issues().isEmpty()
                ? ""
                : section.issues().stream()
                    .map(ContentRenderIssue::issueKindLabel)
                    .collect(java.util.stream.Collectors.joining("；")));
        // 正文列放在最后：它是唯一含换行的字段，放在中间会让某些表格软件
        // 把一行拆成多行。RFC 4180 的引号转义能保住换行，但阅读体验仍然更差。
        line.add(section.text());
        sb.append(String.join(",", line.stream().map(DescriptionService::csv).toList()))
            .append('\n');
      }
      // 每批立刻写出并让出内存，峰值只与单批大小相关。
      emit.accept(sb.toString());
      offset += batch.size();
    }
  }

  /** 段落的公共查询片段：列表与 CSV 导出共用，保证两边口径逐字一致。 */
  private static final String SECTION_SQL =
      "select s.id,s.render_id,s.render_sku_id,s.sku_id,s.content_type,s.locale,s.text,"
          + "s.char_length,s.used_phrases,s.status,s.created_at,"
          + "k.code,k.name,p.name as spu_name,p.brand,p.category"
          + " from content_render_section s"
          + " join sku k on k.tenant_id=s.tenant_id and k.id=s.sku_id"
          + " join spu p on p.tenant_id=k.tenant_id and p.id=k.spu_id"
          + " where s.tenant_id=?";

  private static final String SECTION_ORDER = " order by s.sku_id,s.locale,s.id";

  private static String whereClause(String where) {
    return where.isEmpty() ? "" : " and " + where;
  }

  private static Object[] paged(List<Object> binds, int size, int offset) {
    var all = new ArrayList<>(binds);
    all.add(size);
    all.add(offset);
    return all.toArray();
  }

  /** 组装段落的 WHERE 片段（不含 tenant 条件与分页）。 */
  private String sectionWhere(ContentSectionQuery query) {
    List<String> where = new ArrayList<>();
    if (query.skuId() != null) where.add("s.sku_id=?");
    String locale = DescriptionRules.normalizeLocale(query.locale());
    if (!locale.isEmpty()) where.add("s.locale=?");
    String contentType = MediaRules.normalizeUpper(query.contentType());
    if (!contentType.isEmpty()) {
      CatalogService.check(
          DescriptionRules.CONTENT_TYPES.contains(contentType),
          "内容类型只能是 " + String.join(" / ", DescriptionRules.CONTENT_TYPES));
      where.add("s.content_type=?");
    }
    String status = MediaRules.normalizeUpper(query.status());
    if (!status.isEmpty()) {
      CatalogService.check(
          DescriptionRules.SECTION_STATUSES.contains(status),
          "段落状态只能是 " + String.join(" / ", DescriptionRules.SECTION_STATUSES));
      where.add("s.status=?");
    }
    if (query.renderId() != null) where.add("s.render_id=?");
    String keyword = MediaRules.normalize(query.keyword());
    if (!keyword.isEmpty()) where.add("s.text ilike ? escape '!'");
    return String.join(" and ", where);
  }

  /** 段落的绑定参数（顺序与 {@link #sectionWhere} 逐字对应）。 */
  private List<Object> sectionBinds(ContentSectionQuery query) {
    List<Object> binds = new ArrayList<>();
    if (query.skuId() != null) binds.add(query.skuId());
    String locale = DescriptionRules.normalizeLocale(query.locale());
    if (!locale.isEmpty()) binds.add(locale);
    String contentType = MediaRules.normalizeUpper(query.contentType());
    if (!contentType.isEmpty()) binds.add(contentType);
    String status = MediaRules.normalizeUpper(query.status());
    if (!status.isEmpty()) binds.add(status);
    if (query.renderId() != null) binds.add(query.renderId());
    String keyword = MediaRules.normalize(query.keyword());
    if (!keyword.isEmpty()) binds.add(DescriptionService.escapeLike(keyword));
    return binds;
  }

  /** 批量取批次的全部段落。 */
  private List<ContentSection> sectionsOfRender(UUID renderId) {
    var rows =
        sql.fetch(
            "select s.id,s.render_id,s.render_sku_id,s.sku_id,s.content_type,s.locale,s.text,"
                + "s.char_length,s.used_phrases,s.status,s.created_at,k.code,k.name,"
                + "p.name as spu_name"
                + " from content_render_section s"
                + " join sku k on k.tenant_id=s.tenant_id and k.id=s.sku_id"
                + " join spu p on p.tenant_id=k.tenant_id and p.id=k.spu_id"
                + " where s.tenant_id=? and s.render_id=? order by k.code,s.id",
            descriptions.currentTenantId(), renderId);
    Map<UUID, List<ContentRenderIssue>> issueMap = issuesOfSections(rows);
    List<ContentSection> out = new ArrayList<>(rows.size());
    for (Record r : rows) out.add(toSection(r, issueMap));
    return out;
  }

  /** 一次查齐一批段落上的缺失报告，避免逐段落查一次（N+1）。 */
  private Map<UUID, List<ContentRenderIssue>> issuesOfSections(List<Record> sectionRows) {
    if (sectionRows.isEmpty()) return Map.of();
    List<UUID> ids = sectionRows.stream().map(r -> r.get("id", UUID.class)).toList();
    Map<UUID, List<ContentRenderIssue>> out = new LinkedHashMap<>();
    for (Record r :
        sql.fetch(
            "select i.id,i.section_id,i.sku_id,i.variable_name,i.issue_kind,i.detail,k.code"
                + " from content_render_issue i"
                + " join sku k on k.tenant_id=i.tenant_id and k.id=i.sku_id"
                + " where i.tenant_id=? and i.section_id in (" + DescriptionService.placeholders(ids)
                + ") order by i.variable_name",
            concat(List.of(descriptions.currentTenantId()), ids).toArray()))
      out.computeIfAbsent(r.get("section_id", UUID.class), k -> new ArrayList<>())
          .add(toIssue(r));
    return out;
  }

  private ContentRenderIssue toIssue(Record r) {
    String kind = r.get("issue_kind", String.class);
    return new ContentRenderIssue(
        r.get("id", UUID.class),
        r.get("section_id", UUID.class),
        r.get("sku_id", UUID.class),
        r.get("code", String.class),
        r.get("variable_name", String.class),
        kind,
        DescriptionRules.issueKindLabel(kind),
        r.get("detail", String.class));
  }

  private ContentSection toSection(Record r, Map<UUID, List<ContentRenderIssue>> issueMap) {
    String contentType = r.get("content_type", String.class);
    String status = r.get("status", String.class);
    List<ContentRenderIssue> issues = issueMap.getOrDefault(r.get("id", UUID.class), List.of());
    List<String> phrases = descriptions.parseJsonArray(r.get("used_phrases", String.class));
    return new ContentSection(
        r.get("id", UUID.class),
        r.get("render_id", UUID.class),
        r.get("render_sku_id", UUID.class),
        r.get("sku_id", UUID.class),
        r.get("code", String.class),
        r.get("name", String.class),
        r.get("spu_name", String.class),
        contentType,
        DescriptionRules.contentTypeLabel(contentType),
        r.get("locale", String.class),
        r.get("text", String.class),
        r.get("char_length", Integer.class),
        status,
        DescriptionRules.sectionStatusLabel(status),
        "COMPLETE".equals(status),
        issues,
        phrases,
        DescriptionService.instant(r.get("created_at", OffsetDateTime.class)));
  }

  // ============================================================ 工具

  ContentRender render(UUID id) {
    return toRender(renderRow(id));
  }

  private ContentRender toRender(Record r) {
    int complete = r.get("complete_count", Integer.class);
    int incomplete = r.get("incomplete_count", Integer.class);
    int issueCount = r.get("issue_count", Integer.class);
    String status = r.get("status", String.class);
    String contentType = r.get("content_type", String.class);
    String note =
        "FAILED".equals(status)
            ? "这一批一个可用段落都没产出：看批次详情里逐 SKU 的原因，"
                + "通常是绑定的短语池一条短语都没有。"
            : "已填出 " + complete + " 段可直接引用的正文"
                + (incomplete > 0
                    ? "，另有 " + incomplete + " 段缺变量（合计 " + issueCount
                        + " 次，缺了什么逐条列在批次详情里）"
                    : "")
                + "。缺变量的段落里占位符已被剥掉，不会带着字面量发到平台上。";
    return new ContentRender(
        r.get("id", UUID.class),
        r.get("template_id", UUID.class),
        r.get("template_name", String.class),
        contentType,
        DescriptionRules.contentTypeLabel(contentType),
        r.get("locale", String.class),
        status,
        DescriptionRules.renderStatusLabel(status),
        r.get("sku_count", Integer.class),
        r.get("section_count", Integer.class),
        complete,
        incomplete,
        issueCount,
        r.get("error", String.class),
        note);
  }

  private ContentRenderRow toRenderRow(Record r) {
    String status = r.get("status", String.class);
    String contentType = r.get("content_type", String.class);
    return new ContentRenderRow(
        r.get("id", UUID.class),
        r.get("template_id", UUID.class),
        r.get("template_name", String.class),
        r.get("template_code", String.class),
        contentType,
        DescriptionRules.contentTypeLabel(contentType),
        r.get("locale", String.class),
        status,
        DescriptionRules.renderStatusLabel(status),
        r.get("sku_count", Integer.class),
        r.get("section_count", Integer.class),
        r.get("complete_count", Integer.class),
        r.get("incomplete_count", Integer.class),
        r.get("issue_count", Integer.class),
        r.get("error", String.class),
        DescriptionService.instant(r.get("created_at", OffsetDateTime.class)),
        DescriptionService.instant(r.get("finished_at", OffsetDateTime.class)),
        r.get("version", Long.class));
  }

  private Record renderRow(UUID id) {
    var row =
        sql.fetchOne(
            "select r.id,r.template_id,r.locale,r.status,r.sku_count,r.section_count,"
                + "r.complete_count,r.incomplete_count,r.issue_count,r.error,r.created_at,"
                + "r.finished_at,r.version,t.name as template_name,t.code as template_code,"
                + "t.content_type"
                + " from content_render r join content_template t"
                + " on t.tenant_id=r.tenant_id and t.id=r.template_id where r.id=?",
            id);
    if (row == null) throw CatalogService.missing();
    return row;
  }

  private Record translationRow(UUID templateId, String locale) {
    return sql.fetchOne(
        "select body from content_template_translation where template_id=? and locale=?",
        templateId, locale);
  }

  /** 填充用：模板变量的 name → spec。 */
  private Map<String, DescriptionRenderer.VariableSpec> templateVariables(UUID templateId) {
    Map<String, DescriptionRenderer.VariableSpec> out = new LinkedHashMap<>();
    descriptions
        .variablesOf(List.of(templateId))
        .getOrDefault(templateId, List.of())
        .forEach(
            v ->
                out.put(
                    v.name(),
                    new DescriptionRenderer.VariableSpec(v.name(), v.source(), v.slotNo())));
    return out;
  }

  /**
   * 填充用：SKU 事实快照（含 SPU 的品牌与分类，以及同 SPU 下的型号清单）。
   *
   * <p>{@code p.name} 必须显式起别名 {@code as spu_name}：不起别名时 s.name 与 p.name
   * 都落在 "name" 列上，取到的会是**后一列**的值，于是每个 SKU 的商品名都变成 SPU 名。
   */
  private List<DescriptionRenderer.SkuFacts> skuFacts(List<UUID> skuIds) {
    List<DescriptionRenderer.SkuFacts> out = new ArrayList<>();
    for (UUID skuId : skuIds) {
      var row =
          sql.fetchOne(
              "select s.id,s.code,s.name,s.variant_signature,p.name as spu_name,p.brand,p.category,"
                  + "(select string_agg(k.code,' / ' order by k.code) from sku k"
                  + "  where k.tenant_id=s.tenant_id and k.spu_id=s.spu_id and k.id<>s.id)"
                  + "  as sibling_codes"
                  + " from sku s join spu p on p.tenant_id=s.tenant_id and p.id=s.spu_id"
                  + " where s.tenant_id=? and s.id=?",
              descriptions.currentTenantId(), skuId);
      if (row == null)
        throw new ResponseStatusException(
            HttpStatus.NOT_FOUND, "SKU 不存在或不属于当前租户：" + skuId);
      String siblings = row.get("sibling_codes", String.class);
      out.add(
          new DescriptionRenderer.SkuFacts(
              row.get("id", UUID.class),
              MediaRules.normalize(row.get("code", String.class)),
              MediaRules.normalize(row.get("name", String.class)),
              MediaRules.normalize(row.get("spu_name", String.class)),
              MediaRules.normalize(row.get("brand", String.class)),
              MediaRules.normalize(row.get("category", String.class)),
              MediaRules.normalize(row.get("variant_signature", String.class)),
              siblings == null || siblings.isBlank() ? List.of() : List.of(siblings)));
    }
    return out;
  }

  private static <T> List<T> concat(List<T> first, List<T> second) {
    var all = new ArrayList<T>(first.size() + second.size());
    all.addAll(first);
    all.addAll(second);
    return all;
  }
}
