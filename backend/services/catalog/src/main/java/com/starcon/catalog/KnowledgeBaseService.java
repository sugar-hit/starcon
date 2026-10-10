package com.starcon.catalog;

import static com.starcon.catalog.KnowledgeBaseModels.*;

import com.starcon.tenancy.Ids;
import java.math.BigDecimal;
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
 * 11.4.2.2 客服知识库与话术：知识条目（常见问题标准答法 / 平台政策说明 / 退换货话术）、
 * 多语言版本、<b>平台规则版本挂钩</b>、真实引用记录与命中率统计。
 *
 * <p>蓝图 11.4.2.2：「职责：维护客服知识库与回复模板：常见问题标准答法、平台政策说明、
 * 退换货话术、多语言版本；记录使用效果。」「输入：模板与知识条目；多语言版本；使用统计。」
 * 「输出：话术库 + 客服一键引用；命中率统计。」「上游 = 3.4.2.2 文案资产；
 * 下游 = 15 客服消息发送。<b>话术需与平台规则版本挂钩</b>，平台政策变化时能定位
 * 哪些话术需要更新。」
 *
 * <p>本类的纪律：
 *
 * <ol>
 *   <li><b>引用记录只追加不改不删。</b>命中率的全部可信度来自明细行是唯一真相
 *       （V100 里有 append-only 触发器 + REVOKE 双重兜底）。服务层同样不提供改删端点。
 *   <li><b>待复核任务在规则版本被接替时自动生成</b>（V100 的
 *       {@code spawn_kb_review_tasks_on_supersede} 触发器）。服务层只在
 *       {@link #updateRuleVersion} 里做一次状态改写，任务生成不依赖服务层记得调用 ——
 *       「平台政策变化时能定位哪些话术需要更新」这条硬要求不能因为漏调一次就失效。
 *   <li><b>命中率只在一处算</b>（{@link KnowledgeBaseRules#hitRate}，规则 R2~R4）。
 *       概览、单条详情、排行榜三处都调它：两处各算一次的症状是「概览说 40%、详情说 33%」。
 *   <li><b>编码建后不可改。</b>客服与运营用 code 互相口头引用（与
 *       {@code TitleService.saveTemplate} / {@code MediaService.saveTag} 同一纪律）。
 * </ol>
 *
 * <p>权限口径：读 {@code catalog.assets.read}，写 {@code catalog.assets.update}。
 * 话术是「素材与翻译」域（identity V3 的 {@code catalog.assets} 词表）里的客服侧文案资产 ——
 * 与 3.4.2.2 的商品文案同域但不同宿主（那边挂 SKU 给消费者看，这边挂条目给客服引用）。
 * 不新造词表：新词表要改 identity 服务的迁移，属于另一个服务的写入所有权。
 *
 * <p>租户一律来自已认证成员关系经 RLS 生效的连接上下文，<b>不读 {@code X-Tenant-Id} 头</b>。
 */
@Service
public class KnowledgeBaseService {
  /** 单页最大行数。话术库是租户级共享资产，行数量级在百位，与 DescriptionService 同口径。 */
  private static final int MAX_PAGE_SIZE = 200;

  private final DSLContext sql;

  public KnowledgeBaseService(DSLContext sql) {
    this.sql = sql;
  }

  // ================================================================ 词表

  public KnowledgeBaseOptions options() {
    return new KnowledgeBaseOptions(
        KnowledgeBaseRules.PLATFORMS,
        KnowledgeBaseRules.PLATFORMS.stream()
            .map(p -> new KnowledgeBaseRules.Option(p, platformLabel(p)))
            .toList(),
        KnowledgeBaseRules.ENTRY_TYPES,
        KnowledgeBaseRules.entryTypeOptions(),
        KnowledgeBaseRules.USAGE_OUTCOMES,
        KnowledgeBaseRules.outcomeOptions(),
        KnowledgeBaseRules.REVIEW_STATUSES,
        KnowledgeBaseRules.reviewStatusOptions(),
        KnowledgeBaseRules.TRANSLATION_ORIGINS,
        KnowledgeBaseRules.TRANSLATION_ORIGINS.stream()
            .map(o -> new KnowledgeBaseRules.Option(o, originLabel(o)))
            .toList(),
        "命中率口径 = 有引用记录的启用话术条数 ÷ 启用话术总条数（规则 R2~R4）："
            + "「被驳回」与「放弃引用」不计入命中 —— 前者是最该被发现的一种，后者是检索噪声的信号。"
            + "待复核任务在平台规则版本被标记为「已被接替」时自动生成，并记下是哪一版接替的它。");
  }

  // ================================================================ 概览

  /**
   * 知识库概览：存量 + 命中率 + 待办量。
   *
   * <p>{@code reviewPending} 与 {@code usageRejected} 是这个库每天唯一需要盯的两个数：
   * 前者是「政策改版了、有多少条话术还挂着没复核」，后者是「照话术答复被买家驳回了多少次」。
   * 命中率本身波动慢，盯它没有行动价值。
   */
  public KnowledgeBaseOverview overview() {
    UUID tenantId = currentTenantId();
    long entryTotal = scalar("select count(*) from kb_entry where tenant_id=?", tenantId);
    long entryEnabled =
        scalar("select count(*) from kb_entry where tenant_id=? and enabled", tenantId);
    long entryUntranslated =
        scalar(
            "select count(*) from kb_entry e where e.tenant_id=? and e.enabled"
                + " and not exists (select 1 from kb_entry_translation t"
                + " where t.tenant_id=e.tenant_id and t.entry_id=e.id and t.status='REVIEWED')",
            tenantId);
    long translationTotal =
        scalar("select count(*) from kb_entry_translation where tenant_id=?", tenantId);
    long translationDraft =
        scalar("select count(*) from kb_entry_translation where tenant_id=? and status='DRAFT'", tenantId);
    long ruleVersionTotal = scalar("select count(*) from kb_rule_version where tenant_id=?", tenantId);
    long ruleVersionActive =
        scalar("select count(*) from kb_rule_version where tenant_id=? and status='ACTIVE'", tenantId);
    long bindingTotal = scalar("select count(*) from kb_entry_rule where tenant_id=?", tenantId);
    long reviewPending =
        scalar("select count(*) from kb_review_task where tenant_id=? and review_status='PENDING'", tenantId);
    long reviewReviewing =
        scalar(
            "select count(*) from kb_review_task where tenant_id=? and review_status='REVIEWING'",
            tenantId);
    long reviewDone =
        scalar("select count(*) from kb_review_task where tenant_id=? and review_status='DONE'", tenantId);
    long usageTotal = scalar("select count(*) from kb_usage where tenant_id=?", tenantId);
    long usageHit = hitUsageCount(tenantId);
    long usageRejected =
        scalar("select count(*) from kb_usage where tenant_id=? and outcome='REJECTED'", tenantId);
    long usageAbandoned =
        scalar("select count(*) from kb_usage where tenant_id=? and outcome='ABANDONED'", tenantId);
    long hitEntryCount = hitEntryCount(tenantId);
    BigDecimal hitRate = KnowledgeBaseRules.hitRate(entryEnabled, hitEntryCount);
    return new KnowledgeBaseOverview(
        entryTotal,
        entryEnabled,
        entryTotal - entryEnabled,
        entryUntranslated,
        translationTotal,
        translationDraft,
        ruleVersionTotal,
        ruleVersionActive,
        bindingTotal,
        reviewPending,
        reviewReviewing,
        reviewDone,
        usageTotal,
        usageHit,
        usageRejected,
        usageAbandoned,
        hitEntryCount,
        hitRate,
        "命中率的分母只算启用话术：停用的话术没被引用是预期的，不该拉低命中率；"
            + "分母为 0 时这里返回 null 而不是 0 —— 「一条话术都没有」与「话术一条都没被用过」"
            + "是两件不同的事。");
  }

  // ================================================================ 话术条目

  /** 话术列表：分页 + 关键词（编码 / 标题 / 正文）+ 类型 + 启用状态 + 待复核筛选。 */
  public KnowledgeEntryPage listEntries(
      String keyword, String entryType, String enabled, String reviewState, int page, int size) {
    int limit = pageSize(size);
    int offset = Math.max(page, 0) * limit;
    String type = blankToNull(KnowledgeBaseRules.normalizeUpper(entryType));
    Boolean on = enabled == null || enabled.isBlank() ? null : Boolean.valueOf(enabled.trim());
    String needle = blankToNull(keyword);

    var where = new StringBuilder(" where e.tenant_id=?");
    List<Object> args = new ArrayList<>();
    args.add(currentTenantId());
    if (type != null) {
      where.append(" and e.entry_type=?");
      args.add(type);
    }
    if (on != null) {
      where.append(" and e.enabled=?");
      args.add(on);
    }
    if (needle != null) {
      where.append(" and (e.code like ? escape '!' or e.title like ? escape '!' or e.body like ? escape '!')");
      String like = escapeLike(needle);
      args.add(like);
      args.add(like);
      args.add(like);
    }
    if ("PENDING".equals(reviewState)) {
      where.append(
          " and exists (select 1 from kb_review_task rt where rt.tenant_id=e.tenant_id"
              + " and rt.entry_id=e.id and rt.review_status<>'DONE')");
    } else if ("NONE".equals(reviewState)) {
      where.append(
          " and not exists (select 1 from kb_review_task rt where rt.tenant_id=e.tenant_id"
              + " and rt.entry_id=e.id and rt.review_status<>'DONE')");
    }
    long total = scalar("select count(*) from kb_entry e" + where, args.toArray());
    var rows =
        sql.fetch(
            "select e.id,e.code,e.title,e.entry_type,e.body,e.source_locale,e.trigger_keywords,"
                + "e.enabled,e.version,e.created_at,e.updated_at,"
                + "(select count(*) from kb_entry_translation t where t.tenant_id=e.tenant_id and t.entry_id=e.id)"
                + " as translation_count,"
                + "(select count(*) from kb_usage u where u.tenant_id=e.tenant_id and u.entry_id=e.id)"
                + " as usage_count,"
                + "(select count(*) from kb_review_task rt where rt.tenant_id=e.tenant_id"
                + " and rt.entry_id=e.id and rt.review_status<>'DONE') as review_pending_count"
                + " from kb_entry e"
                + where
                + " order by e.code limit ? offset ?",
            argsWith(args, limit, offset));
    List<KnowledgeEntryRow> items = new ArrayList<>(rows.size());
    for (Record r : rows) {
      String type2 = r.get("entry_type", String.class);
      items.add(
          new KnowledgeEntryRow(
              r.get("id", UUID.class),
              r.get("code", String.class),
              r.get("title", String.class),
              type2,
              KnowledgeBaseRules.entryTypeLabel(type2),
              r.get("body", String.class),
              r.get("source_locale", String.class),
              splitKeywords(r.get("trigger_keywords", String.class)),
              Boolean.TRUE.equals(r.get("enabled", Boolean.class)),
              r.get("translation_count", Long.class),
              r.get("usage_count", Long.class),
              r.get("review_pending_count", Long.class),
              r.get("version", Long.class),
              instant(r.get("created_at", OffsetDateTime.class)),
              instant(r.get("updated_at", OffsetDateTime.class))));
    }
    return new KnowledgeEntryPage(items, Math.max(page, 0), limit, total, totalPages(total, limit));
  }

  /** 单条话术详情（含译文、挂钩的规则版本、命中率、最近引用）。 */
  public KnowledgeEntryDetail detail(UUID id) {
    Record row = entryRow(id);
    UUID entryId = row.get("id", UUID.class);
    return new KnowledgeEntryDetail(
        entryId,
        row.get("code", String.class),
        row.get("title", String.class),
        row.get("entry_type", String.class),
        KnowledgeBaseRules.entryTypeLabel(row.get("entry_type", String.class)),
        row.get("body", String.class),
        row.get("source_locale", String.class),
        splitKeywords(row.get("trigger_keywords", String.class)),
        Boolean.TRUE.equals(row.get("enabled", Boolean.class)),
        row.get("version", Long.class),
        instant(row.get("created_at", OffsetDateTime.class)),
        instant(row.get("updated_at", OffsetDateTime.class)),
        translations(entryId),
        bindingsOf(entryId),
        recentUsages(entryId, 20),
        effectOf(entryId));
  }

  /**
   * 建话术或改话术（id 为空即新建）。
   *
   * <p>编码建后不可改：客服与运营用 code 互相口头引用，改了会让历史话术单上的引用对不上。
   */
  public KnowledgeSaved saveEntry(
      AccessService.Access actor, UUID id, KnowledgeEntryInput input) {
    String body = KnowledgeBaseRules.normalizeBody(input.body());
    String problem =
        firstNonNull(
            KnowledgeBaseRules.codeProblem(input.code()),
            KnowledgeBaseRules.titleProblem(input.title()),
            KnowledgeBaseRules.entryTypeProblem(input.entryType()),
            KnowledgeBaseRules.bodyProblem(body),
            KnowledgeBaseRules.localeProblem(input.sourceLocale()),
            KnowledgeBaseRules.keywordsProblem(input.triggerKeywords()));
    CatalogService.check(problem == null, problem);
    String code = KnowledgeBaseRules.normalizeUpper(input.code());
    String type = KnowledgeBaseRules.normalizeUpper(input.entryType());
    String locale = KnowledgeBaseRules.normalizeLocale(input.sourceLocale());
    String keywords = KnowledgeBaseRules.normalizeKeywords(input.triggerKeywords());
    boolean enabled = !Boolean.FALSE.equals(input.enabled());
    UUID tenantId = currentTenantId();

    if (id != null) {
      Record existing = entryRow(id);
      long version = existing.get("version", Long.class);
      CatalogService.check(
          version == input.version(),
          "这条话术已被他人改动（当前版本 " + version + "，你提交的是 " + input.version()
              + "）：请刷新后重新编辑，避免覆盖掉别人的修改");
      int updated =
          sql.execute(
              "update kb_entry set title=?,entry_type=?,body=?,source_locale=?,trigger_keywords=?,"
                  + "enabled=?,version=version+1,updated_at=now()"
                  + " where tenant_id=? and id=? and version=?",
              KnowledgeBaseRules.normalize(input.title()),
              type,
              body,
              locale,
              keywords,
              enabled,
              tenantId,
              id,
              input.version());
      if (updated != 1)
        throw new ResponseStatusException(
            HttpStatus.CONFLICT, "话术已被他人修改，请刷新后重试");
      audit(actor, id, "KB_ENTRY_UPDATE", code);
      return new KnowledgeSaved(id, version + 1);
    }
    id = Ids.next();
    try {
      sql.execute(
          "insert into kb_entry(id,tenant_id,code,title,entry_type,body,source_locale,"
              + "trigger_keywords,enabled,version) values(?,?,?,?,?,?,?,?,?,0)",
          id,
          tenantId,
          code,
          KnowledgeBaseRules.normalize(input.title()),
          type,
          body,
          locale,
          keywords,
          enabled);
    } catch (DataAccessException e) {
      throw conflictOnDuplicate(rootMessage(e), "话术编码「" + code + "」已被占用，请换一个");
    }
    audit(actor, id, "KB_ENTRY_CREATE", code);
    return new KnowledgeSaved(id, 0L);
  }

  /** 启用 / 停用话术。停用后不能再被新引用，但历史引用记录与译文照常可读。 */
  public KnowledgeSaved setEntryEnabled(
      AccessService.Access actor, UUID id, boolean enabled) {
    Record row = entryRow(id);
    long version = row.get("version", Long.class);
    int updated =
        sql.execute(
            "update kb_entry set enabled=?,version=version+1,updated_at=now()"
                + " where tenant_id=? and id=?",
            enabled,
            currentTenantId(),
            id);
    if (updated != 1) throw CatalogService.missing();
    audit(actor, id, enabled ? "KB_ENTRY_ENABLE" : "KB_ENTRY_DISABLE", row.get("code", String.class));
    return new KnowledgeSaved(id, version + 1);
  }

  /** 删话术。仍被待复核任务或引用记录引用时，DB 的 RESTRICT 会拒绝并说明原因。 */
  public void deleteEntry(AccessService.Access actor, UUID id, long version) {
    Record row = entryRow(id);
    CatalogService.check(
        row.get("version", Long.class) == version,
        "这条话术已被他人改动（当前版本 " + row.get("version", Long.class) + "）：请刷新后重试");
    try {
      sql.execute("delete from kb_entry where tenant_id=? and id=? and version=?", currentTenantId(), id, version);
    } catch (DataAccessException e) {
      throw conflictOnDuplicate(
          rootMessage(e),
          "这条话术已被引用或挂有待复核任务，不能删除："
              + "保留它才能回答「当时依据哪版政策、答复被用过几次」。请改用停用。");
    }
    audit(actor, id, "KB_ENTRY_DELETE", row.get("code", String.class));
  }

  // ================================================================ 多语言版本

  public List<EntryTranslation> translations(UUID entryId) {
    entryRow(entryId);
    var rows =
        sql.fetch(
            "select id,entry_id,locale,title,body,origin,status,version,created_at,updated_at"
                + " from kb_entry_translation where tenant_id=? and entry_id=? order by locale",
            currentTenantId(),
            entryId);
    List<EntryTranslation> out = new ArrayList<>(rows.size());
    for (Record r : rows) out.add(translationOf(r));
    return out;
  }

  /**
   * 译文完整度：这些语言里哪些能直接发给买家。
   *
   * <p>口径与 3.4.2.2 同一形状：只有 REVIEWED 的译文算 {@code usable}，DRAFT 不算 ——
   * AI 预填的译文里占位符被改写或漏掉是最典型的失败模式。
   */
  public EntryTranslationCoverage translationCoverage(UUID entryId, List<String> locales) {
    Record row = entryRow(entryId);
    String sourceLocale = row.get("source_locale", String.class);
    List<String> wanted =
        locales == null || locales.isEmpty()
            ? sql.fetch(
                    "select distinct locale from kb_entry_translation"
                        + " where tenant_id=? and entry_id=? order by locale",
                    currentTenantId(),
                    entryId)
                .stream()
                .map(r -> r.get(0, String.class))
                .toList()
            : locales;
    List<LocaleCoverage> out = new ArrayList<>(wanted.size());
    long ready = 0;
    for (String raw : wanted) {
      String locale = KnowledgeBaseRules.normalizeLocale(raw);
      if (locale.equals(sourceLocale)) {
        out.add(new LocaleCoverage(locale, true, true, true));
        ready++;
        continue;
      }
      Record t =
          sql.fetchOne(
              "select status from kb_entry_translation where tenant_id=? and entry_id=? and locale=?",
              currentTenantId(),
              entryId,
              locale);
      boolean translated = t != null;
      boolean reviewed = translated && "REVIEWED".equals(t.get(0, String.class));
      boolean usable = reviewed;
      if (usable) ready++;
      out.add(new LocaleCoverage(locale, translated, reviewed, usable));
    }
    return new EntryTranslationCoverage(sourceLocale, out, ready, wanted.size());
  }

  /**
   * 建译文或改译文（translationId 为空即新建，按条目 + 语言定位）。
   *
   * <p>占位符集合必须与源语言一致（规则 R1）—— 服务层给出可读原因，V100 的触发器逐条兜底。
   */
  public EntryTranslation saveTranslation(
      AccessService.Access actor, UUID entryId, UUID translationId, EntryTranslationInput input) {
    Record entry = entryRow(entryId);
    String problem =
        firstNonNull(
            KnowledgeBaseRules.localeProblem(input.locale()),
            KnowledgeBaseRules.titleProblem(input.title()),
            KnowledgeBaseRules.bodyProblem(input.body()));
    CatalogService.check(problem == null, problem);
    String locale = KnowledgeBaseRules.normalizeLocale(input.locale());
    String sourceLocale = entry.get("source_locale", String.class);
    CatalogService.check(
        !locale.equals(sourceLocale),
        "语言「" + locale + "」就是这条话术的源语言，不需要译文：源语言正文直接用 kb_entry.body");
    String body = KnowledgeBaseRules.normalizeBody(input.body());
    String placeholderProblem =
        KnowledgeBaseRules.placeholderProblem(entry.get("body", String.class), body);
    CatalogService.check(placeholderProblem == null, placeholderProblem);
    String origin = defaultIfBlank(KnowledgeBaseRules.normalizeUpper(input.origin()), "MANUAL");
    String status = defaultIfBlank(KnowledgeBaseRules.normalizeUpper(input.status()), "DRAFT");
    UUID tenantId = currentTenantId();

    if (translationId != null) {
      Record existing = translationRow(translationId);
      long version = existing.get("version", Long.class);
      CatalogService.check(
          version == input.version(),
          "这条译文已被他人改动（当前版本 " + version + "）：请刷新后重试");
      int updated =
          sql.execute(
              "update kb_entry_translation set title=?,body=?,origin=?,status=?,version=version+1,"
                  + "updated_at=now() where tenant_id=? and id=? and version=?",
              KnowledgeBaseRules.normalize(input.title()),
              body,
              origin,
              status,
              tenantId,
              translationId,
              input.version());
      if (updated != 1)
        throw new ResponseStatusException(HttpStatus.CONFLICT, "译文已被他人修改，请刷新后重试");
      audit(actor, entryId, "KB_TRANSLATION_UPDATE", locale);
      return translationRow(translationId).into(EntryTranslation.class);
    }
    try {
      UUID id = Ids.next();
      sql.execute(
          "insert into kb_entry_translation(id,tenant_id,entry_id,locale,title,body,origin,status,version)"
              + " values(?,?,?,?,?,?,?,?,0)",
          id,
          tenantId,
          entryId,
          locale,
          KnowledgeBaseRules.normalize(input.title()),
          body,
          origin,
          status);
      audit(actor, entryId, "KB_TRANSLATION_CREATE", locale);
      return translationRow(id).into(EntryTranslation.class);
    } catch (DataAccessException e) {
      throw conflictOnDuplicate(
          rootMessage(e), "语言「" + locale + "」的译文已存在，请直接改那条而不是再建一条");
    }
  }

  /** 删译文。仍被引用记录引用时 DB 的 RESTRICT 会拒绝——历史答复的溯源不能断。 */
  public void deleteTranslation(AccessService.Access actor, UUID translationId, long version) {
    Record row = translationRow(translationId);
    CatalogService.check(
        row.get("version", Long.class) == version,
        "这条译文已被他人改动（当前版本 " + row.get("version", Long.class) + "）：请刷新后重试");
    try {
      sql.execute(
          "delete from kb_entry_translation where tenant_id=? and id=? and version=?",
          currentTenantId(),
          translationId,
          version);
    } catch (DataAccessException e) {
      throw conflictOnDuplicate(
          rootMessage(e),
          "这条译文已被引用记录引用，不能删除：删掉之后「那次答复用的是哪个语言版本」就答不上来了。"
              + "请把译文改成新内容，而不是删掉。");
    }
    audit(actor, row.get("entry_id", UUID.class), "KB_TRANSLATION_DELETE", row.get("locale", String.class));
  }

  // ================================================================ 平台规则版本

  /** 规则版本列表：分页 + 关键词（政策编码 / 版本 / 标题）+ 平台 + 状态。 */
  public RuleVersionPage listRuleVersions(
      String keyword, String platform, String status, int page, int size) {
    int limit = pageSize(size);
    int offset = Math.max(page, 0) * limit;
    String plat = blankToNull(KnowledgeBaseRules.normalizeUpper(platform));
    String st = blankToNull(KnowledgeBaseRules.normalizeUpper(status));
    String needle = blankToNull(keyword);

    var where = new StringBuilder(" where r.tenant_id=?");
    List<Object> args = new ArrayList<>();
    args.add(currentTenantId());
    if (plat != null) {
      where.append(" and r.platform=?");
      args.add(plat);
    }
    if (st != null) {
      where.append(" and r.status=?");
      args.add(st);
    }
    if (needle != null) {
      where.append(" and (r.policy_key like ? escape '!' or r.version_label like ? escape '!' or r.title like ? escape '!')");
      String like = escapeLike(needle);
      args.add(like);
      args.add(like);
      args.add(like);
    }
    long total = scalar("select count(*) from kb_rule_version r" + where, args.toArray());
    var rows =
        sql.fetch(
            "select r.id,r.platform,r.policy_key,r.version_label,r.title,r.change_summary,r.status,"
                + "r.effective_from,r.effective_to,r.version,r.created_at,r.updated_at,"
                + "(select count(*) from kb_entry_rule b where b.tenant_id=r.tenant_id"
                + " and b.rule_version_id=r.id) as binding_count"
                + " from kb_rule_version r"
                + where
                + " order by r.policy_key, r.effective_from desc, r.id desc limit ? offset ?",
            argsWith(args, limit, offset));
    List<RuleVersionRow> items = new ArrayList<>(rows.size());
    for (Record r : rows) items.add(ruleVersionOf(r));
    return new RuleVersionPage(items, Math.max(page, 0), limit, total, totalPages(total, limit));
  }

  /**
   * 建规则版本（默认草稿）。
   *
   * <p>草稿不参与挂钩判定：还没生效的政策版本不该让话术被标成「依据了这一版」。
   * 生效与接替走 {@link #updateRuleVersion}。
   */
  public KnowledgeSaved createRuleVersion(
      AccessService.Access actor, RuleVersionInput input) {
    String problem =
        firstNonNull(
            KnowledgeBaseRules.platformProblem(input.platform()),
            KnowledgeBaseRules.policyKeyProblem(input.policyKey()),
            KnowledgeBaseRules.versionLabelProblem(input.versionLabel()),
            KnowledgeBaseRules.titleProblem(input.title()),
            KnowledgeBaseRules.intervalProblem(input.effectiveFrom(), input.effectiveTo()));
    CatalogService.check(problem == null, problem);
    UUID id = Ids.next();
    try {
      sql.execute(
          "insert into kb_rule_version(id,tenant_id,platform,policy_key,version_label,title,"
              + "change_summary,status,effective_from,effective_to,version)"
              + " values(?,?,?,?,?,?,?,'DRAFT',?::timestamptz,?::timestamptz,0)",
          id,
          currentTenantId(),
          KnowledgeBaseRules.normalizeUpper(input.platform()),
          KnowledgeBaseRules.normalizeUpper(input.policyKey()),
          KnowledgeBaseRules.normalize(input.versionLabel()),
          KnowledgeBaseRules.normalize(input.title()),
          KnowledgeBaseRules.normalize(input.changeSummary()),
          input.effectiveFrom(),
          input.effectiveTo());
    } catch (DataAccessException e) {
      throw conflictOnDuplicate(
          rootMessage(e),
          "政策「"
              + KnowledgeBaseRules.normalizeUpper(input.policyKey())
              + "」的版本「"
              + KnowledgeBaseRules.normalize(input.versionLabel())
              + "」已存在");
    }
    audit(actor, id, "KB_RULE_VERSION_CREATE", KnowledgeBaseRules.normalizeUpper(input.policyKey()));
    return new KnowledgeSaved(id, 0L);
  }

  /**
   * 改规则版本（含发布生效与标记接替）。
   *
   * <p><b>标记为 SUPERSEDED 时 V100 的触发器会自动给挂在它上面的话术生成待复核任务</b> ——
   * 这就是蓝图「平台政策变化时能定位哪些话术需要更新」的落地点。
   * 服务层<b>不</b>自己插任务行：靠服务层记得调用的话，某次改库或漏调就让这条硬要求静默失效。
   *
   * <p>同一政策同时只能有一版 ACTIVE，由 {@code guard_kb_rule_single_active} 强制。
   */
  public KnowledgeSaved updateRuleVersion(
      AccessService.Access actor, UUID id, RuleVersionInput input, String status) {
    Record row = ruleVersionRow(id);
    String next = blankToNull(KnowledgeBaseRules.normalizeUpper(status));
    if (next != null)
      CatalogService.check(
          List.of("DRAFT", "ACTIVE", "SUPERSEDED").contains(next),
          "状态只能是 DRAFT / ACTIVE / SUPERSEDED");
    boolean superseding = "SUPERSEDED".equals(next);
    String problem =
        firstNonNull(
            KnowledgeBaseRules.platformProblem(input.platform()),
            KnowledgeBaseRules.policyKeyProblem(input.policyKey()),
            KnowledgeBaseRules.versionLabelProblem(input.versionLabel()),
            KnowledgeBaseRules.titleProblem(input.title()),
            KnowledgeBaseRules.intervalProblem(input.effectiveFrom(), input.effectiveTo()),
            KnowledgeBaseRules.changeSummaryProblem(input.changeSummary(), superseding));
    CatalogService.check(problem == null, problem);
    UUID tenantId = currentTenantId();
    String effectiveStatus = next == null ? row.get("status", String.class) : next;
    try {
      int updated =
          sql.execute(
              "update kb_rule_version set platform=?,policy_key=?,version_label=?,title=?,"
                  + "change_summary=?,status=?,effective_from=?::timestamptz,effective_to=?::timestamptz,"
                  + "version=version+1,updated_at=now()"
                  + " where tenant_id=? and id=? and version=?",
              KnowledgeBaseRules.normalizeUpper(input.platform()),
              KnowledgeBaseRules.normalizeUpper(input.policyKey()),
              KnowledgeBaseRules.normalize(input.versionLabel()),
              KnowledgeBaseRules.normalize(input.title()),
              KnowledgeBaseRules.normalize(input.changeSummary()),
              effectiveStatus,
              input.effectiveFrom(),
              input.effectiveTo(),
              tenantId,
              id,
              row.get("version", Long.class));
      if (updated != 1)
        throw new ResponseStatusException(HttpStatus.CONFLICT, "规则版本已被他人修改，请刷新后重试");
    } catch (DataAccessException e) {
      throw conflictOnDuplicate(
          rootMessage(e),
          "该政策已有一版处于生效中，不能同时激活第二版：一个政策同时只能有一版生效，"
              + "否则「这条话术依据的是哪版政策」无法回答。请先把原生效版标记为已被接替。");
    }
    audit(
        actor,
        id,
        "KB_RULE_VERSION_" + effectiveStatus,
        KnowledgeBaseRules.normalizeUpper(input.policyKey()));
    return new KnowledgeSaved(id, row.get("version", Long.class) + 1);
  }

  public void deleteRuleVersion(AccessService.Access actor, UUID id) {
    Record row = ruleVersionRow(id);
    try {
      sql.execute("delete from kb_rule_version where tenant_id=? and id=?", currentTenantId(), id);
    } catch (DataAccessException e) {
      throw conflictOnDuplicate(
          rootMessage(e),
          "这条规则版本已被话术或待复核任务引用，不能删除：它是政策存档，删掉之后"
              + "「这条话术当时依据哪版政策」就再也答不上来了。请改用标记为「已被接替」。");
    }
    audit(actor, id, "KB_RULE_VERSION_DELETE", row.get("policy_key", String.class));
  }

  // ================================================================ 挂钩

  /** 某条话术挂钩的平台规则版本列表。
   *
   * <p><b>先确认话术存在再读挂钩</b>（{@code entryRow}）：跨租户时返回 404 而不是空列表 ——
   * 「读不到这条话术」与「这条话术没有挂任何规则」是两件不同的事，退化成空列表的话
   * 页面会显示「还没有挂钩」，而真相是这条话术根本不属于当前租户。
   * 与 {@code SupplierQuoteIntegrationTest} 里 3.6.2.1 的同一纪律一致。
   */
  public List<EntryRuleBinding> bindingsOf(UUID entryId) {
    entryRow(entryId);
    var rows =
        sql.fetch(
            "select b.id,b.entry_id,b.rule_version_id,b.note,b.created_at,"
                + "r.platform,r.policy_key,r.version_label,r.title as rule_title,r.status as rule_status,"
                + "r.effective_from"
                + " from kb_entry_rule b"
                + " join kb_rule_version r on r.tenant_id=b.tenant_id and r.id=b.rule_version_id"
                + " where b.tenant_id=? and b.entry_id=? order by r.policy_key, r.effective_from desc",
            currentTenantId(),
            entryId);
    List<EntryRuleBinding> out = new ArrayList<>(rows.size());
    for (Record r : rows) out.add(bindingOf(r));
    return out;
  }

  /**
   * 把话术挂到某版平台规则上。
   *
   * <p>刻意允许挂多条规则：一条退换货话术通常同时依据退货政策与 A-to-Z 政策。
   * 限死一版会逼运营去抄几乎相同的话术，而副本正是「哪条话术要更新」答不对的根源。
   */
  public EntryRuleBinding bindEntry(
      AccessService.Access actor, UUID entryId, RuleBindingInput input) {
    Record entry = entryRow(entryId);
    ruleVersionRow(input.ruleVersionId());
    try {
      UUID id = Ids.next();
      sql.execute(
          "insert into kb_entry_rule(id,tenant_id,entry_id,rule_version_id,note)"
              + " values(?,?,?,?,?)",
          id,
          currentTenantId(),
          entryId,
          input.ruleVersionId(),
          KnowledgeBaseRules.normalize(input.note()));
      audit(actor, entryId, "KB_RULE_BIND", entry.get("code", String.class));
      return bindingsOf(entryId).stream()
          .filter(b -> b.id().equals(id))
          .findFirst()
          .orElseThrow(CatalogService::missing);
    } catch (DataAccessException e) {
      throw conflictOnDuplicate(
          rootMessage(e), "这条话术已经挂在该规则版本上了，不需要重复挂");
    }
  }

  /** 解除挂钩。话术与规则版本都留着，只是这条关联不再成立。 */
  public void unbindEntry(AccessService.Access actor, UUID entryId, UUID bindingId) {
    entryRow(entryId);
    int deleted =
        sql.execute(
            "delete from kb_entry_rule where tenant_id=? and entry_id=? and id=?",
            currentTenantId(),
            entryId,
            bindingId);
    CatalogService.check(deleted == 1, "这条挂钩记录不存在或已经解除了");
    audit(actor, entryId, "KB_RULE_UNBIND", "");
  }

  // ================================================================ 待复核任务

  /**
   * 待复核任务列表：分页 + 状态 + 平台 + 关键词。
   *
   * <p>蓝图「平台政策变化时能定位哪些话术需要更新」的读出口。
   */
  public ReviewTaskPage listReviewTasks(
      String keyword, String reviewStatus, String platform, int page, int size) {
    int limit = pageSize(size);
    int offset = Math.max(page, 0) * limit;
    String status = blankToNull(KnowledgeBaseRules.normalizeUpper(reviewStatus));
    String plat = blankToNull(KnowledgeBaseRules.normalizeUpper(platform));
    String needle = blankToNull(keyword);

    var where = new StringBuilder(
        " where t.tenant_id=?");
    List<Object> args = new ArrayList<>();
    args.add(currentTenantId());
    if (status != null) {
      where.append(" and t.review_status=?");
      args.add(status);
    }
    if (plat != null) {
      where.append(" and r.platform=?");
      args.add(plat);
    }
    if (needle != null) {
      where.append(" and (e.code like ? escape '!' or e.title like ? escape '!' or r.policy_key like ? escape '!')");
      String like = escapeLike(needle);
      args.add(like);
      args.add(like);
      args.add(like);
    }
    long total =
        scalar(
            "select count(*) from kb_review_task t"
                + " join kb_entry e on e.tenant_id=t.tenant_id and e.id=t.entry_id"
                + " join kb_rule_version r on r.tenant_id=t.tenant_id and r.id=t.rule_version_id"
                + where,
            args.toArray());
    var rows =
        sql.fetch(
            "select t.id,t.entry_id,t.rule_version_id,t.superseding_rule_id,t.review_status,t.note,"
                + "t.assigned_to,t.assigned_at,t.reviewed_by,t.reviewed_at,t.created_at,"
                + "e.code as entry_code,e.title as entry_title,"
                + "r.platform,r.policy_key,r.version_label,r.change_summary as rule_change_summary,"
                + "s.version_label as superseding_label,s.change_summary as superseding_summary,"
                + "t.assigned_to,t.reviewed_by"
                + " from kb_review_task t"
                + " join kb_entry e on e.tenant_id=t.tenant_id and e.id=t.entry_id"
                + " join kb_rule_version r on r.tenant_id=t.tenant_id and r.id=t.rule_version_id"
                + " join kb_rule_version s on s.tenant_id=t.tenant_id and s.id=t.superseding_rule_id"
                + where
                + " order by t.review_status='PENDING' desc, t.created_at desc, t.id desc"
                + " limit ? offset ?",
            argsWith(args, limit, offset));
    List<ReviewTaskRow> items = new ArrayList<>(rows.size());
    for (Record r : rows) items.add(reviewTaskOf(r));
    return new ReviewTaskPage(items, Math.max(page, 0), limit, total, totalPages(total, limit));
  }

  /**
   * 认领 / 复核一条待复核任务。
   *
   * <p>DB 层有两条 CHECK 兜底：DONE 必须带复核人与复核时刻，PENDING/REVIEWING 必须带认领人 ——
   * 否则「已复核」这个状态在库里无法追溯是谁做的。
   */
  public ReviewTaskRow updateReviewTask(
      AccessService.Access actor, UUID taskId, ReviewTaskUpdateInput input) {
    ReviewTaskRow existing = reviewTaskRow(taskId);
    String status = KnowledgeBaseRules.normalizeUpper(input.reviewStatus());
    CatalogService.check(
        KnowledgeBaseRules.REVIEW_STATUSES.contains(status),
        "复核状态只能是 PENDING / REVIEWING / DONE");
    String note = input.note() == null ? existing.note() : KnowledgeBaseRules.normalize(input.note());
    UUID tenantId = currentTenantId();
    UUID userId = actor.userId();
    if ("DONE".equals(status)) {
      sql.execute(
          "update kb_review_task set review_status='DONE',note=?,reviewed_by=?,reviewed_at=now(),"
              + "assigned_to=coalesce(assigned_to,?),assigned_at=coalesce(assigned_at,now()),"
              + "updated_at=now() where tenant_id=? and id=?",
          note,
          userId,
          userId,
          tenantId,
          taskId);
    } else {
      sql.execute(
          "update kb_review_task set review_status=?,note=?,assigned_to=?,assigned_at=now(),"
              + "updated_at=now() where tenant_id=? and id=?",
          status,
          note,
          userId,
          tenantId,
          taskId);
    }
    audit(actor, existing.entryId(), "KB_REVIEW_" + status, existing.policyKey());
    return reviewTaskRow(taskId);
  }

  // ================================================================ 引用记录

  /**
   * 记一次引用（客服一键引用话术）。
   *
   * <p>蓝图输出「话术库 + 客服一键引用；命中率统计」与职责「记录使用效果」的落地点：
   * 每次引用真的插一行 {@code kb_usage}，命中率统计从这些行现算（规则 R2~R4）。
   *
   * <p>{@code entryTranslationId} 给了就必须属于这条话术 —— 否则「引用的是哪一版译文」
   * 会指向别的条目，页面上的语言标签会说谎。
   */
  public KbUsageRow recordUsage(AccessService.Access actor, UUID entryId, UsageInput input) {
    Record entry = entryRow(entryId);
    String outcome = KnowledgeBaseRules.normalizeUpper(input.outcome());
    CatalogService.check(
        KnowledgeBaseRules.USAGE_OUTCOMES.contains(outcome),
        "引用结果只能是 " + String.join(" / ", KnowledgeBaseRules.USAGE_OUTCOMES));
    UUID translationId = input.entryTranslationId();
    if (translationId != null) {
      Record t = translationRow(translationId);
      CatalogService.check(
          t.get("entry_id", UUID.class).equals(entryId),
          "这条译文不属于当前话术：引用记录必须指向同一条目下的译文，否则「引用的是哪一版语言」会说谎");
    }
    UUID id = Ids.next();
    sql.execute(
        "insert into kb_usage(id,tenant_id,entry_id,entry_translation_id,agent_id,"
            + "conversation_ref,outcome,note) values(?,?,?,?,?,?,?,?)",
        id,
        currentTenantId(),
        entryId,
        translationId,
        actor.userId(),
        KnowledgeBaseRules.normalize(input.conversationRef()),
        outcome,
        KnowledgeBaseRules.normalize(input.note()));
    // 引用不会覆盖话术本体的 version（那是并发编辑用的），但要让「最近更新」反映被用过。
    sql.execute("update kb_entry set updated_at=now() where tenant_id=? and id=?", currentTenantId(), entryId);
    return usageRow(id);
  }

  /** 引用记录列表：分页 + 话术 + 结果类型 + 关键词（会话引用 / 备注）。 */
  public KbUsagePage listUsages(String keyword, String outcome, UUID entryId, int page, int size) {
    int limit = pageSize(size);
    int offset = Math.max(page, 0) * limit;
    String out = blankToNull(KnowledgeBaseRules.normalizeUpper(outcome));
    String needle = blankToNull(keyword);

    var where = new StringBuilder(" where u.tenant_id=?");
    List<Object> args = new ArrayList<>();
    args.add(currentTenantId());
    if (out != null) {
      where.append(" and u.outcome=?");
      args.add(out);
    }
    if (entryId != null) {
      where.append(" and u.entry_id=?");
      args.add(entryId);
    }
    if (needle != null) {
      where.append(" and (u.conversation_ref like ? escape '!' or u.note like ? escape '!' or e.code like ? escape '!')");
      String like = escapeLike(needle);
      args.add(like);
      args.add(like);
      args.add(like);
    }
    long total =
        scalar(
            "select count(*) from kb_usage u join kb_entry e on e.tenant_id=u.tenant_id and e.id=u.entry_id"
                + where,
            args.toArray());
    var rows =
        sql.fetch(
            "select u.id,u.entry_id,u.entry_translation_id,u.agent_id,u.conversation_ref,u.outcome,"
                + "u.note,u.created_at,e.code as entry_code,e.title as entry_title,"
                + "t.locale as locale"
                + " from kb_usage u"
                + " join kb_entry e on e.tenant_id=u.tenant_id and e.id=u.entry_id"
                + " left join kb_entry_translation t on t.tenant_id=u.tenant_id"
                + " and t.id=u.entry_translation_id"
                + where
                + " order by u.created_at desc, u.id desc limit ? offset ?",
            argsWith(args, limit, offset));
    List<KbUsageRow> items = new ArrayList<>(rows.size());
    for (Record r : rows) items.add(usageOf(r));
    return new KbUsagePage(items, Math.max(page, 0), limit, total, totalPages(total, limit));
  }

  /** 单条话术的引用记录（详情页的「使用历史」）。跨租户时 404 而不是空列表（同 {@link #bindingsOf} 的理由）。 */
  public List<KbUsageRow> usagesOf(UUID entryId, int page, int size) {
    entryRow(entryId);
    int limit = pageSize(size);
    var rows =
        sql.fetch(
            "select u.id,u.entry_id,u.entry_translation_id,u.agent_id,u.conversation_ref,u.outcome,"
                + "u.note,u.created_at,e.code as entry_code,e.title as entry_title,"
                + "t.locale as locale"
                + " from kb_usage u"
                + " join kb_entry e on e.tenant_id=u.tenant_id and e.id=u.entry_id"
                + " left join kb_entry_translation t on t.tenant_id=u.tenant_id"
                + " and t.id=u.entry_translation_id"
                + " where u.tenant_id=? and u.entry_id=?"
                + " order by u.created_at desc, u.id desc limit ?",
            currentTenantId(),
            entryId,
            limit);
    List<KbUsageRow> items = new ArrayList<>(rows.size());
    for (Record r : rows) items.add(usageOf(r));
    return items;
  }

  // ================================================================ 命中率统计

  /**
   * 命中率排行榜 —— 蓝图输出「命中率统计」的可查面。
   *
   * <p>排序默认按「被引用次数」倒序：先看哪些话术真的在用，再看命中率。
   * 按命中率排会把「只被用过 1 次且那 1 次成功」的话术顶到最上面，
   * 而那不是运营要的行动顺序。
   *
   * <p>每行自带 {@code hitRate}（该条话术的命中引用比例，规则 R3 过滤 outcome）与
   * {@code usageCount}，页面排序不必在前端重算。
   */
  public List<EntryHitRateRow> hitRates(String sort, int limit) {
    int cap = Math.min(Math.max(limit <= 0 ? 50 : limit, 1), 500);
    String order =
        switch (sort == null ? "" : sort) {
          case "HIT_RATE" -> "hit_rate desc nulls last, usage_count desc, e.code";
          case "LEAST_USED" -> "usage_count asc, e.code";
          case "REJECTED" -> "rejected_count desc, e.code";
          default -> "usage_count desc, e.code";
        };
    var rows =
        sql.fetch(
            "select e.id,e.code,e.title,e.entry_type,e.enabled,"
                + "count(u.id) as usage_count,"
                + "count(u.id) filter (where u.outcome in ('RESOLVED','PARTIAL')) as hit_usage_count,"
                + "count(u.id) filter (where u.outcome='REJECTED') as rejected_count,"
                + "max(u.created_at) as last_used_at,"
                + "(select count(*) from kb_review_task rt where rt.tenant_id=e.tenant_id"
                + " and rt.entry_id=e.id and rt.review_status<>'DONE') as review_pending_count"
                + " from kb_entry e left join kb_usage u on u.tenant_id=e.tenant_id and u.entry_id=e.id"
                + " where e.tenant_id=? group by e.id,e.code,e.title,e.entry_type,e.enabled"
                + " order by " + order + " limit ?",
            currentTenantId(),
            cap);
    List<EntryHitRateRow> out = new ArrayList<>(rows.size());
    for (Record r : rows) {
      long usageCount = r.get("usage_count", Long.class);
      long hitUsage = r.get("hit_usage_count", Long.class);
      String type = r.get("entry_type", String.class);
      out.add(
          new EntryHitRateRow(
              r.get("id", UUID.class),
              r.get("code", String.class),
              r.get("title", String.class),
              type,
              KnowledgeBaseRules.entryTypeLabel(type),
              Boolean.TRUE.equals(r.get("enabled", Boolean.class)),
              usageCount,
              hitUsage,
              r.get("rejected_count", Long.class),
              usageCount == 0
                  ? null
                  : KnowledgeBaseRules.hitRate(usageCount, hitUsage),
              r.get("review_pending_count", Long.class),
              instant(r.get("last_used_at", OffsetDateTime.class))));
    }
    return out;
  }

  // ================================================================ 行读取

  private Record entryRow(UUID id) {
    Record row =
        sql.fetchOne(
            "select id,code,title,entry_type,body,source_locale,trigger_keywords,enabled,version,"
                + "created_at,updated_at from kb_entry where tenant_id=? and id=?",
            currentTenantId(),
            id);
    if (row == null) throw CatalogService.missing();
    return row;
  }

  private Record translationRow(UUID id) {
    Record row =
        sql.fetchOne(
            "select id,entry_id,locale,title,body,origin,status,version,created_at,updated_at"
                + " from kb_entry_translation where tenant_id=? and id=?",
            currentTenantId(),
            id);
    if (row == null) throw CatalogService.missing();
    return row;
  }

  private Record ruleVersionRow(UUID id) {
    Record row =
        sql.fetchOne(
            "select id,platform,policy_key,version_label,title,change_summary,status,"
                + "effective_from,effective_to,version,created_at,updated_at"
                + " from kb_rule_version where tenant_id=? and id=?",
            currentTenantId(),
            id);
    if (row == null) throw CatalogService.missing();
    return row;
  }

  /** 读一条复核任务（按 id 寻址）。跨租户时 404 而非返回空。 */
  public ReviewTaskRow reviewTaskRowPublic(UUID id) {
    return reviewTaskRow(id);
  }

  private ReviewTaskRow reviewTaskRow(UUID id) {
    Record row =
        sql.fetchOne(
            "select t.id,t.entry_id,t.rule_version_id,t.superseding_rule_id,t.review_status,t.note,"
                + "t.assigned_to,t.assigned_at,t.reviewed_by,t.reviewed_at,t.created_at,"
                + "e.code as entry_code,e.title as entry_title,"
                + "r.platform,r.policy_key,r.version_label,r.change_summary as rule_change_summary,"
                + "s.version_label as superseding_label,s.change_summary as superseding_summary,"
                + "t.assigned_to,t.reviewed_by"
                + " from kb_review_task t"
                + " join kb_entry e on e.tenant_id=t.tenant_id and e.id=t.entry_id"
                + " join kb_rule_version r on r.tenant_id=t.tenant_id and r.id=t.rule_version_id"
                + " join kb_rule_version s on s.tenant_id=t.tenant_id and s.id=t.superseding_rule_id"
                + " where t.tenant_id=? and t.id=?",
            currentTenantId(),
            id);
    if (row == null) throw CatalogService.missing();
    return reviewTaskOf(row);
  }

  /** 读一条引用记录（按 id 寻址）。跨租户时 404 而非返回空 —— 不泄露存在性。 */
  public KbUsageRow usageRowPublic(UUID id) {
    return usageRow(id);
  }

  private KbUsageRow usageRow(UUID id) {
    Record row =
        sql.fetchOne(
            "select u.id,u.entry_id,u.entry_translation_id,u.agent_id,u.conversation_ref,u.outcome,"
                + "u.note,u.created_at,e.code as entry_code,e.title as entry_title,"
                + "t.locale as locale"
                + " from kb_usage u"
                + " join kb_entry e on e.tenant_id=u.tenant_id and e.id=u.entry_id"
                + " left join kb_entry_translation t on t.tenant_id=u.tenant_id"
                + " and t.id=u.entry_translation_id"
                + " where u.tenant_id=? and u.id=?",
            currentTenantId(),
            id);
    if (row == null) throw CatalogService.missing();
    return usageOf(row);
  }

  // ================================================================ 统计子查询

  private long hitUsageCount(UUID tenantId) {
    return scalar(
        "select count(*) from kb_usage where tenant_id=? and outcome in ('RESOLVED','PARTIAL')",
        tenantId);
  }

  /** R2 的分子：至少有一次「命中型」引用（R3 口径）的<b>启用</b>话术条数。 */
  private long hitEntryCount(UUID tenantId) {
    return scalar(
        "select count(distinct e.id) from kb_entry e"
            + " join kb_usage u on u.tenant_id=e.tenant_id and u.entry_id=e.id"
            + " where e.tenant_id=? and e.enabled and u.outcome in ('RESOLVED','PARTIAL')",
        tenantId);
  }

  /** 单条话术的使用效果（规则 R2~R4 同源，页面上的数字与概览同口径）。 */
  private EntryEffect effectOf(UUID entryId) {
    UUID tenantId = currentTenantId();
    Record r =
        sql.fetchOne(
            "select count(*) as total,"
                + "count(*) filter (where outcome='RESOLVED') as resolved,"
                + "count(*) filter (where outcome='PARTIAL') as partial,"
                + "count(*) filter (where outcome='UNRESOLVED') as unresolved,"
                + "count(*) filter (where outcome='ABANDONED') as abandoned,"
                + "count(*) filter (where outcome='REJECTED') as rejected,"
                + "max(created_at) as last_used"
                + " from kb_usage where tenant_id=? and entry_id=?",
            tenantId,
            entryId);
    long total = r.get("total", Long.class);
    long resolved = r.get("resolved", Long.class);
    long partial = r.get("partial", Long.class);
    return new EntryEffect(
        total,
        resolved,
        partial,
        r.get("unresolved", Long.class),
        r.get("abandoned", Long.class),
        r.get("rejected", Long.class),
        resolved + partial,
        total == 0 ? null : KnowledgeBaseRules.hitRate(total, resolved + partial),
        instant(r.get("last_used", OffsetDateTime.class)));
  }

  private List<KbUsageRow> recentUsages(UUID entryId, int limit) {
    return usagesOf(entryId, 0, limit);
  }

  // ================================================================ 行映射

  private EntryTranslation translationOf(Record r) {
    return new EntryTranslation(
        r.get("id", UUID.class),
        r.get("entry_id", UUID.class),
        r.get("locale", String.class),
        r.get("title", String.class),
        r.get("body", String.class),
        r.get("origin", String.class),
        r.get("status", String.class),
        r.get("version", Long.class),
        instant(r.get("created_at", OffsetDateTime.class)),
        instant(r.get("updated_at", OffsetDateTime.class)));
  }

  private RuleVersionRow ruleVersionOf(Record r) {
    return new RuleVersionRow(
        r.get("id", UUID.class),
        r.get("platform", String.class),
        r.get("policy_key", String.class),
        r.get("version_label", String.class),
        r.get("title", String.class),
        r.get("change_summary", String.class),
        r.get("status", String.class),
        instant(r.get("effective_from", OffsetDateTime.class)),
        instant(r.get("effective_to", OffsetDateTime.class)),
        r.get("binding_count", Long.class),
        r.get("version", Long.class),
        instant(r.get("created_at", OffsetDateTime.class)),
        instant(r.get("updated_at", OffsetDateTime.class)));
  }

  private EntryRuleBinding bindingOf(Record r) {
    return new EntryRuleBinding(
        r.get("id", UUID.class),
        r.get("entry_id", UUID.class),
        r.get("rule_version_id", UUID.class),
        r.get("platform", String.class),
        r.get("policy_key", String.class),
        r.get("version_label", String.class),
        r.get("rule_title", String.class),
        r.get("rule_status", String.class),
        instant(r.get("effective_from", OffsetDateTime.class)),
        r.get("note", String.class),
        instant(r.get("created_at", OffsetDateTime.class)));
  }

  private ReviewTaskRow reviewTaskOf(Record r) {
    return new ReviewTaskRow(
        r.get("id", UUID.class),
        r.get("entry_id", UUID.class),
        r.get("entry_code", String.class),
        r.get("entry_title", String.class),
        r.get("rule_version_id", UUID.class),
        r.get("platform", String.class),
        r.get("policy_key", String.class),
        r.get("version_label", String.class),
        r.get("rule_change_summary", String.class),
        r.get("superseding_rule_id", UUID.class),
        r.get("superseding_label", String.class),
        r.get("superseding_summary", String.class),
        r.get("review_status", String.class),
        r.get("note", String.class),
        r.get("assigned_to", UUID.class) == null
            ? null
            : r.get("assigned_to", UUID.class).toString(),
        instant(r.get("assigned_at", OffsetDateTime.class)),
        r.get("reviewed_by", UUID.class) == null
            ? null
            : r.get("reviewed_by", UUID.class).toString(),
        instant(r.get("reviewed_at", OffsetDateTime.class)),
        instant(r.get("created_at", OffsetDateTime.class)));
  }

  private KbUsageRow usageOf(Record r) {
    String outcome = r.get("outcome", String.class);
    return new KbUsageRow(
        r.get("id", UUID.class),
        r.get("entry_id", UUID.class),
        r.get("entry_code", String.class),
        r.get("entry_title", String.class),
        r.get("entry_translation_id", UUID.class),
        r.get("locale", String.class),
        r.get("agent_id", UUID.class) == null
            ? null
            : r.get("agent_id", UUID.class).toString(),
        r.get("conversation_ref", String.class),
        outcome,
        KnowledgeBaseRules.outcomeLabel(outcome),
        KnowledgeBaseRules.countsAsHit(outcome),
        r.get("note", String.class),
        instant(r.get("created_at", OffsetDateTime.class)));
  }

  // ================================================================ 工具

  UUID currentTenantId() {
    return sql.fetchOne("select nullif(current_setting('app.current_tenant',true),'')::uuid")
        .get(0, UUID.class);
  }

  private long scalar(String query, Object... args) {
    return sql.fetchOne(query, args).get(0, Long.class);
  }

  private Object[] argsWith(List<Object> args, Object... extra) {
    Object[] all = new Object[args.size() + extra.length];
    for (int i = 0; i < args.size(); i++) all[i] = args.get(i);
    for (int i = 0; i < extra.length; i++) all[args.size() + i] = extra[i];
    return all;
  }

  private int pageSize(int size) {
    return Math.min(Math.max(size <= 0 ? 20 : size, 1), MAX_PAGE_SIZE);
  }

  private static int totalPages(long total, int limit) {
    return (int) ((total + limit - 1) / limit);
  }

  private static List<String> splitKeywords(String raw) {
    if (raw == null || raw.isBlank()) return List.of();
    List<String> out = new ArrayList<>();
    for (String part : raw.split(",")) {
      String word = part.trim();
      if (!word.isEmpty()) out.add(word);
    }
    return out;
  }

  private static String platformLabel(String value) {
    return switch (value) {
      case "AMAZON" -> "亚马逊";
      case "SHOPIFY" -> "Shopify";
      case "EBAY" -> "eBay";
      case "TIKTOK" -> "TikTok Shop";
      case "ALI1688" -> "速卖通 / 1688";
      default -> value;
    };
  }

  private static String originLabel(String value) {
    return switch (value) {
      case "MANUAL" -> "人工撰写";
      case "AI" -> "AI 翻译预填";
      case "IMPORTED" -> "导入";
      default -> value;
    };
  }

  private void audit(AccessService.Access actor, UUID resourceId, String action, String detail) {
    sql.execute(
        "insert into audit_event(id,tenant_id,actor_id,resource_id,action) values(?,?,?,?,?)",
        Ids.next(), actor.tenantId(), actor.userId(), resourceId, action);
  }

  static String instant(OffsetDateTime value) {
    return value == null ? null : value.toInstant().toString();
  }

  static String firstNonNull(String... values) {
    for (String value : values) if (value != null) return value;
    return null;
  }

  static String escapeLike(String value) {
    return "%" + value.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
  }

  static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  static String defaultIfBlank(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }

  /**
   * 把唯一约束冲突翻译成人话；<b>其它数据库错误原样抛出</b>。
   *
   * <p>刻意区分这两类：把「触发器拒绝」「外键缺失」这类错误也报成「已存在」的话，
   * 页面会显示一句与真实原因完全无关的话，排查时会一直找错方向。
   */
  private static ResponseStatusException conflictOnDuplicate(String rootMessage, String fallback) {
    if (rootMessage != null && rootMessage.contains("duplicate key value violates unique constraint")) {
      return new ResponseStatusException(HttpStatus.CONFLICT, rootMessage);
    }
    return new ResponseStatusException(HttpStatus.CONFLICT, fallback);
  }

  private static String rootMessage(DataAccessException e) {
    var root = e.getRootCause();
    String message = root == null ? e.getMessage() : root.getMessage();
    return message == null ? e.getClass().getSimpleName() : message.replace('\n', ' ');
  }
}
