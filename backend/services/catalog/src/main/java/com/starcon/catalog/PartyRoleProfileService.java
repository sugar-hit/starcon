package com.starcon.catalog;

import static com.starcon.catalog.CatalogModels.*;
import static com.starcon.catalog.PartyRoleProfileRules.*;

import com.starcon.tenancy.Ids;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 3.6.1.2 角色档案：在往来单位的每个角色上挂载角色特有信息 ——
 * 供应商（账期 / MOQ / 合作等级 / 结算方式）、客户（分级 / 默认币种 / 信用额度 / 账期）、
 * 承运商（接口类型 / 账号 / 服务能力），以及蓝图点名的输出「供 06 采购单、12 应收应付默认取值」。
 *
 * <p>本类的纪律：
 *
 * <ol>
 *   <li><b>三张表，一个主体的每个身份各挂一份</b>。蓝图那句「每个角色一张 1:1 档案表」
 *       是本模块存在的理由：把账期与信用额度放进同一张表，「客户没设额度」与
 *       「这一列不属于客户」就都是同一种存储形态（NULL），而业务上它们是<b>相反</b>的两种默认
 *       （不限制赊账 vs 完全不许赊）。理由见 {@code V30__party_role_profiles.sql} 第一节。
 *   <li><b>档案键在角色 id 上</b>（{@code role_id}），不是 {@code party_id}。
 *       3.6.1.1 的 {@code party_role} 上一个主体最多挂三个角色，所以
 *       {@code (tenant_id,party_id)} 上建不出外键可指的唯一列组；而
 *       {@code UNIQUE(tenant_id,id)} 是它真实存在的唯一列组。键在角色上之后
 *       「一个主体最多一份供应商档案」由两条传递性保证自动成立，且
 *       <b>不需要给已交付的 {@code party_role} 补任何索引或约束</b>。解释见 V30 第二节。
 *   <li><b>默认值只有一处判定</b>。{@link #warnings} 是本模块交给 06 / 12 的那份口径，
 *       三个读出口（单张详情 / 汇总 / 列表）都调它。前端只把文案照原样显示，
 *       不在浏览器里再判一次 —— 两处各判一次的症状是「页面说没有起订量、
 *       采购单按起订量拦了这张单」。
 *   <li><b>缺档案不是一个错误状态</b>。GET 在「没有对应角色」或「有角色但没建档案」时
 *       返回 {@code profileFound=false} 而不是 404：详情页要能把
 *       「没指派供应商身份」「指派了但还没填档案」「填了」三种状态<b>长得不一样</b>，
 *       而 404 只能表达成「什么都没了」。真正的主体不存在才 404。
 *       写入路径相反：没有对应角色时给可读的 409（规则 **R3**）。
 *   <li><b>档案可删</b>（规则 **R5**）。解指派角色是 3.6.1.1 的动作；本模块删的只是
 *       挂在角色上的那一层默认值，删掉之后 06/12 退回"没设过"，而这是一个可逆、有意义的状态。
 *   <li><b>不加不归本模块的列</b>。「已用额度」不在这里 —— 那要读 12 自己的应收台账，
 *       跨服务读表是 AGENTS.md 明令禁止的（V30 第八节）。
 * </ol>
 *
 * <p>权限口径：读 {@code catalog.spu.read}，写 {@code catalog.spu.update}，
 * 与 3.6.1.1 完全一致 —— 角色档案是商品主数据域里往来单位档案的下半部分，
 * 不新造权限词表（那要改 identity 服务的迁移，属于另一个服务的写入所有权）。
 */
@Service
public class PartyRoleProfileService {
  /** 单页最大行数。角色档案是租户级主数据，行数量级在千位，与 PartyService 同口径。 */
  private static final int MAX_PAGE_SIZE = 200;

  /**
   * 三张档案表的元信息：表名（同时作为别名）、读出列、分级列、附加 JOIN。
   *
   * <p>表名是<b>受控字面量</b>（这三个字符串在本文件里写死），不是用户输入 ——
   * {@code roleTypeFor} / {@link #profileKindProblem} 先把路径段判成词表里的值，
   * 判不过就抛 400，所以不存在把用户输入拼进 SQL 的路径。
   * 与 PartyService 里 {@code FROM_PARTY} 那种受控常量同一个理由。
   */
  private record ProfileTable(String table, String columns, String levelColumn, String extraJoin) {}

  private static final ProfileTable SUPPLIER_TABLE =
      new ProfileTable(
          "party_supplier_profile sp",
          "sp.payment_terms_days,sp.moq_quantity,sp.moq_unit_code,sp.cooperation_level,"
              + "sp.settlement_method,sp.note,u.name as moq_unit_name",
          "sp.cooperation_level",
          // MOQ 的采购单位要显示中文名而不是代码。与 V30 的外键一致（停用单位仍可引用，
          // 因为历史采购单上的冻结快照要继续显示）。
          " left join unit_of_measure u on u.tenant_id=sp.tenant_id and u.code=sp.moq_unit_code");

  private static final ProfileTable CUSTOMER_TABLE =
      new ProfileTable(
          "party_customer_profile sp",
          "sp.customer_level,sp.default_currency,sp.default_credit_limit,sp.payment_terms_days,sp.note",
          "sp.customer_level",
          "");

  private static final ProfileTable CARRIER_TABLE =
      new ProfileTable(
          "party_carrier_profile sp",
          "sp.interface_type,sp.account_ref,sp.service_capabilities,sp.service_scope_note,sp.note",
          "sp.interface_type",
          "");

  /** 主体 + 角色 + 档案的联合查询（三张表共用，靠 {@link ProfileTable} 区分列）。 */
  private static final String BASE_FROM =
      " from party_role r join party p on p.tenant_id=r.tenant_id and p.id=r.party_id"
          + " left join ";

  private final DSLContext sql;

  public PartyRoleProfileService(DSLContext sql) {
    this.sql = sql;
  }

  // ================================================================ 概览

  /**
   * 概览：三种角色的覆盖率与两个治理缺口指标。
   *
   * <p>角色数与档案数<b>都只数 ACTIVE 角色</b>：已停用的身份当下不会被 06/12 用来取默认值，
   * 把它算进分母会让覆盖率看起来永远差一点。两个"应为 0"的探针
   * （风险客户仍有额度、有账期但结算方式非账期）用来发现绕过服务层写进来的脏数据 ——
   * V30 的两条 DB CHECK 理论上让它们不可能出现，而"理论上"三个字正需要一条读出口来验证。
   */
  public PartyRoleProfileOverview overview() {
    UUID tenantId = currentTenantId();
    long supplierRoles = activeRoleCount(tenantId, ROLE_SUPPLIER);
    long customerRoles = activeRoleCount(tenantId, ROLE_CUSTOMER);
    long carrierRoles = activeRoleCount(tenantId, ROLE_CARRIER);
    long supplierProfiles = profileCount(tenantId, "party_supplier_profile", ROLE_SUPPLIER);
    long customerProfiles = profileCount(tenantId, "party_customer_profile", ROLE_CUSTOMER);
    long carrierProfiles = profileCount(tenantId, "party_carrier_profile", ROLE_CARRIER);
    long risky =
        sql.fetchOne(
                "select count(*) from party_role r join party p on p.tenant_id=r.tenant_id"
                    + " and p.id=r.party_id join party_customer_profile sp on sp.tenant_id=r.tenant_id"
                    + " and sp.role_id=r.id where r.tenant_id=? and r.role_type='CUSTOMER'"
                    + " and sp.customer_level='RISK' and sp.default_credit_limit is not null",
                tenantId)
            .get(0, Long.class);
    long termsWithoutCredit =
        sql.fetchOne(
                "select count(*) from party_role r join party p on p.tenant_id=r.tenant_id"
                    + " and p.id=r.party_id join party_supplier_profile sp on sp.tenant_id=r.tenant_id"
                    + " and sp.role_id=r.id where r.tenant_id=? and r.role_type='SUPPLIER'"
                    + " and sp.payment_terms_days>0 and sp.settlement_method<>'CREDIT'",
                tenantId)
            .get(0, Long.class);
    return new PartyRoleProfileOverview(
        supplierRoles,
        supplierProfiles,
        customerRoles,
        customerProfiles,
        carrierRoles,
        carrierProfiles,
        (supplierRoles - supplierProfiles) + (customerRoles - customerProfiles) + (carrierRoles - carrierProfiles),
        risky,
        termsWithoutCredit,
        "覆盖率只统计 ACTIVE 角色（已停用的身份当下不会被 06 采购 / 12 应收用来取默认值，"
            + "算进分母会让覆盖率永远差一点）。"
            + "missingProfileCount = 已指派身份却没建角色档案的条数，也就是下游取不到默认值的对手方。"
            + "最后两个指标正常都应为 0：它们是绕过服务层写进来的脏数据的探针。");
  }

  private long activeRoleCount(UUID tenantId, String roleType) {
    return sql.fetchOne(
            "select count(*) from party_role r join party p on p.tenant_id=r.tenant_id and p.id=r.party_id"
                + " where r.tenant_id=? and r.role_type=? and r.status='ACTIVE' and p.deleted_at is null",
            tenantId, roleType)
        .get(0, Long.class);
  }

  private long profileCount(UUID tenantId, String table, String roleType) {
    return sql.fetchOne(
            "select count(*) from party_role r join party p on p.tenant_id=r.tenant_id and p.id=r.party_id"
                + " join " + table + " sp on sp.tenant_id=r.tenant_id and sp.role_id=r.id"
                + " where r.tenant_id=? and r.role_type=? and r.status='ACTIVE' and p.deleted_at is null",
            tenantId, roleType)
        .get(0, Long.class);
  }

  // ================================================================ 词表选项

  /**
   * 词表选项：前端下拉的数据源。
   *
   * <p>与 {@link PartyRoleProfileRules} 的词表同源（不是前端各自维护一份），
   * 因为枚举只增不改（规则 **R6**）这条承诺要在**一处**兑现。
   */
  public PartyRoleProfileOptions options(String roleType) {
    String kind = normalizeEnum(roleType);
    String problem = profileKindProblem(kind);
    CatalogService.check(problem == null, problem);
    String type = roleTypeFor(kind);
    List<PartyRoleProfileRules.Option> levels =
        ROLE_SUPPLIER.equals(type) ? cooperationLevelOptions()
            : ROLE_CUSTOMER.equals(type) ? customerLevelOptions()
            : List.of();
    return new PartyRoleProfileOptions(
        type,
        PartyRules.roleLabel(type),
        levels,
        ROLE_SUPPLIER.equals(type) ? settlementMethodOptions() : List.of(),
        ROLE_CARRIER.equals(type) ? interfaceTypeOptions() : List.of(),
        ROLE_CARRIER.equals(type) ? capabilityOptions() : List.of(),
        "币种与采购单位刻意不在这里给列表：ISO 4217 词表是 12 财务的资产、计量单位是 3.3.3 的资产，"
            + "在本模块硬编码一份就等于复制了别人的写入所有权，而两份列表迟早会不一样。"
            + "这两项走自由输入 + 服务层格式校验。");
  }

  // ================================================================ 列表

  /**
   * 角色档案列表：按角色类型分页，支持关键词 / 分级 / 能力 / 结算方式 / 币种 /
   * 「只看缺档案」筛选。
   *
   * <p>计数与取行引<b>同一处</b>筛选条件构造（{@link #conditions}），
   * 而不是各拼一遍 —— 两处各拼一遍的症状是「页面说筛出 7 条，翻到第 2 页是空的」。
   *
   * <p>soft 删除的主体不出现在列表里（{@code p.deleted_at is null}），与 3.6.1.1 的
   * 引用候选同一口径：{@code includeBlocked} 放宽的是「状态」，不是「已删除」。
   */
  public PartyRoleProfilePage list(PartyRoleProfileQuery query) {
    int page = query.page();
    int size = Math.min(Math.max(query.size(), 1), MAX_PAGE_SIZE);
    String problem = profileKindProblem(query.roleType());
    CatalogService.check(problem == null, problem);
    String roleType = roleTypeFor(normalizeEnum(query.roleType()));
    ProfileTable table = tableFor(roleType);

    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    conditions(table, roleType, query, where, binds);
    String sqlWhere = where.isEmpty() ? "" : " and " + String.join(" and ", where);
    String from = BASE_FROM + table.table() + " on sp.tenant_id=r.tenant_id and sp.role_id=r.id"
        + table.extraJoin();

    long total = sql.fetchOne("select count(*)" + from + " where " + alwaysWhere() + sqlWhere,
        concat(List.<Object>of(currentTenantId(), roleType), binds).toArray()).get(0, Long.class);
    var rows =
        sql.fetch(
            "select p.id as party_id,p.code as party_code,p.name as party_name,"
                + "r.id as role_id,r.role_type,r.status as role_status,"
                + (table.columns().isEmpty() ? "null as note" : table.columns())
                + ",sp.role_id is not null as profile_found,sp.version as profile_version,"
                + "sp.updated_at as profile_updated_at"
                + from
                + " where "
                + alwaysWhere()
                + sqlWhere
                + " order by p.code,p.id limit ? offset ?",
            concat(
                    concat(List.<Object>of(currentTenantId(), roleType), binds),
                    List.of(size, (long) page * size))
                .toArray());
    return new PartyRoleProfilePage(
        rows.stream().map(r -> toRow(roleType, r)).toList(),
        total,
        page,
        size,
        "计数与本页取行引同一处筛选条件，所以「筛出来的总数」与「翻页翻到底」一定对得上。"
            + "角色档案列表只列未删除的主体；已停用的身份默认也排除"
            + "（activeOnly=false 才带上）—— 停用的身份当下不会被 06 采购 / 12 应收用来取默认值。");
  }

  /**
   * 每条查询都带的那一段：租户 + 角色类型 + 未删除。
   *
   * <p>与筛选条件<b>分开</b>且放在最前面：租户是安全边界（RLS 之外的第二道闸），
   * 放进可变的 {@code where} 拼装里早晚会被一次重构挪到别处，而那种挪动不报错，
   * 只是隔离口径悄悄变了一条。
   */
  private String alwaysWhere() {
    return "r.tenant_id=? and r.role_type=? and p.deleted_at is null";
  }

  private void conditions(
      ProfileTable table,
      String roleType,
      PartyRoleProfileQuery query,
      List<String> where,
      List<Object> binds) {
    if (query.keyword() != null && !query.keyword().isBlank()) {
      where.add(
          "(p.code ilike ? escape '!' or p.name ilike ? escape '!'"
              + " or p.email ilike ? escape '!' or p.phone ilike ? escape '!')");
      String pattern = "%" + escapeLike(query.keyword().trim()) + "%";
      // 四列各绑一次：四个 OR 分支各自能用上 V27 第四节那四条 pg_trgm GIN 索引。
      binds.add(pattern);
      binds.add(pattern);
      binds.add(pattern);
      binds.add(pattern);
    }
    if (query.level() != null && !query.level().isBlank()) {
      String level = normalizeEnum(query.level());
      String problem = levelProblem(roleType, level);
      CatalogService.check(problem == null, problem);
      where.add(table.levelColumn() + "=?");
      binds.add(level);
    }
    if (query.settlementMethod() != null && !query.settlementMethod().isBlank()) {
      CatalogService.check(ROLE_SUPPLIER.equals(roleType), "结算方式只对供应商档案有效");
      String method = normalizeSettlementMethod(query.settlementMethod());
      CatalogService.check(settlementMethodProblem(query.settlementMethod()) == null,
          "结算方式不在词表里");
      where.add("sp.settlement_method=?");
      binds.add(method);
    }
    if (query.defaultCurrency() != null && !query.defaultCurrency().isBlank()) {
      CatalogService.check(ROLE_CUSTOMER.equals(roleType), "默认币种只对客户档案有效");
      CatalogService.check(currencyProblem(query.defaultCurrency()) == null, "币种请用 ISO 4217 三位代码");
      where.add("sp.default_currency=?");
      binds.add(normalizeCurrency(query.defaultCurrency()));
    }
    if (query.capability() != null && !query.capability().isBlank()) {
      CatalogService.check(ROLE_CARRIER.equals(roleType), "服务能力只对承运商档案有效");
      String capability = normalizeEnum(query.capability());
      CatalogService.check(serviceCapabilitiesProblem(List.of(capability)) == null, "服务能力不在词表里");
      // @> 是数组包含：至少具备这一项能力。用 ARRAY[?] 而不是拼字符串（理由见 toPgArray）。
      where.add("sp.service_capabilities @> ARRAY[?]::text[]");
      binds.add(capability);
    }
    if (Boolean.TRUE.equals(query.missingProfile())) where.add("sp.role_id is null");
    // 默认只列 ACTIVE 角色；显式传 false 才把已停用的身份也带上。
    if (!Boolean.FALSE.equals(query.activeOnly())) where.add("r.status='ACTIVE'");
  }

  private String levelProblem(String roleType, String level) {
    if (level == null) return "分级不能为空";
    if (ROLE_SUPPLIER.equals(roleType)) return cooperationLevelProblem(level);
    if (ROLE_CUSTOMER.equals(roleType)) return customerLevelProblem(level);
    if (ROLE_CARRIER.equals(roleType)) return interfaceTypeProblem(level);
    return "角色类型不在词表里";
  }

  // ================================================================ 汇总与详情

  /**
   * 一个主体的三张角色档案汇总（详情页一次拿全，同一时刻的一致快照）。
   *
   * <p>合成一个 record 而不是三个端点：详情页要显示的就是这三块的<b>同一瞬间</b>。
   * 拆开的话，运营在「供应商身份刚被别人解掉」之后看到的页面会是拼接出来的旧数据。
   */
  public PartyRoleProfiles aggregate(UUID partyId) {
    Record party = partyRow(partyId);
    return new PartyRoleProfiles(
        partyId,
        party.get("code", String.class),
        party.get("name", String.class),
        supplierOf(party),
        customerOf(party),
        carrierOf(party),
        "三张槽位都是「同一时刻」的快照：某个身份没被指派时对应槽位为 null，"
            + "指派了但还没填档案时槽位里 profileFound=false —— 这两种状态在界面上必须长得不一样，"
            + "所以它们不是同一个值。");
  }

  /** 供应商角色档案（详情 / 默认取值读出口）。 */
  public PartySupplierProfile supplier(UUID partyId) {
    return supplierOf(partyRow(partyId));
  }

  /** 客户角色档案（详情 / 默认取值读出口）。 */
  public PartyCustomerProfile customer(UUID partyId) {
    return customerOf(partyRow(partyId));
  }

  /** 承运商角色档案（详情 / 默认取值读出口）。 */
  public PartyCarrierProfile carrier(UUID partyId) {
    return carrierOf(partyRow(partyId));
  }

  /**
   * 读一张供应商档案。
   *
   * <p>主体不存在 → 404；主体存在但没有 SUPPLIER 角色、或有角色但没建档案 →
   * {@code profileFound=false} 加一条说明性 {@code warnings}（不是 404）。
   */
  private PartySupplierProfile supplierOf(Record party) {
    UUID partyId = party.get("id", UUID.class);
    var row =
        fetchProfile(
            SUPPLIER_TABLE, partyId, ROLE_SUPPLIER);
    boolean roleAssigned = row != null;
    boolean found = roleAssigned && row.get("profile_found", Boolean.class);
    int terms = found ? intOf(row.get("payment_terms_days", Integer.class)) : 0;
    String cooperation = found ? row.get("cooperation_level", String.class) : "STANDARD";
    String method = found ? row.get("settlement_method", String.class) : "PREPAID";
    return new PartySupplierProfile(
        partyId,
        row == null ? party.get("code", String.class) : row.get("party_code", String.class),
        row == null ? party.get("name", String.class) : row.get("party_name", String.class),
        roleAssigned ? row.get("role_id", UUID.class) : null,
        ROLE_SUPPLIER,
        PartyRules.roleLabel(ROLE_SUPPLIER),
        row == null ? null : row.get("role_status", String.class),
        found,
        terms,
        terms == 0 ? "现结（无账期）" : "账期 " + terms + " 天",
        found ? row.get("moq_quantity", BigDecimal.class) : null,
        found ? row.get("moq_unit_code", String.class) : null,
        found ? row.get("moq_unit_name", String.class) : null,
        moqLabel(found ? row.get("moq_quantity", BigDecimal.class) : null,
            found ? row.get("moq_unit_name", String.class) : null),
        cooperation,
        cooperationLevelLabel(cooperation),
        method,
        settlementMethodLabel(method),
        found ? emptyToNull(row.get("note", String.class)) : null,
        found ? versionOf(row) : 0L,
        supplierWarnings(found, roleAssigned, terms, method, cooperation, party),
        found ? instant(row.get("profile_created_at", OffsetDateTime.class)) : null,
        found ? instant(row.get("profile_updated_at", OffsetDateTime.class)) : null);
  }

  private PartyCustomerProfile customerOf(Record party) {
    UUID partyId = party.get("id", UUID.class);
    var row =
        fetchProfile(
            CUSTOMER_TABLE, partyId, ROLE_CUSTOMER);
    boolean roleAssigned = row != null;
    boolean found = roleAssigned && row.get("profile_found", Boolean.class);
    int terms = found ? intOf(row.get("payment_terms_days", Integer.class)) : 0;
    String level = found ? row.get("customer_level", String.class) : "STANDARD";
    String currency = found ? emptyToNull(row.get("default_currency", String.class)) : null;
    BigDecimal credit = found ? row.get("default_credit_limit", BigDecimal.class) : null;
    return new PartyCustomerProfile(
        partyId,
        row == null ? party.get("code", String.class) : row.get("party_code", String.class),
        row == null ? party.get("name", String.class) : row.get("party_name", String.class),
        roleAssigned ? row.get("role_id", UUID.class) : null,
        ROLE_CUSTOMER,
        PartyRules.roleLabel(ROLE_CUSTOMER),
        row == null ? null : row.get("role_status", String.class),
        found,
        level,
        customerLevelLabel(level),
        currency,
        credit,
        credit == null ? (currency == null ? "未设额度" : "不限制赊账（未设额度）")
            : "额度 " + credit.stripTrailingZeros().toPlainString() + " " + currency,
        terms,
        terms == 0 ? "现结（无账期）" : "账期 " + terms + " 天",
        found ? emptyToNull(row.get("note", String.class)) : null,
        found ? versionOf(row) : 0L,
        customerWarnings(found, roleAssigned, level, currency, credit, party),
        found ? instant(row.get("profile_created_at", OffsetDateTime.class)) : null,
        found ? instant(row.get("profile_updated_at", OffsetDateTime.class)) : null);
  }

  private PartyCarrierProfile carrierOf(Record party) {
    UUID partyId = party.get("id", UUID.class);
    var row =
        fetchProfile(
            CARRIER_TABLE, partyId, ROLE_CARRIER);
    boolean roleAssigned = row != null;
    boolean found = roleAssigned && row.get("profile_found", Boolean.class);
    String interfaceType = found ? row.get("interface_type", String.class) : "API";
    List<String> capabilities = found ? capabilityArray(row) : List.of();
    return new PartyCarrierProfile(
        partyId,
        row == null ? party.get("code", String.class) : row.get("party_code", String.class),
        row == null ? party.get("name", String.class) : row.get("party_name", String.class),
        roleAssigned ? row.get("role_id", UUID.class) : null,
        ROLE_CARRIER,
        PartyRules.roleLabel(ROLE_CARRIER),
        row == null ? null : row.get("role_status", String.class),
        found,
        interfaceType,
        interfaceTypeLabel(interfaceType),
        found ? emptyToNull(row.get("account_ref", String.class)) : null,
        capabilities,
        capabilityOptions(),
        capabilitySummary(capabilities),
        found ? emptyToNull(row.get("service_scope_note", String.class)) : null,
        found ? emptyToNull(row.get("note", String.class)) : null,
        found ? versionOf(row) : 0L,
        carrierWarnings(found, roleAssigned, interfaceType, capabilities, party),
        found ? instant(row.get("profile_created_at", OffsetDateTime.class)) : null,
        found ? instant(row.get("profile_updated_at", OffsetDateTime.class)) : null);
  }

  /**
   * 取「主体 + 角色 + 档案」的联合行。
   *
   * <p>刻意用 {@code LEFT JOIN} 档案表：角色存在但没建档案时仍然要返回一行
   * （{@code profile_found=false}），否则详情页只能靠 404 去猜"是没建档案还是没这个身份" ——
   * 而这两种状态在界面上必须分开显示。
   *
   * <p>主体已软删除时<b>仍然</b>查得出来（与 {@code PartyService.partyRow} 同一纪律）：
   * 删完回头看一眼的运营要看到"已删除"，不是凭空消失的一条。
   */
  private Record fetchProfile(ProfileTable table, UUID partyId, String roleType) {
    return sql.fetchOne(
        "select p.id as party_id,p.code as party_code,p.name as party_name,p.deleted_at,"
            + "r.id as role_id,r.role_type,r.status as role_status,"
            + table.columns()
            + ",sp.role_id is not null as profile_found,sp.version as profile_version,"
            + "sp.created_at as profile_created_at,sp.updated_at as profile_updated_at"
            + BASE_FROM
            + table.table()
            + " on sp.tenant_id=r.tenant_id and sp.role_id=r.id"
            + table.extraJoin()
            + " where r.tenant_id=? and r.party_id=? and r.role_type=?",
        currentTenantId(), partyId, roleType);
  }

  // ================================================================ 写入：供应商

  /** 建 / 改供应商角色档案（规则 **R3** / **R1**）。 */
  public PartySupplierProfile saveSupplier(
      AccessService.Access actor, UUID partyId, PartySupplierProfileInput input) {
    Record party = partyRow(partyId);
    int terms = normalizePaymentTerms(input.paymentTermsDays());
    String cooperation = normalizeCooperationLevel(input.cooperationLevel());
    String method = normalizeSettlementMethod(input.settlementMethod());
    BigDecimal moq = normalizeQuantity(input.moqQuantity(), MOQ_SCALE);
    String unit = normalizeUnitCode(input.moqUnitCode());
    String problem =
        firstNonNull(
            paymentTermsProblem(input.paymentTermsDays()),
            cooperationLevelProblem(input.cooperationLevel()),
            settlementMethodProblem(input.settlementMethod()),
            settlementConsistencyProblem(method, terms),
            moqProblem(input.moqQuantity(), unit));
    CatalogService.check(problem == null, problem);

    UUID roleId = requireRole(partyId, ROLE_SUPPLIER);
    checkMoqUnit(unit, currentMoqUnit(roleId));

    String note = normalizeText(input.note());
    CatalogService.check(note.length() <= MAX_NOTE_LENGTH, "备注最多 " + MAX_NOTE_LENGTH + " 个字符");
    upsert(
        "party_supplier_profile",
        ROLE_SUPPLIER,
        roleId,
        input.version(),
        "insert into party_supplier_profile(tenant_id,role_id,payment_terms_days,moq_quantity,"
            + "moq_unit_code,cooperation_level,settlement_method,note) values(?,?,?,?,?,?,?,?)",
        "update party_supplier_profile set payment_terms_days=?,moq_quantity=?,moq_unit_code=?,"
            + "cooperation_level=?,settlement_method=?,note=?,version=version+1,updated_at=now()",
        // Arrays.asList 而不是 List.of：绑定值里允许出现 null（MOQ 可空），
        // 而 List.of 遇到 null 会直接抛 NPE —— 症状是"没填 MOQ 就保存失败"。
        java.util.Arrays.asList(terms, moq, unit, cooperation, method, note),
        actor.tenantId());
    audit(actor, partyId, "PartySupplierProfileSaved");
    return supplier(partyId);
  }

  // ================================================================ 写入：客户

  /** 建 / 改客户角色档案（规则 **R2** / **R4**）。 */
  public PartyCustomerProfile saveCustomer(
      AccessService.Access actor, UUID partyId, PartyCustomerProfileInput input) {
    partyRow(partyId);
    int terms = normalizePaymentTerms(input.paymentTermsDays());
    String level = normalizeCustomerLevel(input.customerLevel());
    String currency = normalizeCurrency(input.defaultCurrency());
    BigDecimal limit = normalizeQuantity(input.defaultCreditLimit(), CREDIT_LIMIT_SCALE);
    String problem =
        firstNonNull(
            paymentTermsProblem(input.paymentTermsDays()),
            customerLevelProblem(input.customerLevel()),
            currencyProblem(input.defaultCurrency()),
            creditLimitProblem(input.defaultCreditLimit()),
            creditLimitCurrencyProblem(input.defaultCreditLimit(), currency),
            riskCustomerCreditProblem(level, limit));
    CatalogService.check(problem == null, problem);

    UUID roleId = requireRole(partyId, ROLE_CUSTOMER);
    String note = normalizeText(input.note());
    CatalogService.check(note.length() <= MAX_NOTE_LENGTH, "备注最多 " + MAX_NOTE_LENGTH + " 个字符");
    upsert(
        "party_customer_profile",
        ROLE_CUSTOMER,
        roleId,
        input.version(),
        "insert into party_customer_profile(tenant_id,role_id,customer_level,default_currency,"
            + "default_credit_limit,payment_terms_days,note) values(?,?,?,?,?,?,?)",
        "update party_customer_profile set customer_level=?,default_currency=?,default_credit_limit=?,"
            + "payment_terms_days=?,note=?,version=version+1,updated_at=now()",
        java.util.Arrays.asList(level, currency, limit, terms, note),
        actor.tenantId());
    audit(actor, partyId, "PartyCustomerProfileSaved");
    return customer(partyId);
  }

  // ================================================================ 写入：承运商

  /** 建 / 改承运商角色档案。 */
  public PartyCarrierProfile saveCarrier(
      AccessService.Access actor, UUID partyId, PartyCarrierProfileInput input) {
    partyRow(partyId);
    String interfaceType = normalizeInterfaceType(input.interfaceType());
    List<String> capabilities = normalizeCapabilities(input.serviceCapabilities());
    String problem =
        firstNonNull(
            interfaceTypeProblem(input.interfaceType()),
            accountRefProblem(input.accountRef()),
            serviceCapabilitiesProblem(input.serviceCapabilities()));
    CatalogService.check(problem == null, problem);

    UUID roleId = requireRole(partyId, ROLE_CARRIER);
    String scope = normalizeText(input.serviceScopeNote());
    CatalogService.check(scope.length() <= MAX_SCOPE_NOTE_LENGTH,
        "服务范围说明最多 " + MAX_SCOPE_NOTE_LENGTH + " 个字符");
    String note = normalizeText(input.note());
    CatalogService.check(note.length() <= MAX_NOTE_LENGTH, "备注最多 " + MAX_NOTE_LENGTH + " 个字符");
    upsert(
        "party_carrier_profile",
        ROLE_CARRIER,
        roleId,
        input.version(),
        "insert into party_carrier_profile(tenant_id,role_id,interface_type,account_ref,"
            + "service_capabilities,service_scope_note,note) values(?,?,?,?,?::text[],?,?)",
        "update party_carrier_profile set interface_type=?,account_ref=?,"
            + "service_capabilities=?::text[],service_scope_note=?,note=?,"
            + "version=version+1,updated_at=now()",
        java.util.Arrays.asList(
            interfaceType, normalizeText(input.accountRef()), toPgArray(capabilities), scope, note),
        actor.tenantId());
    audit(actor, partyId, "PartyCarrierProfileSaved");
    return carrier(partyId);
  }

  // ================================================================ 删除（规则 R5）

  /**
   * 删掉一张角色档案（角色指派本身保留 —— 解指派是 3.6.1.1 的动作，走它自己的端点）。
   *
   * <p>删掉之后 06/12 退回"没设过"：额度不设 = 不限制、账期 0 = 现结。
   * 这是一个<b>可逆且有意义</b>的状态，不是需要历史追溯的凭证
   * （与 3.6.1.1 对 party 软删除、对 party_role 物理删除的理由同源）。
   */
  public void delete(AccessService.Access actor, UUID partyId, String roleType, long version) {
    String problem = profileKindProblem(roleType);
    CatalogService.check(problem == null, problem);
    String type = roleTypeFor(normalizeEnum(roleType));
    partyRow(partyId);
    String table = tableFor(type).table().split(" ")[0];
    UUID roleId = requireRole(partyId, type);
    var existing =
        sql.fetchOne("select version from " + table + " where tenant_id=? and role_id=?",
            currentTenantId(), roleId);
    if (existing == null) throw CatalogService.missing();
    if (existing.get("version", Long.class) != version)
      throw conflict(PartyRules.roleLabel(type) + "角色档案已被他人修改，请刷新后重试");
    sql.execute("delete from " + table + " where tenant_id=? and role_id=? and version=?",
        currentTenantId(), roleId, version);
    audit(actor, partyId, "PartyRoleProfileDeleted");
  }

  // ================================================================ 写入公共路径

  /**
   * 建档 / 改档的公共路径：**显式**的插入分支与更新分支，而不是 {@code on conflict} upsert。
   *
   * <p>不用 upsert 的理由：乐观锁要区分「这一行还不存在（version 只能是 0）」与
   * 「这一行存在（version 必须相等）」两种情况，而 upsert 只有一个出口 ——
   * 它在并发的两个请求下会把"都以为自己是新建"的两条请求静默合并成一行，
   * 后到的那条覆盖先到的那条而没有任何人收到 409。
   *
   * @param insertSql 插入语句（列与 values 逐字对应）
   * @param updateSql 更新语句（不含 {@code where}，由本方法补上 version 条件）
   * @param values 字段值，两条语句顺序相同
   */
  private void upsert(
      String table,
      String roleType,
      UUID roleId,
      long version,
      String insertSql,
      String updateSql,
      List<?> values,
      UUID tenantId) {
    var existing =
        sql.fetchOne("select version from " + table + " where tenant_id=? and role_id=?",
            tenantId, roleId);
    if (existing == null) {
      // 还不存在时只能带 version=0：带了别的值说明客户端以为它存在，而它不存在。
      if (version != 0)
        throw conflict(PartyRules.roleLabel(roleType) + "角色档案已被他人创建，请刷新后重试");
      List<Object> binds = new ArrayList<>();
      binds.add(tenantId);
      binds.add(roleId);
      binds.addAll(values);
      sql.execute(insertSql, binds.toArray());
      return;
    }
    if (existing.get("version", Long.class) != version)
      throw conflict("角色档案已被他人修改，请刷新后重试");
    List<Object> binds = new ArrayList<Object>(values);
    binds.add(tenantId);
    binds.add(roleId);
    binds.add(version);
    int updated =
        sql.execute(
            updateSql + " where tenant_id=? and role_id=? and version=?", binds.toArray());
    if (updated != 1) throw conflict("角色档案已被他人修改，请刷新后重试");
  }

  /**
   * 取某个身份的角色 id；没有就给出可读的 409（规则 **R3**）。
   *
   * <p>文案是「先指派身份」而不是「档案不存在」：运营点供应商档案时如果他还没指派供应商身份，
   * 他要做的是回到上面去指派身份，而不是以为自己已经指派过了。
   */
  private UUID requireRole(UUID partyId, String roleType) {
    var row =
        sql.fetchOne(
            "select id from party_role where tenant_id=? and party_id=? and role_type=?",
            currentTenantId(), partyId, roleType);
    if (row == null) throw conflict(roleMissingProblem(roleType, assignedRoleTypes(partyId)));
    return row.get("id", UUID.class);
  }

  /** 该主体当前已指派的角色类型（给缺身份的报错文案用：告诉他现在到底有什么）。 */
  private List<String> assignedRoleTypes(UUID partyId) {
    List<String> types = new ArrayList<>();
    for (Record r :
        sql.fetch(
            "select role_type from party_role where tenant_id=? and party_id=? order by role_type",
            currentTenantId(), partyId))
      types.add(r.get("role_type", String.class));
    return types;
  }

  /**
   * MOQ 采购单位的写入前置检查。
   *
   * <p>三条判据，单位不可换算这一条由 3.3.3 的 V17 触发器兜底，本类不再重复：
   * 必须已登记、必须启用、必须允许采购。
   *
   * <p><b>已经写着的那个单位可以继续留着</b>（哪怕它后来被停用）：
   * 否则停用一个单位之后，所有正在用它做 MOQ 的供应商档案都会变成改不动的东西
   * —— 改一个备注都保存不了，而运营完全看不出原因。
   */
  private void checkMoqUnit(String unit, String storedUnit) {
    if (unit == null) return;
    var row =
        sql.fetchOne(
            "select code,enabled,allow_purchase from unit_of_measure where tenant_id=? and code=?",
            currentTenantId(), unit);
    if (row == null)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "采购单位「" + unit + "」没有在计量单位里登记（3.3.3），请先登记该单位再填最小起订量");
    if (unit.equals(storedUnit)) return;
    if (!Boolean.TRUE.equals(row.get("enabled", Boolean.class)))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "采购单位「" + unit + "」已停用，换一个启用的单位；"
              + "停用只影响「新建」，已经用它写的起订量照常保留");
    if (!Boolean.TRUE.equals(row.get("allow_purchase", Boolean.class)))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "计量单位「" + unit + "」不允许用于采购（allow_purchase=false），换一个允许采购的单位");
  }

  private String currentMoqUnit(UUID roleId) {
    var row =
        sql.fetchOne(
            "select moq_unit_code from party_supplier_profile where tenant_id=? and role_id=?",
            currentTenantId(), roleId);
    return row == null ? null : emptyToNull(row.get("moq_unit_code", String.class));
  }

  // ================================================================ 默认取值提示（单一实现）

  /**
   * 供应商档案的默认取值提示（06 采购单用）。
   *
   * <p>这是本模块交给 06 的那份口径，三个读出口都调它，前端只照原样显示。
   * 每条提示回答的都是一个具体的问题："这张单要不要按起订量校验"、"到期日怎么算"、
   * "这个供应商要不要人工确认"。
   */
  private List<String> supplierWarnings(
      boolean found,
      boolean roleAssigned,
      int terms,
      String method,
      String cooperation,
      Record party) {
    List<String> warnings = new ArrayList<>();
    appendIdentityWarnings(warnings, found, roleAssigned, ROLE_SUPPLIER, party);
    if (!found) return warnings;
    // 「账期结算 + 0 天」在 DB 层被 CHECK 挡掉了，所以这里看到的只可能是别人绕过服务层写的；
    // 提示它是为了让运营看见"这行是脏的"，而不是让系统悄悄用 0 天算到期日。
    if ("CREDIT".equals(method) && terms <= 0)
      warnings.add("账期结算但账期是 0 天，到期日等于单据日 —— 请确认这不是漏填");
    if ("TRIAL".equals(cooperation) && "CREDIT".equals(method))
      warnings.add("试单供应商选了账期结算：试单阶段通常只做现结，确认账期是有意设置的");
    if ("RISK".equals(cooperation))
      warnings.add("该供应商被标记为风险供应商，06 采购下单需要人工确认才能下单");
    return warnings;
  }

  /** 客户档案的默认取值提示（12 应收 / 06 销售单用）。 */
  private List<String> customerWarnings(
      boolean found,
      boolean roleAssigned,
      String level,
      String currency,
      BigDecimal credit,
      Record party) {
    List<String> warnings = new ArrayList<>();
    appendIdentityWarnings(warnings, found, roleAssigned, ROLE_CUSTOMER, party);
    if (!found) return warnings;
    if (credit == null && currency != null)
      warnings.add("未设置信用额度：按「不限制赊账」处理（不是额度为 0，那表示完全不许赊）");
    if (currency == null)
      warnings.add("未设置默认币种，06 销售单需要在单据上单独指定币种，12 应收无法按币种分组结汇");
    if (credit != null && "PROSPECT".equals(level))
      warnings.add("潜在客户却设置了信用额度，请确认这不是在成交之前提前发放的额度");
    if ("RISK".equals(level) && credit != null)
      warnings.add("风险客户仍有信用额度：12 应收会照着放单，而唯一的风险信号已被额度覆盖");
    return warnings;
  }

  /** 承运商档案的默认取值提示（10 物流用）。 */
  private List<String> carrierWarnings(
      boolean found,
      boolean roleAssigned,
      String interfaceType,
      List<String> capabilities,
      Record party) {
    List<String> warnings = new ArrayList<>();
    appendIdentityWarnings(warnings, found, roleAssigned, ROLE_CARRIER, party);
    if (!found) return warnings;
    if (capabilities.isEmpty())
      warnings.add("未登记服务能力：10 物流按能力筛选承运商时会筛不到这家，运营只能靠记忆挑");
    if (!"API".equals(interfaceType))
      warnings.add("不是系统对接（" + interfaceTypeLabel(interfaceType) + "）：运单轨迹需要人工回填，"
          + "系统不会自动跟踪");
    if (!"API".equals(interfaceType) && capabilities.isEmpty())
      warnings.add("既非系统对接又没有服务能力，这张档案在 10 物流里几乎不可用");
    return warnings;
  }

  /**
   * 三张档案共用的一段提示：身份没指派 / 档案没建 / 主体已删除 / 身份已停用。
   *
   * <p>顺序即优先级：先说"连身份都没有"，因为那是**唯一需要先做别的动作**的情况
   * （回上面指派身份）；档案没建只是"还没填"，停用与已删除更是读得到就不奇怪的状态。
   */
  private void appendIdentityWarnings(
      List<String> warnings, boolean found, boolean roleAssigned, String roleType, Record party) {
    String label = PartyRules.roleLabel(roleType);
    if (!roleAssigned) {
      warnings.add("该往来单位还没有被指派「" + label + "」身份：先指派身份才能维护它的角色档案");
      return;
    }
    if (!found)
      warnings.add("尚未建立" + label + "角色档案：下游取不到默认值（"
          + defaultWhenMissing(roleType) + "）");
    if (party.get("deleted_at", OffsetDateTime.class) != null)
      warnings.add("该往来单位已被删除，档案只读；重新启用主体后档案继续可用");
  }

  /** 「档案缺失时下游会怎么处理」—— 说清后果，而不是只说"没档案"。 */
  private String defaultWhenMissing(String roleType) {
    if (ROLE_SUPPLIER.equals(roleType)) return "按现结、无限购门槛处理";
    if (ROLE_CUSTOMER.equals(roleType)) return "按不限制赊账、现结处理";
    return "10 物流无法自动跟踪该承运商的运单";
  }

  // ================================================================ 工具

  private PartyRoleProfileRow toRow(String roleType, Record r) {
    boolean found = Boolean.TRUE.equals(r.get("profile_found", Boolean.class));
    String roleStatus = r.get("role_status", String.class);
    String level = levelOf(roleType, r, found);
    return new PartyRoleProfileRow(
        r.get("party_id", UUID.class),
        r.get("party_code", String.class),
        r.get("party_name", String.class),
        r.get("role_id", UUID.class),
        roleType,
        PartyRules.roleLabel(roleType),
        roleStatus,
        "ACTIVE".equals(roleStatus) ? "使用中" : "已停用",
        found,
        found ? level : null,
        found ? levelLabelOf(roleType, level) : null,
        found ? headlineOf(roleType, r, level) : null,
        found ? emptyToNull(r.get("note", String.class)) : null,
        found ? versionOf(r) : 0L,
        found ? instant(r.get("profile_updated_at", OffsetDateTime.class)) : null);
  }

  private String levelOf(String roleType, Record r, boolean found) {
    if (!found) return null;
    if (ROLE_SUPPLIER.equals(roleType)) return r.get("cooperation_level", String.class);
    if (ROLE_CUSTOMER.equals(roleType)) return r.get("customer_level", String.class);
    return r.get("interface_type", String.class);
  }

  private String levelLabelOf(String roleType, String level) {
    if (ROLE_SUPPLIER.equals(roleType)) return cooperationLevelLabel(level);
    if (ROLE_CUSTOMER.equals(roleType)) return customerLevelLabel(level);
    return interfaceTypeLabel(level);
  }

  /**
   * 列表行那一列的中文摘要。
   *
   * <p>在服务端算而不是让前端拼：三张表的 headline 口径完全不同
   * （供应商看账期、客户看额度、承运商看接口+能力），散到三个组件里就会漂移 ——
   * 而"这一列显示什么"正是运营判断档案填全了没的唯一依据。
   */
  private String headlineOf(String roleType, Record r, String level) {
    if (ROLE_SUPPLIER.equals(roleType)) {
      int terms = intOf(r.get("payment_terms_days", Integer.class));
      String unitName = emptyToNull(r.get("moq_unit_name", String.class));
      String moq = moqLabel(r.get("moq_quantity", BigDecimal.class), unitName);
      return termsLabel(terms) + " · " + cooperationLevelLabel(level)
          + " · " + settlementMethodLabel(r.get("settlement_method", String.class))
          + (moq == null ? " · 未设起订量" : " · 起订 " + moq);
    }
    if (ROLE_CUSTOMER.equals(roleType)) {
      BigDecimal credit = r.get("default_credit_limit", BigDecimal.class);
      String currency = emptyToNull(r.get("default_currency", String.class));
      String creditText =
          credit == null ? (currency == null ? "未设币种与额度" : "不限制赊账")
              : "额度 " + credit.stripTrailingZeros().toPlainString() + " " + currency;
      return customerLevelLabel(level) + " · " + creditText + " · "
          + termsLabel(intOf(r.get("payment_terms_days", Integer.class)));
    }
    return interfaceTypeLabel(level) + " · " + capabilitySummary(capabilityArray(r));
  }

  private String termsLabel(int days) {
    return days == 0 ? "现结" : "账期 " + days + " 天";
  }

  private String moqLabel(BigDecimal quantity, String unitName) {
    if (quantity == null) return null;
    String unit = unitName == null ? "" : unitName;
    return quantity.stripTrailingZeros().toPlainString() + unit;
  }

  /**
   * 取一行主体。
   *
   * <p>刻意<b>不</b>过滤 {@code deleted_at}：软删除之后详情仍要能读到这行
   * （并带一条 warning），与 {@code PartyService.partyRow} 同一做法。
   * 不加 {@code tenant_id} 条件：RLS（FORCE）已经把别的租户的行挡掉了。
   */
  private Record partyRow(UUID partyId) {
    var row = sql.fetchOne("select * from party where id=?", partyId);
    if (row == null) throw CatalogService.missing();
    return row;
  }

  private static ProfileTable tableFor(String roleType) {
    if (ROLE_SUPPLIER.equals(roleType)) return SUPPLIER_TABLE;
    if (ROLE_CUSTOMER.equals(roleType)) return CUSTOMER_TABLE;
    if (ROLE_CARRIER.equals(roleType)) return CARRIER_TABLE;
    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "角色档案类型只能是 SUPPLIER / CUSTOMER / CARRIER");
  }

  /** jOOQ 把 {@code text[]} 读成 {@code String[]}；空数组归一成空集合而不是 null。 */
  private static List<String> capabilityArray(Record r) {
    String[] raw = r.get("service_capabilities", String[].class);
    if (raw == null) return List.of();
    return normalizeCapabilities(List.of(raw));
  }

  private static long versionOf(Record r) {
    Long version = r.get("profile_version", Long.class);
    return version == null ? 0L : version;
  }

  private static int intOf(Integer value) {
    return value == null ? 0 : value;
  }

  private static String emptyToNull(String value) {
    if (value == null) return null;
    String text = value.trim();
    return text.isEmpty() ? null : text;
  }

  /**
   * LIKE 模式里的通配符转义。
   *
   * <p>与 {@code escape '!'} 成对：不转义的话运营搜 {@code 100%} 会变成「匹配所有」，
   * 而界面上看不出来 —— 那一页返回几百条看起来还挺像对的。与 {@code PartyService} 同一份。
   */
  private static String escapeLike(String value) {
    return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
  }

  /** 当前租户从 RLS 生效的连接上下文取（本服务的每个查询都在 {@code AccessService.run} 包裹里）。 */
  private UUID currentTenantId() {
    return sql.fetchOne("select nullif(current_setting('app.current_tenant',true),'')::uuid")
        .get(0, UUID.class);
  }

  private static List<Object> concat(List<Object> first, List<Object> second) {
    var all = new ArrayList<Object>(first.size() + second.size());
    all.addAll(first);
    all.addAll(second);
    return all;
  }

  private static String firstNonNull(String... values) {
    for (String value : values) if (value != null) return value;
    return null;
  }

  private void audit(AccessService.Access actor, UUID resourceId, String action) {
    sql.execute(
        "insert into audit_event(id,tenant_id,actor_id,resource_id,action) values(?,?,?,?,?)",
        Ids.next(), actor.tenantId(), actor.userId(), resourceId, action);
  }

  /** {@code timestamptz} 读出口径：统一转成 UTC 的 ISO-8601 串（带 {@code Z}），不交给前端猜时区。 */
  private static String instant(OffsetDateTime value) {
    return value == null ? null : value.toInstant().toString();
  }

  private static ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }
}