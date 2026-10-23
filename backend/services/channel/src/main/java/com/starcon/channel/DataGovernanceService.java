package com.starcon.channel;

import static com.starcon.channel.DataGovernanceModels.*;
import static com.starcon.channel.DataGovernanceRules.*;

import com.starcon.channel.DataGovernanceRules.Adjudication;
import com.starcon.channel.DataGovernanceRules.PolicyFact;
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
 * 16.6.2.1 数据出境与留存。
 *
 * <p>蓝图：「管理数据合规：跨境数据传输登记与合法性基础、数据留存期配置、到期清理与删除请求（被遗忘权）、
 * 保留期与财务留存期的区分。输入：数据流登记；留存策略；删除请求。
 * 输出：合规登记 + 清理/删除执行记录。关系：上游 = 17 权限、全部数据域；下游 = 数据清理任务。
 * <b>财务数据有法定留存期，PII 有 GDPR 删除权，两者冲突时按财务留存优先</b>——实体目录 §5 已明确这条边界。」
 *
 * <p>🔴🔴 本类里有<b>七条结构上的不变量</b>，每一条都对应蓝图或 AGENTS.md 的一条硬要求：
 *
 * <ol>
 *   <li><b>「全部数据域」在本仓没有事实表 ⇒ 本模块自建</b>：{@code data_governance_data_domain}
 *       是本模块的核心事实表（登记侧），{@code retention_*} 是留存侧。
 *       上游没有现成事实 ⇒ 本模块就是这张表的写入所有者。</li>
 *   <li><b>两类留存期在数据模型上分开</b>：{@code retention_class ∈ {STANDARD, FINANCIAL}}。
 *       不是一个期限加一个 flag。</li>
 *   <li><b>「财务留存优先」是可判定的规则，不是一句说明</b>：
 *       生效留存期 = max(普通期, 财务期)；冲突时 {@code resolution = FINANCIAL_PREVAILS}；
 *       法定期没走完 ⇒ 删除请求判成 <b>DEFERRED（推迟）</b>，并给出
 *       {@code defer_until}（推迟到哪一天）。</li>
 *   <li><b>判不了 ≠ 默认放行</b>：数据域没有任何留存策略 ⇒ 审批删除请求时
 *       <b>422 拒绝</b>，而不是"没规矩就随便删"。</li>
 *   <li><b>与 16.6.1.1 的删除/导出审批语义对齐</b>（不造第二套冲突语义）：
 *       申请 → 审批 → 执行；<b>审批人 ≠ 申请人</b>；<b>理由必填</b>。</li>
 *   <li><b>本模块不跨服务删业务数据</b>：删除请求执行后产出的是
 *       <b>清理工单 + 执行记录</b>（可供下游数据清理任务消费的状态），
 *       而不是"已经删掉了"的假象。</li>
 *   <li><b>不留假财务数据</b>：12 财务（finance 服务）在本仓尚未落地 ⇒
 *       能力声明里 {@code available=false} + {@code ownerModule}，
 *       本模块只表达"这条是法定财务留存期"的<b>策略口径</b>。</li>
 * </ol>
 */
@Service
public class DataGovernanceService {

  // ================================================================ 口径文案

  private static final String TRANSFER_SEMANTICS =
      "🔴 **跨境数据传输必须登记，且必须写清合法性基础**："
          + "合法性基础是**受控值域**（同意 / 履行合同所必需 / 法定义务 / 正当利益），"
          + "**不是自由文本** —— 自由文本的合法性基础等于没登记（每条都能写「合规需要」）。"
          + "接收方法域是**两位法域码**，不是自由文本国家名"
          + "（自由文本会出现「德国 / DE / Germany / 欧盟」四种写法，跨境判定就没法做了）。"
          + "传输状态 ACTIVE → SUSPENDED → ENDED，**ENDED 是终态**；暂停与终止都**必须写理由**。";

  private static final String RETENTION_SEMANTICS =
      "🔴 **两类留存期在数据模型上就是两类东西**："
          + "STANDARD（普通留存期，租户自订）与 FINANCIAL（**法定**财务留存期）。"
          + "同一（数据域 × 类别）**同时只能有一条生效策略** —— "
          + "两条并存会让「生效留存期」变成「挑一条运气好的」。"
          + "改策略 = **旧条置 SUPERSEDED + 新条插入**（版本化），**不原地改写**；"
          + "生效时刻**不许回填**（回填会把过去的判定全部推翻）。"
          + "留存天数是**数量** ⇒ NUMERIC(19,6)，禁浮点。";

  private static final String CONFLICT_SEMANTICS =
      "🔴 **保留期与财务留存期冲突时按财务留存优先**（实体目录 §5）。"
          + "生效留存期 = **两者取更长者**（法定留存期是**下限**，更短的普通期压不下去）；"
          + "裁决结果取值：FINANCIAL_PREVAILS（财务留存优先）/ STANDARD_APPLIES / NO_CONFLICT。"
          + "法定留存期**没走完**时，删除请求判成 **DEFERRED（推迟）而不是 REJECTED（拒绝）** —— "
          + "「被推迟到某天」与「被拒」在合规台账上是两件完全不同的事。"
          + "推迟到哪一天由**裁决结果**给出（财务策略生效日 + 财务留存天数）。"
          + "🔴 一个数据域**没登记任何留存策略** ⇒ **判不了** ⇒ 拒绝执行删除（不是默认放行）。";

  private static final String DELETION_SEMANTICS =
      "🔴 **删除请求走三段式**：申请 → 审批 → 执行，"
          + "**审批人 ≠ 申请人**（自己批自己的审批形同虚设），**理由必填**。"
          + "这与 16.6.1.1 的导出审批是**同一套语义**，不是第二套。"
          + "审批时会做**财务留存优先**的裁决：法定留存期没走完 ⇒ 判成 DEFERRED，"
          + "并写明**推迟到哪一天、为什么**。"
          + "🔴 **本模块没有「直接删除」端点**：物理删除跨服务做不了，"
          + "执行删除请求产出的是**清理工单**（交给下游数据清理任务）。";

  private static final String CLEANUP_SEMANTICS =
      "🔴 **到期清理产出工单 + 执行记录，不跨服务删业务数据**"
          + "（AGENTS.md 模块边界纪律：每张事实表只有一个写入所有者，跨服务不得直接删别人的表）。"
          + "工单上有两个时点：<b>eligible_at（最早可清理日）</b>与<b>due_at（完成截止日）</b>，"
          + "且 due_at >= eligible_at —— 这把「财务留存优先」在清理路径上也钉了一道："
          + "**不许把财务数据的清理排到法定留存期走完之前**。"
          + "执行记录 **append-only**（只给 SELECT,INSERT，触发器禁止 UPDATE/DELETE）。";

  private static final String AUDIT_SEMANTICS =
      "🔴 **裁决与执行都留痕**：每次真做过一次留存期裁决，就往 "
          + "`retention_conflict` 台账写一行（append-only）；"
          + "每次清理执行都往 `retention_cleanup_record` 写一行（append-only）。"
          + "删除请求的申请 / 审批 / 推迟 / 执行四段都带**操作人 + 时刻 + 理由**。"
          + "⚠️ **实时生效的冲突清单是现算的**（GET /retention-conflicts），"
          + "台账是**历史** —— 两者不是同一份事实的两个副本。";

  private static final String BOUNDARY_NOTE =
      "🔴 四条边界写死在能力声明里："
          + "① **本模块不删业务数据**（只出工单 + 执行记录）；"
          + "② **12 财务（finance 服务）在本仓尚未落地** ⇒ 财务明细与财务凭证取不到真值，"
          + "本模块只表达「这条是法定财务留存期」的**策略口径**；"
          + "③ **下游数据清理任务未交付** ⇒ 工单目前只落到本服务，"
          + "真正的物理删除要等下游；"
          + "④ **数据域清单是租户自维护的登记**，不是自动发现 —— "
          + "本仓没有任何服务提供「全量数据域」的枚举接口。";

  private static final String DOWNSTREAM_NOTE =
      "🔴 下游 = 数据清理任务（**本仓尚未交付**）。"
          + "本模块产出**清理工单 + 状态**，由下游消费后回报执行结果；"
          + "在下游落地之前，「已执行」= **已派发清理工单**，**不等于物理数据已删**。";

  // ================================================================ 依赖

  private final DSLContext sql;

  public DataGovernanceService(DSLContext sql) {
    this.sql = sql;
  }

  // ================================================================ 能力声明

  /** 能力声明：上游可用性、登记口径、留存口径、冲突裁决口径、删除与清理口径。 */
  public DataGovernanceCapabilities capabilities() {
    List<DataGovernanceUpstreamState> upstreams =
        List.of(
            new DataGovernanceUpstreamState(
                "IDENTITY_PERMISSION",
                "17 权限 · 角色 / 页面权限 / 数据范围（identity）",
                true,
                "",
                "已交付。本模块的**删除请求审批人**直接取自这里的角色与数据范围 —— "
                    + "租户也**只**从已认证成员关系解析，不信任 X-Tenant-Id 头。"),
            new DataGovernanceUpstreamState(
                "DATA_DOMAIN_REGISTRY",
                "全部数据域 · 数据域登记表（本模块自建）",
                true,
                "16.6.2.1 数据出境与留存",
                "🔴 **上游在本仓没有任何现成事实表**（调度者已全仓 grep 确认），"
                    + "所以本模块**自建** `data_governance_data_domain` 作为核心事实表 —— "
                    + "本模块就是这张表的写入所有者。"
                    + "它是**租户自维护的登记**，不是自动发现："
                    + "本仓没有任何服务提供「全量数据域」的枚举接口。"),
            new DataGovernanceUpstreamState(
                "FINANCE_RETENTION_DETAIL",
                "12 财务 · 财务留存期明细与凭证（finance）",
                false,
                "12 财务（finance 服务）",
                "🔴 **finance 服务在本仓尚未落地** ⇒ 财务留存期的**明细**（逐类法定期限、凭证）取不到真值。"
                    + "本模块只表达**策略口径**：一条留存策略被登记为 FINANCIAL，"
                    + "它就是「这条按法定财务留存期处理」的本地声明。"
                    + "真正的财务数据由 finance 服务落地后由它提供 —— "
                    + "本模块**不建**财务凭证表、**不声称**费用已入账。"),
            new DataGovernanceUpstreamState(
                "CLEANUP_TASK_RUNNER",
                "下游 · 数据清理任务（执行物理删除）",
                false,
                "数据清理任务（本仓尚未交付）",
                "🔴 **下游尚未交付** ⇒ 工单目前只落到本服务。"
                    + "本模块产出**清理工单 + 状态**，真正的物理删除由下游跨服务执行 —— "
                    + "本模块**不跨服务删业务数据**（那是别人的表、别人的写入所有者）。"));

    return new DataGovernanceCapabilities(
        upstreams,
        TRANSFER_SEMANTICS,
        RETENTION_SEMANTICS,
        CONFLICT_SEMANTICS,
        DELETION_SEMANTICS,
        CLEANUP_SEMANTICS,
        AUDIT_SEMANTICS,
        BOUNDARY_NOTE,
        DOWNSTREAM_NOTE);
  }

  // ================================================================ 数据域

  /** 数据域列表（真分页 + 真筛选）。 */
  public DataGovernanceDomainPage domains(String domainCode, int page, int size) {
    UUID tenant = currentTenant();
    int safePage = Math.max(0, page);
    int safeSize = Math.min(100, Math.max(1, size));
    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    where.add("tenant_id = ?");
    binds.add(tenant);
    if (domainCode != null && !domainCode.isBlank()) {
      where.add("domain_code = ?");
      binds.add(domainCode.trim());
    }
    String clause = " where " + String.join(" and ", where);

    long total =
        ((Number) sql.fetchValue("select count(*) from data_governance_data_domain" + clause, binds.toArray()))
            .longValue();

    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(safeSize);
    pageBinds.add((long) safePage * safeSize);
    List<DataGovernanceDomainView> items = new ArrayList<>();
    for (Record row :
        sql.fetch(
            "select id, domain_code, domain_name, owner_module, data_class, contains_pii,"
                + " statutory_financial, note, version, created_at, updated_at"
                + " from data_governance_data_domain"
                + clause
                + " order by domain_code limit ? offset ?",
            pageBinds.toArray())) {
      items.add(domainView(row));
    }
    return new DataGovernanceDomainPage(items, safePage, safeSize, total);
  }

  /** 登记 / 修改数据域（upsert，按 domain_code）。 */
  public DataGovernanceDomainView saveDomain(DataGovernanceDomainInput input) {
    UUID tenant = currentTenant();
    String code = input.domainCode().trim();
    Record existing =
        sql.fetchOne(
            "select id, version from data_governance_data_domain"
                + " where tenant_id = ? and domain_code = ?",
            tenant,
            code);
    UUID id = existing == null ? Ids.next() : existing.get("id", UUID.class);
    OffsetDateTime now = dbNow();
    if (existing == null) {
      sql.execute(
          """
          insert into data_governance_data_domain
            (id, tenant_id, domain_code, domain_name, owner_module, data_class,
             contains_pii, statutory_financial, note, version, created_at, updated_at)
          values (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?::timestamptz, ?::timestamptz)
          """,
          id,
          tenant,
          code,
          input.domainName().trim(),
          input.ownerModule().trim(),
          input.dataClass().trim(),
          Boolean.TRUE.equals(input.containsPii()),
          Boolean.TRUE.equals(input.statutoryFinancial()),
          nz(input.note()),
          now,
          now);
    } else {
      sql.execute(
          """
          update data_governance_data_domain
             set domain_name = ?, owner_module = ?, data_class = ?, contains_pii = ?,
                 statutory_financial = ?, note = ?, version = version + 1,
                 updated_at = ?::timestamptz
           where tenant_id = ? and id = ?
          """,
          input.domainName().trim(),
          input.ownerModule().trim(),
          input.dataClass().trim(),
          Boolean.TRUE.equals(input.containsPii()),
          Boolean.TRUE.equals(input.statutoryFinancial()),
          nz(input.note()),
          now,
          tenant,
          id);
    }
    return domainById(id);
  }

  private DataGovernanceDomainView domainById(UUID id) {
    Record row =
        sql.fetchOne(
            "select id, domain_code, domain_name, owner_module, data_class, contains_pii,"
                + " statutory_financial, note, version, created_at, updated_at"
                + " from data_governance_data_domain where tenant_id = ? and id = ?",
            currentTenant(),
            id);
    if (row == null) {
      throw notFound("数据域不存在");
    }
    return domainView(row);
  }

  private DataGovernanceDomainView domainView(Record row) {
    String dataClass = row.get("data_class", String.class);
    return new DataGovernanceDomainView(
        row.get("id", UUID.class),
        row.get("domain_code", String.class),
        row.get("domain_name", String.class),
        row.get("owner_module", String.class),
        dataClass,
        dataClassLabel(dataClass),
        Boolean.TRUE.equals(row.get("contains_pii", Boolean.class)),
        Boolean.TRUE.equals(row.get("statutory_financial", Boolean.class)),
        row.get("note", String.class),
        row.get("version", Long.class),
        row.get("created_at", OffsetDateTime.class),
        row.get("updated_at", OffsetDateTime.class));
  }

  private static String dataClassLabel(String dataClass) {
    switch (dataClass == null ? "" : dataClass) {
      case "PII":
        return "个人信息";
      case "FINANCIAL":
        return "财务数据";
      case "OPERATIONAL":
        return "运营数据";
      case "MARKETING":
        return "营销数据";
      default:
        return dataClass == null || dataClass.isEmpty() ? "未分类" : dataClass;
    }
  }

  // ================================================================ 跨境传输

  /** 跨境传输列表（真分页 + 真筛选 + 汇总）。 */
  public DataGovernanceTransferPage transfers(
      String status, String domainCode, int page, int size) {
    UUID tenant = currentTenant();
    int safePage = Math.max(0, page);
    int safeSize = Math.min(100, Math.max(1, size));
    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    where.add("tenant_id = ?");
    binds.add(tenant);
    if (status != null && !status.isBlank()) {
      where.add("status = ?");
      binds.add(status.trim().toUpperCase(Locale.ROOT));
    }
    if (domainCode != null && !domainCode.isBlank()) {
      where.add("source_domain_code = ?");
      binds.add(domainCode.trim());
    }
    String clause = " where " + String.join(" and ", where);

    long total =
        ((Number)
                sql.fetchValue("select count(*) from data_governance_transfer" + clause, binds.toArray()))
            .longValue();

    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(safeSize);
    pageBinds.add((long) safePage * safeSize);
    List<DataGovernanceTransferView> items = new ArrayList<>();
    for (Record row :
        sql.fetch(
            "select id, transfer_no, source_domain_code, target_region, recipient, legal_basis,"
                + " safeguards, tia_ref, status, registered_by, registered_at, effective_from,"
                + " effective_to, suspend_reason, end_reason, note, version, created_at, updated_at"
                + " from data_governance_transfer"
                + clause
                + " order by registered_at desc limit ? offset ?",
            pageBinds.toArray())) {
      items.add(transferView(row));
    }

    long active =
        countTransfer(tenant, "status = 'ACTIVE'");
    long suspended =
        countTransfer(tenant, "status = 'SUSPENDED'");
    long ended =
        countTransfer(tenant, "status = 'ENDED'");
    return new DataGovernanceTransferPage(
        items, safePage, safeSize, total, new DataGovernanceTransferCounts(total, active, suspended, ended));
  }

  private long countTransfer(UUID tenant, String extra) {
    Object raw =
        sql.fetchValue(
            "select count(*) from data_governance_transfer where tenant_id = ? and " + extra, tenant);
    return ((Number) raw).longValue();
  }

  /** 登记一条跨境数据传输。 */
  public DataGovernanceTransferView createTransfer(
      AccessService.Access actor, DataGovernanceTransferInput input) {
    UUID tenant = currentTenant();
    requireDomain(input.sourceDomainCode());
    OffsetDateTime now = dbNow();
    UUID id = Ids.next();
    sql.execute(
        """
        insert into data_governance_transfer
          (id, tenant_id, transfer_no, source_domain_code, target_region, recipient,
           legal_basis, safeguards, tia_ref, status, registered_by, registered_at,
           effective_from, effective_to, suspend_reason, end_reason, note, version,
           created_at, updated_at)
        values (?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?::timestamptz, ?::timestamptz, null,
                '', '', ?, 0, ?::timestamptz, ?::timestamptz)
        """,
        id,
        tenant,
        transferNo("TR", id),
        input.sourceDomainCode().trim(),
        input.targetRegion().trim().toUpperCase(Locale.ROOT),
        input.recipient().trim(),
        input.legalBasis().trim().toUpperCase(Locale.ROOT),
        nz(input.safeguards()),
        nz(input.tiaRef()),
        actor.userId(),
        now,
        input.effectiveFrom(),
        nz(input.note()),
        now,
        now);
    return transferById(id);
  }

  /** 单条跨境传输。 */
  public DataGovernanceTransferView transfer(String transferId) {
    UUID target = parseUuid(transferId);
    if (target == null) {
      throw notFound("跨境传输登记不存在");
    }
    return transferById(target);
  }

  /** 暂停一条传输（理由必填）。 */
  public DataGovernanceTransferView suspendTransfer(
      AccessService.Access actor, String transferId, DataGovernanceTransferStatusInput input) {
    UUID tenant = currentTenant();
    UUID target = parseUuid(transferId);
    if (target == null) {
      throw notFound("跨境传输登记不存在");
    }
    Record row =
        sql.fetchOne(
            "select id, status from data_governance_transfer where tenant_id = ? and id = ?",
            tenant,
            target);
    if (row == null) {
      throw notFound("跨境传输登记不存在");
    }
    String status = row.get("status", String.class);
    if ("ENDED".equals(status)) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "这条传输已经终止了，不能再暂停");
    }
    if ("SUSPENDED".equals(status)) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "这条传输已经是暂停状态");
    }
    OffsetDateTime now = dbNow();
    sql.execute(
        """
        update data_governance_transfer
           set status = 'SUSPENDED', suspend_reason = ?, version = version + 1,
               updated_at = ?::timestamptz
         where tenant_id = ? and id = ?
        """,
        input.reason().trim(),
        now,
        tenant,
        target);
    return transferById(target);
  }

  /** 终止一条传输（理由必填；ENDED 是终态）。 */
  public DataGovernanceTransferView endTransfer(
      AccessService.Access actor, String transferId, DataGovernanceTransferStatusInput input) {
    UUID tenant = currentTenant();
    UUID target = parseUuid(transferId);
    if (target == null) {
      throw notFound("跨境传输登记不存在");
    }
    Record row =
        sql.fetchOne(
            "select id, status from data_governance_transfer where tenant_id = ? and id = ?",
            tenant,
            target);
    if (row == null) {
      throw notFound("跨境传输登记不存在");
    }
    if ("ENDED".equals(row.get("status", String.class))) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "这条传输已经终止过了");
    }
    OffsetDateTime now = dbNow();
    sql.execute(
        """
        update data_governance_transfer
           set status = 'ENDED', end_reason = ?, effective_to = ?::timestamptz,
               version = version + 1, updated_at = ?::timestamptz
         where tenant_id = ? and id = ?
        """,
        input.reason().trim(),
        now,
        now,
        tenant,
        target);
    return transferById(target);
  }

  private DataGovernanceTransferView transferById(UUID id) {
    Record row =
        sql.fetchOne(
            "select id, transfer_no, source_domain_code, target_region, recipient, legal_basis,"
                + " safeguards, tia_ref, status, registered_by, registered_at, effective_from,"
                + " effective_to, suspend_reason, end_reason, note, version, created_at, updated_at"
                + " from data_governance_transfer where tenant_id = ? and id = ?",
            currentTenant(),
            id);
    if (row == null) {
      throw notFound("跨境传输登记不存在");
    }
    return transferView(row);
  }

  private DataGovernanceTransferView transferView(Record row) {
    String domainCode = row.get("source_domain_code", String.class);
    String basis = row.get("legal_basis", String.class);
    String status = row.get("status", String.class);
    return new DataGovernanceTransferView(
        row.get("id", UUID.class),
        row.get("transfer_no", String.class),
        domainCode,
        domainName(domainCode),
        row.get("target_region", String.class),
        row.get("recipient", String.class),
        basis,
        legalBasisLabel(basis),
        row.get("safeguards", String.class),
        row.get("tia_ref", String.class),
        status,
        transferStatusLabel(status),
        row.get("registered_by", UUID.class),
        "",
        row.get("registered_at", OffsetDateTime.class),
        row.get("effective_from", OffsetDateTime.class),
        row.get("effective_to", OffsetDateTime.class),
        row.get("suspend_reason", String.class),
        row.get("end_reason", String.class),
        row.get("note", String.class),
        row.get("version", Long.class),
        row.get("created_at", OffsetDateTime.class),
        row.get("updated_at", OffsetDateTime.class));
  }

  // ================================================================ 留存策略

  /** 留存策略列表（真分页 + 真筛选）。 */
  public RetentionPolicyPage policies(String domainCode, int page, int size) {
    UUID tenant = currentTenant();
    int safePage = Math.max(0, page);
    int safeSize = Math.min(100, Math.max(1, size));
    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    where.add("tenant_id = ?");
    binds.add(tenant);
    if (domainCode != null && !domainCode.isBlank()) {
      where.add("domain_code = ?");
      binds.add(domainCode.trim());
    }
    String clause = " where " + String.join(" and ", where);

    long total =
        ((Number) sql.fetchValue("select count(*) from retention_policy" + clause, binds.toArray()))
            .longValue();

    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(safeSize);
    pageBinds.add((long) safePage * safeSize);
    List<RetentionPolicyView> items = new ArrayList<>();
    for (Record row :
        sql.fetch(
            "select id, domain_code, policy_name, retention_class, retention_days,"
                + " retention_trigger, legal_basis, effective_from, effective_to, status,"
                + " supersedes_id, version_no, created_by, created_at"
                + " from retention_policy"
                + clause
                + " order by domain_code, retention_class, version_no desc limit ? offset ?",
            pageBinds.toArray())) {
      items.add(policyView(row));
    }
    return new RetentionPolicyPage(items, safePage, safeSize, total);
  }

  /**
   * 登记一条留存策略（**新增一个版本**）。
   *
   * <p>🔴 同一（数据域 × 类别）已有的生效策略会被置为 SUPERSEDED，
   * 新版本 {@code supersedes_id} 指向它、{@code version_no} 递增。
   * **不原地改写** —— 改策略不能追溯改变历史判定。
   */
  public RetentionPolicyView createPolicy(AccessService.Access actor, RetentionPolicyInput input) {
    UUID tenant = currentTenant();
    String domainCode = input.domainCode().trim();
    String retentionClass = input.retentionClass().trim().toUpperCase(Locale.ROOT);
    requireDomain(domainCode);

    OffsetDateTime now = dbNow();
    if (input.effectiveFrom().isBefore(now)) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "留存策略的生效时刻不许回填（回填会把过去的判定全部推翻）—— 请填一个不早于当前时刻的时间");
    }
    // 🔴 留存天数必须是**整天**（DB 层也有 CHECK，服务层先给人话）。
    if (input.retentionDays().stripTrailingZeros().scale() > 0) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY, "留存天数必须是整天（不留小数）");
    }

    Record existing =
        sql.fetchOne(
            "select id, version_no, effective_from from retention_policy"
                + " where tenant_id = ? and domain_code = ? and retention_class = ?"
                + " and status = 'ACTIVE'",
            tenant,
            domainCode,
            retentionClass);
    UUID id = Ids.next();
    int versionNo = 1;
    UUID supersedes = null;
    if (existing != null) {
      versionNo = existing.get("version_no", Integer.class) + 1;
      supersedes = existing.get("id", UUID.class);
      OffsetDateTime previousFrom = existing.get("effective_from", OffsetDateTime.class);
      if (!input.effectiveFrom().isAfter(previousFrom)) {
        throw new ResponseStatusException(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "新版本的生效时刻必须晚于旧版本（" + previousFrom.toLocalDate() + "）—— 策略不能回溯改写");
      }
      sql.execute(
          """
          update retention_policy
             set status = 'SUPERSEDED', effective_to = ?::timestamptz, updated_at = ?::timestamptz
           where tenant_id = ? and id = ?
          """,
          input.effectiveFrom(),
          now,
          tenant,
          supersedes);
    }
    sql.execute(
        """
        insert into retention_policy
          (id, tenant_id, domain_code, policy_name, retention_class, retention_days,
           retention_trigger, legal_basis, effective_from, effective_to, status,
           supersedes_id, version_no, created_by, created_at, updated_at)
        values (?, ?, ?, ?, ?, ?, ?, ?, ?::timestamptz, null, 'ACTIVE', ?, ?, ?, ?::timestamptz,
                ?::timestamptz)
        """,
        id,
        tenant,
        domainCode,
        input.policyName().trim(),
        retentionClass,
        input.retentionDays(),
        input.retentionTrigger().trim().toUpperCase(Locale.ROOT),
        nz(input.legalBasis()),
        input.effectiveFrom(),
        supersedes,
        versionNo,
        actor.userId(),
        now,
        now);
    return policyById(id);
  }

  private RetentionPolicyView policyById(UUID id) {
    Record row =
        sql.fetchOne(
            "select id, domain_code, policy_name, retention_class, retention_days,"
                + " retention_trigger, legal_basis, effective_from, effective_to, status,"
                + " supersedes_id, version_no, created_by, created_at"
                + " from retention_policy where tenant_id = ? and id = ?",
            currentTenant(),
            id);
    if (row == null) {
      throw notFound("留存策略不存在");
    }
    return policyView(row);
  }

  private RetentionPolicyView policyView(Record row) {
    String domainCode = row.get("domain_code", String.class);
    String retentionClass = row.get("retention_class", String.class);
    String trigger = row.get("retention_trigger", String.class);
    String status = row.get("status", String.class);
    return new RetentionPolicyView(
        row.get("id", UUID.class),
        domainCode,
        domainName(domainCode),
        row.get("policy_name", String.class),
        retentionClass,
        retentionClassLabel(retentionClass),
        row.get("retention_days", BigDecimal.class),
        trigger,
        retentionTriggerLabel(trigger),
        row.get("legal_basis", String.class),
        row.get("effective_from", OffsetDateTime.class),
        row.get("effective_to", OffsetDateTime.class),
        status,
        policyStatusLabel(status),
        row.get("supersedes_id", UUID.class),
        row.get("version_no", Integer.class),
        row.get("created_by", UUID.class),
        row.get("created_at", OffsetDateTime.class));
  }

  private static String policyStatusLabel(String status) {
    switch (status == null ? "" : status) {
      case "ACTIVE":
        return "生效中";
      case "SUPERSEDED":
        return "已被新版本取代";
      case "DISABLED":
        return "已停用";
      default:
        return status == null || status.isEmpty() ? "未知" : status;
    }
  }

  // ================================================================ 冲突裁决（实时）

  /**
   * 留存期裁决清单（**实时算出来** —— 生效策略是唯一事实源）。
   *
   * <p>🔴 每个已登记的数据域都会出现在清单里，包括**一条策略都没登记**的
   * （{@code conflictKind = NO_POLICY}）—— 那是最需要被看见的一种状态：
   * 判不了，就别删。
   */
  public RetentionConflictPage conflicts(String domainCode, int page, int size) {
    UUID tenant = currentTenant();
    int safePage = Math.max(0, page);
    int safeSize = Math.min(100, Math.max(1, size));
    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    where.add("tenant_id = ?");
    binds.add(tenant);
    if (domainCode != null && !domainCode.isBlank()) {
      where.add("domain_code = ?");
      binds.add(domainCode.trim());
    }
    String clause = " where " + String.join(" and ", where);

    long total =
        ((Number)
                sql.fetchValue("select count(*) from data_governance_data_domain" + clause, binds.toArray()))
            .longValue();

    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(safeSize);
    pageBinds.add((long) safePage * safeSize);
    OffsetDateTime now = dbNow();

    List<RetentionConflictView> items = new ArrayList<>();
    long conflicting = 0;
    long blocked = 0;
    for (Record row :
        sql.fetch(
            "select domain_code, domain_name from data_governance_data_domain"
                + clause
                + " order by domain_code limit ? offset ?",
            pageBinds.toArray())) {
      String code = row.get("domain_code", String.class);
      Adjudication adjudication = adjudicate(code, now);
      boolean isConflict =
          KIND_FINANCIAL_LONGER.equals(adjudication.conflictKind())
              || KIND_STANDARD_LONGER.equals(adjudication.conflictKind());
      if (isConflict) {
        conflicting++;
      }
      if (adjudication.deletionBlocked()) {
        blocked++;
      }
      items.add(conflictView(code, row.get("domain_name", String.class), adjudication));
    }
    return new RetentionConflictPage(
        items,
        safePage,
        safeSize,
        total,
        new RetentionConflictCounts(total, conflicting, blocked));
  }

  /** 某个数据域的实时裁决（供删除请求审批等内部路径复用）。 */
  public Adjudication adjudicate(String domainCode, OffsetDateTime asOf) {
    List<PolicyFact> facts = new ArrayList<>();
    for (Record row :
        sql.fetch(
            "select id, retention_class, retention_days, legal_basis, effective_from"
                + " from retention_policy"
                + " where tenant_id = ? and domain_code = ? and status = 'ACTIVE'",
            currentTenant(),
            domainCode)) {
      facts.add(
          new PolicyFact(
              row.get("id", UUID.class),
              row.get("retention_class", String.class),
              row.get("retention_days", BigDecimal.class),
              row.get("legal_basis", String.class),
              row.get("effective_from", OffsetDateTime.class)));
    }
    // 🔴 显式限定：服务层的 adjudicate(String, OffsetDateTime) 与规则类的
    //   adjudicate(List<PolicyFact>, OffsetDateTime) 同名，不限定会解析到实例方法上。
    return DataGovernanceRules.adjudicate(facts, asOf);
  }

  private RetentionConflictView conflictView(
      String domainCode, String domainName, Adjudication adjudication) {
    OffsetDateTime lastAdjudicated = lastAdjudicatedAt(domainCode);
    return new RetentionConflictView(
        domainCode,
        domainName,
        adjudication.standardPolicy() == null ? null : adjudication.standardPolicy().policyId(),
        adjudication.standardPolicy() == null ? null : adjudication.standardPolicy().retentionDays(),
        adjudication.standardPolicy() == null ? "" : adjudication.standardPolicy().legalBasis(),
        adjudication.financialPolicy() == null ? null : adjudication.financialPolicy().policyId(),
        adjudication.financialPolicy() == null ? null : adjudication.financialPolicy().retentionDays(),
        adjudication.financialPolicy() == null ? "" : adjudication.financialPolicy().legalBasis(),
        adjudication.conflictKind(),
        conflictKindLabel(adjudication.conflictKind()),
        adjudication.resolution(),
        resolutionLabel(adjudication.resolution()),
        adjudication.effectiveRetentionDays(),
        adjudication.financialMaturityAt(),
        adjudication.deletionBlocked(),
        adjudication.rationale(),
        lastAdjudicated);
  }

  private OffsetDateTime lastAdjudicatedAt(String domainCode) {
    Object raw =
        sql.fetchValue(
            "select max(adjudicated_at) from retention_conflict where tenant_id = ? and domain_code = ?",
            currentTenant(),
            domainCode);
    return raw instanceof OffsetDateTime at ? at : null;
  }

  /** 把一次裁决写进台账（append-only）。 */
  private void recordAdjudication(
      AccessService.Access actor, String domainCode, Adjudication adjudication) {
    sql.execute(
        """
        insert into retention_conflict
          (id, tenant_id, domain_code, standard_policy_id, standard_retention_days,
           financial_policy_id, financial_retention_days, conflict_kind, resolution,
           effective_retention_days, deletion_blocked_until, rationale,
           adjudicated_at, adjudicated_by, created_at)
        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::timestamptz, ?, ?::timestamptz, ?, ?::timestamptz)
        """,
        Ids.next(),
        currentTenant(),
        domainCode,
        adjudication.standardPolicy() == null ? null : adjudication.standardPolicy().policyId(),
        adjudication.standardPolicy() == null ? null : adjudication.standardPolicy().retentionDays(),
        adjudication.financialPolicy() == null ? null : adjudication.financialPolicy().policyId(),
        adjudication.financialPolicy() == null ? null : adjudication.financialPolicy().retentionDays(),
        adjudication.conflictKind(),
        adjudication.resolution(),
        adjudication.effectiveRetentionDays(),
        adjudication.financialMaturityAt(),
        adjudication.rationale(),
        dbNow(),
        actor.userId(),
        dbNow());
  }

  // ================================================================ 删除请求

  /** 删除请求列表（真分页 + 真筛选 + 汇总）。 */
  public DataGovernanceDeletionPage deletionRequests(
      String status, String domainCode, int page, int size) {
    UUID tenant = currentTenant();
    int safePage = Math.max(0, page);
    int safeSize = Math.min(100, Math.max(1, size));
    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    where.add("tenant_id = ?");
    binds.add(tenant);
    if (status != null && !status.isBlank()) {
      where.add("status = ?");
      binds.add(status.trim().toUpperCase(Locale.ROOT));
    }
    if (domainCode != null && !domainCode.isBlank()) {
      where.add("domain_code = ?");
      binds.add(domainCode.trim());
    }
    String clause = " where " + String.join(" and ", where);

    long total =
        ((Number)
                sql.fetchValue("select count(*) from data_governance_deletion_request" + clause, binds.toArray()))
            .longValue();

    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(safeSize);
    pageBinds.add((long) safePage * safeSize);
    List<DataGovernanceDeletionRequestView> items = new ArrayList<>();
    for (Record row :
        sql.fetch(
            "select id, request_no, request_type, subject_kind, subject_ref, domain_code, reason,"
                + " status, requested_by, requested_at, approved_by, approved_at, decision_note,"
                + " executed_by, executed_at, defer_until, defer_reason, cleanup_work_order_id,"
                + " created_at"
                + " from data_governance_deletion_request"
                + clause
                + " order by requested_at desc limit ? offset ?",
            pageBinds.toArray())) {
      items.add(deletionView(row));
    }

    long pending = countDeletion(tenant, "status = 'PENDING'");
    long approved = countDeletion(tenant, "status = 'APPROVED'");
    long deferred = countDeletion(tenant, "status = 'DEFERRED'");
    long rejected = countDeletion(tenant, "status = 'REJECTED'");
    long executed = countDeletion(tenant, "status = 'EXECUTED'");
    return new DataGovernanceDeletionPage(
        items,
        safePage,
        safeSize,
        total,
        new DataGovernanceDeletionCounts(total, pending, approved, deferred, rejected, executed));
  }

  private long countDeletion(UUID tenant, String extra) {
    Object raw =
        sql.fetchValue(
            "select count(*) from data_governance_deletion_request where tenant_id = ? and " + extra,
            tenant);
    return ((Number) raw).longValue();
  }

  /** 提交一条删除请求。 */
  public DataGovernanceDeletionRequestView createDeletionRequest(
      AccessService.Access actor, DataGovernanceDeletionRequestInput input) {
    UUID tenant = currentTenant();
    String domainCode = input.domainCode().trim();
    requireDomain(domainCode);
    OffsetDateTime now = dbNow();
    UUID id = Ids.next();
    sql.execute(
        """
        insert into data_governance_deletion_request
          (id, tenant_id, request_no, request_type, subject_kind, subject_ref, domain_code,
           reason, status, requested_by, requested_by_name, requested_at, approved_by,
           approved_by_name, approved_at, decision_note, executed_by, executed_by_name,
           executed_at, defer_until, defer_reason, cleanup_work_order_id, created_at, updated_at)
        values (?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, '', ?::timestamptz, null, '', null, '',
                null, '', null, null, '', null, ?::timestamptz, ?::timestamptz)
        """,
        id,
        tenant,
        requestNo("DR", id),
        input.requestType().trim().toUpperCase(Locale.ROOT),
        input.subjectKind().trim(),
        input.subjectRef().trim(),
        domainCode,
        input.reason().trim(),
        actor.userId(),
        now,
        now,
        now);
    return deletionById(id);
  }

  /** 单条删除请求。 */
  public DataGovernanceDeletionRequestView deletionRequest(String requestId) {
    UUID target = parseUuid(requestId);
    if (target == null) {
      throw notFound("删除请求不存在");
    }
    return deletionById(target);
  }

  /**
   * 审批一条删除请求。
   *
   * <p>🔴 **审批人 ≠ 申请人**（自己批自己的审批形同虚设）。
   *
   * <p>🔴 审批时做**财务留存优先**的裁决：
   * 法定留存期没走完 ⇒ 判成 <b>DEFERRED（推迟）</b>，并写明推迟到哪一天、为什么。
   */
  public DataGovernanceDeletionRequestView approveDeletion(
      AccessService.Access actor, String requestId, DataGovernanceDecisionInput input) {
    UUID tenant = currentTenant();
    UUID target = parseUuid(requestId);
    if (target == null) {
      throw notFound("删除请求不存在");
    }
    Record row =
        sql.fetchOne(
            "select id, status, requested_by, domain_code from data_governance_deletion_request"
                + " where tenant_id = ? and id = ?",
            tenant,
            target);
    if (row == null) {
      throw notFound("删除请求不存在");
    }
    String status = row.get("status", String.class);
    if (!"PENDING".equals(status)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "这条删除请求已经处理过了（当前状态：" + deletionStatusLabel(status) + "）");
    }
    if (actor.userId().equals(row.get("requested_by", UUID.class))) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "审批人不能是申请人本人");
    }

    String domainCode = row.get("domain_code", String.class);
    OffsetDateTime now = dbNow();
    Adjudication adjudication = adjudicate(domainCode, now);
    if (KIND_NO_POLICY.equals(adjudication.conflictKind())) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "这个数据域**没有登记任何留存策略** ⇒ 判不了能不能删（判不了 ≠ 默认放行）。"
              + "先登记留存策略，再来审批这条删除请求。");
    }
    recordAdjudication(actor, domainCode, adjudication);

    String decisionNote = nz(input == null ? null : input.decisionNote());
    if (adjudication.deletionBlocked()) {
      OffsetDateTime deferUntil = adjudication.financialMaturityAt();
      sql.execute(
          """
          update data_governance_deletion_request
             set status = 'DEFERRED', approved_by = ?, approved_at = ?::timestamptz,
                 decision_note = ?, defer_until = ?::timestamptz, defer_reason = ?,
                 updated_at = ?::timestamptz
           where tenant_id = ? and id = ?
          """,
          actor.userId(),
          now,
          decisionNote,
          deferUntil,
          adjudication.rationale(),
          now,
          tenant,
          target);
    } else {
      sql.execute(
          """
          update data_governance_deletion_request
             set status = 'APPROVED', approved_by = ?, approved_at = ?::timestamptz,
                 decision_note = ?, updated_at = ?::timestamptz
           where tenant_id = ? and id = ?
          """,
          actor.userId(),
          now,
          decisionNote,
          now,
          tenant,
          target);
    }
    return deletionById(target);
  }

  /** 驳回一条删除请求（理由必填）。 */
  public DataGovernanceDeletionRequestView rejectDeletion(
      AccessService.Access actor, String requestId, DataGovernanceRejectInput input) {
    UUID tenant = currentTenant();
    UUID target = parseUuid(requestId);
    if (target == null) {
      throw notFound("删除请求不存在");
    }
    Record row =
        sql.fetchOne(
            "select id, status from data_governance_deletion_request where tenant_id = ? and id = ?",
            tenant,
            target);
    if (row == null) {
      throw notFound("删除请求不存在");
    }
    String status = row.get("status", String.class);
    if (!"PENDING".equals(status)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "这条删除请求已经处理过了（当前状态：" + deletionStatusLabel(status) + "）");
    }
    OffsetDateTime now = dbNow();
    sql.execute(
        """
        update data_governance_deletion_request
           set status = 'REJECTED', approved_by = ?, approved_at = ?::timestamptz,
               decision_note = ?, updated_at = ?::timestamptz
         where tenant_id = ? and id = ?
        """,
        actor.userId(),
        now,
        input.decisionNote().trim(),
        now,
        tenant,
        target);
    return deletionById(target);
  }

  /**
   * 执行一条删除请求。
   *
   * <p>🔴 <b>执行 = 派发清理工单</b>，不是物理删除（物理删除跨服务做不了）。
   *
   * <p>🔴 被财务留存期推迟（DEFERRED）的请求**不许执行** —— 那正是「财务留存优先」的落点。
   */
  public DataGovernanceDeletionRequestView executeDeletion(
      AccessService.Access actor, String requestId) {
    UUID tenant = currentTenant();
    UUID target = parseUuid(requestId);
    if (target == null) {
      throw notFound("删除请求不存在");
    }
    Record row =
        sql.fetchOne(
            "select id, status, domain_code, subject_kind, subject_ref, defer_until, defer_reason"
                + " from data_governance_deletion_request where tenant_id = ? and id = ?",
            tenant,
            target);
    if (row == null) {
      throw notFound("删除请求不存在");
    }
    String status = row.get("status", String.class);
    if ("DEFERRED".equals(status)) {
      OffsetDateTime deferUntil = row.get("defer_until", OffsetDateTime.class);
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "这条删除请求被**财务（法定）留存期**挡住了 —— 财务留存优先。"
              + "要等到 "
              + (deferUntil == null ? "法定留存期走完" : deferUntil.toLocalDate().toString())
              + " 才能执行。理由："
              + nz(row.get("defer_reason", String.class)));
    }
    if (!"APPROVED".equals(status)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "只有**已批准**的删除请求才能执行（当前状态：" + deletionStatusLabel(status) + "）");
    }

    OffsetDateTime now = dbNow();
    UUID orderId = Ids.next();
    sql.execute(
        """
        insert into retention_cleanup_work_order
          (id, tenant_id, order_no, domain_code, subject_ref, trigger_kind, retention_days,
           retention_class, affected_record_count, status, eligible_at, due_at,
           requested_by, requested_by_name, created_at, updated_at)
        values (?, ?, ?, ?, ?, 'DELETION_REQUEST', ?, ?, 0, 'PENDING', ?::timestamptz, ?::timestamptz,
                ?, '', ?::timestamptz, ?::timestamptz)
        """,
        orderId,
        tenant,
        cleanupNo("CL", orderId),
        row.get("domain_code", String.class),
        row.get("subject_ref", String.class),
        BigDecimal.ZERO,
        "NONE",
        now,
        now,
        actor.userId(),
        now,
        now);
    sql.execute(
        """
        update data_governance_deletion_request
           set status = 'EXECUTED', executed_by = ?, executed_at = ?::timestamptz,
               cleanup_work_order_id = ?, updated_at = ?::timestamptz
         where tenant_id = ? and id = ?
        """,
        actor.userId(),
        now,
        orderId,
        now,
        tenant,
        target);
    return deletionById(target);
  }

  private DataGovernanceDeletionRequestView deletionById(UUID id) {
    Record row =
        sql.fetchOne(
            "select id, request_no, request_type, subject_kind, subject_ref, domain_code, reason,"
                + " status, requested_by, requested_at, approved_by, approved_at, decision_note,"
                + " executed_by, executed_at, defer_until, defer_reason, cleanup_work_order_id,"
                + " created_at"
                + " from data_governance_deletion_request where tenant_id = ? and id = ?",
            currentTenant(),
            id);
    if (row == null) {
      throw notFound("删除请求不存在");
    }
    return deletionView(row);
  }

  private DataGovernanceDeletionRequestView deletionView(Record row) {
    String domainCode = row.get("domain_code", String.class);
    String requestType = row.get("request_type", String.class);
    String status = row.get("status", String.class);
    UUID orderId = row.get("cleanup_work_order_id", UUID.class);
    String orderNo = "";
    if (orderId != null) {
      Object raw =
          sql.fetchValue(
              "select order_no from retention_cleanup_work_order where tenant_id = ? and id = ?",
              currentTenant(),
              orderId);
      orderNo = raw == null ? "" : String.valueOf(raw);
    }
    return new DataGovernanceDeletionRequestView(
        row.get("id", UUID.class),
        row.get("request_no", String.class),
        requestType,
        requestTypeLabel(requestType),
        row.get("subject_kind", String.class),
        row.get("subject_ref", String.class),
        domainCode,
        domainName(domainCode),
        row.get("reason", String.class),
        status,
        deletionStatusLabel(status),
        row.get("requested_by", UUID.class),
        "",
        row.get("requested_at", OffsetDateTime.class),
        row.get("approved_by", UUID.class),
        row.get("approved_by", UUID.class) == null ? null : "",
        row.get("approved_at", OffsetDateTime.class),
        row.get("decision_note", String.class),
        row.get("executed_by", UUID.class),
        row.get("executed_by", UUID.class) == null ? null : "",
        row.get("executed_at", OffsetDateTime.class),
        row.get("defer_until", OffsetDateTime.class),
        row.get("defer_reason", String.class),
        orderId,
        orderNo,
        row.get("created_at", OffsetDateTime.class));
  }

  // ================================================================ 清理工单

  /** 清理工单列表（真分页 + 真筛选 + 汇总）。 */
  public RetentionCleanupPage cleanupOrders(
      String status, String domainCode, int page, int size) {
    UUID tenant = currentTenant();
    int safePage = Math.max(0, page);
    int safeSize = Math.min(100, Math.max(1, size));
    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    where.add("tenant_id = ?");
    binds.add(tenant);
    if (status != null && !status.isBlank()) {
      where.add("status = ?");
      binds.add(status.trim().toUpperCase(Locale.ROOT));
    }
    if (domainCode != null && !domainCode.isBlank()) {
      where.add("domain_code = ?");
      binds.add(domainCode.trim());
    }
    String clause = " where " + String.join(" and ", where);

    long total =
        ((Number)
                sql.fetchValue("select count(*) from retention_cleanup_work_order" + clause, binds.toArray()))
            .longValue();

    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(safeSize);
    pageBinds.add((long) safePage * safeSize);
    List<RetentionCleanupOrderView> items = new ArrayList<>();
    for (Record row :
        sql.fetch(
            "select id, order_no, domain_code, subject_ref, trigger_kind, retention_days,"
                + " retention_class, affected_record_count, status, eligible_at, due_at,"
                + " requested_by, requested_by_name, created_at, updated_at"
                + " from retention_cleanup_work_order"
                + clause
                + " order by due_at, created_at desc limit ? offset ?",
            pageBinds.toArray())) {
      items.add(cleanupOrderView(row));
    }

    long pending = countCleanup(tenant, "status = 'PENDING'");
    long inProgress = countCleanup(tenant, "status = 'IN_PROGRESS'");
    long completed = countCleanup(tenant, "status = 'COMPLETED'");
    long partial = countCleanup(tenant, "status = 'PARTIAL'");
    long failed = countCleanup(tenant, "status = 'FAILED'");
    return new RetentionCleanupPage(
        items,
        safePage,
        safeSize,
        total,
        new RetentionCleanupCounts(total, pending, inProgress, completed, partial, failed));
  }

  private long countCleanup(UUID tenant, String extra) {
    Object raw =
        sql.fetchValue(
            "select count(*) from retention_cleanup_work_order where tenant_id = ? and " + extra,
            tenant);
    return ((Number) raw).longValue();
  }

  /**
   * 登记一张清理工单。
   *
   * <p>🔴 <b>eligible_at（最早可清理日）由生效留存期算出来</b>，不是自由填的：
   * 生效策略生效日 + 生效留存期。{@code due_at}（完成截止日）不得早于它 ——
   * 这把「财务留存优先」在清理路径上也钉了一道。
   */
  public RetentionCleanupOrderView createCleanupOrder(
      AccessService.Access actor, RetentionCleanupOrderInput input) {
    UUID tenant = currentTenant();
    String domainCode = input.domainCode().trim();
    requireDomain(domainCode);
    String triggerKind = input.triggerKind().trim().toUpperCase(Locale.ROOT);
    String subjectRef = nz(input.subjectRef()).trim();
    if ("DELETION_REQUEST".equals(triggerKind) && subjectRef.isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "由删除请求触发的清理工单必须指明被清理的对象");
    }

    OffsetDateTime now = dbNow();
    Adjudication adjudication = adjudicate(domainCode, now);
    if (KIND_NO_POLICY.equals(adjudication.conflictKind())) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "这个数据域**没有登记任何留存策略** ⇒ 算不出最早可清理日。"
              + "没有依据的清理工单不许开（判不了 ≠ 默认放行）。");
    }
    recordAdjudication(actor, domainCode, adjudication);

    OffsetDateTime eligibleAt = eligibleAt(adjudication);
    OffsetDateTime dueAt = input.dueAt() == null ? eligibleAt : input.dueAt();
    if (dueAt.isBefore(eligibleAt)) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "完成截止日（" + dueAt.toLocalDate() + "）不得早于最早可清理日（" + eligibleAt.toLocalDate() + "）"
              + " —— 财务（法定）留存期没走完之前不许排清理。");
    }

    String retentionClass =
        adjudication.financialPolicy() != null
                && RESOLUTION_FINANCIAL_PREVAILS.equals(adjudication.resolution())
            ? CLASS_FINANCIAL
            : (adjudication.standardPolicy() != null ? CLASS_STANDARD : CLASS_FINANCIAL);

    UUID id = Ids.next();
    BigDecimal affected =
        input.affectedRecordCount() == null ? BigDecimal.ZERO : input.affectedRecordCount();
    sql.execute(
        """
        insert into retention_cleanup_work_order
          (id, tenant_id, order_no, domain_code, subject_ref, trigger_kind, retention_days,
           retention_class, affected_record_count, status, eligible_at, due_at,
           requested_by, requested_by_name, created_at, updated_at)
        values (?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?::timestamptz, ?::timestamptz, ?, '',
                ?::timestamptz, ?::timestamptz)
        """,
        id,
        tenant,
        cleanupNo("CL", id),
        domainCode,
        subjectRef,
        triggerKind,
        adjudication.effectiveRetentionDays(),
        retentionClass,
        affected,
        eligibleAt,
        dueAt,
        actor.userId(),
        now,
        now);
    return cleanupOrderById(id);
  }

  /** 单张清理工单。 */
  public RetentionCleanupOrderView cleanupOrder(String orderId) {
    UUID target = parseUuid(orderId);
    if (target == null) {
      throw notFound("清理工单不存在");
    }
    return cleanupOrderById(target);
  }

  /**
   * 执行清理工单（写一条 append-only 执行记录，并更新工单状态）。
   *
   * <p>🔴 **执行结果由执行方回报** —— 本模块只落"谁、何时、对哪个域、做了什么、结果如何"。
   */
  public RetentionCleanupOrderView executeCleanupOrder(
      AccessService.Access actor, String orderId, RetentionCleanupExecuteInput input) {
    UUID tenant = currentTenant();
    UUID target = parseUuid(orderId);
    if (target == null) {
      throw notFound("清理工单不存在");
    }
    Record row =
        sql.fetchOne(
            "select id, order_no, domain_code, status from retention_cleanup_work_order"
                + " where tenant_id = ? and id = ?",
            tenant,
            target);
    if (row == null) {
      throw notFound("清理工单不存在");
    }
    String status = row.get("status", String.class);
    if ("COMPLETED".equals(status) || "CANCELLED".equals(status)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "这张清理工单已经" + cleanupStatusLabel(status) + "了，不许再执行");
    }
    OffsetDateTime now = dbNow();
    String result = input.result().trim().toUpperCase(Locale.ROOT);
    // 🔴 处理条数必须是**整数条**（数量 ⇒ NUMERIC(19,6)，但条数不能是 1.5 条）。
    if (input.recordCount().stripTrailingZeros().scale() > 0) {
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "处理条数必须是整数条");
    }
    String action = "PENDING".equals(status) ? "EXECUTE" : "RETRY";
    sql.execute(
        """
        insert into retention_cleanup_record
          (id, tenant_id, work_order_id, order_no, domain_code, action, result, record_count,
           message, executed_by, executed_by_name, executed_at, created_at)
        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, '', ?::timestamptz, ?::timestamptz)
        """,
        Ids.next(),
        tenant,
        target,
        row.get("order_no", String.class),
        row.get("domain_code", String.class),
        action,
        result,
        input.recordCount(),
        nz(input.message()),
        actor.userId(),
        now,
        now);
    String next =
        switch (result) {
          case "SUCCESS" -> "COMPLETED";
          case "PARTIAL" -> "PARTIAL";
          default -> "FAILED";
        };
    sql.execute(
        """
        update retention_cleanup_work_order
           set status = ?, affected_record_count = greatest(affected_record_count, ?),
               updated_at = ?::timestamptz
         where tenant_id = ? and id = ?
        """,
        next,
        input.recordCount(),
        now,
        tenant,
        target);
    return cleanupOrderById(target);
  }

  private RetentionCleanupOrderView cleanupOrderById(UUID id) {
    Record row =
        sql.fetchOne(
            "select id, order_no, domain_code, subject_ref, trigger_kind, retention_days,"
                + " retention_class, affected_record_count, status, eligible_at, due_at,"
                + " requested_by, requested_by_name, created_at, updated_at"
                + " from retention_cleanup_work_order where tenant_id = ? and id = ?",
            currentTenant(),
            id);
    if (row == null) {
      throw notFound("清理工单不存在");
    }
    return cleanupOrderView(row);
  }

  private RetentionCleanupOrderView cleanupOrderView(Record row) {
    String domainCode = row.get("domain_code", String.class);
    String triggerKind = row.get("trigger_kind", String.class);
    String status = row.get("status", String.class);
    String retentionClass = row.get("retention_class", String.class);
    return new RetentionCleanupOrderView(
        row.get("id", UUID.class),
        row.get("order_no", String.class),
        domainCode,
        domainName(domainCode),
        row.get("subject_ref", String.class),
        triggerKind,
        triggerKindLabel(triggerKind),
        row.get("retention_days", BigDecimal.class),
        retentionClass,
        retentionClassLabel(retentionClass),
        row.get("affected_record_count", BigDecimal.class),
        status,
        cleanupStatusLabel(status),
        row.get("eligible_at", OffsetDateTime.class),
        row.get("due_at", OffsetDateTime.class),
        row.get("requested_by", UUID.class),
        row.get("requested_by_name", String.class),
        row.get("created_at", OffsetDateTime.class),
        row.get("updated_at", OffsetDateTime.class));
  }

  // ================================================================ 清理执行记录

  /** 清理执行记录（真分页 + 真筛选；**append-only**）。 */
  public RetentionCleanupRecordPage cleanupRecords(
      String orderId, String domainCode, int page, int size) {
    UUID tenant = currentTenant();
    int safePage = Math.max(0, page);
    int safeSize = Math.min(100, Math.max(1, size));
    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    where.add("tenant_id = ?");
    binds.add(tenant);
    if (orderId != null && !orderId.isBlank()) {
      UUID target = parseUuid(orderId);
      if (target == null) {
        throw notFound("清理工单不存在");
      }
      where.add("work_order_id = ?");
      binds.add(target);
    }
    if (domainCode != null && !domainCode.isBlank()) {
      where.add("domain_code = ?");
      binds.add(domainCode.trim());
    }
    String clause = " where " + String.join(" and ", where);

    long total =
        ((Number) sql.fetchValue("select count(*) from retention_cleanup_record" + clause, binds.toArray()))
            .longValue();

    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(safeSize);
    pageBinds.add((long) safePage * safeSize);
    List<RetentionCleanupRecordView> items = new ArrayList<>();
    for (Record row :
        sql.fetch(
            "select id, work_order_id, order_no, domain_code, action, result, record_count,"
                + " message, executed_by, executed_by_name, executed_at"
                + " from retention_cleanup_record"
                + clause
                + " order by executed_at desc limit ? offset ?",
            pageBinds.toArray())) {
      String domain = row.get("domain_code", String.class);
      String action = row.get("action", String.class);
      String result = row.get("result", String.class);
      items.add(
          new RetentionCleanupRecordView(
              row.get("id", UUID.class),
              row.get("work_order_id", UUID.class),
              row.get("order_no", String.class),
              domain,
              domainName(domain),
              action,
              cleanupActionLabel(action),
              result,
              cleanupResultLabel(result),
              row.get("record_count", BigDecimal.class),
              row.get("message", String.class),
              row.get("executed_by", UUID.class),
              row.get("executed_by_name", String.class),
              row.get("executed_at", OffsetDateTime.class)));
    }
    return new RetentionCleanupRecordPage(items, safePage, safeSize, total);
  }

  // ================================================================ 工具

  /** 数据域必须已登记（服务层复查一次，DB 层还有触发器）。 */
  private void requireDomain(String domainCode) {
    Object raw =
        sql.fetchValue(
            "select count(*) from data_governance_data_domain where tenant_id = ? and domain_code = ?",
            currentTenant(),
            domainCode);
    if (((Number) raw).longValue() == 0L) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "数据域「" + domainCode + "」还没登记 —— 先在数据域登记表里登记它");
    }
  }

  /** 数据域名（没登记时回显编码，不编造名字）。 */
  private String domainName(String domainCode) {
    Object raw =
        sql.fetchValue(
            "select domain_name from data_governance_data_domain where tenant_id = ? and domain_code = ?",
            currentTenant(),
            domainCode);
    return raw == null ? domainCode : String.valueOf(raw);
  }

  /** 跨境传输单号（租户内唯一）。 */
  private String transferNo(String prefix, UUID id) {
    return docNo(prefix, id);
  }

  /** 删除请求单号。 */
  private String requestNo(String prefix, UUID id) {
    return docNo(prefix, id);
  }

  /** 清理工单单号。 */
  private String cleanupNo(String prefix, UUID id) {
    return docNo(prefix, id);
  }

  /**
   * 单号 = 前缀 + 日期 + <b>UUID 的随机尾部</b>。
   *
   * <p>🔴 <b>为什么取尾部而不是头部</b>：本仓的 {@code Ids.next()} 发的是 UUIDv7，
   * 它的<b>前 48 bit 是毫秒时间戳</b> —— 同一毫秒内生成的两个 id，**开头那段完全一样**。
   * 用头部做单号 ⇒ 同一秒里连着登记两条就撞唯一键（这正是测试第一次跑就撞出来的那个 bug）。
   * 尾部是<b>随机位</b>，用它做单号才唯一。
   */
  private String docNo(String prefix, UUID id) {
    return prefix
        + "-"
        + dbNow().toLocalDate().toString().replace("-", "")
        + "-"
        + id.toString().replace("-", "").substring(20).toUpperCase(Locale.ROOT);
  }

  private UUID currentTenant() {
    Object raw = sql.fetchValue("select nullif(current_setting('app.current_tenant',true),'')");
    if (raw == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "缺少租户上下文");
    }
    return UUID.fromString(String.valueOf(raw));
  }

  private OffsetDateTime dbNow() {
    Object raw = sql.fetchValue("select now()");
    return raw instanceof OffsetDateTime at ? at : OffsetDateTime.now(ZoneOffset.UTC);
  }

  private static UUID parseUuid(String raw) {
    try {
      return UUID.fromString(nz(raw).trim());
    } catch (RuntimeException e) {
      return null;
    }
  }

  private static ResponseStatusException notFound(String message) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
  }

  private static String nz(String value) {
    return value == null ? "" : value;
  }
}
