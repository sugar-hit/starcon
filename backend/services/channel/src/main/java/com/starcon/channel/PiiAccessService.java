package com.starcon.channel;

import static com.starcon.channel.PiiAccessModels.*;
import static com.starcon.channel.PiiAccessRules.*;

import com.starcon.channel.PiiAccessRules.GrantFact;
import com.starcon.tenancy.Ids;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 16.6.1.1 PII 管理。
 *
 * <p>蓝图：「管理个人信息（买家姓名、地址、电话、邮箱）的访问与脱敏：默认脱敏展示、
 * 按角色授权查看完整信息、访问留痕、导出审批。输入：PII 访问请求；授权配置；导出申请。
 * 输出：脱敏数据或授权后的完整数据；访问审计记录。
 * 关系：上游 = 15 客户、9 订单；下游 = 客服与运营页面。
 * <b>完整地址查看需留痕</b>——批量导出 PII 是数据泄露事件的典型形态。」
 *
 * <p>🔴🔴 本类里有<b>六条结构上的不变量</b>，每一条都对应蓝图或 AGENTS.md 的一条硬要求：
 *
 * <ol>
 *   <li><b>默认脱敏是硬默认，不是开关</b>：默认返回的永远是脱敏值；
 *       本服务<b>没有</b>「关闭脱敏」的参数、配置或端点。</li>
 *   <li><b>看完整值必须走授权判定</b>：按角色（{@code pii_access_grant}）× 成员范围
 *       （ALL / DEPARTMENT / SELF）判定，<b>任一字段不通过 ⇒ 整个请求被拒</b>
 *       （半授权会让调用方误以为「拿到了就等于拿到了」）。</li>
 *   <li><b>每一次看完整值都落审计</b>；越权尝试也落。审计表 append-only，
 *       事务回滚不了它（被拒时由控制器在事务提交后才转 403）。</li>
 *   <li><b>批量导出必须走审批</b>：申请 → 审批 → 执行；<b>审批人 ≠ 申请人</b>；
 *       <b>导出对象必须逐条列明</b>（不许「导出全部」）；<b>单次条数有上限</b>。</li>
 *   <li><b>PII 明文不进本服务</b>：本服务的表只存授权配置、访问审计、导出审批与凭据；
 *       审计里的标签是<b>脱敏标签</b>，导出凭据里是主体清单的 <b>md5</b>。</li>
 *   <li><b>上游读不到 ≠ 查无此人</b>：上游不可达一律 503（人话写明「读不到」）；
 *       上游正常但查无此人时返回 {@code known=false}。</li>
 * </ol>
 */
@Service
public class PiiAccessService {

  // ================================================================ 口径文案

  private static final String MASKING_SEMANTICS =
      "🔴 **脱敏是硬默认，不是可选项**：没有开关能关掉它，也没有端点能绕过它。"
          + "脱敏规则按字段类型走**部分掩码**（掩码用 * 逐字替换，"
          + "**不是明文的可逆编码** —— 可逆编码换了个写法的明文，不叫脱敏）："
          + "姓名保留姓氏（西式保留姓、名留首字母；中文式保留首字）；"
          + "电话保留末四位；邮箱本地部分掩码、域名保留；地址保留到区级（国/省/市/区），"
          + "街道与邮编掩码。";

  private static final String AUTHORIZATION_SEMANTICS =
      "🔴 **看完整值必须走一次授权判定**：按角色 × 字段 × 成员范围"
          + "（ALL 全部成员 / DEPARTMENT 限部门成员 / SELF 限本人经手）。"
          + "**任一字段不通过 ⇒ 整个请求被拒**，不给半份完整值 —— "
          + "半授权会让调用方误以为「拿到了就等于拿到了」。"
          + "判不出来的范围（SELF 范围遇到没有经手人字段的订单主体）⇒ **拒绝**，不是默认放行。";

  private static final String AUDIT_SEMANTICS =
      "🔴 **访问留痕**：脱敏视图、完整值查看、越权尝试、导出（放行与被拒）都逐条落审计。"
          + "审计表 **append-only**（只给 SELECT,INSERT，触发器禁止 UPDATE/DELETE）。"
          + "审计里存的是**脱敏标签**，不是 PII 明文 —— 审计表不该变成第二个 PII 副本。";

  private static final String EXPORT_SEMANTICS =
      "🔴 **批量导出 PII 是数据泄露事件的典型形态** ⇒ 导出走三段式：申请 → 审批 → 执行。"
          + "**审批人 ≠ 申请人**（自己批自己的审批形同虚设）；"
          + "**未审批的导出请求一律拒绝**；**导出对象必须逐条列明**（没有「一键全量导出」）；"
          + "**单次条数有上限**（租户可配，系统硬顶 500）；批准后有有效期，超时作废。";

  private static final String STORAGE_SEMANTICS =
      "🔴 **本服务不复制买家 PII**：真值在 9 订单（trade 的 order_address_snapshot）"
          + "与 15 客户（crm 的 crm_buyer_email）里，跨服务只走授权 HTTP API。"
          + "本服务的表只存**授权配置、访问审计、导出审批与凭据** —— "
          + "复制一份 PII 过来就是**新增一个泄露面**，那是本模块最该避免的事。";

  private static final String DOWNSTREAM_NOTE =
      "🔴 下游 = 客服与运营页面。本模块**只提供读取形状与授权判定**，"
          + "不替客服页面改前端；本仓**不写**下游客服 / 运营页面的专属形状，"
          + "它们是否落地以台账为准。";

  private static final String BOUNDARY_NOTE =
      "🔴 三条边界写死在能力声明里：① **不提供一键全量导出**（导出对象必须逐条列明、"
          + "单次条数有上限）；② **SELF 范围对订单主体判不了**（trade 的订单与地址快照"
          + "没有经手人字段）⇒ 拒绝并写明原因；③ **不读上游的 buyer_ref 当买家主键**"
          + "（crm 自己的迁移注释写明 customer_ref 只是一条逻辑引用，不是买家主数据）。";

  // ================================================================ 依赖

  private final DSLContext sql;
  private final PiiAccessSourcePort source;

  public PiiAccessService(DSLContext sql, PiiAccessSourcePort source) {
    this.sql = sql;
    this.source = source;
  }

  // ================================================================ 能力声明

  /** 能力声明：字段口径、上游读面、授权模型、审计与导出规则。 */
  public PiiAccessCapabilities capabilities() {
    List<PiiAccessFieldInfo> fields =
        List.of(
            new PiiAccessFieldInfo(
                FIELD_NAME,
                "姓名",
                "保留姓氏：西式（Alice Smith ⇒ A**** Smith）、中文式（王小明 ⇒ 王**）",
                true,
                true),
            new PiiAccessFieldInfo(
                FIELD_PHONE,
                "电话",
                "保留末四位，其余逐位掩码（+1-555-0100 ⇒ *******0100）",
                false,
                true),
            new PiiAccessFieldInfo(
                FIELD_EMAIL,
                "邮箱",
                "本地部分掩码、域名保留（alice@example.com ⇒ a***@example.com）",
                true,
                true),
            new PiiAccessFieldInfo(
                FIELD_ADDRESS,
                "地址",
                "保留到区级（国/省/市/区），街道与邮编逐字掩码",
                false,
                true));

    List<PiiAccessSourceState> sources =
        List.of(
            new PiiAccessSourceState(
                "TRADE_ORDER_ADDRESS",
                "9 订单 · 收货地址快照（trade）",
                true,
                "取订单上冻结的那一份地址快照（V99 的 order_address_snapshot_frozen 触发器钉死），"
                    + "不是活地址。给全四个字段：姓名 / 电话 / 邮箱 / 地址。"),
            new PiiAccessSourceState(
                "CRM_BUYER_EMAIL",
                "15 客户 · 买家联系方式（crm）",
                true,
                "crm 的 crm_buyer_email 给买家姓名与邮箱；"
                    + "**电话与地址这个来源给不了** ⇒ 那两个字段对 CUSTOMER 主体是「上游没给」，"
                    + "不是「空值」。"),
            new PiiAccessSourceState(
                "CRM_CONTACT_OPERATOR",
                "15 客户 · 接触记录（crm）",
                true,
                "SELF 成员范围要用：crm 的接触记录带 operator_id，能判「本人是否经手」。"
                    + "订单侧判不了（trade 没有经手人字段）⇒ SELF 范围对订单主体一律拒绝。"));

    return new PiiAccessCapabilities(
        fields,
        sources,
        MASKING_SEMANTICS,
        AUTHORIZATION_SEMANTICS,
        AUDIT_SEMANTICS,
        EXPORT_SEMANTICS,
        STORAGE_SEMANTICS,
        DOWNSTREAM_NOTE,
        BOUNDARY_NOTE);
  }

  // ================================================================ 策略

  /** 读策略。没登记过返回 {@code configured=false}（**不预填任何默认值**）。 */
  public PiiAccessPolicyView policy() {
    Record row =
        sql.fetchOne(
            "select id, policy_name, max_export_records, export_approval_ttl_hours,"
                + " max_reveal_per_day, require_reason_on_reveal, version, created_at, updated_at"
                + " from pii_access_policy where tenant_id = ?",
            currentTenant());
    if (row == null) {
      return new PiiAccessPolicyView(
          null, "", 0, 0, 0, false, 0L, false, null, null);
    }
    return policyView(row);
  }

  /** 登记 / 修改策略（upsert，一租户一条）。 */
  public PiiAccessPolicyView savePolicy(PiiAccessPolicyInput input) {
    UUID tenant = currentTenant();
    Record existing =
        sql.fetchOne("select id, version from pii_access_policy where tenant_id = ?", tenant);
    UUID id = existing == null ? Ids.next() : existing.get("id", UUID.class);
    long version = existing == null ? 0L : existing.get("version", Long.class);
    if (input.version() != null && existing != null && input.version() != version) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "策略已被别人改过（版本 " + version + "），请重新读取后再保存");
    }
    OffsetDateTime now = dbNow();
    if (existing == null) {
      sql.execute(
          """
          insert into pii_access_policy
            (id, tenant_id, policy_name, max_export_records, export_approval_ttl_hours,
             max_reveal_per_day, require_reason_on_reveal, version, created_at, updated_at)
          values (?, ?, ?, ?, ?, ?, ?, 0, ?::timestamptz, ?::timestamptz)
          """,
          id,
          tenant,
          input.policyName().trim(),
          input.maxExportRecords(),
          input.exportApprovalTtlHours(),
          input.maxRevealPerDay(),
          Boolean.TRUE.equals(input.requireReasonOnReveal()),
          now,
          now);
    } else {
      sql.execute(
          """
          update pii_access_policy
             set policy_name = ?, max_export_records = ?, export_approval_ttl_hours = ?,
                 max_reveal_per_day = ?, require_reason_on_reveal = ?, version = version + 1,
                 updated_at = ?::timestamptz
           where tenant_id = ? and id = ?
          """,
          input.policyName().trim(),
          input.maxExportRecords(),
          input.exportApprovalTtlHours(),
          input.maxRevealPerDay(),
          Boolean.TRUE.equals(input.requireReasonOnReveal()),
          now,
          tenant,
          id);
    }
    return policy();
  }

  private PiiAccessPolicyView policyView(Record row) {
    return new PiiAccessPolicyView(
        row.get("id", UUID.class),
        row.get("policy_name", String.class),
        row.get("max_export_records", Integer.class),
        row.get("export_approval_ttl_hours", Integer.class),
        row.get("max_reveal_per_day", Integer.class),
        Boolean.TRUE.equals(row.get("require_reason_on_reveal", Boolean.class)),
        row.get("version", Long.class),
        true,
        row.get("created_at", OffsetDateTime.class),
        row.get("updated_at", OffsetDateTime.class));
  }

  /**
   * 生效中的策略值（内部用）。
   *
   * <p>🔴 没登记过策略时用**系统口径**（导出上限 100 条 / 审批有效期 24 小时 /
   * 查看必须写理由 / 每日查看不限），并把「用的是系统口径」如实报出去 ——
   * 凭空给一份看起来像租户配置的值，会让运营以为那就是他们定的规矩。
   */
  private EffectivePolicy effectivePolicy() {
    Record row =
        sql.fetchOne(
            "select max_export_records, export_approval_ttl_hours, max_reveal_per_day,"
                + " require_reason_on_reveal from pii_access_policy where tenant_id = ?",
            currentTenant());
    if (row == null) {
      return new EffectivePolicy(100, 24, 0, true, false);
    }
    return new EffectivePolicy(
        row.get("max_export_records", Integer.class),
        row.get("export_approval_ttl_hours", Integer.class),
        row.get("max_reveal_per_day", Integer.class),
        Boolean.TRUE.equals(row.get("require_reason_on_reveal", Boolean.class)),
        true);
  }

  private record EffectivePolicy(
      int maxExportRecords,
      int exportApprovalTtlHours,
      int maxRevealPerDay,
      boolean requireReasonOnReveal,
      boolean configured) {}

  // ================================================================ 授权配置

  /** 授权配置列表（真分页 + 真筛选）。 */
  public PiiAccessGrantPage grants(String roleCode, String status, int page, int size) {
    UUID tenant = currentTenant();
    int safePage = Math.max(0, page);
    int safeSize = Math.min(100, Math.max(1, size));
    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    where.add("tenant_id = ?");
    binds.add(tenant);
    if (roleCode != null && !roleCode.isBlank()) {
      where.add("role_code = ?");
      binds.add(roleCode.trim());
    }
    if (status != null && !status.isBlank()) {
      where.add("status = ?");
      binds.add(status.trim());
    }
    String clause = " where " + String.join(" and ", where);

    long total =
        ((Number) sql.fetchValue("select count(*) from pii_access_grant" + clause, binds.toArray()))
            .longValue();

    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(safeSize);
    pageBinds.add((long) safePage * safeSize);
    List<PiiAccessGrantView> items = new ArrayList<>();
    for (Record row :
        sql.fetch(
            "select id, role_code, field_code, scope_mode, reason_required, effective_from,"
                + " effective_to, status, granted_by, granted_at, revoked_by, revoked_at,"
                + " revoke_reason, note, version, created_at, updated_at"
                + " from pii_access_grant"
                + clause
                + " order by role_code, field_code limit ? offset ?",
            pageBinds.toArray())) {
      items.add(grantView(row));
    }
    return new PiiAccessGrantPage(items, safePage, safeSize, total);
  }

  /**
   * 登记 / 修改授权配置（upsert，按「角色 × 字段」）。
   *
   * <p>🔴 同一个（角色 × 字段）**只有一条**授权：撤销后再授权走**同一条的更新**。
   * 另起一行会让同一对（角色,字段）出现两条互相矛盾的授权，判定就成了「挑一条运气好的」。
   */
  public PiiAccessGrantView saveGrant(
      AccessService.Access actor, PiiAccessGrantInput input) {
    UUID tenant = currentTenant();
    String role = input.roleCode().trim();
    String field = input.fieldCode().trim();
    Record existing =
        sql.fetchOne(
            "select id, version from pii_access_grant"
                + " where tenant_id = ? and role_code = ? and field_code = ?",
            tenant,
            role,
            field);
    UUID id = existing == null ? Ids.next() : existing.get("id", UUID.class);
    boolean reasonRequired =
        input.reasonRequired() == null ? true : Boolean.TRUE.equals(input.reasonRequired());
    OffsetDateTime now = dbNow();
    if (existing == null) {
      sql.execute(
          """
          insert into pii_access_grant
            (id, tenant_id, role_code, field_code, scope_mode, reason_required,
             effective_from, effective_to, status, granted_by, granted_at,
             note, version, created_at, updated_at)
          values (?, ?, ?, ?, ?, ?, ?::timestamptz, ?::timestamptz, 'ACTIVE', ?, ?::timestamptz,
                  ?, 0, ?::timestamptz, ?::timestamptz)
          """,
          id,
          tenant,
          role,
          field,
          input.scopeMode().trim(),
          reasonRequired,
          input.effectiveFrom(),
          input.effectiveTo(),
          actor.userId(),
          now,
          nz(input.note()),
          now,
          now);
    } else {
      sql.execute(
          """
          update pii_access_grant
             set scope_mode = ?, reason_required = ?, effective_from = ?::timestamptz,
                 effective_to = ?::timestamptz, status = 'ACTIVE',
                 granted_by = ?, granted_at = ?::timestamptz,
                 revoked_by = null, revoked_at = null, revoke_reason = '',
                 note = ?, version = version + 1, updated_at = ?::timestamptz
           where tenant_id = ? and id = ?
          """,
          input.scopeMode().trim(),
          reasonRequired,
          input.effectiveFrom(),
          input.effectiveTo(),
          actor.userId(),
          now,
          nz(input.note()),
          now,
          tenant,
          id);
    }
    return grantById(id);
  }

  /** 撤销授权（理由必填）。 */
  public PiiAccessGrantView revokeGrant(
      AccessService.Access actor, String grantId, PiiAccessGrantRevokeInput input) {
    UUID tenant = currentTenant();
    UUID target = parseUuid(grantId);
    if (target == null) {
      throw notFound("授权配置不存在");
    }
    Record row =
        sql.fetchOne(
            "select id, status from pii_access_grant where tenant_id = ? and id = ?", tenant, target);
    if (row == null) {
      throw notFound("授权配置不存在");
    }
    if ("REVOKED".equals(row.get("status", String.class))) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "这条授权已经撤销过了");
    }
    OffsetDateTime now = dbNow();
    sql.execute(
        """
        update pii_access_grant
           set status = 'REVOKED', revoked_by = ?, revoked_at = ?::timestamptz,
               revoke_reason = ?, version = version + 1, updated_at = ?::timestamptz
         where tenant_id = ? and id = ?
        """,
        actor.userId(),
        now,
        input.revokeReason().trim(),
        now,
        tenant,
        target);
    return grantById(target);
  }

  private PiiAccessGrantView grantById(UUID id) {
    Record row =
        sql.fetchOne(
            "select id, role_code, field_code, scope_mode, reason_required, effective_from,"
                + " effective_to, status, granted_by, granted_at, revoked_by, revoked_at,"
                + " revoke_reason, note, version, created_at, updated_at"
                + " from pii_access_grant where tenant_id = ? and id = ?",
            currentTenant(),
            id);
    if (row == null) {
      throw notFound("授权配置不存在");
    }
    return grantView(row);
  }

  private PiiAccessGrantView grantView(Record row) {
    String field = row.get("field_code", String.class);
    String scope = row.get("scope_mode", String.class);
    return new PiiAccessGrantView(
        row.get("id", UUID.class),
        row.get("role_code", String.class),
        field,
        fieldLabel(field),
        scope,
        scopeLabel(scope),
        Boolean.TRUE.equals(row.get("reason_required", Boolean.class)),
        row.get("effective_from", OffsetDateTime.class),
        row.get("effective_to", OffsetDateTime.class),
        row.get("status", String.class),
        row.get("granted_by", UUID.class),
        row.get("granted_at", OffsetDateTime.class),
        row.get("revoked_by", UUID.class),
        row.get("revoked_at", OffsetDateTime.class),
        row.get("revoke_reason", String.class),
        row.get("note", String.class),
        row.get("version", Long.class),
        row.get("created_at", OffsetDateTime.class),
        row.get("updated_at", OffsetDateTime.class));
  }

  /** 当前时刻生效的授权（按字段取）。 */
  private List<GrantFact> effectiveGrants(String fieldCode, OffsetDateTime at) {
    List<GrantFact> facts = new ArrayList<>();
    for (Record row :
        sql.fetch(
            "select id, role_code, field_code, scope_mode, reason_required"
                + " from pii_access_grant"
                + " where tenant_id = ? and status = 'ACTIVE' and field_code = ?"
                + " and effective_from <= ?::timestamptz"
                + " and (effective_to is null or effective_to > ?::timestamptz)",
            currentTenant(),
            fieldCode,
            at,
            at)) {
      facts.add(
          new GrantFact(
              row.get("id", UUID.class),
              row.get("role_code", String.class),
              row.get("field_code", String.class),
              row.get("scope_mode", String.class),
              Boolean.TRUE.equals(row.get("reason_required", Boolean.class))));
    }
    return facts;
  }

  // ================================================================ 主体列表（默认脱敏）

  /**
   * PII 主体列表（**默认脱敏**，真分页 + 真搜索）。
   *
   * @param kind {@code ORDER}（9 订单）或 {@code CUSTOMER}（15 客户）
   */
  public PiiAccessSubjectPage subjects(
      AccessService.Access actor,
      String bearer,
      String kind,
      String keyword,
      int page,
      int size) {
    UUID tenant = currentTenant();
    int safePage = Math.max(0, page);
    int safeSize = Math.min(50, Math.max(1, size));
    String subjectKind = normalizeKind(kind);
    List<PiiAccessSubjectView> items = new ArrayList<>();
    long total = 0;
    String note = "";

    if ("ORDER".equals(subjectKind)) {
      PiiAccessSourcePort.SourceOrders orders =
          source.orders(bearer, safePage, safeSize);
      total = orders.total();
      note = orders.note();
      for (PiiAccessSourcePort.SourceOrderRow row : orders.orders()) {
        PiiAccessSourcePort.SourceSubject facts = source.orderFacts(bearer, row.id());
        items.add(orderSubjectView(facts, row.orderNo()));
      }
    } else {
      PiiAccessSourcePort.SourceCustomers customers =
          source.customers(bearer, keyword, safePage, safeSize);
      total = customers.total();
      note = customers.note();
      for (PiiAccessSourcePort.SourceCustomer customer : customers.customers()) {
        items.add(customerSubjectView(customer));
      }
    }

    // 🔴 脱敏视图**也要留痕**：谁看过哪些主体的脱敏视图，同样是一次访问。
    for (PiiAccessSubjectView view : items) {
      auditMaskedView(actor, view);
    }

    List<PiiAccessSourceState> sources =
        List.of(
            new PiiAccessSourceState(
                "TRADE_ORDER_ADDRESS",
                "9 订单 · 收货地址快照（trade）",
                true,
                "订单侧主体：给全四个字段。"),
            new PiiAccessSourceState(
                "CRM_BUYER_EMAIL",
                "15 客户 · 买家联系方式（crm）",
                true,
                "客户侧主体：给姓名与邮箱；电话与地址「上游没给」。"));

    return new PiiAccessSubjectPage(
        true,
        note.isEmpty() ? "上游读到了。" : note,
        items,
        safePage,
        safeSize,
        total,
        sources);
  }

  private PiiAccessSubjectView orderSubjectView(
      PiiAccessSourcePort.SourceSubject facts, String orderNo) {
    List<PiiAccessSubjectField> masked = new ArrayList<>();
    List<String> available = new ArrayList<>();
    List<String> missing = new ArrayList<>();
    if (!facts.known() || facts.address() == null) {
      return new PiiAccessSubjectView(
          "ORDER",
          facts.subjectRef(),
          facts.subjectRef(),
          false,
          masked,
          available,
          missing,
          "9 订单 · 收货地址快照（trade）",
          facts.note());
    }
    PiiAccessSourcePort.SourceAddress address = facts.address();
    String name = nz(address.recipientName());
    String phone = nz(address.recipientPhone());
    String email = nz(address.recipientEmail());
    String addressText =
        maskAddress(
            address.countryCode(),
            address.province(),
            address.city(),
            address.district(),
            address.postcode(),
            address.addressLine1(),
            address.addressLine2());
    if (!name.isEmpty()) {
      masked.add(new PiiAccessSubjectField(FIELD_NAME, fieldLabel(FIELD_NAME), maskName(name)));
      available.add(FIELD_NAME);
    } else {
      missing.add(FIELD_NAME);
    }
    if (!phone.isEmpty()) {
      masked.add(new PiiAccessSubjectField(FIELD_PHONE, fieldLabel(FIELD_PHONE), maskPhone(phone)));
      available.add(FIELD_PHONE);
    } else {
      missing.add(FIELD_PHONE);
    }
    if (!email.isEmpty()) {
      masked.add(new PiiAccessSubjectField(FIELD_EMAIL, fieldLabel(FIELD_EMAIL), maskEmail(email)));
      available.add(FIELD_EMAIL);
    } else {
      missing.add(FIELD_EMAIL);
    }
    if (!addressText.isEmpty()) {
      masked.add(
          new PiiAccessSubjectField(FIELD_ADDRESS, fieldLabel(FIELD_ADDRESS), addressText));
      available.add(FIELD_ADDRESS);
    } else {
      missing.add(FIELD_ADDRESS);
    }
    return new PiiAccessSubjectView(
        "ORDER",
        facts.subjectRef(),
        subjectLabel(facts.subjectRef(), name),
        true,
        masked,
        available,
        missing,
        "9 订单 · 收货地址快照（trade）",
        orderNo.isEmpty() ? "" : "订单号 " + orderNo);
  }

  private PiiAccessSubjectView customerSubjectView(PiiAccessSourcePort.SourceCustomer customer) {
    List<PiiAccessSubjectField> masked = new ArrayList<>();
    List<String> available = new ArrayList<>();
    List<String> missing = new ArrayList<>();
    if (!customer.known()) {
      return new PiiAccessSubjectView(
          "CUSTOMER",
          customer.buyerRef(),
          customer.buyerRef(),
          false,
          masked,
          available,
          missing,
          "15 客户 · 买家联系方式（crm）",
          customer.note());
    }
    if (!customer.buyerName().isEmpty()) {
      masked.add(
          new PiiAccessSubjectField(
              FIELD_NAME, fieldLabel(FIELD_NAME), maskName(customer.buyerName())));
      available.add(FIELD_NAME);
    } else {
      missing.add(FIELD_NAME);
    }
    if (!customer.buyerEmail().isEmpty()) {
      masked.add(
          new PiiAccessSubjectField(
              FIELD_EMAIL, fieldLabel(FIELD_EMAIL), maskEmail(customer.buyerEmail())));
      available.add(FIELD_EMAIL);
    } else {
      missing.add(FIELD_EMAIL);
    }
    // 🔴 crm 这个来源**给不了**电话与地址 ⇒ 标成「上游没给」，**不是**「空值」。
    //    （这两栏不是「这个人没有电话」，是「本仓读到的这个来源不提供电话」——
    //      两者在界面上必须分开，否则客服会以为这个人没留电话。）
    missing.add(FIELD_PHONE);
    missing.add(FIELD_ADDRESS);
    return new PiiAccessSubjectView(
        "CUSTOMER",
        customer.buyerRef(),
        subjectLabel(customer.buyerRef(), customer.buyerName()),
        true,
        masked,
        available,
        missing,
        "15 客户 · 买家联系方式（crm）",
        customer.note());
  }

  // ================================================================ 查看主体

  /**
   * 查看一个主体的 PII。
   *
   * <p>🔴 <b>默认脱敏</b>：{@code reveal=false} 时返回的永远是脱敏值。
   *
   * <p>🔴 {@code reveal=true} 时<b>必须指明字段</b>（{@code fields}）——
   * 不指明就默认「全字段」等于给了一个「一键看全部」的形状。
   *
   * <p>🔴 <b>被拒时返回 outcome 而不是抛异常</b>：见 {@link PiiAccessRevealOutcome} 的说明
   * —— 越权尝试必须留在审计里，不能被事务回滚带走。
   */
  public PiiAccessRevealOutcome subject(
      AccessService.Access actor,
      String bearer,
      String kind,
      String ref,
      boolean reveal,
      List<String> fields,
      String reason) {

    UUID tenant = currentTenant();
    String subjectKind = normalizeKind(kind);
    String subjectRef = nz(ref).trim();
    if (subjectRef.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "主体标识不能为空");
    }

    Map<String, String> plain = new LinkedHashMap<>();
    String label = subjectRef;
    boolean known = false;
    RawAddress rawAddress = null;

    if ("ORDER".equals(subjectKind)) {
      PiiAccessSourcePort.SourceSubject facts = source.orderFacts(bearer, subjectRef);
      known = facts.known();
      if (known && facts.address() != null) {
        PiiAccessSourcePort.SourceAddress address = facts.address();
        plain.put(FIELD_NAME, nz(address.recipientName()));
        plain.put(FIELD_PHONE, nz(address.recipientPhone()));
        plain.put(FIELD_EMAIL, nz(address.recipientEmail()));
        rawAddress =
            new RawAddress(
                nz(address.countryCode()),
                nz(address.province()),
                nz(address.city()),
                nz(address.district()),
                nz(address.postcode()),
                nz(address.addressLine1()),
                nz(address.addressLine2()));
        plain.put(FIELD_ADDRESS, addressFullText(rawAddress));
        label = subjectLabel(subjectRef, address.recipientName());
      }
    } else {
      PiiAccessSourcePort.SourceCustomer customer = source.customer(bearer, subjectRef);
      known = customer.known();
      if (known) {
        plain.put(FIELD_NAME, customer.buyerName());
        plain.put(FIELD_EMAIL, customer.buyerEmail());
        label = subjectLabel(subjectRef, customer.buyerName());
      }
    }

    if (!known) {
      // 🔴 查无此人：返回 known=false，**不冒充「这个人没有 PII」**。
      // 也没有 PII 被读出来 ⇒ 这一笔不写访问审计（没有发生一次「访问」）。
      PiiAccessSubjectDetail detail =
          new PiiAccessSubjectDetail(
              subjectKind, subjectRef, subjectRef, false, List.of(), false,
              "上游查无这个主体 —— 「查无此人」不等于「这个人没有 PII」。", List.of());
      return new PiiAccessRevealOutcome(true, "", detail);
    }

    // ---- 默认路径：脱敏视图 ----
    // 🔴 每个字段的 present 标志回答的是「**上游给没给这个字段**」，
    //    不是「这个字段是不是空串」—— 两者在界面上必须分开。
    List<PiiAccessFieldValue> maskedFields = new ArrayList<>();
    for (String field : FIELDS) {
      String value = plain.get(field);
      boolean has = value != null && !value.isBlank();
      maskedFields.add(
          new PiiAccessFieldValue(
              field,
              fieldLabel(field),
              has ? maskOf(field, value, rawAddress) : "",
              true,
              has));
    }

    if (!reveal) {
      auditMasked(actor, subjectKind, subjectRef, label);
      PiiAccessSubjectDetail detail =
          new PiiAccessSubjectDetail(
              subjectKind, subjectRef, label, true, maskedFields, false,
              "默认脱敏展示：返回的是脱敏值。要看完整值必须走一次授权判定。", List.of());
      return new PiiAccessRevealOutcome(true, "", detail);
    }

    // ---- 完整值路径 ----
    List<String> requested = new ArrayList<>();
    if (fields != null) {
      for (String raw : fields) {
        String field = nz(raw).trim().toUpperCase(Locale.ROOT);
        if (field.isEmpty()) {
          continue;
        }
        if (!FIELDS.contains(field)) {
          throw new ResponseStatusException(
              HttpStatus.BAD_REQUEST, "字段类别不可识别：" + raw + "（可用：NAME / PHONE / EMAIL / ADDRESS）");
        }
        if (!requested.contains(field)) {
          requested.add(field);
        }
      }
    }
    if (requested.isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "查看完整值必须指明字段类别（fields=NAME / PHONE / EMAIL / ADDRESS）");
    }

    OffsetDateTime now = dbNow();
    Set<String> roleCodes = actorRoleCodes(actor);
    boolean hasDepartment = actor.departmentId() != null;
    Boolean handledByActor = null;
    if ("CUSTOMER".equals(subjectKind)) {
      handledByActor = source.customerHandledBy(bearer, subjectRef, actor.userId()).handledByActor();
    }

    List<PiiAccessFieldDecision> decisions = new ArrayList<>();
    List<String> denied = new ArrayList<>();
    for (String field : requested) {
      List<GrantFact> grants = effectiveGrants(field, now);
      Decision decision = decide(grants, roleCodes, field, hasDepartment, handledByActor);
      decisions.add(
          new PiiAccessFieldDecision(
              field, decision.allowed(), decision.reason(), decision.roleCode(), decision.grantId()));
      if (!decision.allowed()) {
        denied.add(field);
      }
    }

    if (!denied.isEmpty()) {
      // 🔴 越权尝试**也要留痕**（而且不能被回滚带走 ⇒ 这里返回而不是抛异常）。
      for (PiiAccessFieldDecision decision : decisions) {
        if (decision.allowed()) {
          continue;
        }
        auditDecision(
            actor,
            subjectKind,
            subjectRef,
            label,
            decision.fieldCode(),
            "VIEW_FULL",
            "DENIED",
            null,
            "",
            decision.reason(),
            nz(reason),
            null);
      }
      String denialReason =
          "没有授权查看"
              + denied.stream().map(PiiAccessRules::fieldLabel).toList()
              + "的完整值："
              + decisions.stream()
                  .filter(d -> !d.allowed())
                  .map(PiiAccessFieldDecision::reason)
                  .reduce((a, b) -> a + "；" + b)
                  .orElse("");
      PiiAccessSubjectDetail detail =
          new PiiAccessSubjectDetail(
              subjectKind, subjectRef, label, true, maskedFields, false, denialReason, decisions);
      return new PiiAccessRevealOutcome(false, denialReason, detail);
    }

    // ---- 理由必填（策略或授权要求时）----
    EffectivePolicy policy = effectivePolicy();
    boolean reasonRequired = policy.requireReasonOnReveal();
    for (PiiAccessFieldDecision decision : decisions) {
      for (GrantFact grant : effectiveGrants(decision.fieldCode(), now)) {
        if (grant.grantId().equals(decision.grantId()) && grant.reasonRequired()) {
          reasonRequired = true;
        }
      }
    }
    if (reasonRequired && nz(reason).trim().isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY, "查看完整 PII 必须写明理由（reason 不能为空）");
    }

    // ---- 每日查看次数上限 ----
    if (policy.maxRevealPerDay() > 0) {
      long used = revealCountToday(actor.userId(), now);
      if (used >= policy.maxRevealPerDay()) {
        String denialReason =
            "今天的完整值查看次数已达上限（" + policy.maxRevealPerDay() + " 次）";
        for (String field : requested) {
          auditDecision(
              actor, subjectKind, subjectRef, label, field, "VIEW_FULL", "DENIED", null, "",
              denialReason, nz(reason), null);
        }
        PiiAccessSubjectDetail detail =
            new PiiAccessSubjectDetail(
                subjectKind, subjectRef, label, true, maskedFields, false, denialReason, decisions);
        return new PiiAccessRevealOutcome(false, denialReason, detail);
      }
    }

    // ---- 放行：返回完整值并逐字段留痕 ----
    List<PiiAccessFieldValue> revealedFields = new ArrayList<>(maskedFields);
    for (String field : requested) {
      String value = plain.get(field);
      boolean has = value != null && !value.isBlank();
      PiiAccessFieldValue plainValue =
          new PiiAccessFieldValue(field, fieldLabel(field), has ? value : "", false, has);
      int index = -1;
      for (int i = 0; i < revealedFields.size(); i++) {
        if (revealedFields.get(i).fieldCode().equals(field)) {
          index = i;
          break;
        }
      }
      if (index >= 0) {
        revealedFields.set(index, plainValue);
      } else {
        revealedFields.add(plainValue);
      }
      PiiAccessFieldDecision decision =
          decisions.stream()
              .filter(d -> d.fieldCode().equals(field))
              .findFirst()
              .orElse(null);
      auditDecision(
          actor,
          subjectKind,
          subjectRef,
          label,
          field,
          "VIEW_FULL",
          "ALLOWED",
          decision == null ? null : decision.grantId(),
          decision == null ? "" : decision.roleCode(),
          "",
          nz(reason),
          null);
    }

    PiiAccessSubjectDetail detail =
        new PiiAccessSubjectDetail(
            subjectKind,
            subjectRef,
            label,
            true,
            revealedFields,
            true,
            "已按授权返回完整值（每一次完整值查看都已留痕）。",
            decisions);
    return new PiiAccessRevealOutcome(true, "", detail);
  }

  /**
   * 某个字段的脱敏值。
   *
   * <p>🔴 地址走 {@link #maskAddress} 的**分段**规则（保留到区级），
   * 不能把拼好的整串再逐字掩一遍 —— 那会把区级也抹掉，与规则说的「保留到区级」相反。
   */
  private String maskOf(String field, String value, RawAddress address) {
    return switch (field) {
      case FIELD_NAME -> maskName(value);
      case FIELD_PHONE -> maskPhone(value);
      case FIELD_EMAIL -> maskEmail(value);
      case FIELD_ADDRESS ->
          address == null
              ? maskChars(value)
              : maskAddress(
                  address.country(), address.province(), address.city(), address.district(),
                  address.postcode(), address.line1(), address.line2());
      default -> maskChars(value);
    };
  }

  /** 地址的完整文本（授权后返回给调用方的那个值）。 */
  private static String addressFullText(RawAddress address) {
    return (nz(address.country()) + " " + nz(address.province()) + " " + nz(address.city()) + " "
            + nz(address.district()) + " " + nz(address.postcode()) + " " + nz(address.line1())
            + " " + nz(address.line2()))
        .trim();
  }

  /** 收货地址的各个分段（脱敏与完整值共用同一份分段，避免两套口径）。 */
  private record RawAddress(
      String country, String province, String city, String district, String postcode,
      String line1, String line2) {}

  // ================================================================ 导出申请

  /** 导出申请列表（真分页 + 真筛选）。 */
  public PiiAccessExportPage exportRequests(String status, String kind, int page, int size) {
    UUID tenant = currentTenant();
    int safePage = Math.max(0, page);
    int safeSize = Math.min(100, Math.max(1, size));
    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    where.add("tenant_id = ?");
    binds.add(tenant);
    if (status != null && !status.isBlank()) {
      where.add("status = ?");
      binds.add(status.trim());
    }
    if (kind != null && !kind.isBlank()) {
      where.add("subject_kind = ?");
      binds.add(kind.trim());
    }
    String clause = " where " + String.join(" and ", where);

    long total =
        ((Number)
                sql.fetchValue(
                    "select count(*) from pii_access_export_request" + clause, binds.toArray()))
            .longValue();

    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(safeSize);
    pageBinds.add((long) safePage * safeSize);
    List<PiiAccessExportRequestView> items = new ArrayList<>();
    for (Record row :
        sql.fetch(
            "select id, request_no, subject_kind, field_code, record_count, max_records_limit,"
                + " purpose, status, requested_by, requested_by_name, requested_at, approved_by,"
                + " approved_by_name, approved_at, decision_note, executed_by, executed_at,"
                + " export_batch_id, expires_at, created_at"
                + " from pii_access_export_request"
                + clause
                + " order by requested_at desc, id desc limit ? offset ?",
            pageBinds.toArray())) {
      items.add(exportView(row));
    }

    Record counts =
        sql.fetchOne(
            "select count(*) as total,"
                + " count(*) filter (where status = 'PENDING') as pending,"
                + " count(*) filter (where status = 'APPROVED') as approved,"
                + " count(*) filter (where status = 'REJECTED') as rejected,"
                + " count(*) filter (where status = 'EXECUTED') as executed"
                + " from pii_access_export_request where tenant_id = ?",
            tenant);
    PiiAccessExportCounts summary =
        new PiiAccessExportCounts(
            counts.get("total", Long.class),
            counts.get("pending", Long.class),
            counts.get("approved", Long.class),
            counts.get("rejected", Long.class),
            counts.get("executed", Long.class));
    return new PiiAccessExportPage(items, safePage, safeSize, total, summary);
  }

  /** 导出申请详情（含清单）。 */
  public PiiAccessExportRequestView exportRequest(String requestId) {
    UUID tenant = currentTenant();
    UUID target = parseUuid(requestId);
    if (target == null) {
      throw notFound("导出申请不存在");
    }
    Record row =
        sql.fetchOne(
            "select id, request_no, subject_kind, field_code, record_count, max_records_limit,"
                + " purpose, status, requested_by, requested_by_name, requested_at, approved_by,"
                + " approved_by_name, approved_at, decision_note, executed_by, executed_at,"
                + " export_batch_id, expires_at, created_at"
                + " from pii_access_export_request where tenant_id = ? and id = ?",
            tenant,
            target);
    if (row == null) {
      throw notFound("导出申请不存在");
    }
    return exportView(row);
  }

  /**
   * 提交导出申请（**必须走审批**）。
   *
   * <p>🔴 <b>导出对象必须逐条列明</b>：{@code subjectRefs} 的大小必须等于 {@code recordCount}，
   * 且不得超过租户的条数上限 —— 没有「一键全量导出」这个形状。
   */
  public PiiAccessExportRequestView createExportRequest(
      AccessService.Access actor, PiiAccessExportRequestInput input) {
    UUID tenant = currentTenant();
    EffectivePolicy policy = effectivePolicy();
    String kind = input.subjectKind().trim();
    String field = input.fieldCode().trim();

    List<String> refs = new ArrayList<>();
    for (String raw : input.subjectRefs()) {
      String ref = nz(raw).trim();
      if (ref.isEmpty()) {
        continue;
      }
      if (!refs.contains(ref)) {
        refs.add(ref);
      }
    }
    if (refs.isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY, "导出申请必须逐条列出主体（subjectRefs 不能为空）");
    }
    if (refs.size() != input.recordCount()) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "导��条数（" + refs.size() + "）与申请条数（" + input.recordCount() + "）不一致");
    }
    if (refs.size() > policy.maxExportRecords()) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "单次导出条数（" + refs.size() + "）超过上限（" + policy.maxExportRecords() + "）"
              + (policy.configured() ? "" : "；当前用的是系统口径上限 100"));
    }
    // 🔴 上游给不了的字段**不许申请导出**：crm 侧只提供姓名与邮箱，
    //    申请导出电话只会得到一份空清单 —— 让它在申请这一步就被拒掉。
    if ("CUSTOMER".equals(kind) && !FIELD_NAME.equals(field) && !FIELD_EMAIL.equals(field)) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "15 客户（crm）这个来源只提供姓名与邮箱，没有" + fieldLabel(field)
              + " —— 不接受导出上游给不了的字段");
    }

    OffsetDateTime now = dbNow();
    UUID id = Ids.next();
    String requestNo = exportNo("PE", id);
    OffsetDateTime expiresAt = now.plusHours(policy.exportApprovalTtlHours());
    sql.execute(
        """
        insert into pii_access_export_request
          (id, tenant_id, request_no, subject_kind, field_code, record_count, max_records_limit,
           purpose, status, requested_by, requested_by_name, requested_at, expires_at,
           created_at, updated_at)
        values (?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?, ?::timestamptz, ?::timestamptz,
                ?::timestamptz, ?::timestamptz)
        """,
        id,
        tenant,
        requestNo,
        kind,
        field,
        refs.size(),
        policy.maxExportRecords(),
        input.purpose().trim(),
        actor.userId(),
        "",
        now,
        expiresAt,
        now,
        now);

    for (String ref : refs) {
      sql.execute(
          """
          insert into pii_access_export_item
            (id, tenant_id, export_request_id, subject_kind, subject_ref, subject_label)
          values (?, ?, ?, ?, ?, ?)
          """,
          Ids.next(),
          tenant,
          id,
          kind,
          ref,
          ref);
    }
    return exportRequest(id.toString());
  }

  /**
   * 批准导出申请。
   *
   * <p>🔴 <b>审批人 ≠ 申请人</b>：自己批自己的审批形同虚设。
   */
  public PiiAccessExportRequestView approveExport(
      AccessService.Access actor, String requestId, PiiAccessExportDecisionInput input) {
    UUID tenant = currentTenant();
    UUID target = parseUuid(requestId);
    if (target == null) {
      throw notFound("导出申请不存在");
    }
    Record row =
        sql.fetchOne(
            "select id, status, requested_by from pii_access_export_request"
                + " where tenant_id = ? and id = ?",
            tenant,
            target);
    if (row == null) {
      throw notFound("导出申请不存在");
    }
    if (!"PENDING".equals(row.get("status", String.class))) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "这张导出申请已经处理过了（当前状态：" + row.get("status", String.class) + "）");
    }
    if (actor.userId().equals(row.get("requested_by", UUID.class))) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, "审批人不能是申请人本人 —— 自己批自己的审批形同虚设");
    }
    EffectivePolicy policy = effectivePolicy();
    OffsetDateTime now = dbNow();
    sql.execute(
        """
        update pii_access_export_request
           set status = 'APPROVED', approved_by = ?, approved_by_name = ?, approved_at = ?::timestamptz,
               decision_note = ?, expires_at = ?::timestamptz, updated_at = ?::timestamptz
         where tenant_id = ? and id = ?
        """,
        actor.userId(),
        "",
        now,
        nz(input == null ? null : input.decisionNote()),
        now.plusHours(policy.exportApprovalTtlHours()),
        now,
        tenant,
        target);
    return exportRequest(target.toString());
  }

  /** 驳回导出申请（**驳回理由必填**）。 */
  public PiiAccessExportRequestView rejectExport(
      AccessService.Access actor, String requestId, PiiAccessExportDecisionInput input) {
    UUID tenant = currentTenant();
    UUID target = parseUuid(requestId);
    if (target == null) {
      throw notFound("导出申请不存在");
    }
    Record row =
        sql.fetchOne(
            "select id, status, requested_by from pii_access_export_request"
                + " where tenant_id = ? and id = ?",
            tenant,
            target);
    if (row == null) {
      throw notFound("导出申请不存在");
    }
    if (!"PENDING".equals(row.get("status", String.class))) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "这张导出申请已经处理过了（当前状态：" + row.get("status", String.class) + "）");
    }
    if (actor.userId().equals(row.get("requested_by", UUID.class))) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, "审批人不能是申请人本人 —— 自己批自己的审批形同虚设");
    }
    String note = nz(input == null ? null : input.decisionNote()).trim();
    if (note.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "驳回必须写明理由");
    }
    OffsetDateTime now = dbNow();
    sql.execute(
        """
        update pii_access_export_request
           set status = 'REJECTED', approved_by = ?, approved_by_name = ?, approved_at = ?::timestamptz,
               decision_note = ?, updated_at = ?::timestamptz
         where tenant_id = ? and id = ?
        """,
        actor.userId(),
        "",
        now,
        note,
        now,
        tenant,
        target);
    return exportRequest(target.toString());
  }

  /**
   * 执行导出（**必须已审批**）。
   *
   * <p>🔴 被拒时（未审批 / 审批过期 / 超出当前上限）返回 outcome 而不是抛异常 ——
   * 那几次尝试要留在审计表里，不能被事务回滚带走。
   */
  public PiiAccessExportOutcome executeExport(
      AccessService.Access actor, String bearer, String requestId) {
    UUID tenant = currentTenant();
    UUID target = parseUuid(requestId);
    if (target == null) {
      throw notFound("导出申请不存在");
    }
    Record row =
        sql.fetchOne(
            "select id, request_no, subject_kind, field_code, record_count, max_records_limit,"
                + " status, expires_at from pii_access_export_request where tenant_id = ? and id = ?",
            tenant,
            target);
    if (row == null) {
      throw notFound("导出申请不存在");
    }
    String status = row.get("status", String.class);
    String kind = row.get("subject_kind", String.class);
    String field = row.get("field_code", String.class);
    OffsetDateTime now = dbNow();

    if (!"APPROVED".equals(status)) {
      String denial =
          "这张导出申请还没有获批（当前状态：" + status + "）—— 未审批的导出请求一律拒绝";
      auditExport(actor, kind, row.get("request_no", String.class), field, "DENIED", denial, target);
      return new PiiAccessExportOutcome(false, denial, null);
    }
    OffsetDateTime expiresAt = row.get("expires_at", OffsetDateTime.class);
    if (expiresAt != null && now.isAfter(expiresAt)) {
      String denial = "这张导出申请的审批已过期（有效期至 " + expiresAt + "）";
      auditExport(actor, kind, row.get("request_no", String.class), field, "DENIED", denial, target);
      return new PiiAccessExportOutcome(false, denial, null);
    }
    EffectivePolicy policy = effectivePolicy();
    if (row.get("record_count", Integer.class) > policy.maxExportRecords()) {
      String denial =
          "这张导出申请（"
              + row.get("record_count", Integer.class)
              + " 条）超过当前的导出上限（"
              + policy.maxExportRecords()
              + " 条）";
      auditExport(actor, kind, row.get("request_no", String.class), field, "DENIED", denial, target);
      return new PiiAccessExportOutcome(false, denial, null);
    }

    // ---- 取清单，逐条读 PII ----
    List<PiiAccessExportItemView> items = exportItems(target);
    List<PiiAccessExportedRow> rows = new ArrayList<>();
    for (PiiAccessExportItemView item : items) {
      String value = readPiiValue(bearer, kind, item.subjectRef(), field);
      rows.add(
          new PiiAccessExportedRow(
              kind, item.subjectRef(), item.subjectLabel(), value, value != null && !value.isBlank()));
    }
    if (rows.isEmpty()) {
      String denial = "导出清单里的主体在上游已经不存在，没有可导出的数据";
      auditExport(actor, kind, row.get("request_no", String.class), field, "DENIED", denial, target);
      return new PiiAccessExportOutcome(false, denial, null);
    }

    // ---- 落导出凭据 ----
    UUID batchId = Ids.next();
    String exportNo = exportNo("EX", batchId);
    List<String> refs = new ArrayList<>();
    for (PiiAccessExportItemView item : items) {
      refs.add(item.subjectRef());
    }
    sql.execute(
        """
        insert into pii_access_export_batch
          (id, tenant_id, export_request_id, export_no, record_count, field_code, subject_digest,
           exported_by, exported_by_name, exported_at, created_at)
        values (?, ?, ?, ?, ?, ?, ?, ?, '', ?::timestamptz, ?::timestamptz)
        """,
        batchId,
        tenant,
        target,
        exportNo,
        rows.size(),
        field,
        digest(refs),
        actor.userId(),
        now,
        now);

    sql.execute(
        """
        update pii_access_export_request
           set status = 'EXECUTED', executed_by = ?, executed_at = ?::timestamptz,
               export_batch_id = ?, updated_at = ?::timestamptz
         where tenant_id = ? and id = ?
        """,
        actor.userId(),
        now,
        batchId,
        now,
        tenant,
        target);

    for (PiiAccessExportedRow exported : rows) {
      auditExport(
          actor,
          kind,
          exported.subjectRef(),
          field,
          "ALLOWED",
          "",
          target);
    }

    PiiAccessExportResult result =
        new PiiAccessExportResult(
            target,
            row.get("request_no", String.class),
            exportNo,
            batchId,
            rows.size(),
            field,
            now,
            rows);
    return new PiiAccessExportOutcome(true, "", result);
  }

  /** 从上游读一个主体的某个字段的**完整值**（读不到 ⇒ null，不是空串）。 */
  private String readPiiValue(String bearer, String kind, String ref, String field) {
    if ("ORDER".equals(kind)) {
      PiiAccessSourcePort.SourceSubject facts = source.orderFacts(bearer, ref);
      if (!facts.known() || facts.address() == null) {
        return null;
      }
      PiiAccessSourcePort.SourceAddress address = facts.address();
      return switch (field) {
        case FIELD_NAME -> nz(address.recipientName());
        case FIELD_PHONE -> nz(address.recipientPhone());
        case FIELD_EMAIL -> nz(address.recipientEmail());
        case FIELD_ADDRESS ->
            (nz(address.countryCode()) + " " + nz(address.province()) + " " + nz(address.city()) + " "
                    + nz(address.district()) + " " + nz(address.postcode()) + " "
                    + nz(address.addressLine1()) + " " + nz(address.addressLine2()))
                .trim();
        default -> null;
      };
    }
    PiiAccessSourcePort.SourceCustomer customer = source.customer(bearer, ref);
    if (!customer.known()) {
      return null;
    }
    return switch (field) {
      case FIELD_NAME -> customer.buyerName();
      case FIELD_EMAIL -> customer.buyerEmail();
      default -> null;
    };
  }

  private List<PiiAccessExportItemView> exportItems(UUID requestId) {
    List<PiiAccessExportItemView> items = new ArrayList<>();
    for (Record row :
        sql.fetch(
            "select id, subject_kind, subject_ref, subject_label from pii_access_export_item"
                + " where tenant_id = ? and export_request_id = ? order by subject_ref",
            currentTenant(),
            requestId)) {
      items.add(
          new PiiAccessExportItemView(
              row.get("id", UUID.class),
              row.get("subject_kind", String.class),
              row.get("subject_ref", String.class),
              row.get("subject_label", String.class)));
    }
    return items;
  }

  private PiiAccessExportRequestView exportView(Record row) {
    UUID id = row.get("id", UUID.class);
    String status = row.get("status", String.class);
    String field = row.get("field_code", String.class);
    return new PiiAccessExportRequestView(
        id,
        row.get("request_no", String.class),
        row.get("subject_kind", String.class),
        field,
        fieldLabel(field),
        row.get("record_count", Integer.class),
        row.get("max_records_limit", Integer.class),
        row.get("purpose", String.class),
        status,
        statusLabel(status),
        row.get("requested_by", UUID.class),
        row.get("requested_by_name", String.class),
        row.get("requested_at", OffsetDateTime.class),
        row.get("approved_by", UUID.class),
        row.get("approved_by_name", String.class),
        row.get("approved_at", OffsetDateTime.class),
        row.get("decision_note", String.class),
        row.get("executed_by", UUID.class),
        row.get("executed_at", OffsetDateTime.class),
        row.get("export_batch_id", UUID.class),
        row.get("expires_at", OffsetDateTime.class),
        exportItems(id),
        row.get("created_at", OffsetDateTime.class));
  }

  private static String statusLabel(String status) {
    return switch (status) {
      case "PENDING" -> "待审批";
      case "APPROVED" -> "已批准（未执行）";
      case "REJECTED" -> "已驳回";
      case "EXECUTED" -> "已导出";
      default -> status;
    };
  }

  // ================================================================ 审计

  /** 访问审计（真分页 + 真筛选 + 与分页无关的汇总）。 */
  public PiiAccessAuditPage audit(
      String kind, String subjectRef, String action, String decision, String actorId, int page, int size) {
    UUID tenant = currentTenant();
    int safePage = Math.max(0, page);
    int safeSize = Math.min(100, Math.max(1, size));
    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    where.add("tenant_id = ?");
    binds.add(tenant);
    if (kind != null && !kind.isBlank()) {
      where.add("subject_kind = ?");
      binds.add(kind.trim());
    }
    if (subjectRef != null && !subjectRef.isBlank()) {
      where.add("subject_ref = ?");
      binds.add(subjectRef.trim());
    }
    if (action != null && !action.isBlank()) {
      where.add("action = ?");
      binds.add(action.trim());
    }
    if (decision != null && !decision.isBlank()) {
      where.add("decision = ?");
      binds.add(decision.trim());
    }
    if (actorId != null && !actorId.isBlank()) {
      where.add("actor_id::text = ?");
      binds.add(actorId.trim());
    }
    String clause = " where " + String.join(" and ", where);

    long total =
        ((Number)
                sql.fetchValue("select count(*) from pii_access_view_audit" + clause, binds.toArray()))
            .longValue();

    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(safeSize);
    pageBinds.add((long) safePage * safeSize);
    List<PiiAccessAuditRow> items = new ArrayList<>();
    for (Record row :
        sql.fetch(
            "select id, subject_kind, subject_ref, subject_label, field_code, action, decision,"
                + " grant_id, grant_role_code, decision_reason, actor_id, actor_name, reason,"
                + " export_request_id, occurred_at"
                + " from pii_access_view_audit"
                + clause
                + " order by occurred_at desc, id desc limit ? offset ?",
            pageBinds.toArray())) {
      items.add(
          new PiiAccessAuditRow(
              row.get("id", UUID.class),
              row.get("subject_kind", String.class),
              row.get("subject_ref", String.class),
              row.get("subject_label", String.class),
              row.get("field_code", String.class),
              row.get("action", String.class),
              row.get("decision", String.class),
              row.get("grant_id", UUID.class),
              row.get("grant_role_code", String.class),
              row.get("decision_reason", String.class),
              row.get("actor_id", UUID.class),
              row.get("actor_name", String.class),
              row.get("reason", String.class),
              row.get("export_request_id", UUID.class),
              row.get("occurred_at", OffsetDateTime.class)));
    }

    Record counts =
        sql.fetchOne(
            "select count(*) as total,"
                + " count(*) filter (where action = 'VIEW_MASKED') as masked,"
                + " count(*) filter (where action = 'VIEW_FULL') as full,"
                + " count(*) filter (where action = 'EXPORT') as export,"
                + " count(*) filter (where decision = 'DENIED') as denied"
                + " from pii_access_view_audit where tenant_id = ?",
            tenant);
    PiiAccessAuditCounts summary =
        new PiiAccessAuditCounts(
            counts.get("total", Long.class),
            counts.get("masked", Long.class),
            counts.get("full", Long.class),
            counts.get("export", Long.class),
            counts.get("denied", Long.class));
    return new PiiAccessAuditPage(items, safePage, safeSize, total, summary);
  }

  /** 脱敏视图的审计行。 */
  private void auditMaskedView(AccessService.Access actor, PiiAccessSubjectView view) {
    auditMasked(actor, view.subjectKind(), view.subjectRef(), view.subjectLabel());
  }

  /** 脱敏视图的审计行（只记「谁看了哪个主体的脱敏视图」）。 */
  private void auditMasked(
      AccessService.Access actor, String subjectKind, String subjectRef, String subjectLabel) {
    auditDecision(
        actor,
        subjectKind,
        subjectRef,
        subjectLabel,
        AUDIT_FIELD_ALL,
        "VIEW_MASKED",
        "ALLOWED",
        null,
        "",
        "",
        "",
        null);
  }

  /** 一条审计行。 */
  private void auditDecision(
      AccessService.Access actor,
      String subjectKind,
      String subjectRef,
      String subjectLabel,
      String fieldCode,
      String action,
      String decision,
      UUID grantId,
      String grantRoleCode,
      String decisionReason,
      String reason,
      UUID exportRequestId) {
    sql.execute(
        """
        insert into pii_access_view_audit
          (id, tenant_id, subject_kind, subject_ref, subject_label, field_code, action, decision,
           grant_id, grant_role_code, decision_reason, actor_id, actor_name, reason,
           export_request_id, occurred_at, created_at)
        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, '', ?, ?, ?::timestamptz, now())
        """,
        Ids.next(),
        currentTenant(),
        subjectKind,
        subjectRef,
        subjectLabel,
        fieldCode,
        action,
        decision,
        grantId,
        grantRoleCode,
        decisionReason,
        actor.userId(),
        reason,
        exportRequestId,
        dbNow());
  }

  /** 导出相关的审计行（放行与被拒都记）。 */
  private void auditExport(
      AccessService.Access actor,
      String subjectKind,
      String subjectRef,
      String fieldCode,
      String decision,
      String decisionReason,
      UUID exportRequestId) {
    auditDecision(
        actor,
        subjectKind,
        subjectRef,
        subjectRef,
        fieldCode,
        "EXPORT",
        decision,
        null,
        "",
        decisionReason,
        "",
        exportRequestId);
  }

  /** 今天（UTC）这个成员已用掉的完整值查看次数。 */
  private long revealCountToday(UUID actorId, OffsetDateTime now) {
    OffsetDateTime dayStart = now.withHour(0).withMinute(0).withSecond(0).withNano(0);
    Object value =
        sql.fetchValue(
            "select count(*) from pii_access_view_audit"
                + " where tenant_id = ? and actor_id = ? and action = 'VIEW_FULL'"
                + " and decision = 'ALLOWED' and occurred_at >= ?::timestamptz",
            currentTenant(),
            actorId,
            dayStart);
    return value == null ? 0L : ((Number) value).longValue();
  }

  // ================================================================ 工具

  /** 该成员命中的角色 code 集合。 */
  private Set<String> actorRoleCodes(AccessService.Access actor) {
    Set<String> codes = new LinkedHashSet<>();
    if (actor.grants() != null) {
      for (AccessService.Grant grant : actor.grants()) {
        if (grant.roleCode() != null && !grant.roleCode().isBlank()) {
          codes.add(grant.roleCode());
        }
      }
    }
    return codes;
  }

  private String normalizeKind(String kind) {
    String k = nz(kind).trim().toUpperCase(Locale.ROOT);
    if (k.isEmpty()) {
      return "ORDER";
    }
    if (!"ORDER".equals(k) && !"CUSTOMER".equals(k)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "主体类型不可识别：" + kind + "（可用：CUSTOMER / ORDER）");
    }
    return k;
  }

  private String sourceLabel(String kind) {
    return "ORDER".equals(kind) ? "9 订单 · 收货地址快照（trade）" : "15 客户 · 买家联系方式（crm）";
  }

  /** 导出单号（租户内唯一）。 */
  private String exportNo(String prefix, UUID id) {
    return prefix
        + "-"
        + dbNow().toLocalDate().toString().replace("-", "")
        + "-"
        + id.toString().replace("-", "").substring(0, 10).toUpperCase(Locale.ROOT);
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
