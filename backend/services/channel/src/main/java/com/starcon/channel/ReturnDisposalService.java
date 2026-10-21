package com.starcon.channel;

import static com.starcon.channel.ReturnDisposalModels.*;

import com.starcon.tenancy.Ids;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 10.7.1.3 弃置与销毁。
 *
 * <p>蓝图：「管理弃置与销毁：无销售价值/品牌方要求/法规不允许再销售的退货，处理销毁、
 * <b>留存销毁凭证/照片/重量记录</b>，满足平台与品牌方的销毁证明要求；<b>销毁数量可作为
 * 品牌方索赔依据</b>。输入：待销毁清单；销毁方式（压碎/焚烧/专业销毁）；凭证要求。
 * 输出：销毁单 + 凭证 + 数量/重量记录；索赔依据（→ 6.1.2.3 / 11.2.3.3）。」
 *
 * <p>🔴 本类唯一不能妥协的约束是那条加粗句「<b>销毁凭证缺失会触发品牌方追责</b>」。
 * 它的落地是三件事同时做，缺一件就退化成「一个销毁单的空壳」：
 *
 * <ol>
 *   <li><b>凭证要求是算出来的</b>：销毁方式（压碎 / 焚烧 / 专业销毁）**决定**基线凭证要求，
 *       品牌方 / 平台政策**只能加严**不能放松。四面旗子由 DB 触发器重算并冻结
 *       （{@code return_disposal_resolve_requirements}），客户端填什么都不作废。</li>
 *   <li><b>凭证是真的行</b>：{@link #recordEvidence} 一次登记**一类**凭证
 *       （照片 / 重量 / 处置证明编号 / 执行方签核），每类有专属必填字段。</li>
 *   <li><b>完成态的门在 DB 上</b>：{@link #dispose} 前服务端先核对凭证齐不齐（422），
 *       DB 触发器再钉一道 —— <b>绕过服务层直接写 SQL 也进不去完成态</b>。</li>
 * </ol>
 *
 * <p>数据来源纪律：待销毁清单来自 <b>10.7.1.1</b> 的处置决策，<b>就在本服务</b>
 * （所以本模块真的读它，且只有 {@code DISCARD} 的处置单能进清单）。
 * 下游 12 财务<b>不存在</b>、11.2.3.3 供应商索赔<b>在 trade（跨服务）</b>
 * ⇒ 费用与索赔依据都以**本服务自持的事实**落地，不跨库 join、不建跨服务外键。
 */
@Service
public class ReturnDisposalService {

  private final DSLContext sql;

  public ReturnDisposalService(DSLContext sql) {
    this.sql = sql;
  }

  // ==================================================================
  // 基础工具
  // ==================================================================

  private static String text(String value) {
    return value == null ? "" : value.trim();
  }

  /** 当前事务内的租户（由 backend/libraries/tenancy 在进入事务时绑定）。 */
  private UUID currentTenant() {
    Record row = sql.fetchOne("select nullif(current_setting('app.current_tenant',true),'')");
    String raw = row == null ? null : row.get(0, String.class);
    if (raw == null || raw.isBlank()) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "缺少租户上下文");
    }
    return UUID.fromString(raw);
  }

  /** 跨租户与不存在返回<b>同一个 404 且理由逐字相同</b>。 */
  private static ResponseStatusException notFound(String what) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, what + "不存在");
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC);
  }

  /** 币种必须是三个大写字母（DB 列级 CHECK 再钉一道）。 */
  private static String currency(String raw, String field) {
    String value = text(raw).toUpperCase(Locale.ROOT);
    if (!value.matches("^[A-Z]{3}$")) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, field + " 必须是三个大写字母的币种代码（例如 USD / EUR）");
    }
    return value;
  }

  private static BigDecimal positive(BigDecimal value, String field) {
    if (value == null || value.signum() <= 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " 必须大于 0");
    }
    return value;
  }

  private static BigDecimal nonNegative(BigDecimal value, String field) {
    if (value == null || value.signum() < 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " 不能小于 0");
    }
    return value;
  }

  private static BigDecimal zero(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  private static <E extends Enum<E>> String enumCode(String raw, E[] values, String field, String what) {
    String value = text(raw).toUpperCase(Locale.ROOT);
    for (E e : values) {
      if (e.name().equals(value)) {
        return value;
      }
    }
    List<String> codes = new ArrayList<>();
    for (E e : values) {
      codes.add(e.name());
    }
    throw new ResponseStatusException(
        HttpStatus.UNPROCESSABLE_ENTITY, field + "必须是 " + String.join(" / ", codes) + " 之一：" + what);
  }

  private static String statusLabel(String raw) {
    for (DisposalStatus s : DisposalStatus.values()) {
      if (s.name().equals(raw)) {
        return s.label();
      }
    }
    return raw;
  }

  private static String methodLabel(String raw) {
    for (DisposalMethod m : DisposalMethod.values()) {
      if (m.name().equals(raw)) {
        return m.label();
      }
    }
    return raw;
  }

  private static String evidenceKindLabel(String raw) {
    for (EvidenceKind k : EvidenceKind.values()) {
      if (k.name().equals(raw)) {
        return k.label();
      }
    }
    return raw;
  }

  private static String costTypeLabel(String raw) {
    for (DisposalCostType c : DisposalCostType.values()) {
      if (c.name().equals(raw)) {
        return c.label();
      }
    }
    return raw;
  }

  private static String claimTypeLabel(String raw) {
    for (ClaimType c : ClaimType.values()) {
      if (c.name().equals(raw)) {
        return c.label();
      }
    }
    return raw;
  }

  private static String claimStatusLabel(String raw) {
    for (ClaimBasisStatus c : ClaimBasisStatus.values()) {
      if (c.name().equals(raw)) {
        return c.label();
      }
    }
    return raw;
  }

  private static String subjectTypeLabel(String raw) {
    for (PolicySubjectType s : PolicySubjectType.values()) {
      if (s.name().equals(raw)) {
        return s.label();
      }
    }
    return raw;
  }

  private static String policyStatusLabel(String raw) {
    return "ACTIVE".equals(raw) ? "生效中" : "已撤销";
  }

  private static String eventTypeLabel(String raw) {
    for (DisposalEventType e : DisposalEventType.values()) {
      if (e.name().equals(raw)) {
        return e.label();
      }
    }
    return raw;
  }

  private static long count(Record row) {
    return row == null ? 0L : row.get(0, Long.class);
  }

  private static Object[] concat(List<Object> args, Object... more) {
    Object[] all = new Object[args.size() + more.length];
    for (int i = 0; i < args.size(); i++) {
      all[i] = args.get(i);
    }
    for (int i = 0; i < more.length; i++) {
      all[args.size() + i] = more[i];
    }
    return all;
  }

  private String nextNo(String sequence, String prefix) {
    long n = sql.fetchOne("select nextval('" + sequence + "')").get(0, Long.class);
    return prefix + n;
  }

  /** 当前事务内的操作者（由 AccessService 从已认证成员关系解析并盖章到 app.actor）。 */
  private UUID actorOrFail() {
    Record row = sql.fetchOne("select nullif(current_setting('app.actor',true),'')");
    String raw = row == null ? null : row.get(0, String.class);
    if (raw == null || raw.isBlank()) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "缺少操作者上下文");
    }
    return UUID.fromString(raw);
  }

  // ==================================================================
  // 能力声明
  // ==================================================================

  public ReturnDisposalCapabilities capabilities() {
    List<DisposalCapabilityDimension> dimensions =
        List.of(
            new DisposalCapabilityDimension(
                "candidate_list",
                "🔴 待销毁清单：只取 10.7.1.1 决策为「弃置销毁」的处置单",
                true,
                "上游 10.7.1.1（**同服务** channel，直接读 return_disposition_order）",
                "",
                "转销售 / 退运回国的处置单**根本不会**出现在候选清单里；"
                    + "一条处置单最多进一个销毁单"),
            new DisposalCapabilityDimension(
                "disposal_method",
                "🔴 销毁方式（压碎 / 焚烧 / 专业销毁）：**枚举事实**，不是自由文本",
                true,
                "本服务 V221 disposal_method",
                "",
                "方式**决定**基线凭证要求：专业销毁要重量记录、焚烧要处置证明编号；"
                    + "列上没有默认值，缺方式直接 not-null violation"),
            new DisposalCapabilityDimension(
                "evidence_policy",
                "凭证要求（品牌方要求 / 平台政策 / 租户默认）—— **配置**",
                true,
                "本服务 V221 return_disposal_evidence_policy",
                "",
                "🔴 只能**加严**不能放松（取并集）；租户级可见（不挂数据范围），"
                    + "否则另一个运营会在看不见要求的情况下把货销毁掉"),
            new DisposalCapabilityDimension(
                "evidence_records",
                "🔴 销毁凭证（照片 / 重量 / 处置证明编号 / 执行方签核）",
                true,
                "本服务 V221 return_disposal_evidence",
                "",
                "一类凭证一张；每类有专属必填字段；凭证不可删，销毁单完成后不可再改"),
            new DisposalCapabilityDimension(
                "evidence_completeness_gate",
                "🔴 凭证缺失 ⇒ 不许把销毁单置为完成态（**DB 触发器钉死**）",
                true,
                "本服务 V221 触发器 guard_return_disposal_order_state",
                "",
                "服务端 422 + DB 触发器各拒一次；绕过服务层直接写 SQL 也进不去完成态"),
            new DisposalCapabilityDimension(
                "quantity_weight_record",
                "数量 / 重量记录（禁浮点：NUMERIC(19,6) + BigDecimal）",
                true,
                "本服务 V221 disposal_qty / weight_kg / weight_unit",
                "",
                "重量以 WEIGHT 凭证的称重读数为**唯一来源**，完成时写入销毁单；"
                    + "销毁单上不允许另填一个与凭证不同的重量"),
            new DisposalCapabilityDimension(
                "disposal_cost",
                "销毁费用（销毁费 / 上门取件费 / 运输费……）",
                true,
                "本服务 V221 return_disposal_cost",
                "",
                "合计**不落表**，查询时 SUM 现算；金额 BigDecimal ↔ NUMERIC(19,4)"),
            new DisposalCapabilityDimension(
                "finance_settlement",
                "🔴 12 费用对账 / 应付冲减",
                false,
                "下游 12 财务（finance 服务）当前不存在",
                "12",
                "🔴 12 财务（finance 服务）当前不存在 ⇒ 销毁费用以本服务自有事实落地，"
                    + "与 12 财务的最终对账留待 finance 落地后经授权 API / event 衔接"),
            new DisposalCapabilityDimension(
                "claim_basis",
                "索赔依据（销毁数量 / 重量 / 凭证齐备性）",
                true,
                "本服务 V221 return_disposal_claim_basis",
                "",
                "一条销毁单一条依据（不重复索赔）；数量与重量必须与销毁单逐字相同；"
                    + "凭证齐备性由触发器重算，客户端自报不作数"),
            new DisposalCapabilityDimension(
                "claim_handoff",
                "🔴 索赔单创建与应付冲减",
                false,
                "下游 11.2.3.3 供应商索赔在 trade（**跨服务**）",
                "11.2.3.3",
                "🔴 11.2.3.3 在 trade ⇒ 本模块**只产出索赔依据事实**、以 source_reference / "
                    + "claim_ref 记逻辑引用，**绝不写 trade 的任何表**；"
                    + "下游索赔单创建与应付冲减留待 trade 侧授权 API / event 衔接"),
            new DisposalCapabilityDimension(
                "inventory_writeoff",
                "库存销账（10.7.1.2 报废分录的销账方）",
                true,
                "本服务 V221 return_disposal_order",
                "",
                "🔴 10.7.1.2 的报废分录 consumer_module 恒为 '10.7.1.3' ⇒ "
                    + "本模块销毁即销账，**不另开库存分录**（否则同一批货记两遍）"),
            new DisposalCapabilityDimension(
                "platform_evidence_return",
                "🔴 平台侧销毁证明回传",
                false,
                "下游 9.4.2.1 平台回传未交付",
                "9.4.2.1",
                "本模块留存凭证以**满足**平台与品牌方的销毁证明要求，"
                    + "但**回传动作**归 9.4.2.1（未交付）⇒ 本模块不假装已回传"));
    return new ReturnDisposalCapabilities(
        dimensions,
        DisposalStatus.codes(),
        DisposalMethod.codes(),
        EvidenceKind.codes(),
        DisposalCostType.codes(),
        ClaimType.codes(),
        WeightUnit.codes(),
        "🔴 销毁方式是**枚举事实**（压碎 / 焚烧 / 专业销毁），不是自由文本；"
            + "方式同时决定基线凭证要求：专业销毁要重量记录、焚烧要处置证明编号。",
        "🔴 凭证要求 = 方式基线 ∪ 品牌方 / 平台政策，**只能加严不能放松**；"
            + "四面旗子由 DB 触发器重算并冻结，客户端填什么都不作废。",
        "🔴 销毁费用与索赔金额都走销毁单那**一个**费用币种；"
            + "本模块**不做任何汇率换算**，两者**永远不相加**。",
        "🔴 12 财务（finance 服务）当前不存在：销毁费用以本服务自有事实落地，"
            + "与 12 财务的最终对账留待 finance 落地后经授权 API / event 衔接。",
        "🔴 11.2.3.3 供应商索赔在 trade（跨服务）：本模块**只产出索赔依据事实**，"
            + "以 source_reference / claim_ref 记逻辑引用，**绝不写 trade 的任何表**。",
        "上游待销毁清单来自 10.7.1.1（**同服务**，直接读处置单）；"
            + "只有明确决策为「弃置销毁」的货才能进销毁单。",
        "本模块站在 10.7.1.1 之上做**真的销毁与留证**：不改处置决策、不改处置单、"
            + "不重做 10.7.1.1 的库存指令。");
  }

  // ==================================================================
  // 概览
  // ==================================================================

  public ReturnDisposalOverview overview() {
    UUID tenant = currentTenant();
    long orders =
        count(sql.fetchOne("select count(*) from return_disposal_order where tenant_id=?::uuid", tenant));
    long draft = statusCount(tenant, "DRAFT");
    long scheduled = statusCount(tenant, "SCHEDULED");
    long disposed = statusCount(tenant, "DISPOSED");
    long cancelled = statusCount(tenant, "CANCELLED");
    long evidence =
        count(
            sql.fetchOne(
                "select count(*) from return_disposal_evidence where tenant_id=?::uuid", tenant));
    var candidate =
        sql.fetchOne(
            "select count(*), coalesce(sum(o.disposed_qty),0) from return_disposition_order o"
                + " where o.tenant_id=?::uuid and o.disposition_method='DISCARD'"
                + "   and o.status='ISSUED'"
                + "   and not exists (select 1 from return_disposal_order d"
                + "     where d.tenant_id=o.tenant_id and d.disposition_order_id=o.id)",
            tenant);
    long candidateCount = candidate == null ? 0L : candidate.get(0, Long.class);
    BigDecimal candidateQty = candidate == null ? BigDecimal.ZERO : zero(candidate.get(1, BigDecimal.class));
    var disposedRow =
        sql.fetchOne(
            "select count(*), coalesce(sum(d.disposal_qty),0), coalesce(sum(d.weight_kg),0)"
                + " from return_disposal_order d"
                + " where d.tenant_id=?::uuid and d.status='DISPOSED'",
            tenant);
    long disposedOrderCount = disposedRow == null ? 0L : disposedRow.get(0, Long.class);
    BigDecimal disposedQty = disposedRow == null ? BigDecimal.ZERO : zero(disposedRow.get(1, BigDecimal.class));
    BigDecimal disposedWeight =
        disposedRow == null ? BigDecimal.ZERO : zero(disposedRow.get(2, BigDecimal.class));
    long claimBasis =
        count(
            sql.fetchOne(
                "select count(*) from return_disposal_claim_basis where tenant_id=?::uuid", tenant));
    List<DisposalCurrencyTotal> costTotals = costTotals(tenant);
    return new ReturnDisposalOverview(
        orders,
        draft,
        scheduled,
        disposed,
        cancelled,
        candidateCount,
        candidateQty,
        evidence,
        disposedOrderCount,
        disposedQty,
        disposedWeight,
        claimBasis,
        costTotals,
        now(),
        "🔴 已销毁的数量与重量就是**索赔依据的来源**；候选清单只来自 10.7.1.1 明确判为"
            + "「弃置销毁」的处置单。",
        "🔴 费用按币种分行，**跨币种不相加**；本模块不做任何汇率换算。");
  }

  private long statusCount(UUID tenant, String status) {
    return count(
        sql.fetchOne(
            "select count(*) from return_disposal_order where tenant_id=?::uuid and status=?", tenant, status));
  }

  private List<DisposalCurrencyTotal> costTotals(UUID tenant) {
    List<DisposalCurrencyTotal> out = new ArrayList<>();
    for (Record r :
        sql.fetch(
            "select c.currency, coalesce(sum(c.amount),0), count(*)"
                + " from return_disposal_cost c where c.tenant_id=?::uuid"
                + " group by c.currency order by c.currency",
            tenant)) {
      out.add(
          new DisposalCurrencyTotal(
              r.get(0, String.class), zero(r.get(1, BigDecimal.class)), r.get(2, Long.class)));
    }
    return out;
  }

  // ==================================================================
  // 待销毁清单（上游 10.7.1.1，同服务直接读）
  // ==================================================================

  private static final String CANDIDATE_COLUMNS =
      "o.id,o.disposition_no,o.decision_id,o.receipt_no,o.sku_code,o.sku_name,o.batch_no,"
          + "o.brand_code,o.platform_code,o.disposed_qty,o.disposition_method,o.issued_at,o.note";

  private static final String CANDIDATE_FROM =
      " from return_disposition_order o"
          + " where o.tenant_id=?::uuid and o.disposition_method='DISCARD' and o.status='ISSUED'"
          + "   and not exists (select 1 from return_disposal_order d"
          + "     where d.tenant_id=o.tenant_id and d.disposition_order_id=o.id)";

  public DisposalCandidatePage candidates(String keyword, int page, int size) {
    UUID tenant = currentTenant();
    int limit = Math.min(Math.max(size, 1), 200);
    int offset = Math.max(page, 0) * limit;
    List<Object> args = new ArrayList<>();
    args.add(tenant);
    StringBuilder where = new StringBuilder();
    String kw = text(keyword);
    if (!kw.isEmpty()) {
      where.append(
          " and (o.sku_code ilike ? or o.sku_name ilike ? or o.receipt_no ilike ?"
              + " or o.batch_no ilike ? or o.disposition_no ilike ?)");
      for (int i = 0; i < 5; i++) {
        args.add("%" + kw + "%");
      }
    }
    long total = count(sql.fetchOne("select count(*)" + CANDIDATE_FROM + where, args.toArray()));
    var qtyRow = sql.fetchOne("select coalesce(sum(o.disposed_qty),0)" + CANDIDATE_FROM + where, args.toArray());
    BigDecimal totalQty = qtyRow == null ? BigDecimal.ZERO : zero(qtyRow.get(0, BigDecimal.class));
    var rows =
        sql.fetch(
            "select " + CANDIDATE_COLUMNS + CANDIDATE_FROM + where
                + " order by o.issued_at desc, o.id desc limit ? offset ?",
            concat(args, limit, offset));
    List<DisposalCandidateRow> items = new ArrayList<>();
    for (Record r : rows) {
      items.add(candidateRow(r));
    }
    return new DisposalCandidatePage(
        items,
        Math.max(page, 0),
        limit,
        total,
        totalQty,
        now(),
        "🔴 候选只来自 10.7.1.1 决策为「弃置销毁」的处置单："
            + "转销售 / 退运回国的处置单不会出现在这里。");
  }

  private DisposalCandidateRow candidateRow(Record r) {
    String method = r.get("disposition_method", String.class);
    OffsetDateTime issued = r.get("issued_at", OffsetDateTime.class);
    return new DisposalCandidateRow(
        r.get("id", UUID.class),
        r.get("disposition_no", String.class),
        r.get("decision_id", UUID.class),
        r.get("receipt_no", String.class),
        r.get("sku_code", String.class),
        r.get("sku_name", String.class),
        r.get("batch_no", String.class),
        r.get("brand_code", String.class),
        r.get("platform_code", String.class),
        zero(r.get("disposed_qty", BigDecimal.class)),
        method,
        "弃置销毁",
        issued == null ? "" : issued.toString(),
        r.get("note", String.class));
  }

  // ==================================================================
  // 凭证要求（配置）
  // ==================================================================

  public ReturnDisposalPolicyPage policies(String subjectType, String status, int page, int size) {
    UUID tenant = currentTenant();
    int limit = Math.min(Math.max(size, 1), 200);
    int offset = Math.max(page, 0) * limit;
    List<Object> args = new ArrayList<>();
    args.add(tenant);
    StringBuilder where = new StringBuilder(" where p.tenant_id=?::uuid");
    String st = text(subjectType).toUpperCase(Locale.ROOT);
    if (!st.isEmpty()) {
      where.append(" and p.subject_type=?");
      args.add(st);
    }
    String ss = text(status).toUpperCase(Locale.ROOT);
    if (!ss.isEmpty()) {
      where.append(" and p.status=?");
      args.add(ss);
    }
    long total = count(sql.fetchOne("select count(*) from return_disposal_evidence_policy p" + where, args.toArray()));
    var rows =
        sql.fetch(
            "select p.id,p.subject_type,p.subject_ref,p.required_photo,p.required_weight,"
                + "p.required_certificate,p.required_executor,p.effective_from,p.effective_to,"
                + "p.status,p.note,p.created_at"
                + " from return_disposal_evidence_policy p"
                + where
                + " order by p.created_at desc, p.id desc limit ? offset ?",
            concat(args, limit, offset));
    List<ReturnDisposalPolicyRow> items = new ArrayList<>();
    for (Record r : rows) {
      items.add(policyRow(r));
    }
    return new ReturnDisposalPolicyPage(
        items,
        Math.max(page, 0),
        limit,
        total,
        now(),
        "🔴 凭证要求**只能加严**不能放松；它对全租户生效，"
            + "所以不按负责人收敛（否则有人会在看不见要求的情况下把货销毁掉）。");
  }

  private ReturnDisposalPolicyRow policyRow(Record r) {
    String subjectType = r.get("subject_type", String.class);
    String status = r.get("status", String.class);
    return new ReturnDisposalPolicyRow(
        r.get("id", UUID.class),
        subjectType,
        subjectTypeLabel(subjectType),
        r.get("subject_ref", String.class),
        Boolean.TRUE.equals(r.get("required_photo", Boolean.class)),
        Boolean.TRUE.equals(r.get("required_weight", Boolean.class)),
        Boolean.TRUE.equals(r.get("required_certificate", Boolean.class)),
        Boolean.TRUE.equals(r.get("required_executor", Boolean.class)),
        r.get("effective_from", OffsetDateTime.class),
        r.get("effective_to", OffsetDateTime.class),
        status,
        policyStatusLabel(status),
        r.get("note", String.class),
        r.get("created_at", OffsetDateTime.class));
  }

  /** 登记一条凭证要求（品牌方 / 平台 / 租户默认）。 */
  public ReturnDisposalPolicyRow createPolicy(CreateDisposalPolicyRequest request) {
    UUID tenant = currentTenant();
    if (request == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "必须给出凭证要求");
    }
    String subjectType =
        enumCode(
            request.subjectType(),
            PolicySubjectType.values(),
            "要求主体类型",
            "租户默认 / 品牌方要求 / 平台政策");
    String subjectRef = text(request.subjectRef());
    if (subjectType.equals("GENERAL")) {
      subjectRef = "ALL";
    } else {
      if (subjectRef.isEmpty()) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "品牌方 / 平台要求必须写明对应的编码（品牌编码 / 平台编码）");
      }
    }
    OffsetDateTime effectiveFrom =
        request.effectiveFrom() == null ? now() : request.effectiveFrom();
    if (request.effectiveTo() != null && !request.effectiveTo().isAfter(effectiveFrom)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "失效时刻必须晚于生效时刻");
    }
    UUID id = Ids.next();
    sql.execute(
        "insert into return_disposal_evidence_policy"
            + " (id,tenant_id,subject_type,subject_ref,required_photo,required_weight,"
            + "  required_certificate,required_executor,effective_from,effective_to,note)"
            + " values(?::uuid,?::uuid,?,?,?,?,?,?,?::timestamptz,?::timestamptz,?)",
        id,
        tenant,
        subjectType,
        subjectRef,
        request.requiredPhoto(),
        request.requiredWeight(),
        request.requiredCertificate(),
        request.requiredExecutor(),
        effectiveFrom,
        request.effectiveTo(),
        text(request.note()));
    event(tenant, "POLICY_CREATED", null, null, null, null, id, "登记凭证要求 " + subjectType + "/" + subjectRef);
    return policyRow(
        sql.fetchOne(
            "select p.id,p.subject_type,p.subject_ref,p.required_photo,p.required_weight,"
                + "p.required_certificate,p.required_executor,p.effective_from,p.effective_to,"
                + "p.status,p.note,p.created_at"
                + " from return_disposal_evidence_policy p where p.tenant_id=?::uuid and p.id=?::uuid",
            tenant,
            id));
  }

  /** 撤销一条凭证要求（必须留理由）。 */
  public ReturnDisposalPolicyRow revokePolicy(UUID policyId, RevokeDisposalPolicyRequest request) {
    UUID tenant = currentTenant();
    String reason = request == null ? "" : text(request.reason());
    if (reason.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "撤销凭证要求必须写明理由");
    }
    Record row =
        sql.fetchOne(
            "select id from return_disposal_evidence_policy where tenant_id=?::uuid and id=?::uuid",
            tenant,
            policyId);
    if (row == null) {
      throw notFound("凭证要求");
    }
    sql.execute(
        "update return_disposal_evidence_policy set status='REVOKED', revoked_at=?::timestamptz,"
            + " revoked_by=?::uuid, revoke_reason=?, updated_at=?::timestamptz"
            + " where tenant_id=?::uuid and id=?::uuid",
        now(),
        actorOrFail(),
        reason,
        now(),
        tenant,
        policyId);
    event(tenant, "POLICY_REVOKED", null, null, null, null, policyId, "撤销凭证要求：" + reason);
    return policyRow(
        sql.fetchOne(
            "select p.id,p.subject_type,p.subject_ref,p.required_photo,p.required_weight,"
                + "p.required_certificate,p.required_executor,p.effective_from,p.effective_to,"
                + "p.status,p.note,p.created_at"
                + " from return_disposal_evidence_policy p where tenant_id=?::uuid and p.id=?::uuid",
            tenant,
            policyId));
  }

  // ==================================================================
  // 销毁单
  // ==================================================================

  private static final String ORDER_COLUMNS =
      "d.id,d.disposal_no,d.status,d.disposal_method,d.source_reference,d.disposition_order_id,"
          + "d.decision_id,d.receipt_no,d.sku_code,d.sku_name,d.batch_no,d.brand_code,d.platform_code,"
          + "d.disposal_qty,d.weight_kg,d.weight_unit,d.require_photo,d.require_weight,"
          + "d.require_certificate,d.require_executor,d.cost_currency,d.planned_at,d.disposed_at,"
          + "d.executor_name,d.created_at,d.note,d.version,"
          + "(select count(*) from return_disposal_evidence e"
          + "  where e.tenant_id=d.tenant_id and e.disposal_order_id=d.id) as evidence_count,"
          + "(select coalesce(sum(c.amount),0) from return_disposal_cost c"
          + "  where c.tenant_id=d.tenant_id and c.disposal_order_id=d.id) as total_cost";

  private static final String ORDER_FROM = " from return_disposal_order d";

  public ReturnDisposalPage orders(String keyword, String status, String method, int page, int size) {
    UUID tenant = currentTenant();
    int limit = Math.min(Math.max(size, 1), 200);
    int offset = Math.max(page, 0) * limit;
    List<Object> args = new ArrayList<>();
    args.add(tenant);
    StringBuilder where = new StringBuilder(" where d.tenant_id=?::uuid");
    String kw = text(keyword);
    if (!kw.isEmpty()) {
      where.append(
          " and (d.disposal_no ilike ? or d.source_reference ilike ? or d.receipt_no ilike ?"
              + " or d.sku_code ilike ? or d.sku_name ilike ? or d.batch_no ilike ?"
              + " or d.executor_name ilike ?)");
      for (int i = 0; i < 7; i++) {
        args.add("%" + kw + "%");
      }
    }
    String st = text(status).toUpperCase(Locale.ROOT);
    if (!st.isEmpty()) {
      where.append(" and d.status=?");
      args.add(st);
    }
    String sm = text(method).toUpperCase(Locale.ROOT);
    if (!sm.isEmpty()) {
      where.append(" and d.disposal_method=?");
      args.add(sm);
    }
    long total = count(sql.fetchOne("select count(*)" + ORDER_FROM + where, args.toArray()));
    var sumRow =
        sql.fetchOne(
            "select coalesce(sum(c.amount),0) from return_disposal_cost c"
                + " join return_disposal_order d on d.tenant_id=c.tenant_id and d.id=c.disposal_order_id"
                + " where c.tenant_id=?::uuid and d.status <> 'CANCELLED'",
            tenant);
    BigDecimal totalCost = sumRow == null ? BigDecimal.ZERO : zero(sumRow.get(0, BigDecimal.class));
    var rows =
        sql.fetch(
            "select " + ORDER_COLUMNS + ORDER_FROM + where
                + " order by d.created_at desc, d.id desc limit ? offset ?",
            concat(args, limit, offset));
    List<ReturnDisposalRow> items = new ArrayList<>();
    for (Record r : rows) {
      items.add(orderRow(r));
    }
    return new ReturnDisposalPage(
        items,
        Math.max(page, 0),
        limit,
        total,
        totalCost,
        now(),
        "销毁单 = 一次销毁的执行凭据：方式 / 数量 / 重量 / 凭证齐备性。合计为查询时现算的 SUM，不落表。");
  }

  private ReturnDisposalRow orderRow(Record r) {
    String status = r.get("status", String.class);
    String method = r.get("disposal_method", String.class);
    return new ReturnDisposalRow(
        r.get("id", UUID.class),
        r.get("disposal_no", String.class),
        status,
        statusLabel(status),
        method,
        methodLabel(method),
        r.get("source_reference", String.class),
        r.get("disposition_order_id", UUID.class),
        r.get("decision_id", UUID.class),
        r.get("receipt_no", String.class),
        r.get("sku_code", String.class),
        r.get("sku_name", String.class),
        r.get("batch_no", String.class),
        r.get("brand_code", String.class),
        r.get("platform_code", String.class),
        zero(r.get("disposal_qty", BigDecimal.class)),
        zero(r.get("weight_kg", BigDecimal.class)),
        r.get("weight_unit", String.class),
        Boolean.TRUE.equals(r.get("require_photo", Boolean.class)),
        Boolean.TRUE.equals(r.get("require_weight", Boolean.class)),
        Boolean.TRUE.equals(r.get("require_certificate", Boolean.class)),
        Boolean.TRUE.equals(r.get("require_executor", Boolean.class)),
        r.get("cost_currency", String.class),
        zero(r.get("total_cost", BigDecimal.class)),
        r.get("evidence_count", Long.class),
        r.get("planned_at", OffsetDateTime.class),
        r.get("disposed_at", OffsetDateTime.class),
        r.get("executor_name", String.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("note", String.class),
        r.get("version", Long.class));
  }

  /**
   * 建一张销毁单。
   *
   * <p>🔴 上游的 SKU / 批次 / 收货单号**由服务端从处置单抄**（触发器再钉一道），
   * 不接受客户端自报 —— 客户端自报会出现「单上写着 A 仓的货、实际销毁的是 B 仓的货」。
   */
  public ReturnDisposalDetail createOrder(CreateDisposalOrderRequest request) {
    UUID tenant = currentTenant();
    if (request == null || request.dispositionOrderId() == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "必须指定上游处置单（dispositionOrderId）：销毁单的货来自 10.7.1.1");
    }
    String method =
        enumCode(
            request.disposalMethod(),
            DisposalMethod.values(),
            "销毁方式",
            "压碎 / 焚烧 / 专业销毁");
    BigDecimal qty = positive(request.disposalQty(), "销毁数量");
    String costCurrency = currency(request.costCurrency(), "销毁费用币种");
    String weightUnit =
        request.weightUnit() == null || text(request.weightUnit()).isEmpty()
            ? "KG"
            : enumCode(request.weightUnit(), WeightUnit.values(), "重量单位", "千克 / 克 / 磅");

    var upstream =
        sql.fetchOne(
            "select id,decision_id,disposition_no,receipt_no,sku_code,sku_name,batch_no,"
                + "brand_code,platform_code,disposed_qty,disposition_method,status"
                + " from return_disposition_order where tenant_id=?::uuid and id=?::uuid",
            tenant,
            request.dispositionOrderId());
    if (upstream == null) {
      throw notFound("上游处置单");
    }
    if (!"DISCARD".equals(upstream.get("disposition_method", String.class))) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "处置单 "
              + upstream.get("disposition_no", String.class)
              + " 的去向不是「弃置销毁」：只有明确决策为弃置销毁的货才能进销毁单");
    }
    if (!"ISSUED".equals(upstream.get("status", String.class))) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "上游处置单已撤销：不能进销毁单");
    }
    BigDecimal disposedQty = zero(upstream.get("disposed_qty", BigDecimal.class));
    if (qty.compareTo(disposedQty) > 0) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY, "销毁数量 " + qty + " 超过处置单的处置量 " + disposedQty);
    }
    var exists =
        sql.fetchOne(
            "select id from return_disposal_order where tenant_id=?::uuid and disposition_order_id=?::uuid",
            tenant,
            request.dispositionOrderId());
    if (exists != null) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "这条处置单已经进过销毁单：同一批货不能被销毁两次");
    }

    UUID id = Ids.next();
    String no = nextNo("return_disposal_order_no_seq", "DSP");
    sql.execute(
        "insert into return_disposal_order"
            + " (id,tenant_id,disposal_no,disposition_order_id,decision_id,source_reference,"
            + "  receipt_no,sku_code,sku_name,batch_no,brand_code,platform_code,disposal_method,"
            + "  disposal_qty,weight_unit,cost_currency,note)"
            + " values(?::uuid,?::uuid,?,?::uuid,?::uuid,?,?,?,?,?,?,?,?,?::numeric,?,?,?)",
        id,
        tenant,
        no,
        upstream.get("id", UUID.class),
        upstream.get("decision_id", UUID.class),
        upstream.get("disposition_no", String.class),
        upstream.get("receipt_no", String.class),
        upstream.get("sku_code", String.class),
        upstream.get("sku_name", String.class),
        upstream.get("batch_no", String.class),
        upstream.get("brand_code", String.class),
        upstream.get("platform_code", String.class),
        method,
        qty,
        weightUnit,
        costCurrency,
        text(request.note()));
    event(
        tenant,
        "ORDER_CREATED",
        id,
        null,
        null,
        null,
        null,
        "新建销毁单 " + no + "（" + methodLabel(method) + " × " + qty + "）");
    return detailOf(tenant, id);
  }

  /** 取销毁单行（跨租户与不存在返回同一个 404）。 */
  private Record requireOrder(UUID tenant, UUID orderId) {
    Record row =
        sql.fetchOne(
            "select id,disposal_no,status,disposal_method,source_reference,disposition_order_id,"
                + "decision_id,receipt_no,sku_code,sku_name,batch_no,brand_code,platform_code,"
                + "disposal_qty,weight_kg,weight_unit,require_photo,require_weight,"
                + "require_certificate,require_executor,cost_currency,planned_at,disposed_at,"
                + "executor_name,created_at,note,version"
                + " from return_disposal_order where tenant_id=?::uuid and id=?::uuid",
            tenant,
            orderId);
    if (row == null) {
      throw notFound("销毁单");
    }
    return row;
  }

  private ReturnDisposalDetail detailOf(UUID tenant, UUID orderId) {
    Record row =
        sql.fetchOne(
            "select " + ORDER_COLUMNS + ORDER_FROM + " where d.tenant_id=?::uuid and d.id=?::uuid",
            tenant,
            orderId);
    if (row == null) {
      throw notFound("销毁单");
    }
    List<ReturnDisposalEvidenceRow> evidence = new ArrayList<>();
    for (Record r :
        sql.fetch(
            "select id,disposal_order_id,evidence_kind,photo_url,weight_kg,certificate_no,"
                + "executor_name,occurred_at,recorded_by,note,created_at"
                + " from return_disposal_evidence where tenant_id=?::uuid and disposal_order_id=?::uuid"
                + " order by evidence_kind",
            tenant,
            orderId)) {
      String kind = r.get("evidence_kind", String.class);
      evidence.add(
          new ReturnDisposalEvidenceRow(
              r.get("id", UUID.class),
              r.get("disposal_order_id", UUID.class),
              kind,
              evidenceKindLabel(kind),
              r.get("photo_url", String.class),
              r.get("weight_kg", BigDecimal.class),
              r.get("certificate_no", String.class),
              r.get("executor_name", String.class),
              r.get("occurred_at", OffsetDateTime.class),
              r.get("recorded_by", UUID.class),
              r.get("note", String.class),
              r.get("created_at", OffsetDateTime.class)));
    }
    List<ReturnDisposalCostRow> costs = new ArrayList<>();
    for (Record r :
        sql.fetch(
            "select id,disposal_order_id,cost_type,amount,currency,vendor_ref,incurred_at,note"
                + " from return_disposal_cost where tenant_id=?::uuid and disposal_order_id=?::uuid"
                + " order by incurred_at desc, id desc",
            tenant,
            orderId)) {
      String costType = r.get("cost_type", String.class);
      costs.add(
          new ReturnDisposalCostRow(
              r.get("id", UUID.class),
              r.get("disposal_order_id", UUID.class),
              costType,
              costTypeLabel(costType),
              zero(r.get("amount", BigDecimal.class)),
              r.get("currency", String.class),
              r.get("vendor_ref", String.class),
              r.get("incurred_at", OffsetDateTime.class),
              r.get("note", String.class)));
    }
    List<ReturnDisposalClaimBasisRow> claimBases = new ArrayList<>();
    for (Record r :
        sql.fetch(
            "select id,disposal_order_id,claim_type,counterparty_code,claim_qty,claim_weight_kg,"
                + "claimed_amount,claimed_currency,evidence_complete,source_reference,claim_ref,"
                + "status,submitted_at,reject_reason,note,created_at"
                + " from return_disposal_claim_basis where tenant_id=?::uuid and disposal_order_id=?::uuid"
                + " order by created_at desc, id desc",
            tenant,
            orderId)) {
      claimBases.add(claimBasisRow(r));
    }
    List<ReturnDisposalEventRow> events = new ArrayList<>();
    for (Record r :
        sql.fetch(
            "select id,event_type,order_id,evidence_id,cost_id,claim_basis_id,policy_id,actor_id,"
                + "occurred_at,detail from return_disposal_event"
                + " where tenant_id=?::uuid and order_id=?::uuid order by occurred_at desc, id desc"
                + " limit 200",
            tenant,
            orderId)) {
      events.add(eventRow(r));
    }
    ReturnDisposalRow order = orderRow(row);
    return new ReturnDisposalDetail(order, evidence, costs, claimBases, events);
  }

  public ReturnDisposalDetail orderDetail(UUID orderId) {
    UUID tenant = currentTenant();
    return detailOf(tenant, orderId);
  }

  /** 排期：把凭证要求**冻结**下来的那一刻。 */
  public ReturnDisposalDetail schedule(UUID orderId, ScheduleDisposalRequest request) {
    UUID tenant = currentTenant();
    Record row = requireOrder(tenant, orderId);
    String status = row.get("status", String.class);
    if (!"DRAFT".equals(status)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "销毁单当前是「" + statusLabel(status) + "」，只有草稿可以排期");
    }
    OffsetDateTime plannedAt = request == null ? null : request.plannedAt();
    sql.execute(
        "update return_disposal_order set status='SCHEDULED', planned_at=?::timestamptz,"
            + " updated_at=?::timestamptz, version=version+1"
            + " where tenant_id=?::uuid and id=?::uuid",
        plannedAt,
        now(),
        tenant,
        orderId);
    event(tenant, "ORDER_SCHEDULED", orderId, null, null, null, null, "排期并冻结凭证要求");
    return detailOf(tenant, orderId);
  }

  /**
   * 登记一类凭证（照片 / 重量 / 处置证明编号 / 执行方签核）。
   *
   * <p>🔴 一类凭证一张：重复登记**改写**那一行而不是再插一行 ——
   * 两张互相矛盾的 PHOTO 只会让品牌方追责时更难说清。
   */
  public ReturnDisposalDetail recordEvidence(UUID orderId, RecordDisposalEvidenceRequest request) {
    UUID tenant = currentTenant();
    if (request == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "必须给出凭证内容");
    }
    Record row = requireOrder(tenant, orderId);
    String status = row.get("status", String.class);
    if ("DISPOSED".equals(status) || "CANCELLED".equals(status)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "销毁单当前是「" + statusLabel(status) + "」，不再接受新凭证");
    }
    String kind =
        enumCode(
            request.evidenceKind(),
            EvidenceKind.values(),
            "凭证种类",
            "销毁照片 / 重量记录 / 处置证明编号 / 执行方签核");
    String photoUrl = text(request.photoUrl());
    String certificateNo = text(request.certificateNo());
    String executorName = text(request.executorName());
    BigDecimal weightKg = request.weightKg();
    switch (kind) {
      case "PHOTO" -> {
        if (photoUrl.isEmpty()) {
          throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "照片凭证必须填照片地址");
        }
        weightKg = null;
        certificateNo = "";
        executorName = "";
      }
      case "WEIGHT" -> {
        if (weightKg == null) {
          throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "重量凭证必须填重量");
        }
        nonNegative(weightKg, "重量");
        photoUrl = "";
        certificateNo = "";
        executorName = "";
      }
      case "CERTIFICATE" -> {
        if (certificateNo.isEmpty()) {
          throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "处置证明凭证必须填处置证明编号");
        }
        weightKg = null;
        photoUrl = "";
        executorName = "";
      }
      case "EXECUTOR" -> {
        if (executorName.isEmpty()) {
          throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "执行方签核凭证必须填执行方");
        }
        weightKg = null;
        photoUrl = "";
        certificateNo = "";
      }
      default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "未知的凭证种类");
    }
    OffsetDateTime occurredAt = request.occurredAt() == null ? now() : request.occurredAt();
    UUID id = Ids.next();
    var inserted =
        sql.fetchOne(
            "insert into return_disposal_evidence"
                + " (id,tenant_id,disposal_order_id,evidence_kind,photo_url,weight_kg,"
                + "  certificate_no,executor_name,occurred_at,note)"
                + " values(?::uuid,?::uuid,?::uuid,?,?,?::numeric,?,?,?::timestamptz,?)"
                + " on conflict (tenant_id,disposal_order_id,evidence_kind) do update set"
                + "  photo_url=excluded.photo_url, weight_kg=excluded.weight_kg,"
                + "  certificate_no=excluded.certificate_no, executor_name=excluded.executor_name,"
                + "  occurred_at=excluded.occurred_at, note=excluded.note"
                + " returning id",
            id,
            tenant,
            orderId,
            kind,
            photoUrl,
            weightKg,
            certificateNo,
            executorName,
            occurredAt,
            text(request.note()));
    UUID evidenceId = inserted == null ? id : inserted.get(0, UUID.class);
    event(
        tenant,
        "EVIDENCE_RECORDED",
        orderId,
        evidenceId,
        null,
        null,
        null,
        "登记凭证：" + evidenceKindLabel(kind));
    return detailOf(tenant, orderId);
  }

  /** 记一笔销毁费用（合计不落表，查询时 SUM 现算）。 */
  public ReturnDisposalDetail recordCost(UUID orderId, RecordDisposalCostRequest request) {
    UUID tenant = currentTenant();
    if (request == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "必须给出费用内容");
    }
    Record row = requireOrder(tenant, orderId);
    String status = row.get("status", String.class);
    if ("CANCELLED".equals(status)) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "销毁单已取消：不能再记费用");
    }
    String costType =
        enumCode(
            request.costType(),
            DisposalCostType.values(),
            "费用类型",
            "销毁费 / 上门取件费 / 运输费 / 标签处理费 / 其他");
    BigDecimal amount = nonNegative(request.amount(), "费用金额");
    String currencyCode =
        request.currency() == null || text(request.currency()).isEmpty()
            ? row.get("cost_currency", String.class)
            : currency(request.currency(), "费用币种");
    // 🔴 混币种的「合计」是没有意义的数字：服务端先拒一次，DB 触发器再钉一道
    if (!currencyCode.equals(row.get("cost_currency", String.class))) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "费用币种 " + currencyCode + " 与销毁单的费用币种 "
              + row.get("cost_currency", String.class) + " 不一致：混币种不能相加");
    }
    OffsetDateTime incurredAt = request.incurredAt() == null ? now() : request.incurredAt();
    UUID id = Ids.next();
    sql.execute(
        "insert into return_disposal_cost"
            + " (id,tenant_id,disposal_order_id,cost_type,amount,currency,vendor_ref,incurred_at,note)"
            + " values(?::uuid,?::uuid,?::uuid,?,?::numeric,?,?,?::timestamptz,?)",
        id,
        tenant,
        orderId,
        costType,
        amount,
        currencyCode,
        text(request.vendorRef()),
        incurredAt,
        text(request.note()));
    event(tenant, "COST_RECORDED", orderId, null, id, null, null, "记销毁费用 " + amount + " " + currencyCode);
    return detailOf(tenant, orderId);
  }

  /**
   * 生成索赔依据。
   *
   * <p>🔴 数量与重量**必须与销毁单逐字相同**（DB 触发器钉死）——
   * 索赔依据不能是「另一个口径的数量」。
   */
  public ReturnDisposalDetail createClaimBasis(UUID orderId, CreateDisposalClaimBasisRequest request) {
    UUID tenant = currentTenant();
    if (request == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "必须给出索赔依据内容");
    }
    Record row = requireOrder(tenant, orderId);
    if (!"DISPOSED".equals(row.get("status", String.class))) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "只有**已销毁**的货才能作为索赔依据");
    }
    String claimType =
        enumCode(request.claimType(), ClaimType.values(), "索赔类型", "品牌方索赔 / 供应商索赔");
    String counterparty = text(request.counterpartyCode());
    if (counterparty.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "必须写明索赔对方（品牌方 / 供应商编码）");
    }
    BigDecimal claimedAmount = nonNegative(zero(request.claimedAmount()), "索赔金额");
    String claimedCurrency =
        request.claimedCurrency() == null || text(request.claimedCurrency()).isEmpty()
            ? row.get("cost_currency", String.class)
            : currency(request.claimedCurrency(), "索赔币种");
    var existing =
        sql.fetchOne(
            "select id from return_disposal_claim_basis where tenant_id=?::uuid and disposal_order_id=?::uuid",
            tenant,
            orderId);
    if (existing != null) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "这条销毁单已经有索赔依据了：同一批货不能被拿去索赔两次");
    }
    UUID id = Ids.next();
    sql.execute(
        "insert into return_disposal_claim_basis"
            + " (id,tenant_id,disposal_order_id,claim_type,counterparty_code,claim_qty,"
            + "  claim_weight_kg,claimed_amount,claimed_currency,source_reference,note)"
            + " values(?::uuid,?::uuid,?::uuid,?,?,?::numeric,?::numeric,?::numeric,?,?,?)",
        id,
        tenant,
        orderId,
        claimType,
        counterparty,
        zero(row.get("disposal_qty", BigDecimal.class)),
        zero(row.get("weight_kg", BigDecimal.class)),
        claimedAmount,
        claimedCurrency,
        row.get("source_reference", String.class),
        text(request.note()));
    event(
        tenant,
        "CLAIM_BASIS_CREATED",
        orderId,
        null,
        null,
        id,
        null,
        "生成索赔依据（" + claimTypeLabel(claimType) + " / " + counterparty + "）");
    return detailOf(tenant, orderId);
  }

  /** 提交下游索赔（回填下游索赔单号；🔴 下游在 trade，本模块不写它的表）。 */
  public ReturnDisposalDetail submitClaimBasis(UUID claimId, SubmitDisposalClaimBasisRequest request) {
    UUID tenant = currentTenant();
    String claimRef = request == null ? "" : text(request.claimRef());
    if (claimRef.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "必须回填下游索赔单号（claimRef）");
    }
    Record row = requireClaimBasis(tenant, claimId);
    if (!"PENDING".equals(row.get("status", String.class))) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "索赔依据当前是「" + claimStatusLabel(row.get("status", String.class)) + "」，不能重复提交");
    }
    sql.execute(
        "update return_disposal_claim_basis set status='SUBMITTED', claim_ref=?,"
            + " submitted_at=?::timestamptz, submitted_by=?::uuid, note=?, updated_at=?::timestamptz"
            + " where tenant_id=?::uuid and id=?::uuid",
        claimRef,
        now(),
        actorOrFail(),
        request == null ? "" : text(request.note()),
        now(),
        tenant,
        claimId);
    event(
        tenant,
        "CLAIM_SUBMITTED",
        row.get("disposal_order_id", UUID.class),
        null,
        null,
        claimId,
        null,
        "提交下游索赔，单号 " + claimRef);
    return detailOf(tenant, row.get("disposal_order_id", UUID.class));
  }

  /** 下游驳回索赔依据。 */
  public ReturnDisposalDetail rejectClaimBasis(UUID claimId, RejectDisposalClaimBasisRequest request) {
    UUID tenant = currentTenant();
    String reason = request == null ? "" : text(request.reason());
    if (reason.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "驳回必须写明理由");
    }
    Record row = requireClaimBasis(tenant, claimId);
    if (!"PENDING".equals(row.get("status", String.class))) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "索赔依据当前是「" + claimStatusLabel(row.get("status", String.class)) + "」，不能重复驳回");
    }
    sql.execute(
        "update return_disposal_claim_basis set status='REJECTED', reject_reason=?,"
            + " updated_at=?::timestamptz where tenant_id=?::uuid and id=?::uuid",
        reason,
        now(),
        tenant,
        claimId);
    event(
        tenant,
        "CLAIM_REJECTED",
        row.get("disposal_order_id", UUID.class),
        null,
        null,
        claimId,
        null,
        "下游驳回：" + reason);
    return detailOf(tenant, row.get("disposal_order_id", UUID.class));
  }

  private Record requireClaimBasis(UUID tenant, UUID claimId) {
    Record row =
        sql.fetchOne(
            "select id,disposal_order_id,status,claim_ref,note from return_disposal_claim_basis"
                + " where tenant_id=?::uuid and id=?::uuid",
            tenant,
            claimId);
    if (row == null) {
      throw notFound("索赔依据");
    }
    return row;
  }

  private ReturnDisposalClaimBasisRow claimBasisRow(Record r) {
    String claimType = r.get("claim_type", String.class);
    String status = r.get("status", String.class);
    return new ReturnDisposalClaimBasisRow(
        r.get("id", UUID.class),
        r.get("disposal_order_id", UUID.class),
        claimType,
        claimTypeLabel(claimType),
        r.get("counterparty_code", String.class),
        zero(r.get("claim_qty", BigDecimal.class)),
        zero(r.get("claim_weight_kg", BigDecimal.class)),
        zero(r.get("claimed_amount", BigDecimal.class)),
        r.get("claimed_currency", String.class),
        Boolean.TRUE.equals(r.get("evidence_complete", Boolean.class)),
        r.get("source_reference", String.class),
        r.get("claim_ref", String.class),
        status,
        claimStatusLabel(status),
        r.get("submitted_at", OffsetDateTime.class),
        r.get("reject_reason", String.class),
        r.get("note", String.class),
        r.get("created_at", OffsetDateTime.class));
  }

  /**
   * 执行销毁（完成态）。
   *
   * <p>🔴 <b>凭证不齐 ⇒ 不许完成</b>：这里先核对一次并给出**缺哪几样**，
   * DB 触发器 {@code guard_return_disposal_order_state} 再钉一道 ——
   * 绕过服务层直接写 SQL 也进不去完成态。
   */
  public ReturnDisposalDetail dispose(UUID orderId, DisposeDisposalRequest request) {
    UUID tenant = currentTenant();
    Record row = requireOrder(tenant, orderId);
    String status = row.get("status", String.class);
    if (!"SCHEDULED".equals(status)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "销毁单当前是「" + statusLabel(status) + "」：只有已排期的销毁单可以执行销毁");
    }
    String executorName = request == null ? "" : text(request.executorName());
    if (executorName.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "必须写明执行方");
    }
    UUID executedBy = request == null || request.executedBy() == null ? actorOrFail() : request.executedBy();
    OffsetDateTime disposedAt = request == null || request.disposedAt() == null ? now() : request.disposedAt();

    List<String> missing = missingEvidence(tenant, orderId, row);
    if (!missing.isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "销毁凭证不齐，不能置为完成态：还缺「" + String.join(" / ", missing) + "」。"
              + "凭证缺失会触发品牌方追责，所以这里直接拒绝。");
    }

    // 重量以 WEIGHT 凭证的称重读数为唯一来源
    BigDecimal weight = evidenceWeight(tenant, orderId);
    if (weight == null) {
      weight = request == null ? BigDecimal.ZERO : nonNegative(zero(request.weightKg()), "销毁重量");
    } else if (request != null && request.weightKg() != null
        && request.weightKg().compareTo(weight) != 0) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "重量凭证记录的是 " + weight + "，与填写的 " + request.weightKg() + " 不一致：一个数只留一个口径");
    }

    sql.execute(
        "update return_disposal_order set status='DISPOSED', disposed_at=?::timestamptz,"
            + " executed_by=?::uuid, executor_name=?, weight_kg=?::numeric,"
            + " updated_at=?::timestamptz, version=version+1"
            + " where tenant_id=?::uuid and id=?::uuid",
        disposedAt,
        executedBy,
        executorName,
        weight,
        now(),
        tenant,
        orderId);
    event(
        tenant,
        "ORDER_DISPOSED",
        orderId,
        null,
        null,
        null,
        null,
        "执行销毁："
            + methodLabel(row.get("disposal_method", String.class))
            + " × "
            + zero(row.get("disposal_qty", BigDecimal.class))
            + "，重量 "
            + weight
            + "，执行方 "
            + executorName);
    return detailOf(tenant, orderId);
  }

  /** 列出还缺哪几类凭证（🔴 完成态的闸门，服务端先把话说明白）。 */
  private List<String> missingEvidence(UUID tenant, UUID orderId, Record order) {
    List<String> missing = new ArrayList<>();
    if (Boolean.TRUE.equals(order.get("require_photo", Boolean.class))
        && !hasEvidence(tenant, orderId, "PHOTO")) {
      missing.add(evidenceKindLabel("PHOTO"));
    }
    if (Boolean.TRUE.equals(order.get("require_weight", Boolean.class))
        && !hasEvidence(tenant, orderId, "WEIGHT")) {
      missing.add(evidenceKindLabel("WEIGHT"));
    }
    if (Boolean.TRUE.equals(order.get("require_certificate", Boolean.class))
        && !hasEvidence(tenant, orderId, "CERTIFICATE")) {
      missing.add(evidenceKindLabel("CERTIFICATE"));
    }
    if (Boolean.TRUE.equals(order.get("require_executor", Boolean.class))
        && !hasEvidence(tenant, orderId, "EXECUTOR")) {
      missing.add(evidenceKindLabel("EXECUTOR"));
    }
    return missing;
  }

  private boolean hasEvidence(UUID tenant, UUID orderId, String kind) {
    Record row =
        sql.fetchOne(
            "select id from return_disposal_evidence"
                + " where tenant_id=?::uuid and disposal_order_id=?::uuid and evidence_kind=?",
            tenant,
            orderId,
            kind);
    return row != null;
  }

  private BigDecimal evidenceWeight(UUID tenant, UUID orderId) {
    Record row =
        sql.fetchOne(
            "select weight_kg from return_disposal_evidence"
                + " where tenant_id=?::uuid and disposal_order_id=?::uuid and evidence_kind='WEIGHT'",
            tenant,
            orderId);
    return row == null ? null : row.get(0, BigDecimal.class);
  }

  /** 取消销毁单（必须写理由）。 */
  public ReturnDisposalDetail cancel(UUID orderId, CancelDisposalRequest request) {
    UUID tenant = currentTenant();
    String reason = request == null ? "" : text(request.reason());
    if (reason.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "取消销毁单必须写明理由");
    }
    Record row = requireOrder(tenant, orderId);
    String status = row.get("status", String.class);
    if ("DISPOSED".equals(status)) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "销毁单已完成：货已经销毁了，不能取消");
    }
    if ("CANCELLED".equals(status)) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "销毁单已经是「已取消」");
    }
    sql.execute(
        "update return_disposal_order set status='CANCELLED', cancelled_at=?::timestamptz,"
            + " cancelled_by=?::uuid, cancel_reason=?, updated_at=?::timestamptz, version=version+1"
            + " where tenant_id=?::uuid and id=?::uuid",
        now(),
        actorOrFail(),
        reason,
        now(),
        tenant,
        orderId);
    event(tenant, "ORDER_CANCELLED", orderId, null, null, null, null, "取消销毁单：" + reason);
    return detailOf(tenant, orderId);
  }

  // ==================================================================
  // 留痕
  // ==================================================================

  public ReturnDisposalEventPage events(UUID orderId, int page, int size) {
    UUID tenant = currentTenant();
    int limit = Math.min(Math.max(size, 1), 200);
    int offset = Math.max(page, 0) * limit;
    List<Object> args = new ArrayList<>();
    args.add(tenant);
    StringBuilder where = new StringBuilder(" where tenant_id=?::uuid");
    if (orderId != null) {
      where.append(" and order_id=?::uuid");
      args.add(orderId);
    }
    long total = count(sql.fetchOne("select count(*) from return_disposal_event" + where, args.toArray()));
    var rows =
        sql.fetch(
            "select id,event_type,order_id,evidence_id,cost_id,claim_basis_id,policy_id,actor_id,"
                + "occurred_at,detail from return_disposal_event"
                + where
                + " order by occurred_at desc, id desc limit ? offset ?",
            concat(args, limit, offset));
    List<ReturnDisposalEventRow> items = new ArrayList<>();
    for (Record r : rows) {
      items.add(eventRow(r));
    }
    return new ReturnDisposalEventPage(
        items,
        Math.max(page, 0),
        limit,
        total,
        now(),
        "销毁留痕是**追加式**的：改与删都被 DB 拒。");
  }

  private ReturnDisposalEventRow eventRow(Record r) {
    String eventType = r.get("event_type", String.class);
    return new ReturnDisposalEventRow(
        r.get("id", UUID.class),
        eventType,
        eventTypeLabel(eventType),
        r.get("order_id", UUID.class),
        r.get("evidence_id", UUID.class),
        r.get("cost_id", UUID.class),
        r.get("claim_basis_id", UUID.class),
        r.get("policy_id", UUID.class),
        r.get("actor_id", UUID.class),
        r.get("occurred_at", OffsetDateTime.class),
        r.get("detail", String.class));
  }

  /** 追加一条留痕（actor 由 DB 触发器盖章，客户端伪造不了「谁做的」）。 */
  private void event(
      UUID tenant,
      String eventType,
      UUID orderId,
      UUID evidenceId,
      UUID costId,
      UUID claimBasisId,
      UUID policyId,
      String detail) {
    sql.execute(
        "insert into return_disposal_event"
            + " (id,tenant_id,event_type,order_id,evidence_id,cost_id,claim_basis_id,policy_id,"
            + "  actor_id,occurred_at,detail)"
            + " values(?::uuid,?::uuid,?,?::uuid,?::uuid,?::uuid,?::uuid,?::uuid,?::uuid,?::timestamptz,?)",
        Ids.next(),
        tenant,
        eventType,
        orderId,
        evidenceId,
        costId,
        claimBasisId,
        policyId,
        actorOrFail(),
        now(),
        detail);
  }
}
