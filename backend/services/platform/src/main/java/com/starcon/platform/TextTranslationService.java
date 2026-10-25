package com.starcon.platform;

import static com.starcon.platform.TextQualityPipeline.*;
import static com.starcon.platform.TextTranslationModels.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 18.4.2.1「文本与翻译」的服务层。
 *
 * <p>数据访问一律走 <b>jOOQ 显式 SQL</b>（AGENTS.md：不用 JPA 懒加载掩盖关键逻辑）。
 * 租户上下文由 {@link AccessService#run} 绑定 —— 本类<b>不</b>读任何请求头里的租户。
 *
 * <p><b>四条纪律：</b>
 *
 * <ol>
 *   <li><b>AI 未接入就一个字都不写</b>：{@link #generate} 拿到「未接入」直接翻成 409，
 *       <b>不</b>落草稿、<b>不</b>落任务状态、<b>不</b>落任何「已翻译」的痕迹。
 *       理由：一条返回成功结果的假通道，会让「文案已经翻译好了」在数据上看起来发生过，
 *       而现场一个字都没翻译出来。
 *   <li><b>确定性检查真实生效</b>：术语保护、占位符完整性、违禁词命中、长度/字符集
 *       都是真规则，<b>FAIL 会拦住发布</b> —— 不是只写一行提示。
 *   <li><b>人工确认是硬门槛</b>：未确认的译文<b>取不走</b>（{@link #publishedReplies}
 *       只返回 PUBLISHED 的行），发布端点还会校验「先确认、后发布」。
 *   <li><b>写入归属会话租户</b>：所有 INSERT 的 {@code tenant_id} 取自已认证会话，
 *       不接受请求参数传入；RLS 的 WITH CHECK 再兜一层。
 * </ol>
 */
@Service
public class TextTranslationService {

  /** 列表分页上限（真分页的上限，防止一次拉爆）。 */
  static final int MAX_PAGE_SIZE = 100;

  private final DSLContext sql;
  private final AiTextGenerationPort ai;
  private final CatalogTranslationPort catalog;
  private final ChannelListingPort channel;
  private final ObjectMapper json;

  public TextTranslationService(
      DSLContext sql,
      AiTextGenerationPort ai,
      CatalogTranslationPort catalog,
      ChannelListingPort channel,
      ObjectMapper json) {
    this.sql = sql;
    this.ai = ai;
    this.catalog = catalog;
    this.channel = channel;
    this.json = json;
  }

  // ================================================================ 工具

  private static void check(boolean condition, String message) {
    if (!condition) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  /** 状态冲突（409）：不是「参数不对」，是「当前状态不允许」。 */
  private static void conflict(String message) {
    throw new ResponseStatusException(HttpStatus.CONFLICT, message);
  }

  /**
   * 状态冲突的判定式写法。
   *
   * <p>🔴 <b>409 与 400 的分界</b>：参数本身合法、但**当前状态**不允许这个动作 ⇒ 409；
   * 参数缺失 / 越界 / 格式不对 ⇒ 400。发布闸（未确认就发）、已发布后再改、
   * 已驳回后再确认 —— 这三条都是前者，用 400 报会把「状态不允许」说成「你填错了」，
   * 运营会去改一份根本没问题的内容。
   */
  private static void conflictIf(boolean violated, String message) {
    if (violated) conflict(message);
  }

  private static String required(String value, String label, int max) {
    var trimmed = value == null ? "" : value.trim();
    check(!trimmed.isEmpty(), label + "不能为空");
    check(trimmed.length() <= max, label + "长度不能超过 " + max + " 个字符");
    return trimmed;
  }

  /** 可选字段：空串合法，超长不合法。 */
  private static String optional(String value, String label, int max) {
    var trimmed = value == null ? "" : value.trim();
    check(trimmed.length() <= max, label + "长度不能超过 " + max + " 个字符");
    return trimmed;
  }

  /** 可选的长文本（保留内部换行，只去掉首尾空白）。 */
  private static String optionalText(String value, String label, int max) {
    var trimmed = value == null ? "" : value.strip();
    check(trimmed.length() <= max, label + "长度不能超过 " + max + " 个字符");
    return trimmed;
  }

  private UUID currentTenant() {
    // 事务局部 GUC：租户由 AccessService 绑定，这里读的是**同一个**绑定。
    var raw =
        sql.fetchOne("select current_setting('app.current_tenant', true) as t").get(0, String.class);
    check(raw != null && !raw.isBlank(), "缺少可信租户上下文");
    return UUID.fromString(raw);
  }

  private long count(String sqlText) {
    return sql.fetchOne(sqlText).get(0, Long.class);
  }

  // ================================================================ 能力声明

  /**
   * 18.4.2.1 的能力声明：逐维说清「有没有、依据是什么、缺了会怎样」。
   *
   * <p>🔴 **AI 自动翻译那一维如实答「没有」** —— 本仓没有任何真实的 AI / LLM 供应商适配器。
   * 能力声明把这一格写清楚，「缺在谁身上」才是一条**可查的事实**，而不是一句注释。
   */
  public TextCapabilities capabilities() {
    var capabilities =
        List.of(
            new TextCapability(
                "18.4.2.1",
                "术语表与品牌词 / 专业术语保护",
                true,
                "platform_text_glossary 表 + 术语表端点；译文里品牌词缺失判 FAIL 并拦住发布",
                "",
                ""),
            new TextCapability(
                "18.4.2.1",
                "占位符完整性校验（封闭变量词表）",
                true,
                "确定性检查 PLACEHOLDER：源文本的 {{变量}} 一个不能少、一个不能多，"
                    + "且变量名必须在封闭词表内（复用 crm MessageSendService 的 11 个变量）",
                "",
                ""),
            new TextCapability(
                "18.4.2.1",
                "违禁词命中检测与规避建议",
                true,
                "确定性检查 BANNED_TERM：租户词表（platform_text_banned_term）+ 内置合规红线，"
                    + "逐条命中并给可执行的规避建议",
                "",
                ""),
            new TextCapability(
                "18.4.2.1",
                "长度与字符集校验",
                true,
                "platform_text_rule 表（内容类型 × 目标语言）+ 确定性检查 LENGTH / CHARSET；"
                    + "未配置规则时该维显式记 SKIPPED，不参与置信度",
                "",
                ""),
            new TextCapability(
                "18.4.2.1",
                "置信度提示（**可解释**）",
                true,
                "四个维度加权：术语覆盖率 / 占位符完整率 / 校验通过率 / 长度合规率；"
                    + "不适用维度按剩余维度重新归一，逐维给出人话说明",
                "",
                ""),
            new TextCapability(
                "18.4.2.1",
                "人工编辑与**人工确认后发布**",
                true,
                "译文草稿按版本追加（platform_text_draft）；发布端点强制校验「先 CONFIRMED 后 PUBLISHED」，"
                    + "且不允许带 FAIL 的检查结论发布",
                "",
                ""),
            new TextCapability(
                "18.4.2.1",
                "客服回复草稿模板与变量绑定",
                true,
                "platform_text_template 表 + 变量绑定端点；变量词表是封闭的，越界模板存不进来",
                "",
                ""),
            new TextCapability(
                "18.4.2.1",
                "跨服务源文本（3.4.3 多语言 / 4.2.1.1 编辑器）",
                true,
                "CatalogTranslationPort（catalog 8082）与 ChannelListingPort（channel 8083）只读端口；"
                    + "只转发调用方自己的 bearer，取不到一律 503",
                "",
                ""),
            new TextCapability(
                "18.4.2.1",
                "**AI 自动翻译 / 标题生成 / 卖点提炼**",
                false,
                "本仓目前没有任何真实的 AI / LLM 供应商适配器"
                    + "（backend / services / tools 下不存在 openai / anthropic / qwen / deepseek 之类的客户端）",
                "18.4",
                "生成端点如实答 409 且**一个字都不写**；"
                    + "在此之前可走「人工录入译文 + 确定性检查 + 人工确认发布」这条完整路径"));
    var gaps =
        List.of(
            new TextGap(
                "18.4.2.2",
                "选品与需求预测",
                "platform",
                false,
                "同卷兄弟",
                "本车道**不依赖**它，也**没有**顺手实现它 —— 预测是另一套数据与口径"),
            new TextGap(
                "18.4.2.3",
                "异常诊断",
                "platform",
                false,
                "同卷兄弟",
                "诊断需要关联库存 / 利润 / 履约等跨域数据，本模块不碰"),
            new TextGap(
                "18.4.3.1",
                "使用与配额",
                "platform",
                false,
                "下游",
                "AI 用量与配额的统计归 18.4.3.1；本模块的 AI 通道未接入，当前没有可计量的调用"),
            new TextGap(
                "18.4.3.2",
                "效果评估",
                "platform",
                false,
                "下游",
                "翻译采纳率是 18.4.3.2 的口径；本模块已留出「人工确认 / 驳回」的留痕，统计归那一格"));
    return new TextCapabilities(capabilities, gaps);
  }

  /** 概览：几个数。 */
  public TextOverview overview() {
    return new TextOverview(
        count("select count(*) from platform_text_task"),
        count("select count(*) from platform_text_task where status = 'PENDING'"),
        count("select count(*) from platform_text_task where status = 'DRAFTED'"),
        count("select count(*) from platform_text_task where status = 'CONFIRMED'"),
        count("select count(*) from platform_text_task where status = 'PUBLISHED'"),
        count("select count(*) from platform_text_task where status = 'REJECTED'"),
        count("select count(*) from platform_text_glossary"),
        count("select count(*) from platform_text_banned_term"),
        count("select count(*) from platform_text_rule"),
        count("select count(*) from platform_text_template"),
        count("select count(*) from platform_text_draft"),
        OffsetDateTime.now(ZoneOffset.UTC));
  }

  // ================================================================ 术语表

  /** 术语表列表（真筛选）。 */
  public List<TextGlossaryRow> glossary(String termType, String locale, Boolean enabled) {
    var where = new StringBuilder(" where 1 = 1");
    var params = new ArrayList<Object>();
    if (termType != null && !termType.isBlank()) {
      where.append(" and term_type = ?");
      params.add(termType.trim().toUpperCase(Locale.ROOT));
    }
    if (locale != null && !locale.isBlank()) {
      where.append(" and target_locale = ?");
      params.add(locale.trim());
    }
    if (enabled != null) {
      where.append(" and enabled = ?");
      params.add(enabled);
    }
    return sql.fetch(
            "select id,source_term,target_term,term_type,target_locale,case_sensitive,enabled,note,"
                + " created_at,updated_at from platform_text_glossary"
                + where
                + " order by term_type, source_term",
            params.toArray())
        .map(TextTranslationService::toGlossaryRow);
  }

  /** 新增或修改一条术语。 */
  public TextGlossaryRow saveGlossary(TextGlossaryInput input, AccessService.Access actor) {
    var sourceTerm = required(input.sourceTerm(), "源词", 200);
    var targetTerm = optional(input.targetTerm(), "目标词", 200);
    var type = TermType.parse(input.termType());
    check(type != null, "术语类型只能是 BRAND / PROFESSIONAL / PRODUCT");
    var locale = optional(input.targetLocale(), "适用目标语言", 16);
    var enabled = input.enabled() == null || input.enabled();
    var caseSensitive = input.caseSensitive() != null && input.caseSensitive();
    var note = optional(input.note(), "备注", 500);

    var existing =
        sql.fetchOne(
            "select id from platform_text_glossary where source_term = ? and target_locale = ?",
            sourceTerm,
            locale);
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    var id = existing == null ? UUID.randomUUID() : existing.get("id", UUID.class);
    if (existing == null) {
      sql.execute(
          "insert into platform_text_glossary(id,tenant_id,source_term,target_term,term_type,"
              + " target_locale,case_sensitive,enabled,note,created_by,created_at,updated_at)"
              + " values(?,?,?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz)",
          id,
          currentTenant(),
          sourceTerm,
          targetTerm,
          type.name(),
          locale,
          caseSensitive,
          enabled,
          note,
          actor.userId(),
          now,
          now);
    } else {
      sql.execute(
          "update platform_text_glossary set target_term = ?, term_type = ?, case_sensitive = ?,"
              + " enabled = ?, note = ?, updated_at = ?::timestamptz where id = ?",
          targetTerm,
          type.name(),
          caseSensitive,
          enabled,
          note,
          now,
          id);
    }
    return glossaryRow(id);
  }

  /** 删除一条术语。 */
  public void deleteGlossary(UUID id, AccessService.Access actor) {
    var row = sql.fetchOne("select source_term from platform_text_glossary where id = ?", id);
    check(row != null, "术语不存在");
    sql.execute("delete from platform_text_glossary where id = ?", id);
  }

  private TextGlossaryRow glossaryRow(UUID id) {
    return sql.fetchOne(
            "select id,source_term,target_term,term_type,target_locale,case_sensitive,enabled,note,"
                + " created_at,updated_at from platform_text_glossary where id = ?",
            id)
        .map(TextTranslationService::toGlossaryRow);
  }

  private static TextGlossaryRow toGlossaryRow(org.jooq.Record r) {
    var type = TermType.parse(r.get("term_type", String.class));
    return new TextGlossaryRow(
        r.get("id", UUID.class),
        r.get("source_term", String.class),
        r.get("target_term", String.class),
        type == null ? "" : type.name(),
        type == null ? "" : type.label(),
        r.get("target_locale", String.class),
        r.get("case_sensitive", Boolean.class),
        r.get("enabled", Boolean.class),
        r.get("note", String.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  // ================================================================ 违禁词

  /** 违禁词列表（真筛选）。 */
  public List<TextBannedTermRow> bannedTerms(
      String category, String severity, String locale, Boolean enabled) {
    var where = new StringBuilder(" where 1 = 1");
    var params = new ArrayList<Object>();
    if (category != null && !category.isBlank()) {
      where.append(" and category = ?");
      params.add(category.trim().toUpperCase(Locale.ROOT));
    }
    if (severity != null && !severity.isBlank()) {
      where.append(" and severity = ?");
      params.add(severity.trim().toUpperCase(Locale.ROOT));
    }
    if (locale != null && !locale.isBlank()) {
      where.append(" and target_locale = ?");
      params.add(locale.trim());
    }
    if (enabled != null) {
      where.append(" and enabled = ?");
      params.add(enabled);
    }
    return sql.fetch(
            "select id,term,category,severity,target_locale,enabled,note,created_at,updated_at"
                + " from platform_text_banned_term"
                + where
                + " order by category, term",
            params.toArray())
        .map(TextTranslationService::toBannedTermRow);
  }

  /** 新增或修改一条违禁词。 */
  public TextBannedTermRow saveBannedTerm(TextBannedTermInput input, AccessService.Access actor) {
    var term = required(input.term(), "违禁词", 200);
    var category = BannedCategory.parse(input.category());
    check(category != null, "违禁词分类只能是 ABSOLUTE / CLAIM / GUARANTEE / COMPETITOR / CONTACT / INCENTIVE");
    var severity =
        input.severity() == null || input.severity().isBlank()
            ? "BLOCK"
            : input.severity().trim().toUpperCase(Locale.ROOT);
    check(
        List.of("BLOCK", "WARN").contains(severity),
        "严重程度只能是 BLOCK（命中即拦住发布）或 WARN（只警告）");
    var locale = optional(input.targetLocale(), "适用目标语言", 16);
    var enabled = input.enabled() == null || input.enabled();
    var note = optional(input.note(), "备注", 500);

    var existing =
        sql.fetchOne(
            "select id from platform_text_banned_term where term = ? and target_locale = ?",
            term,
            locale);
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    var id = existing == null ? UUID.randomUUID() : existing.get("id", UUID.class);
    if (existing == null) {
      sql.execute(
          "insert into platform_text_banned_term(id,tenant_id,term,category,severity,target_locale,"
              + " enabled,note,created_by,created_at,updated_at)"
              + " values(?,?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz)",
          id,
          currentTenant(),
          term,
          category.name(),
          severity,
          locale,
          enabled,
          note,
          actor.userId(),
          now,
          now);
    } else {
      sql.execute(
          "update platform_text_banned_term set category = ?, severity = ?, enabled = ?, note = ?,"
              + " updated_at = ?::timestamptz where id = ?",
          category.name(),
          severity,
          enabled,
          note,
          now,
          id);
    }
    return bannedTermRow(id);
  }

  /** 删除一条违禁词。 */
  public void deleteBannedTerm(UUID id, AccessService.Access actor) {
    var row = sql.fetchOne("select term from platform_text_banned_term where id = ?", id);
    check(row != null, "违禁词不存在");
    sql.execute("delete from platform_text_banned_term where id = ?", id);
  }

  private TextBannedTermRow bannedTermRow(UUID id) {
    return sql.fetchOne(
            "select id,term,category,severity,target_locale,enabled,note,created_at,updated_at"
                + " from platform_text_banned_term where id = ?",
            id)
        .map(TextTranslationService::toBannedTermRow);
  }

  private static TextBannedTermRow toBannedTermRow(org.jooq.Record r) {
    var category = BannedCategory.parse(r.get("category", String.class));
    return new TextBannedTermRow(
        r.get("id", UUID.class),
        r.get("term", String.class),
        category == null ? "" : category.name(),
        category == null ? "" : category.label(),
        r.get("severity", String.class),
        r.get("target_locale", String.class),
        r.get("enabled", Boolean.class),
        r.get("note", String.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  // ================================================================ 长度与字符集规则

  /** 规则列表。 */
  public List<TextRuleRow> rules(String contentType, String locale) {
    var where = new StringBuilder(" where 1 = 1");
    var params = new ArrayList<Object>();
    if (contentType != null && !contentType.isBlank()) {
      where.append(" and content_type = ?");
      params.add(contentType.trim().toUpperCase(Locale.ROOT));
    }
    if (locale != null && !locale.isBlank()) {
      where.append(" and target_locale = ?");
      params.add(locale.trim());
    }
    return sql.fetch(
            "select id,content_type,target_locale,min_length,max_length,require_charset,"
                + " terminology_strict,enabled,note,created_at,updated_at from platform_text_rule"
                + where
                + " order by content_type, target_locale",
            params.toArray())
        .map(TextTranslationService::toRuleRow);
  }

  /** 新增或修改一条规则。 */
  public TextRuleRow saveRule(TextRuleInput input, AccessService.Access actor) {
    var contentType = ContentType.parse(input.contentType());
    check(contentType != null, "内容类型只能是 TITLE / DESCRIPTION / HIGHLIGHT / CUSTOMER_REPLY");
    var locale = optional(input.targetLocale(), "适用目标语言", 16);
    var charset = CharsetRule.parse(input.requireCharset());
    check(charset != null, "字符集要求只能是 ANY / LATIN / CJK");
    var min = input.minLength();
    var max = input.maxLength();
    check(min == null || min >= 0, "长度下限不能为负");
    check(max == null || max > 0, "长度上限必须大于 0");
    check(min == null || max == null || min <= max, "长度下限不能大于上限");
    var strict = input.terminologyStrict() == null || input.terminologyStrict();
    var enabled = input.enabled() == null || input.enabled();
    var note = optional(input.note(), "备注", 500);

    var existing =
        sql.fetchOne(
            "select id from platform_text_rule where content_type = ? and target_locale = ?",
            contentType.name(),
            locale);
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    var id = existing == null ? UUID.randomUUID() : existing.get("id", UUID.class);
    if (existing == null) {
      sql.execute(
          "insert into platform_text_rule(id,tenant_id,content_type,target_locale,min_length,"
              + " max_length,require_charset,terminology_strict,enabled,note,created_by,"
              + " created_at,updated_at)"
              + " values(?,?,?,?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz)",
          id,
          currentTenant(),
          contentType.name(),
          locale,
          min,
          max,
          charset.name(),
          strict,
          enabled,
          note,
          actor.userId(),
          now,
          now);
    } else {
      sql.execute(
          "update platform_text_rule set min_length = ?, max_length = ?, require_charset = ?,"
              + " terminology_strict = ?, enabled = ?, note = ?, updated_at = ?::timestamptz where id = ?",
          min,
          max,
          charset.name(),
          strict,
          enabled,
          note,
          now,
          id);
    }
    return ruleRow(id);
  }

  /** 删除一条规则。 */
  public void deleteRule(UUID id, AccessService.Access actor) {
    var row = sql.fetchOne("select content_type from platform_text_rule where id = ?", id);
    check(row != null, "规则不存在");
    sql.execute("delete from platform_text_rule where id = ?", id);
  }

  private TextRuleRow ruleRow(UUID id) {
    return sql.fetchOne(
            "select id,content_type,target_locale,min_length,max_length,require_charset,"
                + " terminology_strict,enabled,note,created_at,updated_at from platform_text_rule"
                + " where id = ?",
            id)
        .map(TextTranslationService::toRuleRow);
  }

  private static TextRuleRow toRuleRow(org.jooq.Record r) {
    var charset = CharsetRule.parse(r.get("require_charset", String.class));
    return new TextRuleRow(
        r.get("id", UUID.class),
        r.get("content_type", String.class),
        r.get("target_locale", String.class),
        r.get("min_length", Integer.class),
        r.get("max_length", Integer.class),
        charset == null ? "" : charset.name(),
        charset == null ? "" : charset.label(),
        r.get("terminology_strict", Boolean.class),
        r.get("enabled", Boolean.class),
        r.get("note", String.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  // ================================================================ 客服回复模板

  /** 模板列表。 */
  public List<TextTemplateRow> templates(String contentType, String locale, Boolean enabled) {
    var where = new StringBuilder(" where 1 = 1");
    var params = new ArrayList<Object>();
    if (contentType != null && !contentType.isBlank()) {
      where.append(" and content_type = ?");
      params.add(contentType.trim().toUpperCase(Locale.ROOT));
    }
    if (locale != null && !locale.isBlank()) {
      where.append(" and target_locale = ?");
      params.add(locale.trim());
    }
    if (enabled != null) {
      where.append(" and enabled = ?");
      params.add(enabled);
    }
    return sql.fetch(
            "select id,template_code,template_name,body,variables::text as variables,content_type,"
                + " target_locale,enabled,note,created_at,updated_at from platform_text_template"
                + where
                + " order by template_code",
            params.toArray())
        .map(TextTranslationService::toTemplateRow);
  }

  /**
   * 新增或修改一个模板。
   *
   * <p>🔴 <b>变量词表是封闭的</b>：模板里出现封闭词表之外的 {@code {{变量}}} 直接拒收 ——
   * 服务端填不上的变量留在正文里，那封信是要发给买家的。
   */
  public TextTemplateRow saveTemplate(TextTemplateInput input, AccessService.Access actor) {
    var code = required(input.templateCode(), "模板编码", 64);
    var name = required(input.templateName(), "模板名称", 120);
    var body = required(input.body(), "模板正文", 20000);
    var contentType = ContentType.parse(input.contentType());
    check(contentType != null, "内容类型只能是 TITLE / DESCRIPTION / HIGHLIGHT / CUSTOMER_REPLY");
    var locale = optional(input.targetLocale(), "适用目标语言", 16);
    var enabled = input.enabled() == null || input.enabled();
    var note = optional(input.note(), "备注", 500);

    var variables = new ArrayList<>(TextQualityPipeline.placeholdersOf(body));
    var unknown = new ArrayList<String>();
    for (var variable : variables) {
      if (!TextQualityPipeline.TEMPLATE_VARIABLES.contains(variable)) unknown.add(variable);
    }
    check(
        unknown.isEmpty(),
        "模板里有不在封闭变量词表内的占位符："
            + String.join(" / ", unknown)
            + "。服务端**确定填不上**这些变量，正文里会留下没人看得懂的 {{xxx}}。"
            + "允许的变量：" + String.join(" / ", TextQualityPipeline.TEMPLATE_VARIABLES));

    String variablesJson;
    try {
      variablesJson = json.writeValueAsString(variables);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException(e);
    }

    var existing =
        sql.fetchOne("select id from platform_text_template where template_code = ?", code);
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    var id = existing == null ? UUID.randomUUID() : existing.get("id", UUID.class);
    if (existing == null) {
      sql.execute(
          "insert into platform_text_template(id,tenant_id,template_code,template_name,body,variables,"
              + " content_type,target_locale,enabled,note,created_by,created_at,updated_at)"
              + " values(?,?,?,?,?,?::jsonb,?,?,?,?,?,?::timestamptz,?::timestamptz)",
          id,
          currentTenant(),
          code,
          name,
          body,
          variablesJson,
          contentType.name(),
          locale,
          enabled,
          note,
          actor.userId(),
          now,
          now);
    } else {
      sql.execute(
          "update platform_text_template set template_name = ?, body = ?, variables = ?::jsonb,"
              + " content_type = ?, target_locale = ?, enabled = ?, note = ?,"
              + " updated_at = ?::timestamptz where id = ?",
          name,
          body,
          variablesJson,
          contentType.name(),
          locale,
          enabled,
          note,
          now,
          id);
    }
    return templateRow(id);
  }

  /** 删除一个模板。 */
  public void deleteTemplate(UUID id, AccessService.Access actor) {
    var row = sql.fetchOne("select template_code from platform_text_template where id = ?", id);
    check(row != null, "模板不存在");
    sql.execute("delete from platform_text_template where id = ?", id);
  }

  /**
   * 变量绑定（把模板渲染成一封**可发出去**的回复）。
   *
   * @param values 变量取值（键是封闭词表里的变量名）
   */
  public TextTemplateBinding bindTemplate(UUID templateId, Map<String, String> values) {
    var template = templateRow(templateId);
    check(template != null, "模板不存在");
    var rendered = template.body();
    var missing = new ArrayList<String>();
    for (var variable : template.variables()) {
      var value = values == null ? null : values.get(variable);
      if (value == null || value.isBlank()) {
        missing.add(variable);
        continue;
      }
      rendered = rendered.replace("{{" + variable + "}}", value);
    }
    var unknown = new ArrayList<String>();
    if (values != null) {
      for (var key : values.keySet()) {
        if (!template.variables().contains(key)) unknown.add(key);
      }
    }
    return new TextTemplateBinding(
        template.id(), template.templateCode(), rendered, template.variables(), missing, unknown);
  }

  private TextTemplateRow templateRow(UUID id) {
    return sql.fetchOne(
            "select id,template_code,template_name,body,variables::text as variables,content_type,"
                + " target_locale,enabled,note,created_at,updated_at from platform_text_template"
                + " where id = ?",
            id)
        .map(TextTranslationService::toTemplateRow);
  }

  @SuppressWarnings("unchecked")
  private static TextTemplateRow toTemplateRow(org.jooq.Record r) {
    var raw = r.get("variables", String.class);
    List<String> variables = List.of();
    if (raw != null && !raw.isBlank()) {
      try {
        variables = new ObjectMapper().readValue(raw, List.class);
      } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
        variables = List.of();
      }
    }
    return new TextTemplateRow(
        r.get("id", UUID.class),
        r.get("template_code", String.class),
        r.get("template_name", String.class),
        r.get("body", String.class),
        variables,
        r.get("content_type", String.class),
        r.get("target_locale", String.class),
        r.get("enabled", Boolean.class),
        r.get("note", String.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  // ================================================================ 任务

  /** 任务列表（真分页 + 真筛选）。 */
  public TextTaskPage tasks(
      String keyword, String contentType, String status, String targetLocale, int page, int size) {
    var size2 = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    var page2 = Math.max(page, 0);
    var where = new StringBuilder(" where 1 = 1");
    var params = new ArrayList<Object>();
    if (keyword != null && !keyword.isBlank()) {
      where.append(" and (t.task_no ilike ? or t.source_text ilike ?)");
      var like = "%" + keyword.trim() + "%";
      params.add(like);
      params.add(like);
    }
    if (contentType != null && !contentType.isBlank()) {
      where.append(" and t.content_type = ?");
      params.add(contentType.trim().toUpperCase(Locale.ROOT));
    }
    if (status != null && !status.isBlank()) {
      where.append(" and t.status = ?");
      params.add(status.trim().toUpperCase(Locale.ROOT));
    }
    if (targetLocale != null && !targetLocale.isBlank()) {
      where.append(" and t.target_locale = ?");
      params.add(targetLocale.trim());
    }
    var total =
        sql.fetchOne("select count(*) from platform_text_task t" + where, params.toArray())
            .get(0, Long.class);
    var rows =
        sql.fetch(
            taskSelect()
                + where
                + " order by t.created_at desc, t.id limit ? offset ?",
            append(params, size2, (long) page2 * size2));
    return new TextTaskPage(
        rows.map(TextTranslationService::toTaskRow),
        page2,
        size2,
        total,
        (total + size2 - 1) / size2);
  }

  /**
   * 建一个翻译任务。
   *
   * @param input.templateId 客服话术任务可以指定模板 —— 源文本取模板正文
   */
  public TextTaskRow createTask(TextTaskInput input, AccessService.Access actor) {
    var contentType = ContentType.parse(input.contentType());
    check(contentType != null, "内容类型只能是 TITLE / DESCRIPTION / HIGHLIGHT / CUSTOMER_REPLY");
    var sourceLocale = required(input.sourceLocale(), "源语言", 16);
    var targetLocale =
        input.targetLocale() == null || input.targetLocale().isBlank()
            ? ""
            : input.targetLocale().trim();
    var sourceText = input.sourceText() == null ? "" : input.sourceText().strip();

    if (input.templateId() != null && !input.templateId().isBlank()) {
      // 🔴 模板 id 是**客户端给的字符串**：形状不对要报 400，不能一路走到
      //   UUID.fromString 抛 IllegalArgumentException 变成 500（那是服务端崩了的意思）。
      UUID templateId;
      try {
        templateId = UUID.fromString(input.templateId().trim());
      } catch (IllegalArgumentException e) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "模板 id 不是合法的 UUID");
      }
      var template = templateRow(templateId);
      check(template != null, "客服话术模板不存在");
      check(template.enabled(), "该模板已停用，不能用来建任务");
      sourceText = template.body();
      if (targetLocale.isEmpty()) targetLocale = template.targetLocale();
    }
    check(!sourceText.isEmpty(), "源文本不能为空");
    check(sourceText.length() <= 20000, "源文本长度不能超过 20000 个字符");
    check(!targetLocale.isEmpty(), "目标语言不能为空");
    check(targetLocale.length() <= 16, "目标语言长度不能超过 16 个字符");
    check(!sourceLocale.equalsIgnoreCase(targetLocale), "源语言与目标语言不能相同 —— 那不叫翻译");

    var style = optional(input.styleRequirement(), "风格要求", 500);
    var upstreamType = optional(input.upstreamRefType(), "上游引用类型", 32).toUpperCase(Locale.ROOT);
    check(
        upstreamType.isEmpty()
            || List.of("CATALOG_SPU", "CATALOG_SKU", "CHANNEL_LISTING").contains(upstreamType),
        "上游引用类型只能是 CATALOG_SPU / CATALOG_SKU / CHANNEL_LISTING");

    var now = OffsetDateTime.now(ZoneOffset.UTC);
    var id = UUID.randomUUID();
    var taskNo = nextTaskNo();
    sql.execute(
        "insert into platform_text_task(id,tenant_id,task_no,content_type,source_text,source_locale,"
            + " target_locale,style_requirement,upstream_ref_type,upstream_ref_id,status,created_by,"
            + " created_at,updated_at)"
            + " values(?,?,?,?,?,?,?,?,?,?,'PENDING',?,?::timestamptz,?::timestamptz)",
        id,
        currentTenant(),
        taskNo,
        contentType.name(),
        sourceText,
        sourceLocale,
        targetLocale,
        style,
        upstreamType,
        input.upstreamRefId(),
        actor.userId(),
        now,
        now);
    return taskRow(id);
  }

  /** 人可读单号：租户短码 + 序号（跨租户也一眼看得出归属）。 */
  private String nextTaskNo() {
    var seq =
        sql.fetchOne("select nextval('platform_text_task_no_seq') as seq").get("seq", Long.class);
    return "TX-"
        + currentTenant().toString().substring(0, 8).toUpperCase(Locale.ROOT)
        + "-"
        + String.format("%06d", seq);
  }

  /** 任务详情（任务 + 当前草稿 + 历史版本 + 检查结论 + 规避建议 + 置信度）。 */
  public TextTaskDetail detail(UUID taskId) {
    var task = taskRow(taskId);
    check(task != null, "任务不存在");
    var history = drafts(taskId);
    var checks = checks(taskId);
    var violations = violations(checks);
    var confidence =
        new TextConfidence(
            task.confidence(),
            task.confidenceTermCoverage(),
            task.confidencePlaceholderIntegrity(),
            task.confidenceCheckPassRate(),
            task.confidenceLengthCompliance(),
            confidenceExplanation(task, checks));
    return new TextTaskDetail(
        task,
        history.isEmpty() ? null : history.get(0),
        history,
        checks,
        violations,
        confidence);
  }

  /**
   * 走 AI 通道生成 / 翻译。
   *
   * 🔴 **本仓当前 AI 供应商未接入** ⇒ 这里的实现是：拿到「未接入」直接翻成 <b>409</b>，
   *   <b>一个字都不写</b> —— 不落草稿、不落状态、不留任何「已翻译」的痕迹。
   *   适配器交付后，只要换掉 {@link UnavailableAiTextGenerationPort}，这里立刻变成真的。
   */
  public TextGenerationOutcome generate(UUID taskId, AccessService.Access actor, String bearer) {
    var task = taskRow(taskId);
    check(task != null, "任务不存在");
    conflictIf(
        "PUBLISHED".equals(task.status()),
        "这条译文已经发布；要改请新建一条任务（已发布的内容不能被就地改掉）。");
    var terms = protectedTerms(task.targetLocale());
    var outcome =
        ai.generate(
            new AiTextGenerationPort.AiGenerationRequest(
                task.contentType(),
                task.sourceText(),
                task.sourceLocale(),
                task.targetLocale(),
                task.styleRequirement(),
                terms.stream().map(Term::sourceTerm).toList()),
            bearer);
    if (!outcome.connected() || !outcome.generated()) {
      // 🔴 **这里不落任何数据**：一条假的成功回执会让「文案已经翻译好了」在数据上
      // 看起来发生过，而现场一个字都没翻译出来。
      conflict(outcome.reason());
    }
    // 适配器交付后走这里：把生成的文本当成一版人工可编辑的草稿落库。
    return new TextGenerationOutcome(
        outcome.connected(),
        outcome.generated(),
        outcome.text(),
        outcome.model(),
        outcome.reason(),
        outcome.ownerModule());
  }

  /**
   * 人工录入 / 编辑译文（**可人工编辑**）。
   *
   * <p>每次保存追加一个**新版本**（旧版本留着：哪一版是谁改的，要能查清），
   * 并**重跑**确定性检查、重算置信度。
   *
   * <p>🔴 **已确认的译文被改动后，确认作废**（状态退回 DRAFTED）：
   * 人工确认是对**那一版内容**的确认；内容变了还留着确认，等于确认了一个没人看过的版本。
   */
  public TextTaskDetail saveDraft(UUID taskId, TextDraftInput input, AccessService.Access actor) {
    var task = taskRow(taskId);
    check(task != null, "任务不存在");
    conflictIf(
        "PUBLISHED".equals(task.status()),
        "这条译文已经发布；要改请新建一条任务（已发布的内容不能被就地改掉）。");
    conflictIf("REJECTED".equals(task.status()), "这条任务已被驳回，请新建一条任务。");
    var targetText = required(input.targetText(), "译文", 20000);

    var now = OffsetDateTime.now(ZoneOffset.UTC);
    var version =
        sql.fetchOne(
                "select coalesce(max(version),0) from platform_text_draft where task_id = ?", taskId)
            .get(0, Integer.class)
            + 1;
    var draftId = UUID.randomUUID();
    sql.execute(
        "insert into platform_text_draft(id,tenant_id,task_id,version,target_text,origin,confidence,"
            + " edited_by,created_at,updated_at) values(?,?,?,?,?,'MANUAL',0,?,?::timestamptz,"
            + "?::timestamptz)",
        draftId,
        currentTenant(),
        taskId,
        version,
        targetText,
        actor.userId(),
        now,
        now);

    // 跑确定性检查：术语 / 占位符 / 违禁词 / 长度 / 字符集。
    var report =
        TextQualityPipeline.evaluate(
            task.sourceText(),
            targetText,
            termsFor(task.targetLocale()),
            bannedFor(task.targetLocale()),
            ruleFor(task.contentType(), task.targetLocale()));

    // 检查结论是「这一版译文的现状」，不是历史流水 —— 同事务内替换。
    sql.execute("delete from platform_text_check where task_id = ?", taskId);
    for (var finding : report.findings()) {
      sql.execute(
          "insert into platform_text_check(id,tenant_id,task_id,draft_id,rule_code,verdict,"
              + " matched_item,detail,created_at) values(?,?,?,?,?,?,?,?,?::timestamptz)",
          UUID.randomUUID(),
          currentTenant(),
          taskId,
          draftId,
          finding.ruleCode(),
          finding.verdict(),
          finding.matchedItem(),
          finding.detail(),
          now);
    }

    sql.execute(
        "update platform_text_draft set confidence = ?::numeric, confidence_term_coverage = ?::numeric,"
            + " confidence_placeholder_integrity = ?::numeric, confidence_check_pass_rate = ?::numeric,"
            + " confidence_length_compliance = ?::numeric, check_total = ?, check_failed = ?,"
            + " check_warned = ?, updated_at = ?::timestamptz where id = ?",
        report.confidence(),
        report.termCoverage(),
        report.placeholderIntegrity(),
        report.checkPassRate(),
        report.lengthCompliance(),
        report.findings().size(),
        (int) report.findings().stream().filter(f -> "FAIL".equals(f.verdict())).count(),
        (int) report.findings().stream().filter(f -> "WARN".equals(f.verdict())).count(),
        now,
        draftId);

    // 内容变了 ⇒ 人工确认作废（确认是对**那一版**的确认）。
    sql.execute(
        "update platform_text_task set current_draft_id = ?, status = 'DRAFTED', confirmed_by = null,"
            + " confirmed_at = null, updated_at = ?::timestamptz where id = ?",
        draftId,
        now,
        taskId);
    return detail(taskId);
  }

  /**
   * 人工确认（**发布的硬门槛**）。
   *
   * <p>确认的是「这一版内容人工看过」。带 FAIL 的内容**确认本身允许**（那是人的判断），
   * 但它**发不出去** —— 发布端点会再拦一道。
   */
  public TextTaskDetail confirm(UUID taskId, AccessService.Access actor) {
    var task = taskRow(taskId);
    check(task != null, "任务不存在");
    check(
        task.currentDraftId() != null,
        "这条任务还没有译文 —— 先录入译文（人工录入或接入 AI 通道之后再确认）。");
    conflictIf(
        "PUBLISHED".equals(task.status()),
        "这条译文已经发布，确认是多余的（已发布的内容不能被就地改掉）。");
    conflictIf("REJECTED".equals(task.status()), "这条任务已被驳回，不能再确认。请新建一条任务。");
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    sql.execute(
        "update platform_text_task set status = 'CONFIRMED', confirmed_by = ?, confirmed_at = ?::timestamptz,"
            + " updated_at = ?::timestamptz where id = ?",
        actor.userId(),
        now,
        now,
        taskId);
    return detail(taskId);
  }

  /**
   * 发布。
   *
   * <p>🔴 <b>两道闸</b>：
   *
   * <ol>
   *   <li>必须已 <b>CONFIRMED</b> —— 蓝图加粗句「翻译结果必须人工确认后发布」；
   *   <li>不允许带 <b>FAIL</b> 的检查结论发布 —— 确定性检查必须<b>真的拦得住</b>，
   *       否则它只是一行提示。
   * </ol>
   */
  public TextTaskDetail publish(UUID taskId, AccessService.Access actor) {
    var task = taskRow(taskId);
    check(task != null, "任务不存在");
    // 🔴 **409 而不是 400**：参数没毛病，是「当前状态不允许发布」。
    conflictIf(
        !"CONFIRMED".equals(task.status()),
        "翻译结果必须人工确认后发布：这条任务当前是「"
            + statusLabel(task.status())
            + "」，还没有经过人工确认。");
    var failures =
        checks(taskId).stream().filter(c -> "FAIL".equals(c.verdict())).map(TextCheckRow::detail).toList();
    if (!failures.isEmpty()) {
      // 状态冲突（409）：不是「参数不对」，是「当前这一版还不该发出去」。
      conflict(
          "这一版译文还有未解决的确定性检查失败（"
              + failures.size()
              + " 条），不能发布："
              + String.join("；", failures));
    }
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    sql.execute(
        "update platform_text_task set status = 'PUBLISHED', published_at = ?::timestamptz,"
            + " updated_at = ?::timestamptz where id = ?",
        now,
        now,
        taskId);
    return detail(taskId);
  }

  /** 驳回（终态）。 */
  public TextTaskDetail reject(UUID taskId, String reason, AccessService.Access actor) {
    var task = taskRow(taskId);
    check(task != null, "任务不存在");
    conflictIf(
        "PUBLISHED".equals(task.status()),
        "已发布的译文不能被驳回（已发布的内容必须可追溯）。");
    var text = required(reason, "驳回原因", 500);
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    sql.execute(
        "update platform_text_task set status = 'REJECTED', reject_reason = ?, updated_at = ?::timestamptz"
            + " where id = ?",
        text,
        now,
        taskId);
    return detail(taskId);
  }

  /**
   * 已发布的文案（**下游可取的那一份**）。
   *
   * <p>🔴 这里只返回 {@code status='PUBLISHED'} 的行 —— 蓝图加粗句「翻译结果必须人工确认后发布」
   * 落到读路径上：**未确认的译文取不走**。
   *
   * <p>下游 = 15.1.1.2 消息发送（crm）与 4.2.1.1 刊登编辑器（channel）。
   */
  public List<TextTaskRow> published(String contentType, int page, int size) {
    var size2 = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    var page2 = Math.max(page, 0);
    var where = new StringBuilder(" where t.status = 'PUBLISHED'");
    var params = new ArrayList<Object>();
    if (contentType != null && !contentType.isBlank()) {
      where.append(" and t.content_type = ?");
      params.add(contentType.trim().toUpperCase(Locale.ROOT));
    }
    return sql.fetch(
            taskSelect()
                + where
                + " order by t.published_at desc, t.id limit ? offset ?",
            append(params, size2, (long) page2 * size2))
        .map(TextTranslationService::toTaskRow);
  }

  // ================================================================ 上游（跨服务只读）

  /**
   * 可选目标语言（来自 **3.4.3 多语言**的租户语言需求）。
   *
   * <p>🔴 取不到就是 <b>503</b>（上游端口负责），**不**返回空列表冒充「没有需要翻译的语言」。
   */
  public TextLocaleView locales(String bearer) {
    var page = catalog.locales(0, bearer);
    var items =
        page.items() == null
            ? List.<TextLocaleOption>of()
            : page.items().stream()
                .map(
                    row ->
                        new TextLocaleOption(
                            row.locale(),
                            row.localeName(),
                            row.enabled(),
                            row.translationCount(),
                            row.pendingCount()))
                .toList();
    return new TextLocaleView(items, "catalog 服务 · 3.4.3 多语言内容的租户语言需求", "");
  }

  /**
   * 可拿来翻译的上游源文本。
   *
   * @param refType CATALOG_SPU / CATALOG_SKU / CHANNEL_LISTING
   */
  public TextSourceCandidateView sourceCandidates(
      String refType, UUID refId, String bearer) {
    check(refId != null, "上游引用 id 不能为空");
    var type = refType == null ? "" : refType.trim().toUpperCase(Locale.ROOT);
    switch (type) {
      case "CATALOG_SPU", "CATALOG_SKU" -> {
        var targetType = "CATALOG_SPU".equals(type) ? "SPU" : "SKU";
        // 🔴 判据必须是 **`type`（CATALOG_SPU / CATALOG_SKU）**，不是 `targetType`（SPU / SKU）。
        //   写成 "SPU".equals(type) 时两边**永远不相等** —— 于是 spuId / skuId 两个过滤参数
        //   永远是 null，上游收到的查询**不带任何 SPU / SKU 过滤**，返回的是全量条目：
        //   「查某一个 SPU 的译文」会变成「把所有 SPU 的译文都拿回来」。
        var page =
            catalog.entries(
                targetType,
                "CATALOG_SPU".equals(type) ? refId : null,
                "CATALOG_SKU".equals(type) ? refId : null,
                0,
                bearer);
        var items =
            page.items() == null
                ? List.<TextSourceCandidate>of()
                : page.items().stream()
                    .map(
                        row -> {
                          var ref = "SPU".equals(targetType) ? row.spuId() : row.skuId();
                          return new TextSourceCandidate(
                              type,
                              ref,
                              // ⚠️ 上游的译文列表**没有**人类可读的编码（只有 id 与语言），
                              //   所以这里如实给 id，而不是编一个看起来像编码的字符串。
                              ref == null ? "" : ref.toString(),
                              row.locale(),
                              row.name(),
                              row.description());
                        })
                    .toList();
        return new TextSourceCandidateView(items, "catalog 服务 · 3.4.3 多语言内容", "");
      }
      case "CHANNEL_LISTING" -> {
        var draft = channel.draft(refId, bearer);
        var texts = new ArrayList<String>();
        var title = "";
        for (var field : draft.fieldValues() == null ? List.<ChannelListingPort.FieldValue>of() : draft.fieldValues()) {
          var value = field.text();
          if (value == null || value.isBlank()) continue;
          if (title.isEmpty() && field.field() != null && field.field().toLowerCase(Locale.ROOT).contains("title")) {
            title = value.strip();
          } else {
            texts.add(value.strip());
          }
        }
        if (title.isEmpty() && !texts.isEmpty()) {
          title = texts.remove(0);
        }
        var items =
            List.of(
                new TextSourceCandidate(
                    type,
                    refId,
                    draft.shopCode() == null ? "" : draft.shopCode(),
                    draft.locale() == null ? "" : draft.locale(),
                    title,
                    String.join("\n", texts)));
        return new TextSourceCandidateView(items, "channel 服务 · 4.2.1.1 刊登编辑器草稿", "");
      }
      default ->
          throw new ResponseStatusException(
              HttpStatus.BAD_REQUEST,
              "上游引用类型只能是 CATALOG_SPU / CATALOG_SKU / CHANNEL_LISTING");
    }
  }

  // ================================================================ 内部：数据装载

  /** 适用于该目标语言的术语（空 locale = 全部语言）。 */
  private List<Term> termsFor(String locale) {
    return sql.fetch(
            "select source_term,target_term,term_type,case_sensitive from platform_text_glossary"
                + " where enabled = true and (target_locale = '' or target_locale = ?)"
                + " order by term_type, source_term",
            locale)
        .map(
            r -> {
              var type = TermType.parse(r.get("term_type", String.class));
              return new Term(
                  r.get("source_term", String.class),
                  r.get("target_term", String.class),
                  type == null ? TermType.PROFESSIONAL : type,
                  r.get("case_sensitive", Boolean.class));
            });
  }

  /** 需要保护的品牌词（给 AI 通道用的清单）。 */
  private List<Term> protectedTerms(String locale) {
    return termsFor(locale).stream().filter(t -> t.type() == TermType.BRAND).toList();
  }

  /** 适用于该目标语言的违禁词（空 locale = 全部语言）。 */
  private List<BannedTerm> bannedFor(String locale) {
    return sql.fetch(
            "select term,category,severity from platform_text_banned_term"
                + " where enabled = true and (target_locale = '' or target_locale = ?)"
                + " order by category, term",
            locale)
        .map(
            r -> {
              var category = BannedCategory.parse(r.get("category", String.class));
              return new BannedTerm(
                  r.get("term", String.class),
                  category == null ? BannedCategory.CLAIM : category,
                  r.get("severity", String.class));
            });
  }

  /** 长度与字符集规则：先找精确语言，再找通用（空 locale）规则。 */
  private Rule ruleFor(String contentType, String locale) {
    var row =
        sql.fetchOne(
            "select min_length,max_length,require_charset,terminology_strict from platform_text_rule"
                + " where enabled = true and content_type = ? and target_locale = ?",
            contentType,
            locale);
    if (row == null) {
      row =
          sql.fetchOne(
              "select min_length,max_length,require_charset,terminology_strict from platform_text_rule"
                  + " where enabled = true and content_type = ? and target_locale = ''",
              contentType);
    }
    if (row == null) return null;
    var charset = CharsetRule.parse(row.get("require_charset", String.class));
    return new Rule(
        row.get("min_length", Integer.class),
        row.get("max_length", Integer.class),
        charset == null ? CharsetRule.ANY : charset,
        row.get("terminology_strict", Boolean.class));
  }

  // ================================================================ 内部：读取

  private String taskSelect() {
    return "select t.id,t.task_no,t.content_type,t.source_text,t.source_locale,t.target_locale,"
        + " t.style_requirement,t.upstream_ref_type,t.upstream_ref_id,t.status,t.current_draft_id,"
        + " t.confirmed_by,t.confirmed_at,t.published_at,t.reject_reason,t.created_at,t.updated_at,"
        + " d.target_text as current_draft_text,d.confidence as confidence,"
        + " d.confidence_term_coverage,d.confidence_placeholder_integrity,"
        + " d.confidence_check_pass_rate,d.confidence_length_compliance,"
        + " d.check_total,d.check_failed,d.check_warned"
        + " from platform_text_task t"
        + " left join platform_text_draft d on d.tenant_id = t.tenant_id and d.id = t.current_draft_id";
  }

  private TextTaskRow taskRow(UUID taskId) {
    return sql.fetchOne(taskSelect() + " where t.id = ?", taskId)
        .map(TextTranslationService::toTaskRow);
  }

  private static TextTaskRow toTaskRow(org.jooq.Record r) {
    var contentType = ContentType.parse(r.get("content_type", String.class));
    var status = TaskStatus.parse(r.get("status", String.class));
    return new TextTaskRow(
        r.get("id", UUID.class),
        r.get("task_no", String.class),
        contentType == null ? "" : contentType.name(),
        contentType == null ? "" : contentType.label(),
        r.get("source_text", String.class),
        r.get("source_locale", String.class),
        r.get("target_locale", String.class),
        r.get("style_requirement", String.class),
        r.get("upstream_ref_type", String.class),
        r.get("upstream_ref_id", UUID.class),
        status == null ? "" : status.name(),
        status == null ? "" : status.label(),
        r.get("current_draft_id", UUID.class),
        r.get("current_draft_text", String.class),
        r.get("confidence", BigDecimal.class),
        r.get("confidence_term_coverage", BigDecimal.class),
        r.get("confidence_placeholder_integrity", BigDecimal.class),
        r.get("confidence_check_pass_rate", BigDecimal.class),
        r.get("confidence_length_compliance", BigDecimal.class),
        r.get("check_total", Integer.class) == null ? 0 : r.get("check_total", Integer.class),
        r.get("check_failed", Integer.class) == null ? 0 : r.get("check_failed", Integer.class),
        r.get("check_warned", Integer.class) == null ? 0 : r.get("check_warned", Integer.class),
        r.get("confirmed_by", UUID.class),
        r.get("confirmed_at", OffsetDateTime.class),
        r.get("published_at", OffsetDateTime.class),
        r.get("reject_reason", String.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  /** 草稿版本列表（新到旧）。 */
  private List<TextDraftRow> drafts(UUID taskId) {
    return sql.fetch(
            "select id,task_id,version,target_text,origin,confidence,confidence_term_coverage,"
                + " confidence_placeholder_integrity,confidence_check_pass_rate,"
                + " confidence_length_compliance,check_total,check_failed,check_warned,edited_by,"
                + " created_at,updated_at from platform_text_draft where task_id = ?"
                + " order by version desc",
            taskId)
        .map(
            r ->
                new TextDraftRow(
                    r.get("id", UUID.class),
                    r.get("task_id", UUID.class),
                    r.get("version", Integer.class),
                    r.get("target_text", String.class),
                    r.get("origin", String.class),
                    r.get("confidence", BigDecimal.class),
                    r.get("confidence_term_coverage", BigDecimal.class),
                    r.get("confidence_placeholder_integrity", BigDecimal.class),
                    r.get("confidence_check_pass_rate", BigDecimal.class),
                    r.get("confidence_length_compliance", BigDecimal.class),
                    r.get("check_total", Integer.class),
                    r.get("check_failed", Integer.class),
                    r.get("check_warned", Integer.class),
                    r.get("edited_by", UUID.class),
                    r.get("created_at", OffsetDateTime.class),
                    r.get("updated_at", OffsetDateTime.class)));
  }

  /** 检查结论列表。 */
  private List<TextCheckRow> checks(UUID taskId) {
    return sql.fetch(
            "select id,task_id,draft_id,rule_code,verdict,matched_item,detail,created_at"
                + " from platform_text_check where task_id = ? order by rule_code, created_at",
            taskId)
        .map(
            r ->
                new TextCheckRow(
                    r.get("id", UUID.class),
                    r.get("task_id", UUID.class),
                    r.get("draft_id", UUID.class),
                    r.get("rule_code", String.class),
                    ruleLabel(r.get("rule_code", String.class)),
                    r.get("verdict", String.class),
                    r.get("matched_item", String.class),
                    r.get("detail", String.class),
                    r.get("created_at", OffsetDateTime.class)));
  }

  /** 从检查结论里抽出「需要处理的问题」（FAIL / WARN），并给出规避建议。 */
  private List<TextViolation> violations(List<TextCheckRow> checks) {
    var out = new ArrayList<TextViolation>();
    for (var check : checks) {
      if ("FAIL".equals(check.verdict()) || "WARN".equals(check.verdict())) {
        out.add(
            new TextViolation(
                check.ruleCode(),
                "FAIL".equals(check.verdict()) ? "BLOCK" : "WARN",
                check.matchedItem(),
                suggestionFor(check),
                check.detail()));
      }
    }
    return out;
  }

  /** 规避建议：能说清「改什么」就说清，说不清就退回检查结论本身。 */
  private String suggestionFor(TextCheckRow check) {
    if ("BANNED_TERM".equals(check.ruleCode())) {
      return "把「" + check.matchedItem() + "」换成合规表述（详情见结论）。";
    }
    if ("TERMINOLOGY".equals(check.ruleCode())) {
      return "把译文里的「" + check.matchedItem() + "」恢复成术语表里的写法。";
    }
    if ("PLACEHOLDER".equals(check.ruleCode())) {
      return "补回源文本里的占位符 {{" + check.matchedItem() + "}}。";
    }
    if ("LENGTH".equals(check.ruleCode())) {
      return "把译文长度调整到规则区间内。";
    }
    if ("CHARSET".equals(check.ruleCode())) {
      return "按该语言的字符集要求重写这一段。";
    }
    return "按检查结论改这一处。";
  }

  /** 置信度的逐维说明（详情视图用）。 */
  private List<String> confidenceExplanation(TextTaskRow task, List<TextCheckRow> checks) {
    var out = new ArrayList<String>();
    if (task.confidence() == null) {
      out.add("还没有译文，暂不计算置信度。");
      return out;
    }
    out.add("置信度总分 " + task.confidence().toPlainString());
    out.add(
        "术语覆盖率 "
            + (task.confidenceTermCoverage() == null
                ? "不适用（源文本未命中术语表里的术语）"
                : task.confidenceTermCoverage().toPlainString()));
    out.add(
        "占位符完整率 "
            + (task.confidencePlaceholderIntegrity() == null
                ? "不适用（源文本与译文都没有占位符）"
                : task.confidencePlaceholderIntegrity().toPlainString()));
    out.add(
        "校验通过率 "
            + (task.confidenceCheckPassRate() == null
                ? "不适用"
                : task.confidenceCheckPassRate().toPlainString()));
    out.add(
        "长度合规率 "
            + (task.confidenceLengthCompliance() == null
                ? "不适用（该内容类型在该语言下未配置长度规则）"
                : task.confidenceLengthCompliance().toPlainString()));
    var failed = (int) checks.stream().filter(c -> "FAIL".equals(c.verdict())).count();
    var warned = (int) checks.stream().filter(c -> "WARN".equals(c.verdict())).count();
    out.add("本次检查：" + checks.size() + " 条结论，" + failed + " 条失败、" + warned + " 条警告。");
    return out;
  }

  // ================================================================ 内部：小工具

  /** 规则码的中文标签。 */
  static String ruleLabel(String ruleCode) {
    return switch (ruleCode == null ? "" : ruleCode) {
      case "TERMINOLOGY" -> "术语保护";
      case "PLACEHOLDER" -> "占位符完整性";
      case "BANNED_TERM" -> "违禁词";
      case "LENGTH" -> "长度";
      case "CHARSET" -> "字符集";
      default -> ruleCode;
    };
  }

  private static String statusLabel(String status) {
    var parsed = TaskStatus.parse(status);
    return parsed == null ? status : parsed.label();
  }

  /** 拼参数（可变长列表 → 数组）。 */
  private static Object[] append(List<Object> params, Object... extra) {
    var all = new ArrayList<Object>(params);
    all.addAll(List.of(extra));
    return all.toArray();
  }

  /** 变量取值（模板绑定用）。 */
  static Map<String, String> valuesOf(Map<String, String> values) {
    return values == null ? new LinkedHashMap<>() : values;
  }
}
