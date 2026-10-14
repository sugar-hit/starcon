package com.starcon.warehouse;

import static com.starcon.warehouse.CustomsDeclarationModels.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 10.5.1.1 报关单管理。
 *
 * <p>蓝图：「管理报关单：按发货单生成申报资料（报关单、装箱单、发票），提交报关行/IOR， 接收放行、退单、改单结果」。输入是「发货明细；3.7.1.1 HS 编码与申报要素；货值与汇率快照；
 * 贸易方式与收发货人信息」。
 *
 * <p>六条实现纪律：
 *
 * <ol>
 *   <li><b>人工复核闸在 DB 层，不只靠服务层 if</b>：DB 触发器 {@code customs_1_state} 钉死 「{@code status → SUBMITTED}
 *       时旧状态必须已经是 REVIEWED 且 reviewed_at/reviewed_by 都非空」。 绕过服务层直接 UPDATE
 *       状态同样被挡。服务层这一层是为了把「先复核」讲成人话， 而不是唯一的防线。
 *   <li><b>复核退回后必须重新走复核</b>：{@code REJECTED → SUBMITTED} 不在状态机转换表里， 改完要先回到待复核再走一遍 ——
 *       这正是「罚款由卖家承担」的推论：改完的数据没人看过就不该再出去。
 *   <li><b>货值与汇率是快照</b>：汇率、精度刻度、汇率日期、折算本位币金额一起落库， DB 延迟约束钉死 {@code declaration_value_base = 明细货值合计
 *       × exchange_rate}。 海关事后复核时要能回答「当时按哪个汇率申报的」，所以运行时**不**去取当前汇率。
 *   <li><b>三单据按版本链保存，改单不覆盖旧版本</b>：被退单时要能举证「上一版报的是什么」， 所以 {@code status} 允许 SUPERSEDED。content
 *       是生成那一刻的快照，读取时不再重算 —— 否则明细一改，历史单据就跟着变了。
 *   <li><b>未交付依赖显式声明，不建假上游</b>：3.7.1.1 HS 编码字典被 product_compliance 服务缺失阻塞， 本模块<b>不建</b>任何 HS
 *       编码字典表（建了就是假的上游，且会成为双 writer）； {@code hsCode} 接受自由码并带 {@code hsCodeSource}/{@code
 *       hsCodeVerified} 如实标记， 申报要素留成可后补的 jsonb 结构。外部报关行接口同样未交付，回执由人工回填并 以 {@code
 *       source_system='MANUAL_ENTRY'} 如实标注录入方式。
 *   <li><b>多租户不可省</b>：所有 SQL 走 RLS，租户由 backend/libraries/tenancy 在事务内设置， 客户端伪造 {@code X-Tenant-Id}
 *       进不来也改不动它。
 * </ol>
 *
 * <p>状态机只有一处真相：{@link DeclarationStatus#allowedNext}。
 */
@Service
public class CustomsDeclarationService {

  /** 单页最大行数。 */
  static final int MAX_PAGE_SIZE = 200;

  private static final List<String> SORTS =
      List.of("UPDATED_AT", "CREATED_AT", "DECLARATION_CODE", "SUBMITTED_AT", "REVIEWED_AT");

  /** 人工录入的报关回执统一用这个来源标记，将来接了真实报关行接口由它写 BROKER_API，结构不变。 */
  private static final String SOURCE_MANUAL = "MANUAL_ENTRY";

  private final DSLContext sql;
  private final ObjectMapper json;

  public CustomsDeclarationService(DSLContext sql, ObjectMapper json) {
    this.sql = sql;
    this.json = json;
  }

  // ================================================================ 能力

  /**
   * 报关能力清单：贸易方式、运输方式、状态机、单据类型、申报要素状态、 **未交付依赖逐条声明**与四条必须讲清的口径。
   *
   * <p>本车道对集成头实际查证的结论：3.7.1.1（HS 编码与申报要素）被 product_compliance 服务缺失阻塞，未交付；外部报关行/IOR
   * 接口在本项目里没有可调用的系统与凭证，未交付。 两者都显式 {@code available=false} + 原因 + {@code ownerModule} + {@code
   * href}， <b>不建占位表、不返回空集冒充成功</b>。
   */
  public CustomsCapabilities capabilities() {
    List<CustomsDimension> dependencies =
        List.of(
            new CustomsDimension(
                "HS_CODE_DICTIONARY",
                "HS 编码字典与申报要素校验",
                false,
                "3.7.1.1",
                "/products/hs-codes",
                "3.7.1.1 HS 编码与申报要素未交付（台账记为阻塞：product_compliance 复制自 "
                    + "product_research，装不了 HS 编码）。因此本模块的 hsCode 接受自由码，"
                    + "**没有**编码字典可校验 —— 不假装有字典，也不给一个点下去没反应的选择器。"),
            new CustomsDimension(
                "BROKER_API",
                "报关行/IOR 系统对接",
                false,
                "10.5.1.1",
                "/logistics/customs-declarations",
                "外部报关行/IOR 接口未交付：本项目里没有可调用的报关行系统与凭证，"
                    + "所以提交回执与海关结果由人工回填，并以 source_system='MANUAL_ENTRY' "
                    + "如实标注「这条是人抄进来的，不是机器对接回来的」。"
                    + "将来接了真实接口由它写 BROKER_API/CUSTOMS_FEED，结构不变。"),
            new CustomsDimension(
                "TARIFF_AND_DUTY",
                "关税与税费计算",
                false,
                "10.5.1.3",
                "/logistics/tariffs",
                "下游 10.5.1.3 关税未交付：本模块只承载申报资料与汇率快照，" + "不计算关税、不做税费试算。"));

    return new CustomsCapabilities(
        dependencies,
        transitions(),
        statusOptions(),
        java.util.Arrays.stream(TradeMode.values())
            .map(m -> new CustomsTradeModeOption(m.name(), m.label()))
            .toList(),
        java.util.Arrays.stream(DeclarationTransportMode.values())
            .map(m -> new CustomsTransportModeOption(m.name(), m.label()))
            .toList(),
        java.util.Arrays.stream(CustomsDocumentType.values())
            .map(m -> new CustomsDocumentTypeOption(m.name(), m.label()))
            .toList(),
        java.util.Arrays.stream(DeclarationElementStatus.values())
            .map(m -> new CustomsElementStatusOption(m.name(), m.label()))
            .toList(),
        "人工复核是硬闸：只有「已复核」才能提交报关行，且复核时会逐行检查"
            + "HS 编码 / 申报要素 / 申报货值 / 毛重 / 箱数 / 计量单位是否齐备。"
            + "蓝图原文——报关数据错误导致的扣货与罚款由卖家承担，所以这一闸不能省。",
        "货值与汇率是快照不是实时值：汇率、精度刻度、汇率日期与折算本位币金额一起落库并被"
            + "DB 延迟约束钉死自洽。海关事后复核 12 个月前的报关单时要能回答"
            + "「当时按哪个汇率申报的」，所以读取时不重新取当前汇率。",
        "一张头程发运单只允许一张报关单：重复报关是真实法律风险（可能被判申报不实），"
            + "而且「一票货报了两次」在账上完全看不出来。改单走状态机与单据版本链，"
            + "不靠再造一张单来解决。",
        "HS 编码上游未交付，当前不可用：hs_code 接受自由码，并用 hs_code_source / hs_code_verified "
            + "如实记下「这个码是人工填的还是字典来的」；申报要素留成可后补结构"
            + "（jsonb 对象 + 齐备性状态机），3.7.1.1 交付后由它回填即可，不需要改结构。");
  }

  /** 状态机的合法转换，前端据此只给合法的按钮。 */
  public List<CustomsTransition> transitions() {
    List<CustomsTransition> out = new ArrayList<>();
    for (var from : DeclarationStatus.values()) {
      for (var to : DeclarationStatus.allowedNext(from)) {
        out.add(
            new CustomsTransition(
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

  private static String actionOf(DeclarationStatus from, DeclarationStatus to) {
    if (from == to) {
      return "UPDATE";
    }
    return switch (to) {
      case PENDING_REVIEW -> "SUBMIT_FOR_REVIEW";
      case REVIEWED -> "REVIEW";
      case REJECTED -> "REJECT_REVIEW";
      case SUBMITTED -> "SUBMIT";
      case CLEARED -> "RECORD_CLEARED";
      case CUSTOMS_REJECTED -> "RECORD_REJECTED";
      case AMENDED -> "AMEND";
      case DRAFT -> "RETURN_TO_DRAFT";
      case CANCELLED -> "CANCEL";
      default -> "UPDATE";
    };
  }

  private static String noteOf(DeclarationStatus from, DeclarationStatus to) {
    if (from == DeclarationStatus.REVIEWED && to == DeclarationStatus.SUBMITTED) {
      return "提交报关行：必须先完成人工复核，且报关单/装箱单/商业发票三份单据都已定稿。";
    }
    if (from == DeclarationStatus.PENDING_REVIEW && to == DeclarationStatus.REVIEWED) {
      return "复核通过：会逐行检查申报要素齐备性（HS 编码 / 申报要素 / 申报货值 / 毛重 / 箱数 / 计量单位）。";
    }
    if (to == DeclarationStatus.DRAFT && from == DeclarationStatus.REJECTED) {
      return "退回草稿：改完必须重新走一次复核才能再次提交 —— 改完没人看过就不该再出去一次。";
    }
    if (to == DeclarationStatus.CANCELLED) {
      return "取消必须给原因：报关资料已生成过，取消后无法追责。";
    }
    if (to == DeclarationStatus.CUSTOMS_REJECTED) {
      return "退单必须给原因：改单要按退单原因改，没有原因就无从改起。";
    }
    if (from == DeclarationStatus.CLEARED || from == DeclarationStatus.CANCELLED) {
      return "终态不允许离开：货已清关放行/单已作废，要修正请走改单（登记结果为改单）。";
    }
    return "";
  }

  private static List<CustomsStatusOption> statusOptions() {
    return java.util.Arrays.stream(DeclarationStatus.values())
        .map(s -> new CustomsStatusOption(s.name(), s.label()))
        .toList();
  }

  // ================================================================ 列表

  /** 报关单列表 + 全量汇总（**合计不受分页影响**）+ 词表下拉。 */
  public CustomsDeclarationPage page(CustomsQuery query) {
    int page = Math.max(0, query.page());
    int size = Math.min(MAX_PAGE_SIZE, Math.max(1, query.size()));
    String sort =
        query.sort() == null || !SORTS.contains(query.sort()) ? "UPDATED_AT" : query.sort();

    List<Object> args = new ArrayList<>();
    String where = where(query, args);

    long total =
        sql.fetchOne("select count(*) from customs_declaration where " + where, args.toArray())
            .get(0, Long.class);

    List<Object> pageArgs = new ArrayList<>(args);
    pageArgs.add(size);
    pageArgs.add((long) page * size);
    var rows =
        sql.fetch(
            "select * from customs_declaration where "
                + where
                + " order by "
                + orderBy(sort)
                + " limit ? offset ?",
            pageArgs.toArray());

    CustomsSummary summary = summary(where, args);

    return new CustomsDeclarationPage(
        summary,
        rows.stream().map(this::row).toList(),
        statusOptions(),
        java.util.Arrays.stream(TradeMode.values())
            .map(m -> new CustomsTradeModeOption(m.name(), m.label()))
            .toList(),
        java.util.Arrays.stream(DeclarationTransportMode.values())
            .map(m -> new CustomsTransportModeOption(m.name(), m.label()))
            .toList(),
        total,
        page,
        size);
  }

  private String where(CustomsQuery query, List<Object> args) {
    StringBuilder w = new StringBuilder("true");
    if (query.status() != null && !query.status().isEmpty()) {
      List<DeclarationStatus> parsed =
          query.status().stream().map(DeclarationStatus::parse).toList();
      w.append(" and status in (");
      for (int i = 0; i < parsed.size(); i++) {
        w.append(i == 0 ? "?" : ",?");
        args.add(parsed.get(i).name());
      }
      w.append(')');
    }
    if (query.tradeMode() != null && !query.tradeMode().isBlank()) {
      w.append(" and trade_mode=?");
      args.add(TradeMode.parse(query.tradeMode()).name());
    }
    if (query.transportMode() != null && !query.transportMode().isBlank()) {
      w.append(" and transport_mode=?");
      args.add(DeclarationTransportMode.parse(query.transportMode()).name());
    }
    if (query.destinationCountryCode() != null && !query.destinationCountryCode().isBlank()) {
      w.append(" and destination_country_code=?");
      args.add(query.destinationCountryCode().trim().toUpperCase(java.util.Locale.ROOT));
    }
    if (query.sourceShipmentId() != null) {
      w.append(" and source_shipment_id=?");
      args.add(query.sourceShipmentId());
    }
    if (Boolean.TRUE.equals(query.pendingReviewOnly())) {
      w.append(" and status='PENDING_REVIEW'");
    }
    if (query.keyword() != null && !query.keyword().isBlank()) {
      w.append(
          " and (declaration_code ilike ? or source_shipment_code ilike ?"
              + " or exporter_name ilike ? or consignee_name ilike ?"
              + " or bill_of_lading_no ilike ? or broker_name ilike ?)");
      String k = "%" + query.keyword().trim() + "%";
      for (int i = 0; i < 6; i++) {
        args.add(k);
      }
    }
    return w.toString();
  }

  private static String orderBy(String sort) {
    return switch (sort) {
      case "CREATED_AT" -> "created_at desc, id";
      case "DECLARATION_CODE" -> "declaration_code, id";
      case "SUBMITTED_AT" -> "submitted_at desc nulls last, id";
      case "REVIEWED_AT" -> "reviewed_at desc nulls last, id";
      default -> "updated_at desc, id";
    };
  }

  /** 汇总：与分页无关（同一套 where 条件，不带 limit/offset）。 */
  private CustomsSummary summary(String where, List<Object> args) {
    Record r =
        sql.fetchOne(
            "select count(*) as total,"
                + " count(*) filter (where status='DRAFT') as draft,"
                + " count(*) filter (where status='PENDING_REVIEW') as pending_review,"
                + " count(*) filter (where status='REVIEWED') as reviewed,"
                + " count(*) filter (where status='SUBMITTED') as submitted,"
                + " count(*) filter (where status='CLEARED') as cleared,"
                + " count(*) filter (where status='CUSTOMS_REJECTED') as customs_rejected,"
                + " coalesce(sum(declaration_value) filter (where declaration_currency ="
                + "   (select min(declaration_currency) from customs_declaration)),0) as total_value,"
                + " coalesce(min(declaration_currency),'USD') as total_currency"
                + " from customs_declaration where "
                + where,
            args.toArray());
    return new CustomsSummary(
        r.get("total", Long.class),
        r.get("draft", Long.class),
        r.get("pending_review", Long.class),
        r.get("reviewed", Long.class),
        r.get("submitted", Long.class),
        r.get("cleared", Long.class),
        r.get("customs_rejected", Long.class),
        r.get("total_value", BigDecimal.class),
        r.get("total_currency", String.class));
  }

  // ================================================================ 详情

  /**
   * 报关单详情：头 + 明细 + 三单据 + 提交回执 + 结果回执 + 事件时间线 + 当前状态的合法下一步。
   *
   * <p>跨租户在 RLS 下查不到 → 404（403 会泄露「这张单存在」）。
   */
  public CustomsDeclarationDetail detail(UUID id) {
    Record head = head(id);
    UUID tenantId = head.get("tenant_id", UUID.class);
    List<CustomsTransition> allowed =
        transitions().stream()
            .filter(t -> t.from().equals(head.get("status", String.class)))
            .toList();
    return new CustomsDeclarationDetail(
        row(head),
        lines(id),
        documents(id),
        submissions(id),
        results(id),
        events(id),
        allowed,
        "来源是 10.4.1.1 头程发运单（迁移 V80，同服务，复合外键保证「引用的那张发运单真的存在" + "且与本租户一致」）。一票货只允许一张报关单，所以本单的发运单是唯一的。",
        "人工复核是硬闸：只有「已复核」才能提交报关行。复核不通过退回草稿后，" + "改完必须重新走一次复核才能再次提交。");
  }

  public List<CustomsLineRow> lines(UUID declarationId) {
    return sql
        .fetch(
            "select * from customs_declaration_line where declaration_id=?"
                + " order by line_no, id",
            declarationId)
        .stream()
        .map(this::lineRow)
        .toList();
  }

  /** 三单据（含被改版取代的历史版本）—— 退单时要举证「上一版报的是什么」靠的就是它。 */
  public List<CustomsDocumentRow> documents(UUID declarationId) {
    return sql
        .fetch(
            "select * from customs_document where declaration_id=?"
                + " order by document_type, document_version desc",
            declarationId)
        .stream()
        .map(this::documentRow)
        .toList();
  }

  public List<CustomsSubmissionRow> submissions(UUID declarationId) {
    return sql
        .fetch(
            "select * from customs_submission where declaration_id=? order by submitted_at desc, id",
            declarationId)
        .stream()
        .map(this::submissionRow)
        .toList();
  }

  /** 海关结果回执：放行 / 退单 / 改单逐条留痕，按时间倒序（最近一次在最前）。 */
  public List<CustomsResultRow> results(UUID declarationId) {
    return sql
        .fetch(
            "select * from customs_result where declaration_id=? order by result_at desc, id",
            declarationId)
        .stream()
        .map(this::resultRow)
        .toList();
  }

  public List<CustomsEventRow> events(UUID declarationId) {
    return sql
        .fetch(
            "select * from customs_declaration_event where declaration_id=?"
                + " order by occurred_at desc, id",
            declarationId)
        .stream()
        .map(
            r ->
                new CustomsEventRow(
                    r.get("id", UUID.class),
                    r.get("kind", String.class),
                    r.get("from_status", String.class),
                    r.get("to_status", String.class),
                    r.get("actor_id", UUID.class),
                    r.get("reason", String.class),
                    r.get("notes", String.class),
                    r.get("occurred_at", OffsetDateTime.class)))
        .toList();
  }

  // ================================================================ 写动作

  /**
   * 建单（草稿）。来源固定是 10.4.1.1 头程发运单。
   *
   * <p>草稿不动任何账：申报资料还没人看过，更没送到报关行。
   */
  @Transactional
  public CustomsSaved create(UUID tenantId, CustomsCreateRequest request) {
    if (request.sourceShipmentId() == null) {
      throw new IllegalArgumentException("必须给出 sourceShipmentId：报关单按头程发运单生成，不能凭空建");
    }
    Record shipment =
        sql.fetchOne(
            "select * from first_mile_shipment where tenant_id=? and id=?",
            tenantId,
            request.sourceShipmentId());
    if (shipment == null) {
      throw new IllegalArgumentException("来源头程发运单不存在或无权访问：" + request.sourceShipmentId());
    }
    if ("CANCELLED".equals(shipment.get("status", String.class))) {
      throw new IllegalArgumentException("来源头程发运单已取消，不能对它报关");
    }
    if (request.lines() == null || request.lines().isEmpty()) {
      throw new IllegalArgumentException("报关单至少要有一条明细：没有货物就没有申报");
    }

    TradeMode tradeMode = TradeMode.parse(request.tradeMode());
    DeclarationTransportMode mode = DeclarationTransportMode.parse(request.transportMode());
    String currency = money(request.declarationCurrency(), "申报币种");
    String base = moneyOrDefault(request.baseCurrency(), "CNY", "本位币种");
    BigDecimal rate = positive(request.exchangeRate(), "汇率");
    int scale = request.exchangeRateScale() == null ? 6 : request.exchangeRateScale();
    if (scale < 0 || scale > 10) {
      throw new IllegalArgumentException("汇率精度刻度必须在 0–10 之间，收到 " + scale);
    }
    LocalDate rateDate =
        request.exchangeRateDate() == null ? LocalDate.now() : request.exchangeRateDate();
    requireText(request.exporterName(), "出口方名称");
    requireText(request.consigneeName(), "收货方名称");
    requireCountry(request.consigneeCountryCode(), "收货方国家/地区");
    requireCountry(request.destinationCountryCode(), "目的国/地区");

    UUID id = UUID.randomUUID();
    long seq = sql.fetchOne("select nextval('customs_declaration_code_seq')").get(0, Long.class);
    LocalDate today = sql.fetchOne("select current_date").get(0, LocalDate.class);
    String code = "CD-" + today.toString().replace("-", "") + "-" + String.format("%06d", seq);

    // 明细行触发器 guard_customs_line_source 要查单头，所以**必须先建头再写明细**。
    // 汇总值先落 0，写完明细再回填 —— 折算额的自洽由 DB 延迟约束在提交时核对。
    sql.execute(
        "insert into customs_declaration(id,tenant_id,declaration_code,source_shipment_id,"
            + "source_shipment_code,legal_entity_id,trade_mode,exporter_name,exporter_country_code,"
            + "consignee_name,consignee_country_code,ior_name,ior_country_code,broker_name,broker_code,"
            + "broker_contact,destination_country_code,destination_customs_district,departure_port,"
            + "destination_port,transport_mode,bill_of_lading_no,declaration_currency,declaration_value,"
            + "base_currency,exchange_rate,exchange_rate_scale,exchange_rate_date,declaration_value_base,"
            + "total_line_count,notes,created_by)"
            + " values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0,?,?,?,?,0,0,?,?)",
        id,
        tenantId,
        code,
        request.sourceShipmentId(),
        fit(shipment.get("shipment_code", String.class), 64, "来源单号快照"),
        request.legalEntityId(),
        tradeMode.name(),
        fit(requireText(request.exporterName(), "出口方名称"), 200, "出口方名称"),
        countryOrDefault(request.exporterCountryCode(), "出口方国家/地区"),
        fit(requireText(request.consigneeName(), "收货方名称"), 200, "收货方名称"),
        requireCountry(request.consigneeCountryCode(), "收货方国家/地区"),
        fit(request.iorName(), 200, "IOR 名称"),
        fit(request.iorCountryCode(), 2, "IOR 国家/地区"),
        fit(request.brokerName(), 200, "报关行名称"),
        fit(request.brokerCode(), 64, "报关行代码"),
        fit(request.brokerContact(), 100, "报关行联系人"),
        requireCountry(request.destinationCountryCode(), "目的国/地区"),
        fit(request.destinationCustomsDistrict(), 100, "目的关区"),
        fit(request.departurePort(), 100, "启运港"),
        fit(request.destinationPort(), 100, "目的港"),
        mode.name(),
        fit(request.billOfLadingNo(), 64, "提单号"),
        currency,
        base,
        rate,
        scale,
        rateDate,
        fit(request.notes(), 2000, "备注"),
        currentActor());

    writeLines(id, tenantId, request.lines());

    // 单头申报货值 = 明细货值合计（DB 延迟约束在提交时再校验一次，这里先算对）
    Record sum =
        sql.fetchOne(
            "select coalesce(sum(declared_value),0) v from customs_declaration_line where declaration_id=?",
            id);
    BigDecimal total = sum.get("v", BigDecimal.class);
    sql.execute(
        "update customs_declaration set declaration_value=?,declaration_value_base=?,"
            + "total_line_count=?,updated_at=now() where id=?",
        total,
        total.multiply(rate).setScale(4, RoundingMode.HALF_UP),
        request.lines().size(),
        id);

    recordEvent(id, "CREATED", "", "DRAFT", "", request.notes());
    return saved(id, "报关单已创建为草稿。提交报关行前必须走人工复核。");
  }

  /** 改单：仅草稿与复核退回可改。已复核之后一行都不让改，否则「复核」就变成走过场。 */
  @Transactional
  public CustomsSaved update(UUID id, CustomsUpdateRequest request) {
    Record head = head(id);
    String status = head.get("status", String.class);
    DeclarationStatus current = DeclarationStatus.parse(status);
    // 撤回复核后必须能改：withdrawReview 把单子退回「待复核」就是为了让人接着改，
    // 这里若不放行 PENDING_REVIEW，撤回复核就成了一条死路 —— 退回后既不能改也不能复核。
    // 改完仍需重新复核才能提交（submit 只认「已复核」），所以放宽不会绕过人工复核闸。
    if (current != DeclarationStatus.DRAFT
        && current != DeclarationStatus.PENDING_REVIEW
        && current != DeclarationStatus.REJECTED) {
      throw new IllegalArgumentException(
          "报关单处于「" + current.label() + "」状态，不允许改单：要让改动被看到，请先撤回复核或等海关退单后退回草稿再改");
    }
    if (request.expectedVersion() != null
        && request.expectedVersion() != head.get("version", Long.class)) {
      throw new IllegalStateException(
          "报关单已被他人修改（期望版本 "
              + request.expectedVersion()
              + "，当前版本 "
              + head.get("version", Long.class)
              + "），请刷新后重试");
    }

    TradeMode tradeMode = TradeMode.parse(request.tradeMode());
    DeclarationTransportMode mode = DeclarationTransportMode.parse(request.transportMode());
    String currency = money(request.declarationCurrency(), "申报币种");
    String base = moneyOrDefault(request.baseCurrency(), "CNY", "本位币种");
    BigDecimal rate = positive(request.exchangeRate(), "汇率");
    int scale = request.exchangeRateScale() == null ? 6 : request.exchangeRateScale();
    if (scale < 0 || scale > 10) {
      throw new IllegalArgumentException("汇率精度刻度必须在 0–10 之间，收到 " + scale);
    }
    LocalDate rateDate =
        request.exchangeRateDate() == null ? LocalDate.now() : request.exchangeRateDate();
    requireText(request.exporterName(), "出口方名称");
    requireText(request.consigneeName(), "收货方名称");
    requireCountry(request.consigneeCountryCode(), "收货方国家/地区");
    requireCountry(request.destinationCountryCode(), "目的国/地区");

    // 整份替换明细：改单不是增量补丁，避免「删了一行」在申报资料里说不清
    sql.execute("delete from customs_declaration_line where declaration_id=?", id);
    writeLines(id, head.get("tenant_id", UUID.class), request.lines());
    Record sum =
        sql.fetchOne(
            "select coalesce(sum(declared_value),0) v from customs_declaration_line where declaration_id=?",
            id);
    BigDecimal total = sum.get("v", BigDecimal.class);

    sql.execute(
        "update customs_declaration set trade_mode=?,exporter_name=?,exporter_country_code=?,"
            + "consignee_name=?,consignee_country_code=?,ior_name=?,ior_country_code=?,broker_name=?,"
            + "broker_code=?,broker_contact=?,destination_country_code=?,destination_customs_district=?,"
            + "departure_port=?,destination_port=?,transport_mode=?,bill_of_lading_no=?,"
            + "declaration_currency=?,declaration_value=?,base_currency=?,exchange_rate=?,"
            + "exchange_rate_scale=?,exchange_rate_date=?,declaration_value_base=?,total_line_count=?,"
            + "notes=?,version=version+1,updated_at=now() where id=?",
        tradeMode.name(),
        fit(requireText(request.exporterName(), "出口方名称"), 200, "出口方名称"),
        countryOrDefault(request.exporterCountryCode(), "出口方国家/地区"),
        fit(requireText(request.consigneeName(), "收货方名称"), 200, "收货方名称"),
        requireCountry(request.consigneeCountryCode(), "收货方国家/地区"),
        fit(request.iorName(), 200, "IOR 名称"),
        fit(request.iorCountryCode(), 2, "IOR 国家/地区"),
        fit(request.brokerName(), 200, "报关行名称"),
        fit(request.brokerCode(), 64, "报关行代码"),
        fit(request.brokerContact(), 100, "报关行联系人"),
        requireCountry(request.destinationCountryCode(), "目的国/地区"),
        fit(request.destinationCustomsDistrict(), 100, "目的关区"),
        fit(request.departurePort(), 100, "启运港"),
        fit(request.destinationPort(), 100, "目的港"),
        mode.name(),
        fit(request.billOfLadingNo(), 64, "提单号"),
        currency,
        total,
        base,
        rate,
        scale,
        rateDate,
        total.multiply(rate).setScale(4, RoundingMode.HALF_UP),
        request.lines() == null ? 0 : request.lines().size(),
        fit(request.notes(), 2000, "备注"),
        id);

    recordEvent(id, "UPDATED", status, status, "", request.notes());
    return saved(id, "报关单已更新（草稿）。提交报关行前必须走人工复核。");
  }

  /** 送人工复核：草稿 / 复核退回 → 待复核。 */
  @Transactional
  public CustomsSaved submitForReview(UUID id, CustomsSubmitForReviewRequest request) {
    return transition(
        id,
        List.of(DeclarationStatus.DRAFT, DeclarationStatus.REJECTED),
        DeclarationStatus.PENDING_REVIEW,
        "PENDING_REVIEW",
        "",
        request == null ? "" : request.notes(),
        "已送人工复核。复核通过后才能提交报关行。");
  }

  /**
   * 人工复核通过：待复核 → 已复核。
   *
   * <p>DB 触发器 {@code customs_2_review_completeness} 会逐行检查申报资料齐备性， 缺哪几项会一次全报出来（而不是让运营反复提交试错）。
   */
  @Transactional
  public CustomsSaved review(UUID id, CustomsReviewRequest request) {
    return transition(
        id,
        List.of(DeclarationStatus.PENDING_REVIEW),
        DeclarationStatus.REVIEWED,
        "REVIEWED",
        "",
        request == null ? "" : request.reviewNotes(),
        "复核通过：现在可以生成并定稿三单据，然后提交报关行。");
  }

  /** 复核退回：待复核 → 复核退回。必须给原因。改完必须重新走复核才能再次提交。 */
  @Transactional
  public CustomsSaved rejectReview(UUID id, CustomsRejectReviewRequest request) {
    String reason = requireText(request == null ? null : request.reason(), "复核退回原因");
    return transition(
        id,
        List.of(DeclarationStatus.PENDING_REVIEW),
        DeclarationStatus.REJECTED,
        "REVIEW_REJECTED",
        reason,
        "",
        "复核已退回。改完必须重新送一次复核才能提交报关行 —— 改完的数据没人看过就不该再出去一次。");
  }

  /** 撤回复核：已复核 → 待复核。撤回复核后明细才允许再改。 */
  @Transactional
  public CustomsSaved withdrawReview(UUID id, CustomsWithdrawReviewRequest request) {
    String reason = requireText(request == null ? null : request.reason(), "撤回复核原因");
    return transition(
        id,
        List.of(DeclarationStatus.REVIEWED),
        DeclarationStatus.PENDING_REVIEW,
        "REVIEW_WITHDRAWN",
        reason,
        "",
        "已撤回复核，现在可以改明细。改完要重新走一次复核才能提交报关行。");
  }

  /**
   * 生成三单据之一：按类型递增版本链，旧版本置为 SUPERSEDED（**不覆盖**）。
   *
   * <p>content 是<b>生成那一刻的单据内容快照</b>：申报资料必须可复现，不能读取时按当前明细 重新拼一遍 —— 那样明细一改，历史单据就跟着变了，被退单时无法举证当时提交的内容。
   */
  @Transactional
  public CustomsSaved generateDocument(UUID id, CustomsGenerateDocumentRequest request) {
    if (request == null || request.documentType() == null) {
      throw new IllegalArgumentException("必须给出 documentType：报关单/装箱单/商业发票三选一");
    }
    CustomsDocumentType type = CustomsDocumentType.parse(request.documentType());
    Record head = head(id);
    String status = head.get("status", String.class);
    DeclarationStatus current = DeclarationStatus.parse(status);
    if (current == DeclarationStatus.DRAFT
        || current == DeclarationStatus.PENDING_REVIEW
        || current == DeclarationStatus.REJECTED) {
      throw new IllegalArgumentException(
          "报关单还处于「" + current.label() + "」状态，不允许生成正式单据：三单据是提交报关行时递交的申报资料，" + "必须先完成人工复核");
    }

    UUID tenantId = head.get("tenant_id", UUID.class);
    List<CustomsLineRow> lines = lines(id);

    Record prev =
        sql.fetchOne(
            "select * from customs_document where declaration_id=? and document_type=?"
                + " order by document_version desc limit 1",
            id,
            type.name());

    int nextVersion = prev == null ? 1 : prev.get("document_version", Integer.class) + 1;
    UUID docId = UUID.randomUUID();

    if (prev != null) {
      sql.execute(
          "update customs_document set status='SUPERSEDED' where id=?", prev.get("id", UUID.class));
    }

    String currency = head.get("declaration_currency", String.class);
    // 单据编号必须挂在**本单**的报关单号下。取别的报关单的号来编本单的单据号，
    // 会让退单举证时单据号与报关单号对不上。
    String declarationCode = head.get("declaration_code", String.class);
    BigDecimal total = value(lines, type, head);
    String docNo =
        request.documentNo() == null || request.documentNo().isBlank()
            ? declarationCode + "-" + type.name() + "-v" + nextVersion
            : fit(request.documentNo(), 64, "单据号");

    sql.execute(
        "insert into customs_document(id,tenant_id,declaration_id,document_type,document_no,"
            + "document_version,currency,total_amount,content,status,supersedes_document_id,"
            + "issued_at,prepared_by,source_system,source_reference)"
            + " values(?,?,?,?,?,?,?,?,?::jsonb,'DRAFT',?,now(),?,?,?)",
        docId,
        tenantId,
        id,
        type.name(),
        docNo,
        nextVersion,
        currency,
        total,
        toJson(content(type, head, lines, currency, total), "单据内容"),
        prev == null ? null : prev.get("id", UUID.class),
        currentActor(),
        "CUSTOMS_DECLARATION",
        declarationCode + ":" + type.name() + ":v" + nextVersion);

    recordEvent(id, "DOCUMENT_GENERATED", status, status, "", type.label() + " v" + nextVersion);
    return saved(id, type.label() + " v" + nextVersion + " 已生成（草稿）。定稿后才能随报关单一起提交报关行。");
  }

  /** 定稿单据：定稿后才允许提交报关行（DB 层提交前会校验三份最新版本都是 FINAL）。 */
  @Transactional
  public CustomsSaved finalizeDocument(UUID documentId, CustomsFinalizeDocumentRequest request) {
    Record doc = sql.fetchOne("select * from customs_document where id=?", documentId);
    if (doc == null) {
      throw new ResponseStatusException(
          org.springframework.http.HttpStatus.NOT_FOUND, "单据不存在或无权访问");
    }
    sql.execute(
        "update customs_document set status='FINAL',issued_at=now(),updated_at=now()"
            + " where id=? and status<>'SUPERSEDED'",
        documentId);
    return saved(doc.get("declaration_id", UUID.class), "单据已定稿。报关单 / 装箱单 / 商业发票三份都定稿后即可提交报关行。");
  }

  /**
   * 提交报关行：已复核 → 已提交。
   *
   * <p>这是本模块风险最高的动作，所以有三道闸（DB 层已钉死，服务层先讲成人话）： 必须先完成人工复核、三单据齐备且最新版本都已定稿、商业发票币种与单头申报币种一致。
   */
  @Transactional
  public CustomsSaved submit(UUID id, CustomsSubmitRequest request) {
    Record head = head(id);
    DeclarationStatus current = DeclarationStatus.parse(head.get("status", String.class));
    if (current != DeclarationStatus.REVIEWED) {
      throw new IllegalArgumentException(
          current == DeclarationStatus.DRAFT
              ? "提交报关行前必须人工复核：当前状态是「草稿」。报关数据错误导致的扣货与罚款由卖家承担，" + "所以必须先走复核"
              : "提交报关行前必须人工复核：当前状态是「" + current.label() + "」，只允许「已复核」提交。");
    }
    CustomsSubmissionResult result =
        request == null || request.submissionResult() == null
            ? CustomsSubmissionResult.ACCEPTED
            : CustomsSubmissionResult.parse(request.submissionResult());
    String reference =
        fit(
            requireText(request == null ? null : request.submissionReference(), "提交回执号"),
            64,
            "提交回执号");

    int round = Math.max(1, head.get("review_round", Integer.class));
    OffsetDateTime now = OffsetDateTime.now();

    UUID submissionId = UUID.randomUUID();
    sql.execute(
        "insert into customs_submission(id,tenant_id,declaration_id,submission_round,document_id,"
            + "submission_reference,broker_name,submission_result,submitted_at,submitted_by,"
            + "source_system,source_reference,result_reason)"
            + " values(?,?,?,?,null,?,?,?,?::timestamptz,?,?,?,?)",
        submissionId,
        head.get("tenant_id", UUID.class),
        id,
        round,
        reference,
        fit(
            request.brokerName() == null
                ? head.get("broker_name", String.class)
                : request.brokerName(),
            200,
            "报关行名称"),
        result.name(),
        now,
        currentActor(),
        SOURCE_MANUAL,
        reference,
        fit(request.resultReason(), 2000, "结果说明"));

    sql.execute(
        "update customs_declaration set status='SUBMITTED',submitted_at=?::timestamptz,submitted_by=?,"
            + "submission_reference=?,version=version+1,updated_at=now() where id=?",
        now,
        currentActor(),
        reference,
        id);

    recordEvent(id, "SUBMITTED", "REVIEWED", "SUBMITTED", "", reference);
    return saved(id, "已提交报关行（第 " + round + " 轮）。等待海关回执。");
  }

  /**
   * 登记海关结果：放行 / 退单 / 改单。
   *
   * <p>三种结果都落 {@code customs_result} 审计（谁、何时、原结果、新结果、原因）， 且必须挂在**已经提交过**的那一轮上 ——
   * 否则会出现「没提交过却有海关回执」这种不可能的记录。
   */
  @Transactional
  public CustomsSaved recordResult(UUID id, CustomsResultRequest request) {
    if (request == null || request.resultType() == null) {
      throw new IllegalArgumentException("必须给出 resultType：放行 / 退单 / 改单三选一");
    }
    CustomsResultType type = CustomsResultType.parse(request.resultType());
    String reason = requireText(request.reason(), "海关结果原因");
    OffsetDateTime at = request.resultAt() == null ? OffsetDateTime.now() : request.resultAt();

    Record head = head(id);
    DeclarationStatus current = DeclarationStatus.parse(head.get("status", String.class));
    if (current != DeclarationStatus.SUBMITTED
        && current != DeclarationStatus.AMENDED
        && !(type == CustomsResultType.AMENDED && current == DeclarationStatus.CLEARED)) {
      throw new IllegalArgumentException("当前状态「" + current.label() + "」不处于已提交，不能登记海关回执");
    }

    Record submission =
        sql.fetchOne(
            "select * from customs_submission where declaration_id=?"
                + " order by submitted_at desc limit 1",
            id);
    if (submission == null) {
      throw new IllegalStateException("这张报关单还没有任何提交记录，不能登记海关回执");
    }

    // from_result/to_result 记的是**海关回执口径**，不是报关单内部状态机口径：
    // 两套枚举不是同一套名字（单头有 SUBMITTED/CUSTOMS_REJECTED，回执侧是 PENDING/REJECTED）。
    // 直接把单头状态写进去会撞 CHECK 约束，而且事后翻回执也读不懂。
    String from = resultVocabulary(current);
    DeclarationStatus to =
        switch (type) {
          case CLEARED -> DeclarationStatus.CLEARED;
          case REJECTED -> DeclarationStatus.CUSTOMS_REJECTED;
          case AMENDED -> DeclarationStatus.AMENDED;
        };

    sql.execute(
        "insert into customs_result(id,tenant_id,declaration_id,submission_id,result_type,"
            + "from_result,to_result,result_reference,reason,result_at,actor_id,source_system,"
            + "source_reference) values(?,?,?,?,?,?,?,?,?,?::timestamptz,?,?,?)",
        UUID.randomUUID(),
        head.get("tenant_id", UUID.class),
        id,
        submission.get("id", UUID.class),
        type.name(),
        from,
        resultVocabulary(to),
        fit(request.resultReference(), 64, "海关单号"),
        reason,
        at,
        currentActor(),
        request.sourceSystem() == null || request.sourceSystem().isBlank()
            ? SOURCE_MANUAL
            : request.sourceSystem().trim(),
        requireText(request.sourceReference(), "结果来源单号"));

    if (type == CustomsResultType.CLEARED) {
      sql.execute(
          "update customs_declaration set status='CLEARED',cleared_at=?::timestamptz,"
              + "customs_release_no=?,amendment_count=?,version=version+1,updated_at=now() where id=?",
          at,
          fit(request.resultReference(), 64, "放行通知书号"),
          amendmentCount(id),
          id);
      recordEvent(id, "CLEARED", from, "CLEARED", reason, request.resultReference());
      return saved(id, "已登记海关放行。放行是终态，要修正已放行的申报资料请走改单。");
    }

    if (type == CustomsResultType.REJECTED) {
      sql.execute(
          "update customs_declaration set status='CUSTOMS_REJECTED',customs_rejected_at=?::timestamptz,"
              + "customs_rejection_reason=?,amendment_count=?,version=version+1,updated_at=now() where id=?",
          at,
          reason,
          amendmentCount(id),
          id);
      recordEvent(
          id, "CUSTOMS_REJECTED", from, "CUSTOMS_REJECTED", reason, request.resultReference());
      return saved(id, "已登记海关退单。请按退单原因改单或退回草稿重改 —— 退单原因没看就先把内容改了" + "是真实且常见的操作事故。");
    }

    // 改单：状态退回草稿，改完必须重新走复核才能再次提交
    sql.execute(
        "update customs_declaration set status='DRAFT',review_round=0,reviewed_at=null,reviewed_by=null,"
            + "amendment_count=?,last_amended_at=?::timestamptz,version=version+1,updated_at=now() where id=?",
        amendmentCount(id),
        at,
        id);
    recordEvent(id, "AMENDED", from, "DRAFT", reason, request.resultReference());
    return saved(id, "已登记海关改单并退回草稿。改完必须重新走人工复核才能再次提交报关行。");
  }

  /** 改单：已提交 / 已改单 / 已放行 → 已改单。必须给原因。 */
  @Transactional
  public CustomsSaved amend(UUID id, CustomsAmendRequest request) {
    String reason = requireText(request == null ? null : request.reason(), "改单原因");
    return transition(
        id,
        List.of(DeclarationStatus.SUBMITTED, DeclarationStatus.AMENDED, DeclarationStatus.CLEARED),
        DeclarationStatus.AMENDED,
        "AMENDED",
        reason,
        "",
        "已登记改单。改单后请重新生成三单据并重新走复核，才能再次提交报关行。");
  }

  /** 取消：必须给原因。报关资料已生成过，取消后无法追责。 */
  @Transactional
  public CustomsSaved cancel(UUID id, CustomsCancelRequest request) {
    String reason = requireText(request == null ? null : request.reason(), "取消原因");
    Record head = head(id);
    String from = head.get("status", String.class);
    OffsetDateTime now = OffsetDateTime.now();
    sql.execute(
        "update customs_declaration set status='CANCELLED',cancelled_at=?::timestamptz,"
            + "cancelled_by=?,cancel_reason=?,version=version+1,updated_at=now() where id=?",
        now,
        currentActor(),
        fit(reason, 200, "取消原因"),
        id);
    recordEvent(id, "CANCELLED", from, "CANCELLED", reason, "");
    return saved(id, "报关单已取消。");
  }

  /** 通用状态流转：先校验合法转换，再写状态/时间戳并落事件。 */
  private CustomsSaved transition(
      UUID id,
      List<DeclarationStatus> allowedFrom,
      DeclarationStatus to,
      String eventKind,
      String reason,
      String notes,
      String message) {
    Record head = head(id);
    DeclarationStatus from = DeclarationStatus.parse(head.get("status", String.class));
    if (!allowedFrom.contains(from)) {
      throw new IllegalArgumentException(
          "报关单处于「"
              + from.label()
              + "」状态，不允许此操作。合法的前置状态："
              + String.join("、", allowedFrom.stream().map(s -> s.label()).toList()));
    }
    if (!DeclarationStatus.allowedNext(from).contains(to)) {
      throw new IllegalArgumentException("不允许的状态跳转：「" + from.label() + "」→「" + to.label() + "」");
    }

    OffsetDateTime now = OffsetDateTime.now();
    switch (to) {
      case REVIEWED ->
          sql.execute(
              "update customs_declaration set status='REVIEWED',review_round=review_round+1,"
                  + "reviewed_at=?::timestamptz,reviewed_by=?,review_notes=?,"
                  + "version=version+1,updated_at=now() where id=?",
              now,
              currentActor(),
              fit(notes, 2000, "复核意见"),
              id);
        // 复核退回同样**走完过一轮复核**：有人真的看过这张单才退得回去。
        // 不记轮次就会在库里留下「轮次 0 却已复核」的自相矛盾凭据，
        // 而罚款追责时要被问的正是「这张单到底谁看过」。
      case REJECTED ->
          sql.execute(
              "update customs_declaration set status='REJECTED',review_round=review_round+1,"
                  + "reviewed_at=?::timestamptz,reviewed_by=?,review_notes=?,"
                  + "version=version+1,updated_at=now() where id=?",
              now,
              currentActor(),
              fit(notes, 2000, "复核意见"),
              id);
      case CANCELLED ->
          sql.execute(
              "update customs_declaration set status='CANCELLED',cancelled_at=?::timestamptz,"
                  + "cancelled_by=?,cancel_reason=?,version=version+1,updated_at=now() where id=?",
              now,
              currentActor(),
              fit(reason, 200, "取消原因"),
              id);
      case AMENDED ->
          sql.execute(
              "update customs_declaration set status='AMENDED',amendment_count=?,"
                  + "last_amended_at=?::timestamptz,version=version+1,updated_at=now() where id=?",
              amendmentCount(id),
              now,
              id);
      default ->
          sql.execute(
              "update customs_declaration set status=?,version=version+1,updated_at=now() where id=?",
              to.name(),
              id);
    }
    recordEvent(id, eventKind, from.name(), to.name(), reason, notes);
    return saved(id, message);
  }

  // ================================================================ 内部

  /** 明细整份写入：从发运明细行取 SKU 与申报基准，不允许凭空造一个发运单上不存在的 SKU。 */
  private void writeLines(UUID declarationId, UUID tenantId, List<CustomsLineRequest> requests) {
    if (requests == null || requests.isEmpty()) {
      return;
    }
    int lineNo = 0;
    for (CustomsLineRequest line : requests) {
      lineNo++;
      if (line.sourceLineId() == null) {
        throw new IllegalArgumentException("第 " + lineNo + " 行必须给出 sourceLineId：申报的这一行必须来自那张头程发运单");
      }
      Record src =
          sql.fetchOne(
              "select * from first_mile_shipment_line where tenant_id=? and id=?",
              tenantId,
              line.sourceLineId());
      if (src == null) {
        throw new IllegalArgumentException(
            "第 " + lineNo + " 行引用的发运明细不存在或不属于本租户：" + line.sourceLineId());
      }
      BigDecimal quantity = positive(line.quantity(), "申报数量");
      String uom = line.uom() == null || line.uom().isBlank() ? "PCS" : line.uom().trim();
      BigDecimal declaredValue =
          line.declaredValue() == null
              ? BigDecimal.ZERO
              : nonNegative(line.declaredValue(), "申报货值");
      DeclarationElementStatus elementStatus =
          line.declarationElementStatus() == null
              ? DeclarationElementStatus.MANUAL_PENDING
              : DeclarationElementStatus.parse(line.declarationElementStatus());
      boolean fromModule = elementStatus.name().startsWith("MODULE_3_7_1_1");

      sql.execute(
          "insert into customs_declaration_line(id,tenant_id,declaration_id,line_no,sku_id,sku_code,"
              + "sku_name,source_line_id,source_line_no,declared_item_name,hs_code,hs_code_source,"
              + "hs_code_verified,declaration_elements,declaration_element_status,"
              + "declaration_elements_source,declaration_elements_ref,spec_material,usage,brand,model_no,"
              + "origin_country_code,quantity,uom,declared_unit_value,declared_value,gross_weight_kg,"
              + "net_weight_kg,volume_cbm,package_count)"
              + " values(?,?,?,?,?,?,?,?,?,?,?,?,false,?::jsonb,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
          UUID.randomUUID(),
          tenantId,
          declarationId,
          lineNo,
          src.get("sku_id", UUID.class),
          skuCode(src.get("sku_id", UUID.class), tenantId),
          skuName(src.get("sku_id", UUID.class), tenantId),
          line.sourceLineId(),
          src.get("line_no", Integer.class),
          fit(requireText(line.declaredItemName(), "申报品名"), 200, "申报品名"),
          fit(line.hsCode(), 32, "HS 编码"),
          // 3.7.1.1 未交付，所以没有任何一行能说「这个码来自字典」——全部如实记为人工填。
          "MANUAL",
          elementsJson(line.declarationElements()),
          elementStatus.name(),
          fromModule ? "MODULE_3_7_1_1" : "MANUAL",
          "",
          fit(line.specMaterial(), 200, "规格材质"),
          fit(line.usage(), 100, "用途"),
          fit(line.brand(), 100, "品牌"),
          fit(line.modelNo(), 100, "型号"),
          requireCountry(line.originCountryCode(), "原产国"),
          quantity,
          fit(uom, 8, "计量单位"),
          line.declaredUnitValue() == null
              ? BigDecimal.ZERO
              : nonNegative(line.declaredUnitValue(), "申报单价"),
          declaredValue,
          line.grossWeightKg() == null ? BigDecimal.ZERO : nonNegative(line.grossWeightKg(), "毛重"),
          line.netWeightKg() == null ? BigDecimal.ZERO : nonNegative(line.netWeightKg(), "净重"),
          line.volumeCbm() == null ? BigDecimal.ZERO : nonNegative(line.volumeCbm(), "体积"),
          line.packageCount() == null ? 0 : line.packageCount());
    }
  }

  /** 改单次数来自结果回执条数，不是页面上手填的数字（DB 延迟约束也会再钉一次）。 */
  private int amendmentCount(UUID declarationId) {
    return sql.fetchOne(
            "select count(*) from customs_result where declaration_id=? and result_type='AMENDED'",
            declarationId)
        .get(0, Integer.class);
  }

  /** 三单据各自的口径：报关单/发票按申报货值，装箱单按箱数与体积。 */
  private BigDecimal value(List<CustomsLineRow> lines, CustomsDocumentType type, Record head) {
    if (type == CustomsDocumentType.PACKING_LIST) {
      return BigDecimal.valueOf(
          lines.stream().mapToInt(l -> l.packageCount() == null ? 0 : l.packageCount()).sum());
    }
    return lines.stream()
        .map(l -> l.declaredValue() == null ? BigDecimal.ZERO : l.declaredValue())
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  /**
   * 单据内容快照。**在这里固化**而不是读取时重算 —— 申报资料必须可复现、可举证。
   *
   * <p>刻意不写死申报要素的键名：申报要素随类目与目的国变化，写死等于把上游字典抄成第二份 且必然漂移，所以这里把 3.7.1.1 回填进来的 jsonb 原样带进单据。
   */
  private Map<String, Object> content(
      CustomsDocumentType type,
      Record head,
      List<CustomsLineRow> lines,
      String currency,
      BigDecimal total) {
    Map<String, Object> content = new LinkedHashMap<>();
    content.put("documentType", type.name());
    content.put("declarationCode", head.get("declaration_code", String.class));
    content.put("sourceShipmentCode", head.get("source_shipment_code", String.class));
    content.put("tradeMode", head.get("trade_mode", String.class));
    content.put("exporterName", head.get("exporter_name", String.class));
    content.put("consigneeName", head.get("consignee_name", String.class));
    content.put("consigneeCountryCode", head.get("consignee_country_code", String.class));
    content.put("iorName", head.get("ior_name", String.class));
    content.put("destinationCountryCode", head.get("destination_country_code", String.class));
    content.put(
        "destinationCustomsDistrict", head.get("destination_customs_district", String.class));
    content.put("transportMode", head.get("transport_mode", String.class));
    content.put("billOfLadingNo", head.get("bill_of_lading_no", String.class));
    content.put("currency", currency);
    content.put("totalAmount", total);
    // 汇率快照随单据一起固化：事后举证时要能回答「当时按哪个汇率申报的」。
    content.put("exchangeRate", head.get("exchange_rate", BigDecimal.class));
    content.put("exchangeRateScale", head.get("exchange_rate_scale", Integer.class));
    content.put("exchangeRateDate", head.get("exchange_rate_date", LocalDate.class));
    content.put("declarationValueBase", head.get("declaration_value_base", BigDecimal.class));
    content.put("baseCurrency", head.get("base_currency", String.class));

    List<Map<String, Object>> lineMaps = new ArrayList<>();
    for (CustomsLineRow l : lines) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("lineNo", l.lineNo());
      m.put("skuCode", l.skuCode());
      m.put("skuName", l.skuName());
      m.put("declaredItemName", l.declaredItemName());
      m.put("hsCode", l.hsCode());
      m.put("hsCodeSource", l.hsCodeSource());
      m.put("hsCodeVerified", l.hsCodeVerified());
      // 原样带出，不在这里展开键名 —— 键名归 3.7.1.1 的字典，本模块不复制第二份。
      m.put("declarationElements", l.declarationElements());
      m.put("declarationElementStatus", l.declarationElementStatus());
      m.put("specMaterial", l.specMaterial());
      m.put("usage", l.usage());
      m.put("brand", l.brand());
      m.put("modelNo", l.modelNo());
      m.put("originCountryCode", l.originCountryCode());
      m.put("quantity", l.quantity());
      m.put("uom", l.uom());
      m.put("declaredUnitValue", l.declaredUnitValue());
      m.put("declaredValue", l.declaredValue());
      m.put("grossWeightKg", l.grossWeightKg());
      m.put("netWeightKg", l.netWeightKg());
      m.put("volumeCbm", l.volumeCbm());
      m.put("packageCount", l.packageCount());
      lineMaps.add(m);
    }
    content.put("lines", lineMaps);
    return content;
  }

  // ================================================================ 取值与校验

  private Record head(UUID id) {
    Record r = sql.fetchOne("select * from customs_declaration where id=?", id);
    if (r == null) {
      throw new ResponseStatusException(
          org.springframework.http.HttpStatus.NOT_FOUND, "报关单不存在或无权访问");
    }
    return r;
  }

  private CustomsSaved saved(UUID id, String message) {
    Record r = head(id);
    String status = r.get("status", String.class);
    return new CustomsSaved(
        id,
        r.get("declaration_code", String.class),
        status,
        DeclarationStatus.parse(status).label(),
        r.get("version", Long.class),
        message);
  }

  private CustomsDeclarationRow row(Record r) {
    String tradeMode = r.get("trade_mode", String.class);
    String status = r.get("status", String.class);
    return new CustomsDeclarationRow(
        r.get("id", UUID.class),
        r.get("declaration_code", String.class),
        r.get("source_shipment_id", UUID.class),
        r.get("source_shipment_code", String.class),
        tradeMode,
        labelOf(TradeMode.values(), tradeMode),
        r.get("exporter_name", String.class),
        r.get("consignee_name", String.class),
        r.get("consignee_country_code", String.class),
        r.get("destination_country_code", String.class),
        r.get("transport_mode", String.class),
        r.get("declaration_currency", String.class),
        r.get("declaration_value", BigDecimal.class),
        r.get("base_currency", String.class),
        r.get("exchange_rate", BigDecimal.class),
        r.get("exchange_rate_scale", Integer.class),
        r.get("exchange_rate_date", LocalDate.class),
        r.get("declaration_value_base", BigDecimal.class),
        r.get("total_line_count", Integer.class),
        r.get("review_round", Integer.class),
        status,
        DeclarationStatus.parse(status).label(),
        r.get("amendment_count", Integer.class),
        r.get("reviewed_at", OffsetDateTime.class),
        r.get("submitted_at", OffsetDateTime.class),
        r.get("cleared_at", OffsetDateTime.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class),
        r.get("version", Long.class));
  }

  private CustomsLineRow lineRow(Record r) {
    String elementStatus = r.get("declaration_element_status", String.class);
    return new CustomsLineRow(
        r.get("id", UUID.class),
        r.get("line_no", Integer.class),
        r.get("sku_id", UUID.class),
        r.get("sku_code", String.class),
        r.get("sku_name", String.class),
        r.get("source_line_id", UUID.class),
        r.get("source_line_no", Integer.class),
        r.get("declared_item_name", String.class),
        r.get("hs_code", String.class),
        r.get("hs_code_source", String.class),
        r.get("hs_code_verified", Boolean.class),
        mapOf(r.get("declaration_elements")),
        elementStatus,
        labelOf(DeclarationElementStatus.values(), elementStatus),
        r.get("declaration_elements_source", String.class),
        r.get("declaration_elements_ref", String.class),
        r.get("spec_material", String.class),
        r.get("usage", String.class),
        r.get("brand", String.class),
        r.get("model_no", String.class),
        r.get("origin_country_code", String.class),
        r.get("quantity", BigDecimal.class),
        r.get("uom", String.class),
        r.get("declared_unit_value", BigDecimal.class),
        r.get("declared_value", BigDecimal.class),
        r.get("gross_weight_kg", BigDecimal.class),
        r.get("net_weight_kg", BigDecimal.class),
        r.get("volume_cbm", BigDecimal.class),
        r.get("package_count", Integer.class));
  }

  private CustomsDocumentRow documentRow(Record r) {
    String type = r.get("document_type", String.class);
    return new CustomsDocumentRow(
        r.get("id", UUID.class),
        type,
        labelOf(CustomsDocumentType.values(), type),
        r.get("document_no", String.class),
        r.get("document_version", Integer.class),
        r.get("currency", String.class),
        r.get("total_amount", BigDecimal.class),
        mapOf(r.get("content")),
        r.get("status", String.class),
        r.get("supersedes_document_id", UUID.class),
        r.get("issued_at", OffsetDateTime.class),
        r.get("prepared_by", UUID.class),
        r.get("source_system", String.class),
        r.get("source_reference", String.class),
        r.get("created_at", OffsetDateTime.class));
  }

  private CustomsSubmissionRow submissionRow(Record r) {
    String result = r.get("submission_result", String.class);
    return new CustomsSubmissionRow(
        r.get("id", UUID.class),
        r.get("submission_round", Integer.class),
        r.get("document_id", UUID.class),
        r.get("submission_reference", String.class),
        r.get("broker_name", String.class),
        result,
        labelOf(CustomsSubmissionResult.values(), result),
        r.get("submitted_at", OffsetDateTime.class),
        r.get("submitted_by", UUID.class),
        r.get("source_system", String.class),
        r.get("source_reference", String.class),
        r.get("result_reason", String.class));
  }

  private CustomsResultRow resultRow(Record r) {
    String type = r.get("result_type", String.class);
    return new CustomsResultRow(
        r.get("id", UUID.class),
        r.get("submission_id", UUID.class),
        type,
        labelOf(CustomsResultType.values(), type),
        r.get("from_result", String.class),
        r.get("to_result", String.class),
        r.get("result_reference", String.class),
        r.get("reason", String.class),
        r.get("result_at", OffsetDateTime.class),
        r.get("actor_id", UUID.class),
        r.get("source_system", String.class),
        r.get("source_reference", String.class));
  }

  private void recordEvent(
      UUID declarationId,
      String kind,
      String fromStatus,
      String toStatus,
      String reason,
      String notes) {
    sql.execute(
        "insert into customs_declaration_event(id,tenant_id,declaration_id,kind,occurred_at,"
            + "from_status,to_status,actor_id,reason,notes)"
            + " select ?,tenant_id,?,?,?::timestamptz,?,?,?,?,? from customs_declaration where id=?",
        UUID.randomUUID(),
        declarationId,
        kind,
        OffsetDateTime.now(),
        fromStatus,
        toStatus,
        currentActor(),
        fit(reason, 2000, "原因"),
        fit(notes, 2000, "备注"),
        declarationId);
  }

  /** 当前 RLS 租户。由 backend/libraries/tenancy 在事务内设置，客户端头改不动它。 */
  private UUID currentTenant() {
    return sql.fetchOne("select nullif(current_setting('app.current_tenant',true),'')::uuid")
        .get(0, UUID.class);
  }

  /** 当前操作人。设置名是 {@code app.actor}（AccessService 在事务里绑定），不是 app.current_user。 */
  private UUID currentActor() {
    Record r = sql.fetchOne("select nullif(current_setting('app.actor',true),'')::uuid as actor");
    UUID actor = r.get("actor", UUID.class);
    return actor == null ? currentTenant() : actor;
  }

  private String skuCode(UUID skuId, UUID tenantId) {
    Record r = sql.fetchOne("select code from sku_ref where tenant_id=? and id=?", tenantId, skuId);
    return r == null ? "" : r.get(0, String.class);
  }

  private String skuName(UUID skuId, UUID tenantId) {
    Record r = sql.fetchOne("select name from sku_ref where tenant_id=? and id=?", tenantId, skuId);
    return r == null ? "" : r.get(0, String.class);
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

  private String elementsJson(Map<String, Object> elements) {
    return toJson(elements == null ? Map.of() : elements, "申报要素");
  }

  /** 报关单状态 → 海关回执口径（对应 customs_result.from_result/to_result 的 CHECK 词表）。 */
  private String resultVocabulary(DeclarationStatus status) {
    return switch (status) {
      case SUBMITTED -> "PENDING";
      case CUSTOMS_REJECTED -> "REJECTED";
      case CLEARED -> "CLEARED";
      case AMENDED -> "AMENDED";
      default ->
          throw new IllegalArgumentException("状态「" + status.label() + "」还没有对应的海关回执口径，不能登记回执");
    };
  }

  /** 单据内容快照序列化成 jsonb。申报资料必须是真 JSON —— Map.toString() 出来的是 {k=v}，不是 JSON。 */
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

  private static String countryOrDefault(String value, String label) {
    return value == null || value.isBlank() ? "CN" : requireCountry(value, label);
  }

  private static String money(String value, String label) {
    String v = requireText(value, label).toUpperCase(java.util.Locale.ROOT);
    if (!v.matches("[A-Z]{3}")) {
      throw new IllegalArgumentException(label + "必须是三位大写币种代码（如 USD / CNY），收到：" + v);
    }
    return v;
  }

  private static String moneyOrDefault(String value, String fallback, String label) {
    return value == null || value.isBlank() ? fallback : money(value, label);
  }

  private static BigDecimal positive(BigDecimal value, String label) {
    if (value == null) {
      throw new IllegalArgumentException("缺少必填项：" + label);
    }
    if (value.signum() <= 0) {
      throw new IllegalArgumentException(label + "必须大于 0，收到 " + value);
    }
    return value;
  }

  private static BigDecimal nonNegative(BigDecimal value, String label) {
    if (value == null) {
      return BigDecimal.ZERO;
    }
    if (value.signum() < 0) {
      throw new IllegalArgumentException(label + "不能为负数，收到 " + value);
    }
    return value;
  }

  /** 统一截断到列宽，超长直接报错而不是静默截断 —— 申报资料被悄悄截断就是申报不实。 */
  private static String fit(String value, int width, String label) {
    if (value == null) {
      return "";
    }
    String v = value.trim();
    if (v.length() > width) {
      throw new IllegalArgumentException(
          label + "长度超过 " + width + " 字符（当前 " + v.length() + "）。申报资料被悄悄截断就是申报不实。");
    }
    return v;
  }
}
