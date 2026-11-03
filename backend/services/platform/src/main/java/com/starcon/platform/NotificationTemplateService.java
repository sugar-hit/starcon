package com.starcon.platform;

import static com.starcon.platform.NotificationTemplateModels.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 18.5.1.1「模板管理」的服务层。
 *
 * <p>数据访问一律走 <b>jOOQ 显式 SQL</b>（AGENTS.md：不用 JPA 懒加载掩盖关键逻辑）。
 * 租户上下文由 {@link AccessService#run} 绑定 —— 本类<b>不</b>读任何请求头里的租户。
 *
 * <p><b>五条纪律：</b>
 *
 * <ol>
 *   <li><b>编辑 = 新增版本行</b>：{@link #appendVersion} 只 INSERT 一行新版本，
 *       <b>绝不</b> UPDATE 老版本行 ⇒ 版本天然不可篡改（迁移里连 UPDATE / DELETE 权限都没给）。
 *   <li><b>按时刻解析</b>：{@link #resolve} 取的是「那一刻生效的那一版」，
 *       依据是<b>启用事件日志</b>而不是「当前版本」⇒ 后来改模板篡改不了已经发出的通知。
 *   <li><b>渲染失败就报错</b>：缺变量 / 多余变量 / 未知变量 / 语法错误一律点名报错，
 *       <b>绝不</b>渲染成空串、也<b>绝不</b>留下 {@code {{xxx}}} 残渣（见
 *       {@link NotificationTemplateRenderer}）。
 *   <li><b>写入归属会话租户</b>：所有 INSERT 的 {@code tenant_id} 取自已认证会话，
 *       不接受请求参数传入；RLS 的 WITH CHECK + 租户触发器再兜一层。
 *   <li><b>不物理删历史</b>：删除模板 = 停用 + 归档，版本行原样留着。
 * </ol>
 */
@Service
public class NotificationTemplateService {

  /** 列表分页上限（真分页的上限，防止一次拉爆）。 */
  static final int MAX_PAGE_SIZE = 100;

  /** 正文 / 标题的长度上限（字符数，按 Unicode 码点计）。 */
  static final int MAX_CONTENT_LENGTH = 20000;

  private final DSLContext sql;
  private final ObjectMapper json;
  private final CatalogContentAssetPort catalog;

  public NotificationTemplateService(
      DSLContext sql, ObjectMapper json, CatalogContentAssetPort catalog) {
    this.sql = sql;
    this.json = json;
    this.catalog = catalog;
  }

  // ================================================================ 工具

  private static void check(boolean condition, String message) {
    if (!condition) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  /** 状态冲突（409）：不是「参数不对」，是「当前状态不允许」。 */
  private static void conflict(String message) {
    throw new ResponseStatusException(HttpStatus.CONFLICT, message);
  }

  private static String required(String value, String label, int max) {
    var trimmed = value == null ? "" : value.trim();
    check(!trimmed.isEmpty(), label + "不能为空");
    check(trimmed.length() <= max, label + "长度不能超过 " + max + " 个字符");
    return trimmed;
  }

  private static String optional(String value, String label, int max) {
    var trimmed = value == null ? "" : value.trim();
    check(trimmed.length() <= max, label + "长度不能超过 " + max + " 个字符");
    return trimmed;
  }

  private static String optionalText(String value, String label, int max) {
    var trimmed = value == null ? "" : value.strip();
    check(trimmed.length() <= max, label + "长度不能超过 " + max + " 个字符");
    return trimmed;
  }

  /**
   * 语言规范化成 BCP-47 的**规范写法**：语种小写、地区大写。
   *
   * <p>「zh-cn」与「zh-CN」说的是同一门语言，存成两种写法就会出现两条「看起来不同、
   * 其实是同一个」的版本链 —— 下游按语言取内容时会漏掉一条。
   */
  static String normalizeLanguage(String raw) {
    var value = raw == null ? "" : raw.trim();
    check(
        value.matches("^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*$"),
        "语言要用 IANA 写法（en / zh-CN / pt-BR 这样）");
    var parts = value.split("-");
    var out = new StringBuilder(parts[0].toLowerCase(Locale.ROOT));
    for (var i = 1; i < parts.length; i++) {
      var part = parts[i];
      if (part.length() == 2) {
        part = part.toUpperCase(Locale.ROOT);
      } else if (part.length() == 4) {
        part = part.substring(0, 1).toUpperCase(Locale.ROOT) + part.substring(1).toLowerCase(Locale.ROOT);
      } else {
        part = part.toLowerCase(Locale.ROOT);
      }
      out.append('-').append(part);
    }
    return out.toString();
  }

  private UUID currentTenant() {
    // 事务局部 GUC：租户由 AccessService 绑定，这里读的是**同一个**绑定。
    var raw =
        sql.fetchOne("select current_setting('app.current_tenant', true) as t").get(0, String.class);
    check(raw != null && !raw.isBlank(), "缺少可信租户上下文");
    return UUID.fromString(raw);
  }

  private long count(String sqlText, Object... params) {
    return sql.fetchOne(sqlText, params).get(0, Long.class);
  }

  // ================================================================ 能力声明

  /**
   * 18.5.1.1 的能力声明：逐维说清「有没有、依据是什么、缺了会怎样」。
   */
  public NotifyTemplateCapabilities capabilities() {
    var capabilities =
        List.of(
            new NotifyTemplateCapability(
                "18.5.1.1",
                "模板**版本化**（编辑 = 新增版本，老版本不可改）",
                true,
                "platform_notify_template_version 表：版本行只给 SELECT / INSERT 权限"
                    + "（迁移里没有 UPDATE / DELETE），另有 BEFORE UPDATE OR DELETE 触发器兜底",
                "",
                "改模板 = 追加一个新版本；老版本原样留着，已经发出的通知解释得清"),
            new NotifyTemplateCapability(
                "18.5.1.1",
                "**按时刻解析**当时生效的版本（历史通知保留当时内容）",
                true,
                "platform_notify_template_activation 启用事件日志 + resolve 端点："
                    + "取的是「那一刻仍在有效期内」的版本，而不是「当前版本」",
                "",
                "下游引用 version id + 内容快照，后来改模板篡改不了已发出的通知"),
            new NotifyTemplateCapability(
                "18.5.1.1",
                "同一 key × 场景 × 语言**同时只允许一个启用版本**",
                true,
                "部分唯一索引 platform_notify_template_activation_one_open "
                    + "（WHERE deactivated_at IS NULL）：第二条「生效中」的记录在数据库层写不进去",
                "",
                "启用新版本 = 旧版本区间收口 + 新增一条启用记录"),
            new NotifyTemplateCapability(
                "18.5.1.1",
                "渲染预览（**不落库**）",
                true,
                "render-preview 端点 + NotificationTemplateRenderer："
                    + "缺变量 / 多余变量 / 未知变量 / 语法错误 / 类型不符一律点名报错",
                "",
                "预览**不写库**；渲染失败时正文返回 null，绝不返回空串或残留 {{xxx}}"),
            new NotifyTemplateCapability(
                "18.5.1.1",
                "多语言版本链（同一 key × 不同 language 各有独立版本链）",
                true,
                "platform_notify_template 主记录唯一键 (tenant_id, template_key, scene, language)；"
                    + "language 走 IANA 形状 CHECK",
                "",
                "语言是语言层面的事实，不做成租户数据（与租户无关的词表不落库）"),
            new NotifyTemplateCapability(
                "18.5.1.1",
                "上游文案资产（**3.4.2 标题库 / 描述与卖点库**）",
                true,
                "CatalogContentAssetPort（catalog 服务 8082）只读端口："
                    + "只转发调用方自己的 bearer，取不到一律 503",
                "",
                "上游读不到 ≠ 没有：503 的消息里写明「是读不到，不是没有数据」"),
            new NotifyTemplateCapability(
                "18.5.1.1",
                "**通知投递与触达统计**",
                false,
                "18.5.2.1「投递记录」尚未交付（本仓没有投递结果表）",
                "18.5.2",
                "本模块只保证「按时刻取到当时那一版 + 版本 id 可引用」；"
                    + "投递结果、触达率与退订统计归 18.5.2.1"));
    var gaps =
        List.of(
            new NotifyTemplateGap(
                "1.3.1.1",
                "通知列表 / 收件箱",
                "identity",
                false,
                "下游",
                "通知落库与已读状态归 identity；本模块**不跨服务写对方库**，"
                    + "下游按 key + 场景 + 语言来取内容"),
            new NotifyTemplateGap(
                "15.1.1.2",
                "发送",
                "crm",
                false,
                "下游",
                "发送归 crm；本模块提供「内容 + 版本」这一半"),
            new NotifyTemplateGap(
                "18.5.2.1",
                "投递记录",
                "platform",
                false,
                "下游",
                "投递结果、触达率、退订统计归 18.5.2.1；本模块留出的接口是 resolve 的版本快照"));
    var conventions =
        new NotifyTemplateConventions(
            java.util.Arrays.stream(NotifyTemplateScene.values())
                .map(s -> new NotifyTemplateSceneOption(s.name(), s.label()))
                .toList(),
            "语言写 IANA 形状：2–3 位小写语种 + 可选「-地区」（如 en / zh-CN / pt-BR）",
            List.of("en", "zh-CN", "en-US", "pt-BR", "de", "ja"),
            java.util.Arrays.stream(NotifyTemplateVariableType.values())
                .map(t -> new NotifyTemplateVariableTypeOption(t.name(), t.label()))
                .toList(),
            List.of(
                new NotifyTemplateRenderErrorCode(
                    NotificationTemplateRenderer.CODE_SYNTAX,
                    "占位符没闭合、变量名非法、落单的 }}"),
                new NotifyTemplateRenderErrorCode(
                    NotificationTemplateRenderer.CODE_MISSING,
                    "声明了却没给值（也没有默认值）的变量 —— 点名列出"),
                new NotifyTemplateRenderErrorCode(
                    NotificationTemplateRenderer.CODE_UNKNOWN,
                    "正文里出现、但变量定义里没声明的变量 —— 点名列出"),
                new NotifyTemplateRenderErrorCode(
                    NotificationTemplateRenderer.CODE_EXTRA,
                    "给了变量定义里没有的取值 —— 点名列出"),
                new NotifyTemplateRenderErrorCode(
                    NotificationTemplateRenderer.CODE_INVALID_VALUE,
                    "取值与声明的类型不符（数字 / 日期 / 金额解析不了）"),
                new NotifyTemplateRenderErrorCode(
                    NotificationTemplateRenderer.CODE_INVALID_DECLARATION,
                    "变量定义本身写错了（重名 / 类型非法 / 必填又带默认值）")));
    return new NotifyTemplateCapabilities(capabilities, gaps, conventions);
  }

  /** 概览：几个数。 */
  public NotifyTemplateOverview overview() {
    return new NotifyTemplateOverview(
        count("select count(*) from platform_notify_template where archived_at is null"),
        count(
            "select count(*) from platform_notify_template t"
                + " join platform_notify_template_activation a"
                + " on a.tenant_id = t.tenant_id and a.template_id = t.id"
                + " where t.archived_at is null and a.deactivated_at is null"),
        count("select count(*) from platform_notify_template where archived_at is not null"),
        count("select count(*) from platform_notify_template_version"),
        count("select count(distinct language) from platform_notify_template"),
        count("select count(distinct scene) from platform_notify_template"),
        OffsetDateTime.now(ZoneOffset.UTC));
  }

  // ================================================================ 列表

  /**
   * 模板列表（**真分页 + 真筛选**）。
   *
   * @param keyword 模板 key 或名称（模糊）
   * @param scene 适用场景（受控词表）
   * @param language 语言（IANA 形状）
   * @param enabled 启用状态（null = 不筛）
   * @param status 归档状态：{@code ACTIVE}（未归档，默认）/ {@code ARCHIVED} / {@code ALL}
   */
  public NotifyTemplatePage list(
      String keyword,
      String scene,
      String language,
      Boolean enabled,
      String status,
      int page,
      int size) {
    var pageSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    var pageIndex = Math.max(page, 0);
    var where = new StringBuilder(" where 1 = 1");
    var params = new ArrayList<Object>();
    if (keyword != null && !keyword.isBlank()) {
      where.append(" and (t.template_key ilike ? or t.name ilike ?)");
      var like = "%" + keyword.trim() + "%";
      params.add(like);
      params.add(like);
    }
    if (scene != null && !scene.isBlank()) {
      where.append(" and t.scene = ?");
      params.add(scene.trim().toUpperCase(Locale.ROOT));
    }
    if (language != null && !language.isBlank()) {
      // 🔴 语言**大小写不敏感**：「zh-cn」与「zh-CN」说的是同一门语言，
      //   筛不出来会让运营以为「这个模板没有中文版」。
      where.append(" and lower(t.language) = lower(?)");
      params.add(language.trim());
    }
    var statusCode =
        status == null || status.isBlank() ? "ACTIVE" : status.trim().toUpperCase(Locale.ROOT);
    if ("ARCHIVED".equals(statusCode)) {
      where.append(" and t.archived_at is not null");
    } else if ("ACTIVE".equals(statusCode)) {
      where.append(" and t.archived_at is null");
    } else if (!"ALL".equals(statusCode)) {
      check(false, "归档状态只能是 ACTIVE（未归档）/ ARCHIVED（已归档）/ ALL（全部）");
    }
    if (enabled != null) {
      where.append(enabled ? " and a.version_id is not null" : " and a.version_id is null");
    }
    var total =
        sql.fetchOne(
                "select count(*) from platform_notify_template t"
                    + " left join platform_notify_template_activation a"
                    + " on a.tenant_id = t.tenant_id and a.template_id = t.id"
                    + " and a.deactivated_at is null"
                    + where,
                params.toArray())
            .get(0, Long.class);
    var rows =
        sql.fetch(
            templateSelect() + where + " order by t.template_key, t.language limit ? offset ?",
            append(params, pageSize, (long) pageIndex * pageSize));
    return new NotifyTemplatePage(
        rows.map(this::toTemplateRow),
        pageIndex,
        pageSize,
        total,
        (int) ((total + pageSize - 1) / pageSize));
  }

  /** 模板列表 / 详情的公共 SELECT（启用版本、版本数、最新版本号都在这里推出来）。 */
  private static String templateSelect() {
    return "select t.id, t.template_key, t.scene, t.language, t.name, t.description,"
        + " t.archived_at, t.created_at, t.updated_at,"
        + " a.version_id as enabled_version_id,"
        + " (select v.version_no from platform_notify_template_version v"
        + "  where v.tenant_id = t.tenant_id and v.template_id = t.id and v.id = a.version_id)"
        + "  as enabled_version_no,"
        + " (select count(*) from platform_notify_template_version v"
        + "  where v.tenant_id = t.tenant_id and v.template_id = t.id) as version_count,"
        + " (select max(v.version_no) from platform_notify_template_version v"
        + "  where v.tenant_id = t.tenant_id and v.template_id = t.id) as latest_version_no"
        + " from platform_notify_template t"
        + " left join platform_notify_template_activation a"
        + " on a.tenant_id = t.tenant_id and a.template_id = t.id and a.deactivated_at is null";
  }

  private NotifyTemplateRow toTemplateRow(org.jooq.Record r) {
    var scene = NotifyTemplateScene.parse(r.get("scene", String.class));
    var enabledVersionId = r.get("enabled_version_id", UUID.class);
    var enabledVersionNo = r.get("enabled_version_no", Integer.class);
    var latestVersionNo = r.get("latest_version_no", Integer.class);
    var archivedAt = r.get("archived_at", OffsetDateTime.class);
    return new NotifyTemplateRow(
        r.get("id", UUID.class),
        r.get("template_key", String.class),
        scene == null ? "" : scene.name(),
        scene == null ? "" : scene.label(),
        r.get("language", String.class),
        r.get("name", String.class),
        r.get("description", String.class),
        enabledVersionId != null,
        enabledVersionId,
        enabledVersionNo,
        r.get("version_count", Long.class) == null ? 0L : r.get("version_count", Long.class),
        latestVersionNo == null ? 0 : latestVersionNo,
        archivedAt != null,
        archivedAt,
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  // ================================================================ 详情

  /** 模板详情（主记录 + 版本列表 + 当前生效版本）。 */
  public NotifyTemplateDetail detail(UUID templateId) {
    var template = requireTemplate(templateId);
    var versionRows = versions(templateId, 0, MAX_PAGE_SIZE);
    var enabled = versionRows.items().stream().filter(NotifyTemplateVersionRow::active).findFirst().orElse(null);
    return new NotifyTemplateDetail(template, versionRows.items(), enabled);
  }

  /** 版本列表（真分页）。 */
  public NotifyTemplateVersionPage versions(UUID templateId, int page, int size) {
    requireTemplate(templateId);
    var pageSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    var pageIndex = Math.max(page, 0);
    var total =
        count(
            "select count(*) from platform_notify_template_version where template_id = ?", templateId);
    var rows =
        sql.fetch(
            versionSelect()
                + " where v.template_id = ? order by v.version_no desc limit ? offset ?",
            templateId,
            pageSize,
            (long) pageIndex * pageSize);
    return new NotifyTemplateVersionPage(
        rows.map(this::toVersionRow), pageIndex, pageSize, total,
        (int) ((total + pageSize - 1) / pageSize));
  }

  /** 版本详情的公共 SELECT：带上这一版的启用区间。 */
  private static String versionSelect() {
    return "select v.id, v.template_id, v.version_no, v.subject, v.body, v.variables::text as variables,"
        + " v.change_note, v.created_by, v.created_at,"
        + " (select a.activated_at from platform_notify_template_activation a"
        + "  where a.tenant_id = v.tenant_id and a.version_id = v.id and a.deactivated_at is null"
        + "  order by a.activated_at desc limit 1) as activated_at,"
        + " (select a.deactivated_at from platform_notify_template_activation a"
        + "  where a.tenant_id = v.tenant_id and a.version_id = v.id and a.deactivated_at is not null"
        + "  order by a.activated_at desc limit 1) as deactivated_at"
        + " from platform_notify_template_version v";
  }

  private NotifyTemplateVersionRow toVersionRow(org.jooq.Record r) {
    var activatedAt = r.get("activated_at", OffsetDateTime.class);
    return new NotifyTemplateVersionRow(
        r.get("id", UUID.class),
        r.get("template_id", UUID.class),
        r.get("version_no", Integer.class),
        r.get("subject", String.class),
        r.get("body", String.class),
        parseVariables(r.get("variables", String.class)),
        r.get("change_note", String.class),
        r.get("created_by", UUID.class),
        r.get("created_at", OffsetDateTime.class),
        activatedAt != null,
        activatedAt,
        r.get("deactivated_at", OffsetDateTime.class));
  }

  /** 版本详情（版本本身 + 它所属模板的 key / 场景 / 语言）。 */
  public NotifyTemplateVersionDetail versionDetail(UUID templateId, UUID versionId) {
    var template = requireTemplate(templateId);
    var row =
        sql.fetchOne(versionSelect() + " where v.template_id = ? and v.id = ?", templateId, versionId);
    check(row != null, "版本不存在");
    return new NotifyTemplateVersionDetail(
        toVersionRow(row),
        template.templateKey(),
        template.scene(),
        template.language(),
        template.name());
  }

  private NotifyTemplateRow requireTemplate(UUID templateId) {
    var row = templateRow(templateId);
    if (row == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "模板不存在");
    return row;
  }

  private NotifyTemplateRow templateRow(UUID templateId) {
    var row = sql.fetchOne(templateSelect() + " where t.id = ?", templateId);
    return row == null ? null : row.map(this::toTemplateRow);
  }

  // ================================================================ 写入

  /**
   * 新建模板（**同时**落下第 1 版内容）。
   *
   * <p>🔴 新建后**不自动启用**：启用与否是运营的决定（新建一个模板不等于它就该发出去）。
   */
  public NotifyTemplateRow create(NotifyTemplateInput input, AccessService.Access actor) {
    var key = required(input.templateKey(), "模板 key", 64).toLowerCase(Locale.ROOT);
    check(
        key.matches("^[a-z0-9][a-z0-9._-]{0,63}$"),
        "模板 key 只能是小写字母 / 数字 / 点 / 下划线 / 连字符，且必须以字母或数字开头");
    var scene = NotifyTemplateScene.parse(input.scene());
    check(scene != null, "适用场景只能是 ORDER_STATUS / STOCK / PRICE / LOGISTICS / CUSTOMER_SERVICE / MARKETING / SYSTEM");
    var language = normalizeLanguage(required(input.language(), "语言", 16));
    var name = required(input.name(), "模板名称", 120);
    var description = optional(input.description(), "模板说明", 500);

    var existing =
        sql.fetchOne(
            "select id from platform_notify_template"
                + " where template_key = ? and scene = ? and language = ?",
            key,
            scene.name(),
            language);
    check(existing == null, "同一个 key × 场景 × 语言已经有模板了（改内容请新增版本，不要新建）");

    var content =
        validateContent(input.subject(), input.body(), input.variables(), input.changeNote());
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    var templateId = UUID.randomUUID();
    sql.execute(
        "insert into platform_notify_template(id,tenant_id,template_key,scene,language,name,description,"
            + " created_by,created_at,updated_at)"
            + " values(?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz)",
        templateId,
        currentTenant(),
        key,
        scene.name(),
        language,
        name,
        description,
        actor.userId(),
        now,
        now);
    insertVersion(templateId, 1, content, actor, now);
    return templateRow(templateId);
  }

  /**
   * 新增版本（= 编辑）。
   *
   * <p>🔴 **只追加**一个版本行，老版本一个字节都不动。
   *
   * <p>🔴 **新版本会不会立刻生效**：模板**当前有生效版本**时 ⇒ 新版本接管（旧的区间收口），
   * 这正是蓝图那句「修改后新产生的通知使用新版本」；模板**当前是停用的**时 ⇒ 新版本
   * 仍然是停用的（新建/改内容不等于「可以发出去」），要发得显式启用。
   */
  public NotifyTemplateVersionDetail appendVersion(
      UUID templateId, NotifyTemplateVersionInput input, AccessService.Access actor) {
    var template = requireTemplate(templateId);
    check(template.archivedAt() == null, "模板已归档，不能再新增版本");
    var content =
        validateContent(input.subject(), input.body(), input.variables(), input.changeNote());
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    var nextVersionNo =
        sql.fetchOne(
                "select coalesce(max(version_no), 0) from platform_notify_template_version"
                    + " where template_id = ?",
                templateId)
            .get(0, Integer.class)
            + 1;
    var versionId = insertVersion(templateId, nextVersionNo, content, actor, now);

    // 模板当前有生效版本 ⇒ 新版本接管（旧的区间收口）。
    if (template.enabledVersionId() != null) {
      activate(templateId, versionId, actor, now);
    }
    return versionDetail(templateId, versionId);
  }

  /** 改模板的名称与说明（**内容只能新增版本**，这里改不了内容）。 */
  public NotifyTemplateRow updateMetadata(UUID templateId, NotifyTemplateMetadataInput input, AccessService.Access actor) {
    var template = requireTemplate(templateId);
    check(template.archivedAt() == null, "模板已归档，不能再修改");
    var name = required(input.name(), "模板名称", 120);
    var description = optional(input.description(), "模板说明", 500);
    sql.execute(
        "update platform_notify_template set name = ?, description = ?, updated_at = ?::timestamptz"
            + " where id = ?",
        name,
        description,
        OffsetDateTime.now(ZoneOffset.UTC),
        templateId);
    return templateRow(templateId);
  }

  /**
   * 启用某个版本。
   *
   * <p>🔴 启用 = 把当前生效版本的区间收口 + 追加一条新的启用记录（**不改版本内容**）。
   */
  public NotifyTemplateDetail enableVersion(UUID templateId, UUID versionId, AccessService.Access actor) {
    var template = requireTemplate(templateId);
    check(template.archivedAt() == null, "模板已归档，不能再启用");
    var version =
        sql.fetchOne(
            "select id from platform_notify_template_version where template_id = ? and id = ?",
            templateId,
            versionId);
    check(version != null, "版本不存在");
    activate(templateId, versionId, actor, OffsetDateTime.now(ZoneOffset.UTC));
    return detail(templateId);
  }

  /**
   * 停用某个版本。
   *
   * <p>🔴 **版本行本身不动** —— 只是把它的「生效区间」收口，历史版本原样留着。
   */
  public NotifyTemplateDetail disableVersion(UUID templateId, UUID versionId, AccessService.Access actor) {
    var template = requireTemplate(templateId);
    var open =
        sql.fetchOne(
            "select id, version_id from platform_notify_template_activation"
                + " where template_id = ? and deactivated_at is null",
            templateId);
    check(open != null, "这个模板当前没有生效的版本");
    if (!versionId.equals(open.get("version_id", UUID.class))) {
      conflict("这个版本当前没有生效，停用它没有意义（要停用请停用当前生效的版本）");
    }
    sql.execute(
        "update platform_notify_template_activation set deactivated_at = ?::timestamptz where id = ?",
        nextMoment(templateId),
        open.get("id", UUID.class));
    return detail(templateId);
  }

  /**
   * 删除模板 = **停用 + 归档**（**不物理删**）。
   *
   * <p>🔴 版本行与启用历史原样留着：已经发出的通知要能按「当时的内容」解释清楚，
   * 删掉版本就等于让那段历史说不清了。
   */
  public NotifyTemplateRow archive(UUID templateId, AccessService.Access actor) {
    var template = requireTemplate(templateId);
    check(template.archivedAt() == null, "模板已经归档过了");
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    sql.execute(
        "update platform_notify_template_activation set deactivated_at = ?::timestamptz"
            + " where template_id = ? and deactivated_at is null",
        now,
        templateId);
    sql.execute(
        "update platform_notify_template set archived_at = ?::timestamptz, updated_at = ?::timestamptz"
            + " where id = ?",
        now,
        now,
        templateId);
    return templateRow(templateId);
  }

  // ================================================================ 按时刻解析

  /**
   * **按时刻**解析当时生效的那一版。
   *
   * <p>🔴 这就是蓝图那句「历史通知保留当时内容」的落地：返回的是
   * {@code versionId + 内容快照}，不是「当前版本」。
   *
   * @param templateKey 模板 key
   * @param scene 适用场景
   * @param language 语言
   * @param at 时刻（timestamptz）
   */
  public NotifyTemplateResolved resolve(
      String templateKey, String scene, String language, OffsetDateTime at) {
    // 🔴 key 存的是**小写**机器名（迁移里的 CHECK 只允许小写），所以这里也要小写化 ——
    //   否则上游用大写 key 来取会 404，而那不是「没有这个模板」。
    var key = required(templateKey, "模板 key", 64).toLowerCase(Locale.ROOT);
    var sceneCode = NotifyTemplateScene.parse(scene);
    check(sceneCode != null, "适用场景只能是 ORDER_STATUS / STOCK / PRICE / LOGISTICS / CUSTOMER_SERVICE / MARKETING / SYSTEM");
    var lang = required(language, "语言", 16);
    var moment = at == null ? OffsetDateTime.now(ZoneOffset.UTC) : at;

    var template =
        sql.fetchOne(
            "select id from platform_notify_template"
                + " where template_key = ? and scene = ? and lower(language) = lower(?)",
            key,
            sceneCode.name(),
            lang);
    if (template == null) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, "没有这个模板（key × 场景 × 语言）：" + key + " / " + sceneCode.name() + " / " + lang);
    }
    var templateId = template.get("id", UUID.class);

    var row =
        sql.fetchOne(
            "select v.id, v.version_no, v.subject, v.body, v.variables::text as variables,"
                + " a.activated_at, a.deactivated_at"
                + " from platform_notify_template_activation a"
                + " join platform_notify_template_version v"
                + " on v.tenant_id = a.tenant_id and v.id = a.version_id"
                + " where a.template_id = ? and a.activated_at <= ?::timestamptz"
                + " and (a.deactivated_at is null or a.deactivated_at > ?::timestamptz)"
                + " order by a.activated_at desc limit 1",
            templateId,
            moment,
            moment);
    if (row == null) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND,
          "在 " + moment + " 那一刻，这个模板**没有生效的版本**（后来才启用的那一版不算数）");
    }
    return new NotifyTemplateResolved(
        templateId,
        row.get("id", UUID.class),
        row.get("version_no", Integer.class),
        key,
        sceneCode.name(),
        lang,
        row.get("subject", String.class),
        row.get("body", String.class),
        parseVariables(row.get("variables", String.class)),
        moment,
        row.get("activated_at", OffsetDateTime.class),
        row.get("deactivated_at", OffsetDateTime.class));
  }

  // ================================================================ 渲染预览

  /**
   * 渲染预览（**不落库**）。
   *
   * <p>两种用法：预览**已保存**的某一版（传 templateId + versionId），
   * 或预览**还没保存**的草稿内容（传 subject / body / variables）。
   */
  public NotifyTemplateRenderResult renderPreview(NotifyTemplateRenderInput input) {
    var subject = input.subject() == null ? "" : input.subject();
    var body = input.body() == null ? "" : input.body();
    var definitions = input.variables() == null ? List.<NotifyTemplateVariableDefinition>of() : input.variables();
    UUID templateId = null;
    UUID versionId = null;
    String templateKey = null;
    String language = null;
    Integer versionNo = null;

    var hasSavedRef = notBlank(input.templateId()) || notBlank(input.versionId());
    if (hasSavedRef) {
      var savedTemplateId = parseUuid(input.templateId(), "模板 id");
      var savedVersionId = parseUuid(input.versionId(), "版本 id");
      check(savedTemplateId != null, "预览已保存的版本时，templateId 与 versionId 要一起给");
      check(savedVersionId != null, "预览已保存的版本时，templateId 与 versionId 要一起给");
      var version =
          sql.fetchOne(
              versionSelect() + " where v.template_id = ? and v.id = ?", savedTemplateId, savedVersionId);
      check(version != null, "版本不存在");
      var template = requireTemplate(savedTemplateId);
      subject = version.get("subject", String.class);
      body = version.get("body", String.class);
      definitions = parseVariables(version.get("variables", String.class));
      templateId = savedTemplateId;
      versionId = savedVersionId;
      templateKey = template.templateKey();
      language = template.language();
      versionNo = version.get("version_no", Integer.class);
    } else {
      check(!body.isBlank(), "正文不能为空");
      var errors = NotificationTemplateRenderer.validateUsage(subject, body, definitions);
      if (!errors.isEmpty()) {
        return new NotifyTemplateRenderResult(
            false,
            errors.get(0).message(),
            null,
            null,
            definitions,
            errors,
            null,
            null,
            null,
            null,
            null);
      }
    }

    return NotificationTemplateRenderer.render(
        subject, body, definitions, input.values(), templateId, versionId, templateKey, language, versionNo);
  }

  // ================================================================ 上游（3.4.2 文案资产）

  /**
   * 上游文案资产（catalog 服务 3.4.2 标题库 / 描述与卖点库）。
   *
   * <p>⚠️ **只读、不抄**：上游读不到就如实报 503（PlatformUpstreamClient 已经这么做了），
   * 绝不返回空集合冒充「上游没有文案资产」。
   */
  public NotifyTemplateContentAssetView contentAssets(String bearer) {
    return catalog.contentAssets(bearer);
  }

  // ================================================================ 内部

  /** 一版内容的校验结果。 */
  private record Content(
      String subject, String body, List<NotifyTemplateVariableDefinition> variables, String changeNote) {}

  /**
   * 校验一版内容：长度、变量定义、正文与变量定义是否对得上。
   *
   * <p>🔴 正文里出现、但没声明的变量 ⇒ **拒收**（400，点名哪个）。
   * 那样的模板存进去，将来渲染必然失败 —— 与其渲染时才发现，不如登记时就挡住。
   */
  private Content validateContent(
      String subject, String body, List<NotifyTemplateVariableDefinition> variables, String changeNote) {
    var subjectText = optionalText(subject, "标题", MAX_CONTENT_LENGTH);
    var bodyText = optionalText(body, "正文", MAX_CONTENT_LENGTH);
    check(!bodyText.isBlank(), "正文不能为空");
    var note = optional(changeNote, "改动说明", 500);
    var definitions = variables == null ? List.<NotifyTemplateVariableDefinition>of() : variables;
    var errors = NotificationTemplateRenderer.validateUsage(subjectText, bodyText, definitions);
    if (!errors.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, errors.get(0).message());
    }
    return new Content(subjectText, bodyText, definitions, note);
  }

  /** 落一行新版本（**只 INSERT**，永不 UPDATE）。 */
  private UUID insertVersion(
      UUID templateId,
      int versionNo,
      Content content,
      AccessService.Access actor,
      OffsetDateTime now) {
    var versionId = UUID.randomUUID();
    sql.execute(
        "insert into platform_notify_template_version(id,tenant_id,template_id,version_no,subject,"
            + " body,variables,change_note,created_by,created_at)"
            + " values(?,?,?,?,?,?,?::jsonb,?,?,?::timestamptz)",
        versionId,
        currentTenant(),
        templateId,
        versionNo,
        content.subject(),
        content.body(),
        variablesJson(content.variables()),
        content.changeNote(),
        actor.userId(),
        now);
    return versionId;
  }

  /**
   * 启用某个版本：旧区间收口 + 追加一条启用记录。
   *
   * <p>🔴 启用时刻**严格递增**：同一事务里连着启用两次时 {@code now()} 是同一个值，
   * 「取最新一条」就会取到不确定的那一条 ⇒ 这里显式把时刻往后推 1 微秒。
   */
  private void activate(UUID templateId, UUID versionId, AccessService.Access actor, OffsetDateTime now) {
    var activatedAt = nextMoment(templateId, now);
    sql.execute(
        "update platform_notify_template_activation set deactivated_at = ?::timestamptz"
            + " where template_id = ? and deactivated_at is null",
        activatedAt,
        templateId);
    sql.execute(
        "insert into platform_notify_template_activation(id,tenant_id,template_id,version_id,"
            + " activated_at,activated_by) values(?,?,?,?,?::timestamptz,?)",
        UUID.randomUUID(),
        currentTenant(),
        templateId,
        versionId,
        activatedAt,
        actor.userId());
  }

  /**
   * 取一个**严格递增**的时刻。
   *
   * <p>🔴 同一事务里 {@code now()} 是同一个值，两次启用会拿到同一个时刻 ——
   * 「取生效区间覆盖 T 的那一条」就会变成不确定的。所以这里显式往后推 1 微秒。
   */
  private OffsetDateTime nextMoment(UUID templateId) {
    return nextMoment(templateId, OffsetDateTime.now(ZoneOffset.UTC));
  }

  private OffsetDateTime nextMoment(UUID templateId, OffsetDateTime now) {
    var latestRow =
        sql.fetchOne(
            "select activated_at from platform_notify_template_activation"
                + " where template_id = ? order by activated_at desc limit 1",
            templateId);
    var latest = latestRow == null ? null : latestRow.get("activated_at", OffsetDateTime.class);
    if (latest != null && !now.isAfter(latest)) {
      return latest.plusNanos(1000);
    }
    return now;
  }

  private String variablesJson(List<NotifyTemplateVariableDefinition> variables) {
    try {
      return json.writeValueAsString(variables == null ? List.of() : variables);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  private List<NotifyTemplateVariableDefinition> parseVariables(String raw) {
    if (raw == null || raw.isBlank()) return List.of();
    try {
      return json.readValue(raw, new TypeReference<List<NotifyTemplateVariableDefinition>>() {});
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      return List.of();
    }
  }

  private static boolean notBlank(String value) {
    return value != null && !value.isBlank();
  }

  private static UUID parseUuid(String value, String label) {
    if (!notBlank(value)) return null;
    try {
      return UUID.fromString(value.trim());
    } catch (IllegalArgumentException e) {
      // 🔴 客户端给的 id 形状不对要报 400，不能一路走到 UUID.fromString 抛异常变成 500。
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + " 不是合法的 UUID");
    }
  }

  private static Object[] append(List<Object> params, Object... extra) {
    var out = new ArrayList<>(params);
    for (var item : extra) {
      out.add(item);
    }
    return out.toArray();
  }

  /** 供测试与其它模块复用的变量名提取（内容里到底写了哪些变量）。 */
  public static List<String> placeholderNames(String subject, String body) {
    return NotificationTemplateRenderer.placeholderNames(subject, body);
  }
}
