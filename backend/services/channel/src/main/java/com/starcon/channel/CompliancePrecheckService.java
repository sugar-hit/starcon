package com.starcon.channel;

import static com.starcon.channel.CompliancePrecheckModels.*;
import static com.starcon.channel.CompliancePrecheckRules.*;
import static com.starcon.channel.ListingEditorFieldRules.LABELS;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
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
 * 16.2.2.1 合规预检。
 *
 * <p>蓝图：「发布前的合规体检：内容违规、认证缺失、尺寸规格不符、材质与描述不符、危险品与运输限制、标签与说明书要求，
 * 多维度检查并给出整改建议。输入：listing 内容草稿；商品属性；认证状态；平台要求。输出：体检报告（分维度得分 +
 * 问题清单 + 整改建议）。关系：上游 = 4.2.1.1 编辑器；下游 = 16.2.1.2 拦截。<b>预检在保存草稿时就跑一次</b>（即时反馈），
 * <b>发布时再跑一次</b>（强制校验）。」
 *
 * <p>🔴🔴 本类里有四处**结构**上的不变量：
 *
 * <ol>
 *   <li><b>两种触发语义，报告能区分</b>：{@code trigger_kind} 取 {@code DRAFT_SAVE}（保存草稿时的
 *       即时反馈）或 {@code PUBLISH_GATE}（发布前的强制校验）。同一份草稿跑两次 ⇒ 两份报告，
 *       历史里分得清。⚠️ <b>本模块不执行拦截</b>：拦截、豁免、审批归 16.2.1.2，
 *       这里只把结论算出来交给它。
 *   <li><b>不可知 ≠ 0 分</b>：量不到的检查项 state=UNKNOWN 且不进分母；量不到的维度 score=null
 *       且权重不进总分分母；全维度不可知时总体得分给 null（不是 0）。
 *       判定在 {@link CompliancePrecheckRules}，那里只有「已交付来源」的依据。
 *   <li><b>只读上游，绝不写上游</b>：本类**只写** {@code compliance_check_report} /
 *       {@code compliance_check_finding} / {@code compliance_check_dimension_score} 三张表；
 *       草稿表 {@code listing_editor_draft}（4.2.1.1）**只读**，写入所有权仍在编辑器手里。
 *   <li><b>报告是那一次判定的凭据</b>：三张表都由 V197 的追加式触发器钉住，事后改写不了。
 * </ol>
 *
 * <p>跨服务只经授权 HTTP API 读（{@link CompliancePrecheckSourcePort}）：03 商品主数据与 03 品牌。
 * 租户不由本服务传递 —— 转发的是调用方自己的 bearer，上游仍按已认证成员关系解析租户。
 */
@Service
public class CompliancePrecheckService {

  private static final String SCORE_SEMANTICS =
      "得分一律是 0..1 的比值（不是百分数：数据库列 NUMERIC(5,4) 装不下 100.0000）。"
          + "维度得分 = 该维度通过数 /（通过数 + 违规数）；总体得分 = Σ(维度得分 × 权重) / Σ(被评出分的维度的权重)。";

  private static final String VERDICT_SEMANTICS =
      "结论三态：UNKNOWN = 一个维度都评不出分（判不了，不是「合规」）；"
          + "ATTENTION = 至少有一条违规；PASS = 没有违规。"
          + "🔴 PASS **不等于**「全维度都合规」：量不到的维度不产生违规，"
          + "所以看结论时必须一起看 hasUnknownDimensions 与各维度的 UNKNOWN 标记。";

  private static final String UNKNOWN_SEMANTICS =
      "🔴「量不到」是一个状态，不是一个 0 分：量不到的检查项不计入该维度的分母，"
          + "量不到的维度不计入总分的分母（权重也不进），全维度都量不到时总体得分是 null 而不是 0。"
          + "量到的维度同时给出归属模块与整改建议 —— 界面显示「未知」，不显示 0。";

  private static final String REPORT_NOTE =
      "一次体检一份报告：分维度得分 + 问题清单 + 整改建议。报告是那一次判定的凭据（V197 追加式触发器钉住，"
          + "事后改写不了），所以「昨天那次为什么拦住」永远答得出来。";

  private static final String ENFORCEMENT_NOTE =
      "🔴 本模块**不执行拦截**：发布前的阻断、豁免申请与审批归 16.2.1.2 拦截执行（未交付）。"
          + "这里只把结论算出来（PASS / ATTENTION / UNKNOWN）交给它消费，界面上的「发布前强制校验」"
          + "是一次体检，不是一次放行决定。";

  private static final String BOUNDARY_NOTE =
      "🔴 与 4.2.3.2「合规预检（违禁词 / 侵权）」是**两个模块**：那边出「阻断项 + 提醒项」的风险清单"
          + "（卷 04，路由 /listings/compliance），这边出「分维度得分 + 问题清单 + 整改建议」的体检报告"
          + "（卷 16，路由 /compliance/precheck）。两边不共用表、不共用端点、不共用页面。";

  private static final String UPSTREAM_NOTE =
      "上游 = 4.2.1.1 刊登编辑器的内容草稿（只读）+ 2.1.1.2 平台能力矩阵 + V194 平台字段要求注册表 + "
          + "catalog 3.2.1.2 品牌授权与刊登资格（只经授权 HTTP API 读）。";

  private final DSLContext sql;
  private final ObjectMapper json;
  private final CompliancePrecheckSourcePort source;

  public CompliancePrecheckService(
      DSLContext sql, ObjectMapper json, CompliancePrecheckSourcePort source) {
    this.sql = sql;
    this.json = json;
    this.source = source;
  }

  // ==================================================================
  // 小工具（照抄 ListingEditorService 的同名写法）
  // ==================================================================

  private static String text(String value) {
    return value == null ? "" : value.trim();
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

  private String jsonText(Object raw) {
    if (raw == null) {
      return "{}";
    }
    String value = raw instanceof org.jooq.JSONB j ? j.data() : String.valueOf(raw);
    if ("{}".equals(value) || value.isBlank()) {
      return value;
    }
    try {
      return json.writeValueAsString(json.readTree(value));
    } catch (Exception e) {
      return value;
    }
  }

  private String writeJson(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException("内容序列化失败", e);
    }
  }

  private com.fasterxml.jackson.databind.JsonNode readNode(Object raw) {
    try {
      String text = jsonText(raw);
      if (text == null || text.isBlank()) {
        return null;
      }
      return json.readTree(text);
    } catch (Exception e) {
      return null;
    }
  }

  // ==================================================================
  // 能力声明
  // ==================================================================

  /**
   * 逐维度能力声明。
   *
   * <p>🔴 五个维度当前**量不到**，如实报出 ownerModule 与影响：16.2.2.2（认证档案）、
   * 16.2.1.1（尺寸 / 材质 / 危险品 / 标签细则）、4.2.3.2（违禁词 / 侵权扫描）。
   * 4.2.3.2 是<b>同批并行开发、此刻不存在</b>，不是「永远不存在」。
   */
  public CompliancePrecheckCapabilities capabilities() {
    List<CompliancePrecheckCapabilityDimension> dimensions = new ArrayList<>();
    for (Dimension dimension : DIMENSIONS) {
      boolean available = dimension.basisOwnerModule() == null;
      dimensions.add(
          new CompliancePrecheckCapabilityDimension(
              dimension.code(),
              dimension.label(),
              available,
              available
                  ? "V194 platform_listing_field_requirement + 2.1.1.2 platform.capabilities + "
                      + "4.2.1.1 listing_editor_draft + catalog 3.2.1.2 品牌授权"
                  : dimension.basisOwnerModule(),
              available ? "" : dimension.basisOwnerModule(),
              available
                  ? "该维度的判定依据全部来自已交付来源，逐条检查项在报告里给结论"
                  : dimension.basisNote()));
    }
    return new CompliancePrecheckCapabilities(
        dimensions,
        DIMENSION_ORDER,
        CHECK_STATES,
        SEVERITIES,
        TRIGGER_KINDS,
        SCORE_SEMANTICS,
        VERDICT_SEMANTICS,
        UNKNOWN_SEMANTICS,
        REPORT_NOTE,
        ENFORCEMENT_NOTE,
        BOUNDARY_NOTE,
        UPSTREAM_NOTE);
  }

  // ==================================================================
  // 输入侧：可体检的草稿（4.2.1.1 的草稿，本模块只读）
  // ==================================================================

  /** 可体检的草稿列表（**真分页**：筛选 / 排序 / LIMIT / OFFSET 全在 SQL 上）。 */
  public CompliancePrecheckDraftPage drafts(UUID shopId, int page, int size) {
    UUID tenant = currentTenant();
    int limit = Math.max(1, Math.min(size, 200));
    int offset = Math.max(0, page) * limit;

    long total =
        ((Number)
                sql.fetchValue(
                    """
                    select count(*) from listing_editor_draft d
                    where d.tenant_id = ? and d.state <> 'DISCARDED'
                      and (?::uuid is null or d.shop_id = ?::uuid)
                    """,
                    tenant,
                    shopId,
                    shopId))
            .longValue();

    List<Record> rows =
        sql.fetch(
            """
            select d.id, d.shop_id, p.code as platform_code, m.code as marketplace_code, d.locale,
                   d.state, d.source_sku_id, d.updated_at,
                   coalesce(d.field_values->>'TITLE','') as title
            from listing_editor_draft d
            join platform p on p.id = d.platform_id
            join marketplace m on m.id = d.marketplace_id
            where d.tenant_id = ?
              and d.state <> 'DISCARDED'
              and (?::uuid is null or d.shop_id = ?::uuid)
            order by d.updated_at desc, d.id
            limit ? offset ?
            """,
            tenant,
            shopId,
            shopId,
            limit,
            offset);

    List<CompliancePrecheckDraft> items = new ArrayList<>();
    for (Record row : rows) {
      items.add(
          new CompliancePrecheckDraft(
              row.get("id", UUID.class).toString(),
              row.get("shop_id", UUID.class).toString(),
              row.get("platform_code", String.class),
              row.get("marketplace_code", String.class),
              row.get("locale", String.class),
              row.get("title", String.class),
              row.get("state", String.class),
              row.get("source_sku_id", UUID.class) == null
                  ? null
                  : row.get("source_sku_id", UUID.class).toString(),
              row.get("updated_at", OffsetDateTime.class) == null
                  ? ""
                  : row.get("updated_at", OffsetDateTime.class).toString()));
    }
    return new CompliancePrecheckDraftPage(items, total, Math.max(0, page), limit);
  }

  // ==================================================================
  // 体检
  // ==================================================================

  /**
   * 🔴 跑一次体检并落一份报告。
   *
   * @param draftId 被体检的草稿（4.2.1.1）
   * @param triggerKind {@code DRAFT_SAVE}（保存时的即时反馈）或 {@code PUBLISH_GATE}（发布前强制校验）
   * @param bearer 调用方的 bearer（转发给 catalog，租户仍由上游按已认证成员关系解析）
   */
  public CompliancePrecheckReport check(String draftId, String triggerKind, String bearer) {
    if (!TRIGGER_KINDS.contains(triggerKind)) {
      throw unprocessable("触发语义只能是 DRAFT_SAVE（保存草稿时的即时反馈）或 PUBLISH_GATE（发布前强制校验）");
    }
    UUID tenant = currentTenant();
    Draft draft = draft(draftId);
    if (draft == null) {
      throw notFound();
    }

    // ---- 平台侧依据：字段要求注册表 + 能力矩阵 ----
    List<ListingEditorFieldRules.Requirement> requirements = requirements(draft);
    Map<String, Boolean> capabilities = capabilities(draft.platformId());

    // ---- 上游只读：源商品（品牌解析用）与品牌授权 ----
    CompliancePrecheckSourcePort.SourceProduct product =
        draft.sourceSkuId() == null
            ? new CompliancePrecheckSourcePort.SourceProduct(
                false, "", "", List.of(), List.of(), "草稿没有关联源商品")
            : source.sku(draft.sourceSkuId().toString(), bearer);
    CompliancePrecheckSourcePort.BrandReference brand =
        product.available() && !text(product.brand()).isEmpty()
            ? source.brand(product.brand(), bearer)
            : new CompliancePrecheckSourcePort.BrandReference(
                false, null, product.brand(), "", product.note());
    CompliancePrecheckSourcePort.BrandAuthorizations authorizations =
        brand.available() ? source.authorizations(brand.brandId(), bearer) : null;
    CompliancePrecheckSourcePort.BrandEligibility eligibility =
        brand.available() ? source.eligibility(brand.brandId(), bearer) : null;

    // ---- 逐维度判定 ----
    List<DimensionOutcome> outcomes = new ArrayList<>();
    Map<String, List<CheckResult>> resultsByDimension = new LinkedHashMap<>();
    List<CheckResult> contentResults =
        evaluateContent(
            new ContentInput(
                requirements,
                shapes(draft.fieldValues(), kinds(requirements)),
                capabilities,
                brand,
                authorizations,
                eligibility));
    outcomes.add(score(dimension("CONTENT"), contentResults));
    resultsByDimension.put("CONTENT", contentResults);
    for (Dimension dimension : DIMENSIONS) {
      if (dimension.code().equals("CONTENT")) {
        continue;
      }
      // 🔴 其余五个维度当前**量不到**：各带一条 UNKNOWN 检查项 + 归属模块 + 整改建议，
      //    不进分母（量不到不是 0 分），也不被悄悄丢掉。
      List<CheckResult> unmeasurable = List.of(unmeasurable(dimension));
      outcomes.add(score(dimension, unmeasurable));
      resultsByDimension.put(dimension.code(), unmeasurable);
    }

    // ---- 汇总 ----
    BigDecimal overall = overall(outcomes);
    int scored = 0;
    int unknown = 0;
    int checkItems = 0;
    int passed = 0;
    int failed = 0;
    int unknownItems = 0;
    for (DimensionOutcome outcome : outcomes) {
      if ("SCORED".equals(outcome.state())) {
        scored++;
      } else {
        unknown++;
      }
      checkItems += outcome.checkItemCount();
      passed += outcome.passedItemCount();
      failed += outcome.failedItemCount();
      unknownItems += outcome.unknownItemCount();
    }
    String state = scored > 0 ? "SCORED" : "UNKNOWN";
    String verdict = scored == 0 ? "UNKNOWN" : (failed > 0 ? "ATTENTION" : "PASS");
    boolean hasUnknown = unknown > 0;

    // ---- 问题清单：违规项 + 「量不到」项（后者也必须显式出现；通过项不是问题，不进清单） ----
    // 🔴 顺序在这里定死：**先违规、再量不到**，同一组里按平台要求注册表的字段序。
    //    序号（seq）随行落库 —— 读的时候不靠「数据库碰巧怎么返回」决定顺序
    //    （本表有 (tenant_id, report_id, dimension_code, id) 索引，按随机 UUID 扫出来的行序是随机的）。
    List<FindingRow> findings = new ArrayList<>();
    for (DimensionOutcome outcome : outcomes) {
      List<CheckResult> scoped =
          resultsByDimension.getOrDefault(outcome.dimension().code(), List.of());
      for (String wanted : List.of("VIOLATION", "UNKNOWN")) {
        for (CheckResult result : scoped) {
          if (!wanted.equals(result.state())) {
            continue;
          }
          findings.add(
              new FindingRow(
                  outcome.dimension().code(),
                  outcome.dimension().label(),
                  result.checkCode(),
                  result.subjectRef(),
                  result.location(),
                  result.severity(),
                  result.state(),
                  result.message(),
                  result.suggestion(),
                  result.ownerModule(),
                  result.evidence()));
        }
      }
    }

    // ---- 整改建议（按维度归并） ----
    List<CompliancePrecheckRemediation> remediation = remediation(outcomes, findings);

    Map<String, Object> snapshot = new LinkedHashMap<>();
    snapshot.put("draftId", draft.id().toString());
    snapshot.put("draftState", draft.state());
    snapshot.put("fieldValues", draft.fieldValues());
    snapshot.put("platformId", draft.platformId().toString());
    snapshot.put("marketplaceId", draft.marketplaceId().toString());
    snapshot.put("categoryPath", draft.categoryPath());
    snapshot.put("sourceSkuId", draft.sourceSkuId() == null ? null : draft.sourceSkuId().toString());
    snapshot.put("sourceBrand", product.brand());
    snapshot.put("sourceAvailable", product.available());
    snapshot.put("sourceNote", product.note());
    snapshot.put("sourceSpecs", product.specs());
    snapshot.put("sourceAttributes", product.attributes());
    snapshot.put("brandAvailable", brand.available());
    snapshot.put("brandNote", brand.note());
    snapshot.put("capabilities", capabilities);
    snapshot.put("fieldRequirements", requirementSnapshot(requirements));
    snapshot.put(
        "sourceAttributeNote",
        "源商品的规格与属性已取到，但「尺寸规格不符 / 材质与描述不符」的比对细则归 16.2.1.1 规则库（未交付），"
            + "因此这两维仍为未知。");

    // ---- 落库 ----
    UUID reportId = UUID.randomUUID();
    OffsetDateTime checkedAt = OffsetDateTime.now();
    sql.execute(
        """
        insert into compliance_check_report
          (id,tenant_id,draft_id,shop_id,platform_id,marketplace_id,locale,trigger_kind,
           state,overall_score,verdict,has_unknown_dimensions,
           scored_dimension_count,unknown_dimension_count,total_dimension_count,
           check_item_count,passed_item_count,failed_item_count,unknown_item_count,
           source_snapshot,remediation,checked_at,created_at)
        values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?::jsonb,?::timestamptz,now())
        """,
        reportId,
        tenant,
        draft.id(),
        draft.shopId(),
        draft.platformId(),
        draft.marketplaceId(),
        draft.locale(),
        triggerKind,
        state,
        overall,
        verdict,
        hasUnknown,
        scored,
        unknown,
        outcomes.size(),
        checkItems,
        passed,
        failed,
        unknownItems,
        writeJson(snapshot),
        writeJson(remediation),
        checkedAt);

    for (DimensionOutcome outcome : outcomes) {
      sql.execute(
          """
          insert into compliance_check_dimension_score
            (id,tenant_id,report_id,dimension_code,dimension_label,weight,score,state,
             check_item_count,passed_item_count,failed_item_count,unknown_item_count,
             owner_module,unavailable_reason,note,created_at)
          values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,now())
          """,
          UUID.randomUUID(),
          tenant,
          reportId,
          outcome.dimension().code(),
          outcome.dimension().label(),
          outcome.dimension().weight(),
          outcome.score(),
          outcome.state(),
          outcome.checkItemCount(),
          outcome.passedItemCount(),
          outcome.failedItemCount(),
          outcome.unknownItemCount(),
          outcome.ownerModule(),
          outcome.unavailableReason(),
          outcome.dimension().basisNote(),
          checkedAt);
    }

    for (int index = 0; index < findings.size(); index++) {
      FindingRow finding = findings.get(index);
      sql.execute(
          """
          insert into compliance_check_finding
            (id,tenant_id,report_id,seq,dimension_code,dimension_label,check_code,subject_ref,
             location,severity,finding_state,message,suggestion,owner_module,evidence,created_at)
          values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,now())
          """,
          UUID.randomUUID(),
          tenant,
          reportId,
          index,
          finding.dimensionCode(),
          finding.dimensionLabel(),
          finding.checkCode(),
          finding.subjectRef(),
          finding.location(),
          finding.severity(),
          finding.state(),
          finding.message(),
          finding.suggestion(),
          finding.ownerModule(),
          writeJson(finding.evidence()));
    }

    return report(reportId.toString());
  }

  // ==================================================================
  // 读报告
  // ==================================================================

  /** 单份报告（含分维度得分 + 问题清单 + 整改建议）。跨租户与不存在返回逐字相同的 404。 */
  public CompliancePrecheckReport report(String reportId) {
    UUID tenant = currentTenant();
    UUID id = CompliancePrecheckSourcePort.parseId(reportId);
    if (id == null) {
      throw notFound();
    }
    Record row =
        sql.fetchOne(
            """
            select r.id, r.draft_id, r.trigger_kind, r.state, r.overall_score, r.verdict,
                   r.has_unknown_dimensions, r.scored_dimension_count, r.unknown_dimension_count,
                   r.total_dimension_count, r.check_item_count, r.failed_item_count,
                   r.unknown_item_count, r.locale, r.checked_at,
                   p.code as platform_code, m.code as marketplace_code
            from compliance_check_report r
            join platform p on p.id = r.platform_id
            join marketplace m on m.id = r.marketplace_id
            where r.tenant_id = ? and r.id = ?
            """,
            tenant,
            id);
    if (row == null) {
      throw notFound();
    }

    List<CompliancePrecheckDimensionScore> dimensions = new ArrayList<>();
    for (Record score :
        sql.fetch(
            """
            select dimension_code, dimension_label, weight, score, state,
                   check_item_count, passed_item_count, failed_item_count, unknown_item_count,
                   owner_module, unavailable_reason, note
            from compliance_check_dimension_score
            where tenant_id = ? and report_id = ?
            order by dimension_code
            """,
            tenant,
            id)) {
      dimensions.add(
          new CompliancePrecheckDimensionScore(
              score.get("dimension_code", String.class),
              score.get("dimension_label", String.class),
              score.get("weight", BigDecimal.class),
              score.get("score", BigDecimal.class),
              scoreLabel(score.get("score", BigDecimal.class)),
              score.get("state", String.class),
              intOf(score, "check_item_count"),
              intOf(score, "passed_item_count"),
              intOf(score, "failed_item_count"),
              intOf(score, "unknown_item_count"),
              score.get("owner_module", String.class),
              score.get("unavailable_reason", String.class),
              score.get("note", String.class)));
    }
    dimensions.sort(
        (a, b) -> Integer.compare(order(a.dimensionCode()), order(b.dimensionCode())));

    List<CompliancePrecheckFinding> findings = new ArrayList<>();
    for (Record finding :
        sql.fetch(
            """
            select id, dimension_code, dimension_label, check_code, subject_ref, location,
                   severity, finding_state, message, suggestion, owner_module, evidence
            from compliance_check_finding
            where tenant_id = ? and report_id = ?
            order by seq
            """,
            tenant,
            id)) {
      findings.add(
          new CompliancePrecheckFinding(
              finding.get("id", UUID.class).toString(),
              finding.get("dimension_code", String.class),
              finding.get("dimension_label", String.class),
              finding.get("check_code", String.class),
              finding.get("subject_ref", String.class),
              finding.get("location", String.class),
              finding.get("severity", String.class),
              finding.get("finding_state", String.class),
              finding.get("message", String.class),
              finding.get("suggestion", String.class),
              finding.get("owner_module", String.class),
              evidenceMap(finding.get("evidence"))));
    }

    List<CompliancePrecheckRemediation> remediation = new ArrayList<>();
    for (Record action :
        sql.fetch(
            """
            select remediation from compliance_check_report
            where tenant_id = ? and id = ?
            """,
            tenant,
            id)) {
      remediation.addAll(readRemediation(action.get("remediation")));
    }

    BigDecimal overallScore = row.get("overall_score", BigDecimal.class);
    CompliancePrecheckReportHeader header =
        new CompliancePrecheckReportHeader(
            row.get("id", UUID.class).toString(),
            row.get("draft_id", UUID.class).toString(),
            row.get("trigger_kind", String.class),
            triggerLabel(row.get("trigger_kind", String.class)),
            row.get("state", String.class),
            overallScore,
            overallScore == null ? "UNKNOWN" : "SCORED",
            scoreLabel(overallScore),
            row.get("verdict", String.class),
            Boolean.TRUE.equals(row.get("has_unknown_dimensions", Boolean.class)),
            intOf(row, "total_dimension_count"),
            intOf(row, "scored_dimension_count"),
            intOf(row, "unknown_dimension_count"),
            intOf(row, "check_item_count"),
            intOf(row, "failed_item_count"),
            intOf(row, "unknown_item_count"),
            row.get("platform_code", String.class),
            row.get("marketplace_code", String.class),
            row.get("locale", String.class),
            row.get("checked_at", OffsetDateTime.class) == null
                ? ""
                : row.get("checked_at", OffsetDateTime.class).toString());
    return new CompliancePrecheckReport(header, dimensions, findings, remediation);
  }

  /** 报告历史（**真分页**：筛选 / 排序 / LIMIT / OFFSET 全在 SQL 上）。 */
  public CompliancePrecheckReportPage reports(UUID draftId, String triggerKind, int page, int size) {
    UUID tenant = currentTenant();
    int limit = Math.max(1, Math.min(size, 200));
    int offset = Math.max(0, page) * limit;

    long total =
        ((Number)
                sql.fetchValue(
                    """
                    select count(*) from compliance_check_report r
                    where r.tenant_id = ?
                      and (?::uuid is null or r.draft_id = ?::uuid)
                      and (?::text is null or r.trigger_kind = ?::text)
                    """,
                    tenant,
                    draftId,
                    draftId,
                    triggerKind,
                    triggerKind))
            .longValue();

    List<Record> rows =
        sql.fetch(
            """
            select r.id, r.draft_id, r.trigger_kind, r.state, r.overall_score, r.verdict,
                   r.failed_item_count, r.unknown_item_count, r.checked_at,
                   p.code as platform_code, m.code as marketplace_code,
                   coalesce(d.field_values->>'TITLE','') as draft_title
            from compliance_check_report r
            join platform p on p.id = r.platform_id
            join marketplace m on m.id = r.marketplace_id
            left join listing_editor_draft d
                   on d.tenant_id = r.tenant_id and d.id = r.draft_id
            where r.tenant_id = ?
              and (?::uuid is null or r.draft_id = ?::uuid)
              and (?::text is null or r.trigger_kind = ?::text)
            order by r.checked_at desc, r.id
            limit ? offset ?
            """,
            tenant,
            draftId,
            draftId,
            triggerKind,
            triggerKind,
            limit,
            offset);

    List<CompliancePrecheckReportSummary> items = new ArrayList<>();
    for (Record row : rows) {
      BigDecimal overallScore = row.get("overall_score", BigDecimal.class);
      items.add(
          new CompliancePrecheckReportSummary(
              row.get("id", UUID.class).toString(),
              row.get("draft_id", UUID.class).toString(),
              row.get("draft_title", String.class),
              row.get("trigger_kind", String.class),
              triggerLabel(row.get("trigger_kind", String.class)),
              row.get("state", String.class),
              overallScore,
              scoreLabel(overallScore),
              row.get("verdict", String.class),
              intOf(row, "failed_item_count"),
              intOf(row, "unknown_item_count"),
              row.get("platform_code", String.class),
              row.get("marketplace_code", String.class),
              row.get("checked_at", OffsetDateTime.class) == null
                  ? ""
                  : row.get("checked_at", OffsetDateTime.class).toString()));
    }
    return new CompliancePrecheckReportPage(items, total, Math.max(0, page), limit);
  }

  // ==================================================================
  // 内部形状与查询
  // ==================================================================

  /** 内部用的一条 finding（落库前）。 */
  private record FindingRow(
      String dimensionCode,
      String dimensionLabel,
      String checkCode,
      String subjectRef,
      String location,
      String severity,
      String state,
      String message,
      String suggestion,
      String ownerModule,
      Map<String, Object> evidence) {}

  /** 被体检的草稿。 */
  private record Draft(
      UUID id,
      UUID shopId,
      UUID platformId,
      UUID marketplaceId,
      String locale,
      String categoryPath,
      UUID sourceSkuId,
      String state,
      Map<String, Object> fieldValues) {}

  private Draft draft(String draftId) {
    UUID id = CompliancePrecheckSourcePort.parseId(draftId);
    if (id == null) {
      return null;
    }
    Record row =
        sql.fetchOne(
            """
            select id, shop_id, platform_id, marketplace_id, locale, category_path, source_sku_id,
                   state, field_values
            from listing_editor_draft
            where tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid
              and id = ?
            """,
            id);
    if (row == null) {
      return null;
    }
    Map<String, Object> values = new LinkedHashMap<>();
    var node = readNode(row.get("field_values"));
    if (node != null && node.isObject()) {
      node.fields()
          .forEachRemaining(entry -> values.put(entry.getKey(), toPlain(entry.getValue())));
    }
    return new Draft(
        row.get("id", UUID.class),
        row.get("shop_id", UUID.class),
        row.get("platform_id", UUID.class),
        row.get("marketplace_id", UUID.class),
        row.get("locale", String.class),
        row.get("category_path", String.class),
        row.get("source_sku_id", UUID.class),
        row.get("state", String.class),
        values);
  }

  /** 平台字段要求（**就近覆盖**：站点 + 类目 > 站点 > 类目 > 平台，覆盖在 SQL 里做）。 */
  private List<ListingEditorFieldRules.Requirement> requirements(Draft draft) {
    List<Record> rows =
        sql.fetch(
            """
            select distinct on (r.field_key)
                   r.field_key, r.value_kind, r.requirement, r.min_length, r.max_length,
                   r.min_items, r.max_items, r.requires_capability, r.editable, r.value_source,
                   r.constraint_owner_module, r.note
            from platform_listing_field_requirement r
            where r.platform_id = ? and r.enabled
              and (r.marketplace_id is null or r.marketplace_id = ?)
              and (r.category_path is null or r.category_path = ?)
            order by r.field_key, (r.marketplace_id is not null) desc, (r.category_path is not null) desc
            """,
            draft.platformId(),
            draft.marketplaceId(),
            draft.categoryPath() == null ? "" : draft.categoryPath());
    List<ListingEditorFieldRules.Requirement> result = new ArrayList<>();
    for (Record row : rows) {
      result.add(
          new ListingEditorFieldRules.Requirement(
              row.get("field_key", String.class),
              row.get("value_kind", String.class),
              row.get("requirement", String.class),
              row.get("min_length", Integer.class),
              row.get("max_length", Integer.class),
              row.get("min_items", Integer.class),
              row.get("max_items", Integer.class),
              row.get("requires_capability", String.class),
              Boolean.TRUE.equals(row.get("editable", Boolean.class)),
              row.get("value_source", String.class),
              row.get("constraint_owner_module", String.class),
              row.get("note", String.class)));
    }
    return result;
  }

  /** 平台能力矩阵：键缺失 = 未声明（**不是 false**）。 */
  private Map<String, Boolean> capabilities(UUID platformId) {
    Map<String, Boolean> result = new LinkedHashMap<>();
    Object raw = sql.fetchValue("select capabilities from platform where id = ?", platformId);
    var node = readNode(raw);
    if (node == null || !node.isObject()) {
      return result;
    }
    node.fields()
        .forEachRemaining(
            entry -> {
              var value = entry.getValue();
              if (value.isBoolean()) {
                result.put(entry.getKey(), value.booleanValue());
              } else if (value.isTextual()) {
                result.put(entry.getKey(), Boolean.parseBoolean(value.textValue()));
              }
            });
    return result;
  }

  /** 字段键 → 取值形状（value_kind）。 */
  private Map<String, String> kinds(List<ListingEditorFieldRules.Requirement> requirements) {
    Map<String, String> result = new LinkedHashMap<>();
    for (ListingEditorFieldRules.Requirement requirement : requirements) {
      result.put(requirement.field(), requirement.valueKind());
    }
    return result;
  }

  /**
   * 草稿内容 → 各栏的形状摘要。
   *
   * <p>🔴 与编辑器（4.2.1.1）<b>同一套形状语义</b>：长度按码位算（{@code codePointCount}），
   * 列表取最长项 —— 同一份草稿在编辑器里报「长度超限」，在体检里也必须报同一件事。
   */
  private Map<String, ListingEditorFieldRules.ValueShape> shapes(
      Map<String, Object> fieldValues, Map<String, String> kinds) {
    Map<String, ListingEditorFieldRules.ValueShape> result = new LinkedHashMap<>();
    for (Map.Entry<String, String> entry : kinds.entrySet()) {
      String key = entry.getKey();
      Object raw = fieldValues.get(key);
      if (raw == null) {
        continue;
      }
      String kind = entry.getValue();
      switch (kind) {
        case "TEXT", "LONG_TEXT", "VIDEO_REF" -> {
          String value = raw instanceof String s ? s : String.valueOf(raw);
          result.put(
              key,
              new ListingEditorFieldRules.ValueShape(
                  true, !value.isBlank(), value.codePointCount(0, value.length()), 0));
        }
        case "TEXT_LIST", "IMAGE_LIST" -> {
          List<String> items = stringList(raw);
          int longest = 0;
          for (String item : items) {
            longest = Math.max(longest, item.codePointCount(0, item.length()));
          }
          result.put(
              key, new ListingEditorFieldRules.ValueShape(true, !items.isEmpty(), longest, items.size()));
        }
        case "MODULE_LIST" -> {
          List<Object> modules = objectList(raw);
          int longest = 0;
          for (Object module : modules) {
            if (module instanceof Map<?, ?> map && map.get("body") != null) {
              String body = String.valueOf(map.get("body"));
              longest = Math.max(longest, body.codePointCount(0, body.length()));
            }
          }
          result.put(
              key,
              new ListingEditorFieldRules.ValueShape(true, !modules.isEmpty(), longest, modules.size()));
        }
        case "ATTRIBUTE_MAP", "STATEMENT_MAP" -> {
          Map<String, Object> map = stringMap(raw);
          int longest = 0;
          for (Object value : map.values()) {
            String text = value == null ? "" : String.valueOf(value);
            longest = Math.max(longest, text.codePointCount(0, text.length()));
          }
          result.put(
              key,
              new ListingEditorFieldRules.ValueShape(true, !map.isEmpty(), longest, map.size()));
        }
        default -> result.put(
            key, new ListingEditorFieldRules.ValueShape(true, true, 0, 0));
      }
    }
    return result;
  }

  /** 整改建议：按维度归并（一个维度一条行动项，带优先级与归属模块）。 */
  private List<CompliancePrecheckRemediation> remediation(
      List<DimensionOutcome> outcomes, List<FindingRow> findings) {
    List<CompliancePrecheckRemediation> result = new ArrayList<>();
    for (DimensionOutcome outcome : outcomes) {
      List<FindingRow> scoped =
          findings.stream()
              .filter(finding -> finding.dimensionCode().equals(outcome.dimension().code()))
              .toList();
      if (scoped.isEmpty()) {
        continue;
      }
      boolean blocking = scoped.stream().anyMatch(f -> "BLOCKING".equals(f.severity()));
      boolean warning = scoped.stream().anyMatch(f -> "WARNING".equals(f.severity()));
      String owner =
          scoped.stream()
              .map(FindingRow::ownerModule)
              .filter(value -> value != null && !value.isBlank())
              .findFirst()
              .orElse(OWNER_SELF);
      String action;
      if (blocking) {
        action =
            "先处理"
                + outcome.dimension().label()
                + "里的阻断项（"
                + scoped.stream().filter(f -> "BLOCKING".equals(f.severity())).count()
                + " 条），再重新体检";
      } else if (warning) {
        action = "复核" + outcome.dimension().label() + "里的警告项，确认是否需要调整";
      } else {
        action =
            outcome.dimension().label()
                + "当前**量不到**（归 "
                + owner
                + "）：接入该模块的细则后重跑一次体检，才能得到这一维度的得分";
      }
      result.add(
          new CompliancePrecheckRemediation(
              outcome.dimension().code(),
              outcome.dimension().label(),
              owner,
              action,
              blocking ? "HIGH" : (warning ? "MEDIUM" : "LOW")));
    }
    return result;
  }

  // ==================================================================
  // 小工具
  // ==================================================================

  /** 某个维度的检查结果（已按维度归档）。 */
  private static int order(String dimensionCode) {
    int index = DIMENSION_ORDER.indexOf(dimensionCode);
    return index < 0 ? DIMENSION_ORDER.size() : index;
  }

  private static String triggerLabel(String triggerKind) {
    return switch (triggerKind) {
      case "DRAFT_SAVE" -> "保存草稿时的即时反馈";
      case "PUBLISH_GATE" -> "发布前的强制校验";
      default -> triggerKind;
    };
  }

  private static int intOf(Record row, String column) {
    Number value = row.get(column, Number.class);
    return value == null ? 0 : value.intValue();
  }

  private static List<String> stringList(Object raw) {
    List<String> result = new ArrayList<>();
    if (raw instanceof List<?> list) {
      for (Object item : list) {
        result.add(item == null ? "" : String.valueOf(item));
      }
    }
    return result;
  }

  private static List<Object> objectList(Object raw) {
    List<Object> result = new ArrayList<>();
    if (raw instanceof List<?> list) {
      result.addAll(list);
    }
    return result;
  }

  private static Map<String, Object> stringMap(Object raw) {
    Map<String, Object> result = new LinkedHashMap<>();
    if (raw instanceof Map<?, ?> map) {
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        result.put(String.valueOf(entry.getKey()), entry.getValue());
      }
    }
    return result;
  }

  /** jsonb 节点 → 纯 Java 值（可再序列化）。 */
  private static Object toPlain(com.fasterxml.jackson.databind.JsonNode node) {
    if (node == null || node.isNull()) {
      return null;
    }
    if (node.isTextual()) {
      return node.textValue();
    }
    if (node.isBoolean()) {
      return node.booleanValue();
    }
    if (node.isIntegralNumber()) {
      return node.longValue();
    }
    if (node.isNumber()) {
      return node.decimalValue();
    }
    if (node.isArray()) {
      List<Object> result = new ArrayList<>();
      node.forEach(item -> result.add(toPlain(item)));
      return result;
    }
    if (node.isObject()) {
      Map<String, Object> result = new LinkedHashMap<>();
      node.fields().forEachRemaining(entry -> result.put(entry.getKey(), toPlain(entry.getValue())));
      return result;
    }
    return node.toString();
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> evidenceMap(Object raw) {
    if (raw instanceof org.jooq.JSONB jsonb) {
      try {
        return json.readValue(jsonb.data(), Map.class);
      } catch (Exception e) {
        return Map.of();
      }
    }
    return Map.of();
  }

  @SuppressWarnings("unchecked")
  private List<CompliancePrecheckRemediation> readRemediation(Object raw) {
    if (raw instanceof org.jooq.JSONB jsonb) {
      try {
        return json.readValue(
            jsonb.data(),
            new com.fasterxml.jackson.core.type.TypeReference<
                List<CompliancePrecheckRemediation>>() {});
      } catch (Exception e) {
        return List.of();
      }
    }
    return List.of();
  }

  private static List<Map<String, Object>> requirementSnapshot(
      List<ListingEditorFieldRules.Requirement> requirements) {
    List<Map<String, Object>> result = new ArrayList<>();
    for (ListingEditorFieldRules.Requirement requirement : requirements) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("field", requirement.field());
      row.put("label", LABELS.getOrDefault(requirement.field(), requirement.field()));
      row.put("requirement", requirement.requirement());
      row.put("maxLength", requirement.maxLength());
      row.put("maxItems", requirement.maxItems());
      row.put("requiresCapability", requirement.requiresCapability());
      row.put("constraintOwnerModule", requirement.constraintOwnerModule());
      result.add(row);
    }
    return result;
  }
}
