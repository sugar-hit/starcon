package com.starcon.channel;

import static com.starcon.channel.ComplianceInterceptModels.*;
import static com.starcon.channel.ComplianceInterceptRules.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
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
 * 16.2.1.2 拦截执行。
 *
 * <p>蓝图：「在关键业务节点执行合规校验并阻断：刊登发布前、发货前、结算提现前；
 * 展示拦截原因与解除方式（提交资质、申请豁免）。输入：合规规则集；业务动作触发的校验请求（商品/订单/店铺）。
 * 输出：校验结论（通过/警告/阻断）+ 原因 + 解除入口；豁免申请与审批。
 * 关系：上游 = 16.2.1.1；下游 = 4.3.1.1 发布、8.3.4.1 发货、12 提现。
 * <b>豁免必须有审批与理由</b>，否则阻断会被随意绕过。」
 *
 * <p>🔴🔴 本类里有五处<b>结构</b>上的不变量：
 *
 * <ol>
 *   <li><b>结论是三态 + 一个「判不了」</b>：{@code PASS / WARN / BLOCK / UNDECIDABLE}。
 *       规则读不到 / 适用版本查不到 / 规则集为空 ⇒ {@code UNDECIDABLE}，
 *       <b>绝不</b>返回 {@code PASS}（返回 PASS 等于放行）。
 *       {@code releaseGranted} 在 {@code UNDECIDABLE} 时也是 {@code false}。</li>
 *   <li><b>判定绑定「当时生效的那个版本」</b>：读 16.2.1.1 的
 *       {@code compliance_rule_version}，按 {@code now()} 取生效窗口覆盖此刻的那一版，
 *       判定凭据行里冻结 {@code rule_version_id} ⇒ 规则后来发新版本不追溯改历史判定。</li>
 *   <li><b>阻断在服务端强制</b>：{@link #enforceListingPublish} 是刊登派发路径上的闸门 ——
 *       它<b>读服务端结论</b>才放行；前端只负责显示。</li>
 *   <li><b>豁免要走审批</b>：申请人 ≠ 审批人、理由必填、有效期必填；
 *       因豁免放行时结论里<b>明确标出并指向豁免单 id</b>。</li>
 *   <li><b>下游未交付一律不编</b>：8.3.4.1 发货（跨服务）与 12 提现（服务未落地）
 *       在能力声明里 {@code available=false} + {@code ownerModule} + 影响说明。</li>
 * </ol>
 *
 * <p>🔴 <b>上游是同库只读</b>：16.2.1.1 的 {@code compliance_rule*} 四张表就在本服务的库里，
 * 本类**只读**它们，**不写**上游任何一张表。跨服务只读端口（{@code SourcePort}）本模块
 * <b>不需要</b> —— 没有跨服务数据源要读。
 */
@Service
public class ComplianceInterceptService {

  /** 一页最多多少行。 */
  private static final int MAX_PAGE_SIZE = 200;

  /** 一次最多校验多少个文本字段。 */
  private static final int MAX_FIELDS = 50;

  /** 单个字段的值最多多少字符（超了直接拒，不截断 —— 截断会漏判）。 */
  private static final int MAX_FIELD_LENGTH = 100000;

  /** 一次最多提交多少条要求状态。 */
  private static final int MAX_REQUIREMENTS = 200;

  /** 豁免有效期最长多少天（豁免不能永久生效）。 */
  private static final int MAX_EXEMPTION_DAYS = 365;

  // ==================================================================
  // 口径说明（能力声明与各处共用同一句话）
  // ==================================================================

  private static final String CONCLUSION_NOTE =
      "🔴🔴 结论是**三态 + 一个「判不了」**：通过 PASS / 警告 WARN / 阻断 BLOCK，"
          + "外加 UNDECIDABLE（判不了）。"
          + "**规则读不到、适用版本查不到、规则集为空时一律返回 UNDECIDABLE，绝不返回 PASS** —— "
          + "返回 PASS 等于放行。"
          + "判不了时 {@code releaseGranted=false}：判不出来不等于没毛病。";

  private static final String ENFORCEMENT_NOTE =
      "🔴 阻断**在服务端强制**：刊登派发路径（4.3.1.1）读本模块的服务端结论才放行，"
          + "命中阻断时目标被标成失败并写明原因；"
          + "前端只是把 blocked 与逐条原因显示出来，**绕不过服务端**。"
          + "拦截默认**不生效**：租户要先显式开启对应拦截点的策略（enabled），"
          + "没开启就不拦 —— 这与 4.2.3.2「未扫描过则不拦，避免把『还没扫过』当成『不合规』」同一纪律。";

  private static final String EXEMPTION_NOTE =
      "🔴🔴 **豁免必须有审批与理由，否则阻断会被随意绕过** —— 落成五道闸："
          + "① 状态机 PENDING → APPROVED / REJECTED，终态不可回退；"
          + "② **申请人 ≠ 审批人**（DB CHECK + 服务层双重钉住）；"
          + "③ 申请人理由与审批意见都必填（非空且非空白）；"
          + "④ **有效期必填**（过期即失效，不能永久生效）；"
          + "⑤ 一张豁免只针对**一条规则**（什么都豁免的空白豁免等于把阻断关掉）。"
          + "已批准的豁免参与后续判定时，结论里**明确标出「因豁免放行」并指向豁免单 id** —— "
          + "阻断在审计上不能无声消失。";

  private static final String UPSTREAM_NOTE =
      "上游 = **16.2.1.1 规则库**（**已交付，同库** `compliance_rule` / `compliance_rule_version` / "
          + "`compliance_rule_term` / `compliance_rule_scope`）。"
          + "本模块**只读**它，**不写**上游任何一张表。"
          + "判定一律绑定**当时生效的那一版**：规则后来发新版本，历史判定不被追溯改变。";

  private static final String DOWNSTREAM_NOTE =
      "下游 = **4.3.1.1 一键刊登**（**已交付，同服务** ⇒ 本模块在它的派发路径上落地服务端闸门）、"
          + "**8.3.4.1 发货**（**跨服务**，warehouse 侧才有发货路径 ⇒ 本模块**只出结论**，"
          + "**不跨服务 SQL / 不造跨库 FK**）、"
          + "**12 结算提现**（**finance 服务在本仓尚未落地** ⇒ 本模块**不建财务表、不造提现调用**，"
          + "**不声称已拦截提现**）。";

  private final DSLContext sql;
  private final ObjectMapper json;

  public ComplianceInterceptService(DSLContext sql, ObjectMapper json) {
    this.sql = sql;
    this.json = json;
  }

  // ==================================================================
  // 小工具
  // ==================================================================

  private static String text(String value) {
    return value == null ? "" : value.trim();
  }

  private static boolean blank(String value) {
    return text(value).isEmpty();
  }

  private static ResponseStatusException notFound() {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "不存在");
  }

  private static ResponseStatusException unprocessable(String message) {
    return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, message);
  }

  private UUID currentTenant() {
    Object raw = sql.fetchValue("select nullif(current_setting('app.current_tenant',true),'')");
    if (raw == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "缺少租户上下文");
    }
    return UUID.fromString(String.valueOf(raw));
  }

  private static UUID parseId(String raw) {
    String value = text(raw);
    if (value.isEmpty()) {
      return null;
    }
    try {
      return UUID.fromString(value);
    } catch (RuntimeException e) {
      return null;
    }
  }

  /** 🔴 非法 UUID 按「筛不出东西」处理，但**绝不当成没筛选**。 */
  private static final UUID NO_SUCH_ID = UUID.fromString("00000000-0000-0000-0000-000000000000");

  private static UUID parseFilterId(String raw) {
    String value = text(raw);
    if (value.isEmpty()) {
      return null;
    }
    UUID parsed = parseId(value);
    return parsed == null ? NO_SUCH_ID : parsed;
  }

  private static long count(Object raw) {
    return raw == null ? 0L : ((Number) raw).longValue();
  }

  private static int intOf(Record row, String column) {
    Number value = row.get(column, Number.class);
    return value == null ? 0 : value.intValue();
  }

  private static int clampSize(int size) {
    return Math.max(1, Math.min(size, MAX_PAGE_SIZE));
  }

  private static int clampPage(int page) {
    return Math.max(page, 0);
  }

  /** 🔴 <b>同源 DB 时钟</b>：判定时刻、豁免有效期一律从这里取。 */
  private OffsetDateTime dbNow() {
    Object raw = sql.fetchValue("select now()");
    return raw instanceof OffsetDateTime value ? value : OffsetDateTime.now(ZoneOffset.UTC);
  }

  private String writeJson(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException("序列化失败", e);
    }
  }

  // ==================================================================
  // 能力声明
  // ==================================================================

  /**
   * 能力声明：值域、结论口径、以及**哪些下游未交付 / 怎么被强制**。
   *
   * <p>🔴 8.3.4.1 发货（跨服务）与 12 提现（服务未落地）如实报出 ownerModule 与影响，
   * **不编**一条不存在的拦截链路。
   */
  public ComplianceInterceptCapabilities capabilities() {
    List<ComplianceInterceptCapabilityGap> gaps =
        List.of(
            new ComplianceInterceptCapabilityGap(
                "16.2.1.1",
                "规则库",
                "16.2.1.1 规则库（已交付，同库）",
                true,
                "规则库**已交付**且就在本服务（`compliance_rule*` 四张表）。"
                    + "本模块**只读**它，判定一律绑定**当时生效的那一版**；"
                    + "两边**互不改动对方的表**，边界写在两侧的迁移文件头。"),
            new ComplianceInterceptCapabilityGap(
                "4.3.1.1",
                "一键刊登",
                "04 刊登与内容 · 4.3.1.1 一键刊登（已交付，同服务）",
                true,
                "一键刊登**已交付**且就在本服务 ⇒ 本模块在它的**派发路径**上落地服务端闸门："
                    + "派发前读服务端结论，命中阻断时该目标被标成失败并写明原因，"
                    + "**前端绕不过服务端**。"
                    + "🔴 4.3.1.2 批量发布那条通道**尚未接入**本闸门（归 4.3.1.2 自己接）。"),
            new ComplianceInterceptCapabilityGap(
                "8.3.4.1",
                "发货",
                "08 仓储与运营 · 8.3.4.1 发货",
                false,
                "发货在 **warehouse 服务**（**跨服务**）⇒ 本模块**只出结论**："
                    + "在本模块对 SHIPMENT 场景跑一次判定，结论与原因都在本模块落库可查；"
                    + "**不跨服务 SQL、不造跨库 FK**。发货路径真正读这个结论，要等 8.3.4.1 那侧接入。"),
            new ComplianceInterceptCapabilityGap(
                "12",
                "结算提现",
                "12 财务与结算 · 提现",
                false,
                "finance 服务**在本仓尚未落地** ⇒ 本模块**不建财务表、不造提现调用**，"
                    + "**不声称已拦截提现**。"
                    + "WITHDRAWAL 场景在本模块里可以跑判定并落结论（规则侧是齐的），"
                    + "但**没有任何提现路径会读它** —— 接上是 12 那条车道的活。"));

    return new ComplianceInterceptCapabilities(
        gaps,
        SCENES,
        SCENE_LABELS,
        SUBJECT_TYPES,
        SUBJECT_TYPE_LABELS,
        CONCLUSIONS,
        CONCLUSION_LABELS,
        REASON_CODES,
        REASON_CODE_LABELS,
        EXEMPTION_STATUSES,
        EXEMPTION_STATUS_LABELS,
        CONCLUSION_NOTE,
        ENFORCEMENT_NOTE,
        EXEMPTION_NOTE,
        UPSTREAM_NOTE,
        DOWNSTREAM_NOTE);
  }

  // ==================================================================
  // 策略
  // ==================================================================

  /** 🔴 策略清单：三个拦截点各一条（没有配置过的按默认值返回）。 */
  public ComplianceInterceptPolicySet policy() {
    UUID tenant = currentTenant();
    List<ComplianceInterceptPolicyRow> items = new ArrayList<>();
    Map<String, Record> rows = new LinkedHashMap<>();
    for (Record row :
        sql.fetch(
            "select * from compliance_intercept_policy where tenant_id = ? order by scene", tenant)) {
      rows.put(row.get("scene", String.class), row);
    }
    for (String scene : SCENES) {
      Record row = rows.get(scene);
      if (row == null) {
        items.add(new ComplianceInterceptPolicyRow(scene, sceneLabel(scene), false, "BLOCK", "", null));
        continue;
      }
      items.add(
          new ComplianceInterceptPolicyRow(
              scene,
              sceneLabel(scene),
              Boolean.TRUE.equals(row.get("enabled", Boolean.class)),
              row.get("undecidable_action", String.class),
              row.get("note", String.class),
              row.get("updated_at", OffsetDateTime.class)));
    }
    return new ComplianceInterceptPolicySet(
        items,
        "🔴 拦截**默认不生效**（enabled=false）：租户显式开启后，"
            + "对应的放行路径才会真的读服务端结论。"
            + "undecidable_action 决定「判不了」时失败关闭（BLOCK）还是放行并留痕（ALLOW）。");
  }

  /** 🔴 改策略（按场景 upsert）。 */
  public ComplianceInterceptPolicyRow savePolicy(
      String rawScene, ComplianceInterceptPolicyInput input, UUID actorId) {
    UUID tenant = currentTenant();
    String scene = text(rawScene).toUpperCase();
    if (!knownScene(scene)) {
      throw unprocessable("未知的拦截点：" + rawScene);
    }
    boolean enabled = parseBoolean(input.enabled());
    String action = text(input.undecidableAction()).toUpperCase();
    if (action.isEmpty()) {
      action = "BLOCK";
    }
    if (!action.equals("BLOCK") && !action.equals("ALLOW")) {
      throw unprocessable("未知的判不了处理方式：" + input.undecidableAction());
    }
    String note = text(input.note());
    if (note.length() > 500) {
      throw unprocessable("备注最多 500 字");
    }
    sql.execute(
        """
        insert into compliance_intercept_policy
          (id,tenant_id,scene,enabled,undecidable_action,note,created_by,updated_by)
        values (?,?,?,?,?,?,?,?)
        on conflict (tenant_id, scene) do update
          set enabled = excluded.enabled,
              undecidable_action = excluded.undecidable_action,
              note = excluded.note,
              updated_by = excluded.updated_by,
              updated_at = now()
        """,
        UUID.randomUUID(),
        tenant,
        scene,
        enabled,
        action,
        note,
        actorId,
        actorId);
    return policy().items().stream()
        .filter(row -> row.scene().equals(scene))
        .findFirst()
        .orElseThrow(ComplianceInterceptService::notFound);
  }

  private static boolean parseBoolean(String raw) {
    String value = text(raw).toLowerCase();
    return value.equals("true") || value.equals("1") || value.equals("yes") || value.equals("on");
  }

  // ==================================================================
  // 判定引擎
  // ==================================================================

  /** 生效中的一条规则（绑定**当时生效的那个版本**）。 */
  private record EffectiveRule(
      UUID ruleId,
      String ruleCode,
      String ruleType,
      UUID versionId,
      int versionNo,
      String severity,
      String title,
      List<String> terms) {}

  /** 一条待写入的原因。 */
  private record ReasonDraft(
      String reasonCode,
      String reasonText,
      UUID ruleId,
      UUID ruleVersionId,
      String ruleCode,
      String ruleType,
      String severity,
      String fieldKey,
      String matchedTerm,
      boolean waived,
      UUID exemptionId) {}

  /** 判定过程中解析出来的主体上下文。 */
  private record SubjectContext(UUID shopId, UUID platformId, UUID marketplaceId) {}

  /** 判定引擎的内部结果。 */
  private record Outcome(
      String conclusion,
      boolean blocked,
      boolean releaseGranted,
      int ruleCount,
      List<ReasonDraft> reasons,
      List<ComplianceInterceptExemptionRef> exemptions) {}

  /**
   * 🔴 跑一次判定（**不落库**）。
   *
   * @param tenant 租户
   * @param scene 拦截点
   * @param subjectType 校验对象
   * @param subjectId 主体逻辑 id
   * @param subject 主体上下文（店铺 / 平台 / 站点）
   * @param fields 被校验的文本字段
   * @param requirements 要求状态（按规则编码）
   * @param now 判定时刻（同源 DB 时钟）
   */
  private Outcome run(
      UUID tenant,
      String scene,
      String subjectType,
      String subjectId,
      SubjectContext subject,
      List<ComplianceInterceptFieldInput> fields,
      List<ComplianceInterceptRequirementInput> requirements,
      OffsetDateTime now) {

    List<ReasonDraft> reasons = new ArrayList<>();
    Map<String, ComplianceInterceptRequirementInput> requirementByCode = new LinkedHashMap<>();
    for (ComplianceInterceptRequirementInput requirement : requirements) {
      requirementByCode.put(text(requirement.ruleCode()), requirement);
    }

    // ---- 🔴 规则集：只取**此刻生效**的那一版 ----
    List<EffectiveRule> effective = new ArrayList<>();
    List<UUID> ambiguous = new ArrayList<>();
    for (Record row :
        sql.fetch(
            """
            select rule_id, count(*) as n
              from compliance_rule_version
             where tenant_id = ?
               and effective_from <= ?::timestamptz
               and (effective_to is null or effective_to > ?::timestamptz)
             group by rule_id
            having count(*) > 1
            """,
            tenant,
            now,
            now)) {
      ambiguous.add(row.get("rule_id", UUID.class));
    }

    for (Record row :
        sql.fetch(
            """
            select r.id as rule_id, r.rule_code, r.rule_type,
                   v.id as version_id, v.version_no, v.severity, v.title
              from compliance_rule r
              join compliance_rule_version v
                on v.tenant_id = r.tenant_id and v.rule_id = r.id
             where r.tenant_id = ? and r.status = 'ACTIVE'
               and v.effective_from <= ?::timestamptz
               and (v.effective_to is null or v.effective_to > ?::timestamptz)
             order by r.rule_code
            """,
            tenant,
            now,
            now)) {
      UUID ruleId = row.get("rule_id", UUID.class);
      UUID versionId = row.get("version_id", UUID.class);
      if (ambiguous.contains(ruleId)) {
        reasons.add(
            new ReasonDraft(
                "RULE_VERSION_UNRESOLVED",
                "规则 "
                    + row.get("rule_code", String.class)
                    + " 有多个版本在同一时刻生效，判不了用哪一版",
                ruleId,
                versionId,
                row.get("rule_code", String.class),
                row.get("rule_type", String.class),
                row.get("severity", String.class),
                null,
                null,
                false,
                null));
        continue;
      }
      effective.add(
          new EffectiveRule(
              ruleId,
              row.get("rule_code", String.class),
              row.get("rule_type", String.class),
              versionId,
              intOf(row, "version_no"),
              row.get("severity", String.class),
              row.get("title", String.class),
              List.of()));
    }

    // ---- 🔴 适用范围收敛（16.2.1.1 的 scope 属于版本，按生效版本取）----
    List<EffectiveRule> applicable = new ArrayList<>();
    if (!effective.isEmpty()) {
      Set<UUID> scoped = scopeMatched(tenant, effective, subject);
      for (EffectiveRule rule : effective) {
        if (scoped.contains(rule.versionId())) {
          applicable.add(rule);
        }
      }
    }

    // ---- 🔴 规则集为空 ⇒ 判不了（**不是通过**）----
    if (applicable.isEmpty()) {
      reasons.add(
          new ReasonDraft(
              "RULE_SET_EMPTY",
              subject.platformId() == null
                  ? "这个主体没有绑定店铺/平台，判不了适用哪些规则（规则集为空）"
                  : "这个主体名下没有任何生效且适用的合规规则（规则集为空），判不了",
              null,
              null,
              null,
              null,
              null,
              null,
              null,
              false,
              null));
    }

    // ---- 🔴 词条（词库）----
    Map<UUID, List<String>> termsByVersion = new LinkedHashMap<>();
    if (!applicable.isEmpty()) {
      List<Object> params = new ArrayList<>();
      params.add(tenant);
      StringBuilder placeholders = new StringBuilder();
      for (EffectiveRule rule : applicable) {
        placeholders.append(placeholders.length() == 0 ? "?" : ",?");
        params.add(rule.versionId());
      }
      for (Record row :
          sql.fetch(
              "select rule_version_id, term from compliance_rule_term"
                  + " where tenant_id = ? and rule_version_id in ("
                  + placeholders
                  + ") order by term",
              params.toArray())) {
        termsByVersion
            .computeIfAbsent(row.get("rule_version_id", UUID.class), key -> new ArrayList<>())
            .add(row.get("term", String.class));
      }
    }

    // ---- 🔴 逐条规则判定 ----
    List<ReasonDraft> hits = new ArrayList<>();
    for (EffectiveRule rule : applicable) {
      List<String> terms = termsByVersion.getOrDefault(rule.versionId(), List.of());
      if (ComplianceRuleRules.wordRuleType(rule.ruleType())) {
        if (terms.isEmpty()) {
          hits.add(
              new ReasonDraft(
                  "RULE_TERMS_MISSING",
                  "规则 " + rule.ruleCode() + "（" + rule.title() + "）的词库是空的，判不了",
                  rule.ruleId(),
                  rule.versionId(),
                  rule.ruleCode(),
                  rule.ruleType(),
                  rule.severity(),
                  null,
                  null,
                  false,
                  null));
          continue;
        }
        for (ComplianceInterceptFieldInput field : fields) {
          String matched = null;
          for (String term : terms) {
            if (matchesTerm(term, field.value())) {
              matched = term;
              break;
            }
          }
          if (matched != null) {
            hits.add(
                new ReasonDraft(
                    "WORD_TERM_HIT",
                    "规则 "
                        + rule.ruleCode()
                        + "（"
                        + rule.title()
                        + "）命中词条「"
                        + matched
                        + "」（"
                        + (field.key() == null ? "内容" : field.key())
                        + "）",
                    rule.ruleId(),
                    rule.versionId(),
                    rule.ruleCode(),
                    rule.ruleType(),
                    rule.severity(),
                    field.key(),
                    matched,
                    false,
                    null));
          }
        }
        continue;
      }

      // ---- 非词条类规则：靠**要求状态**判定（认证 / 禁限售 / 标签 / 材质）----
      ComplianceInterceptRequirementInput requirement = requirementByCode.get(rule.ruleCode());
      if (requirement == null) {
        hits.add(
            new ReasonDraft(
                "REQUIREMENT_UNRESOLVED",
                "规则 "
                    + rule.ruleCode()
                    + "（"
                    + rule.title()
                    + "）是"
                    + ComplianceRuleRules.ruleTypeLabel(rule.ruleType())
                    + "类要求，校验请求里没有给出它的满足状态，判不了",
                rule.ruleId(),
                rule.versionId(),
                rule.ruleCode(),
                rule.ruleType(),
                rule.severity(),
                null,
                null,
                false,
                null));
        continue;
      }
      String status = text(requirement.status()).toUpperCase();
      if (!status.equals("SATISFIED") && !status.equals("UNSATISFIED")) {
        hits.add(
            new ReasonDraft(
                "REQUIREMENT_UNRESOLVED",
                "规则 " + rule.ruleCode() + " 的要求状态不认识的取值：" + requirement.status(),
                rule.ruleId(),
                rule.versionId(),
                rule.ruleCode(),
                rule.ruleType(),
                rule.severity(),
                null,
                null,
                false,
                null));
        continue;
      }
      if (status.equals("UNSATISFIED")) {
        String evidence = text(requirement.evidence());
        if (evidence.isEmpty()) {
          throw unprocessable(
              "规则 " + rule.ruleCode() + " 的要求状态是未满足，必须给出依据（凭什么说它没满足）");
        }
        hits.add(
            new ReasonDraft(
                "REQUIREMENT_NOT_SATISFIED",
                "规则 "
                    + rule.ruleCode()
                    + "（"
                    + rule.title()
                    + "）未满足（"
                    + evidence
                    + "）",
                rule.ruleId(),
                rule.versionId(),
                rule.ruleCode(),
                rule.ruleType(),
                rule.severity(),
                null,
                null,
                false,
                null));
      }
    }

    // ---- 🔴 豁免：已批准且在有效期内的才放行 ----
    List<ReasonDraft> resolved = new ArrayList<>(reasons);
    List<ComplianceInterceptExemptionRef> exemptions = new ArrayList<>();
    for (ReasonDraft hit : hits) {
      ExemptionMatch match =
          findExemption(tenant, scene, subjectType, subjectId, hit.ruleId(), now);
      if (match == null) {
        resolved.add(hit);
        continue;
      }
      resolved.add(
          new ReasonDraft(
              hit.reasonCode(),
              hit.reasonText() + " —— 因豁免放行（豁免单 " + match.exemptionId() + "）",
              hit.ruleId(),
              hit.ruleVersionId(),
              hit.ruleCode(),
              hit.ruleType(),
              hit.severity(),
              hit.fieldKey(),
              hit.matchedTerm(),
              true,
              match.exemptionId()));
      exemptions.add(
          new ComplianceInterceptExemptionRef(
              match.exemptionId().toString(),
              hit.ruleId().toString(),
              hit.ruleCode(),
              match.validFrom(),
              match.validTo(),
              "因豁免放行（豁免单 " + match.exemptionId() + "，有效期 " + match.validFrom() + " ~ "
                  + match.validTo() + "）"));
    }

    // ---- 🔴 结论聚合（三态 + 一个「判不了」）----
    int blocking = 0;
    int warning = 0;
    int undecidable = 0;
    for (ReasonDraft reason : resolved) {
      if (undecidableReason(reason.reasonCode())) {
        undecidable++;
        continue;
      }
      if (reason.waived()) {
        continue;
      }
      if ("BLOCKING".equals(reason.severity())) {
        blocking++;
      } else if ("WARNING".equals(reason.severity())) {
        warning++;
      }
    }
    String conclusion = aggregate(blocking, warning, undecidable);
    return new Outcome(
        conclusion,
        blocked(conclusion),
        releaseGranted(conclusion),
        applicable.size(),
        resolved,
        exemptions);
  }

  /** 命中适用范围的版本 id 集合。 */
  private Set<UUID> scopeMatched(
      UUID tenant, List<EffectiveRule> rules, SubjectContext subject) {
    Set<UUID> matched = new LinkedHashSet<>();
    if (subject.platformId() == null) {
      // 没有平台 ⇒ 判不了适用哪些规则（返回空 ⇒ 规则集为空 ⇒ UNDECIDABLE）。
      return matched;
    }
    List<Object> params = new ArrayList<>();
    params.add(tenant);
    StringBuilder placeholders = new StringBuilder();
    for (EffectiveRule rule : rules) {
      placeholders.append(placeholders.length() == 0 ? "?" : ",?");
      params.add(rule.versionId());
    }
    StringBuilder scopeFilter = new StringBuilder();
    if (subject.shopId() != null) {
      scopeFilter.append(
          """
          and (
                sc.shop_id = ?
             or (sc.shop_id is null and sc.platform_id = ?
                 and (sc.marketplace_id is null
                      or sc.marketplace_id = ?
                      or exists (select 1 from shop_marketplace sm
                                  where sm.tenant_id = sc.tenant_id and sm.shop_id = ?
                                    and sm.marketplace_id = sc.marketplace_id)))
          )
          """);
      params.add(subject.shopId());
      params.add(subject.platformId());
      params.add(subject.marketplaceId());
      params.add(subject.shopId());
    } else {
      scopeFilter.append(
          """
          and sc.shop_id is null and sc.platform_id = ?
              and (sc.marketplace_id is null or sc.marketplace_id = ?)
          """);
      params.add(subject.platformId());
      params.add(subject.marketplaceId());
    }
    for (Record row :
        sql.fetch(
            "select distinct sc.rule_version_id from compliance_rule_scope sc"
                + " where sc.tenant_id = ? and sc.rule_version_id in ("
                + placeholders
                + ")"
                + scopeFilter,
            params.toArray())) {
      matched.add(row.get("rule_version_id", UUID.class));
    }
    return matched;
  }

  /** 命中的一张已批准且在有效期内的豁免单。 */
  private record ExemptionMatch(
      UUID exemptionId, OffsetDateTime validFrom, OffsetDateTime validTo) {}

  private ExemptionMatch findExemption(
      UUID tenant,
      String scene,
      String subjectType,
      String subjectId,
      UUID ruleId,
      OffsetDateTime now) {
    if (ruleId == null) {
      return null;
    }
    Record row =
        sql.fetchOne(
            """
            select id, valid_from, valid_to
              from compliance_intercept_exemption
             where tenant_id = ? and scene = ? and subject_type = ? and subject_id = ?
               and rule_id = ? and status = 'APPROVED'
               and valid_from <= ?::timestamptz and valid_to > ?::timestamptz
             order by valid_to desc, created_at desc
             limit 1
            """,
            tenant,
            scene,
            subjectType,
            subjectId,
            ruleId,
            now,
            now);
    if (row == null) {
      return null;
    }
    return new ExemptionMatch(
        row.get("id", UUID.class),
        row.get("valid_from", OffsetDateTime.class),
        row.get("valid_to", OffsetDateTime.class));
  }

  // ==================================================================
  // 落库
  // ==================================================================

  /** 🔴 写入判定凭据（一次判定一行 + 逐条原因），**只追加**。 */
  private ComplianceInterceptDecision persist(
      UUID tenant,
      String scene,
      String subjectType,
      String subjectId,
      SubjectContext subject,
      Outcome outcome,
      List<ComplianceInterceptFieldInput> fields,
      List<ComplianceInterceptRequirementInput> requirements,
      OffsetDateTime now,
      UUID actorId) {

    UUID decisionId = UUID.randomUUID();
    int blocking = 0;
    int warning = 0;
    int undecidable = 0;
    int waived = 0;
    for (ReasonDraft reason : outcome.reasons()) {
      if (undecidableReason(reason.reasonCode())) {
        undecidable++;
        continue;
      }
      if (reason.waived()) {
        waived++;
        continue;
      }
      if ("BLOCKING".equals(reason.severity())) {
        blocking++;
      } else if ("WARNING".equals(reason.severity())) {
        warning++;
      }
    }
    List<String> exemptionIds = new ArrayList<>();
    for (ComplianceInterceptExemptionRef ref : outcome.exemptions()) {
      exemptionIds.add(ref.exemptionId());
    }

    Map<String, Object> snapshot = new LinkedHashMap<>();
    snapshot.put("scene", scene);
    snapshot.put("subjectType", subjectType);
    snapshot.put("subjectId", subjectId);
    snapshot.put("shopId", subject.shopId() == null ? null : subject.shopId().toString());
    snapshot.put("platformId", subject.platformId() == null ? null : subject.platformId().toString());
    snapshot.put(
        "marketplaceId", subject.marketplaceId() == null ? null : subject.marketplaceId().toString());
    snapshot.put("fields", fields);
    snapshot.put("requirements", requirements);

    sql.execute(
        """
        insert into compliance_intercept_decision
          (id,tenant_id,scene,subject_type,subject_id,shop_id,platform_id,marketplace_id,
           conclusion,blocked,release_granted,rule_count,reason_count,blocking_reason_count,
           waived_reason_count,undecidable_reason_count,exemption_ids,subject_snapshot,
           decided_at,decided_by)
        values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?::jsonb,?::timestamptz,?)
        """,
        decisionId,
        tenant,
        scene,
        subjectType,
        subjectId,
        subject.shopId(),
        subject.platformId(),
        subject.marketplaceId(),
        outcome.conclusion(),
        outcome.blocked(),
        outcome.releaseGranted(),
        outcome.ruleCount(),
        outcome.reasons().size(),
        blocking,
        waived,
        undecidable,
        writeJson(exemptionIds),
        writeJson(snapshot),
        now,
        actorId);

    for (ReasonDraft reason : outcome.reasons()) {
      sql.execute(
          """
          insert into compliance_intercept_reason
            (id,tenant_id,decision_id,rule_id,rule_version_id,rule_code,rule_type,severity,
             reason_code,reason_text,field_key,matched_term,waived,exemption_id)
          values (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
          """,
          UUID.randomUUID(),
          tenant,
          decisionId,
          reason.ruleId(),
          reason.ruleVersionId(),
          reason.ruleCode(),
          reason.ruleType(),
          reason.severity(),
          reason.reasonCode(),
          reason.reasonText(),
          reason.fieldKey(),
          reason.matchedTerm(),
          reason.waived(),
          reason.exemptionId());
    }

    return loadDecision(tenant, decisionId);
  }

  // ==================================================================
  // 对外：判定
  // ==================================================================

  /** 🔴 跑一次判定并落库（判定端点：返回 {@code blocked} + 逐条原因）。 */
  public ComplianceInterceptDecision evaluate(
      ComplianceInterceptEvaluateInput input, UUID actorId) {
    UUID tenant = currentTenant();
    String scene = text(input.scene()).toUpperCase();
    if (!knownScene(scene)) {
      throw unprocessable("未知的拦截点：" + input.scene());
    }
    String subjectType = text(input.subjectType()).toUpperCase();
    if (!knownSubjectType(subjectType)) {
      throw unprocessable("未知的校验对象：" + input.subjectType());
    }
    String subjectId = text(input.subjectId());
    if (subjectId.isEmpty()) {
      throw unprocessable("主体 id 不能为空");
    }
    if (subjectId.length() > 128) {
      throw unprocessable("主体 id 最多 128 个字符");
    }

    List<ComplianceInterceptFieldInput> fields =
        input.fields() == null ? List.of() : input.fields();
    if (fields.size() > MAX_FIELDS) {
      throw unprocessable("一次最多校验 " + MAX_FIELDS + " 个字段");
    }
    for (ComplianceInterceptFieldInput field : fields) {
      if (field.value() != null && field.value().length() > MAX_FIELD_LENGTH) {
        throw unprocessable(
            "字段「" + text(field.key()) + "」的值太长（超过 " + MAX_FIELD_LENGTH + " 字符）");
      }
    }
    List<ComplianceInterceptRequirementInput> requirements =
        input.requirements() == null ? List.of() : input.requirements();
    if (requirements.size() > MAX_REQUIREMENTS) {
      throw unprocessable("一次最多提交 " + MAX_REQUIREMENTS + " 条要求状态");
    }
    for (ComplianceInterceptRequirementInput requirement : requirements) {
      String code = text(requirement.ruleCode());
      if (code.isEmpty()) {
        throw unprocessable("要求状态必须给出规则编码");
      }
      String status = text(requirement.status()).toUpperCase();
      if (!status.equals("SATISFIED") && !status.equals("UNSATISFIED")) {
        throw unprocessable(
            "规则 " + code + " 的要求状态只能是 SATISFIED 或 UNSATISFIED（当前：" + requirement.status() + "）");
      }
      requireKnownRuleCode(tenant, code);
    }

    SubjectContext subject = resolveSubject(tenant, input);
    OffsetDateTime now = dbNow();
    Outcome outcome =
        run(tenant, scene, subjectType, subjectId, subject, fields, requirements, now);
    return persist(
        tenant, scene, subjectType, subjectId, subject, outcome, fields, requirements, now, actorId);
  }

  /** 规则编码必须真的存在（不能拿一个不存在的规则编要求状态）。 */
  private void requireKnownRuleCode(UUID tenant, String ruleCode) {
    Record row =
        sql.fetchOne(
            "select 1 from compliance_rule where tenant_id = ? and rule_code = ? limit 1",
            tenant,
            ruleCode);
    if (row == null) {
      throw unprocessable("规则编码不存在：" + ruleCode);
    }
  }

  /** 解析主体上下文：店铺 → 平台（读本服务自己的店铺目录）。 */
  private SubjectContext resolveSubject(UUID tenant, ComplianceInterceptEvaluateInput input) {
    UUID shopId = parseId(input.shopId());
    UUID platformId = parseId(input.platformId());
    UUID marketplaceId = parseId(input.marketplaceId());
    if (shopId != null) {
      Record shop =
          sql.fetchOne(
              "select id, platform_id from shop where tenant_id = ? and id = ?", tenant, shopId);
      if (shop == null) {
        throw unprocessable("店铺不存在或不属于当前租户：" + input.shopId());
      }
      platformId = shop.get("platform_id", UUID.class);
    }
    return new SubjectContext(shopId, platformId, marketplaceId);
  }

  // ==================================================================
  // 🔴 服务端闸门（放行路径读服务端结论）
  // ==================================================================

  /**
   * 🔴🔴 刊登发布路径上的服务端闸门。
   *
   * <p>返回 {@code null} = 放行；返回非空 = 阻断原因（人话，含逐条原因）。
   *
   * <p>🔴 拦截**默认不生效**：策略没启用时直接放行（不产生判定凭据）——
   * 这与 4.2.3.2「未扫描过则不拦」同一纪律：把「还没开合规」当成「违规」会让刊登链一步都走不动。
   *
   * @param tenant 租户
   * @param subjectId 主体逻辑 id（源 SKU）
   * @param shopId 目标店铺
   * @param platformId 目标平台
   * @param marketplaceId 目标站点
   * @param fields 被校验的文本字段（标题 / 描述 / 卖点）
   * @param requirements 要求状态
   * @return 阻断原因（null = 放行）
   */
  public String enforceListingPublish(
      UUID tenant,
      UUID subjectId,
      UUID shopId,
      UUID platformId,
      UUID marketplaceId,
      List<ComplianceInterceptFieldInput> fields,
      List<ComplianceInterceptRequirementInput> requirements) {

    ComplianceInterceptPolicyRow policyRow = policyRow(tenant, "LISTING_PUBLISH");
    if (!policyRow.enabled()) {
      return null;
    }
    OffsetDateTime now = dbNow();
    SubjectContext subject = new SubjectContext(shopId, platformId, marketplaceId);
    Outcome outcome;
    try {
      outcome =
          run(
              tenant,
              "LISTING_PUBLISH",
              "PRODUCT",
              subjectId == null ? "" : subjectId.toString(),
              subject,
              fields == null ? List.of() : fields,
              requirements == null ? List.of() : requirements,
              now);
    } catch (RuntimeException e) {
      // 规则读不到 / 校验跑不起来 ⇒ **判不了**（不是通过），但**不静默**：写一条判定凭据。
      outcome =
          new Outcome(
              "UNDECIDABLE",
              false,
              false,
              0,
              List.of(
                  new ReasonDraft(
                      "RULE_READ_FAILED",
                      "合规校验没跑起来：" + (e.getMessage() == null ? "未知原因" : e.getMessage()),
                      null,
                      null,
                      null,
                      null,
                      null,
                      null,
                      null,
                      false,
                      null)),
              List.of());
    }
    try {
      persist(
          tenant,
          "LISTING_PUBLISH",
          "PRODUCT",
          subjectId == null ? "" : subjectId.toString(),
          subject,
          outcome,
          fields == null ? List.of() : fields,
          requirements == null ? List.of() : requirements,
          now,
          null);
    } catch (RuntimeException e) {
      // 🔴 判定凭据写不进去 ⇒ **失败关闭**：不能「没记上就当没事」。
      return "合规校验没跑起来（判定凭据写入失败）："
          + (e.getMessage() == null ? "未知原因" : e.getMessage());
    }

    if (outcome.releaseGranted()) {
      return null;
    }
    if (undecidableConclusion(outcome.conclusion())
        && "ALLOW".equals(policyRow.undecidableAction())) {
      // 判不了但租户选了「放行并留痕」：判定凭据已经写下，审计上仍看得见。
      return null;
    }
    return describeBlocking(outcome);
  }

  /** 把阻断原因写成人话（逐条列出）。 */
  private String describeBlocking(Outcome outcome) {
    StringBuilder message = new StringBuilder();
    message.append(
        undecidableConclusion(outcome.conclusion())
            ? "合规校验判不了，已拦截（服务端强制）："
            : "合规校验阻断，已拦截（服务端强制）：");
    boolean first = true;
    for (ReasonDraft reason : outcome.reasons()) {
      if (reason.waived()) {
        continue;
      }
      message.append(first ? " " : "；");
      first = false;
      message.append(reason.reasonText());
    }
    return message.toString();
  }

  private ComplianceInterceptPolicyRow policyRow(UUID tenant, String scene) {
    Record row =
        sql.fetchOne(
            "select * from compliance_intercept_policy where tenant_id = ? and scene = ?",
            tenant,
            scene);
    if (row == null) {
      return new ComplianceInterceptPolicyRow(scene, sceneLabel(scene), false, "BLOCK", "", null);
    }
    return new ComplianceInterceptPolicyRow(
        scene,
        sceneLabel(scene),
        Boolean.TRUE.equals(row.get("enabled", Boolean.class)),
        row.get("undecidable_action", String.class),
        row.get("note", String.class),
        row.get("updated_at", OffsetDateTime.class));
  }

  // ==================================================================
  // 对外：判定历史
  // ==================================================================

  /** 判定历史（**真分页 + 真筛选**）。 */
  public ComplianceInterceptDecisionPage decisions(
      String scene, String subjectType, String conclusion, String subjectId, String shopId,
      int page, int size) {
    UUID tenant = currentTenant();
    int safePage = clampPage(page);
    int safeSize = clampSize(size);

    StringBuilder where = new StringBuilder(" where tenant_id = ?");
    List<Object> params = new ArrayList<>();
    params.add(tenant);

    String sceneValue = text(scene).toUpperCase();
    if (!sceneValue.isEmpty()) {
      if (!knownScene(sceneValue)) {
        throw unprocessable("未知的拦截点：" + scene);
      }
      where.append(" and scene = ?");
      params.add(sceneValue);
    }
    String subjectTypeValue = text(subjectType).toUpperCase();
    if (!subjectTypeValue.isEmpty()) {
      if (!knownSubjectType(subjectTypeValue)) {
        throw unprocessable("未知的校验对象：" + subjectType);
      }
      where.append(" and subject_type = ?");
      params.add(subjectTypeValue);
    }
    String conclusionValue = text(conclusion).toUpperCase();
    if (!conclusionValue.isEmpty()) {
      if (!knownConclusion(conclusionValue)) {
        throw unprocessable("未知的结论：" + conclusion);
      }
      where.append(" and conclusion = ?");
      params.add(conclusionValue);
    }
    String subjectValue = text(subjectId);
    if (!subjectValue.isEmpty()) {
      where.append(" and subject_id = ?");
      params.add(subjectValue);
    }
    UUID shop = parseFilterId(shopId);
    if (shop != null) {
      where.append(" and shop_id = ?");
      params.add(shop);
    }

    long total =
        count(sql.fetchValue("select count(*) from compliance_intercept_decision" + where, params.toArray()));

    Map<String, Long> byConclusion = new LinkedHashMap<>();
    for (String code : CONCLUSIONS) {
      byConclusion.put(code, 0L);
    }
    for (Record row :
        sql.fetch(
            "select conclusion, count(*) as n from compliance_intercept_decision"
                + where
                + " group by conclusion",
            params.toArray())) {
      byConclusion.put(row.get("conclusion", String.class), count(row.get("n")));
    }

    List<Object> pageParams = new ArrayList<>(params);
    pageParams.add(safeSize);
    pageParams.add(safePage * safeSize);
    List<Record> rows =
        sql.fetch(
            "select * from compliance_intercept_decision"
                + where
                + " order by decided_at desc, id desc limit ? offset ?",
            pageParams.toArray());

    List<ComplianceInterceptDecision> items = new ArrayList<>();
    for (Record row : rows) {
      items.add(toDecision(tenant, row));
    }
    return new ComplianceInterceptDecisionPage(
        items,
        total,
        safePage,
        safeSize,
        byConclusion.getOrDefault("PASS", 0L),
        byConclusion.getOrDefault("WARN", 0L),
        byConclusion.getOrDefault("BLOCK", 0L),
        byConclusion.getOrDefault("UNDECIDABLE", 0L),
        "🔴 判定凭据**只追加**：这里看到的每一行都是当时那一次的结论，"
            + "规则后来改了也不改写它。");
  }

  /** 单次判定详情（含逐条原因）。 */
  public ComplianceInterceptDecision decision(String rawId) {
    UUID tenant = currentTenant();
    UUID id = parseId(rawId);
    if (id == null) {
      throw notFound();
    }
    Record row =
        sql.fetchOne(
            "select * from compliance_intercept_decision where tenant_id = ? and id = ?", tenant, id);
    if (row == null) {
      throw notFound();
    }
    return toDecision(tenant, row);
  }

  private ComplianceInterceptDecision loadDecision(UUID tenant, UUID decisionId) {
    Record row =
        sql.fetchOne(
            "select * from compliance_intercept_decision where tenant_id = ? and id = ?",
            tenant,
            decisionId);
    if (row == null) {
      throw notFound();
    }
    return toDecision(tenant, row);
  }

  private ComplianceInterceptDecision toDecision(UUID tenant, Record row) {
    UUID decisionId = row.get("id", UUID.class);
    List<ComplianceInterceptReasonRow> reasons = new ArrayList<>();
    for (Record reason :
        sql.fetch(
            """
            select * from compliance_intercept_reason
             where tenant_id = ? and decision_id = ?
             order by reason_code, rule_code nulls last, id
            """,
            tenant,
            decisionId)) {
      reasons.add(
          new ComplianceInterceptReasonRow(
              reason.get("id", UUID.class).toString(),
              reason.get("reason_code", String.class),
              reasonCodeLabel(reason.get("reason_code", String.class)),
              reason.get("reason_text", String.class),
              idText(reason.get("rule_id", UUID.class)),
              idText(reason.get("rule_version_id", UUID.class)),
              reason.get("rule_code", String.class),
              reason.get("rule_type", String.class),
              reason.get("severity", String.class),
              reason.get("field_key", String.class),
              reason.get("matched_term", String.class),
              Boolean.TRUE.equals(reason.get("waived", Boolean.class)),
              idText(reason.get("exemption_id", UUID.class)),
              undecidableReason(reason.get("reason_code", String.class))));
    }

    // 🔴 因豁免放行的指向**从原因行推导**（原因行才是「凭什么放行」的一手证据）。
    List<ComplianceInterceptExemptionRef> exemptions = new ArrayList<>();
    Set<String> seenExemptions = new LinkedHashSet<>();
    for (ComplianceInterceptReasonRow reason : reasons) {
      if (!reason.waived() || reason.exemptionId() == null) {
        continue;
      }
      if (!seenExemptions.add(reason.exemptionId())) {
        continue;
      }
      UUID id = parseId(reason.exemptionId());
      if (id == null) {
        continue;
      }
      Record exemption =
          sql.fetchOne(
              """
              select e.id, e.valid_from, e.valid_to, r.rule_code
                from compliance_intercept_exemption e
                left join compliance_rule r on r.tenant_id = e.tenant_id and r.id = e.rule_id
               where e.tenant_id = ? and e.id = ?
              """,
              tenant,
              id);
      if (exemption == null) {
        continue;
      }
      exemptions.add(
          new ComplianceInterceptExemptionRef(
              id.toString(),
              id.toString(),
              exemption.get("rule_code", String.class),
              exemption.get("valid_from", OffsetDateTime.class),
              exemption.get("valid_to", OffsetDateTime.class),
              "因豁免放行（豁免单 " + id + "）"));
    }

    List<ComplianceInterceptRemedy> remedies = new ArrayList<>();
    for (ComplianceInterceptReasonRow reason : reasons) {
      if (reason.waived()) {
        continue;
      }
      remedies.add(
          new ComplianceInterceptRemedy(
              reason.reasonCode(),
              reason.ruleType() == null
                  ? "补齐规则信息或联系管理员后重新校验"
                  : remedyOf(reason.ruleType()),
              !undecidableReason(reason.reasonCode())));
    }
    remedies.add(new ComplianceInterceptRemedy("EXEMPTION", EXEMPTION_REMEDY, true));

    String conclusion = row.get("conclusion", String.class);
    return new ComplianceInterceptDecision(
        decisionId.toString(),
        row.get("scene", String.class),
        sceneLabel(row.get("scene", String.class)),
        row.get("subject_type", String.class),
        subjectTypeLabel(row.get("subject_type", String.class)),
        row.get("subject_id", String.class),
        idText(row.get("shop_id", UUID.class)),
        idText(row.get("platform_id", UUID.class)),
        idText(row.get("marketplace_id", UUID.class)),
        conclusion,
        conclusionLabel(conclusion),
        Boolean.TRUE.equals(row.get("blocked", Boolean.class)),
        Boolean.TRUE.equals(row.get("release_granted", Boolean.class)),
        intOf(row, "rule_count"),
        intOf(row, "reason_count"),
        intOf(row, "blocking_reason_count"),
        intOf(row, "waived_reason_count"),
        intOf(row, "undecidable_reason_count"),
        reasons,
        exemptions,
        remedies,
        row.get("decided_at", OffsetDateTime.class),
        idText(row.get("decided_by", UUID.class)),
        "🔴 这一行是**当时那一次**的判定：规则版本冻结在原因行里，"
            + "规则后来改了也不改写它。");
  }

  /** 🔴 当前操作人（来自 identity 已认证的成员关系，不是客户端头）。 */
  private UUID currentActor() {
    Object raw = sql.fetchValue("select nullif(current_setting('app.actor',true),'')");
    return raw == null ? null : UUID.fromString(String.valueOf(raw));
  }

  private static String idText(UUID id) {
    return id == null ? null : id.toString();
  }

  // ==================================================================
  // 对外：豁免
  // ==================================================================

  /** 申请豁免（申请人理由必填；有效期必填）。 */
  public ComplianceInterceptExemptionRow applyExemption(
      ComplianceInterceptExemptionInput input, UUID actorId) {
    UUID tenant = currentTenant();
    String scene = text(input.scene()).toUpperCase();
    if (!knownScene(scene)) {
      throw unprocessable("未知的拦截点：" + input.scene());
    }
    String subjectType = text(input.subjectType()).toUpperCase();
    if (!knownSubjectType(subjectType)) {
      throw unprocessable("未知的校验对象：" + input.subjectType());
    }
    String subjectId = text(input.subjectId());
    if (subjectId.isEmpty()) {
      throw unprocessable("主体 id 不能为空");
    }
    UUID ruleId = parseId(input.ruleId());
    if (ruleId == null) {
      throw unprocessable("必须指定被豁免的规则 id（一张豁免只针对一条规则）");
    }
    String reason = text(input.reason());
    if (reason.isEmpty()) {
      throw unprocessable("申请理由不能为空（豁免必须有理由）");
    }
    if (reason.length() > 2000) {
      throw unprocessable("申请理由最多 2000 字");
    }
    Record rule =
        sql.fetchOne(
            "select id from compliance_rule where tenant_id = ? and id = ?", tenant, ruleId);
    if (rule == null) {
      throw unprocessable("规则不存在或不属于当前租户：" + input.ruleId());
    }
    OffsetDateTime now = dbNow();
    OffsetDateTime validFrom = input.validFrom() == null ? now : input.validFrom();
    OffsetDateTime validTo = input.validTo();
    if (validTo == null) {
      throw unprocessable("必须给出豁免有效期（豁免不能永久生效）");
    }
    if (!validTo.isAfter(validFrom)) {
      throw unprocessable("豁免有效期的结束时刻必须晚于开始时刻");
    }
    if (validTo.isAfter(validFrom.plusDays(MAX_EXEMPTION_DAYS))) {
      throw unprocessable("豁免有效期最长 " + MAX_EXEMPTION_DAYS + " 天");
    }

    UUID id = UUID.randomUUID();
    sql.execute(
        """
        insert into compliance_intercept_exemption
          (id,tenant_id,scene,subject_type,subject_id,rule_id,status,
           applicant_id,applicant_reason,valid_from,valid_to)
        values (?,?,?,?,?,?,'PENDING',?,?,?::timestamptz,?::timestamptz)
        """,
        id,
        tenant,
        scene,
        subjectType,
        subjectId,
        ruleId,
        actorId,
        reason,
        validFrom,
        validTo);
    return loadExemption(tenant, id);
  }

  /** 🔴 审批（**申请人不能审批自己**；审批意见必填；不能批一张已过期的豁免）。 */
  public ComplianceInterceptExemptionRow approveExemption(String rawId, String note, UUID actorId) {
    return decideExemption(rawId, note, actorId, "APPROVED");
  }

  /** 🔴 驳回（**申请人不能审批自己**；审批意见必填）。 */
  public ComplianceInterceptExemptionRow rejectExemption(String rawId, String note, UUID actorId) {
    return decideExemption(rawId, note, actorId, "REJECTED");
  }

  private ComplianceInterceptExemptionRow decideExemption(
      String rawId, String note, UUID actorId, String targetStatus) {
    UUID tenant = currentTenant();
    UUID id = parseId(rawId);
    if (id == null) {
      throw notFound();
    }
    Record row =
        sql.fetchOne(
            "select * from compliance_intercept_exemption where tenant_id = ? and id = ?",
            tenant,
            id);
    if (row == null) {
      throw notFound();
    }
    String decisionNote = text(note);
    if (decisionNote.isEmpty()) {
      throw unprocessable("审批意见不能为空（豁免必须留下审批意见）");
    }
    if (decisionNote.length() > 2000) {
      throw unprocessable("审批意见最多 2000 字");
    }
    String status = row.get("status", String.class);
    if (!"PENDING".equals(status)) {
      throw unprocessable("这张豁免单已经是终态（" + status + "），不可回退或改判");
    }
    UUID applicantId = row.get("applicant_id", UUID.class);
    if (applicantId.equals(actorId)) {
      throw unprocessable("申请人不能审批自己的豁免（申请人 ≠ 审批人）");
    }
    OffsetDateTime now = dbNow();
    OffsetDateTime validTo = row.get("valid_to", OffsetDateTime.class);
    if (targetStatus.equals("APPROVED") && validTo != null && !validTo.isAfter(now)) {
      throw unprocessable("这张豁免的有效期已经过期，不能批准（豁免不能永久生效）");
    }
    sql.execute(
        """
        update compliance_intercept_exemption
           set status = ?, approver_id = ?, decision_note = ?, decided_at = ?::timestamptz,
               updated_at = now()
         where tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid
           and id = ? and status = 'PENDING'
        """,
        targetStatus,
        actorId,
        decisionNote,
        now,
        id);
    return loadExemption(tenant, id);
  }

  /** 豁免单清单（**真分页 + 真筛选**）。 */
  public ComplianceInterceptExemptionPage exemptions(
      String scene, String status, String subjectType, String subjectId, String ruleCode,
      int page, int size) {
    UUID tenant = currentTenant();
    int safePage = clampPage(page);
    int safeSize = clampSize(size);

    StringBuilder where = new StringBuilder(" where e.tenant_id = ?");
    List<Object> params = new ArrayList<>();
    params.add(tenant);

    String sceneValue = text(scene).toUpperCase();
    if (!sceneValue.isEmpty()) {
      if (!knownScene(sceneValue)) {
        throw unprocessable("未知的拦截点：" + scene);
      }
      where.append(" and e.scene = ?");
      params.add(sceneValue);
    }
    String statusValue = text(status).toUpperCase();
    if (!statusValue.isEmpty()) {
      if (!knownExemptionStatus(statusValue)) {
        throw unprocessable("未知的豁免状态：" + status);
      }
      where.append(" and e.status = ?");
      params.add(statusValue);
    }
    String subjectTypeValue = text(subjectType).toUpperCase();
    if (!subjectTypeValue.isEmpty()) {
      if (!knownSubjectType(subjectTypeValue)) {
        throw unprocessable("未知的校验对象：" + subjectType);
      }
      where.append(" and e.subject_type = ?");
      params.add(subjectTypeValue);
    }
    String subjectValue = text(subjectId);
    if (!subjectValue.isEmpty()) {
      where.append(" and e.subject_id = ?");
      params.add(subjectValue);
    }
    String ruleCodeValue = text(ruleCode);
    if (!ruleCodeValue.isEmpty()) {
      where.append(
          " and exists (select 1 from compliance_rule r where r.tenant_id = e.tenant_id"
              + " and r.id = e.rule_id and r.rule_code = ?)");
      params.add(ruleCodeValue);
    }

    long total =
        count(
            sql.fetchValue(
                "select count(*) from compliance_intercept_exemption e" + where, params.toArray()));

    Map<String, Long> byStatus = new LinkedHashMap<>();
    for (String code : EXEMPTION_STATUSES) {
      byStatus.put(code, 0L);
    }
    for (Record row :
        sql.fetch(
            "select e.status, count(*) as n from compliance_intercept_exemption e"
                + where
                + " group by e.status",
            params.toArray())) {
      byStatus.put(row.get("status", String.class), count(row.get("n")));
    }

    List<Object> pageParams = new ArrayList<>(params);
    pageParams.add(safeSize);
    pageParams.add(safePage * safeSize);
    List<Record> rows =
        sql.fetch(
            """
            select e.*, r.rule_code, r.rule_type,
                   (select v.title from compliance_rule_version v
                     where v.tenant_id = e.tenant_id and v.rule_id = e.rule_id
                     order by v.version_no desc limit 1) as rule_title
              from compliance_intercept_exemption e
              left join compliance_rule r on r.tenant_id = e.tenant_id and r.id = e.rule_id
            """
                + where
                + " order by e.created_at desc, e.id desc limit ? offset ?",
            pageParams.toArray());

    OffsetDateTime now = dbNow();
    List<ComplianceInterceptExemptionRow> items = new ArrayList<>();
    for (Record row : rows) {
      items.add(toExemption(row, now));
    }
    long effectiveCount =
        count(
            sql.fetchValue(
                "select count(*) from compliance_intercept_exemption e"
                    + where
                    + " and e.status = 'APPROVED' and e.valid_from <= ?::timestamptz"
                    + " and e.valid_to > ?::timestamptz",
                concat(params, now, now)));

    return new ComplianceInterceptExemptionPage(
        items,
        total,
        safePage,
        safeSize,
        byStatus.getOrDefault("PENDING", 0L),
        byStatus.getOrDefault("APPROVED", 0L),
        byStatus.getOrDefault("REJECTED", 0L),
        effectiveCount,
        "🔴 **豁免必须有审批与理由**：申请人 ≠ 审批人、理由必填、有效期必填（过期即失效）。"
            + "「当前生效」按**数据库时钟**现算，不落库。");
  }

  /** 豁免单详情。 */
  public ComplianceInterceptExemptionRow exemption(String rawId) {
    UUID tenant = currentTenant();
    UUID id = parseId(rawId);
    if (id == null) {
      throw notFound();
    }
    Record row =
        sql.fetchOne(
            """
            select e.*, r.rule_code, r.rule_type,
                   (select v.title from compliance_rule_version v
                     where v.tenant_id = e.tenant_id and v.rule_id = e.rule_id
                     order by v.version_no desc limit 1) as rule_title
              from compliance_intercept_exemption e
              left join compliance_rule r on r.tenant_id = e.tenant_id and r.id = e.rule_id
             where e.tenant_id = ? and e.id = ?
            """,
            tenant,
            id);
    if (row == null) {
      throw notFound();
    }
    return toExemption(row, dbNow());
  }

  private ComplianceInterceptExemptionRow loadExemption(UUID tenant, UUID id) {
    Record row =
        sql.fetchOne(
            """
            select e.*, r.rule_code, r.rule_type,
                   (select v.title from compliance_rule_version v
                     where v.tenant_id = e.tenant_id and v.rule_id = e.rule_id
                     order by v.version_no desc limit 1) as rule_title
              from compliance_intercept_exemption e
              left join compliance_rule r on r.tenant_id = e.tenant_id and r.id = e.rule_id
             where e.tenant_id = ? and e.id = ?
            """,
            tenant,
            id);
    if (row == null) {
      throw notFound();
    }
    return toExemption(row, dbNow());
  }

  private ComplianceInterceptExemptionRow toExemption(Record row, OffsetDateTime now) {
    String status = row.get("status", String.class);
    OffsetDateTime validFrom = row.get("valid_from", OffsetDateTime.class);
    OffsetDateTime validTo = row.get("valid_to", OffsetDateTime.class);
    boolean expired = validTo != null && !validTo.isAfter(now);
    boolean effective =
        "APPROVED".equals(status)
            && validFrom != null
            && validTo != null
            && !validFrom.isAfter(now)
            && validTo.isAfter(now);
    // 🔴 当前登录人能不能审批：**申请人不能审批自己**（职责分离，服务端算好，页面据此显示）。
    UUID actor = currentActor();
    UUID applicant = row.get("applicant_id", UUID.class);
    boolean decidable =
        "PENDING".equals(status) && actor != null && applicant != null && !applicant.equals(actor);
    return new ComplianceInterceptExemptionRow(
        row.get("id", UUID.class).toString(),
        row.get("scene", String.class),
        sceneLabel(row.get("scene", String.class)),
        row.get("subject_type", String.class),
        subjectTypeLabel(row.get("subject_type", String.class)),
        row.get("subject_id", String.class),
        idText(row.get("rule_id", UUID.class)),
        row.get("rule_code", String.class),
        row.get("rule_title", String.class),
        status,
        exemptionStatusLabel(status),
        idText(row.get("applicant_id", UUID.class)),
        row.get("applicant_reason", String.class),
        idText(row.get("approver_id", UUID.class)),
        row.get("decision_note", String.class),
        validFrom,
        validTo,
        expired,
        effective,
        decidable,
        row.get("decided_at", OffsetDateTime.class),
        row.get("created_at", OffsetDateTime.class));
  }

  /** 把「参数列表 + 两个时刻」拼成 jOOQ 的可变参数。 */
  private static Object[] concat(List<Object> rest, OffsetDateTime first, OffsetDateTime second) {
    List<Object> all = new ArrayList<>();
    all.addAll(rest);
    all.add(first);
    all.add(second);
    return all.toArray();
  }
}
