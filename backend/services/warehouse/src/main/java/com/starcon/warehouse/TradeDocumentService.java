package com.starcon.warehouse;

import static com.starcon.warehouse.TradeDocumentModels.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 10.5.2.1 贸易单证。
 *
 * <p>蓝图：「管理与生成贸易相关单证：商业发票、装箱单、原产地证（RCEP/CO/FTA 优惠）、提单/空运单、
 * 进口许可与认证文件，按目的地国家规则校验完整性」。输入是「发货明细；目的地国家单证要求； 16 合规的认证状态」，输出是「单证文件 + 完整性校验结果 + 缺失提醒」。
 *
 * <p>七条实现纪律：
 *
 * <ol>
 *   <li><b>「实质性改变」是业务判断，系统只校验与留档</b>（本模块最重要的一条）。蓝图关系段原文： 「FTA
 *       优惠原产地证能显著降低关税，其适用条件（尤其是"实质性改变"）需要业务确认， 系统只负责按规则校验与留档」。所以本类<b>没有任何</b>实质性改变判定逻辑 —— {@link
 *       #confirmPreference} 只做三件事：要求确认人+确认依据、要求引用一条真实存在的 优惠规则、把确认人与时间落库。系统能回答「有没有人确认过、依据是什么、什么时候」，
 *       <b>不能</b>回答「法律上成不成立」。DB 层 guard_trade_3_preference 同步钉死 「主张优惠但没业务确认 → 不许进入已复核通过/已提交」。
 *   <li><b>单证要求规则是数据不是常量</b>：目的地国家 × 单证类型 × 必备程度落 {@code trade_document_rule}，租户可维护。写死成 Java 常量或
 *       CHECK 词表等于把海关 要求抄成第二份且必然漂移。
 *   <li><b>有条件必备的条件是人口径，系统不执行</b>：{@code condition_expression} 是人读描述，
 *       缺失时只报一条**提示级**提醒请人判断，绝不自动判成「必备」或「不适用」—— 做成可执行表达式等于让运营在没有评审的情况下定义报关规则。
 *   <li><b>校验结论 append-only，改结论只能重跑</b>：{@code trade_document_check} 一次一行、
 *       不改不删。重跑时把上一轮已不再报的提醒**显式自动结清**（带说明与处理人）， 否则「历史阻断提醒」会永久挡住一份已经补齐的单证包。
 *   <li><b>提交闸是三道且由 DB 钉死</b>：人工复核通过（V91 {@code trade_1_state}）、 完整性校验必须 PASS 且无阻断提醒（{@code
 *       trade_2_submit_gate}）、 优惠必须已获业务确认（{@code trade_3_preference}）。绕服务层直接改状态同样被挡。
 *   <li><b>16 合规上游未交付</b>：本仓库无 product_compliance 服务，所以认证状态来自 <b>租户可维护</b>的 {@code
 *       trade_certification_status}，每行带 {@code certificationSource} 如实标注「人登记的」还是「16 合规推来的」；
 *       <b>不建</b> 16 合规的镜像表（建了就是假的上游且会成为双 writer）。
 *   <li><b>多租户不可省</b>：所有 SQL 走 RLS，租户由 backend/libraries/tenancy 在事务内设置， 客户端伪造 {@code X-Tenant-Id}
 *       进不来也改不动它。
 * </ol>
 *
 * <p>状态机只有一处真相：{@link DocumentSetStatus#allowedNext}。
 */
@Service
public class TradeDocumentService {

  /** 单页最大行数。 */
  static final int MAX_PAGE_SIZE = 200;

  private static final List<String> SET_SORTS =
      List.of("UPDATED_AT", "CREATED_AT", "SET_CODE", "SUBMITTED_AT", "DECLARED_VALUE");

  private static final List<String> RULE_SORTS =
      List.of("COUNTRY_CODE", "DOCUMENT_KIND", "UPDATED_AT");
  private static final List<String> TEMPLATE_SORTS = List.of("TEMPLATE_CODE", "UPDATED_AT");
  private static final List<String> CERT_SORTS = List.of("SKU", "UPDATED_AT", "EXPIRES_ON");
  private static final List<String> PREF_SORTS =
      List.of("SCHEME", "DESTINATION_COUNTRY", "UPDATED_AT");
  private static final List<String> REMINDER_SORTS = List.of("CREATED_AT", "SEVERITY");

  private static final String SOURCE_TENANT = "TENANT_MAINTAINED";

  private final DSLContext sql;
  private final ObjectMapper json;

  public TradeDocumentService(DSLContext sql, ObjectMapper json) {
    this.sql = sql;
    this.json = json;
  }

  // ================================================================ 能力

  /**
   * 贸易单证能力清单：状态机、单证类型、要求级别、确认状态、校验结论、 **未交付依赖逐条声明**与四条必须讲清的口径。
   *
   * <p>本车道对集成头实查的结论：16 合规（认证状态）未交付 —— {@code backend/services/} 下 没有 product_compliance
   * 服务；外部报关行/目的仓系统对接未交付。两者都 {@code available=false} + 原因 + {@code ownerModule} + {@code href}，
   * <b>不建占位表、不返回空集冒充成功</b>。
   */
  public TradeDocumentCapabilities capabilities() {
    List<TradeDimension> dependencies =
        List.of(
            new TradeDimension(
                "COMPLIANCE_CERTIFICATION",
                "16 合规的认证状态",
                false,
                "16 合规",
                "/compliance/certifications",
                "16 合规未交付：本仓库 backend/services/ 下没有 product_compliance 服务。"
                    + "因此认证状态来自**租户可维护**的登记表，每行带 certificationSource"
                    + "（TENANT_MAINTAINED / MODULE_16）如实标注来源；"
                    + "16 合规交付后由它回填并把来源改成 MODULE_16，不需要改结构。"),
            new TradeDimension(
                "BROKER_AND_DESTINATION_WAREHOUSE",
                "报关行 / 目的仓系统对接",
                false,
                "10.5.2.1",
                "/logistics/trade-documents",
                "外部报关行与目的仓系统对接未交付：本项目里没有可调用的外部系统与凭证，"
                    + "所以提交与接收回执由人工回填，并以 source_system='MANUAL_ENTRY' "
                    + "如实标注「这条是人抄进来的，不是机器对接回来的」。"),
            new TradeDimension(
                "CUSTOMS_DECLARATION",
                "10.5.1.1 报关单",
                true,
                "10.5.1.1",
                "/logistics/customs",
                "上游 10.5.1.1 报关单已交付（V85），本模块的单证包以它为来源并建复合外键，" + "备证资料的收发货人/币种/金额快照全部固化在单证包上。"),
            new TradeDimension(
                "DUTY_AND_TAX",
                "10.5.1.3 关税与税费",
                true,
                "10.5.1.3",
                "/logistics/tariffs",
                "下游 10.5.1.3 关税已交付（V90）。本模块**不计算关税**、不做优惠额试算："
                    + "「实质性改变」成不成立由业务确认，本模块只负责按规则校验与留档；"
                    + "优惠能省多少由 10.5.1.3 按已确认的单证口径计算。"));

    return new TradeDocumentCapabilities(
        dependencies,
        transitions(),
        java.util.Arrays.stream(DocumentSetStatus.values())
            .map(s -> new TradeStatusOption(s.name(), s.label()))
            .toList(),
        java.util.Arrays.stream(TradeDocumentKind.values())
            .map(k -> new TradeDocumentKindOption(k.name(), k.label(), k.isOriginCertificate()))
            .toList(),
        java.util.Arrays.stream(RequirementLevel.values())
            .map(r -> new TradeRequirementOption(r.name(), r.label()))
            .toList(),
        java.util.Arrays.stream(SubstantiveChangeStatus.values())
            .map(
                s ->
                    new TradeSubstantiveChangeOption(
                        s.name(),
                        s.label(),
                        s == SubstantiveChangeStatus.PENDING_BUSINESS_CONFIRMATION))
            .toList(),
        java.util.Arrays.stream(CheckResult.values())
            .map(r -> new TradeCheckResultOption(r.name(), r.label()))
            .toList(),
        java.util.Arrays.stream(CertificationStatus.values())
            .map(c -> new TradeCertificationOption(c.name(), c.label()))
            .toList(),
        java.util.Arrays.stream(ReminderType.values())
            .map(r -> new TradeReminderTypeOption(r.name(), r.label()))
            .toList(),
        "FTA 优惠原产地证的「实质性改变」是**业务判断**，系统只校验与留档、不代替业务下结论。"
            + "本模块能回答「有没有人确认过、谁确认的、什么时候、依据是什么、按哪一版规则」；"
            + "**不能**回答「实质性改变在法律上成不成立」。主张优惠而没有业务确认痕迹的单证"
            + "会被 DB 闸门挡住，并被显式报成阻断级缺失提醒。",
        "提交下游前必须过三道闸，且都由 DB 触发器钉死（绕服务层直接改状态同样被挡）："
            + "① 人工复核通过；② 完整性校验必须 PASS 且无未处理的阻断级提醒；"
            + "③ 优惠主张已获业务确认。「待业务确认」**不算通过** —— 混进 PASS 会让界面显示"
            + "「校验通过」而实际上优惠还没人拍板。",
        "目的地国家单证要求、单证模板、FTA 优惠规则**全部真实落库**（trade_document_rule / "
            + "trade_document_template / trade_origin_preference_rule），租户可维护。"
            + "它们不是代码里的常量：写死等于把海关要求抄成第二份且必然漂移。"
            + "另：该目的国一条规则都没配时，校验会**报不通过**而不是「没有要求就算通过」——"
            + "没有定义要求就无法断言齐不齐。",
        "16 合规的认证状态上游**未交付**（本仓库无 product_compliance 服务），"
            + "所以认证状态来自租户可维护的登记表，并带 certificationSource 如实标注来源。"
            + "认证过期/被撤销/未登记都会被校验报成缺失提醒，不静默当成齐备。");
  }

  /** 状态机的合法转换，前端据此只给合法的按钮。 */
  public List<TradeTransition> transitions() {
    List<TradeTransition> out = new ArrayList<>();
    for (var from : DocumentSetStatus.values()) {
      for (var to : DocumentSetStatus.allowedNext(from)) {
        out.add(
            new TradeTransition(
                from.name(),
                from.label(),
                to.name(),
                to.label(),
                actionOf(from, to),
                noteOf(from, to)));
      }
    }
    return out;
  }

  private static String actionOf(DocumentSetStatus from, DocumentSetStatus to) {
    if (from == to) {
      return "UPDATE";
    }
    return switch (to) {
      case UNDER_REVIEW -> "SUBMIT_FOR_REVIEW";
      case APPROVED -> "REVIEW";
      case REJECTED -> "REJECT_REVIEW";
      case SUBMITTED -> "SUBMIT";
      case ACKNOWLEDGED -> "ACKNOWLEDGE";
      case SENT_BACK -> "SEND_BACK";
      case DRAFT -> from == DocumentSetStatus.UNDER_REVIEW ? "WITHDRAW_REVIEW" : "RETURN_TO_DRAFT";
      case CANCELLED -> "CANCEL";
      default -> "UPDATE";
    };
  }

  private static String noteOf(DocumentSetStatus from, DocumentSetStatus to) {
    if (to == DocumentSetStatus.SUBMITTED) {
      return "提交报关行/目的仓：必须先人工复核通过、完整性校验 PASS、无阻断级缺失提醒、" + "且 FTA 优惠主张已获业务确认。四道闸由 DB 触发器钉死。";
    }
    if (to == DocumentSetStatus.APPROVED) {
      return "复核通过：确认单证齐备、金额币种自洽、优惠确认痕迹完整。";
    }
    if (to == DocumentSetStatus.DRAFT && from == DocumentSetStatus.UNDER_REVIEW) {
      return "撤回复核：撤回复核后单证才允许再改，改完必须重新走复核才能提交。";
    }
    if (to == DocumentSetStatus.DRAFT && from == DocumentSetStatus.REJECTED) {
      return "退回草稿：改完必须重新走一次复核才能再次提交 —— 改完没人看过就不该再出去一次。";
    }
    if (to == DocumentSetStatus.SENT_BACK) {
      return "被退回必须给原因：改单证要按退回原因改，没有原因就无从改起。";
    }
    if (to == DocumentSetStatus.CANCELLED) {
      return "取消必须给原因：备证资料已生成过，取消后无法追责。";
    }
    if (from == DocumentSetStatus.ACKNOWLEDGED || from == DocumentSetStatus.CANCELLED) {
      return "终态不允许离开：下游已接收/单证包已作废。";
    }
    return "";
  }

  // ================================================================ 目的地国家单证要求规则

  /** 规则列表 + 全量汇总（**合计不受分页影响**）。规则是数据，租户自己维护。 */
  public TradeRulePage rules(RuleQuery query) {
    int page = Math.max(0, query.page());
    int size = Math.min(MAX_PAGE_SIZE, Math.max(1, query.size()));
    String sort = sortOf(RULE_SORTS, query.sort(), "COUNTRY_CODE");

    List<Object> args = new ArrayList<>();
    String where = ruleWhere(query, args);

    long total =
        sql.fetchOne("select count(*) from trade_document_rule where " + where, args.toArray())
            .get(0, Long.class);
    List<Object> pageArgs = new ArrayList<>(args);
    pageArgs.add(size);
    pageArgs.add((long) page * size);
    var rows =
        sql.fetch(
            "select * from trade_document_rule where "
                + where
                + " order by "
                + orderBy(sort, "country_code", "document_kind", "updated_at", "id")
                + " limit ? offset ?",
            pageArgs.toArray());

    TradeRuleSummary summary =
        new TradeRuleSummary(
            total,
            count(where, args, "status='ACTIVE'"),
            count(where, args, "status='RETIRED'"),
            count(where, args, "requirement_level='MANDATORY'"),
            count(where, args, "requires_certification"));

    return new TradeRulePage(
        summary,
        rows.stream().map(this::ruleRow).toList(),
        statusOptions(),
        requirementOptions(),
        documentKindOptions(),
        total,
        page,
        size);
  }

  private long count(String where, List<Object> args, String extra) {
    List<Object> a = new ArrayList<>(args);
    return sql.fetchOne(
            "select count(*) from trade_document_rule where " + where + " and " + extra,
            a.toArray())
        .get(0, Long.class);
  }

  private String ruleWhere(RuleQuery q, List<Object> args) {
    StringBuilder w = new StringBuilder("true");
    if (notBlank(q.countryCode())) {
      w.append(" and country_code=?");
      args.add(q.countryCode().trim().toUpperCase(java.util.Locale.ROOT));
    }
    if (notBlank(q.documentKind())) {
      w.append(" and document_kind=?");
      args.add(TradeDocumentKind.parse(q.documentKind()).name());
    }
    if (notBlank(q.requirementLevel())) {
      w.append(" and requirement_level=?");
      args.add(RequirementLevel.parse(q.requirementLevel()).name());
    }
    if (notBlank(q.status())) {
      w.append(" and status=?");
      args.add(q.status().trim().toUpperCase(java.util.Locale.ROOT));
    }
    if (notBlank(q.keyword())) {
      w.append(" and (condition_expression ilike ? or notes ilike ? or document_kind ilike ?)");
      String k = "%" + q.keyword().trim() + "%";
      args.add(k);
      args.add(k);
      args.add(k);
    }
    return w.toString();
  }

  /** 落一条规则。真实写库，不是内存常量。 */
  @Transactional
  public TradeRuleSaved createRule(UUID tenantId, TradeRuleCreateRequest request) {
    String country = requireCountry(request.countryCode(), "目的国/地区");
    TradeDocumentKind kind = TradeDocumentKind.parse(request.documentKind());
    RequirementLevel level = RequirementLevel.parse(request.requirementLevel());
    boolean requiresCert = Boolean.TRUE.equals(request.requiresCertification());
    String condition = fit(request.conditionExpression(), 2000, "适用条件说明");
    // 有条件必备必须写清条件：留空等于让校验无据可依，只能默默当成「不适用」。
    if (level == RequirementLevel.CONDITIONAL && condition.isEmpty()) {
      throw new IllegalArgumentException("「有条件必备」必须写清适用条件说明：条件是给人读的口径，系统不会执行它，留空就无从判断");
    }
    if (requiresCert
        && !kind.isOriginCertificate()
        && kind != TradeDocumentKind.CERTIFICATION_DOC
        && kind != TradeDocumentKind.IMPORT_LICENSE) {
      // 不是硬性报错：认证要求可以挂在任何单证上（目的国可能要求发票随附某项认证）。
      // 这里只是把口径讲清楚，不拦。
      assert true;
    }
    String source = sourceOrDefault(request.ruleSource());
    UUID id = UUID.randomUUID();
    sql.execute(
        "insert into trade_document_rule(id,tenant_id,country_code,document_kind,requirement_level,"
            + "condition_expression,requires_certification,rule_source,source_reference,valid_from,"
            + "valid_to,notes,created_by) values(?,?,?,?,?,?,?,?,?,?,?,?,?)",
        id,
        tenantId,
        country,
        kind.name(),
        level.name(),
        condition,
        requiresCert,
        source,
        fit(request.sourceReference(), 64, "规则出处"),
        request.validFrom() == null ? LocalDate.now() : request.validFrom(),
        request.validTo(),
        fit(request.notes(), 2000, "备注"),
        currentActor());
    return new TradeRuleSaved(
        id, country, kind.name(), "ACTIVE", 0, "单证要求规则已创建。规则是数据不是常量，改了立刻影响校验。");
  }

  @Transactional
  public TradeRuleSaved updateRule(UUID id, TradeRuleUpdateRequest request) {
    Record head = ruleHead(id);
    if (request.expectedVersion() != null
        && request.expectedVersion() != head.get("version", Long.class)) {
      throw new IllegalStateException(
          "规则已被他人修改（期望版本 "
              + request.expectedVersion()
              + "，当前版本 "
              + head.get("version", Long.class)
              + "），请刷新后重试");
    }
    RequirementLevel level = RequirementLevel.parse(request.requirementLevel());
    String condition = fit(request.conditionExpression(), 2000, "适用条件说明");
    if (level == RequirementLevel.CONDITIONAL && condition.isEmpty()) {
      throw new IllegalArgumentException("「有条件必备」必须写清适用条件说明");
    }
    LocalDate validFrom =
        request.validFrom() == null ? head.get("valid_from", LocalDate.class) : request.validFrom();
    LocalDate validTo = request.validTo();
    if (validTo != null && validTo.isBefore(validFrom)) {
      throw new IllegalArgumentException("失效日 " + validTo + " 早于生效日 " + validFrom + "：有效期必须自洽");
    }
    sql.execute(
        "update trade_document_rule set requirement_level=?,condition_expression=?,"
            + "requires_certification=?,rule_source=?,source_reference=?,valid_from=?,valid_to=?,"
            + "status=?,notes=?,version=version+1,updated_at=now() where id=?",
        level.name(),
        condition,
        Boolean.TRUE.equals(request.requiresCertification()),
        request.ruleSource() == null || request.ruleSource().isBlank()
            ? head.get("rule_source", String.class)
            : sourceOrDefault(request.ruleSource()),
        fit(request.sourceReference(), 64, "规则出处"),
        validFrom,
        validTo,
        upperOrDefault(request.status(), head.get("status", String.class)),
        fit(request.notes(), 2000, "备注"),
        id);
    return new TradeRuleSaved(
        id,
        head.get("country_code", String.class),
        head.get("document_kind", String.class),
        upperOrDefault(request.status(), head.get("status", String.class)),
        head.get("version", Long.class) + 1,
        "规则已更新。历史校验结论里记着当时命中的规则条数，所以改规则不会篡改已发生的留档。");
  }

  private Record ruleHead(UUID id) {
    Record r = sql.fetchOne("select * from trade_document_rule where id=?", id);
    if (r == null) {
      throw new ResponseStatusException(
          org.springframework.http.HttpStatus.NOT_FOUND, "单证要求规则不存在或无权访问");
    }
    return r;
  }

  // ================================================================ 单证模板

  public TradeTemplatePage templates(TemplateQuery query) {
    int page = Math.max(0, query.page());
    int size = Math.min(MAX_PAGE_SIZE, Math.max(1, query.size()));
    String sort = sortOf(TEMPLATE_SORTS, query.sort(), "TEMPLATE_CODE");

    List<Object> args = new ArrayList<>();
    StringBuilder w = new StringBuilder("true");
    if (notBlank(query.documentKind())) {
      w.append(" and document_kind=?");
      args.add(TradeDocumentKind.parse(query.documentKind()).name());
    }
    if (notBlank(query.status())) {
      w.append(" and status=?");
      args.add(query.status().trim().toUpperCase(java.util.Locale.ROOT));
    }
    if (notBlank(query.countryCode())) {
      w.append(" and country_code=?");
      args.add(query.countryCode().trim().toUpperCase(java.util.Locale.ROOT));
    }
    if (notBlank(query.keyword())) {
      w.append(" and (template_code ilike ? or notes ilike ?)");
      args.add("%" + query.keyword().trim() + "%");
      args.add("%" + query.keyword().trim() + "%");
    }
    String where = w.toString();

    long total =
        sql.fetchOne("select count(*) from trade_document_template where " + where, args.toArray())
            .get(0, Long.class);
    List<Object> pageArgs = new ArrayList<>(args);
    pageArgs.add(size);
    pageArgs.add((long) page * size);
    var rows =
        sql.fetch(
            "select * from trade_document_template where "
                + where
                + " order by "
                + orderBy(sort, "template_code", "updated_at", "id")
                + " limit ? offset ?",
            pageArgs.toArray());

    return new TradeTemplatePage(
        rows.stream().map(this::templateRow).toList(),
        statusOptions(),
        documentKindOptions(),
        total,
        page,
        size);
  }

  @Transactional
  public TradeTemplateSaved createTemplate(UUID tenantId, TradeTemplateCreateRequest request) {
    String code = requireText(request.templateCode(), "模板编码");
    TradeDocumentKind kind = TradeDocumentKind.parse(request.documentKind());
    String country = optionalCountry(request.countryCode());
    String currency = moneyOrDefault(request.currency(), "USD", "模板币种");
    Map<String, Object> body = request.templateBody() == null ? Map.of() : request.templateBody();
    // 模板体必须是对象：数组/标量渲染不出单证版式，而且空模板等于「生成出来是空白单据」。
    if (body.isEmpty()) {
      throw new IllegalArgumentException("模板体不能为空：空模板生成出来的是一张空白单证，不是备证资料");
    }
    String status = upperOrDefault(request.status(), "DRAFT");
    if (!Set.of("DRAFT", "ACTIVE", "RETIRED").contains(status)) {
      throw new IllegalArgumentException("未知的模板状态：" + status + "，可选：DRAFT / ACTIVE / RETIRED");
    }
    LocalDate from = request.effectiveFrom() == null ? LocalDate.now() : request.effectiveFrom();
    LocalDate to = request.effectiveTo();
    if (to != null && to.isBefore(from)) {
      throw new IllegalArgumentException("失效日 " + to + " 早于生效日 " + from + "：有效期必须自洽");
    }
    int nextVersion =
        sql.fetchOne(
                "select coalesce(max(template_version),0)+1 from trade_document_template"
                    + " where tenant_id=? and template_code=?",
                tenantId,
                code)
            .get(0, Integer.class);
    // fetchOne 查不到行时返回 null（不是「一行全 NULL」），直接 .get(0,...) 会 NPE。
    Record prevRow =
        sql.fetchOne(
            "select id from trade_document_template where tenant_id=? and template_code=?"
                + " order by template_version desc limit 1",
            tenantId,
            code);
    UUID prev = prevRow == null ? null : prevRow.get(0, UUID.class);
    if (nextVersion > 1 && prev == null) {
      throw new IllegalStateException("模板改版链断裂：找不到前一版，无法确定新版本号");
    }

    UUID id = UUID.randomUUID();
    sql.execute(
        "insert into trade_document_template(id,tenant_id,template_code,document_kind,country_code,"
            + "template_version,currency,template_body,status,supersedes_template_id,effective_from,"
            + "effective_to,notes,created_by) values(?,?,?,?,?,?,?,?::jsonb,?,?,?,?,?,?)",
        id,
        tenantId,
        code,
        kind.name(),
        country,
        nextVersion,
        currency,
        toJson(body, "模板体"),
        status,
        prev,
        from,
        to,
        fit(request.notes(), 2000, "备注"),
        currentActor());
    return new TradeTemplateSaved(
        id, code, nextVersion, status, 0, "单证模板已创建（第 " + nextVersion + " 版）。模板是数据不是代码里的版式常量。");
  }

  @Transactional
  public TradeTemplateSaved updateTemplate(UUID id, TradeTemplateUpdateRequest request) {
    Record head = templateHead(id);
    if (request.expectedVersion() != null
        && request.expectedVersion() != head.get("version", Long.class)) {
      throw new IllegalStateException("模板已被他人修改，请刷新后重试");
    }
    Map<String, Object> body = request.templateBody();
    if (body != null && body.isEmpty()) {
      throw new IllegalArgumentException("模板体不能为空：空模板生成出来的是一张空白单证");
    }
    String status = upperOrDefault(request.status(), head.get("status", String.class));
    if (!Set.of("DRAFT", "ACTIVE", "RETIRED").contains(status)) {
      throw new IllegalArgumentException("未知的模板状态：" + status);
    }
    LocalDate from =
        request.effectiveFrom() == null
            ? head.get("effective_from", LocalDate.class)
            : request.effectiveFrom();
    LocalDate to = request.effectiveTo();
    if (to != null && to.isBefore(from)) {
      throw new IllegalArgumentException("失效日 " + to + " 早于生效日 " + from + "：有效期必须自洽");
    }
    sql.execute(
        "update trade_document_template set template_body=coalesce(?::jsonb,template_body),status=?,"
            + "effective_from=?,effective_to=?,notes=?,version=version+1,updated_at=now() where id=?",
        body == null ? null : toJson(body, "模板体"),
        status,
        from,
        to,
        fit(request.notes(), 2000, "备注"),
        id);
    return new TradeTemplateSaved(
        id,
        head.get("template_code", String.class),
        head.get("template_version", Integer.class),
        status,
        head.get("version", Long.class) + 1,
        "模板已更新。注意：已生成的单证是**内容快照**，改模板不会追溯改写历史单证。");
  }

  private Record templateHead(UUID id) {
    Record r = sql.fetchOne("select * from trade_document_template where id=?", id);
    if (r == null) {
      throw new ResponseStatusException(
          org.springframework.http.HttpStatus.NOT_FOUND, "单证模板不存在或无权访问");
    }
    return r;
  }

  // ================================================================ 认证状态（16 合规替代来源）

  public TradeCertificationPage certifications(CertificationQuery query) {
    int page = Math.max(0, query.page());
    int size = Math.min(MAX_PAGE_SIZE, Math.max(1, query.size()));
    String sort = sortOf(CERT_SORTS, query.sort(), "SKU");

    List<Object> args = new ArrayList<>();
    StringBuilder w = new StringBuilder("true");
    if (query.skuId() != null) {
      w.append(" and sku_id=?");
      args.add(query.skuId());
    }
    if (notBlank(query.status())) {
      w.append(" and status=?");
      args.add(CertificationStatus.parse(query.status()).name());
    }
    if (notBlank(query.certificationSource())) {
      w.append(" and certification_source=?");
      args.add(query.certificationSource().trim().toUpperCase(java.util.Locale.ROOT));
    }
    if (notBlank(query.keyword())) {
      w.append(
          " and (certification_code ilike ? or certification_name ilike ? or sku_code ilike ?)");
      args.add("%" + query.keyword().trim() + "%");
      args.add("%" + query.keyword().trim() + "%");
      args.add("%" + query.keyword().trim() + "%");
    }
    String where = w.toString();

    long total =
        sql.fetchOne(
                "select count(*) from trade_certification_status where " + where, args.toArray())
            .get(0, Long.class);
    List<Object> pageArgs = new ArrayList<>(args);
    pageArgs.add(size);
    pageArgs.add((long) page * size);
    var rows =
        sql.fetch(
            "select * from trade_certification_status where "
                + where
                + " order by "
                + orderBy(sort, "sku_code", "updated_at", "expires_on", "id")
                + " limit ? offset ?",
            pageArgs.toArray());

    return new TradeCertificationPage(
        rows.stream().map(this::certificationRow).toList(),
        java.util.Arrays.stream(CertificationStatus.values())
            .map(c -> new TradeCertificationOption(c.name(), c.label()))
            .toList(),
        total,
        page,
        size);
  }

  @Transactional
  public TradeCertificationSaved createCertification(
      UUID tenantId, TradeCertificationCreateRequest request) {
    if (request.skuId() == null) {
      throw new IllegalArgumentException("必须给出 skuId：认证状态挂在商品上，不能凭空登记");
    }
    Record sku =
        sql.fetchOne("select * from sku_ref where tenant_id=? and id=?", tenantId, request.skuId());
    if (sku == null) {
      throw new IllegalArgumentException("SKU 不存在或无权访问：" + request.skuId());
    }
    String code = requireText(request.certificationCode(), "认证类型编码");
    CertificationStatus status = CertificationStatus.parse(request.status());
    String certificateNo = fit(request.certificateNo(), 64, "证书编号");
    if (status == CertificationStatus.VALID && certificateNo.isEmpty()) {
      throw new IllegalArgumentException("认证标成有效但没有证书编号：一张没有编号的证书在目的国海关眼里等于没有");
    }
    // 有效必须带有效期 —— 一张没有有效期的认证在目的国海关眼里等于没有。
    if (status == CertificationStatus.VALID
        && (request.issuedOn() == null || request.expiresOn() == null)) {
      throw new IllegalArgumentException("认证标成有效必须给签发日与有效期，否则无法判断它现在还管不管用");
    }
    if (request.issuedOn() != null
        && request.expiresOn() != null
        && request.expiresOn().isBefore(request.issuedOn())) {
      throw new IllegalArgumentException(
          "有效期 " + request.expiresOn() + " 早于签发日 " + request.issuedOn() + "：必须自洽");
    }
    UUID id = UUID.randomUUID();
    sql.execute(
        "insert into trade_certification_status(id,tenant_id,sku_id,sku_code,sku_name,certification_code,"
            + "certification_name,status,certificate_no,issuing_body,issued_on,expires_on,document_ref,"
            + "certification_source,source_reference,notes,created_by)"
            + " values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        id,
        tenantId,
        request.skuId(),
        fit(sku.get("code", String.class), 64, "SKU 编码快照"),
        fit(sku.get("name", String.class), 200, "SKU 名称快照"),
        code,
        fit(request.certificationName(), 200, "认证名称"),
        status.name(),
        certificateNo,
        fit(request.issuingBody(), 200, "发证机构"),
        request.issuedOn(),
        request.expiresOn(),
        fit(request.documentRef(), 200, "认证文件定位"),
        sourceOrDefaultCert(request.certificationSource()),
        fit(request.sourceReference(), 64, "来源定位"),
        fit(request.notes(), 2000, "备注"),
        currentActor());
    return new TradeCertificationSaved(
        id, code, status.name(), 0, "认证状态已登记。来源标记为「租户维护」—— 16 合规未交付，这条是人登记的，校验不会假装它来自系统。");
  }

  @Transactional
  public TradeCertificationSaved updateCertification(
      UUID id, TradeCertificationUpdateRequest request) {
    Record head = certificationHead(id);
    if (request.expectedVersion() != null
        && request.expectedVersion() != head.get("version", Long.class)) {
      throw new IllegalStateException("认证状态已被他人修改，请刷新后重试");
    }
    CertificationStatus status = CertificationStatus.parse(request.status());
    String certificateNo = fit(request.certificateNo(), 64, "证书编号");
    if (status == CertificationStatus.VALID && certificateNo.isEmpty()) {
      throw new IllegalArgumentException("认证标成有效但没有证书编号");
    }
    LocalDate issued = request.issuedOn();
    LocalDate expires = request.expiresOn();
    if (status == CertificationStatus.VALID && (issued == null || expires == null)) {
      throw new IllegalArgumentException("认证标成有效必须给签发日与有效期");
    }
    if (issued != null && expires != null && expires.isBefore(issued)) {
      throw new IllegalArgumentException("有效期早于签发日：必须自洽");
    }
    sql.execute(
        "update trade_certification_status set status=?,certificate_no=?,issuing_body=?,issued_on=?,"
            + "expires_on=?,document_ref=?,certification_source=?,source_reference=?,notes=?,"
            + "version=version+1,updated_at=now() where id=?",
        status.name(),
        certificateNo,
        fit(request.issuingBody(), 200, "发证机构"),
        issued,
        expires,
        fit(request.documentRef(), 200, "认证文件定位"),
        request.certificationSource() == null || request.certificationSource().isBlank()
            ? head.get("certification_source", String.class)
            : sourceOrDefaultCert(request.certificationSource()),
        fit(request.sourceReference(), 64, "来源定位"),
        fit(request.notes(), 2000, "备注"),
        id);
    return new TradeCertificationSaved(
        id,
        head.get("certification_code", String.class),
        status.name(),
        head.get("version", Long.class) + 1,
        "认证状态已更新。下次完整性校验会按新状态重算齐备性。");
  }

  private Record certificationHead(UUID id) {
    Record r = sql.fetchOne("select * from trade_certification_status where id=?", id);
    if (r == null) {
      throw new ResponseStatusException(
          org.springframework.http.HttpStatus.NOT_FOUND, "认证状态不存在或无权访问");
    }
    return r;
  }

  // ================================================================ FTA 优惠规则

  public TradePreferenceRulePage preferenceRules(PreferenceRuleQuery query) {
    int page = Math.max(0, query.page());
    int size = Math.min(MAX_PAGE_SIZE, Math.max(1, query.size()));
    String sort = sortOf(PREF_SORTS, query.sort(), "SCHEME");

    List<Object> args = new ArrayList<>();
    StringBuilder w = new StringBuilder("true");
    if (notBlank(query.scheme())) {
      w.append(" and scheme=?");
      args.add(query.scheme().trim().toUpperCase(java.util.Locale.ROOT));
    }
    if (notBlank(query.destinationCountryCode())) {
      w.append(" and destination_country_code=?");
      args.add(query.destinationCountryCode().trim().toUpperCase(java.util.Locale.ROOT));
    }
    if (notBlank(query.status())) {
      w.append(" and status=?");
      args.add(query.status().trim().toUpperCase(java.util.Locale.ROOT));
    }
    String where = w.toString();

    long total =
        sql.fetchOne(
                "select count(*) from trade_origin_preference_rule where " + where, args.toArray())
            .get(0, Long.class);
    List<Object> pageArgs = new ArrayList<>(args);
    pageArgs.add(size);
    pageArgs.add((long) page * size);
    var rows =
        sql.fetch(
            "select * from trade_origin_preference_rule where "
                + where
                + " order by "
                + orderBy(sort, "scheme", "destination_country_code", "updated_at", "id")
                + " limit ? offset ?",
            pageArgs.toArray());

    return new TradePreferenceRulePage(
        rows.stream().map(this::preferenceRuleRow).toList(), statusOptions(), total, page, size);
  }

  @Transactional
  public TradePreferenceRuleSaved createPreferenceRule(
      UUID tenantId, TradePreferenceRuleCreateRequest request) {
    String scheme = requireText(request.scheme(), "优惠方案").toUpperCase(java.util.Locale.ROOT);
    String country = requireCountry(request.destinationCountryCode(), "适用目的国/地区");
    String scope = upperOrDefault(request.productScope(), "ALL");
    if (!Set.of("ALL", "HS_PREFIX").contains(scope)) {
      throw new IllegalArgumentException("未知的适用范围口径：" + scope + "，可选：ALL / HS_PREFIX");
    }
    String prefix = fit(request.hsPrefix(), 8, "HS 前缀");
    if ("HS_PREFIX".equals(scope) && prefix.isEmpty()) {
      throw new IllegalArgumentException("适用范围选了 HS 前缀就必须给前缀，否则这条规则会命中所有产品");
    }
    if ("HS_PREFIX".equals(scope) && prefix.length() > 4) {
      throw new IllegalArgumentException("HS 前缀最多 4 位（与 10.5.1.3 的税率表前缀口径一致），收到 " + prefix);
    }
    // 这两条是本表最关键的**口径字段**，不是判定式：留空等于让「实质性改变」无从确认。
    String criteria = fit(request.preferenceCriteria(), 2000, "优惠适用条件口径");
    String substantive = fit(request.substantiveChangeCriterion(), 2000, "实质性改变适用条件口径");
    if (substantive.isEmpty()) {
      throw new IllegalArgumentException(
          "「实质性改变」的适用条件口径不能为空：它是业务确认时要依据的东西，" + "系统不执行也不推断，留空就等于让业务在一张空白纸上签字");
    }
    boolean requiresConfirmation =
        request.requiresBusinessConfirmation() == null || request.requiresBusinessConfirmation();
    LocalDate from = request.validFrom() == null ? LocalDate.now() : request.validFrom();
    LocalDate to = request.validTo();
    if (to != null && to.isBefore(from)) {
      throw new IllegalArgumentException("失效日早于生效日：有效期必须自洽");
    }
    UUID id = UUID.randomUUID();
    sql.execute(
        "insert into trade_origin_preference_rule(id,tenant_id,scheme,destination_country_code,product_scope,"
            + "hs_prefix,preference_criteria,substantive_change_criterion,requires_business_confirmation,"
            + "valid_from,valid_to,status,rule_source,source_reference,notes,created_by)"
            + " values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        id,
        tenantId,
        scheme,
        country,
        scope,
        prefix,
        criteria,
        substantive,
        requiresConfirmation,
        from,
        to,
        upperOrDefault(request.status(), "ACTIVE"),
        sourceOrDefault(request.ruleSource()),
        fit(request.sourceReference(), 64, "规则出处"),
        fit(request.notes(), 2000, "备注"),
        currentActor());
    return new TradePreferenceRuleSaved(
        id,
        scheme,
        "ACTIVE",
        0,
        requiresConfirmation
            ? "优惠规则已创建。优惠主张默认必须业务确认——系统只校验留痕，不代替业务判断「实质性改变」是否成立。"
            : "优惠规则已创建，并**显式关闭了业务确认要求**。请注意：这一笔操作已记入事件留痕，" + "「实质性改变」无人确认即主张优惠，风险由业务承担。");
  }

  @Transactional
  public TradePreferenceRuleSaved updatePreferenceRule(
      UUID id, TradePreferenceRuleUpdateRequest request) {
    Record head = sql.fetchOne("select * from trade_origin_preference_rule where id=?", id);
    if (head == null) {
      throw new ResponseStatusException(
          org.springframework.http.HttpStatus.NOT_FOUND, "优惠规则不存在或无权访问");
    }
    if (request.expectedVersion() != null
        && request.expectedVersion() != head.get("version", Long.class)) {
      throw new IllegalStateException("优惠规则已被他人修改，请刷新后重试");
    }
    String substantive = fit(request.substantiveChangeCriterion(), 2000, "实质性改变适用条件口径");
    if (substantive.isEmpty()) {
      throw new IllegalArgumentException("「实质性改变」的适用条件口径不能为空");
    }
    LocalDate from =
        request.validFrom() == null ? head.get("valid_from", LocalDate.class) : request.validFrom();
    LocalDate to = request.validTo();
    if (to != null && to.isBefore(from)) {
      throw new IllegalArgumentException("失效日早于生效日：有效期必须自洽");
    }
    boolean requiresConfirmation =
        request.requiresBusinessConfirmation() == null
            ? Boolean.TRUE.equals(head.get("requires_business_confirmation", Boolean.class))
            : request.requiresBusinessConfirmation();
    sql.execute(
        "update trade_origin_preference_rule set preference_criteria=?,substantive_change_criterion=?,"
            + "requires_business_confirmation=?,valid_from=?,valid_to=?,status=?,rule_source=?,"
            + "source_reference=?,notes=?,version=version+1,updated_at=now() where id=?",
        fit(request.preferenceCriteria(), 2000, "优惠适用条件口径"),
        substantive,
        requiresConfirmation,
        from,
        to,
        upperOrDefault(request.status(), head.get("status", String.class)),
        request.ruleSource() == null || request.ruleSource().isBlank()
            ? head.get("rule_source", String.class)
            : sourceOrDefault(request.ruleSource()),
        fit(request.sourceReference(), 64, "规则出处"),
        fit(request.notes(), 2000, "备注"),
        id);
    return new TradePreferenceRuleSaved(
        id,
        head.get("scheme", String.class),
        upperOrDefault(request.status(), head.get("status", String.class)),
        head.get("version", Long.class) + 1,
        "优惠规则已更新。已确认过的单证不会被追溯改写 —— 单证上记着当时按的规则版本。");
  }

  // ================================================================ 单证包：读

  public TradeSetPage page(TradeSetQuery query) {
    int page = Math.max(0, query.page());
    int size = Math.min(MAX_PAGE_SIZE, Math.max(1, query.size()));
    String sort = sortOf(SET_SORTS, query.sort(), "UPDATED_AT");

    List<Object> args = new ArrayList<>();
    String where = setWhere(query, args);

    long total =
        sql.fetchOne("select count(*) from trade_document_set where " + where, args.toArray())
            .get(0, Long.class);
    List<Object> pageArgs = new ArrayList<>(args);
    pageArgs.add(size);
    pageArgs.add((long) page * size);
    var rows =
        sql.fetch(
            "select * from trade_document_set where "
                + where
                + " order by "
                + orderBy(
                    sort,
                    "updated_at",
                    "created_at",
                    "set_code",
                    "submitted_at",
                    "total_declared_value",
                    "id")
                + " limit ? offset ?",
            pageArgs.toArray());

    return new TradeSetPage(
        setSummary(where, args),
        rows.stream().map(this::setRow).toList(),
        statusOptions(),
        java.util.Arrays.stream(CheckResult.values())
            .map(r -> new TradeCheckResultOption(r.name(), r.label()))
            .toList(),
        total,
        page,
        size);
  }

  private String setWhere(TradeSetQuery q, List<Object> args) {
    StringBuilder w = new StringBuilder("true");
    if (q.status() != null && !q.status().isEmpty()) {
      List<DocumentSetStatus> parsed = q.status().stream().map(DocumentSetStatus::parse).toList();
      w.append(" and status in (");
      for (int i = 0; i < parsed.size(); i++) {
        w.append(i == 0 ? "?" : ",?");
        args.add(parsed.get(i).name());
      }
      w.append(")");
    }
    if (notBlank(q.destinationCountryCode())) {
      w.append(" and destination_country_code=?");
      args.add(q.destinationCountryCode().trim().toUpperCase(java.util.Locale.ROOT));
    }
    if (q.sourceDeclarationId() != null) {
      w.append(" and source_declaration_id=?");
      args.add(q.sourceDeclarationId());
    }
    if (notBlank(q.latestCheckResult())) {
      w.append(" and latest_check_result=?");
      args.add(CheckResult.parse(q.latestCheckResult()).name());
    }
    // 「有阻断」不是状态：它由汇总列算出来（缺件 + 待业务确认 + 未处理阻断提醒）。
    if (Boolean.TRUE.equals(q.blockedOnly())) {
      w.append(" and (missing_document_count > 0 or pending_business_count > 0)");
    }
    if (notBlank(q.keyword())) {
      w.append(
          " and (set_code ilike ? or source_declaration_code ilike ? or consignee_name ilike ?"
              + " or exporter_name ilike ?)");
      String k = "%" + q.keyword().trim() + "%";
      args.add(k);
      args.add(k);
      args.add(k);
      args.add(k);
    }
    return w.toString();
  }

  private TradeSetSummary setSummary(String where, List<Object> args) {
    Record r =
        sql.fetchOne(
            "select count(*) total,"
                + " count(*) filter (where status='DRAFT') draft,"
                + " count(*) filter (where status='UNDER_REVIEW') under_review,"
                + " count(*) filter (where status='APPROVED') approved,"
                + " count(*) filter (where status='SUBMITTED') submitted,"
                + " count(*) filter (where missing_document_count>0 or pending_business_count>0) blocked,"
                + " count(*) filter (where pending_business_count>0) pending_business,"
                + " coalesce(sum(total_declared_value),0) total_value,"
                + " coalesce(max(document_currency),'') currency"
                + " from trade_document_set where "
                + where,
            args.toArray());
    return new TradeSetSummary(
        r.get("total", Long.class),
        r.get("draft", Long.class),
        r.get("under_review", Long.class),
        r.get("approved", Long.class),
        r.get("submitted", Long.class),
        r.get("blocked", Long.class),
        r.get("pending_business", Long.class),
        r.get("total_value", BigDecimal.class),
        r.get("currency", String.class));
  }

  public TradeSetDetail detail(UUID id) {
    Record head = setHead(id);
    String status = head.get("status", String.class);
    return new TradeSetDetail(
        setRow(head),
        documents(id),
        checks(id),
        reminders(id),
        events(id),
        DocumentSetStatus.allowedNext(DocumentSetStatus.parse(status)).stream()
            .map(
                to ->
                    new TradeTransition(
                        status,
                        DocumentSetStatus.parse(status).label(),
                        to.name(),
                        to.label(),
                        actionOf(DocumentSetStatus.parse(status), to),
                        noteOf(DocumentSetStatus.parse(status), to)))
            .toList(),
        documentKindOptions());
  }

  public List<TradeDocumentRow> documents(UUID setId) {
    return sql
        .fetch(
            "select d.*, t.template_code from trade_document d"
                + " left join trade_document_template t on t.tenant_id=d.tenant_id and t.id=d.template_id"
                + " where d.set_id=? order by d.document_kind, d.document_version",
            setId)
        .stream()
        .map(this::documentRow)
        .toList();
  }

  public List<TradeCheckRow> checks(UUID setId) {
    return sql
        .fetch("select * from trade_document_check where set_id=? order by check_round desc", setId)
        .stream()
        .map(this::checkRow)
        .toList();
  }

  public List<TradeReminderRow> reminders(UUID setId) {
    return sql
        .fetch(
            "select * from trade_document_reminder where set_id=?"
                + " order by (severity='BLOCKER') desc, created_at desc",
            setId)
        .stream()
        .map(this::reminderRow)
        .toList();
  }

  public List<TradeEventRow> events(UUID setId) {
    return sql
        .fetch(
            "select * from trade_document_event where set_id=? order by occurred_at desc, id desc",
            setId)
        .stream()
        .map(this::eventRow)
        .toList();
  }

  /** 优惠主张清单（跨单证包）：谁主张了优惠、确认到哪一步了。 */
  public TradePreferenceConfirmationPage preferenceConfirmations(
      PreferenceConfirmationQuery query) {
    int page = Math.max(0, query.page());
    int size = Math.min(MAX_PAGE_SIZE, Math.max(1, query.size()));

    List<Object> args = new ArrayList<>();
    StringBuilder w = new StringBuilder("true");
    if (notBlank(query.substantiveChangeStatus())) {
      w.append(" and d.substantive_change_status=?");
      args.add(SubstantiveChangeStatus.parse(query.substantiveChangeStatus()).name());
    }
    if (notBlank(query.ftaScheme())) {
      w.append(" and d.fta_scheme=?");
      args.add(query.ftaScheme().trim().toUpperCase(java.util.Locale.ROOT));
    }
    if (Boolean.TRUE.equals(query.claimedOnly())) {
      w.append(" and d.preference_claimed");
    }
    if (query.setId() != null) {
      w.append(" and d.set_id=?");
      args.add(query.setId());
    }
    String where = w.toString();

    long total =
        sql.fetchOne("select count(*) from trade_document d where " + where, args.toArray())
            .get(0, Long.class);
    List<Object> pageArgs = new ArrayList<>(args);
    pageArgs.add(size);
    pageArgs.add((long) page * size);
    var rows =
        sql.fetch(
            "select d.* from trade_document d where "
                + where
                + " order by d.created_at desc, d.id desc limit ? offset ?",
            pageArgs.toArray());

    List<TradePreferenceConfirmationRow> mapped = new ArrayList<>();
    for (Record r : rows) {
      String status = r.get("substantive_change_status", String.class);
      String kind = r.get("document_kind", String.class);
      mapped.add(
          new TradePreferenceConfirmationRow(
              r.get("id", UUID.class),
              kind,
              labelOf(TradeDocumentKind.values(), kind),
              r.get("document_no", String.class),
              r.get("fta_scheme", String.class),
              r.get("origin_country_code", String.class),
              r.get("preference_claimed", Boolean.class),
              status,
              labelOf(SubstantiveChangeStatus.values(), status),
              r.get("substantive_change_note", String.class),
              r.get("business_confirmed_at", OffsetDateTime.class),
              r.get("business_confirmed_by", UUID.class),
              r.get("preference_rule_version", Long.class),
              r.get("status", String.class),
              r.get("created_at", OffsetDateTime.class)));
    }
    return new TradePreferenceConfirmationPage(
        mapped,
        java.util.Arrays.stream(SubstantiveChangeStatus.values())
            .map(
                s ->
                    new TradeSubstantiveChangeOption(
                        s.name(),
                        s.label(),
                        s == SubstantiveChangeStatus.PENDING_BUSINESS_CONFIRMATION))
            .toList(),
        total,
        page,
        size);
  }

  /** 缺失提醒工作台（跨单证包）。这是蓝图输出之三「缺失提醒」的操作面。 */
  public TradeReminderPage reminderPage(ReminderQuery query) {
    int page = Math.max(0, query.page());
    int size = Math.min(MAX_PAGE_SIZE, Math.max(1, query.size()));
    String sort = sortOf(REMINDER_SORTS, query.sort(), "CREATED_AT");

    List<Object> args = new ArrayList<>();
    StringBuilder w = new StringBuilder("true");
    if (notBlank(query.status())) {
      w.append(" and status=?");
      args.add(query.status().trim().toUpperCase(java.util.Locale.ROOT));
    }
    if (notBlank(query.severity())) {
      w.append(" and severity=?");
      args.add(ReminderSeverity.parse(query.severity()).name());
    }
    if (notBlank(query.reminderType())) {
      w.append(" and reminder_type=?");
      args.add(ReminderType.parse(query.reminderType()).name());
    }
    if (notBlank(query.documentKind())) {
      w.append(" and document_kind=?");
      args.add(TradeDocumentKind.parse(query.documentKind()).name());
    }
    if (query.setId() != null) {
      w.append(" and set_id=?");
      args.add(query.setId());
    }
    if (notBlank(query.keyword())) {
      w.append(" and message ilike ?");
      args.add("%" + query.keyword().trim() + "%");
    }
    String where = w.toString();

    long total =
        sql.fetchOne("select count(*) from trade_document_reminder where " + where, args.toArray())
            .get(0, Long.class);
    List<Object> pageArgs = new ArrayList<>(args);
    pageArgs.add(size);
    pageArgs.add((long) page * size);
    var rows =
        sql.fetch(
            "select * from trade_document_reminder where "
                + where
                + " order by "
                + orderBy(sort, "created_at", "severity", "id")
                + " limit ? offset ?",
            pageArgs.toArray());

    // 汇总是**全量**口径：不受当前筛选与分页影响，否则「阻断 3 条」会随翻页变数字。
    Record counts =
        sql.fetchOne(
            "select count(*) filter (where result='PASS') pass,"
                + " count(*) filter (where result='FAIL') fail,"
                + " count(*) filter (where result='PENDING_BUSINESS_CONFIRMATION') pending"
                + " from trade_document_check");
    Record openCounts =
        sql.fetchOne(
            "select count(*) filter (where status='OPEN' and severity='BLOCKER') blockers,"
                + " count(*) filter (where status='OPEN' and severity='WARNING') warnings"
                + " from trade_document_reminder");
    TradeCheckSummary summary =
        new TradeCheckSummary(
            counts.get("pass", Long.class)
                + counts.get("fail", Long.class)
                + counts.get("pending", Long.class),
            counts.get("pass", Long.class),
            counts.get("fail", Long.class),
            counts.get("pending", Long.class),
            openCounts.get("blockers", Long.class),
            openCounts.get("warnings", Long.class));

    return new TradeReminderPage(
        summary,
        rows.stream().map(this::reminderRow).toList(),
        java.util.List.of("OPEN", "RESOLVED", "DISMISSED").stream()
            .map(s -> new TradeStatusOption(s, s))
            .toList(),
        documentKindOptions(),
        total,
        page,
        size);
  }

  // ================================================================ 单证包：写

  /** 建包（草稿）。来源固定是 10.5.1.1 报关单，收发货人/币种/金额从报关单固化下来。 */
  @Transactional
  public TradeSaved createSet(UUID tenantId, TradeSetCreateRequest request) {
    if (request.sourceDeclarationId() == null) {
      throw new IllegalArgumentException("必须给出 sourceDeclarationId：贸易单证按报关单备证，不能凭空建");
    }
    Record decl =
        sql.fetchOne(
            "select * from customs_declaration where tenant_id=? and id=?",
            tenantId,
            request.sourceDeclarationId());
    if (decl == null) {
      throw new IllegalArgumentException("来源报关单不存在或无权访问：" + request.sourceDeclarationId());
    }
    String declStatus = decl.get("status", String.class);
    // 状态机判据，不是时间戳：submitted_at 一旦打上就永不消失（退单改单退回草稿也留着，
    // 因为「有没有报过关」是举证材料）。用时间戳判会把退回草稿的单错判成「已报关」。
    if (Set.of("DRAFT", "PENDING_REVIEW", "REJECTED").contains(declStatus)) {
      throw new IllegalArgumentException(
          "来源报关单还处于「" + declStatus + "」，备证资料要基于已复核的申报资料：请先在 10.5.1.1 完成人工复核再回来建包");
    }
    if ("CANCELLED".equals(declStatus)) {
      throw new IllegalArgumentException("来源报关单已取消，不能对它备证");
    }
    UUID legalEntity =
        request.legalEntityId() == null
            ? decl.get("legal_entity_id", UUID.class)
            : request.legalEntityId();

    UUID id = UUID.randomUUID();
    long seq = sql.fetchOne("select nextval('trade_document_set_code_seq')").get(0, Long.class);
    LocalDate today = sql.fetchOne("select current_date").get(0, LocalDate.class);
    String code = "TD-" + today.toString().replace("-", "") + "-" + String.format("%06d", seq);

    // 目的国与收货方国家必须一致（DB CHECK 也钉了这一条）：单证抬头上的收货国与
    // 备证目的国对不上，海关直接退。
    String destination = decl.get("destination_country_code", String.class);
    String consigneeCountry = decl.get("consignee_country_code", String.class);
    if (!destination.equals(consigneeCountry)) {
      throw new IllegalArgumentException(
          "报关单的目的国「"
              + destination
              + "」与收货方国家「"
              + consigneeCountry
              + "」不一致："
              + "备证资料按目的国规则校验，目的国对不上会让整套校验结论失去意义");
    }
    BigDecimal value =
        decl.get("declaration_value", BigDecimal.class) == null
            ? BigDecimal.ZERO
            : decl.get("declaration_value", BigDecimal.class).setScale(2, RoundingMode.HALF_UP);

    sql.execute(
        "insert into trade_document_set(id,tenant_id,set_code,source_declaration_id,"
            + "source_declaration_code,legal_entity_id,exporter_name,exporter_country_code,consignee_name,"
            + "consignee_country_code,destination_country_code,transport_mode,bill_of_lading_no,"
            + "document_currency,total_declared_value,notes,created_by)"
            + " values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        id,
        tenantId,
        code,
        request.sourceDeclarationId(),
        fit(decl.get("declaration_code", String.class), 64, "来源报关单号快照"),
        legalEntity,
        decl.get("exporter_name", String.class),
        decl.get("exporter_country_code", String.class),
        decl.get("consignee_name", String.class),
        consigneeCountry,
        destination,
        decl.get("transport_mode", String.class),
        decl.get("bill_of_lading_no", String.class),
        decl.get("declaration_currency", String.class),
        value,
        fit(request.notes(), 2000, "备注"),
        currentActor());
    recordEvent(id, "CREATED", "", "DRAFT", "", request.notes(), "", null);
    return saved(id, "单证包已创建为草稿。下一步：按目的国规则生成单证并跑完整性校验。");
  }

  @Transactional
  public TradeSaved updateSet(UUID id, TradeSetUpdateRequest request) {
    Record head = setHead(id);
    DocumentSetStatus status = DocumentSetStatus.parse(head.get("status", String.class));
    if (status != DocumentSetStatus.DRAFT && status != DocumentSetStatus.UNDER_REVIEW) {
      throw new IllegalArgumentException(
          "单证包处于「" + status.label() + "」状态，不允许改基本信息：要让改动被看到，请先撤回复核或等下游退回后再改");
    }
    if (request.expectedVersion() != null
        && request.expectedVersion() != head.get("version", Long.class)) {
      throw new IllegalStateException("单证包已被他人修改，请刷新后重试");
    }
    sql.execute(
        "update trade_document_set set submitted_to=?,notes=?,version=version+1,updated_at=now() where id=?",
        fit(request.submittedTo(), 200, "接收方"),
        fit(request.notes(), 2000, "备注"),
        id);
    recordEvent(id, "UPDATED", status.name(), status.name(), "", request.notes(), "", null);
    return saved(id, "单证包基本信息已更新。");
  }

  @Transactional
  public TradeSaved submitForReview(UUID id, TradeSetReviewRequest request) {
    Record head = setHead(id);
    DocumentSetStatus current = DocumentSetStatus.parse(head.get("status", String.class));
    if (current != DocumentSetStatus.DRAFT && current != DocumentSetStatus.REJECTED) {
      throw new IllegalArgumentException("单证包处于「" + current.label() + "」状态，不允许送复核：当前状态没有待复核这一说");
    }
    // 送复核前先看一眼有没有单证：一份都没有就送复核等于让人复核空气。
    long docs = countDocuments(id);
    if (docs == 0) {
      throw new IllegalArgumentException("这套单证包一张单证都没有，不允许送复核：先按目的国规则生成单证");
    }
    sql.execute(
        "update trade_document_set set status='UNDER_REVIEW',version=version+1,updated_at=now() where id=?",
        id);
    recordEvent(
        id,
        "UNDER_REVIEW",
        current.name(),
        "UNDER_REVIEW",
        "",
        request == null ? "" : request.reviewNotes(),
        "",
        null);
    return saved(id, "已送人工复核。复核通过后才能提交下游。");
  }

  /**
   * 人工复核通过 → 允许提交。
   *
   * <p>这一闸同时被 DB 触发器 {@code guard_trade_3_preference} 钉死：有优惠主张却没业务确认时
   * 复核**过不去**（不是复核完才发现提交不了）。所以服务层先把问题说成人话。
   */
  @Transactional
  public TradeSaved review(UUID id, TradeSetReviewRequest request) {
    Record head = setHead(id);
    DocumentSetStatus current = DocumentSetStatus.parse(head.get("status", String.class));
    if (current != DocumentSetStatus.UNDER_REVIEW) {
      throw new IllegalArgumentException("单证包处于「" + current.label() + "」状态，不允许复核通过：只有待复核的单证包能复核");
    }
    List<Map<String, Object>> blocking = pendingPreferenceDocuments(id);
    if (!blocking.isEmpty()) {
      throw new IllegalArgumentException(
          "还有 "
              + blocking.size()
              + " 份优惠原产地证主张了 FTA 优惠但没有业务确认痕迹。"
              + "「实质性改变」是否成立需要业务确认，系统只负责按规则校验与留档，"
              + "不代替业务下结论 —— 请先让业务确认或撤回优惠主张，再复核。缺哪几份："
              + blocking.stream()
                  .map(m -> String.valueOf(m.get("documentNo")))
                  .reduce((a, b) -> a + "、" + b)
                  .orElse(""));
    }
    long notFinal = countNotFinalDocuments(id);
    if (notFinal > 0) {
      throw new IllegalArgumentException("还有 " + notFinal + " 份单证没有定稿：定稿前的单证不是备证资料，复核一份草稿没有意义");
    }
    sql.execute(
        "update trade_document_set set status='APPROVED',review_round=review_round+1,reviewed_at=?::timestamptz,"
            + "reviewed_by=?,review_notes=?,version=version+1,updated_at=now() where id=?",
        OffsetDateTime.now(),
        currentActor(),
        fit(request == null ? "" : request.reviewNotes(), 2000, "复核意见"),
        id);
    recordEvent(
        id,
        "APPROVED",
        current.name(),
        "APPROVED",
        "",
        request == null ? "" : request.reviewNotes(),
        "",
        null);
    return saved(id, "复核通过。提交前仍要跑一次完整性校验并处理阻断级提醒。");
  }

  @Transactional
  public TradeSaved rejectReview(UUID id, TradeSetRejectReviewRequest request) {
    Record head = setHead(id);
    DocumentSetStatus current = DocumentSetStatus.parse(head.get("status", String.class));
    if (current != DocumentSetStatus.UNDER_REVIEW) {
      throw new IllegalArgumentException("单证包处于「" + current.label() + "」状态，不允许复核退回");
    }
    String reason = requireText(request == null ? null : request.reason(), "复核退回原因");
    sql.execute(
        "update trade_document_set set status='REJECTED',review_round=review_round+1,reviewed_at=?::timestamptz,"
            + "reviewed_by=?,rejected_reason=?,version=version+1,updated_at=now() where id=?",
        OffsetDateTime.now(),
        currentActor(),
        reason,
        id);
    recordEvent(id, "REVIEW_REJECTED", current.name(), "REJECTED", reason, "", "", null);
    return saved(id, "已复核退回。改完必须重新走一次复核才能提交。");
  }

  @Transactional
  public TradeSaved withdrawReview(UUID id, TradeSetWithdrawReviewRequest request) {
    Record head = setHead(id);
    DocumentSetStatus current = DocumentSetStatus.parse(head.get("status", String.class));
    if (current != DocumentSetStatus.UNDER_REVIEW) {
      throw new IllegalArgumentException("单证包处于「" + current.label() + "」状态，不允许撤回复核");
    }
    sql.execute(
        "update trade_document_set set status='DRAFT',version=version+1,updated_at=now() where id=?",
        id);
    recordEvent(
        id,
        "REVIEW_WITHDRAWN",
        current.name(),
        "DRAFT",
        "",
        request == null ? "" : request.notes(),
        "",
        null);
    return saved(id, "已撤回复核，回到草稿。改完重新走复核才能提交。");
  }

  /** 提交报关行 / 目的仓。三道闸（复核、校验 PASS、无阻断提醒）由 DB 触发器钉死， 绕服务层直接改状态同样被挡。 */
  @Transactional
  public TradeSaved submit(UUID id, TradeSetSubmitRequest request) {
    Record head = setHead(id);
    DocumentSetStatus current = DocumentSetStatus.parse(head.get("status", String.class));
    if (current != DocumentSetStatus.APPROVED) {
      throw new IllegalArgumentException("单证包处于「" + current.label() + "」状态，不允许提交：必须先人工复核通过");
    }
    String to = fit(request == null ? "" : request.submittedTo(), 200, "接收方");
    if (to.isEmpty()) {
      throw new IllegalArgumentException("必须写清接收方（报关行 / 目的仓）：备证资料发给了谁必须可追责");
    }
    String reference = fit(request == null ? "" : request.submissionReference(), 64, "提交回执号");
    sql.execute(
        "update trade_document_set set status='SUBMITTED',submitted_at=?::timestamptz,submitted_by=?,submitted_to=?,"
            + "submission_reference=?,version=version+1,updated_at=now() where id=?",
        OffsetDateTime.now(),
        currentActor(),
        to,
        reference,
        id);
    recordEvent(id, "SUBMITTED", current.name(), "SUBMITTED", "", to, "", null);
    return saved(id, "已提交给「" + to + "」。提交痕迹是历史，不会因为之后被退回而消失。");
  }

  @Transactional
  public TradeSaved acknowledge(UUID id, TradeSetAcknowledgeRequest request) {
    Record head = setHead(id);
    DocumentSetStatus current = DocumentSetStatus.parse(head.get("status", String.class));
    if (current != DocumentSetStatus.SUBMITTED) {
      throw new IllegalArgumentException("单证包处于「" + current.label() + "」状态，不允许登记接收");
    }
    sql.execute(
        "update trade_document_set set status='ACKNOWLEDGED',acknowledged_at=?::timestamptz,"
            + "acknowledgement_reference=?,version=version+1,updated_at=now() where id=?",
        OffsetDateTime.now(),
        fit(request == null ? "" : request.acknowledgementReference(), 64, "接收回执号"),
        id);
    recordEvent(
        id,
        "ACKNOWLEDGED",
        current.name(),
        "ACKNOWLEDGED",
        "",
        request == null ? "" : request.acknowledgementReference(),
        "",
        null);
    return saved(id, "已登记下游接收。终态，不再接受任何动作。");
  }

  @Transactional
  public TradeSaved sendBack(UUID id, TradeSetSendBackRequest request) {
    Record head = setHead(id);
    DocumentSetStatus current = DocumentSetStatus.parse(head.get("status", String.class));
    if (current != DocumentSetStatus.SUBMITTED) {
      throw new IllegalArgumentException("单证包处于「" + current.label() + "」状态，不允许登记退回");
    }
    String reason = requireText(request == null ? null : request.reason(), "退回原因");
    sql.execute(
        "update trade_document_set set status='SENT_BACK',sent_back_at=?::timestamptz,sent_back_reason=?,"
            + "version=version+1,updated_at=now() where id=?",
        OffsetDateTime.now(),
        reason,
        id);
    recordEvent(id, "SENT_BACK", current.name(), "SENT_BACK", reason, "", "", null);
    return saved(id, "已登记被退回。改完必须重新走复核才能再次提交。");
  }

  @Transactional
  public TradeSaved cancel(UUID id, TradeSetCancelRequest request) {
    Record head = setHead(id);
    DocumentSetStatus current = DocumentSetStatus.parse(head.get("status", String.class));
    if (current == DocumentSetStatus.ACKNOWLEDGED) {
      throw new IllegalArgumentException("下游已接收，单证包已进入终态，不允许取消");
    }
    String reason = requireText(request == null ? null : request.reason(), "取消原因");
    sql.execute(
        "update trade_document_set set status='CANCELLED',cancelled_at=?::timestamptz,cancelled_by=?,cancel_reason=?,"
            + "version=version+1,updated_at=now() where id=?",
        OffsetDateTime.now(),
        currentActor(),
        reason,
        id);
    recordEvent(id, "CANCELLED", current.name(), "CANCELLED", reason, "", "", null);
    return saved(id, "单证包已取消。备证资料已生成过，取消后无法追责。");
  }

  // ================================================================ 单证生成

  /**
   * 生成单证：按报关明细 + 模板生成**内容快照**。
   *
   * <p>content 在这里固化而不是读取时重算 —— 备证资料必须可复现、可举证。 报关明细一改，历史单证不能跟着变。
   */
  @Transactional
  public TradeDocumentSaved generateDocument(UUID setId, TradeDocumentCreateRequest request) {
    Record head = setHead(setId);
    DocumentSetStatus status = DocumentSetStatus.parse(head.get("status", String.class));
    if (status != DocumentSetStatus.DRAFT
        && status != DocumentSetStatus.UNDER_REVIEW
        && status != DocumentSetStatus.REJECTED) {
      throw new IllegalArgumentException(
          "单证包处于「" + status.label() + "」状态，不允许生成/改版单证：" + "要让改动被看到，请先撤回复核或等下游退回后再改");
    }
    TradeDocumentKind kind = TradeDocumentKind.parse(request.documentKind());
    UUID templateId = request.templateId();
    if (templateId != null) {
      Record t = templateHead(templateId);
      if (!kind.name().equals(t.get("document_kind", String.class))) {
        throw new IllegalArgumentException(
            "模板类型是「"
                + t.get("document_kind", String.class)
                + "」，与要生成的「"
                + kind.name()
                + "」不是同一种单证");
      }
    }
    String currency =
        moneyOrDefault(request.currency(), head.get("document_currency", String.class), "单证币种");
    if (!currency.equals(head.get("document_currency", String.class))) {
      throw new IllegalArgumentException(
          "单证币种「"
              + currency
              + "」与单证包币种「"
              + head.get("document_currency", String.class)
              + "」不一致：币种对不上的发票提交上去是申报不实");
    }

    int nextVersion =
        sql.fetchOne(
                "select coalesce(max(document_version),0)+1 from trade_document"
                    + " where tenant_id=? and set_id=? and document_kind=?",
                head.get("tenant_id", UUID.class),
                setId,
                kind.name())
            .get(0, Integer.class);
    Record prevRow =
        sql.fetchOne(
            "select id from trade_document where tenant_id=? and set_id=? and document_kind=?"
                + " order by document_version desc limit 1",
            head.get("tenant_id", UUID.class),
            setId,
            kind.name());
    UUID prev = prevRow == null ? null : prevRow.get(0, UUID.class);
    if (nextVersion > 1) {
      // 改版：旧版本置 SUPERSEDED 而不是删 —— 备证资料的历史版本是举证材料。
      sql.execute(
          "update trade_document set status='SUPERSEDED',updated_at=now() where id=?", prev);
    }
    String documentNo =
        notBlank(request.documentNo())
            ? fit(request.documentNo(), 64, "单证号")
            : prefixOf(kind) + "-" + head.get("set_code", String.class) + "-v" + nextVersion;

    Map<String, Object> content =
        request.content() == null || request.content().isEmpty()
            ? buildContent(setId, head, kind, templateId, currency)
            : new LinkedHashMap<>(request.content());

    BigDecimal amount = amountOf(setId, kind);
    BigDecimal quantity = quantityOf(setId);
    Integer packages = packagesOf(setId);

    UUID id = UUID.randomUUID();
    // supersedes_document_id 必须挂上前一版：改版链闸（guard_trade_document_chain）
    // 会核对「版本 N 必须接在版本 N-1 之后」，不挂链就等于凭空跳到第 2 版。
    sql.execute(
        "insert into trade_document(id,tenant_id,set_id,line_no,document_kind,document_no,document_version,"
            + "template_id,currency,total_amount,quantity_total,package_count,content,supersedes_document_id,"
            + "source_reference,created_at)"
            + " values(?,?,?,1,?,?,?,?,?,?,?,?,?::jsonb,?,?,now())",
        id,
        head.get("tenant_id", UUID.class),
        setId,
        kind.name(),
        documentNo,
        nextVersion,
        templateId,
        currency,
        amount,
        quantity,
        packages,
        toJson(content, "单证内容"),
        prev,
        // 生成阶段还没有「文件放在哪」：source_reference 是定稿时写的定位键，
        // 这里刻意留空而不是塞备注 —— 塞进去会让「定稿前就有定位」看起来像已定稿。
        "");
    refreshTotals(setId);
    recordEvent(
        setId,
        "DOCUMENT_GENERATED",
        status.name(),
        status.name(),
        "",
        kind.label() + " 第 " + nextVersion + " 版",
        "DOCUMENT",
        id);
    return new TradeDocumentSaved(
        id,
        setId,
        kind.name(),
        kind.label(),
        nextVersion,
        "DRAFT",
        0,
        "单证已生成（第 " + nextVersion + " 版，内容是生成那一刻的快照）。定稿后才能复核通过。");
  }

  /** 定稿单证：必须有签发时间与来源定位 —— 定稿前的单证不是备证资料。 */
  @Transactional
  public TradeDocumentSaved finalizeDocument(
      UUID setId, UUID documentId, TradeDocumentFinalizeRequest request) {
    Record doc = documentHead(documentId);
    if (!doc.get("set_id", UUID.class).equals(setId)) {
      throw new IllegalArgumentException("单证不存在或不属于这套单证包：" + documentId);
    }
    if ("SUPERSEDED".equals(doc.get("status", String.class))) {
      throw new IllegalArgumentException("这张单证已被新版本取代，不允许再定稿：改版后请给新版本定稿");
    }
    String reference = fit(request == null ? "" : request.sourceReference(), 64, "来源定位");
    if (reference.isEmpty()) {
      throw new IllegalArgumentException("定稿必须写清来源定位（单证文件放在哪 / 出自哪个系统）：" + "没有定位的单证在举证时拿不出来");
    }
    String documentNo =
        notBlank(request == null ? null : request.documentNo())
            ? fit(request.documentNo(), 64, "单证号")
            : doc.get("document_no", String.class);
    sql.execute(
        "update trade_document set status='FINAL',issued_at=?::timestamptz,prepared_by=?,document_no=?,source_reference=?,"
            + "updated_at=now() where id=?",
        OffsetDateTime.now(),
        currentActor(),
        documentNo,
        reference,
        documentId);
    Record head = setHead(setId);
    refreshTotals(setId);
    recordEvent(
        setId,
        "DOCUMENT_FINALIZED",
        head.get("status", String.class),
        head.get("status", String.class),
        "",
        doc.get("document_kind", String.class),
        "DOCUMENT",
        documentId);
    return new TradeDocumentSaved(
        documentId,
        setId,
        doc.get("document_kind", String.class),
        labelOf(TradeDocumentKind.values(), doc.get("document_kind", String.class)),
        doc.get("document_version", Integer.class),
        "FINAL",
        0,
        "单证已定稿。定稿后要改只能出新版本（旧版本置为被取代，不覆盖）。");
  }

  // ================================================================ 优惠主张与业务确认

  /**
   * 登记 FTA 优惠主张。**只登记主张，不判定成立**。
   *
   * <p>主张后状态停在 {@code PENDING_BUSINESS_CONFIRMATION}，直到业务来确认 ——
   * 这正是蓝图关系段要的那道闸：系统按规则校验与留档，不替业务判断「实质性改变」是否成立。
   */
  @Transactional
  public TradeDocumentSaved claimPreference(
      UUID setId, UUID documentId, TradePreferenceClaimRequest request) {
    Record head = setHead(setId);
    Record doc = documentHead(documentId);
    if (!doc.get("set_id", UUID.class).equals(setId)) {
      throw new IllegalArgumentException("单证不存在或不属于这套单证包：" + documentId);
    }
    TradeDocumentKind kind = TradeDocumentKind.parse(doc.get("document_kind", String.class));
    if (!kind.isOriginCertificate()) {
      throw new IllegalArgumentException(
          "「" + kind.label() + "」不是原产地证，不能主张 FTA 优惠：" + "只有原产地证 / RCEP 原产地证承载优惠主张");
    }
    if ("SUPERSEDED".equals(doc.get("status", String.class))) {
      throw new IllegalArgumentException("这张单证已被新版本取代，不能在旧版本上主张优惠");
    }
    String scheme =
        requireText(request == null ? null : request.ftaScheme(), "优惠方案")
            .toUpperCase(java.util.Locale.ROOT);
    String origin = requireCountry(request == null ? null : request.originCountryCode(), "原产国/地区");
    if (request == null || request.preferenceRuleId() == null) {
      throw new IllegalArgumentException("必须引用一条真实存在的优惠规则：优惠主张要能留档，就必须答得出「按哪条规则主张的」");
    }
    Record rule =
        sql.fetchOne(
            "select * from trade_origin_preference_rule where id=?", request.preferenceRuleId());
    if (rule == null) {
      throw new IllegalArgumentException("优惠规则不存在或无权访问：" + request.preferenceRuleId());
    }
    if (!scheme.equals(rule.get("scheme", String.class))) {
      throw new IllegalArgumentException(
          "主张的方案「" + scheme + "」与规则里的方案「" + rule.get("scheme", String.class) + "」不是同一个");
    }
    sql.execute(
        "update trade_document set fta_scheme=?,preference_claimed=true,origin_country_code=?,"
            + "preference_rule_id=?,substantive_change_status='PENDING_BUSINESS_CONFIRMATION',"
            + "substantive_change_note='',business_confirmed_at=null,business_confirmed_by=null,"
            + "preference_rule_version=null,updated_at=now() where id=?",
        scheme,
        origin,
        request.preferenceRuleId(),
        documentId);
    recordEvent(
        setId,
        "PREFERENCE_CLAIMED",
        head.get("status", String.class),
        head.get("status", String.class),
        "",
        scheme + " / 原产国 " + origin,
        "DOCUMENT",
        documentId);
    refreshTotals(setId);
    return new TradeDocumentSaved(
        documentId,
        setId,
        kind.name(),
        kind.label(),
        doc.get("document_version", Integer.class),
        doc.get("status", String.class),
        0,
        "优惠主张已登记，状态停在「待业务确认」。"
            + "「实质性改变」是否成立需要业务确认——系统只负责按规则校验与留档，不代替业务下结论，"
            + "所以在确认之前这套单证提交不出去。");
  }

  /**
   * 业务确认（或确认不成立）。**这是本模块唯一能把优惠从「待确认」推进去的入口**， 而且它只做三件事：收下确认人与依据、把规则版本钉在单证上、记事件。
   *
   * <p>刻意没有第四条：不判断确认的依据在法律上是否站得住 —— 那是业务/法务的活。
   */
  @Transactional
  public TradeDocumentSaved confirmPreference(
      UUID setId, UUID documentId, TradePreferenceClaimRequest request) {
    Record head = setHead(setId);
    Record doc = documentHead(documentId);
    if (!doc.get("set_id", UUID.class).equals(setId)) {
      throw new IllegalArgumentException("单证不存在或不属于这套单证包：" + documentId);
    }
    if (!Boolean.TRUE.equals(doc.get("preference_claimed", Boolean.class))) {
      throw new IllegalArgumentException("这张单证还没有主张 FTA 优惠，无从确认：先登记优惠主张");
    }
    if ("SUPERSEDED".equals(doc.get("status", String.class))) {
      throw new IllegalArgumentException("这张单证已被新版本取代，请在当前版本上确认");
    }
    String note = requireText(request == null ? null : request.substantiveChangeNote(), "确认依据");
    if (note.length() < 5) {
      throw new IllegalArgumentException(
          "确认依据太短：优惠原产地证能显著降低关税，「凭什么适用」必须写清楚，" + "否则事后无法回答「这批货的优惠是谁批的、依据是什么」");
    }
    boolean confirmed = request.confirmed() != null && request.confirmed();
    UUID ruleId = doc.get("preference_rule_id", UUID.class);
    Long ruleVersion =
        ruleId == null
            ? null
            : sql.fetchOne("select version from trade_origin_preference_rule where id=?", ruleId)
                .get(0, Long.class);
    if (confirmed && ruleVersion == null) {
      throw new IllegalArgumentException("优惠已确认但读不到规则版本：无法留档「按哪一版规则批的」");
    }
    sql.execute(
        "update trade_document set substantive_change_status=?,substantive_change_note=?,"
            + "business_confirmed_at=?::timestamptz,business_confirmed_by=?,preference_rule_version=?,updated_at=now()"
            + " where id=?",
        confirmed ? "CONFIRMED" : "REJECTED",
        note,
        OffsetDateTime.now(),
        currentActor(),
        confirmed ? ruleVersion : null,
        documentId);
    recordEvent(
        setId,
        confirmed ? "BUSINESS_CONFIRMED" : "BUSINESS_REJECTED",
        head.get("status", String.class),
        head.get("status", String.class),
        note,
        doc.get("fta_scheme", String.class),
        "DOCUMENT",
        documentId);
    refreshTotals(setId);
    String kind = labelOf(TradeDocumentKind.values(), doc.get("document_kind", String.class));
    return new TradeDocumentSaved(
        documentId,
        setId,
        doc.get("document_kind", String.class),
        kind,
        doc.get("document_version", Integer.class),
        doc.get("status", String.class),
        0,
        confirmed
            ? "业务已留痕确认（按规则版本 "
                + ruleVersion
                + "）。这条留档只说明「有人确认过」，"
                + "不代表系统判定「实质性改变」在法律上成立 —— 那个结论由业务承担。"
            : "业务确认不成立，优惠主张已撤回。这张单证不再需要业务确认。");
  }

  // ================================================================ 完整性校验

  /**
   * 跑一次完整性校验（蓝图输出之二）。
   *
   * <p>七条判据，逐条都在 {@code items} 里留痕：
   *
   * <ol>
   *   <li>该目的国一条规则都没配 → <b>不通过</b>（没有定义要求就无法断言齐不齐）
   *   <li>必备单证缺失 / 未定稿 → 阻断级缺失提醒
   *   <li>有条件必备但没出单证 → <b>提示级</b>提醒请人判断条件是否适用（系统不执行条件）
   *   <li>规则要求认证 → 读租户可维护的认证状态；过期/撤销/未登记各自报对应提醒
   *   <li>优惠主张待业务确认 → 阻断级提醒，且结论是「待业务确认」而**不是**「通过」
   *   <li>已出但规则没要求的单证 → 提示级提醒（多余单证有时会被目的国拒收）
   *   <li>汇总数与明细逐项对账，对不上直接报
   * </ol>
   *
   * <p>重跑时把上一轮已不再报的提醒**显式自动结清**（带说明与处理人）， 否则「历史阻断提醒」会永久挡住一份已经补齐的单证包。
   */
  @Transactional
  public TradeSaved runCheck(UUID setId) {
    Record head = setHead(setId);
    DocumentSetStatus status = DocumentSetStatus.parse(head.get("status", String.class));
    if (status == DocumentSetStatus.CANCELLED || status == DocumentSetStatus.ACKNOWLEDGED) {
      throw new IllegalArgumentException("单证包处于「" + status.label() + "」终态，不允许再跑校验");
    }
    UUID tenantId = head.get("tenant_id", UUID.class);
    String country = head.get("destination_country_code", String.class);
    LocalDate today = sql.fetchOne("select current_date").get(0, LocalDate.class);

    var rules =
        sql.fetch(
            "select * from trade_document_rule where tenant_id=? and country_code=? and status='ACTIVE'"
                + " and valid_from<=? and (valid_to is null or valid_to>=?) order by document_kind",
            tenantId,
            country,
            today,
            today);
    var documents =
        sql.fetch(
            "select * from trade_document where tenant_id=? and set_id=? and status<>'SUPERSEDED'",
            tenantId,
            setId);

    List<Object> items = new ArrayList<>();
    List<Pending> pendings = new ArrayList<>();
    int required = 0;
    int satisfied = 0;
    int missing = 0;
    int pendingBusiness = 0;

    // ① 该目的国一条规则都没配：没有定义要求就无法断言齐不齐，所以报不通过而不是「没有要求就算通过」。
    if (rules.isEmpty()) {
      // required 也 +1：DB 的 CHECK 是 satisfied+missing<=required，
      // 只把 missing 加 1 而不同步加 required，这行自己就会被 CHECK 拒掉。
      required++;
      missing++;
      items.add(
          item(
              "OTHER",
              "MANDATORY",
              "MISSING",
              "RULE",
              "目的国 " + country + " 没有配置任何单证要求规则：无法确认这套单证齐不齐，" + "请先在「目的国单证要求」里配置规则再校验"));
      pendings.add(
          new Pending(
              "OTHER",
              ReminderType.MISSING_DOCUMENT,
              ReminderSeverity.BLOCKER,
              "目的国 " + country + " 没有配置任何单证要求规则，无法判定完整性"));
    }

    Set<String> ruledKinds = new LinkedHashSet<>();
    for (Record rule : rules) {
      String kind = rule.get("document_kind", String.class);
      ruledKinds.add(kind);
      RequirementLevel level = RequirementLevel.parse(rule.get("requirement_level", String.class));
      Record doc = null;
      for (Record d : documents) {
        if (kind.equals(d.get("document_kind", String.class))) {
          doc = d;
          break;
        }
      }
      if (level == RequirementLevel.OPTIONAL) {
        items.add(
            item(
                kind,
                level.name(),
                doc == null ? "OPTIONAL_NOT_PROVIDED" : "PROVIDED",
                "RULE",
                "可选单证，未提供不阻断"));
        continue;
      }
      if (level == RequirementLevel.CONDITIONAL) {
        if (doc == null) {
          // 条件是人口径，系统不执行：只报提示级提醒请人判断是否适用。
          items.add(
              item(
                  kind,
                  level.name(),
                  "CONDITIONAL_REVIEW_REQUIRED",
                  "RULE",
                  "有条件必备但未提供：适用条件「"
                      + rule.get("condition_expression", String.class)
                      + "」需人工判断是否适用（系统不执行该条件）"));
          pendings.add(
              new Pending(
                  kind,
                  ReminderType.MISSING_DOCUMENT,
                  ReminderSeverity.WARNING,
                  "「"
                      + labelOf(TradeDocumentKind.values(), kind)
                      + "」有条件必备但未提供。适用条件（人工判断）："
                      + rule.get("condition_expression", String.class)));
        } else {
          required++;
          if ("FINAL".equals(doc.get("status", String.class))) {
            satisfied++;
            items.add(item(kind, level.name(), "PROVIDED_FINAL", "DOCUMENT", "已提供并定稿"));
          } else {
            missing++;
            items.add(
                item(kind, level.name(), "PROVIDED_NOT_FINAL", "DOCUMENT", "已生成但未定稿，定稿前不是备证资料"));
            pendings.add(
                new Pending(
                    kind,
                    ReminderType.DOCUMENT_NOT_FINAL,
                    ReminderSeverity.BLOCKER,
                    "「" + labelOf(TradeDocumentKind.values(), kind) + "」已生成但未定稿"));
          }
        }
        continue;
      }
      // MANDATORY
      required++;
      if (doc == null) {
        missing++;
        items.add(item(kind, level.name(), "MISSING", "RULE", "必备单证缺失"));
        pendings.add(
            new Pending(
                kind,
                ReminderType.MISSING_DOCUMENT,
                ReminderSeverity.BLOCKER,
                "必备单证「" + labelOf(TradeDocumentKind.values(), kind) + "」缺失"));
      } else if (!"FINAL".equals(doc.get("status", String.class))) {
        missing++;
        items.add(item(kind, level.name(), "PROVIDED_NOT_FINAL", "DOCUMENT", "已生成但未定稿，定稿前不是备证资料"));
        pendings.add(
            new Pending(
                kind,
                ReminderType.DOCUMENT_NOT_FINAL,
                ReminderSeverity.BLOCKER,
                "必备单证「" + labelOf(TradeDocumentKind.values(), kind) + "」已生成但未定稿"));
      } else {
        satisfied++;
        items.add(item(kind, level.name(), "PROVIDED_FINAL", "DOCUMENT", "已提供并定稿"));
      }
    }

    // ④ 规则要求认证 → 读租户可维护的认证状态（16 合规未交付，来源标记如实带出）
    List<Record> declLines =
        sql.fetch(
            "select distinct sku_id from customs_declaration_line where declaration_id=?",
            head.get("source_declaration_id", UUID.class));
    for (Record rule : rules) {
      if (!Boolean.TRUE.equals(rule.get("requires_certification", Boolean.class))) {
        continue;
      }
      String kind = rule.get("document_kind", String.class);
      for (Record line : declLines) {
        UUID skuId = line.get("sku_id", UUID.class);
        var certs =
            sql.fetch(
                "select * from trade_certification_status where tenant_id=? and sku_id=?",
                tenantId,
                skuId);
        // 「这条规则要求附认证」本身就是一条要求，逐条计入 required ——
        // 否则 satisfied+missing 会超过 required，DB 的 CHECK（每条必备项要么齐备要么缺）
        // 必然拒绝，认证不合格这条就永远报不出来。
        required++;
        if (certs.isEmpty()) {
          missing++;
          items.add(
              item(
                  kind,
                  "MANDATORY",
                  "CERTIFICATION_MISSING",
                  "CERTIFICATION",
                  "单证「"
                      + labelOf(TradeDocumentKind.values(), kind)
                      + "」要求附认证状态，"
                      + "但该 SKU 没有登记任何认证"));
          pendings.add(
              new Pending(
                  kind,
                  ReminderType.CERTIFICATION_MISSING,
                  ReminderSeverity.BLOCKER,
                  "「"
                      + labelOf(TradeDocumentKind.values(), kind)
                      + "」要求认证，但 SKU "
                      + skuId
                      + " 未登记任何认证状态（16 合规未交付，这条靠租户维护）"));
          continue;
        }
        for (Record cert : certs) {
          String cstatus = cert.get("status", String.class);
          LocalDate expires = cert.get("expires_on", LocalDate.class);
          boolean expired = expires != null && expires.isBefore(today);
          String source = cert.get("certification_source", String.class);
          String certCode = cert.get("certification_code", String.class);
          if ("REVOKED".equals(cstatus)) {
            missing++;
            items.add(
                item(
                    kind,
                    "MANDATORY",
                    "CERTIFICATION_REVOKED",
                    "CERTIFICATION",
                    "认证 " + certCode + " 已被撤销"));
            pendings.add(
                new Pending(
                    kind,
                    ReminderType.CERTIFICATION_REVOKED,
                    ReminderSeverity.BLOCKER,
                    "认证「" + certCode + "」已被撤销（来源：" + source + "）"));
          } else if ("EXPIRED".equals(cstatus) || expired) {
            missing++;
            items.add(
                item(
                    kind,
                    "MANDATORY",
                    "CERTIFICATION_EXPIRED",
                    "CERTIFICATION",
                    "认证 " + certCode + " 已过期（有效期至 " + expires + "）"));
            pendings.add(
                new Pending(
                    kind,
                    ReminderType.CERTIFICATION_EXPIRED,
                    ReminderSeverity.BLOCKER,
                    "认证「" + certCode + "」已过期（有效期至 " + expires + "，来源：" + source + "）"));
          } else if ("PENDING".equals(cstatus)) {
            satisfied++;
            items.add(
                item(
                    kind,
                    "MANDATORY",
                    "CERTIFICATION_PENDING",
                    "CERTIFICATION",
                    "认证 " + certCode + " 待取证"));
            pendings.add(
                new Pending(
                    kind,
                    ReminderType.CERTIFICATION_MISSING,
                    ReminderSeverity.WARNING,
                    "认证「" + certCode + "」待取证（来源：" + source + "）"));
          } else {
            satisfied++;
            items.add(
                item(
                    kind,
                    "MANDATORY",
                    "CERTIFICATION_OK",
                    "CERTIFICATION",
                    "认证 " + certCode + " 有效（来源：" + source + "）"));
          }
        }
      }
    }

    // ⑤ 优惠主张待业务确认 —— 结论是「待业务确认」而**不是**「通过」
    for (Record doc : documents) {
      if (!Boolean.TRUE.equals(doc.get("preference_claimed", Boolean.class))) {
        continue;
      }
      String kind = doc.get("document_kind", String.class);
      String sc = doc.get("substantive_change_status", String.class);
      if ("PENDING_BUSINESS_CONFIRMATION".equals(sc)) {
        pendingBusiness++;
        items.add(
            item(
                kind,
                "MANDATORY",
                "SUBSTANTIVE_CHANGE_PENDING",
                "PREFERENCE",
                "主张了 "
                    + doc.get("fta_scheme", String.class)
                    + " 优惠但还没有业务确认痕迹"
                    + "（实质性改变是否成立需业务确认，系统不代替业务下结论）"));
        pendings.add(
            new Pending(
                kind,
                ReminderType.UNCONFIRMED_SUBSTANTIVE_CHANGE,
                ReminderSeverity.BLOCKER,
                "「"
                    + labelOf(TradeDocumentKind.values(), kind)
                    + "」主张了 "
                    + doc.get("fta_scheme", String.class)
                    + " 优惠但还没有业务确认。实质性改变是否成立需要业务确认，"
                    + "系统只负责按规则校验与留档 —— 请业务确认或撤回优惠主张。"));
      } else {
        items.add(
            item(
                kind,
                "MANDATORY",
                "SUBSTANTIVE_CHANGE_" + sc,
                "PREFERENCE",
                sc
                    + "（确认人 "
                    + doc.get("business_confirmed_by", UUID.class)
                    + "，确认时间 "
                    + doc.get("business_confirmed_at", OffsetDateTime.class)
                    + "，依据："
                    + doc.get("substantive_change_note", String.class)
                    + "）"));
      }
    }

    // ⑥ 已出但规则没要求的单证 → 提示级（多余单证有时会被目的国拒收）
    for (Record doc : documents) {
      String kind = doc.get("document_kind", String.class);
      if (ruledKinds.contains(kind)) {
        continue;
      }
      items.add(
          item(kind, "OPTIONAL", "UNDECLARED", "DOCUMENT", "已出单证但目的国 " + country + " 的规则里没有要求这一类"));
      pendings.add(
          new Pending(
              kind,
              ReminderType.UNDECLARED_DOCUMENT,
              ReminderSeverity.WARNING,
              "「"
                  + labelOf(TradeDocumentKind.values(), kind)
                  + "」已生成，但目的国 "
                  + country
                  + " 的单证要求规则里没有这一类，请确认是否多余"));
    }

    CheckResult result =
        missing > 0
            ? CheckResult.FAIL
            : (pendingBusiness > 0 ? CheckResult.PENDING_BUSINESS_CONFIRMATION : CheckResult.PASS);

    int round =
        sql.fetchOne(
                "select coalesce(max(check_round),0)+1 from trade_document_check where tenant_id=? and set_id=?",
                tenantId,
                setId)
            .get(0, Integer.class);
    UUID checkId = UUID.randomUUID();
    String missingSummary = summarize(pendings, missing, pendingBusiness);
    sql.execute(
        "insert into trade_document_check(id,tenant_id,set_id,check_round,result,required_count,"
            + "satisfied_count,missing_count,pending_business_count,rule_count,items,missing_summary,"
            + "checked_at,checked_by) values(?,?,?,?,?,?,?,?,?,?,?::jsonb,?,?::timestamptz,?)",
        checkId,
        tenantId,
        setId,
        round,
        result.name(),
        required,
        satisfied,
        missing,
        pendingBusiness,
        rules.size(),
        toJson(items, "校验明细"),
        missingSummary,
        OffsetDateTime.now(),
        currentActor());

    // 重跑：把上一轮已不再报的提醒显式自动结清（带说明与处理人），否则历史阻断会永久挡住已补齐的单证包。
    autoResolveStaleReminders(setId, checkId, pendings);
    writeReminders(tenantId, setId, checkId, pendings);

    sql.execute(
        "update trade_document_set set latest_check_id=?,latest_check_result=?,latest_check_at=?::timestamptz,"
            + "required_document_count=?,version=version+1,updated_at=now() where id=?",
        checkId,
        result.name(),
        OffsetDateTime.now(),
        required,
        setId);
    refreshTotals(setId);
    recordEvent(
        setId,
        "CHECK_RUN",
        status.name(),
        status.name(),
        missingSummary,
        "第 " + round + " 轮",
        "CHECK",
        checkId);

    return saved(
        setId,
        switch (result) {
          case PASS -> "完整性校验通过（第 " + round + " 轮，命中 " + rules.size() + " 条规则）。可以提交下游。";
          case FAIL ->
              "完整性校验不通过（第 "
                  + round
                  + " 轮）：缺 "
                  + missing
                  + " 项、待业务确认 "
                  + pendingBusiness
                  + " 项。补齐后重跑校验。";
          case PENDING_BUSINESS_CONFIRMATION ->
              "单证齐备但有 "
                  + pendingBusiness
                  + " 项优惠待业务确认（第 "
                  + round
                  + " 轮）。"
                  + "「实质性改变」是否成立需要业务确认，系统只负责按规则校验与留档 —— "
                  + "这**不算通过**，确认或撤回优惠主张后才能提交。";
        });
  }

  /** 处理/忽略一条缺失提醒。处理记录同样留痕（解决痕迹不成对会被 DB 拒）。 */
  @Transactional
  public TradeReminderSaved resolveReminder(UUID id, TradeReminderResolveRequest request) {
    Record head = sql.fetchOne("select * from trade_document_reminder where id=?", id);
    if (head == null) {
      throw new ResponseStatusException(
          org.springframework.http.HttpStatus.NOT_FOUND, "缺失提醒不存在或无权访问");
    }
    String target = upperOrDefault(request == null ? null : request.status(), "RESOLVED");
    if (!Set.of("RESOLVED", "DISMISSED").contains(target)) {
      throw new IllegalArgumentException("未知的处理动作：" + target + "，可选：RESOLVED（已处理）/ DISMISSED（忽略）");
    }
    String note = fit(request == null ? "" : request.resolutionNote(), 2000, "处理说明");
    if (note.isEmpty()) {
      throw new IllegalArgumentException("处理必须写说明：静默消失的提醒等于没提醒过");
    }
    sql.execute(
        "update trade_document_reminder set status=?,resolved_at=?::timestamptz,resolved_by=?,resolution_note=?,"
            + "updated_at=now() where id=?",
        target,
        OffsetDateTime.now(),
        currentActor(),
        note,
        id);
    UUID setId = head.get("set_id", UUID.class);
    recordEvent(
        setId,
        "DISMISSED".equals(target) ? "REMINDER_DISMISSED" : "REMINDER_RESOLVED",
        head.get("status", String.class),
        target,
        note,
        head.get("reminder_type", String.class),
        "REMINDER",
        id);
    return new TradeReminderSaved(id, target, "缺失提醒已标记为「" + target + "」。");
  }

  // ================================================================ 汇总与内部工具

  /**
   * 汇总数与实际一致。
   *
   * <p>三列一起算、一次 UPDATE —— DB 延迟约束 {@code guard_trade_set_totals} 在提交时 复核这三列。分开 update
   * 会在两次写之间留下一个不自洽的中间态。
   */
  private void refreshTotals(UUID setId) {
    // 三列一次算、一次 UPDATE。
    // 刻意**不用**「UPDATE ... SET col=(select ... 引用目标表)」的写法：UPDATE 的 SET 目标列
    // 在子查询里不可见，PG 会直接报「column does not exist」。先 SELECT 算好再 UPDATE，
    // 既绕开那个坑，也顺带让「汇总数来自一次真实查询而不是页面上手填的数字」可读。
    Record counts =
        sql.fetchOne(
            "select"
                + " (select count(*) from trade_document d where d.tenant_id=s.tenant_id"
                + "   and d.set_id=s.id and d.status<>'SUPERSEDED') docs,"
                + " (select count(*) from trade_document d where d.tenant_id=s.tenant_id"
                + "   and d.set_id=s.id and d.status<>'SUPERSEDED' and d.preference_claimed"
                + "   and d.substantive_change_status='PENDING_BUSINESS_CONFIRMATION') pending,"
                + " (select coalesce(max(c.missing_count),0) from trade_document_check c"
                + "   where c.tenant_id=s.tenant_id and c.set_id=s.id) missing,"
                + " (select c.required_count from trade_document_check c where c.tenant_id=s.tenant_id"
                + "   and c.set_id=s.id order by c.check_round desc limit 1) required"
                + " from trade_document_set s where s.id=?",
            setId);
    sql.execute(
        "update trade_document_set set total_document_count=?,pending_business_count=?,"
            + "missing_document_count=?,required_document_count=coalesce(?,required_document_count),"
            + "updated_at=now() where id=?",
        counts.get("docs", Integer.class),
        counts.get("pending", Integer.class),
        counts.get("missing", Integer.class),
        counts.get("required", Integer.class),
        setId);
  }

  /** 单证内容快照：报关单头 + 逐行申报明细 + 模板骨架。 */
  private Map<String, Object> buildContent(
      UUID setId, Record head, TradeDocumentKind kind, UUID templateId, String currency) {
    Map<String, Object> content = new LinkedHashMap<>();
    content.put("documentKind", kind.name());
    content.put("setCode", head.get("set_code", String.class));
    content.put("declarationCode", head.get("source_declaration_code", String.class));
    content.put("exporterName", head.get("exporter_name", String.class));
    content.put("exporterCountryCode", head.get("exporter_country_code", String.class));
    content.put("consigneeName", head.get("consignee_name", String.class));
    content.put("consigneeCountryCode", head.get("consignee_country_code", String.class));
    content.put("destinationCountryCode", head.get("destination_country_code", String.class));
    content.put("transportMode", head.get("transport_mode", String.class));
    content.put("billOfLadingNo", head.get("bill_of_lading_no", String.class));
    content.put("currency", currency);
    content.put("generatedFrom", "CUSTOMS_DECLARATION");
    if (templateId != null) {
      Record t = templateHead(templateId);
      content.put("templateCode", t.get("template_code", String.class));
      content.put("templateVersion", t.get("template_version", Integer.class));
      // 模板体原样带进内容快照：版式定义是数据，原样带走才可复现。
      content.put("templateBody", mapOf(t.get("template_body")));
    }
    List<Map<String, Object>> lines = new ArrayList<>();
    var declLines =
        sql.fetch(
            "select * from customs_declaration_line where declaration_id=? order by line_no",
            head.get("source_declaration_id", UUID.class));
    for (Record l : declLines) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("lineNo", l.get("line_no", Integer.class));
      m.put("skuCode", l.get("sku_code", String.class));
      m.put("skuName", l.get("sku_name", String.class));
      m.put("declaredItemName", l.get("declared_item_name", String.class));
      m.put("hsCode", l.get("hs_code", String.class));
      m.put("originCountryCode", l.get("origin_country_code", String.class));
      m.put("quantity", l.get("quantity", BigDecimal.class));
      m.put("uom", l.get("uom", String.class));
      m.put("declaredValue", l.get("declared_value", BigDecimal.class));
      m.put("netWeightKg", l.get("net_weight_kg", BigDecimal.class));
      m.put("grossWeightKg", l.get("gross_weight_kg", BigDecimal.class));
      m.put("packageCount", l.get("package_count", Integer.class));
      lines.add(m);
    }
    content.put("lines", lines);
    return content;
  }

  private BigDecimal amountOf(UUID setId, TradeDocumentKind kind) {
    // 提单/空运单/认证文件按申报货值口径，商业发票同样按申报货值；装箱单按箱数（见下方 comment）。
    Record set =
        sql.fetchOne("select source_declaration_id from trade_document_set where id=?", setId);
    if (set == null) {
      return BigDecimal.ZERO;
    }
    if (kind == TradeDocumentKind.PACKING_LIST) {
      Record p =
          sql.fetchOne(
              "select coalesce(sum(package_count),0) v from customs_declaration_line where declaration_id=?",
              set.get("source_declaration_id", UUID.class));
      return new BigDecimal(p.get("v", Integer.class)).setScale(2, RoundingMode.HALF_UP);
    }
    Record v =
        sql.fetchOne(
            "select coalesce(sum(declared_value),0) v from customs_declaration_line where declaration_id=?",
            set.get("source_declaration_id", UUID.class));
    BigDecimal raw = v.get("v", BigDecimal.class);
    return (raw == null ? BigDecimal.ZERO : raw).setScale(2, RoundingMode.HALF_UP);
  }

  private BigDecimal quantityOf(UUID setId) {
    Record set =
        sql.fetchOne("select source_declaration_id from trade_document_set where id=?", setId);
    if (set == null) {
      return BigDecimal.ZERO;
    }
    Record q =
        sql.fetchOne(
            "select coalesce(sum(quantity),0) v from customs_declaration_line where declaration_id=?",
            set.get("source_declaration_id", UUID.class));
    BigDecimal raw = q.get("v", BigDecimal.class);
    return raw == null ? BigDecimal.ZERO : raw.setScale(6, RoundingMode.HALF_UP);
  }

  private Integer packagesOf(UUID setId) {
    Record set =
        sql.fetchOne("select source_declaration_id from trade_document_set where id=?", setId);
    if (set == null) {
      return 0;
    }
    Record p =
        sql.fetchOne(
            "select coalesce(sum(package_count),0) v from customs_declaration_line where declaration_id=?",
            set.get("source_declaration_id", UUID.class));
    return p.get("v", Integer.class);
  }

  private Map<String, Object> item(
      String kind, String level, String state, String source, String detail) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("documentKind", kind);
    m.put("documentKindLabel", labelOf(TradeDocumentKind.values(), kind));
    m.put("requirementLevel", level);
    m.put("state", state);
    m.put("source", source);
    m.put("detail", detail);
    return m;
  }

  private void writeReminders(UUID tenantId, UUID setId, UUID checkId, List<Pending> pendings) {
    for (Pending p : pendings) {
      // on conflict do nothing：同一次校验里同类问题只报一条（表上有对应唯一约束兜底）。
      sql.execute(
          "insert into trade_document_reminder(id,tenant_id,set_id,check_id,document_kind,reminder_type,"
              + "severity,message) values(?,?,?,?,?,?,?,?)"
              + " on conflict (tenant_id,set_id,check_id,reminder_type,document_kind) do nothing",
          UUID.randomUUID(),
          tenantId,
          setId,
          checkId,
          p.kind(),
          p.type().name(),
          p.severity().name(),
          p.message());
    }
  }

  /**
   * 上一轮报过、这一轮不再报的提醒 → 显式自动结清。
   *
   * <p>必须结清而不是留着：提交闸数的是「未处理的阻断级提醒」，一份已经补齐的单证包 不该被上一轮的旧提醒永久挡住。但也不能静默消失 —— 结清动作带说明与处理人。
   */
  private void autoResolveStaleReminders(UUID setId, UUID checkId, List<Pending> current) {
    var open =
        sql.fetch(
            "select * from trade_document_reminder where tenant_id="
                + "(select tenant_id from trade_document_set where id=?) and set_id=? and status='OPEN'",
            setId,
            setId);
    for (Record r : open) {
      String type = r.get("reminder_type", String.class);
      String kind = r.get("document_kind", String.class);
      boolean still = false;
      for (Pending p : current) {
        if (p.type().name().equals(type) && p.kind().equals(kind)) {
          still = true;
          break;
        }
      }
      if (still) {
        continue;
      }
      sql.execute(
          "update trade_document_reminder set status='RESOLVED',resolved_at=?::timestamptz,resolved_by=?,"
              + "resolution_note=?,updated_at=now() where id=?",
          OffsetDateTime.now(),
          currentActor(),
          "本次完整性校验已不再报此项（自动结清，第 " + r.get("check_id", UUID.class) + " 轮 → 第 " + checkId + " 轮）",
          r.get("id", UUID.class));
    }
  }

  private String summarize(List<Pending> pendings, int missing, int pendingBusiness) {
    if (pendings.isEmpty()) {
      return "全部齐备，无缺失、无待业务确认项。";
    }
    List<String> parts = new ArrayList<>();
    if (missing > 0) {
      parts.add("缺 " + missing + " 项");
    }
    if (pendingBusiness > 0) {
      parts.add("待业务确认 " + pendingBusiness + " 项");
    }
    List<String> blockers =
        pendings.stream()
            .filter(p -> p.severity() == ReminderSeverity.BLOCKER)
            .map(Pending::message)
            .toList();
    String head = String.join("、", parts) + "：" + String.join("；", blockers);
    return head.length() > 1900 ? head.substring(0, 1900) + "…" : head;
  }

  private long countDocuments(UUID setId) {
    return sql.fetchOne(
            "select count(*) from trade_document where set_id=? and status<>'SUPERSEDED'", setId)
        .get(0, Long.class);
  }

  private long countNotFinalDocuments(UUID setId) {
    return sql.fetchOne(
            "select count(*) from trade_document where set_id=? and status<>'FINAL'", setId)
        .get(0, Long.class);
  }

  private List<Map<String, Object>> pendingPreferenceDocuments(UUID setId) {
    var rows =
        sql.fetch(
            "select document_kind, document_no from trade_document where set_id=? and status<>'SUPERSEDED'"
                + " and preference_claimed and substantive_change_status='PENDING_BUSINESS_CONFIRMATION'",
            setId);
    List<Map<String, Object>> out = new ArrayList<>();
    for (Record r : rows) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("documentKind", r.get("document_kind", String.class));
      m.put("documentNo", r.get("document_no", String.class));
      out.add(m);
    }
    return out;
  }

  private static String prefixOf(TradeDocumentKind kind) {
    return switch (kind) {
      case COMMERCIAL_INVOICE -> "CI";
      case PACKING_LIST -> "PL";
      case CERTIFICATE_OF_ORIGIN -> "CO";
      case RCEP_CERTIFICATE_OF_ORIGIN -> "RCEP";
      case BILL_OF_LADING -> "BL";
      case AIR_WAYBELL -> "AWB";
      case IMPORT_LICENSE -> "IL";
      case CERTIFICATION_DOC -> "CD";
      case OTHER -> "DOC";
    };
  }

  // ================================================================ 取值与映射

  private Record setHead(UUID id) {
    Record r = sql.fetchOne("select * from trade_document_set where id=?", id);
    if (r == null) {
      throw new ResponseStatusException(
          org.springframework.http.HttpStatus.NOT_FOUND, "单证包不存在或无权访问");
    }
    return r;
  }

  private Record documentHead(UUID id) {
    Record r = sql.fetchOne("select * from trade_document where id=?", id);
    if (r == null) {
      throw new ResponseStatusException(
          org.springframework.http.HttpStatus.NOT_FOUND, "单证不存在或无权访问");
    }
    return r;
  }

  private TradeSaved saved(UUID id, String message) {
    Record r = setHead(id);
    String status = r.get("status", String.class);
    return new TradeSaved(
        id,
        r.get("set_code", String.class),
        status,
        DocumentSetStatus.parse(status).label(),
        r.get("version", Long.class),
        message);
  }

  private TradeSetRow setRow(Record r) {
    String status = r.get("status", String.class);
    String result = r.get("latest_check_result", String.class);
    return new TradeSetRow(
        r.get("id", UUID.class),
        r.get("set_code", String.class),
        r.get("source_declaration_id", UUID.class),
        r.get("source_declaration_code", String.class),
        r.get("legal_entity_id", UUID.class),
        r.get("exporter_name", String.class),
        r.get("consignee_name", String.class),
        r.get("consignee_country_code", String.class),
        r.get("destination_country_code", String.class),
        r.get("transport_mode", String.class),
        r.get("document_currency", String.class),
        r.get("total_declared_value", BigDecimal.class),
        r.get("total_document_count", Integer.class),
        r.get("required_document_count", Integer.class),
        r.get("missing_document_count", Integer.class),
        r.get("pending_business_count", Integer.class),
        result,
        checkResultLabel(result),
        r.get("latest_check_at", OffsetDateTime.class),
        status,
        DocumentSetStatus.parse(status).label(),
        r.get("review_round", Integer.class),
        r.get("reviewed_at", OffsetDateTime.class),
        r.get("submitted_at", OffsetDateTime.class),
        r.get("acknowledged_at", OffsetDateTime.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class),
        r.get("version", Long.class));
  }

  private TradeDocumentRow documentRow(Record r) {
    String kind = r.get("document_kind", String.class);
    String sc = r.get("substantive_change_status", String.class);
    return new TradeDocumentRow(
        r.get("id", UUID.class),
        r.get("set_id", UUID.class),
        r.get("line_no", Integer.class),
        kind,
        labelOf(TradeDocumentKind.values(), kind),
        r.get("document_no", String.class),
        r.get("document_version", Integer.class),
        r.get("template_id", UUID.class),
        r.get("template_code", String.class),
        r.get("currency", String.class),
        r.get("total_amount", BigDecimal.class),
        r.get("quantity_total", BigDecimal.class),
        r.get("package_count", Integer.class),
        mapOf(r.get("content")),
        r.get("fta_scheme", String.class),
        r.get("preference_claimed", Boolean.class),
        r.get("origin_country_code", String.class),
        r.get("preference_rule_id", UUID.class),
        sc,
        labelOf(SubstantiveChangeStatus.values(), sc),
        r.get("substantive_change_note", String.class),
        r.get("business_confirmed_at", OffsetDateTime.class),
        r.get("business_confirmed_by", UUID.class),
        r.get("preference_rule_version", Long.class),
        r.get("status", String.class),
        r.get("supersedes_document_id", UUID.class),
        r.get("issued_at", OffsetDateTime.class),
        r.get("prepared_by", UUID.class),
        r.get("source_system", String.class),
        r.get("source_reference", String.class),
        r.get("created_at", OffsetDateTime.class));
  }

  private TradeCheckRow checkRow(Record r) {
    String result = r.get("result", String.class);
    return new TradeCheckRow(
        r.get("id", UUID.class),
        r.get("set_id", UUID.class),
        r.get("check_round", Integer.class),
        result,
        checkResultLabel(result),
        r.get("required_count", Integer.class),
        r.get("satisfied_count", Integer.class),
        r.get("missing_count", Integer.class),
        r.get("pending_business_count", Integer.class),
        r.get("rule_count", Integer.class),
        listOf(r.get("items")),
        r.get("missing_summary", String.class),
        r.get("checked_at", OffsetDateTime.class),
        r.get("checked_by", UUID.class),
        r.get("created_at", OffsetDateTime.class));
  }

  private TradeReminderRow reminderRow(Record r) {
    String type = r.get("reminder_type", String.class);
    String severity = r.get("severity", String.class);
    String kind = r.get("document_kind", String.class);
    return new TradeReminderRow(
        r.get("id", UUID.class),
        r.get("set_id", UUID.class),
        r.get("check_id", UUID.class),
        kind,
        labelOf(TradeDocumentKind.values(), kind),
        type,
        labelOf(ReminderType.values(), type),
        severity,
        labelOf(ReminderSeverity.values(), severity),
        r.get("message", String.class),
        r.get("status", String.class),
        r.get("resolved_at", OffsetDateTime.class),
        r.get("resolved_by", UUID.class),
        r.get("resolution_note", String.class),
        r.get("created_at", OffsetDateTime.class));
  }

  private TradeEventRow eventRow(Record r) {
    String kind = r.get("kind", String.class);
    return new TradeEventRow(
        r.get("id", UUID.class),
        r.get("set_id", UUID.class),
        kind,
        eventLabel(kind),
        r.get("occurred_at", OffsetDateTime.class),
        r.get("from_status", String.class),
        r.get("to_status", String.class),
        r.get("actor_id", UUID.class),
        r.get("target_kind", String.class),
        r.get("target_id", UUID.class),
        r.get("reason", String.class),
        r.get("notes", String.class),
        r.get("created_at", OffsetDateTime.class));
  }

  private static String eventLabel(String kind) {
    return switch (kind) {
      case "CREATED" -> "创建草稿";
      case "UPDATED" -> "修改草稿";
      case "UNDER_REVIEW" -> "送人工复核";
      case "APPROVED" -> "复核通过";
      case "REVIEW_REJECTED" -> "复核退回";
      case "REVIEW_WITHDRAWN" -> "撤回复核";
      case "CHECK_RUN" -> "跑完整性校验";
      case "DOCUMENT_GENERATED" -> "生成单证";
      case "DOCUMENT_FINALIZED" -> "单证定稿";
      case "PREFERENCE_CLAIMED" -> "登记优惠主张";
      case "BUSINESS_CONFIRMED" -> "业务确认优惠";
      case "BUSINESS_REJECTED" -> "业务撤回优惠";
      case "REMINDER_RESOLVED" -> "处理缺失提醒";
      case "REMINDER_DISMISSED" -> "忽略缺失提醒";
      case "SUBMITTED" -> "提交下游";
      case "ACKNOWLEDGED" -> "下游已接收";
      case "SENT_BACK" -> "下游退回";
      case "CANCELLED" -> "取消";
      default -> kind;
    };
  }

  private TradeRuleRow ruleRow(Record r) {
    String kind = r.get("document_kind", String.class);
    String level = r.get("requirement_level", String.class);
    return new TradeRuleRow(
        r.get("id", UUID.class),
        r.get("country_code", String.class),
        kind,
        labelOf(TradeDocumentKind.values(), kind),
        level,
        labelOf(RequirementLevel.values(), level),
        r.get("condition_expression", String.class),
        r.get("requires_certification", Boolean.class),
        r.get("rule_source", String.class),
        r.get("source_reference", String.class),
        r.get("valid_from", LocalDate.class),
        r.get("valid_to", LocalDate.class),
        r.get("status", String.class),
        r.get("notes", String.class),
        r.get("version", Long.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  private TradeTemplateRow templateRow(Record r) {
    String kind = r.get("document_kind", String.class);
    return new TradeTemplateRow(
        r.get("id", UUID.class),
        r.get("template_code", String.class),
        kind,
        labelOf(TradeDocumentKind.values(), kind),
        r.get("country_code", String.class),
        r.get("template_version", Integer.class),
        r.get("currency", String.class),
        mapOf(r.get("template_body")),
        r.get("status", String.class),
        r.get("supersedes_template_id", UUID.class),
        r.get("effective_from", LocalDate.class),
        r.get("effective_to", LocalDate.class),
        r.get("notes", String.class),
        r.get("version", Long.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  private TradeCertificationRow certificationRow(Record r) {
    String status = r.get("status", String.class);
    return new TradeCertificationRow(
        r.get("id", UUID.class),
        r.get("sku_id", UUID.class),
        r.get("sku_code", String.class),
        r.get("sku_name", String.class),
        r.get("certification_code", String.class),
        r.get("certification_name", String.class),
        status,
        labelOf(CertificationStatus.values(), status),
        r.get("certificate_no", String.class),
        r.get("issuing_body", String.class),
        r.get("issued_on", LocalDate.class),
        r.get("expires_on", LocalDate.class),
        r.get("document_ref", String.class),
        r.get("certification_source", String.class),
        r.get("source_reference", String.class),
        r.get("notes", String.class),
        r.get("version", Long.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  private TradePreferenceRuleRow preferenceRuleRow(Record r) {
    return new TradePreferenceRuleRow(
        r.get("id", UUID.class),
        r.get("scheme", String.class),
        r.get("destination_country_code", String.class),
        r.get("product_scope", String.class),
        r.get("hs_prefix", String.class),
        r.get("preference_criteria", String.class),
        r.get("substantive_change_criterion", String.class),
        r.get("requires_business_confirmation", Boolean.class),
        r.get("valid_from", LocalDate.class),
        r.get("valid_to", LocalDate.class),
        r.get("status", String.class),
        r.get("rule_source", String.class),
        r.get("source_reference", String.class),
        r.get("notes", String.class),
        r.get("version", Long.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  private void recordEvent(
      UUID setId,
      String kind,
      String fromStatus,
      String toStatus,
      String reason,
      String notes,
      String targetKind,
      UUID targetId) {
    sql.execute(
        "insert into trade_document_event(id,tenant_id,set_id,kind,occurred_at,from_status,to_status,"
            + "actor_id,target_kind,target_id,reason,notes)"
            + " select ?,tenant_id,?,?,?::timestamptz,?,?,?,?,?,?,? from trade_document_set where id=?",
        UUID.randomUUID(),
        setId,
        kind,
        OffsetDateTime.now(),
        fromStatus,
        toStatus,
        currentActor(),
        targetKind,
        targetId,
        fit(reason, 2000, "原因"),
        fit(notes, 2000, "备注"),
        setId);
  }

  /** 当前 RLS 租户。由 backend/libraries/tenancy 在事务内设置，客户端头改不动它。 */
  private UUID currentTenant() {
    return sql.fetchOne("select nullif(current_setting('app.current_tenant',true),'')::uuid")
        .get(0, UUID.class);
  }

  /** 当前操作人。设置名是 {@code app.actor}（AccessService 在事务里绑定）。 */
  private UUID currentActor() {
    Record r = sql.fetchOne("select nullif(current_setting('app.actor',true),'')::uuid as actor");
    UUID actor = r.get("actor", UUID.class);
    return actor == null ? currentTenant() : actor;
  }

  private static List<TradeStatusOption> statusOptions() {
    return java.util.List.of("ACTIVE", "RETIRED", "DRAFT").stream()
        .map(s -> new TradeStatusOption(s, s))
        .toList();
  }

  private static List<TradeRequirementOption> requirementOptions() {
    return java.util.Arrays.stream(RequirementLevel.values())
        .map(r -> new TradeRequirementOption(r.name(), r.label()))
        .toList();
  }

  private static List<TradeDocumentKindOption> documentKindOptions() {
    return java.util.Arrays.stream(TradeDocumentKind.values())
        .map(k -> new TradeDocumentKindOption(k.name(), k.label(), k.isOriginCertificate()))
        .toList();
  }

  /**
   * 单证包上的「最近校验结论」标签。
   *
   * <p>{@code NOT_CHECKED} 是「**还没跑过校验**」，只出现在单证包上，刻意**不**进 {@link CheckResult} 词表 ——
   * trade_document_check 上的每一次校验都必须有真结论。 所以这里不能拿 CheckResult.parse 去读它，那会把「没校验过」变成一个异常。
   */
  private static String checkResultLabel(String raw) {
    return "NOT_CHECKED".equals(raw) ? "未校验" : CheckResult.parse(raw).label();
  }

  private static String labelOf(Enum<?>[] values, String name) {
    for (var v : values) {
      if (v.name().equals(name)) {
        try {
          return (String) v.getClass().getMethod("label").invoke(v);
        } catch (ReflectiveOperationException e) {
          return name;
        }
      }
    }
    return name;
  }

  private static String orderBy(String sort, String... allowed) {
    for (String a : allowed) {
      // 大小写无关：排序键词表是 UPPER_SNAKE（SET_SORTS / RULE_SORTS …），
      // 而这里是真实列名（小写下划线）。不做这个匹配，词表就永远命中不了，
      // 排序会**静默**退回 id —— 表还在、结果也对，只是顺序不是用户要的。
      if (a.equalsIgnoreCase(sort)) {
        return a
            + (a.equals("id")
                ? ""
                : (a.startsWith("created") || a.startsWith("updated") ? " desc" : " asc"))
            + ", id";
      }
    }
    return "id";
  }

  private String toJson(Object value, String what) {
    try {
      return json.writeValueAsString(value);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalArgumentException(what + "不是可序列化的对象：" + e.getMessage());
    }
  }

  private Map<String, Object> mapOf(Object raw) {
    if (raw == null) {
      return Map.of();
    }
    try {
      return json.readValue(raw.toString(), new TypeReference<Map<String, Object>>() {});
    } catch (Exception e) {
      return Map.of();
    }
  }

  private List<Object> listOf(Object raw) {
    if (raw == null) {
      return List.of();
    }
    try {
      return json.readValue(raw.toString(), new TypeReference<List<Object>>() {});
    } catch (Exception e) {
      return List.of();
    }
  }

  private static String sourceOrDefault(String raw) {
    String v =
        raw == null || raw.isBlank()
            ? SOURCE_TENANT
            : raw.trim().toUpperCase(java.util.Locale.ROOT);
    if (!Set.of("TENANT_MAINTAINED", "MODULE_16", "CUSTOMS_FEED").contains(v)) {
      throw new IllegalArgumentException(
          "未知的规则来源：" + v + "，可选：TENANT_MAINTAINED / MODULE_16 / CUSTOMS_FEED");
    }
    return v;
  }

  private static String sourceOrDefaultCert(String raw) {
    String v =
        raw == null || raw.isBlank()
            ? SOURCE_TENANT
            : raw.trim().toUpperCase(java.util.Locale.ROOT);
    if (!Set.of("TENANT_MAINTAINED", "MODULE_16").contains(v)) {
      throw new IllegalArgumentException(
          "未知的认证状态来源：" + v + "，可选：TENANT_MAINTAINED（租户维护）/ MODULE_16（16 合规推送）");
    }
    return v;
  }

  private static String upperOrDefault(String raw, String fallback) {
    return raw == null || raw.isBlank() ? fallback : raw.trim().toUpperCase(java.util.Locale.ROOT);
  }

  private static boolean notBlank(String v) {
    return v != null && !v.isBlank();
  }

  /**
   * 排序键白名单。{@code List.of(...).contains(null)} 会直接抛 NPE（不可变 List 不收 null）， 而排序键是**可选**查询参数，前端不传就是
   * null —— 所以必须先判空再查表。 不在白名单里的值静默回落到默认键（表还在），注入串也因此进不来。
   */
  private static String sortOf(List<String> allowed, String requested, String fallback) {
    return requested != null && allowed.contains(requested) ? requested : fallback;
  }

  private static String requireText(String value, String label) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("缺少必填项：" + label);
    }
    return value.trim();
  }

  private static String requireCountry(String value, String label) {
    String v = requireText(value, label).toUpperCase(java.util.Locale.ROOT);
    if (v.length() != 2) {
      throw new IllegalArgumentException(label + "必须是两位国家/地区代码（如 CN / US），收到：" + v);
    }
    return v;
  }

  private static String optionalCountry(String value) {
    return value == null || value.isBlank() ? "" : requireCountry(value, "国家/地区");
  }

  private static String moneyOrDefault(String value, String fallback, String label) {
    if (value == null || value.isBlank()) {
      return fallback;
    }
    String v = value.trim().toUpperCase(java.util.Locale.ROOT);
    if (!v.matches("[A-Z]{3}")) {
      throw new IllegalArgumentException(label + "必须是三位大写币种代码（如 USD / CNY），收到：" + v);
    }
    return v;
  }

  /** 统一截断到列宽，超长直接报错而不是静默截断 —— 备证资料被悄悄截断就是举证失败。 */
  private static String fit(String value, int width, String label) {
    if (value == null) {
      return "";
    }
    String v = value.trim();
    if (v.length() > width) {
      throw new IllegalArgumentException(
          label + "长度超过 " + width + " 字符（当前 " + v.length() + "）。单证资料被悄悄截断比报错更糟。");
    }
    return v;
  }

  /** 校验待落库的提醒项。 */
  private record Pending(
      String kind, ReminderType type, ReminderSeverity severity, String message) {}

  // ---- 查询参数 ----

  public record RuleQuery(
      String countryCode,
      String documentKind,
      String requirementLevel,
      String status,
      String keyword,
      String sort,
      int page,
      int size) {}

  public record TemplateQuery(
      String documentKind,
      String countryCode,
      String status,
      String keyword,
      String sort,
      int page,
      int size) {}

  public record CertificationQuery(
      UUID skuId,
      String status,
      String certificationSource,
      String keyword,
      String sort,
      int page,
      int size) {}

  public record PreferenceRuleQuery(
      String scheme,
      String destinationCountryCode,
      String status,
      String sort,
      int page,
      int size) {}

  public record ReminderQuery(
      UUID setId,
      String status,
      String severity,
      String reminderType,
      String documentKind,
      String keyword,
      String sort,
      int page,
      int size) {}

  public record PreferenceConfirmationQuery(
      UUID setId,
      String substantiveChangeStatus,
      String ftaScheme,
      Boolean claimedOnly,
      int page,
      int size) {}
}
