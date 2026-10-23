package com.starcon.identity;

import static com.starcon.identity.ShortcutTemplateModels.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 1.2.2.2 快捷入口配置 —— 岗位模板与个人覆盖项的读写，以及两者的合并。
 *
 * <p><b>蓝图原文</b>：
 *
 * <blockquote>
 *
 * <b>职责</b>：按角色/团队预置快捷入口模板，新成员加入时自动获得岗位常用入口，减少培训成本。
 * <b>输入</b>：17 系统配置的岗位模板；用户所属部门/角色；用户个人覆盖项。
 * <b>输出</b>：最终生效的快捷入口集合（模板 + 个人覆盖的合并结果）。
 *
 * </blockquote>
 *
 * <p><b>合并规则（确定、可测，不依赖插入顺序）</b>：
 *
 * <ol>
 *   <li><b>命中哪些模板</b>：当前租户内 ACTIVE 的模板，scope 为 TENANT（全员）、
 *       ROLE（命中我持有的任一岗位）或 DEPARTMENT（等于我所属部门）。
 *       多个模板同时命中时按 {@code (priority 升序, code 升序)} 排序 —— <b>先到的赢</b>。
 *   <li><b>模板内排序</b>：同一张模板里按 {@code (position 升序, key 升序)}。
 *   <li><b>跨模板去重</b>：按入口标识 {@code key} 去重，<b>第一次出现的那条赢</b>
 *       （即优先级高的模板覆盖优先级低的模板里的同名入口）。
 *   <li><b>个人覆盖</b>：按 {@code targetKey} 命中第 3 步的结果。
 *       {@code REMOVE} ⇒ 该入口不生效（<b>个人移除优先于模板存在</b>）；
 *       {@code UPSERT} ⇒ 逐字段覆盖（未给的字段沿用模板项的值），整条入口仍然生效。
 *   <li><b>权限闸</b>：入口的目标页面 {@code pageCode} 对应的 {@code <pageCode>.read}
 *       不在我的权限集合里 ⇒ <b>该入口不生效</b>（模板里的入口若指向无权访问的页面，
 *       它就不该出现在工作台上）。权限变更后自动失效，不需要额外同步任务。
 *   <li><b>最终排序</b>：{@code (position 升序, key 升序)}。
 * </ol>
 *
 * <p><b>为什么覆盖项不能凭空新增入口</b>：本模块**不实现 1.2.2.1 常用页面收藏**。
 * 允许覆盖项新增模板里没有的入口，就等于把「收藏」又实现了一遍（同一个能力两套真相）。
 * 所以写入覆盖项时目标必须命中「我当前生效集合」里真实存在的入口，否则 400。
 */
@Service
public class ShortcutTemplateService {

  /** 合并规则的原文：接口把它原样下发，界面上要能让人看懂为什么是这个结果。 */
  static final List<String> MERGE_RULES =
      List.of(
          "命中模板：当前租户内 ACTIVE 的模板，scope 为 TENANT（全员）/ ROLE（命中我的岗位）/ DEPARTMENT（等于我的部门）。",
          "多模板同时命中时按 (priority 升序, code 升序) 排序，先到的赢。",
          "同一张模板内按 (position 升序, key 升序) 排序。",
          "跨模板按入口标识 key 去重，第一次出现的那条赢。",
          "个人覆盖按 targetKey 命中上面的结果：REMOVE 直接移除（个人移除优先于模板存在），UPSERT 逐字段覆盖（未给字段沿用模板项）。",
          "入口的目标页面 pageCode 对应的 <pageCode>.read 不在我的权限里时，该入口不生效（权限变更后自动失效并回落）。",
          "最终按 (position 升序, key 升序) 排序。");

  private static final int MAX_LIMIT = 100;
  private static final int MAX_ENTRIES = 100;

  private static final Set<String> SCOPE_TYPES = Set.of("TENANT", "ROLE", "DEPARTMENT");
  private static final Set<String> STATUSES = Set.of("ACTIVE", "INACTIVE");
  private static final Set<String> ACTIONS = Set.of("UPSERT", "REMOVE");

  private final DSLContext sql;
  private final ObjectMapper mapper;

  public ShortcutTemplateService(DSLContext sql, ObjectMapper mapper) {
    this.sql = sql;
    this.mapper = mapper;
  }

  // ================================================================= 模板面

  /** 模板列表（真分页 + 真筛选）。 */
  public ShortcutTemplateList listTemplates(
      UUID tenantId, String scopeType, String status, int limit, int offset) {
    int size = Math.min(Math.max(limit, 1), MAX_LIMIT);
    int skip = Math.max(offset, 0);

    String where = " where tenant_id = ?::uuid";
    List<Object> binds = new ArrayList<>();
    binds.add(tenantId);
    if (scopeType != null && !scopeType.isBlank()) {
      where += " and scope_type = ?::varchar";
      binds.add(scopeType.trim());
    }
    if (status != null && !status.isBlank()) {
      where += " and status = ?::varchar";
      binds.add(status.trim());
    }

    Integer total =
        sql.fetchOne("select count(*) from shortcut_entry_template" + where, binds.toArray())
            .get(0, Integer.class);

    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(size);
    pageBinds.add(skip);
    List<ShortcutTemplate> templates = new ArrayList<>();
    for (Record r :
        sql.fetch(
            "select id, code, name, description, scope_type, role_id, department_id, status, priority,"
                + " entries::text as entries_text, created_by, created_at, updated_at"
                + " from shortcut_entry_template"
                + where
                + " order by priority asc, code asc"
                + " limit ? offset ?",
            pageBinds.toArray())) {
      templates.add(readTemplate(r));
    }
    return new ShortcutTemplateList(templates, total == null ? 0 : total, size, skip);
  }

  /** 单张模板；不存在就是 404（不存在与无权看见在这里是同一件事，不区分）。 */
  public ShortcutTemplate getTemplate(UUID tenantId, UUID id) {
    Record r =
        sql.fetchOne(
            "select id, code, name, description, scope_type, role_id, department_id, status, priority,"
                + " entries::text as entries_text, created_by, created_at, updated_at"
                + " from shortcut_entry_template"
                + " where tenant_id = ?::uuid and id = ?::uuid",
            tenantId,
            id);
    if (r == null) throw notFound("快捷入口模板不存在");
    return readTemplate(r);
  }

  /** 新建模板。 */
  public ShortcutTemplate createTemplate(
      UUID tenantId, UUID actorMembershipId, ShortcutTemplateInput input) {
    ShortcutTemplateInput normalized = validateTemplateInput(tenantId, input, true);
    UUID id = UUID.randomUUID();
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    sql.fetch(
        "insert into shortcut_entry_template"
            + "(id,tenant_id,code,name,description,scope_type,role_id,department_id,status,priority,"
            + "entries,created_by,created_at,updated_at)"
            + " values (?::uuid,?::uuid,?::varchar,?::varchar,?::varchar,?::varchar,?::uuid,?::uuid,"
            + "?::varchar,?::int,?::jsonb,?::uuid,?::timestamptz,?::timestamptz)",
        id,
        tenantId,
        normalized.code(),
        normalized.name(),
        normalized.description(),
        normalized.scopeType(),
        normalized.roleId(),
        normalized.departmentId(),
        normalized.status(),
        normalized.priority(),
        writeEntries(normalized.entries()),
        actorMembershipId,
        now,
        now);
    return getTemplate(tenantId, id);
  }

  /** 改模板（整体替换入口清单）。 */
  public ShortcutTemplate updateTemplate(
      UUID tenantId, UUID actorMembershipId, UUID id, ShortcutTemplateInput input) {
    getTemplate(tenantId, id);
    ShortcutTemplateInput normalized = validateTemplateInput(tenantId, input, false);
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    int updated =
        sql.fetch(
                "update shortcut_entry_template set"
                    + " name = ?::varchar, description = ?::varchar, scope_type = ?::varchar,"
                    + " role_id = ?::uuid, department_id = ?::uuid, status = ?::varchar,"
                    + " priority = ?::int, entries = ?::jsonb, updated_at = ?::timestamptz"
                    + " where tenant_id = ?::uuid and id = ?::uuid",
                normalized.name(),
                normalized.description(),
                normalized.scopeType(),
                normalized.roleId(),
                normalized.departmentId(),
                normalized.status(),
                normalized.priority(),
                writeEntries(normalized.entries()),
                now,
                tenantId,
                id)
            .size();
    if (updated == 0) throw notFound("快捷入口模板不存在");
    return getTemplate(tenantId, id);
  }

  /** 删模板。个人覆盖项挂在入口标识上，不跟着删 —— 它们自动变成孤儿（不产生任何入口）。 */
  public void deleteTemplate(UUID tenantId, UUID id) {
    int deleted =
        sql.fetch("delete from shortcut_entry_template where tenant_id = ?::uuid and id = ?::uuid", tenantId, id)
            .size();
    if (deleted == 0) throw notFound("快捷入口模板不存在");
  }

  // ============================================================== 个人覆盖面

  /** 当前这个人的覆盖项列表。 */
  public List<ShortcutOverride> listOverrides(UUID tenantId, UUID membershipId) {
    List<ShortcutOverride> overrides = new ArrayList<>();
    for (Record r :
        sql.fetch(
            "select id, target_key, action, label, page_code, path, icon, position, created_at, updated_at"
                + " from user_shortcut_override"
                + " where tenant_id = ?::uuid and membership_id = ?::uuid"
                + " order by target_key",
            tenantId,
            membershipId)) {
      overrides.add(readOverride(r));
    }
    return overrides;
  }

  /**
   * 写一条个人覆盖项（同一个 targetKey 重复提交是覆盖，不是叠加）。
   *
   * <p>目标必须命中「我当前生效集合」里真实存在的入口：覆盖项只能改模板给的入口，
   * 不能凭空新增（新增是 1.2.2.1 收藏的职责）。
   */
  public ShortcutOverride upsertOverride(
      UUID tenantId,
      UUID membershipId,
      List<UUID> roleIds,
      UUID departmentId,
      Set<String> permissions,
      ShortcutOverrideInput input) {
    ShortcutOverrideInput normalized = validateOverrideInput(input);
    Set<String> available = effectiveKeys(tenantId, roleIds, departmentId, permissions);
    if (!available.contains(normalized.targetKey())) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "覆盖项必须命中当前生效集合里的入口（新增入口是常用页面收藏的职责，本模块不提供）");
    }

    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    UUID id = UUID.randomUUID();
    sql.fetch(
        "insert into user_shortcut_override"
            + "(id,tenant_id,membership_id,target_key,action,label,page_code,path,icon,position,"
            + "created_at,updated_at)"
            + " values (?::uuid,?::uuid,?::uuid,?::varchar,?::varchar,?::varchar,?::varchar,"
            + "?::varchar,?::varchar,?::int,?::timestamptz,?::timestamptz)"
            + " on conflict (tenant_id,membership_id,target_key) do update set"
            + " action = excluded.action, label = excluded.label, page_code = excluded.page_code,"
            + " path = excluded.path, icon = excluded.icon, position = excluded.position,"
            + " updated_at = excluded.updated_at",
        id,
        tenantId,
        membershipId,
        normalized.targetKey(),
        normalized.action(),
        normalized.label(),
        normalized.pageCode(),
        normalized.path(),
        normalized.icon(),
        normalized.position(),
        now,
        now);

    Record r =
        sql.fetchOne(
            "select id, target_key, action, label, page_code, path, icon, position, created_at, updated_at"
                + " from user_shortcut_override"
                + " where tenant_id = ?::uuid and membership_id = ?::uuid and target_key = ?::varchar",
            tenantId,
            membershipId,
            normalized.targetKey());
    return readOverride(r);
  }

  /** 删掉自己的覆盖项（别人的覆盖项在这里一律 404 —— 不泄露「这条覆盖项存在」）。 */
  public void deleteOverride(UUID tenantId, UUID membershipId, UUID id) {
    int deleted =
        sql.fetch(
                "delete from user_shortcut_override"
                    + " where tenant_id = ?::uuid and membership_id = ?::uuid and id = ?::uuid",
                tenantId,
                membershipId,
                id)
            .size();
    if (deleted == 0) throw notFound("个人覆盖项不存在");
  }

  // ================================================================= 合并结果

  /**
   * 当前登录用户最终生效的快捷入口集合。
   *
   * @param roleIds 我持有的岗位（来自已认证会话的授权，不是请求参数）
   * @param permissions 我实际持有的权限码（来自已认证会话）
   */
  public EffectiveShortcutSet effective(
      UUID tenantId,
      UUID membershipId,
      List<UUID> roleIds,
      UUID departmentId,
      Set<String> permissions) {

    // ① 命中模板，按 (priority, code) 排序。
    List<Record> templates = applicableTemplates(tenantId, roleIds, departmentId);

    // ②③ 跨模板按 key 去重，先到的赢；模板内按 (position, key) 排序。
    LinkedHashMap<String, EffectiveShortcut> merged = mergeTemplates(templates);

    // ④ 权限闸：指向无权访问的页面的入口不生效。
    List<EffectiveShortcut> permitted = new ArrayList<>();
    int hiddenByPermission = 0;
    for (EffectiveShortcut entry : merged.values()) {
      if (permissions.contains(entry.pageCode() + ".read")) {
        permitted.add(entry);
      } else {
        hiddenByPermission++;
      }
    }

    // ⑤ 个人覆盖：REMOVE 移除（优先于模板存在），UPSERT 逐字段覆盖。
    List<ShortcutOverride> overrides = listOverrides(tenantId, membershipId);
    Map<String, ShortcutOverride> byKey = new LinkedHashMap<>();
    for (ShortcutOverride override : overrides) {
      byKey.put(override.targetKey(), override);
    }

    List<EffectiveShortcut> result = new ArrayList<>();
    int removedByOverride = 0;
    for (EffectiveShortcut entry : permitted) {
      ShortcutOverride override = byKey.get(entry.key());
      if (override == null) {
        result.add(entry);
        continue;
      }
      if ("REMOVE".equals(override.action())) {
        removedByOverride++;
        continue;
      }
      result.add(
          new EffectiveShortcut(
              entry.key(),
              override.label() != null ? override.label() : entry.label(),
              override.pageCode() != null ? override.pageCode() : entry.pageCode(),
              override.path() != null ? override.path() : entry.path(),
              override.icon() != null ? override.icon() : entry.icon(),
              override.position() != null ? override.position() : entry.position(),
              entry.source(),
              entry.sourceTemplateCode(),
              true));
    }

    // ⑥ 最终排序。
    result.sort(
        Comparator.comparingInt(EffectiveShortcut::position)
            .thenComparing(EffectiveShortcut::key));

    return new EffectiveShortcutSet(
        result, templates.size(), overrides.size(), removedByOverride, hiddenByPermission, MERGE_RULES);
  }

  /** 能力声明。 */
  public ShortcutCapabilities capabilities() {
    return new ShortcutCapabilities(
        true,
        true,
        true,
        true,
        false,
        "1.2.2.1 常用页面收藏",
        MERGE_RULES,
        List.of(
            "本模块只产出「最终生效的快捷入口集合」；收藏列表的渲染与拖拽排序属于 1.2.2.1，本模块不实现、也不造收藏表。",
            "覆盖项只能覆盖模板给的入口，不能凭空新增（新增是收藏的职责）。",
            "模板是租户级配置（tenant_id NOT NULL），不是平台级；同租户内互不可见。",
            "入口若指向当前用户无权访问的页面则不生效；权限变更后自动失效并回落，不需要同步任务。"));
  }

  // ================================================================== 内部实现

  /** 命中当前用户的模板，按 (priority, code) 排好序。 */
  private List<Record> applicableTemplates(UUID tenantId, List<UUID> roleIds, UUID departmentId) {
    StringBuilder sqlText =
        new StringBuilder(
            "select code, name, priority, entries::text as entries_text"
                + " from shortcut_entry_template"
                + " where tenant_id = ?::uuid and status = 'ACTIVE'");
    List<Object> binds = new ArrayList<>();
    binds.add(tenantId);

    sqlText.append(" and (scope_type = 'TENANT'");
    if (roleIds != null && !roleIds.isEmpty()) {
      sqlText.append(" or (scope_type = 'ROLE' and role_id in (");
      for (int i = 0; i < roleIds.size(); i++) {
        if (i > 0) sqlText.append(",");
        sqlText.append("?::uuid");
        binds.add(roleIds.get(i));
      }
      sqlText.append("))");
    }
    if (departmentId != null) {
      sqlText.append(" or (scope_type = 'DEPARTMENT' and department_id = ?::uuid)");
      binds.add(departmentId);
    }
    sqlText.append(") order by priority asc, code asc");

    return sql.fetch(sqlText.toString(), binds.toArray());
  }

  /** 当前用户「生效集合」里的入口标识（经权限过滤，未套个人覆盖）。 */
  private Set<String> effectiveKeys(
      UUID tenantId, List<UUID> roleIds, UUID departmentId, Set<String> permissions) {
    Set<String> keys = new LinkedHashSet<>();
    for (EffectiveShortcut entry : mergeTemplates(applicableTemplates(tenantId, roleIds, departmentId))
        .values()) {
      if (permissions.contains(entry.pageCode() + ".read")) keys.add(entry.key());
    }
    return keys;
  }

  /**
   * 模板侧合并：跨模板按入口标识去重，第一次出现的那条赢。
   *
   * <p>模板已按 {@code (priority 升序, code 升序)} 排好序，模板内按 {@code (position 升序, key 升序)} ——
   * 所以「谁赢」完全确定，不依赖插入顺序，也不依赖查询顺序。
   */
  private LinkedHashMap<String, EffectiveShortcut> mergeTemplates(List<Record> templates) {
    LinkedHashMap<String, EffectiveShortcut> merged = new LinkedHashMap<>();
    for (Record r : templates) {
      String code = r.get("code", String.class);
      for (ShortcutEntry entry : sortEntries(readEntries(r))) {
        if (merged.containsKey(entry.key())) continue;
        merged.put(
            entry.key(),
            new EffectiveShortcut(
                entry.key(),
                entry.label(),
                entry.pageCode(),
                entry.path(),
                entry.icon(),
                entry.position() == null ? 100 : entry.position(),
                "TEMPLATE",
                code,
                false));
      }
    }
    return merged;
  }

  /** 模板入参校验：范围形状、岗位/部门存在性、入口形状与目标页面是否真的注册过。 */
  private ShortcutTemplateInput validateTemplateInput(
      UUID tenantId, ShortcutTemplateInput input, boolean creating) {
    if (input == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "模板内容不能为空");
    String code = trimToEmpty(input.code());
    if (creating) {
      if (code.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "模板编码不能为空");
      if (!code.matches("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$"))
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "模板编码只能包含字母、数字与 . _ -，且必须以字母或数字开头");
    }
    String name = trimToEmpty(input.name());
    if (name.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "模板名称不能为空");
    if (name.length() > 100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "模板名称长度不能超过 100 个字符");
    String description = input.description() == null ? "" : input.description().trim();
    if (description.length() > 200) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "模板说明长度不能超过 200 个字符");

    String scopeType = input.scopeType() == null ? "" : input.scopeType().trim();
    if (!SCOPE_TYPES.contains(scopeType))
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "适用范围只能是 TENANT / ROLE / DEPARTMENT");

    UUID roleId = null;
    UUID departmentId = null;
    if ("ROLE".equals(scopeType)) {
      if (input.roleId() == null)
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "岗位模板必须指定岗位（roleId）");
      if (!roleExists(tenantId, input.roleId()))
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "岗位不存在或不属于当前租户");
      roleId = input.roleId();
    } else if ("DEPARTMENT".equals(scopeType)) {
      if (input.departmentId() == null)
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "团队模板必须指定部门（departmentId）");
      if (!departmentExists(tenantId, input.departmentId()))
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "部门不存在或不属于当前租户");
      departmentId = input.departmentId();
    }

    String status = input.status() == null || input.status().isBlank() ? "ACTIVE" : input.status().trim();
    if (!STATUSES.contains(status))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "状态只能是 ACTIVE / INACTIVE");

    int priority = input.priority() == null ? 100 : input.priority();
    if (priority < 0 || priority > 9999)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "优先级必须在 0 到 9999 之间");

    List<ShortcutEntry> entries = input.entries() == null ? List.of() : input.entries();
    if (entries.size() > MAX_ENTRIES)
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "一张模板最多配置 " + MAX_ENTRIES + " 个快捷入口");
    List<ShortcutEntry> normalized = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    for (ShortcutEntry entry : entries) {
      normalized.add(validateEntry(tenantId, entry, seen));
    }

    return new ShortcutTemplateInput(
        code, name, description, scopeType, roleId, departmentId, status, priority, normalized);
  }

  /** 一条入口的形状与目标页面校验。 */
  private ShortcutEntry validateEntry(UUID tenantId, ShortcutEntry entry, Set<String> seen) {
    if (entry == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "快捷入口不能为空");
    String key = trimToEmpty(entry.key());
    if (!key.matches("^[a-z0-9][a-z0-9._-]{0,99}$"))
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "入口标识只能是小写字母、数字与 . _ -，且必须以字母或数字开头");
    if (!seen.add(key))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "同一张模板里的入口标识不能重复：" + key);

    String label = trimToEmpty(entry.label());
    if (label.isEmpty() || label.length() > 100)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "入口标题长度必须在 1 到 100 个字符之间");

    String pageCode = trimToEmpty(entry.pageCode());
    if (pageCode.isEmpty() || pageCode.length() > 64)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "入口目标页面码长度必须在 1 到 64 个字符之间");
    if (!pageIsRegistered(pageCode))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "入口目标页面未注册：" + pageCode);

    String path = trimToEmpty(entry.path());
    if (!path.startsWith("/") || path.contains("..") || path.length() > 200)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "入口跳转路径必须是站内相对路径（以 / 开头，不含 ..）");

    String icon = entry.icon() == null || entry.icon().isBlank() ? null : entry.icon().trim();
    if (icon != null && icon.length() > 40)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "入口图标名长度不能超过 40 个字符");

    Integer position = entry.position();
    if (position != null && (position < 0 || position > 9999))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "入口排序位必须在 0 到 9999 之间");

    return new ShortcutEntry(key, label, pageCode, path, icon, position);
  }

  /** 覆盖项入参校验。 */
  private ShortcutOverrideInput validateOverrideInput(ShortcutOverrideInput input) {
    if (input == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "覆盖项内容不能为空");
    String targetKey = trimToEmpty(input.targetKey());
    if (!targetKey.matches("^[a-z0-9][a-z0-9._-]{0,99}$"))
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "入口标识只能是小写字母、数字与 . _ -，且必须以字母或数字开头");

    String action = input.action() == null ? "" : input.action().trim();
    if (!ACTIONS.contains(action))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "覆盖动作只能是 UPSERT / REMOVE");

    if ("REMOVE".equals(action)) {
      return new ShortcutOverrideInput(targetKey, action, null, null, null, null, null);
    }

    String label = null;
    if (input.label() != null && !input.label().isBlank()) {
      label = input.label().trim();
      if (label.length() > 100)
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "入口标题长度不能超过 100 个字符");
    }
    String pageCode = null;
    if (input.pageCode() != null && !input.pageCode().isBlank()) {
      pageCode = input.pageCode().trim();
      if (pageCode.length() > 64)
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "入口目标页面码长度不能超过 64 个字符");
      if (!pageIsRegistered(pageCode))
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "入口目标页面未注册：" + pageCode);
    }
    String path = null;
    if (input.path() != null && !input.path().isBlank()) {
      path = input.path().trim();
      if (!path.startsWith("/") || path.contains("..") || path.length() > 200)
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "入口跳转路径必须是站内相对路径（以 / 开头，不含 ..）");
    }
    String icon = null;
    if (input.icon() != null && !input.icon().isBlank()) {
      icon = input.icon().trim();
      if (icon.length() > 40)
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "入口图标名长度不能超过 40 个字符");
    }
    Integer position = null;
    if (input.position() != null) {
      position = input.position();
      if (position < 0 || position > 9999)
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "入口排序位必须在 0 到 9999 之间");
    }

    if (label == null && pageCode == null && path == null && icon == null && position == null)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "覆盖至少要给出一个可覆盖的字段");

    return new ShortcutOverrideInput(targetKey, action, label, pageCode, path, icon, position);
  }

  /** 目标页面是否真的注册过（`permission` 里有它的 read 权限码）。 */
  private boolean pageIsRegistered(String pageCode) {
    return !sql.fetch("select 1 from permission where code = ?::varchar", pageCode + ".read").isEmpty();
  }

  private boolean roleExists(UUID tenantId, UUID roleId) {
    return !sql.fetch(
            "select 1 from role where tenant_id = ?::uuid and id = ?::uuid and status = 'ACTIVE'",
            tenantId,
            roleId)
        .isEmpty();
  }

  private boolean departmentExists(UUID tenantId, UUID departmentId) {
    return !sql.fetch(
            "select 1 from department where tenant_id = ?::uuid and id = ?::uuid", tenantId, departmentId)
        .isEmpty();
  }

  // --------------------------------------------------------------- 读写映射

  private ShortcutTemplate readTemplate(Record r) {
    return new ShortcutTemplate(
        r.get("id", UUID.class),
        r.get("code", String.class),
        r.get("name", String.class),
        r.get("description", String.class),
        r.get("scope_type", String.class),
        r.get("role_id", UUID.class),
        r.get("department_id", UUID.class),
        r.get("status", String.class),
        r.get("priority", Integer.class) == null ? 100 : r.get("priority", Integer.class),
        readEntries(r),
        r.get("created_by", UUID.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  private ShortcutOverride readOverride(Record r) {
    return new ShortcutOverride(
        r.get("id", UUID.class),
        r.get("target_key", String.class),
        r.get("action", String.class),
        r.get("label", String.class),
        r.get("page_code", String.class),
        r.get("path", String.class),
        r.get("icon", String.class),
        r.get("position", Integer.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  /** 读 JSONB 入口清单。 */
  private List<ShortcutEntry> readEntries(Record r) {
    String raw = r.get("entries_text", String.class);
    if (raw == null || raw.isBlank()) return List.of();
    List<Map<String, Object>> rawEntries;
    try {
      rawEntries = mapper.readValue(raw, new TypeReference<List<Map<String, Object>>>() {});
    } catch (Exception e) {
      throw new IllegalStateException("快捷入口清单解析失败", e);
    }
    List<ShortcutEntry> entries = new ArrayList<>();
    for (Map<String, Object> item : rawEntries) {
      Integer position = null;
      Object rawPosition = item.get("position");
      if (rawPosition instanceof Number number) position = number.intValue();
      entries.add(
          new ShortcutEntry(
              str(item.get("key")),
              str(item.get("label")),
              str(item.get("pageCode")),
              str(item.get("path")),
              blankToNull(str(item.get("icon"))),
              position));
    }
    return entries;
  }

  /** 写 JSONB 入口清单（键名固定为 key / label / pageCode / path / icon / position）。 */
  private String writeEntries(List<ShortcutEntry> entries) {
    List<Map<String, Object>> payload = new ArrayList<>();
    for (ShortcutEntry entry : entries) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("key", entry.key());
      item.put("label", entry.label());
      item.put("pageCode", entry.pageCode());
      item.put("path", entry.path());
      if (entry.icon() != null) item.put("icon", entry.icon());
      if (entry.position() != null) item.put("position", entry.position());
      payload.add(item);
    }
    try {
      return mapper.writeValueAsString(payload);
    } catch (Exception e) {
      throw new IllegalStateException("快捷入口清单序列化失败", e);
    }
  }

  /** 模板内排序：position 小的在前，没有 position 的按 key 排（确定的，不依赖插入顺序）。 */
  private List<ShortcutEntry> sortEntries(List<ShortcutEntry> entries) {
    List<ShortcutEntry> sorted = new ArrayList<>(entries);
    sorted.sort(
        Comparator.comparingInt((ShortcutEntry e) -> e.position() == null ? 100 : e.position())
            .thenComparing(ShortcutEntry::key));
    return sorted;
  }

  private static String str(Object value) {
    return value == null ? "" : String.valueOf(value);
  }

  private static String trimToEmpty(String value) {
    return value == null ? "" : value.trim();
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  private static ResponseStatusException notFound(String message) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
  }
}
