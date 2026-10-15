package com.starcon.trade;

import static com.starcon.trade.DealerChannelModels.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 9.7.1.2 经销与分销管理。
 *
 * <p>蓝图要求四个输出，本类逐一兑现，且每个都只有<b>一条</b>产生路径：
 *
 * <ol>
 *   <li><b>经销授权台账</b> → {@link #createAuthorization} / {@link #changeAuthorizationStatus}，
 *       每次变更都写 {@code dealer_authorization_event}（append-only）。
 *   <li><b>配额完成率</b> → {@link #pageQuotaAttainment}，完成率是 SQL 里的 NUMERIC 除法，
 *       服务层用 BigDecimal 独立复算一遍（{@link #attainmentRate}）。
 *   <li><b>返点计提</b> → {@link #accrueRebate}，基数 = 期间内 Σ 销售金额，
 *       金额 = 基数 × 比例，比例是<b>计提时刻的快照</b>。
 *   <li><b>窜货预警</b> → {@link #recordSale}，销售登记的同一次事务里判定，越界才建预警。
 * </ol>
 *
 * <p><b>金额与比率全链路无浮点。</b>数量 numeric(19,6)、单价 numeric(19,4)、金额 numeric(24,4)、
 * 比例 numeric(9,6)，Java 侧一律 BigDecimal。两个最容易翻车的点在这里各有一处钉死断言：
 *
 * <ul>
 *   <li><b>除法</b>（完成率）：{@code round(actual/target, 6)}。目标为 0 时返回 {@code null} ——
 *       0 会被读成「一个都没完成」，100% 会被读成「超额完成」，两者都是编造的答案。
 *   <li><b>乘法</b>（返点）：{@code round(base × rate, 4)}，{@link RoundingMode#HALF_UP}。
 *       {@code 0.333333 × 1000.0000 = 333.333000 → 333.3330} 这类组合在测试里逐条断言精确值。
 * </ul>
 *
 * <p><b>不跨库读上游。</b>经销商主体来自 3.6.1.2（catalog），经 {@link DealerPartyQueryPort} 的授权只读 HTTP 读取，
 * 取不到时显式 503 —— 绝不返回空集合冒充「这个租户一个经销商都没有」。
 * 授权一旦建好，本模块只认自己的 {@code dealer_profile} 投影，销售与计提<b>一次上游都不回查</b>。
 */
@Service
public class DealerChannelService {

  /** 数量刻度：与 DB 的 numeric(19,6) 逐字对齐。 */
  private static final int QTY_SCALE = 6;

  /** 金额刻度：与 DB 的 numeric(24,4) 逐字对齐。 */
  private static final int MONEY_SCALE = 4;

  /** 比率刻度：与 DB 的 numeric(9,6) 逐字对齐。完成率也用同一刻度。 */
  private static final int RATE_SCALE = 6;

  private static final int MAX_PAGE_SIZE = 200;

  private final DSLContext sql;
  private final DealerPartyQueryPort parties;

  public DealerChannelService(DSLContext sql, DealerPartyQueryPort parties) {
    this.sql = sql;
    this.parties = parties;
  }

  // ================================================================ 能力声明

  /**
   * 能力与上下游可用性。
   *
   * <p>存在的理由与 9.7.1.1 / 9.1.2.2 一致：把「哪些输入真有依据」「下游有没有接上」显式报出来，
   * 而不是让调用方从 404 或空数组里猜哪一环没通。
   */
  public DealerCapabilities capabilities() {
    return new DealerCapabilities(
        List.of(DealerAuthorizationStatus.values()),
        List.of(DealerComplianceState.values()),
        List.of(DealerBreachKind.values()),
        List.of(DealerAlertState.values()),
        List.of(DealerSeverity.values()),
        List.of(DealerAccrualState.values()),
        List.of(DealerCustomerLevel.values()),
        List.of(
            "**经销商是 CUSTOMER 角色的往来单位，不是新主体**（蓝图 3.6 实体目录）：本模块只在一个已有客户角色的"
                + "主体上挂授权，不建新主体，也不开供应商/承运商角色的经销授权。",
            "**四个输出都真的算出来，不接受调用方传值**：销售金额 = 数量 × 授权经销价；配额完成率 = 实绩 ÷ 目标（NUMERIC 除法）；"
                + "返点金额 = 计提基数 × 比例；窜货判定 = 销售区域/产品线落在授权范围之外。",
            "**授权期限是两个日期的区间**：过期判定是区间比较，不从写入时刻 + N 天反推 —— 补录历史授权时后者会把期限整体平移。",
            "**返点比例是 0..1 的小数**（0.05 = 5%），不是百分数；DB 的 CHECK 与服务层的 @DecimalMax 各拦一道。",
            "**目标为 0 = 不考核**：完成率返回 null 而不是 0 或 100%，两者都会被读成编造的结论。",
            "**授权期外与暂停状态不接受销售登记**：soldOn 必须落在 [valid_from, valid_to] 且授权为 ACTIVE。",
            "**一条销售最多一条预警**：既越区域又越产品线时合并成 BOTH，拆成两条会让「有几笔窜货」被算大。",
            "**判为误报必须写明理由**（DB 的 CHECK 与服务层的 @NotBlank 各拦一道）。",
            "金额一律 BigDecimal + NUMERIC：数量 numeric(19,6)、单价 numeric(19,4)、金额 numeric(24,4)、"
                + "比例 numeric(9,6)，全链路无浮点。"),
        List.of(
            new DealerDependency(
                "3.6.1.2",
                "角色档案",
                "catalog",
                true,
                "客户角色档案已交付（catalog V27/V30）。本模块经 DealerPartyQueryPort 读它的授权只读接口"
                    + "校验主体确有 CUSTOMER 角色，并持有 trade 侧只读投影 dealer_profile。"
                    + "不跨库 JOIN、不建跨服务 FK：主体改名或改分级只改投影，已成立的授权用的是当时快照。"),
            new DealerDependency(
                "16",
                "合规（授权）",
                "compliance",
                false,
                "**16 合规未交付**：授权的合规校验没有任何可读上游，所以 dealer_authorization.compliance_state "
                    + "服务层只写得出 UNVERIFIED，capabilities 里报 available=false 而不是画成 VERIFIED。"
                    + "把「没有校验过」画成「校验通过」是合规字段最危险的默认值。"),
            new DealerDependency(
                "12",
                "应付 / 返点",
                "finance",
                false,
                "**12 应付/返点未交付**：dealer_rebate 是**本模块自己的计提口径**，不是财务已确认口径 —— "
                    + "蓝图原文写死「需与财务确认计提口径」。finance_confirmed 恒为 false 并在界面上显示，"
                    + "口径确认与核销（accrual_state→SETTLED 的回写）属于 12 财务的写入所有权，本模块不抢。"),
            new DealerDependency(
                "5.5.2",
                "站点价差倒挂",
                "pricing",
                false,
                "**5.5.2 未交付**：蓝图那句「窜货预警可复用 5.5.2 的站点价差倒挂逻辑」是**给未来的建议、不是现成依赖**。"
                    + "本模块的窜货判定只做「授权范围越界」一个维度（区域 + 产品线），没有价格倒挂维度，"
                    + "且该口径**未与 5.5.2 合并** —— 交付后需要由 5.5.2 侧合并，或由它来读 dealer_diversion_alert。"),
            new DealerDependency(
                "5",
                "定价",
                "pricing",
                false,
                "**5 定价未交付**：dealer_price 是授权上的**快照**，改价不影响已成立的授权合同；"
                    + "05 价目交付后由事件同步接管同一张表，表结构与所有下游读法都不需要改。"),
            new DealerDependency(
                "13",
                "分析",
                "analytics",
                false,
                "**13 分析未交付**：配额完成率与计提汇总在本模块内出，13 交付后由它读 dealer_rebate / "
                    + "dealer_quota（同服务同库，不是跨库读取）。"),
            new DealerDependency(
                "9.7.1.1",
                "批发订单与账期",
                "trade",
                true,
                "同一 L3 下的兄弟模块已交付（trade V120），但那是**批发订单与账期**、不是经销商授权。"
                    + "两者互不覆盖、互不 JOIN：本模块对 b2b_* 零 ALTER，需要其口径时只用只读查询。"
                    + "经销订单要落到 b2b_order（order_type=B2B_DISTRIBUTION）需由 9.7.1.1 侧接入，本模块不代写。")));
  }

  // ================================================================ 上游：经销商候选（跨服务只读）

  /**
   * 经销商候选（建授权时的往来单位下拉）。
   *
   * <p>数据<b>直接来自 3.6.1.2</b>，不落库：本模块的 dealer_profile 投影是同步写入的，
   * 而候选是「现在有哪些主体可以挂授权」这个**当下**的问题，读投影会答出一个过期答案。
   *
   * <p>上游不可达时由端口抛 503 且本方法不落任何库。
   */
  public List<DealerPartyQueryPort.PartyView> candidates(String keyword, String bearer) {
    return parties.customerCandidates(keyword, bearer);
  }

  // ================================================================ 上游投影：经销商档案

  /**
   * 经销商档案投影 upsert（3.6.1.2 → trade 侧只读投影）。
   *
   * <p>幂等键是 partyId，版本号单调：乱序到达的旧事件会被 DB 的触发器拒绝覆盖新值。
   */
  @Transactional
  public DealerProfileRow upsertProfile(DealerProfileInput in) {
    try {
      sql.execute(
          "insert into dealer_profile"
              + "(id,tenant_id,party_id,party_code,party_name,customer_level,default_currency,"
              + "payment_terms_days,source_system,source_version,synced_at,created_at,updated_at)"
              + " values (?,?,?,?,?,?,?,?,'catalog',?,?::timestamptz,now(),now())"
              + " on conflict (tenant_id, party_id) do update set"
              + " party_code=excluded.party_code, party_name=excluded.party_name,"
              + " customer_level=excluded.customer_level, default_currency=excluded.default_currency,"
              + " payment_terms_days=excluded.payment_terms_days, source_version=excluded.source_version,"
              + " synced_at=excluded.synced_at, updated_at=now()"
              + " where dealer_profile.source_version < excluded.source_version",
          new Object[] {
            UUID.randomUUID(),
            currentTenant(),
            in.partyId(),
            in.partyCode().trim(),
            in.partyName().trim(),
            in.customerLevel().name(),
            blankToNull(in.defaultCurrency()),
            in.paymentTermsDays(),
            in.sourceVersion(),
            OffsetDateTime.now()
          });
    } catch (DuplicateKeyException e) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "编码 " + in.partyCode() + " 已被另一个经销商占用");
    }
    return profile(in.partyId());
  }

  /** 按 partyId 取投影行（上游同步入口用；不存在即 404）。 */
  public DealerProfileRow profile(UUID partyId) {
    Record r =
        sql.fetchOne(
            "select id,party_id,party_code,party_name,customer_level,default_currency,"
                + "payment_terms_days,source_system,source_version,synced_at"
                + " from dealer_profile where party_id = ?",
            partyId);
    if (r == null) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, "没有这个经销商的档案投影（3.6.1.2 尚未同步过来）");
    }
    return profileRow(r);
  }

  /**
   * 按<b>投影主键</b>取一行 —— 建授权走这条。
   *
   * <p>刻意与 {@link #profile(UUID)} 分开：后者按 {@code party_id}（上游主体 id）查，是同步入口的形状；
   * 而 {@code dealer_authorization.dealer_profile_id} 的外键指向 {@code dealer_profile(tenant_id, id)}，
   * 是**投影主键**。用 party_id 去查投影主键会把一个真实存在的经销商报成 404。
   */
  private DealerProfileRow profileById(UUID profileId) {
    Record r =
        sql.fetchOne(
            "select id,party_id,party_code,party_name,customer_level,default_currency,"
                + "payment_terms_days,source_system,source_version,synced_at"
                + " from dealer_profile where id = ?",
            profileId);
    if (r == null) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, "经销商档案投影不存在（3.6.1.2 尚未同步过来）：" + profileId);
    }
    return profileRow(r);
  }

  /** 经销商档案列表：真分页 + 关键词筛选。 */
  public DealerProfilePage pageProfiles(String keyword, int page, int size) {
    int p = Math.max(0, page);
    int s = Math.min(MAX_PAGE_SIZE, Math.max(1, size));
    List<Object> binds = new ArrayList<>();
    List<String> where = new ArrayList<>();
    if (isSet(keyword)) {
      where.add("(party_code ilike ? or party_name ilike ?)");
      String pattern = "%" + keyword.trim() + "%";
      binds.add(pattern);
      binds.add(pattern);
    }
    String clause = where.isEmpty() ? "" : " where " + String.join(" and ", where);
    long total =
        sql.fetchOne("select count(*) from dealer_profile" + clause, binds.toArray())
            .get(0, Long.class);
    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(s);
    pageBinds.add(p * s);
    List<DealerProfileRow> items =
        sql.fetch(
                "select id,party_id,party_code,party_name,customer_level,default_currency,"
                    + "payment_terms_days,source_system,source_version,synced_at"
                    + " from dealer_profile"
                    + clause
                    + " order by party_name, id limit ? offset ?",
                pageBinds.toArray())
            .map(DealerChannelService::profileRow);
    return new DealerProfilePage(items, p, s, total);
  }

  // ================================================================ 输出①：经销授权台账

  /**
   * 建立一份经销授权。
   *
   * <p><b>经销商必须是 3.6.1.2 里的客户角色主体</b>，且档案投影必须已同步过来 ——
   * 授权的每一次销售、每一笔返点都要挂在经销商上，没有档案投影就挂不住。
   *
   * <p><b>合规状态只能写 UNVERIFIED</b>：16 合规未交付，没有任何可读的合规结论来源。
   */
  @Transactional
  public DealerSaved createAuthorization(DealerAuthorizationInput in) {
    // 经销商档案投影必须存在（本服务不代建主体：主体是 3.6.1.1 的写入所有权）。
    // 授权的每一次销售、每一笔返点都要挂在经销商上，没有投影就挂不住。
    profileById(in.dealerProfileId());

    if (in.validTo().isBefore(in.validFrom())) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "授权结束日期不能早于开始日期：" + in.validFrom() + " ~ " + in.validTo());
    }

    List<String> regions = normalize(in.regionCodes());
    List<String> lines = normalize(in.productLineCodes());

    UUID id = UUID.randomUUID();
    OffsetDateTime now = OffsetDateTime.now();
    int version = 0;
    try {
      sql.execute(
          "insert into dealer_authorization"
              + "(id,tenant_id,dealer_profile_id,auth_code,region_codes,product_line_codes,"
              + "valid_from,valid_to,currency,dealer_price,rebate_rate,status,compliance_state,version,"
              + "created_at,updated_at)"
              + " values (?,?,?,?,?,?,?::date,?::date,?,?,?,'ACTIVE','UNVERIFIED',?,?::timestamptz,?::timestamptz)",
          new Object[] {
            id,
            currentTenant(),
            in.dealerProfileId(),
            in.authCode().trim(),
            regions.toArray(new String[0]),
            lines.toArray(new String[0]),
            in.validFrom(),
            in.validTo(),
            in.currency(),
            in.dealerPrice().setScale(MONEY_SCALE, RoundingMode.HALF_UP),
            in.rebateRate().setScale(RATE_SCALE, RoundingMode.HALF_UP),
            version,
            now,
            now
          });
    } catch (DuplicateKeyException e) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "授权编号 " + in.authCode() + " 已存在");
    }

    sql.execute(
        "insert into dealer_authorization_event"
            + "(id,tenant_id,authorization_id,event_type,from_status,to_status,note,occurred_at)"
            + " values (?,?,?,'CREATED',null,'ACTIVE',?,?::timestamptz)",
        new Object[] {
          UUID.randomUUID(),
          currentTenant(),
          id,
          in.note() == null ? "" : in.note().trim(),
          now
        });

    return new DealerSaved(id, version);
  }

  /** 授权状态流转。终态 EXPIRED 不可回退（授权到期不是可以撤销的操作）。 */
  @Transactional
  public DealerSaved changeAuthorizationStatus(UUID id, DealerAuthorizationStatusInput in) {
    var current = authorization(id);
    if (current.status() == DealerAuthorizationStatus.EXPIRED) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "授权已到期，终态不可回退");
    }
    if (current.status() == in.status()) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "授权已经是 " + in.status() + "，状态没有变化");
    }

    String eventType =
        switch (in.status()) {
          case SUSPENDED -> "SUSPENDED";
          case ACTIVE -> "RESUMED";
          case EXPIRED -> "EXPIRED";
        };
    int next = current.version() + 1;
    // 乐观锁：where 里带上读到的 version，被别人改过则影响 0 行（jOOQ 的 execute 直接返回行数）。
    int updated =
        sql.execute(
            "update dealer_authorization set status = ?, version = ?, updated_at = ?::timestamptz"
                + " where id = ? and version = ?",
            new Object[] {in.status().name(), next, OffsetDateTime.now(), id, current.version()});
    if (updated == 0) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "授权已被他人修改，请刷新后重试");
    }
    sql.execute(
        "insert into dealer_authorization_event"
            + "(id,tenant_id,authorization_id,event_type,from_status,to_status,note,occurred_at)"
            + " values (?,?,?,?,?,?,?,?::timestamptz)",
        new Object[] {
          UUID.randomUUID(),
          currentTenant(),
          id,
          eventType,
          current.status().name(),
          in.status().name(),
          in.note() == null ? "" : in.note().trim(),
          OffsetDateTime.now()
        });
    return new DealerSaved(id, next);
  }

  /** 授权台账列表：真分页 + 状态 / 经销商 / 关键词筛选。 */
  public DealerAuthorizationPage pageAuthorizations(
      String status, UUID dealerProfileId, String keyword, int page, int size) {
    int p = Math.max(0, page);
    int s = Math.min(MAX_PAGE_SIZE, Math.max(1, size));
    List<Object> binds = new ArrayList<>();
    List<String> where = new ArrayList<>();
    if (isSet(status)) {
      where.add("a.status = ?");
      binds.add(status.trim());
    }
    if (dealerProfileId != null) {
      where.add("a.dealer_profile_id = ?");
      binds.add(dealerProfileId);
    }
    if (isSet(keyword)) {
      where.add("(a.auth_code ilike ? or p.party_name ilike ?)");
      String pattern = "%" + keyword.trim() + "%";
      binds.add(pattern);
      binds.add(pattern);
    }
    String clause = where.isEmpty() ? "" : " where " + String.join(" and ", where);
    long total =
        sql.fetchOne(
                "select count(*) from dealer_authorization a join dealer_profile p"
                    + " on p.tenant_id = a.tenant_id and p.id = a.dealer_profile_id" + clause,
                binds.toArray())
            .get(0, Long.class);
    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(s);
    pageBinds.add(p * s);
    List<DealerAuthorizationRow> items =
        sql.fetch(
                "select a.id,a.dealer_profile_id,a.auth_code,a.region_codes,a.product_line_codes,"
                    + "a.valid_from,a.valid_to,a.currency,a.dealer_price,a.rebate_rate,a.status,"
                    + "a.compliance_state,a.version,a.created_at,a.updated_at"
                    + " from dealer_authorization a join dealer_profile p"
                    + " on p.tenant_id = a.tenant_id and p.id = a.dealer_profile_id"
                    + clause
                    + " order by a.valid_from desc, a.id limit ? offset ?",
                pageBinds.toArray())
            .map(DealerChannelService::authorizationRow);
    return new DealerAuthorizationPage(items, p, s, total);
  }

  /** 授权详情：台账头 + 配额 + 计提 + 预警 + 事件流。 */
  public DealerAuthorizationDetail authorizationDetail(UUID id) {
    var auth = authorization(id);
    List<DealerQuotaRow> quotas =
        sql.fetch(
                "select id,authorization_id,period_start,period_end,target_quantity,target_amount,"
                    + "target_settled_amount,note from dealer_quota"
                    + " where authorization_id = ? order by period_start desc, id",
                id)
            .map(DealerChannelService::quotaRow);
    List<DealerRebateRow> rebates =
        sql.fetch(
                "select id,authorization_id,dealer_profile_id,period_start,period_end,currency,"
                    + "rebate_rate,base_amount,rebate_amount,accrual_state,finance_confirmed,note,created_at"
                    + " from dealer_rebate where authorization_id = ? order by period_start desc, id",
                id)
            .map(DealerChannelService::rebateRow);
    List<DealerDiversionAlertRow> alerts =
        sql.fetch(
                "select id,dealer_profile_id,authorization_id,dealer_sale_id,breach_kind,"
                    + "authorized_regions,authorized_product_lines,sold_region_code,product_line_code,"
                    + "sale_amount,currency,severity,alert_state,note,detected_at,updated_at"
                    + " from dealer_diversion_alert where authorization_id = ?"
                    + " order by detected_at desc, id",
                id)
            .map(DealerChannelService::alertRow);
    List<DealerAuthorizationEventRow> events =
        sql.fetch(
                "select id,authorization_id,event_type,from_status,to_status,note,occurred_at"
                    + " from dealer_authorization_event where authorization_id = ?"
                    + " order by occurred_at, id",
                id)
            .map(DealerChannelService::eventRow);
    return new DealerAuthorizationDetail(auth, quotas, rebates, alerts, events);
  }

  // ================================================================ 输出②：销量配额与完成率

  /** 建 / 改一份期间配额（同一授权 + 同一期间唯一，重复即冲突）。 */
  @Transactional
  public DealerSaved upsertQuota(DealerQuotaInput in) {
    authorization(in.authorizationId());
    if (in.periodEnd().isBefore(in.periodStart())) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "考核期间结束不能早于开始：" + in.periodStart() + " ~ " + in.periodEnd());
    }
    OffsetDateTime now = OffsetDateTime.now();
    UUID id = UUID.randomUUID();
    try {
      sql.execute(
          "insert into dealer_quota"
              + "(id,tenant_id,authorization_id,period_start,period_end,target_quantity,"
              + "target_amount,target_settled_amount,note,created_at,updated_at)"
              + " values (?,?,?,?::date,?::date,?,?,?,?,?::timestamptz,?::timestamptz)"
              + " on conflict (tenant_id, authorization_id, period_start) do update set"
              + " period_end=excluded.period_end, target_quantity=excluded.target_quantity,"
              + " target_amount=excluded.target_amount,"
              + " target_settled_amount=excluded.target_settled_amount,"
              + " note=excluded.note, updated_at=now()",
          new Object[] {
            id,
            currentTenant(),
            in.authorizationId(),
            in.periodStart(),
            in.periodEnd(),
            in.targetQuantity().setScale(QTY_SCALE, RoundingMode.HALF_UP),
            in.targetAmount().setScale(MONEY_SCALE, RoundingMode.HALF_UP),
            in.targetSettledAmount().setScale(MONEY_SCALE, RoundingMode.HALF_UP),
            in.note() == null ? "" : in.note().trim(),
            now,
            now
          });
    } catch (DuplicateKeyException e) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "该授权在该期间已有配额（期间开始日 " + in.periodStart() + " 重复）");
    }
    UUID quotaId =
        sql.fetchOne(
                "select id from dealer_quota where authorization_id = ? and period_start = ?",
                in.authorizationId(),
                in.periodStart())
            .get(0, UUID.class);
    return new DealerSaved(quotaId, 0);
  }

  /**
   * 配额完成率（输出②）。
   *
   * <p>完成率是 <b>SQL 里的 NUMERIC 除法</b>（{@code round(actual/target, 6)}），
   * 服务层再用 {@link #attainmentRate} 拿 BigDecimal 独立复算一遍，两者不一致就抛 500 ——
   * 这样「列表页看到的完成率」与「详情页算出来的完成率」不可能分叉。
   *
   * <p>目标为 0 时 SQL 侧用 {@code nullif} 得到 null，服务层原样返回 null。
   */
  public DealerQuotaAttainmentPage pageQuotaAttainment(
      LocalDate periodStart, LocalDate periodEnd, UUID dealerProfileId, int page, int size) {
    int p = Math.max(0, page);
    int s = Math.min(MAX_PAGE_SIZE, Math.max(1, size));
    List<Object> binds = new ArrayList<>();
    List<String> where = new ArrayList<>();
    if (periodStart != null) {
      where.add("q.period_start >= ?");
      binds.add(periodStart);
    }
    if (periodEnd != null) {
      where.add("q.period_end <= ?");
      binds.add(periodEnd);
    }
    if (dealerProfileId != null) {
      where.add("a.dealer_profile_id = ?");
      binds.add(dealerProfileId);
    }
    String clause = where.isEmpty() ? "" : " where " + String.join(" and ", where);
    String from =
        " from dealer_quota q join dealer_authorization a"
            + " on a.tenant_id = q.tenant_id and a.id = q.authorization_id"
            + " left join lateral ("
            + "   select coalesce(sum(s.quantity),0) qty, coalesce(sum(s.amount),0) amt,"
            + "          coalesce(sum(coalesce(s.settled_amount,0)),0) settled"
            + "   from dealer_sale s"
            + "   where s.tenant_id = q.tenant_id and s.authorization_id = q.authorization_id"
            + "     and s.sold_on >= q.period_start and s.sold_on <= q.period_end"
            + " ) x on true"
            + clause;
    long total = sql.fetchOne("select count(*)" + from, binds.toArray()).get(0, Long.class);

    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(s);
    pageBinds.add(p * s);
    List<DealerQuotaAttainmentRow> items =
        sql.fetch(
                "select q.id as quota_id,q.authorization_id,a.auth_code,a.dealer_profile_id,a.currency,"
                    + "q.period_start,q.period_end,q.target_quantity,x.qty as actual_quantity,"
                    + " round(x.qty / nullif(q.target_quantity,0), 6) as quantity_rate,"
                    + "q.target_amount,x.amt as actual_amount,"
                    + " round(x.amt / nullif(q.target_amount,0), 6) as amount_rate,"
                    + "q.target_settled_amount,x.settled as actual_settled_amount,"
                    + " round(x.settled / nullif(q.target_settled_amount,0), 6) as settled_rate"
                    + from
                    + " order by q.period_start desc, q.id limit ? offset ?",
                pageBinds.toArray())
            .map(DealerChannelService::attainmentRow);
    // 复算一遍：SQL 的 NUMERIC 除法与服务层的 BigDecimal 除法必须给出同一个数。
    for (DealerQuotaAttainmentRow row : items) {
      verifyRate("数量完成率", row.actualQuantity(), row.targetQuantity(), row.quantityAttainmentRate());
      verifyRate("金额完成率", row.actualAmount(), row.targetAmount(), row.amountAttainmentRate());
      verifyRate(
          "回款完成率", row.actualSettledAmount(), row.targetSettledAmount(), row.settledAttainmentRate());
    }
    return new DealerQuotaAttainmentPage(items, p, s, total);
  }

  /**
   * 完成率的<b>唯一</b>算法：{@code round(actual / target, 6)}，目标为 0 返回 {@code null}。
   *
   * <p>两条分支都是硬要求：
   *
   * <ul>
   *   <li>目标为 0 → {@code null}。「不限配额」与「完成了 0%」在数值上都是 0，
   *       而把「不考核」显示成「一个都没完成」会让运营去追一个不存在的缺口。
   *   <li>目标非 0 → {@link RoundingMode#HALF_UP} 舍到 6 位。用 HALF_UP 而不是
   *       默认的 HALF_EVEN 是为了让「33.3333…%」这类除不尽的结果在中点方向上可预期。
   * </ul>
   */
  private static BigDecimal attainmentRate(BigDecimal actual, BigDecimal target) {
    if (target == null || target.signum() == 0) {
      return null;
    }
    return actual.divide(target, RATE_SCALE, RoundingMode.HALF_UP);
  }

  /**
   * 复算校验：SQL 的除法结果与服务层的除法结果不一致就是 500，而不是把其中一个悄悄改掉。
   *
   * <p>之所以要复算：完成率是这个模块最容易被两处各算一遍的数（列表一个 SQL、详情一次计算），
   * 而两处的舍入策略一旦漂移，运营会看到「同一个经销商的完成率在两个页面不一样」。
   */
  private static void verifyRate(String label, BigDecimal actual, BigDecimal target, BigDecimal sqlRate) {
    BigDecimal mine = attainmentRate(actual, target);
    if (mine == null && sqlRate == null) {
      return;
    }
    if (mine == null || sqlRate == null || mine.compareTo(sqlRate) != 0) {
      throw new IllegalStateException(
          label + "计算不一致：SQL=" + sqlRate + " 服务层=" + mine + "（实绩 " + actual + " / 目标 " + target + "）");
    }
  }

  // ================================================================ 销售记录（配额与窜货的唯一来源）

  /**
   * 登记一条经销商销售与回款记录。
   *
   * <p><b>金额由本类算出</b>（{@code quantity × authorization.dealer_price}），
   * {@link DealerSaleInput} 刻意没有 amount 字段 —— 允许调用方直接传总额
   * 等于允许它绕过授权价，而「这批货按什么价卖出去的」正是对账时要被问到的问题。
   *
   * <p><b>窜货判定在同一个事务里完成</b>：越界就建一条预警，没有「记完销量再回头补预警」的路径。
   */
  @Transactional
  public DealerSaleRow recordSale(DealerSaleInput in) {
    var auth = authorization(in.authorizationId());

    // 授权期外不接受销售：soldOn 必须在 [valid_from, valid_to] 区间内。
    // 刻意用两个落库的日期比较，**不**从 created_at + N 天推算。
    if (in.soldOn().isBefore(auth.validFrom()) || in.soldOn().isAfter(auth.validTo())) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "销售日期 " + in.soldOn() + " 不在授权期限内（" + auth.validFrom() + " ~ " + auth.validTo() + "）");
    }
    if (auth.status() == DealerAuthorizationStatus.SUSPENDED) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "授权已暂停，不能登记销售");
    }
    if (auth.status() == DealerAuthorizationStatus.EXPIRED) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "授权已到期，不能登记销售");
    }

    BigDecimal quantity = in.quantity().setScale(QTY_SCALE, RoundingMode.HALF_UP);
    BigDecimal unitPrice = auth.dealerPrice().setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    BigDecimal amount = quantity.multiply(unitPrice).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    if (amount.signum() <= 0) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "按授权价 " + unitPrice + " 计算，这笔销售金额为 0");
    }
    BigDecimal settled =
        in.settledAmount() == null
            ? null
            : in.settledAmount().setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    if (settled != null && settled.compareTo(amount) > 0) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "回款额 " + settled + " 超过销售额 " + amount);
    }

    UUID id = UUID.randomUUID();
    OffsetDateTime now = OffsetDateTime.now();
    sql.execute(
        "insert into dealer_sale"
            + "(id,tenant_id,dealer_profile_id,authorization_id,sku_id,sku_code,sku_name,"
            + "product_line_code,sold_region_code,sold_on,quantity,unit_price,amount,currency,"
            + "settled_amount,note,recorded_at)"
            + " values (?,?,?,?,?,?,?,?,?,?::date,?,?,?,?,?,?,?::timestamptz)",
        new Object[] {
          id,
          currentTenant(),
          auth.dealerProfileId(),
          in.authorizationId(),
          in.skuId(),
          in.skuCode() == null ? "" : in.skuCode().trim(),
          in.skuName() == null ? "" : in.skuName().trim(),
          in.productLineCode().trim(),
          in.soldRegionCode().trim(),
          in.soldOn(),
          quantity,
          unitPrice,
          amount,
          auth.currency(),
          settled,
          in.note() == null ? "" : in.note().trim(),
          now
        });

    // 窜货判定：销售区域 / 产品线落在授权范围之外就是越界。
    // 既越区域又越产品线 → 合并成一条 BOTH（拆成两条会让「有几笔窜货」被算大）。
    List<String> regions = auth.regionCodes();
    List<String> lines = auth.productLineCodes();
    boolean regionBreach = !regions.contains(in.soldRegionCode().trim());
    boolean lineBreach = !lines.contains(in.productLineCode().trim());
    if (regionBreach || lineBreach) {
      DealerBreachKind kind =
          regionBreach && lineBreach
              ? DealerBreachKind.BOTH
              : (regionBreach ? DealerBreachKind.REGION : DealerBreachKind.PRODUCT_LINE);
      // 级别按金额档位分：这是本模块能依据的**唯一**维度
      // （5.5.2 的价格倒挂维度未交付，见 capabilities）。
      DealerSeverity severity =
          amount.compareTo(new BigDecimal("10000")) >= 0
              ? DealerSeverity.HIGH
              : (amount.compareTo(new BigDecimal("1000")) >= 0
                  ? DealerSeverity.MEDIUM
                  : DealerSeverity.LOW);
      sql.execute(
          "insert into dealer_diversion_alert"
              + "(id,tenant_id,dealer_profile_id,authorization_id,dealer_sale_id,breach_kind,"
              + "authorized_regions,authorized_product_lines,sold_region_code,product_line_code,"
              + "sale_amount,currency,severity,alert_state,note,detected_at,updated_at)"
              + " values (?,?,?,?,?,?,?,?,?,?,?,?,?,'OPEN','',?::timestamptz,?::timestamptz)",
          new Object[] {
            UUID.randomUUID(),
            currentTenant(),
            auth.dealerProfileId(),
            auth.id(),
            id,
            kind.name(),
            regions.toArray(new String[0]),
            lines.toArray(new String[0]),
            in.soldRegionCode().trim(),
            in.productLineCode().trim(),
            amount,
            auth.currency(),
            severity.name(),
            now,
            now
          });
    }

    return sale(
        sql.fetchOne(
            "select id,dealer_profile_id,authorization_id,sku_id,sku_code,sku_name,"
                + "product_line_code,sold_region_code,sold_on,quantity,unit_price,amount,currency,"
                + "settled_amount,note,recorded_at from dealer_sale where id = ?",
            id));
  }

  /** 销售记录列表：真分页 + 授权 / 区域 / 期间筛选。 */
  public DealerSalePage pageSales(
      UUID authorizationId, String regionCode, LocalDate from, LocalDate to, int page, int size) {
    int p = Math.max(0, page);
    int s = Math.min(MAX_PAGE_SIZE, Math.max(1, size));
    List<Object> binds = new ArrayList<>();
    List<String> where = new ArrayList<>();
    if (authorizationId != null) {
      where.add("authorization_id = ?");
      binds.add(authorizationId);
    }
    if (isSet(regionCode)) {
      where.add("sold_region_code = ?");
      binds.add(regionCode.trim());
    }
    if (from != null) {
      where.add("sold_on >= ?");
      binds.add(from);
    }
    if (to != null) {
      where.add("sold_on <= ?");
      binds.add(to);
    }
    String clause = where.isEmpty() ? "" : " where " + String.join(" and ", where);
    long total =
        sql.fetchOne("select count(*) from dealer_sale" + clause, binds.toArray()).get(0, Long.class);
    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(s);
    pageBinds.add(p * s);
    List<DealerSaleRow> items =
        sql.fetch(
                "select id,dealer_profile_id,authorization_id,sku_id,sku_code,sku_name,"
                    + "product_line_code,sold_region_code,sold_on,quantity,unit_price,amount,currency,"
                    + "settled_amount,note,recorded_at from dealer_sale"
                    + clause
                    + " order by sold_on desc, id limit ? offset ?",
                pageBinds.toArray())
            .map(DealerChannelService::saleRow);
    return new DealerSalePage(items, p, s, total);
  }

  // ================================================================ 输出③：返点计提

  /**
   * 返点计提（输出③）。
   *
   * <p><b>基数 = 该授权在该期间的 Σ 销售金额</b>；比例取授权上的<b>当前</b>值作为<b>快照</b>落库 ——
   * 授权之后改比例，已经计提过的期间金额不跟着漂移（那会让历史负债凭空改变）。
   *
   * <p><b>finance_confirmed 恒为 false</b>：蓝图原文「需与财务确认计提口径」，
   * 而 12 应付/返点未交付。界面上必须把这一点显示出来。
   */
  @Transactional
  public DealerRebateRow accrueRebate(DealerRebateAccrueInput in) {
    var auth = authorization(in.authorizationId());
    if (in.periodEnd().isBefore(in.periodStart())) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "计提期间结束不能早于开始：" + in.periodStart() + " ~ " + in.periodEnd());
    }

    BigDecimal base =
        sql.fetchOne(
                "select coalesce(sum(amount),0) from dealer_sale"
                    + " where authorization_id = ? and sold_on >= ? and sold_on <= ?",
                in.authorizationId(),
                in.periodStart(),
                in.periodEnd())
            .get(0, BigDecimal.class)
            .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    if (base.signum() <= 0) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "该授权在 " + in.periodStart() + " ~ " + in.periodEnd() + " 期间没有销售记录，无法计提");
    }

    BigDecimal rate = auth.rebateRate().setScale(RATE_SCALE, RoundingMode.HALF_UP);
    // 返点金额 = 基数 × 比例，HALF_UP 舍到金额刻度。
    // DB 的 CHECK rebate_amount = round(base_amount * rebate_rate, 4) 独立复算一次。
    BigDecimal amount = base.multiply(rate).setScale(MONEY_SCALE, RoundingMode.HALF_UP);

    UUID id = UUID.randomUUID();
    OffsetDateTime now = OffsetDateTime.now();
    try {
      sql.execute(
          "insert into dealer_rebate"
              + "(id,tenant_id,authorization_id,dealer_profile_id,period_start,period_end,currency,"
              + "rebate_rate,base_amount,rebate_amount,accrual_state,finance_confirmed,note,"
              + "created_at,updated_at)"
              + " values (?,?,?,?,?::date,?::date,?,?,?,?,'ACCRUED',false,?,?::timestamptz,?::timestamptz)",
          new Object[] {
            id,
            currentTenant(),
            in.authorizationId(),
            auth.dealerProfileId(),
            in.periodStart(),
            in.periodEnd(),
            auth.currency(),
            rate,
            base,
            amount,
            in.note() == null ? "" : in.note().trim(),
            now,
            now
          });
    } catch (DuplicateKeyException e) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "该授权在该期间已计提过返点（期间开始日 " + in.periodStart() + " 重复），不能重复计提");
    }

    return rebate(
        sql.fetchOne(
            "select id,authorization_id,dealer_profile_id,period_start,period_end,currency,"
                + "rebate_rate,base_amount,rebate_amount,accrual_state,finance_confirmed,note,created_at"
                + " from dealer_rebate where id = ?",
            id));
  }

  /** 返点计提列表：真分页 + 经销商 / 计提状态筛选。 */
  public DealerRebatePage pageRebates(UUID dealerProfileId, String accrualState, int page, int size) {
    int p = Math.max(0, page);
    int s = Math.min(MAX_PAGE_SIZE, Math.max(1, size));
    List<Object> binds = new ArrayList<>();
    List<String> where = new ArrayList<>();
    if (dealerProfileId != null) {
      where.add("dealer_profile_id = ?");
      binds.add(dealerProfileId);
    }
    if (isSet(accrualState)) {
      where.add("accrual_state = ?");
      binds.add(accrualState.trim());
    }
    String clause = where.isEmpty() ? "" : " where " + String.join(" and ", where);
    long total =
        sql.fetchOne("select count(*) from dealer_rebate" + clause, binds.toArray()).get(0, Long.class);
    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(s);
    pageBinds.add(p * s);
    List<DealerRebateRow> items =
        sql.fetch(
                "select id,authorization_id,dealer_profile_id,period_start,period_end,currency,"
                    + "rebate_rate,base_amount,rebate_amount,accrual_state,finance_confirmed,note,created_at"
                    + " from dealer_rebate"
                    + clause
                    + " order by period_start desc, id limit ? offset ?",
                pageBinds.toArray())
            .map(DealerChannelService::rebateRow);
    return new DealerRebatePage(items, p, s, total);
  }

  // ================================================================ 输出④：窜货预警

  /** 窜货预警列表：真分页 + 状态 / 经销商 / 越界维度筛选。 */
  public DealerAlertPage pageAlerts(
      String alertState, String breachKind, UUID dealerProfileId, int page, int size) {
    int p = Math.max(0, page);
    int s = Math.min(MAX_PAGE_SIZE, Math.max(1, size));
    List<Object> binds = new ArrayList<>();
    List<String> where = new ArrayList<>();
    if (isSet(alertState)) {
      where.add("alert_state = ?");
      binds.add(alertState.trim());
    }
    if (isSet(breachKind)) {
      where.add("breach_kind = ?");
      binds.add(breachKind.trim());
    }
    if (dealerProfileId != null) {
      where.add("dealer_profile_id = ?");
      binds.add(dealerProfileId);
    }
    String clause = where.isEmpty() ? "" : " where " + String.join(" and ", where);
    long total =
        sql.fetchOne("select count(*) from dealer_diversion_alert" + clause, binds.toArray())
            .get(0, Long.class);
    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(s);
    pageBinds.add(p * s);
    List<DealerDiversionAlertRow> items =
        sql.fetch(
                "select id,dealer_profile_id,authorization_id,dealer_sale_id,breach_kind,"
                    + "authorized_regions,authorized_product_lines,sold_region_code,product_line_code,"
                    + "sale_amount,currency,severity,alert_state,note,detected_at,updated_at"
                    + " from dealer_diversion_alert"
                    + clause
                    + " order by detected_at desc, id limit ? offset ?",
                pageBinds.toArray())
            .map(DealerChannelService::alertRow);
    return new DealerAlertPage(items, p, s, total);
  }

  /**
   * 处置一条窜货预警。
   *
   * <p>判为 {@link DealerAlertState#DISMISSED} 必须写明理由（{@code @NotBlank} 与 DB 的 CHECK 各拦一道）——
   * 否则「查过了确实没窜」与「懒得处理直接关掉」在数据上无法区分。
   */
  @Transactional
  public DealerDiversionAlertRow changeAlertState(UUID id, DealerAlertStateInput in) {
    Record r =
        sql.fetchOne(
            "select id,dealer_profile_id,authorization_id,dealer_sale_id,breach_kind,"
                + "authorized_regions,authorized_product_lines,sold_region_code,product_line_code,"
                + "sale_amount,currency,severity,alert_state,note,detected_at,updated_at"
                + " from dealer_diversion_alert where id = ?",
            id);
    if (r == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "窜货预警不存在");
    }
    var current = alertRow(r);
    if (current.alertState() == DealerAlertState.DISMISSED) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "预警已判为误报，终态不可再改");
    }
    sql.execute(
        "update dealer_diversion_alert set alert_state = ?, note = ?, updated_at = ?::timestamptz where id = ?",
        new Object[] {in.alertState().name(), in.note().trim(), OffsetDateTime.now(), id});
    return alert(
        sql.fetchOne(
            "select id,dealer_profile_id,authorization_id,dealer_sale_id,breach_kind,"
                + "authorized_regions,authorized_product_lines,sold_region_code,product_line_code,"
                + "sale_amount,currency,severity,alert_state,note,detected_at,updated_at"
                + " from dealer_diversion_alert where id = ?",
            id));
  }

  // ================================================================ 内部：单条读取

  private DealerAuthorizationRow authorization(UUID id) {
    Record r =
        sql.fetchOne(
            "select id,dealer_profile_id,auth_code,region_codes,product_line_codes,valid_from,valid_to,"
                + "currency,dealer_price,rebate_rate,status,compliance_state,version,created_at,updated_at"
                + " from dealer_authorization where id = ?",
            id);
    if (r == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "经销授权不存在");
    }
    return authorizationRow(r);
  }

  // ================================================================ 行映射

  private static DealerProfileRow profileRow(Record r) {
    return new DealerProfileRow(
        r.get("id", UUID.class),
        r.get("party_id", UUID.class),
        r.get("party_code", String.class),
        r.get("party_name", String.class),
        DealerCustomerLevel.valueOf(r.get("customer_level", String.class)),
        blankToNull(r.get("default_currency", String.class)),
        r.get("payment_terms_days", Integer.class),
        r.get("source_system", String.class),
        r.get("source_version", Long.class),
        r.get("synced_at", OffsetDateTime.class));
  }

  private static DealerAuthorizationRow authorizationRow(Record r) {
    return new DealerAuthorizationRow(
        r.get("id", UUID.class),
        r.get("dealer_profile_id", UUID.class),
        r.get("auth_code", String.class),
        textArray(r.get("region_codes", String[].class)),
        textArray(r.get("product_line_codes", String[].class)),
        r.get("valid_from", LocalDate.class),
        r.get("valid_to", LocalDate.class),
        r.get("currency", String.class),
        r.get("dealer_price", BigDecimal.class),
        r.get("rebate_rate", BigDecimal.class),
        DealerAuthorizationStatus.valueOf(r.get("status", String.class)),
        DealerComplianceState.valueOf(r.get("compliance_state", String.class)),
        r.get("version", Integer.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  private static DealerAuthorizationEventRow eventRow(Record r) {
    return new DealerAuthorizationEventRow(
        r.get("id", UUID.class),
        r.get("authorization_id", UUID.class),
        r.get("event_type", String.class),
        blankToNull(r.get("from_status", String.class)),
        blankToNull(r.get("to_status", String.class)),
        r.get("note", String.class),
        r.get("occurred_at", OffsetDateTime.class));
  }

  private static DealerQuotaRow quotaRow(Record r) {
    return new DealerQuotaRow(
        r.get("id", UUID.class),
        r.get("authorization_id", UUID.class),
        r.get("period_start", LocalDate.class),
        r.get("period_end", LocalDate.class),
        r.get("target_quantity", BigDecimal.class),
        r.get("target_amount", BigDecimal.class),
        r.get("target_settled_amount", BigDecimal.class),
        r.get("note", String.class));
  }

  private static DealerQuotaAttainmentRow attainmentRow(Record r) {
    return new DealerQuotaAttainmentRow(
        r.get("quota_id", UUID.class),
        r.get("authorization_id", UUID.class),
        r.get("auth_code", String.class),
        r.get("dealer_profile_id", UUID.class),
        r.get("period_start", LocalDate.class),
        r.get("period_end", LocalDate.class),
        r.get("currency", String.class),
        r.get("target_quantity", BigDecimal.class),
        r.get("actual_quantity", BigDecimal.class),
        r.get("quantity_rate", BigDecimal.class),
        r.get("target_amount", BigDecimal.class),
        r.get("actual_amount", BigDecimal.class),
        r.get("amount_rate", BigDecimal.class),
        r.get("target_settled_amount", BigDecimal.class),
        r.get("actual_settled_amount", BigDecimal.class),
        r.get("settled_rate", BigDecimal.class));
  }

  private static DealerSaleRow saleRow(Record r) {
    return new DealerSaleRow(
        r.get("id", UUID.class),
        r.get("dealer_profile_id", UUID.class),
        r.get("authorization_id", UUID.class),
        r.get("sku_id", UUID.class),
        r.get("sku_code", String.class),
        r.get("sku_name", String.class),
        r.get("product_line_code", String.class),
        r.get("sold_region_code", String.class),
        r.get("sold_on", LocalDate.class),
        r.get("quantity", BigDecimal.class),
        r.get("unit_price", BigDecimal.class),
        r.get("amount", BigDecimal.class),
        r.get("currency", String.class),
        r.get("settled_amount", BigDecimal.class),
        r.get("note", String.class),
        r.get("recorded_at", OffsetDateTime.class));
  }

  private static DealerRebateRow rebateRow(Record r) {
    return new DealerRebateRow(
        r.get("id", UUID.class),
        r.get("authorization_id", UUID.class),
        r.get("dealer_profile_id", UUID.class),
        r.get("period_start", LocalDate.class),
        r.get("period_end", LocalDate.class),
        r.get("currency", String.class),
        r.get("rebate_rate", BigDecimal.class),
        r.get("base_amount", BigDecimal.class),
        r.get("rebate_amount", BigDecimal.class),
        DealerAccrualState.valueOf(r.get("accrual_state", String.class)),
        Boolean.TRUE.equals(r.get("finance_confirmed", Boolean.class)),
        r.get("note", String.class),
        r.get("created_at", OffsetDateTime.class));
  }

  private static DealerDiversionAlertRow alertRow(Record r) {
    return new DealerDiversionAlertRow(
        r.get("id", UUID.class),
        r.get("dealer_profile_id", UUID.class),
        r.get("authorization_id", UUID.class),
        r.get("dealer_sale_id", UUID.class),
        DealerBreachKind.valueOf(r.get("breach_kind", String.class)),
        textArray(r.get("authorized_regions", String[].class)),
        textArray(r.get("authorized_product_lines", String[].class)),
        r.get("sold_region_code", String.class),
        r.get("product_line_code", String.class),
        r.get("sale_amount", BigDecimal.class),
        r.get("currency", String.class),
        DealerSeverity.valueOf(r.get("severity", String.class)),
        DealerAlertState.valueOf(r.get("alert_state", String.class)),
        r.get("note", String.class),
        r.get("detected_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  private static DealerSaleRow sale(Record r) {
    if (r == null) {
      throw new IllegalStateException("销售记录写入后读不回来");
    }
    return saleRow(r);
  }

  private static DealerRebateRow rebate(Record r) {
    if (r == null) {
      throw new IllegalStateException("返点计提写入后读不回来");
    }
    return rebateRow(r);
  }

  private static DealerDiversionAlertRow alert(Record r) {
    if (r == null) {
      throw new IllegalStateException("窜货预警写入后读不回来");
    }
    return alertRow(r);
  }

  // ================================================================ 小工具

  private static List<String> textArray(String[] value) {
    return value == null ? List.of() : Arrays.asList(value);
  }

  /**
   * 当前租户：来自 {@code AccessService.run} 在事务里绑定的 {@code app.current_tenant}。
   *
   * <p><b>绝不</b>取自任何请求头 —— 伪造的 {@code X-Tenant-Id} 改不动这个 GUC，
   * 而每一条 {@code INSERT} 都要显式带上它：RLS 的 {@code WITH CHECK} 会在 tenant_id 为 NULL 时
   * 拒绝写入（NULL = 不等于任何租户），所以「忘了绑定租户」在这里表现为一个 409，
   * 而不是一行悄悄写进错误租户的数据。
   */
  private UUID currentTenant() {
    return sql.fetchOne("select nullif(current_setting('app.current_tenant',true),'')::uuid")
        .get(0, UUID.class);
  }

  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s;
  }

  private static boolean isSet(String s) {
    return s != null && !s.isBlank();
  }

  /** 去空白 + 去重 + 保持顺序（授权区域/产品线是集合，重复项没有意义）。 */
  private static List<String> normalize(List<String> values) {
    return values.stream()
        .map(String::trim)
        .filter(v -> !v.isEmpty())
        .distinct()
        .toList();
  }
}
