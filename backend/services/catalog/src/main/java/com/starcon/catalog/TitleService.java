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
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 3.4.2.1 标题库：标题模板（变量占位）、标题词根字典、批量生成批次、生成的标题候选。
 *
 * <p>蓝图原文：「沉淀可复用的标题模板与标题记录，支持变量占位（品牌+品类+核心词+卖点）， 批量生成差异化标题（同一商品在多平台不同标题以避重复铺货判定）。」
 * 「输入：标题模板；批量生成参数（SKU 范围、平台、词根）。」 「输出：生成的标题候选集；供 04 选用或导出。」 「上游 = 3.1.1.1；下游 = 04 刊登。」
 *
 * <p>本类的纪律：
 *
 * <ol>
 *   <li><b>重复度控制在算法里，不在调用方手里。</b>候选由 {@link TitleGenerator}（纯函数）产出，本类只负责喂齐它需要的三类词池、 SKU 事实快照与「该 SKU
 *       已有标题」。相似度一旦超限是**拒绝并给出原因**， 不是放宽阈值凑数——3.4.2.1 的存在理由就是「不要雷同」。
 *   <li><b>SKU 范围与词池在提交时快照。</b>批量跑到一半商品改名、词库被改， 这一批候选的口径都必须仍是提交那一刻的，否则「这批标题为什么长这样」答不出来。
 *   <li><b>重复度比较范围限定在单个 SKU 内</b>（已有 + 本次新生成）。 平台判重复铺货看的是同一个商品在不同站点的标题，而不是两个不同商品之间 （后者是品类共性）。范围收在单
 *       SKU 也让耗时与 SKU 总数无关。
 *   <li><b>生成是同步的。</b>与 3.4.1.2 不同，那里的长耗时来自逐像素加工； 这里的成本只与「单个 SKU 内的候选数」有关，几百个 SKU 是几十毫秒。
 *       做成异步任务只会让运营为一个几百毫秒的操作去盯进度条。
 * </ol>
 *
 * <p>权限口径：读 {@code catalog.spu.read}，写 {@code catalog.spu.update}。 标题是挂在 SKU 上的商品文案、上游是 3.1.1.1 的
 * SPU/SKU，所以沿用 spu 词表 （不新造——新词表要改 identity 服务的迁移，属于另一个服务的写入所有权）。
 */
@Service
public class TitleService {
  /** 单页最大行数。 */
  private static final int MAX_PAGE_SIZE = 200;

  private final DSLContext sql;
  private final AccessService access;

  public TitleService(DSLContext sql, AccessService access) {
    this.sql = sql;
    this.access = access;
  }

  // ============================================================ 概览

  /**
   * 标题库概览：模板数、词根数、批次与候选规模、以及全局最严的重复度上限。
   *
   * <p>{@code maxSimilarityPermille} 取所有启用模板里**最严**的那个（最小值）： 概览页上写的应该是「你这个租户现在管得最严的是什么」，
   * 而不是随便挑一个模板的阈值。
   */
  public TitleOverview overview() {
    UUID tenantId = currentTenantId();
    var counts =
        sql.fetchOne(
            "select count(*) as generation_count,"
                + "count(*) filter (where status='SUCCEEDED') as succeeded_count,"
                + "count(*) filter (where status='PARTIAL') as partial_count,"
                + "count(*) filter (where status='FAILED') as failed_count"
                + " from title_generation where tenant_id=?",
            tenantId);
    long templateCount =
        sql.fetchOne("select count(*) from title_template where tenant_id=?", tenantId)
            .get(0, Long.class);
    long enabledTemplates =
        sql.fetchOne("select count(*) from title_template where tenant_id=? and enabled", tenantId)
            .get(0, Long.class);
    var keywords =
        sql.fetchOne(
            "select count(*) as total,"
                + "count(*) filter (where keyword_type='CORE') as core_count,"
                + "count(*) filter (where keyword_type='SELLING') as selling_count,"
                + "count(*) filter (where keyword_type='MODIFIER') as modifier_count"
                + " from title_keyword where tenant_id=?",
            tenantId);
    var records =
        sql.fetchOne(
            "select count(*) as total,"
                + "count(*) filter (where status='SELECTED') as selected_count,"
                + "count(*) filter (where truncated) as truncated_count"
                + " from title_record where tenant_id=?",
            tenantId);
    Integer strictest =
        sql.fetchOne(
                "select min(max_similarity_permille) from title_template where tenant_id=? and enabled",
                tenantId)
            .get(0, Integer.class);
    return new TitleOverview(
        templateCount,
        enabledTemplates,
        keywords.get("total", Long.class),
        keywords.get("core_count", Long.class),
        keywords.get("modifier_count", Long.class),
        counts.get("generation_count", Long.class),
        records.get("total", Long.class),
        records.get("selected_count", Long.class),
        records.get("truncated_count", Long.class),
        strictest == null ? TitleRules.DEFAULT_SIMILARITY_PERMILLE : strictest,
        "重复度控制写在生成算法里：每条候选都带着「它与本 SKU 已有标题的最大相似度」落库，"
            + "超过模板设定上限的组合会被拒绝并给出原因，而不是放宽阈值凑数。"
            + "相似度只在同一 SKU 内比较——平台判重复铺货看的是同一个商品在不同站点的标题。");
  }

  // ============================================================ 模板

  /** 模板列表（含变量与平台上限）。按编码排序，停用的也在列表里（要看历史配置）。 */
  public List<TitleTemplateRow> templates() {
    var rows =
        sql.fetch(
            "select id,code,name,note,pattern,max_similarity_permille,candidate_count,enabled,version,"
                + "created_at,updated_at from title_template where tenant_id=? order by code",
            currentTenantId());
    if (rows.isEmpty()) return List.of();
    List<UUID> ids = rows.stream().map(r -> r.get("id", UUID.class)).toList();
    Map<UUID, List<TitleTemplateVariable>> variables = variablesOf(ids);
    Map<UUID, List<TitleTemplatePlatform>> platforms = platformsOf(ids);
    List<TitleTemplateRow> out = new ArrayList<>(rows.size());
    for (Record r : rows) {
      UUID id = r.get("id", UUID.class);
      out.add(
          new TitleTemplateRow(
              id,
              r.get("code", String.class),
              r.get("name", String.class),
              r.get("note", String.class),
              r.get("pattern", String.class),
              r.get("max_similarity_permille", Integer.class),
              r.get("candidate_count", Integer.class),
              Boolean.TRUE.equals(r.get("enabled", Boolean.class)),
              variables.getOrDefault(id, List.of()),
              platforms.getOrDefault(id, List.of()),
              r.get("version", Long.class),
              instant(r.get("created_at", OffsetDateTime.class)),
              instant(r.get("updated_at", OffsetDateTime.class))));
    }
    return out;
  }

  /** 单个模板（含变量与平台上限）。 */
  public TitleTemplateRow template(UUID id) {
    var row = templateRow(id);
    return new TitleTemplateRow(
        row.get("id", UUID.class),
        row.get("code", String.class),
        row.get("name", String.class),
        row.get("note", String.class),
        row.get("pattern", String.class),
        row.get("max_similarity_permille", Integer.class),
        row.get("candidate_count", Integer.class),
        Boolean.TRUE.equals(row.get("enabled", Boolean.class)),
        variablesOf(List.of(id)).getOrDefault(id, List.of()),
        platformsOf(List.of(id)).getOrDefault(id, List.of()),
        row.get("version", Long.class),
        instant(row.get("created_at", OffsetDateTime.class)),
        instant(row.get("updated_at", OffsetDateTime.class)));
  }

  /**
   * 建模板或改模板（id 为空即新建）。变量与平台上限**整体替换**。
   *
   * <p>整体替换而不是增量增删：变量集合与平台上限集合是「这条标题怎么拼、每个平台能有多长」 的完整口径，增删式会让「当前有哪些变量」这个问题有两个答案。
   *
   * <p>编码建后不可改：历史候选按模板解释，改编码会让旧批次的溯源对不上—— 与 {@code MediaService.saveTag} / {@code
   * ImageProcessService.saveRecipe} 同一纪律。
   */
  public TitleTemplateSaved saveTemplate(
      AccessService.Access actor, UUID id, TitleTemplateInput input) {
    String problem =
        firstNonNull(
            TitleRules.templateCodeProblem(input.code()),
            TitleRules.templateNameProblem(input.name()),
            TitleRules.patternProblem(input.pattern()),
            TitleRules.maxSimilarityProblem(input.maxSimilarityPermille()),
            TitleRules.candidateCountProblem(input.candidateCount()),
            TitleRules.variablesSizeProblem(
                input.variables() == null ? 0 : input.variables().size()),
            TitleRules.templatePlatformsSizeProblem(
                input.platforms() == null ? 0 : input.platforms().size()),
            variablesProblem(input.pattern(), input.variables()),
            platformsProblem(input.platforms()));
    CatalogService.check(problem == null, problem);

    String code = MediaRules.normalizeUpper(input.code());
    // 阈值与候选数给默认值：这两列 NOT NULL，null 会变成一条「没有重复度控制的模板」，
    // 而那正是本模块要消灭的东西——所以宁可给一个明确的安全默认。
    int maxSimilarity =
        input.maxSimilarityPermille() == null
            ? TitleRules.DEFAULT_SIMILARITY_PERMILLE
            : input.maxSimilarityPermille();
    int candidateCount = input.candidateCount() == null ? 3 : input.candidateCount();

    if (id == null) {
      if (sql.fetchOne("select id from title_template where code=?", code) != null)
        throw conflict("模板编码已存在：" + code);
      UUID newId = Ids.next();
      try {
        sql.execute(
            "insert into title_template(id,tenant_id,code,name,note,pattern,"
                + "max_similarity_permille,candidate_count,enabled) values(?,?,?,?,?,?,?,?,?)",
            newId,
            currentTenantId(),
            code,
            MediaRules.normalize(input.name()),
            MediaRules.normalize(input.note()),
            TitleRules.normalizePattern(input.pattern()),
            maxSimilarity,
            candidateCount,
            input.enabled());
        replaceVariables(newId, input.variables());
        replaceTemplatePlatforms(newId, input.platforms());
      } catch (DataAccessException e) {
        throw conflict("模板保存失败：请检查参数后重试（" + rootMessage(e) + "）");
      }
      audit(actor, newId, "TitleTemplateSaved", code);
      return templateSaved(newId);
    }

    var row = templateRow(id);
    CatalogService.check(
        row.get("code", String.class).equals(code),
        "模板编码建后不可修改：历史标题候选是按这套变量绑定解释的，改编码会让旧批次的溯源对不上。要改请新建一个模板");
    int updated;
    try {
      updated =
          sql.execute(
              "update title_template set name=?,note=?,pattern=?,max_similarity_permille=?,"
                  + "candidate_count=?,enabled=?,version=version+1,updated_at=now()"
                  + " where id=? and version=?",
              MediaRules.normalize(input.name()),
              MediaRules.normalize(input.note()),
              TitleRules.normalizePattern(input.pattern()),
              maxSimilarity,
              candidateCount,
              input.enabled(),
              id,
              input.version());
    } catch (DataAccessException e) {
      throw conflict("模板保存失败：" + rootMessage(e));
    }
    if (updated != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "模板已被他人修改，请刷新后重试");
    replaceVariables(id, input.variables());
    replaceTemplatePlatforms(id, input.platforms());
    audit(actor, id, "TitleTemplateSaved", code);
    return templateSaved(id);
  }

  /**
   * 变量校验：模板文本里的占位符与声明的变量必须互相对得上，且至少有一个词根变量。
   *
   * <p>「至少一个词根变量」不是形式主义：只绑 SPU/SKU 事实字段的模板每个 SKU 只有一种文本，候选之间的差异只能来自词序轮转（上限 = 变量个数）， 批量生成产出的候选会彼此雷同。
   */
  private String variablesProblem(String pattern, List<TitleTemplateVariableInput> variables) {
    if (variables == null || variables.isEmpty()) return "模板至少要声明一个变量占位";
    var names = new ArrayList<String>();
    var sources = new ArrayList<String>();
    var seenNames = new java.util.HashSet<String>();
    var seenSlots = new java.util.HashSet<Integer>();
    for (int i = 0; i < variables.size(); i++) {
      TitleTemplateVariableInput variable = variables.get(i);
      String nameProblem = TitleRules.variableNameProblem(variable.name());
      if (nameProblem != null) return nameProblem;
      String sourceProblem = TitleRules.sourceProblem(variable.source());
      if (sourceProblem != null) return sourceProblem;
      String name = MediaRules.normalize(variable.name());
      if (!seenNames.add(name)) return "变量名重复：" + name + "（一个占位符只能有一个绑定源）";
      // slotNo 省略时按声明顺序排：前端不必为「按顺序声明」这件事多传一个字段。
      int slot = variable.slotNo() == null ? i : variable.slotNo();
      if (slot < 0) return "变量渲染顺序不能为负数";
      if (!seenSlots.add(slot)) return "变量渲染顺序重复：第 " + slot + " 位有两个变量";
      names.add(name);
      sources.add(variable.source());
    }
    String binding = TitleRules.variableBindingProblem(pattern, names);
    if (binding != null) return binding;
    return TitleRules.needsKeywordVariableProblem(sources);
  }

  /** 平台上限校验：名字、长度上限、模板内重复。 */
  private String platformsProblem(List<TitleTemplatePlatformInput> platforms) {
    if (platforms == null || platforms.isEmpty()) return "模板至少要配置一个平台的长度上限";
    var names = new ArrayList<String>();
    for (TitleTemplatePlatformInput platform : platforms) {
      String nameProblem = TitleRules.platformNameProblem(platform.platform());
      if (nameProblem != null) return nameProblem;
      String lengthProblem = TitleRules.maxLengthProblem(platform.maxLength());
      if (lengthProblem != null) return lengthProblem;
      names.add(platform.platform());
    }
    return TitleRules.duplicatePlatformProblem(names);
  }

  /** 整体替换变量：先删后插，与 replaceRatios 同一形状。 */
  private void replaceVariables(UUID templateId, List<TitleTemplateVariableInput> variables) {
    sql.execute("delete from title_template_variable where template_id=?", templateId);
    List<TitleTemplateVariableInput> list = variables == null ? List.of() : variables;
    for (int i = 0; i < list.size(); i++) {
      TitleTemplateVariableInput variable = list.get(i);
      sql.execute(
          "insert into title_template_variable(id,tenant_id,template_id,slot_no,variable_name,source)"
              + " values(?,?,?,?,?,?)",
          Ids.next(),
          currentTenantId(),
          templateId,
          variable.slotNo() == null ? i : variable.slotNo(),
          MediaRules.normalize(variable.name()),
          MediaRules.normalizeUpper(variable.source()));
    }
  }

  /** 整体替换模板的平台长度上限。 */
  private void replaceTemplatePlatforms(
      UUID templateId, List<TitleTemplatePlatformInput> platforms) {
    sql.execute("delete from title_template_platform where template_id=?", templateId);
    List<TitleTemplatePlatformInput> list = platforms == null ? List.of() : platforms;
    for (TitleTemplatePlatformInput platform : list)
      sql.execute(
          "insert into title_template_platform(id,tenant_id,template_id,platform,max_length)"
              + " values(?,?,?,?,?)",
          Ids.next(),
          currentTenantId(),
          templateId,
          MediaRules.normalize(platform.platform()),
          platform.maxLength());
  }

  /** 模板启用停用。停用后不能再用它新建批次，历史批次与候选照常可读。 */
  public TitleTemplateSaved setTemplateEnabled(
      AccessService.Access actor, UUID id, boolean enabled) {
    int updated =
        sql.execute(
            "update title_template set enabled=?,version=version+1,updated_at=now() where id=?",
            enabled,
            id);
    if (updated != 1) throw CatalogService.missing();
    audit(actor, id, "TitleTemplateEnabledChanged", null);
    return templateSaved(id);
  }

  /** 删除模板。仍被生成批次引用时被拒（V20 的 refuse_title_template_delete 兜底）， 服务层先给可读原因：要清理请改用停用。 */
  public void deleteTemplate(AccessService.Access actor, UUID id, long version) {
    var row = templateRow(id);
    CatalogService.check(row.get("version", Long.class) == version, "模板已被他人修改，请刷新后重试");
    long runs = templateGenerationCount(id);
    CatalogService.check(
        runs == 0,
        "模板「"
            + row.get("code", String.class)
            + "」已被 "
            + runs
            + " 次批量生成引用，不可删除：请改用停用（停用后不能再用它新建批次，历史标题与候选照常可读）");
    sql.execute("delete from title_template where id=?", id);
    audit(actor, id, "TitleTemplateDeleted", null);
  }

  // ============================================================ 词根

  /** 词根列表：分页 + 关键词 + 类型筛选。每行自带被引用数，删除保护在列表上就看得见。 */
  public TitleKeywordPage keywords(TitleKeywordQuery query) {
    int page = Math.max(0, query.page());
    int size = Math.min(Math.max(query.size(), 1), MAX_PAGE_SIZE);
    UUID tenantId = currentTenantId();

    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    if (query.keywordType() != null && !query.keywordType().isBlank()) {
      String type = MediaRules.normalizeUpper(query.keywordType());
      CatalogService.check(
          TitleRules.KEYWORD_TYPES.contains(type),
          "词根类型只能是 " + String.join(" / ", TitleRules.KEYWORD_TYPES));
      where.add("keyword_type=?");
      binds.add(type);
    }
    if (query.keyword() != null && !query.keyword().isBlank()) {
      where.add("(code ilike ? escape '!' or text ilike ? escape '!')");
      String pattern = escapeLike(query.keyword().trim());
      binds.add(pattern);
      binds.add(pattern);
    }
    String sqlWhere = where.isEmpty() ? "" : " and " + String.join(" and ", where);

    long total =
        sql.fetchOne(
                "select count(*) from title_keyword where tenant_id=?" + sqlWhere,
                concat(List.of(tenantId), binds).toArray())
            .get(0, Long.class);
    var rows =
        sql.fetch(
            "select id,code,text,keyword_type,enabled,version,created_at,updated_at from title_keyword"
                + " where tenant_id=?"
                + sqlWhere
                + " order by keyword_type,code limit ? offset ?",
            concat(List.of(tenantId), concat(binds, List.of(size, (long) page * size))).toArray());
    var counts =
        sql.fetchOne(
            "select count(*) filter (where keyword_type='CORE') as core_count,"
                + "count(*) filter (where keyword_type='SELLING') as selling_count,"
                + "count(*) filter (where keyword_type='MODIFIER') as modifier_count"
                + " from title_keyword where tenant_id=?",
            tenantId);
    List<TitleKeywordListRow> items = new ArrayList<>(rows.size());
    for (Record r : rows) {
      UUID id = r.get("id", UUID.class);
      items.add(
          new TitleKeywordListRow(
              new TitleKeywordRow(
                  id,
                  r.get("code", String.class),
                  r.get("text", String.class),
                  r.get("keyword_type", String.class),
                  TitleRules.keywordTypeLabel(r.get("keyword_type", String.class)),
                  Boolean.TRUE.equals(r.get("enabled", Boolean.class)),
                  r.get("version", Long.class),
                  instant(r.get("created_at", OffsetDateTime.class)),
                  instant(r.get("updated_at", OffsetDateTime.class))),
              referenceCount(
                  id, r.get("keyword_type", String.class), r.get("text", String.class))));
    }
    return new TitleKeywordPage(
        items,
        total,
        page,
        size,
        counts.get("core_count", Long.class),
        counts.get("selling_count", Long.class),
        counts.get("modifier_count", Long.class),
        "词根是标题的差异化素材：核心词给主体、卖点给理由、修饰词拉开平台间差异。"
            + "被标题引用过的词根只能停用不能删——标题记录上快照的是它的文本，删了词库行那条文本就成了没有出处的孤儿。");
  }

  /** 某个词根被多少条标题引用（标题记录按类型分别快照在三个列上）。 */
  private long referenceCount(UUID id, String keywordType, String text) {
    String column =
        switch (keywordType == null ? "" : keywordType) {
          case "CORE" -> "core_keyword";
          case "SELLING" -> "selling_keyword";
          case "MODIFIER" -> "modifier_keyword";
          default -> null;
        };
    if (column == null) return 0;
    // 列名来自上面的白名单 switch，不是用户输入，拼进 SQL 不会带来注入面。
    // 绑定参数顺序必须是 (tenant, text)：曾经误传成 (id, text)，
    // 于是 tenant_id=? 拿词根 id 去比，永远返回 0 行——
    // 列表上的「被引用数」永远是 0，删除保护也形同虚设（实测修掉的真缺陷）。
    return sql.fetchOne(
            "select count(*) from title_record where tenant_id=? and " + column + "=?",
            currentTenantId(),
            text)
        .get(0, Long.class);
  }

  /** 建词根或改词根（id 为空即新建）。 */
  public TitleKeywordRow saveKeyword(AccessService.Access actor, UUID id, TitleKeywordInput input) {
    String problem =
        firstNonNull(
            TitleRules.keywordCodeProblem(input.code()),
            TitleRules.keywordTextProblem(input.text()),
            TitleRules.keywordTypeProblem(input.keywordType()));
    CatalogService.check(problem == null, problem);

    String code = MediaRules.normalizeUpper(input.code());
    String text = MediaRules.normalize(input.text());
    String type = MediaRules.normalizeUpper(input.keywordType());

    if (id == null) {
      if (sql.fetchOne("select id from title_keyword where code=?", code) != null)
        throw conflict("词根编码已存在：" + code);
      UUID newId = Ids.next();
      try {
        sql.execute(
            "insert into title_keyword(id,tenant_id,code,text,keyword_type,enabled)"
                + " values(?,?,?,?,?,?)",
            newId,
            currentTenantId(),
            code,
            text,
            type,
            input.enabled());
      } catch (DataAccessException e) {
        throw conflict("词根保存失败：请检查参数后重试（" + rootMessage(e) + "）");
      }
      audit(actor, newId, "TitleKeywordSaved", code);
      return toKeywordRow(keywordRow(newId));
    }

    var row = keywordRow(id);
    CatalogService.check(
        row.get("code", String.class).equals(code),
        "词根编码建后不可修改：历史标题候选按 code 溯源，改编码会让那条溯源对不上。要改请新建一个词根");
    int updated;
    try {
      updated =
          sql.execute(
              "update title_keyword set text=?,keyword_type=?,enabled=?,version=version+1,"
                  + "updated_at=now() where id=? and version=?",
              text,
              type,
              input.enabled(),
              id,
              input.version());
    } catch (DataAccessException e) {
      throw conflict("词根保存失败：" + rootMessage(e));
    }
    if (updated != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "词根已被他人修改，请刷新后重试");
    audit(actor, id, "TitleKeywordSaved", code);
    return toKeywordRow(keywordRow(id));
  }

  private TitleKeywordRow toKeywordRow(Record r) {
    return new TitleKeywordRow(
        r.get("id", UUID.class),
        r.get("code", String.class),
        r.get("text", String.class),
        r.get("keyword_type", String.class),
        TitleRules.keywordTypeLabel(r.get("keyword_type", String.class)),
        Boolean.TRUE.equals(r.get("enabled", Boolean.class)),
        r.get("version", Long.class),
        instant(r.get("created_at", OffsetDateTime.class)),
        instant(r.get("updated_at", OffsetDateTime.class)));
  }

  /** 词根启用停用。停用后不再进入生成词池，已有标题照常可读。 */
  public TitleKeywordRow setKeywordEnabled(AccessService.Access actor, UUID id, boolean enabled) {
    int updated =
        sql.execute(
            "update title_keyword set enabled=?,version=version+1,updated_at=now() where id=?",
            enabled,
            id);
    if (updated != 1) throw CatalogService.missing();
    audit(actor, id, "TitleKeywordEnabledChanged", null);
    return toKeywordRow(keywordRow(id));
  }

  /** 删除词根。被标题引用时不可删（V20 的 refuse_title_keyword_delete 兜底）， 服务层先给可读原因：要清理请改用停用。 */
  public void deleteKeyword(AccessService.Access actor, UUID id, long version) {
    var row = keywordRow(id);
    CatalogService.check(row.get("version", Long.class) == version, "词根已被他人修改，请刷新后重试");
    long refs =
        referenceCount(id, row.get("keyword_type", String.class), row.get("text", String.class));
    CatalogService.check(
        refs == 0,
        "词根「"
            + row.get("text", String.class)
            + "」已被 "
            + refs
            + " 条生成的标题引用，不可删除：标题记录上快照的是它的文本，删掉词库行那条文本就成了没有出处的孤儿。请改用停用");
    sql.execute("delete from title_keyword where id=?", id);
    audit(actor, id, "TitleKeywordDeleted", null);
  }

  // ============================================================ 批量生成

  /**
   * 批量生成标题候选（同步）。
   *
   * <p>提交参数就是蓝图点名的三样：**SKU 范围、平台、词根**。
   *
   * <p>流程刻意是「先把输入查齐并校验，再开始渲染」：用户点了提交之后才发现 「8 个平台里有 3 个模板没配长度上限」，而批次已经建好了，这体验很糟。
   */
  public TitleGeneration generate(AccessService.Access actor, TitleGenerationInput input) {
    String problem =
        firstNonNull(
            TitleRules.skusSizeProblem(input.skuIds() == null ? 0 : input.skuIds().size()),
            TitleRules.platformsSizeProblem(
                input.platforms() == null ? 0 : input.platforms().size()),
            TitleRules.keywordFilterSizeProblem(
                input.keywordCodes() == null ? 0 : input.keywordCodes().size()));
    CatalogService.check(problem == null, problem);
    CatalogService.check(input.templateId() != null, "请选择一个标题模板");

    var template = templateRow(input.templateId());
    CatalogService.check(
        Boolean.TRUE.equals(template.get("enabled", Boolean.class)),
        "模板「" + template.get("name", String.class) + "」已停用，不能用它生成标题。请换一个启用的模板，或先启用它");

    // 平台必须都在模板里配了长度上限——平台没有上限就渲染不出可发布的标题。
    Map<String, Integer> platformLimits =
        templatePlatformLimits(input.templateId(), input.platforms());
    Map<String, TitleGenerator.VariableSpec> variables = templateVariables(input.templateId());

    List<TitleGenerator.SkuFacts> skus = skuFacts(input.skuIds());
    TitleGenerator.KeywordPools pools = keywordPools(input.keywordCodes());

    // 词池全空时仍继续：模板可能只绑事实字段（虽然 TitleRules 至少要求一个词根变量），
    // 但某一类池子为空是常态（很多租户还没建卖点词），那时该类变量渲染为空、
    // 其余变量照常工作。这里只在**三类全空**时报错。
    CatalogService.check(
        !pools.core().isEmpty() || !pools.selling().isEmpty() || !pools.modifier().isEmpty(),
        "启用的词根库是空的：批量生成至少要有一个启用的核心词 / 卖点 / 修饰词，否则所有组合渲染出来都缺一块。请先在词根字典里建词");

    TitleGenerator.TemplateSpec spec =
        new TitleGenerator.TemplateSpec(
            template.get("pattern", String.class),
            List.copyOf(variables.values()),
            template.get("max_similarity_permille", Integer.class),
            template.get("candidate_count", Integer.class));

    UUID generationId = Ids.next();
    List<String> normalizedCodes =
        input.keywordCodes() == null
            ? List.of()
            : input.keywordCodes().stream().map(MediaRules::normalizeUpper).sorted().toList();

    // 一个 SKU 一行结果，逐平台汇总。同一个 SKU 的多个平台合并成一行，
    // 否则「这个 SKU 产出几条」要跨行加总才能回答。
    Map<UUID, SkuAccumulator> bySku = new LinkedHashMap<>();
    List<String> errors = new ArrayList<>();

    for (TitleGenerator.SkuFacts sku : skus) {
      SkuAccumulator accumulator = new SkuAccumulator(sku);
      // 该 SKU 已有的标题（历史批次 + 其它平台）——重复度比较的基线。
      List<String> existing = existingTitlesOf(sku.skuId());
      for (String platform : input.platforms()) {
        String name = MediaRules.normalize(platform);
        var result =
            TitleGenerator.generate(
                spec,
                sku,
                new TitleGenerator.PlatformSpec(name, platformLimits.get(name)),
                pools,
                existing);
        accumulator.merges(result);
      }
      if (accumulator.candidateCount == 0 && !accumulator.reasons.isEmpty())
        errors.add(accumulator.sku.skuCode() + "：" + accumulator.reasons.get(0));
      bySku.put(accumulator.sku.skuId(), accumulator);
    }

    int totalCandidates = bySku.values().stream().mapToInt(a -> a.candidateCount).sum();
    int totalRejected = bySku.values().stream().mapToInt(a -> a.rejectedCount).sum();
    String status =
        totalCandidates == 0
            ? "FAILED"
            : (bySku.values().stream().anyMatch(a -> !a.complete()) ? "PARTIAL" : "SUCCEEDED");

    sql.execute(
        "insert into title_generation(id,tenant_id,template_id,requested_by,status,sku_count,"
            + "candidate_count,rejected_count,keyword_filter,error) values(?,?,?,?,?,?,?,?,?,?)",
        generationId,
        currentTenantId(),
        input.templateId(),
        actor.userId(),
        status,
        skus.size(),
        totalCandidates,
        totalRejected,
        String.join(",", normalizedCodes),
        errors.isEmpty() ? "" : String.join("；", errors));
    for (SkuAccumulator accumulator : bySku.values()) {
      sql.execute(
          "insert into title_generation_sku(id,tenant_id,generation_id,sku_id,outcome,"
              + "candidate_count,rejected_count,reason) values(?,?,?,?,?,?,?,?)",
          Ids.next(),
          currentTenantId(),
          generationId,
          accumulator.sku.skuId(),
          accumulator.outcome(),
          accumulator.candidateCount,
          accumulator.rejectedCount,
          accumulator.reasons.isEmpty() ? "" : String.join("；", accumulator.reasons));
      // 候选逐条落库。生成时算出的相似度与词根快照原样写入，不在落库时重算——
      // 重算就等于「落库的值」与「生成时决定它过没过闸的值」可能不是一回事。
      for (TitleGenerator.Candidate candidate : accumulator.candidates)
        insertRecord(generationId, input.templateId(), candidate);
    }

    audit(
        actor,
        generationId,
        "TitleGenerationSubmitted",
        "模板 "
            + template.get("code", String.class)
            + "，"
            + skus.size()
            + " 个 SKU，"
            + input.platforms().size()
            + " 个平台，"
            + totalCandidates
            + " 条候选");
    return generation(generationId);
  }

  /** 逐 SKU 累加器：合并多个平台的结果，并算出这一行的 outcome。 */
  private static final class SkuAccumulator {
    private final TitleGenerator.SkuFacts sku;
    private final List<TitleGenerator.Candidate> candidates = new ArrayList<>();
    private final List<TitleGenerator.Rejection> rejections = new ArrayList<>();
    private final List<String> reasons = new ArrayList<>();
    private int expected;
    private int candidateCount;
    private int rejectedCount;

    SkuAccumulator(TitleGenerator.SkuFacts sku) {
      this.sku = sku;
    }

    void merges(TitleGenerator.SkuPlatformResult result) {
      candidates.addAll(result.candidates());
      rejections.addAll(result.rejections());
      expected += result.expected();
      candidateCount += result.candidates().size();
      rejectedCount += result.rejections().size();
      for (TitleGenerator.Rejection rejection : result.rejections())
        reasons.add(result.platform() + " 第 " + rejection.variantNo() + " 条：" + rejection.reason());
    }

    boolean complete() {
      return candidateCount == expected;
    }

    String outcome() {
      return candidateCount > 0 ? "GENERATED" : "REJECTED";
    }
  }

  /** 一条候选落库。生成时算出的相似度与词根快照原样写入，不在落库时重算。 */
  private void insertRecord(
      UUID generationId, UUID templateId, TitleGenerator.Candidate candidate) {
    sql.execute(
        "insert into title_record(id,tenant_id,generation_id,template_id,sku_id,platform,variant_no,"
            + "title,char_length,truncated,rotation,similarity_permille,core_keyword,"
            + "selling_keyword,modifier_keyword,status) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        Ids.next(),
        currentTenantId(),
        generationId,
        templateId,
        candidate.skuId(),
        candidate.platform(),
        candidate.variantNo(),
        candidate.title(),
        candidate.charLength(),
        candidate.truncated(),
        candidate.rotation(),
        candidate.similarityPermille(),
        candidate.coreKeyword(),
        candidate.sellingKeyword(),
        candidate.modifierKeyword(),
        "CANDIDATE");
  }

  // ============================================================ 批次查询

  /** 批次列表：分页 + 关键词 + 状态筛选。按提交时间倒序（页面上最常见的是「我刚生成的那批」）。 */
  public TitleGenerationPage listGenerations(TitleGenerationQuery query) {
    int page = Math.max(0, query.page());
    int size = Math.min(Math.max(query.size(), 1), MAX_PAGE_SIZE);
    UUID tenantId = currentTenantId();

    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    if (query.status() != null && !query.status().isBlank()) {
      String status = MediaRules.normalizeUpper(query.status());
      CatalogService.check(
          TitleRules.GENERATION_STATUSES.contains(status),
          "批次状态只能是 " + String.join(" / ", TitleRules.GENERATION_STATUSES));
      where.add("g.status=?");
      binds.add(status);
    }
    if (query.keyword() != null && !query.keyword().isBlank()) {
      // 关键词搜批次与它用到的模板：运营记得的是「亚马逊那套模板」，不是批次 id。
      where.add(
          "exists (select 1 from title_template t where t.tenant_id=g.tenant_id and t.id=g.template_id"
              + " and (t.name ilike ? escape '!' or t.code ilike ? escape '!'))");
      String pattern = escapeLike(query.keyword().trim());
      binds.add(pattern);
      binds.add(pattern);
    }
    String sqlWhere = where.isEmpty() ? "" : " and " + String.join(" and ", where);

    long total =
        sql.fetchOne(
                "select count(*) from title_generation g where g.tenant_id=?" + sqlWhere,
                concat(List.of(tenantId), binds).toArray())
            .get(0, Long.class);
    var rows =
        sql.fetch(
            "select g.id,g.template_id,g.status,g.sku_count,g.candidate_count,g.rejected_count,"
                + "g.keyword_filter,g.error,g.created_at,g.finished_at,g.version,"
                + "t.name as template_name,t.code as template_code"
                + " from title_generation g join title_template t"
                + " on t.tenant_id=g.tenant_id and t.id=g.template_id"
                + " where g.tenant_id=?"
                + sqlWhere
                + " order by g.created_at desc,g.id desc limit ? offset ?",
            concat(List.of(tenantId), concat(binds, List.of(size, (long) page * size))).toArray());
    var counts =
        sql.fetchOne(
            "select count(*) filter (where status='SUCCEEDED') as succeeded_count,"
                + "count(*) filter (where status='PARTIAL') as partial_count,"
                + "count(*) filter (where status='FAILED') as failed_count"
                + " from title_generation where tenant_id=?",
            tenantId);
    return new TitleGenerationPage(
        rows.stream().map(this::toGenerationRow).toList(),
        total,
        page,
        size,
        counts.get("succeeded_count", Long.class),
        counts.get("partial_count", Long.class),
        counts.get("failed_count", Long.class),
        "批量生成是同步的：提交后候选已经落库，可以直接逐条选用。"
            + "相似度超限的组合会被拒绝并给出原因，而不是放宽阈值凑数——"
            + "所以「某个 SKU 只产出了 2 条而要了 3 条」通常意味着修饰词池太小。");
  }

  private TitleGenerationRow toGenerationRow(Record r) {
    String status = r.get("status", String.class);
    return new TitleGenerationRow(
        r.get("id", UUID.class),
        r.get("template_id", UUID.class),
        r.get("template_name", String.class),
        r.get("template_code", String.class),
        status,
        TitleRules.generationStatusLabel(status),
        r.get("sku_count", Integer.class),
        r.get("candidate_count", Integer.class),
        r.get("rejected_count", Integer.class),
        r.get("keyword_filter", String.class),
        r.get("error", String.class),
        instant(r.get("created_at", OffsetDateTime.class)),
        instant(r.get("finished_at", OffsetDateTime.class)),
        r.get("version", Long.class));
  }

  /** 批次详情：批次 + 逐 SKU 结果 + 全部候选。 */
  public TitleGenerationDetail generationDetail(UUID generationId) {
    TitleGenerationRow generation = toGenerationRow(generationRow(generationId));
    List<TitleGenerationSkuRow> skus = generationSkus(generationId);
    List<TitleRecord> records = recordsOfGeneration(generationId);
    long selected = records.stream().filter(r -> "SELECTED".equals(r.status())).count();
    return new TitleGenerationDetail(
        generation,
        skus,
        records,
        selected,
        "每条候选都带着它与本 SKU 已有标题的最大相似度（千分比）："
            + "越低说明离重复铺货判定越远。相似度上限写在模板上，由生成算法强制执行，"
            + "超限的组合会被拒绝并在这里给出原因。");
  }

  /** 某个批次的逐 SKU 结果。 */
  public List<TitleGenerationSkuRow> generationSkus(UUID generationId) {
    return sql
        .fetch(
            "select i.id,i.generation_id,i.sku_id,i.outcome,i.candidate_count,i.rejected_count,i.reason,"
                + "s.code as sku_code,s.name as sku_name,p.name as spu_name,p.brand,p.category"
                + " from title_generation_sku i"
                + " join sku s on s.tenant_id=i.tenant_id and s.id=i.sku_id"
                + " join spu p on p.tenant_id=s.tenant_id and p.id=s.spu_id"
                + " where i.generation_id=? order by s.code,i.id",
            generationId)
        .stream()
        .map(
            r -> {
              String outcome = r.get("outcome", String.class);
              return new TitleGenerationSkuRow(
                  r.get("id", UUID.class),
                  r.get("generation_id", UUID.class),
                  r.get("sku_id", UUID.class),
                  r.get("sku_code", String.class),
                  r.get("sku_name", String.class),
                  r.get("spu_name", String.class),
                  r.get("brand", String.class),
                  r.get("category", String.class),
                  outcome,
                  TitleRules.outcomeLabel(outcome),
                  r.get("candidate_count", Integer.class),
                  r.get("rejected_count", Integer.class),
                  r.get("reason", String.class));
            })
        .toList();
  }

  // ============================================================ 候选查询

  /** 候选列表：分页 + SKU / 平台 / 状态 / 关键词筛选。 */
  public TitleRecordPage listRecords(TitleRecordQuery query) {
    int page = Math.max(0, query.page());
    int size = Math.min(Math.max(query.size(), 1), MAX_PAGE_SIZE);
    UUID tenantId = currentTenantId();

    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    if (query.skuId() != null) {
      where.add("r.sku_id=?");
      binds.add(query.skuId());
    }
    if (query.generationId() != null) {
      where.add("r.generation_id=?");
      binds.add(query.generationId());
    }
    if (query.platform() != null && !query.platform().isBlank()) {
      where.add("r.platform=?");
      binds.add(MediaRules.normalize(query.platform()));
    }
    if (query.status() != null && !query.status().isBlank()) {
      String status = MediaRules.normalizeUpper(query.status());
      CatalogService.check(
          TitleRules.RECORD_STATUSES.contains(status),
          "候选状态只能是 " + String.join(" / ", TitleRules.RECORD_STATUSES));
      where.add("r.status=?");
      binds.add(status);
    }
    if (query.keyword() != null && !query.keyword().isBlank()) {
      // 关键词搜标题本身与 SKU/SPU：运营记得的是「蓝牙」或商品编码，不是候选 id。
      where.add(
          "(r.title ilike ? escape '!' or s.code ilike ? escape '!' or s.name ilike ? escape '!'"
              + " or p.name ilike ? escape '!')");
      String pattern = escapeLike(query.keyword().trim());
      for (int i = 0; i < 4; i++) binds.add(pattern);
    }
    String sqlWhere = where.isEmpty() ? "" : " and " + String.join(" and ", where);
    String from =
        " from title_record r"
            + " join sku s on s.tenant_id=r.tenant_id and s.id=r.sku_id"
            + " join spu p on p.tenant_id=s.tenant_id and p.id=s.spu_id"
            + " where r.tenant_id=?";

    long total =
        sql.fetchOne(
                "select count(*)" + from + sqlWhere, concat(List.of(tenantId), binds).toArray())
            .get(0, Long.class);
    var rows =
        sql.fetch(
            "select r.id,r.generation_id,r.template_id,r.sku_id,r.platform,r.variant_no,r.title,"
                + "r.char_length,r.truncated,r.rotation,r.similarity_permille,r.core_keyword,"
                + "r.selling_keyword,r.modifier_keyword,r.status,r.created_at,r.updated_at,r.version,"
                + "s.code as sku_code,s.name as sku_name,p.name as spu_name,p.brand,p.category"
                + from
                + sqlWhere
                + " order by r.created_at desc,r.id desc limit ? offset ?",
            concat(List.of(tenantId), concat(binds, List.of(size, (long) page * size))).toArray());
    var stats =
        sql.fetchOne(
            "select count(*) as candidate_count,"
                + "count(*) filter (where status='SELECTED') as selected_count,"
                + "count(*) filter (where status='REJECTED') as rejected_count,"
                + "count(*) filter (where truncated) as truncated_count"
                + " from title_record where tenant_id=?",
            tenantId);
    return new TitleRecordPage(
        rows.stream().map(this::toRecord).toList(),
        total,
        page,
        size,
        stats.get("candidate_count", Long.class),
        stats.get("selected_count", Long.class),
        stats.get("rejected_count", Long.class),
        stats.get("truncated_count", Long.class),
        TitleRules.DEFAULT_SIMILARITY_PERMILLE,
        "相似度（千分比）是这条候选与本 SKU 已有标题的最大相似度：越低说明离重复铺货判定越远。"
            + "被标「已截断」的候选是超过平台长度上限后按码点边界裁过的，发出去前请确认没砍掉关键信息。");
  }

  private TitleRecord toRecord(Record r) {
    String status = r.get("status", String.class);
    return new TitleRecord(
        r.get("id", UUID.class),
        r.get("generation_id", UUID.class),
        r.get("template_id", UUID.class),
        r.get("sku_id", UUID.class),
        r.get("sku_code", String.class),
        r.get("sku_name", String.class),
        r.get("spu_name", String.class),
        r.get("brand", String.class),
        r.get("category", String.class),
        r.get("platform", String.class),
        r.get("variant_no", Integer.class),
        r.get("title", String.class),
        r.get("char_length", Integer.class),
        Boolean.TRUE.equals(r.get("truncated", Boolean.class)),
        r.get("rotation", Integer.class),
        r.get("similarity_permille", Integer.class),
        r.get("core_keyword", String.class),
        r.get("selling_keyword", String.class),
        r.get("modifier_keyword", String.class),
        status,
        TitleRules.recordStatusLabel(status),
        instant(r.get("created_at", OffsetDateTime.class)),
        instant(r.get("updated_at", OffsetDateTime.class)),
        r.get("version", Long.class));
  }

  /** 一个批次的候选（按 SKU → 平台 → 候选序号，运营对比同一商品各平台标题时最顺的顺序）。 */
  private List<TitleRecord> recordsOfGeneration(UUID generationId) {
    return sql
        .fetch(
            "select r.id,r.generation_id,r.template_id,r.sku_id,r.platform,r.variant_no,r.title,"
                + "r.char_length,r.truncated,r.rotation,r.similarity_permille,r.core_keyword,"
                + "r.selling_keyword,r.modifier_keyword,r.status,r.created_at,r.updated_at,r.version,"
                + "s.code as sku_code,s.name as sku_name,p.name as spu_name,p.brand,p.category"
                + " from title_record r"
                + " join sku s on s.tenant_id=r.tenant_id and s.id=r.sku_id"
                + " join spu p on p.tenant_id=s.tenant_id and p.id=s.spu_id"
                + " where r.generation_id=? order by r.sku_id,r.platform,r.variant_no",
            generationId)
        .stream()
        .map(this::toRecord)
        .toList();
  }

  /**
   * 导出 CSV（供 04 选用或人工核对）。StreamingResponseBody 分批写出， 峰值内存与结果集大小无关。
   *
   * <p><b>每批在流式线程里重新开租户事务</b>：租户上下文是事务级 GUC， controller 那个事务在响应开始前就结束了，而流式写发生在另一个线程上—— 那条连接上没有
   * {@code app.current_tenant}，FORCE RLS 会把所有行藏起来， 于是导出只有一个表头（初版就踩了这个坑：{@code streamCsv} 直接用注入的
   * {@code sql} 查，而 {@code actor.tenantId()} 只进了 WHERE 子句、 设不了 GUC，于是查出来 0 行还不报错）。
   */
  public void streamCsv(
      AccessService.Access actor,
      TitleRecordQuery query,
      java.util.function.Consumer<String> emit) {
    int size = 200;
    int offset = 0;
    emit.accept("SKU编码,SPU名称,品牌,分类,平台,候选序号,标题,字符数,已截断,词序档位,相似度‰," + "核心词,卖点,修饰词,状态\n");
    while (true) {
      // offset 必须在进 lambda 前定格成 final 局部变量：lambda 只能捕获
      // 实际 final 的变量，而 offset 每轮循环都在推进。
      int at = offset;
      List<Record> batch =
          access.run(
              actor,
              "catalog.spu.read",
              () ->
                  sql.fetch(
                      "select s.code,p.name as spu_name,p.brand,p.category,r.platform,r.variant_no,r.title,"
                          + "r.char_length,r.truncated,r.rotation,r.similarity_permille,r.core_keyword,"
                          + "r.selling_keyword,r.modifier_keyword,r.status"
                          + " from title_record r"
                          + " join sku s on s.tenant_id=r.tenant_id and s.id=r.sku_id"
                          + " join spu p on p.tenant_id=s.tenant_id and p.id=s.spu_id"
                          + " where r.tenant_id=?"
                          + (query.skuId() == null ? "" : " and r.sku_id=?")
                          + (query.platform() == null || query.platform().isBlank()
                              ? ""
                              : " and r.platform=?")
                          + (query.status() == null || query.status().isBlank()
                              ? ""
                              : " and r.status=?")
                          + " order by r.sku_id,r.platform,r.variant_no limit ? offset ?",
                      csvBinds(actor.tenantId(), query, size, at)));
      if (batch.isEmpty()) break;
      var sb = new StringBuilder();
      for (Record r : batch) {
        var line = new ArrayList<String>();
        line.add(r.get("code", String.class));
        line.add(r.get("spu_name", String.class));
        line.add(r.get("brand", String.class));
        line.add(r.get("category", String.class));
        line.add(r.get("platform", String.class));
        line.add(String.valueOf(r.get("variant_no", Integer.class)));
        line.add(r.get("title", String.class));
        line.add(String.valueOf(r.get("char_length", Integer.class)));
        line.add(Boolean.TRUE.equals(r.get("truncated", Boolean.class)) ? "是" : "否");
        line.add(String.valueOf(r.get("rotation", Integer.class)));
        line.add(String.valueOf(r.get("similarity_permille", Integer.class)));
        line.add(r.get("core_keyword", String.class));
        line.add(r.get("selling_keyword", String.class));
        line.add(r.get("modifier_keyword", String.class));
        line.add(TitleRules.recordStatusLabel(r.get("status", String.class)));
        sb.append(String.join(",", line.stream().map(TitleService::csv).toList())).append('\n');
      }
      // 每批立刻写出并让出内存，峰值只与单批大小相关。
      emit.accept(sb.toString());
      offset += batch.size();
    }
  }

  private Object[] csvBinds(UUID tenantId, TitleRecordQuery query, int size, int offset) {
    var binds = new ArrayList<Object>();
    binds.add(tenantId);
    if (query.skuId() != null) binds.add(query.skuId());
    if (query.platform() != null && !query.platform().isBlank())
      binds.add(MediaRules.normalize(query.platform()));
    if (query.status() != null && !query.status().isBlank())
      binds.add(MediaRules.normalizeUpper(query.status()));
    binds.add(size);
    binds.add(offset);
    return binds.toArray();
  }

  /** CSV 转义按 RFC 4180；前导 =/+/-/@ 会被 Excel 当成公式执行，加前缀单引号切断。 */
  static String csv(String value) {
    String text = value == null ? "" : value;
    if (!text.isEmpty() && "=+-@".indexOf(text.charAt(0)) >= 0) text = "'" + text;
    return '"' + text.replace("\"", "\"\"") + '"';
  }

  // ============================================================ 选用 / 取消选用

  /**
   * 选用一条标题（供 04 刊登使用）。
   *
   * <p>同一 SKU 同一平台只能选用一条：两个平台上架的是同一个商品， 两条都「已选用」意味着 04 不知道该发哪条。撞这个约束时把该 SKU 同平台的其它
   * 选用项**降级为待选用**并给出说明，而不是默默失败——用户以为自己选中了。
   */
  public TitleRecordSelected select(AccessService.Access actor, UUID id) {
    var row = recordRow(id);
    CatalogService.check(!"SELECTED".equals(row.get("status", String.class)), "这条标题已经被选用了");
    UUID tenantId = currentTenantId();
    sql.execute(
        "update title_record set status='CANDIDATE',version=version+1,updated_at=now()"
            + " where tenant_id=? and sku_id=? and platform=? and status='SELECTED'",
        tenantId,
        row.get("sku_id", UUID.class),
        row.get("platform", String.class));
    sql.execute(
        "update title_record set status='SELECTED',version=version+1,updated_at=now()"
            + " where id=? and status<>'SELECTED'",
        id);
    audit(actor, id, "TitleRecordSelected", row.get("platform", String.class));
    return selected(id);
  }

  /** 取消选用。已选用的标题不可改内容（V20 的 guard_title_record_selected_immutable 钉死）。 */
  public TitleRecordSelected deselect(AccessService.Access actor, UUID id) {
    var row = recordRow(id);
    CatalogService.check("SELECTED".equals(row.get("status", String.class)), "这条标题当前没有被选用，无需取消");
    int updated =
        sql.execute(
            "update title_record set status='CANDIDATE',version=version+1,updated_at=now()"
                + " where id=? and status='SELECTED'",
            id);
    if (updated != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "这条标题已被他人修改，请刷新后重试");
    audit(actor, id, "TitleRecordDeselected", null);
    return selected(id);
  }

  private TitleRecordSelected selected(UUID id) {
    var row = recordRow(id);
    String status = row.get("status", String.class);
    return new TitleRecordSelected(
        id,
        status,
        TitleRules.recordStatusLabel(status),
        row.get("title", String.class),
        row.get("platform", String.class),
        row.get("version", Long.class));
  }

  /** 手动把一条候选标成已淘汰（批量生成里被闸门拒的候选也要能人工淘汰）。 */
  public TitleRecordSelected reject(AccessService.Access actor, UUID id) {
    var row = recordRow(id);
    CatalogService.check(
        !"SELECTED".equals(row.get("status", String.class)), "已选用的标题不能淘汰：04 刊登可能已照它发布。请先取消选用");
    sql.execute(
        "update title_record set status='REJECTED',version=version+1,updated_at=now() where id=?",
        id);
    audit(actor, id, "TitleRecordRejected", null);
    return selected(id);
  }

  // ============================================================ 工具

  private TitleGeneration generation(UUID id) {
    var row = generationRow(id);
    String status = row.get("status", String.class);
    int candidateCount = row.get("candidate_count", Integer.class);
    int rejectedCount = row.get("rejected_count", Integer.class);
    return new TitleGeneration(
        id,
        row.get("template_id", UUID.class),
        row.get("template_name", String.class),
        status,
        TitleRules.generationStatusLabel(status),
        row.get("sku_count", Integer.class),
        candidateCount,
        rejectedCount,
        row.get("error", String.class),
        "FAILED".equals(status)
            ? "这一批一个候选都没产出：看批次详情里逐 SKU 的原因，通常是词根池太小或商品的品牌/分类为空。"
            : "已生成 "
                + candidateCount
                + " 条候选"
                + (rejectedCount > 0 ? "，另有 " + rejectedCount + " 条因重复度超限被拒（原因见批次详情）" : "")
                + "。可以逐条选用，或导出 CSV 交给 04 刊登。");
  }

  private Record generationRow(UUID id) {
    var row =
        sql.fetchOne(
            "select g.id,g.template_id,g.status,g.sku_count,g.candidate_count,g.rejected_count,"
                + "g.keyword_filter,g.error,g.created_at,g.finished_at,g.version,"
                + "t.name as template_name,t.code as template_code"
                + " from title_generation g join title_template t"
                + " on t.tenant_id=g.tenant_id and t.id=g.template_id where g.id=?",
            id);
    if (row == null) throw CatalogService.missing();
    return row;
  }

  private Record templateRow(UUID id) {
    var row =
        sql.fetchOne(
            "select id,code,name,note,pattern,max_similarity_permille,candidate_count,enabled,version,"
                + "created_at,updated_at from title_template where id=?",
            id);
    if (row == null) throw CatalogService.missing();
    return row;
  }

  private Record keywordRow(UUID id) {
    var row =
        sql.fetchOne(
            "select id,code,text,keyword_type,enabled,version,created_at,updated_at from title_keyword"
                + " where id=?",
            id);
    if (row == null) throw CatalogService.missing();
    return row;
  }

  private Record recordRow(UUID id) {
    var row =
        sql.fetchOne(
            "select id,sku_id,platform,title,status,version from title_record where id=?", id);
    if (row == null) throw CatalogService.missing();
    return row;
  }

  /** 变量按模板分组（一次查询取齐，避免逐模板查一次）。 */
  private Map<UUID, List<TitleTemplateVariable>> variablesOf(List<UUID> templateIds) {
    if (templateIds.isEmpty()) return Map.of();
    Map<UUID, List<TitleTemplateVariable>> byTemplate = new LinkedHashMap<>();
    for (Record r :
        sql.fetch(
            "select id,template_id,variable_name,source,slot_no from title_template_variable"
                + " where template_id in ("
                + placeholders(templateIds)
                + ") order by template_id,slot_no,id",
            templateIds.toArray()))
      byTemplate
          .computeIfAbsent(r.get("template_id", UUID.class), k -> new ArrayList<>())
          .add(
              new TitleTemplateVariable(
                  r.get("id", UUID.class),
                  r.get("variable_name", String.class),
                  r.get("source", String.class),
                  TitleRules.sourceLabel(r.get("source", String.class)),
                  r.get("slot_no", Integer.class)));
    return byTemplate;
  }

  /** 平台上限按模板分组（一次查询取齐）。 */
  private Map<UUID, List<TitleTemplatePlatform>> platformsOf(List<UUID> templateIds) {
    if (templateIds.isEmpty()) return Map.of();
    Map<UUID, List<TitleTemplatePlatform>> byTemplate = new LinkedHashMap<>();
    for (Record r :
        sql.fetch(
            "select id,template_id,platform,max_length from title_template_platform"
                + " where template_id in ("
                + placeholders(templateIds)
                + ") order by template_id,platform",
            templateIds.toArray()))
      byTemplate
          .computeIfAbsent(r.get("template_id", UUID.class), k -> new ArrayList<>())
          .add(
              new TitleTemplatePlatform(
                  r.get("id", UUID.class),
                  r.get("platform", String.class),
                  r.get("max_length", Integer.class)));
    return byTemplate;
  }

  /** 生成用：模板变量的 name → spec。 */
  private Map<String, TitleGenerator.VariableSpec> templateVariables(UUID templateId) {
    Map<String, TitleGenerator.VariableSpec> out = new LinkedHashMap<>();
    variablesOf(List.of(templateId))
        .getOrDefault(templateId, List.of())
        .forEach(
            v ->
                out.put(
                    v.name(), new TitleGenerator.VariableSpec(v.name(), v.source(), v.slotNo())));
    return out;
  }

  /** 生成用：本次要用的平台上限。平台没配就直接拒——不悄悄用默认值。 */
  private Map<String, Integer> templatePlatformLimits(UUID templateId, List<String> platforms) {
    Map<String, Integer> limits = new LinkedHashMap<>();
    for (TitleTemplatePlatform p :
        platformsOf(List.of(templateId)).getOrDefault(templateId, List.of()))
      limits.put(p.platform(), p.maxLength());
    for (String platform : platforms) {
      String name = MediaRules.normalize(platform);
      CatalogService.check(
          limits.containsKey(name),
          "模板没有配置平台「" + name + "」的标题长度上限：" + "平台标题有硬字符上限，不配就没法判断该裁到多少字。请先在模板的「平台长度上限」里加上这个平台");
    }
    return limits;
  }

  /** 生成用：SKU 事实快照（含 SPU 的品牌与分类）。 */
  private List<TitleGenerator.SkuFacts> skuFacts(List<UUID> skuIds) {
    List<TitleGenerator.SkuFacts> out = new ArrayList<>();
    for (UUID skuId : skuIds) {
      // p.name 必须显式起别名 as spu_name：不起别名时 s.name 与 p.name 都落在 "name" 列上，
      // 取到的会是**后一列**的值，于是每个 SKU 的商品名都变成了 SPU 名——
      // 渲染出来的标题里「{sku_name}」和「{spu_name}」变成同一句话，差异化少了一个轴。
      var row =
          sql.fetchOne(
              "select s.id,s.code,s.name,s.variant_signature,p.name as spu_name,p.brand,p.category"
                  + " from sku s join spu p on p.tenant_id=s.tenant_id and p.id=s.spu_id"
                  + " where s.tenant_id=? and s.id=?",
              currentTenantId(),
              skuId);
      if (row == null)
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "SKU 不存在或不属于当前租户：" + skuId);
      out.add(
          new TitleGenerator.SkuFacts(
              row.get("id", UUID.class),
              MediaRules.normalize(row.get("code", String.class)),
              MediaRules.normalize(row.get("name", String.class)),
              MediaRules.normalize(row.get("spu_name", String.class)),
              MediaRules.normalize(row.get("brand", String.class)),
              MediaRules.normalize(row.get("category", String.class)),
              MediaRules.normalize(row.get("variant_signature", String.class))));
    }
    return out;
  }

  /** 生成用：该 SKU 已有的全部标题（历史批次 + 其它平台）——重复度比较的基线。 */
  private List<String> existingTitlesOf(UUID skuId) {
    return sql
        .fetch(
            "select title from title_record where tenant_id=? and sku_id=? order by created_at,id",
            currentTenantId(),
            skuId)
        .stream()
        .map(r -> r.get(0, String.class))
        .toList();
  }

  /**
   * 生成用：三类词池。
   *
   * <p>{@code keywordCodes} 是「词根」这个批量参数：非空时**只**取这些编码的核心词， 空时取全部启用的核心词。卖点与修饰词不受它限制——限定的是主体词，
   * 而差异化素材永远来自完整的卖点/修饰词池。
   *
   * <p>顺序按 code 排，所以「下标」在同一次生成里是确定的：同一份输入两次生成 得到同样的候选，这是「重复度受控」可被断言的前提。
   */
  private TitleGenerator.KeywordPools keywordPools(List<String> keywordCodes) {
    List<String> core =
        poolOf("CORE", keywordCodes == null || keywordCodes.isEmpty() ? null : keywordCodes);
    return new TitleGenerator.KeywordPools(core, poolOf("SELLING", null), poolOf("MODIFIER", null));
  }

  private List<String> poolOf(String keywordType, List<String> codes) {
    if (codes == null || codes.isEmpty())
      return sql
          .fetch(
              "select text from title_keyword where tenant_id=? and keyword_type=? and enabled order by code",
              currentTenantId(),
              keywordType)
          .stream()
          .map(r -> r.get(0, String.class))
          .toList();
    // 绑定参数混着 UUID（tenant）与 String（code），泛型 concat 推不出来，
    // 所以这里显式列出来而不是硬凑一个 List<Object>。
    var binds = new ArrayList<Object>();
    binds.add(currentTenantId());
    binds.add(keywordType);
    binds.addAll(codes);
    return sql
        .fetch(
            "select text from title_keyword where tenant_id=? and keyword_type=? and enabled"
                + " and code in ("
                + placeholders(codes)
                + ") order by code",
            binds.toArray())
        .stream()
        .map(r -> r.get(0, String.class))
        .toList();
  }

  private long templateGenerationCount(UUID templateId) {
    return sql.fetchOne("select count(*) from title_generation where template_id=?", templateId)
        .get(0, Long.class);
  }

  private TitleTemplateSaved templateSaved(UUID id) {
    var row = templateRow(id);
    return new TitleTemplateSaved(
        id,
        row.get("code", String.class),
        row.get("version", Long.class),
        variablesOf(List.of(id)).getOrDefault(id, List.of()).size(),
        platformsOf(List.of(id)).getOrDefault(id, List.of()).size(),
        templateGenerationCount(id));
  }

  private UUID currentTenantId() {
    return sql.fetchOne("select nullif(current_setting('app.current_tenant',true),'')::uuid")
        .get(0, UUID.class);
  }

  /** 占位符串。与 {@code ImageProcessService} 同一形状：参数个数由调用方保证，SQL 里不会拼进用户输入。 */
  static String placeholders(List<?> ids) {
    return String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
  }

  static <T> List<T> concat(List<T> first, List<T> second) {
    var all = new ArrayList<T>(first.size() + second.size());
    all.addAll(first);
    all.addAll(second);
    return all;
  }

  /** 取第一个非空原因：多个校验同时不合法时，先报最贴近用户输入的那一条。 */
  static String firstNonNull(String... values) {
    for (String value : values) if (value != null) return value;
    return null;
  }

  private void audit(AccessService.Access actor, UUID resourceId, String action, String detail) {
    sql.execute(
        "insert into audit_event(id,tenant_id,actor_id,resource_id,action) values(?,?,?,?,?)",
        Ids.next(),
        actor.tenantId(),
        actor.userId(),
        resourceId,
        action);
  }

  static String escapeLike(String value) {
    return "%" + value.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
  }

  private static String instant(OffsetDateTime value) {
    return value == null ? null : value.toInstant().toString();
  }

  private static ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }

  /** 取出数据库异常里最有信息量的原因（V20 的 CHECK 有人话，没有约束名可用）。 */
  private static String rootMessage(DataAccessException e) {
    var root = e.getRootCause();
    String message = root == null ? e.getMessage() : root.getMessage();
    return message == null ? e.getClass().getSimpleName() : message.replace('\n', ' ');
  }

  private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(TitleService.class);
}
