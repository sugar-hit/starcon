package com.starcon.finance;

import static com.starcon.finance.FundAccountModels.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 12.7.1.1 账户管理。
 *
 * <p>蓝图 12-finance.md：「管理资金账户：银行账户、支付平台账户、平台备付金账户，记录币种、账号、余额、开户主体（法人）、账户类型。」输入：「账户定义；余额同步或手工更新；关联法人。」输出：「账户档案 + 余额历史。」关系：「上游 = 17 法人；下游 = 12.2 收付款、12.7.1.2 对账。<b>账户必须绑定法人</b>，跨法人混用资金账户会导致收入归属错误。」
 *
 * <p><b>本模块的五项能力，各自的真数据源</b>：
 *
 * <ol>
 *   <li><b>账户档案</b> —— 本表 {@code finance_fund_account}（租户自己维护的真实档案）。</li>
 *   <li><b>余额历史</b> —— 本表 {@code finance_fund_account_balance}（**append-only**，只追加）。</li>
 *   <li><b>开户主体（法人）</b> —— identity 的 {@code GET /identity/legal-entities}
 *       （跨服务**只读**端口，落库前<b>实时校验</b>；禁跨库 FK / 跨库 SQL / 视图）。</li>
 *   <li><b>按币种分组的余额汇总</b> —— 本表真聚合（**跨币种绝不相加**，也不做本币折算）。</li>
 *   <li><b>手工更新的留痕</b> —— 谁 / 何时 / 为什么 / 改前改后，全部落在余额历史行里。</li>
 * </ol>
 *
 * <p><b>五条口径纪律</b>：
 *
 * <ol>
 *   <li><b>账户必须绑定法人。</b>建户与换绑都要实时校验；法人清单<b>读不到</b>时一律拒绝
 *       （503），<b>不</b>把「读不到」当成「校验通过」。</li>
 *   <li><b>余额历史不可改写。</b>每次同步 / 手工更新都<b>追加</b>一行；
 *       当前余额由触发器从最新一条历史推导（单一写入口）。</li>
 *   <li><b>单账户单币种。</b>币种是账户的固有属性，<b>不可改</b>（DB 触发器钉死）。</li>
 *   <li><b>跨币种绝不相加。</b>汇总一律按币种分组；「全部账户加在一起」这个数<b>没有意义</b>，
 *       如实给 {@code null} 而不是给一个假数（折算归 12.7.2.1）。</li>
 *   <li><b>账号脱敏。</b>原始账号只在请求作用域内存活，落库只留掩码 + 后四位，
 *       本类<b>不</b>记录任何含原始账号的日志。</li>
 * </ol>
 */
@Service
public class FundAccountService {

  /** 一页最大条数。 */
  static final int MAX_PAGE_SIZE = 200;

  /** 默认页长。 */
  static final int DEFAULT_PAGE_SIZE = 20;

  /** 详情页带的最近余额历史条数。 */
  static final int DETAIL_RECENT_BALANCES = 20;

  /** 账户编号前缀（用户不填编号时由系统生成）。 */
  static final String ACCOUNT_NO_PREFIX = "FA-";

  /** 折算能力的归属模块（本模块不折算）。 */
  static final String CONVERSION_OWNER = "12.7.2.1";

  private final DSLContext sql;
  private final FundAccountLegalEntityPort legalEntities;

  public FundAccountService(DSLContext sql, FundAccountLegalEntityPort legalEntities) {
    this.sql = sql;
    this.legalEntities = legalEntities;
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：**每一维说清「有没有、依据是什么、缺了会怎样」**。
   *
   * <p>⚠️ 必须把「本模块没接」与「上游没交付」分开写：全部写成「不可用」的话，
   * 运维会以为要去催 12.7.1.2，而实际上 12.7.1.1 的能力**全部已交付**。
   */
  public FinanceFundAccountCapabilities capabilities() {
    var capabilities = new ArrayList<FinanceFundAccountCapability>();
    var gaps = new ArrayList<FinanceFundAccountDownstreamGap>();

    capabilities.add(
        new FinanceFundAccountCapability(
            "12.7.1.1",
            "资金账户档案",
            true,
            "finance_fund_account（本模块自己的表）",
            "",
            "银行账户 / 支付平台账户 / 平台备付金账户三种**受控类型**（CHECK 钉死，不是自由文本）；"
                + "一个账户一个币种（币种不可改，DB 触发器钉死）；"
                + "账户**必须绑定法人**（软引用 + 落库前实时校验，禁跨库 FK）；"
                + "账号**脱敏存储与返回**（只存掩码 + 后四位，原始账号不落库、不写日志）。"));

    capabilities.add(
        new FinanceFundAccountCapability(
            "12.7.1.1",
            "余额历史",
            true,
            "finance_fund_account_balance（本模块自己的表）",
            "",
            "**append-only**：每次同步 / 手工更新**追加**一行，历史行不许 UPDATE / DELETE"
                + "（DB 触发器 + 收回运行角色权限两层钉死）；"
                + "当前余额由 AFTER INSERT 触发器从最新一条历史推导（**单一写入口**，"
                + "不存在「两处余额对不上」）；补录更早的历史余额**不**把当前余额倒回去。"));

    capabilities.add(
        new FinanceFundAccountCapability(
            "12.7.1.1",
            "手工更新余额的留痕",
            true,
            "finance_fund_account_balance 的 operator_user_id / recorded_at / reason / previous_amount",
            "",
            "手工更新必须写**谁**（已认证会话里的 user id）、**何时**（事实发生时刻）、"
                + "**为什么**（reason，DB 层 CHECK 钉死：MANUAL 必填）、"
                + "**改前改后**（previous_amount → balance_amount，且必须与上一条首尾相接）。"));

    capabilities.add(
        new FinanceFundAccountCapability(
            "12.7.1.1",
            "按币种分组的余额汇总",
            true,
            "finance_fund_account 的真聚合",
            "",
            "汇总**只到币种级**：USD 一行、EUR 一行，**绝不**把不同币种加在一起；"
                + "币种内再按**法人**分组（跨法人混用资金账户会导致收入归属错误，"
                + "所以法人维度必须在汇总里看得见）。"
                + "「全部账户加在一起」这个数**没有意义**，本模块不给它。"));

    capabilities.add(
        new FinanceFundAccountCapability(
            "12.7.2.1",
            "汇率折算（本位币口径的总余额）",
            false,
            "本模块不读汇率：汇率归 catalog 5.2.2.1，敞口测算归 12.7.2.1",
            "12.7.2.1",
            "⇒ 本模块**不做**任何跨币种折算，也**不**按 1:1 假装折算。"
                + "汇总响应里的 crossCurrencyTotal 恒为 null 并写明原因，"
                + "而不是给一个让人误以为可以加总的数字。"));

    capabilities.add(
        new FinanceFundAccountCapability(
            "12.7.1.2",
            "银行对账（流水 / 差异 / 未达账项）",
            false,
            "下游模块未交付",
            "12.7.1.2",
            "⇒ 本模块**不做对账**、**不产出对账差异**、**不产出未达账项**。"
                + "只保证「账户档案 + 余额历史」可供将来的对账使用。"));

    capabilities.add(
        new FinanceFundAccountCapability(
            "12.2",
            "收付款引用受管资金账户",
            false,
            "finance_receipt.fund_account_ref 由 V341 的 CHECK 钉死恒为 NULL",
            "12.2",
            "⇒ 12.2 要引用本模块的账户时，由 **12.2 自己**改那条 CHECK"
                + "（那是它的写入所有权，且迁移版本号由调度者统一分配）；"
                + "本模块**不 ALTER 别人的表**。"));

    gaps.add(
        new FinanceFundAccountDownstreamGap(
            "12.7.1.2", "银行对账", "finance", false, "12.7 资金",
            "对账是**另一个模块**：本模块只交付「账户档案 + 余额历史」，"
                + "不做对账逻辑（流水解析、差异认定、未达账项都归 12.7.1.2）。"));

    gaps.add(
        new FinanceFundAccountDownstreamGap(
            "12.2", "收付款引用账户", "finance", false, "12 财务核算",
            "12.2 已交付但**只读**账户信息：收付款单引用账户时不写本模块的表；"
                + "反向引用（收款单 / 付款单挂到某个账户）由 12.2 自己接线。"));

    gaps.add(
        new FinanceFundAccountDownstreamGap(
            "12.7.2.1", "资金分析（账户级资金分布）", "finance", false, "12.7 资金",
            "12.7.2.1 的「各币种资金分布」当前只到**币种级**"
                + "（它的 DB 层用 CHECK 把受管账户归属钉死恒为 NULL）；"
                + "本模块交付后由 12.7.2.1 自己接线，届时账户级分布才有数据。"));

    return new FinanceFundAccountCapabilities(List.copyOf(capabilities), List.copyOf(gaps));
  }

  // ================================================================ 筛选项

  /** 筛选项下拉：账户类型取自**受控词表**，币种取自**真实数据**，法人取自 identity。 */
  public FinanceFundAccountOptions options(String bearer) {
    var types =
        List.of(
            new FinanceFundAccountOption(
                FinanceFundAccountType.BANK.name(), FinanceFundAccountType.BANK.label()),
            new FinanceFundAccountOption(
                FinanceFundAccountType.PAYMENT_PLATFORM.name(),
                FinanceFundAccountType.PAYMENT_PLATFORM.label()),
            new FinanceFundAccountOption(
                FinanceFundAccountType.PLATFORM_RESERVE.name(),
                FinanceFundAccountType.PLATFORM_RESERVE.label()));

    var currencies =
        sql.fetch("select distinct currency from finance_fund_account order by currency")
            .map(r -> r.get(0, String.class));

    var set = legalEntities.legalEntities(bearer);
    var entities =
        set.items().stream()
            .map(
                e ->
                    new FinanceFundLegalEntityOption(
                        e.id(), e.code(), e.legalName(), e.status(), e.baseCurrency()))
            .toList();

    return new FinanceFundAccountOptions(
        types,
        currencies,
        set.available(),
        entities,
        set.note(),
        "账户类型取自**受控词表**（银行账户 / 支付平台账户 / 平台备付金账户），不是自由文本；"
            + "币种候选取自本租户**已建账户上真实出现过的币种**（不是常量）；"
            + "法人清单实时读 identity 的 GET /identity/legal-entities（授权只读端口）。");
  }

  // ================================================================ 账户档案

  /** 账户列表（真分页 + 真筛选 + 真排序）。 */
  public FinanceFundAccountPage list(FinanceFundAccountQuery query) {
    int page = Math.max(0, query.page());
    int size = Math.min(Math.max(1, query.size()), MAX_PAGE_SIZE);

    var where = new StringBuilder(" where 1=1");
    var args = new ArrayList<Object>();

    if (query.accountType() != null) {
      where.append(" and account_type = ?");
      args.add(query.accountType().name());
    }
    if (query.currency() != null && !query.currency().isBlank()) {
      where.append(" and currency = ?");
      args.add(query.currency().trim().toUpperCase(Locale.ROOT));
    }
    if (query.legalEntityId() != null) {
      where.append(" and legal_entity_id = ?");
      args.add(query.legalEntityId());
    }
    if (query.status() != null) {
      where.append(" and status = ?");
      args.add(query.status().name());
    }
    if (query.keyword() != null && !query.keyword().isBlank()) {
      // 用 position(lower(?) in lower(...)) 而不是 like：不用处理 % / _ 的转义，
      // 「%」这种字符当关键字搜时不会变成通配符。
      var keyword = query.keyword().trim().toLowerCase(Locale.ROOT);
      where.append(
          " and (position(? in lower(account_name)) > 0"
              + " or position(? in lower(account_no)) > 0"
              + " or position(? in lower(bank_name)) > 0"
              + " or position(? in lower(legal_entity_name)) > 0)");
      args.add(keyword);
      args.add(keyword);
      args.add(keyword);
      args.add(keyword);
    }

    var total =
        sql.fetchOne("select count(*) from finance_fund_account" + where, args.toArray())
            .get(0, Long.class);

    var orderBy = orderByClause(query.sort(), query.direction());
    var bindings = new ArrayList<Object>(args);
    bindings.add(size);
    bindings.add(page * size);
    var rows =
        sql.fetch(
            "select id,account_no,account_name,account_type,currency,legal_entity_id,"
                + " legal_entity_code,legal_entity_name,bank_name,account_number_masked,"
                + " account_number_tail,account_holder,status,current_balance,current_balance_at,"
                + " balance_record_count,note,version,created_at,updated_at"
                + " from finance_fund_account"
                + where
                + orderBy
                + " limit ? offset ?",
            bindings.toArray());
    return new FinanceFundAccountPage(rows.map(FundAccountService::accountRow), page, size, total);
  }

  private static String orderByClause(String sort, String direction) {
    var column =
        switch (sort == null ? "" : sort.trim().toUpperCase(Locale.ROOT)) {
          case "ACCOUNT_NAME" -> "account_name";
          case "CREATED_AT" -> "created_at";
          case "UPDATED_AT" -> "updated_at";
          case "BALANCE_AT" -> "current_balance_at";
          default -> "account_no";
        };
    var dir =
        "DESC".equalsIgnoreCase(direction == null ? "" : direction.trim()) ? " desc" : " asc";
    return " order by " + column + dir + ", id asc";
  }

  /** 账户详情：档案 + 最近若干条余额历史。 */
  public FinanceFundAccountDetail get(UUID id) {
    var account = accountRow(requireAccount(id));
    var balances = balanceRows(id, 0, DETAIL_RECENT_BALANCES);
    var total =
        sql.fetchOne("select count(*) from finance_fund_account_balance where account_id = ?", id)
            .get(0, Long.class);
    return new FinanceFundAccountDetail(account, balances.items(), total);
  }

  // ================================================================ 建户

  /**
   * 建账户。
   *
   * <p>⚠️ 三道闸：① 法人**实时校验**（读不到 ⇒ 503；读到了但没这个法人 ⇒ 400）；
   * ② 账号脱敏（原始账号不落库）；③ 建档即写第一条余额历史（INITIAL），
   * 当前余额由触发器从它推导 —— **不存在「有账户但没有余额事实」的账户**。
   */
  @Transactional
  public FinanceFundAccountRow create(
      FinanceFundAccountCreateInput input, String bearer, AccessService.Access actor) {
    var legalEntity = requireLegalEntity(input.legalEntityId(), bearer);
    var bankName = trim(input.bankName());
    var accountNumber = trim(input.accountNumber());
    checkBankName(input.accountType(), bankName);

    var masked = maskAccountNumber(accountNumber);
    var tail = accountNumber.isEmpty() ? "" : accountNumber.substring(accountNumber.length() - 4);

    var id = UUID.randomUUID();
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    var recordedAt = now;
    var initial = money(input.initialBalance() == null ? BigDecimal.ZERO : input.initialBalance());

    var accountNo =
        input.accountNo() == null || input.accountNo().isBlank()
            ? generatedAccountNo()
            : input.accountNo().trim();

    sql.execute(
        "insert into finance_fund_account(id,tenant_id,account_no,account_name,account_type,currency,"
            + " legal_entity_id,legal_entity_code,legal_entity_name,bank_name,account_number_masked,"
            + " account_number_tail,account_holder,status,current_balance,current_balance_at,"
            + " balance_record_count,note,version,created_at,updated_at)"
            + " values (?,?,?,?,?,?,?,?,?,?,?,?,?,'ACTIVE',0,?::timestamptz,0,?,0,?::timestamptz,?::timestamptz)",
        id,
        actor.tenantId(),
        accountNo,
        input.accountName().trim(),
        input.accountType().name(),
        input.currency().trim().toUpperCase(Locale.ROOT),
        legalEntity.id(),
        legalEntity.code(),
        legalEntity.legalName(),
        bankName,
        masked,
        tail,
        trim(input.accountHolder()),
        // ⚠️ 绑定顺序必须与 values 里的 ? 一一对应：current_balance_at → note → created_at → updated_at
        recordedAt,
        trim(input.note()),
        now,
        now);

    // 建档即第一条余额事实：账户开立时的余额。
    insertBalance(
        id,
        actor.tenantId(),
        input.currency().trim().toUpperCase(Locale.ROOT),
        legalEntity.id(),
        legalEntity.code(),
        legalEntity.legalName(),
        initial,
        BigDecimal.ZERO,
        FinanceFundBalanceSource.INITIAL,
        "建档初始余额",
        "",
        "",
        actor.userId(),
        recordedAt);

    return accountRow(requireAccount(id));
  }

  // ================================================================ 改档案

  /**
   * 改账户档案。
   *
   * <p>⚠️ <b>币种与账户编号不在这里</b>：单账户单币种是硬约束，账户编号是身份。
   * 换绑法人要**实时校验**（读不到 ⇒ 503）。
   */
  @Transactional
  public FinanceFundAccountRow update(
      UUID id,
      FinanceFundAccountUpdateInput input,
      String bearer,
      AccessService.Access actor) {
    var current = requireAccount(id);
    if ("INACTIVE".equals(current.get("status", String.class))) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "资金账户已停用，需要启用后才能修改档案");
    }

    var bankName = trim(input.bankName());
    checkBankName(input.accountType(), bankName);

    var legalEntityId = current.get("legal_entity_id", UUID.class);
    var legalEntityCode = current.get("legal_entity_code", String.class);
    var legalEntityName = current.get("legal_entity_name", String.class);
    if (input.legalEntityId() != null && !input.legalEntityId().equals(legalEntityId)) {
      var legalEntity = requireLegalEntity(input.legalEntityId(), bearer);
      legalEntityId = legalEntity.id();
      legalEntityCode = legalEntity.code();
      legalEntityName = legalEntity.legalName();
    }

    // 账号：null/省略 = 不改；空串 = 清除；给了 = 重新脱敏。
    var masked = current.get("account_number_masked", String.class);
    var tail = current.get("account_number_tail", String.class);
    if (input.accountNumber() != null) {
      var raw = input.accountNumber().trim();
      if (raw.isEmpty()) {
        masked = "";
        tail = "";
      } else {
        masked = maskAccountNumber(raw);
        tail = raw.substring(raw.length() - 4);
      }
    }

    var now = OffsetDateTime.now(ZoneOffset.UTC);
    sql.execute(
        "update finance_fund_account set account_name = ?, account_type = ?, bank_name = ?,"
            + " account_number_masked = ?, account_number_tail = ?, account_holder = ?,"
            + " legal_entity_id = ?, legal_entity_code = ?, legal_entity_name = ?, note = ?,"
            + " version = version + 1, updated_at = ?::timestamptz"
            + " where id = ? and tenant_id = ?",
        input.accountName().trim(),
        input.accountType().name(),
        bankName,
        masked,
        tail,
        trim(input.accountHolder()),
        legalEntityId,
        legalEntityCode,
        legalEntityName,
        trim(input.note()),
        now,
        id,
        actor.tenantId());

    return accountRow(requireAccount(id));
  }

  // ================================================================ 登记余额

  /**
   * 登记一次余额（同步 / 手工更新）。
   *
   * <p>⚠️ <b>余额快照是事实，不是当前值覆盖</b>：这里**追加**一行余额历史，
   * 账户上的当前余额由触发器推导。历史行不许 UPDATE / DELETE。
   */
  @Transactional
  public FinanceFundAccountBalanceRow recordBalance(
      UUID accountId, FinanceFundAccountBalanceInput input, AccessService.Access actor) {
    var current = requireAccount(accountId);
    if ("INACTIVE".equals(current.get("status", String.class))) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "资金账户已停用，不再接受新的余额登记");
    }
    if (input.changeSource() == FinanceFundBalanceSource.INITIAL) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "建档初始余额只能在建账户时登记，不能单独补一条 INITIAL");
    }

    var reason = trim(input.reason());
    var sourceSystem = trim(input.sourceSystem());
    var sourceReference = trim(input.sourceReference());

    if (input.changeSource() == FinanceFundBalanceSource.MANUAL && reason.isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "手工更新余额必须写明理由（谁、何时、为什么、改前改后都要留痕）");
    }
    if (input.changeSource() == FinanceFundBalanceSource.SYNC
        && (sourceSystem.isEmpty() || sourceReference.isEmpty())) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "余额同步必须带来源系统与来源流水号（幂等键的两半）");
    }

    var recordedAt =
        input.recordedAt() == null ? OffsetDateTime.now(ZoneOffset.UTC) : input.recordedAt();
    var previous = current.get("current_balance", BigDecimal.class);

    try {
      var balanceId =
          insertBalance(
              accountId,
              actor.tenantId(),
              current.get("currency", String.class),
              current.get("legal_entity_id", UUID.class),
              current.get("legal_entity_code", String.class),
              current.get("legal_entity_name", String.class),
              money(input.balanceAmount()),
              previous,
              input.changeSource(),
              reason,
              sourceSystem,
              sourceReference,
              actor.userId(),
              recordedAt);
      return balanceRow(requireBalance(balanceId));
    } catch (org.jooq.exception.DataAccessException | org.springframework.dao.DataAccessException e) {
      // 同步幂等键冲突 ⇒ 同一笔来源流水已经入过账，**不能**重复入账。
      if (input.changeSource() == FinanceFundBalanceSource.SYNC
          && messageChainContains(e, "finance_fund_account_balance_sync_key")) {
        throw new ResponseStatusException(
            HttpStatus.CONFLICT,
            "这条来源流水已经入过账了（" + sourceSystem + " / " + sourceReference + "）：余额同步不重复入账");
      }
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "余额登记与既有记录冲突，请刷新后重试：" + rootMessage(e));
    }
  }

  /** 追加一条余额历史（写入口只有这里与建户两处）。返回这一行的 id。 */
  private UUID insertBalance(
      UUID accountId,
      UUID tenantId,
      String currency,
      UUID legalEntityId,
      String legalEntityCode,
      String legalEntityName,
      BigDecimal balance,
      BigDecimal previous,
      FinanceFundBalanceSource source,
      String reason,
      String sourceSystem,
      String sourceReference,
      UUID operatorUserId,
      OffsetDateTime recordedAt) {
    var id = UUID.randomUUID();
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    sql.execute(
        "insert into finance_fund_account_balance(id,tenant_id,account_id,currency,legal_entity_id,"
            + " legal_entity_code,legal_entity_name,balance_amount,previous_amount,change_source,"
            + " reason,source_system,source_reference,operator_user_id,recorded_at,created_at)"
            + " values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz)",
        id,
        tenantId,
        accountId,
        currency,
        legalEntityId,
        legalEntityCode,
        legalEntityName,
        balance,
        previous,
        source.name(),
        reason,
        sourceSystem,
        sourceReference,
        operatorUserId,
        recordedAt,
        now);
    return id;
  }

  // ================================================================ 余额历史

  /** 某个账户的余额历史（真分页，按事实时刻倒序）。 */
  public FinanceFundAccountBalancePage balances(UUID accountId, int page, int size) {
    requireAccount(accountId);
    int p = Math.max(0, page);
    int s = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
    var total =
        sql.fetchOne(
                "select count(*) from finance_fund_account_balance where account_id = ?", accountId)
            .get(0, Long.class);
    var rows =
        sql.fetch(
            "select id,account_id,currency,legal_entity_id,legal_entity_code,legal_entity_name,"
                + " balance_amount,previous_amount,change_source,reason,source_system,"
                + " source_reference,operator_user_id,recorded_at,created_at"
                + " from finance_fund_account_balance where account_id = ?"
                + " order by recorded_at desc, id desc limit ? offset ?",
            accountId,
            s,
            p * s);
    return new FinanceFundAccountBalancePage(rows.map(FundAccountService::balanceRow), p, s, total);
  }

  // ================================================================ 按币种汇总

  /**
   * 余额汇总：**按币种分组**，币种内再按法人分组。
   *
   * <p>⚠️ <b>绝不跨币种相加</b>，也<b>不</b>做本币折算（汇率不可得就不折算，
   * 而不是按 1:1 假装折算）⇒ 「全部账户加在一起」这个数<b>没有意义</b>，
   * 响应里如实给 {@code null} 并写明归属模块。
   */
  public FinanceFundCurrencyBalanceSummary summary() {
    var byCurrency =
        sql.fetch(
            "select currency, count(*) as account_count,"
                + " count(*) filter (where status = 'ACTIVE') as active_count,"
                + " sum(current_balance) as total_balance"
                + " from finance_fund_account group by currency order by currency");

    var byEntity =
        sql.fetch(
            "select currency, legal_entity_id, min(legal_entity_code) as legal_entity_code,"
                + " min(legal_entity_name) as legal_entity_name, count(*) as account_count,"
                + " sum(current_balance) as total_balance"
                + " from finance_fund_account"
                + " group by currency, legal_entity_id order by currency, legal_entity_code");

    var entityTotals = new LinkedHashMap<String, List<FinanceFundLegalEntityBalance>>();
    for (Record r : byEntity) {
      var currency = r.get("currency", String.class);
      var list =
          entityTotals.computeIfAbsent(currency, k -> new ArrayList<>());
      list.add(
          new FinanceFundLegalEntityBalance(
              r.get("legal_entity_id", UUID.class),
              r.get("legal_entity_code", String.class),
              r.get("legal_entity_name", String.class),
              r.get("account_count", Long.class),
              money(r.get("total_balance", BigDecimal.class))));
    }

    var totals = new ArrayList<FinanceFundCurrencyBalanceTotal>();
    for (Record r : byCurrency) {
      var currency = r.get("currency", String.class);
      totals.add(
          new FinanceFundCurrencyBalanceTotal(
              currency,
              r.get("account_count", Long.class),
              r.get("active_count", Long.class),
              money(r.get("total_balance", BigDecimal.class)),
              List.copyOf(entityTotals.getOrDefault(currency, List.of()))));
    }

    return new FinanceFundCurrencyBalanceSummary(
        "余额汇总**只到币种级**：USD 一行、EUR 一行，跨币种绝不相加。"
            + "本模块**不做**本币折算（汇率归 catalog 5.2.2.1、敞口归 12.7.2.1），"
            + "因此「全部账户加在一起」这个数没有意义，crossCurrencyTotal 恒为 null。"
            + "币种内按**法人**分组：跨法人混用资金账户会导致收入归属错误，"
            + "所以法人维度必须看得见。停用账户的余额仍然计入合计（钱还在那儿），"
            + "启用账户数单列。",
        false,
        null,
        CONVERSION_OWNER,
        List.copyOf(totals));
  }

  // ================================================================ 启用 / 停用

  /** 停用账户（不再接受新的余额登记；<b>不做物理删除</b> —— 资金凭据删不得）。 */
  @Transactional
  public FinanceFundAccountRow deactivate(UUID id, AccessService.Access actor) {
    requireAccount(id);
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    sql.execute(
        "update finance_fund_account set status = 'INACTIVE', version = version + 1,"
            + " updated_at = ?::timestamptz where id = ? and tenant_id = ?",
        now,
        id,
        actor.tenantId());
    return accountRow(requireAccount(id));
  }

  /** 启用账户（停用之后可以重新启用）。 */
  @Transactional
  public FinanceFundAccountRow activate(UUID id, AccessService.Access actor) {
    requireAccount(id);
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    sql.execute(
        "update finance_fund_account set status = 'ACTIVE', version = version + 1,"
            + " updated_at = ?::timestamptz where id = ? and tenant_id = ?",
        now,
        id,
        actor.tenantId());
    return accountRow(requireAccount(id));
  }

  // ================================================================ 内部工具

  /** 取一行账户档案（不存在 ⇒ 404）。RLS 保证只看得到本租户的行。 */
  private Record requireAccount(UUID id) {
    Record row =
        sql.fetchOne(
            "select id,account_no,account_name,account_type,currency,legal_entity_id,"
                + " legal_entity_code,legal_entity_name,bank_name,account_number_masked,"
                + " account_number_tail,account_holder,status,current_balance,current_balance_at,"
                + " balance_record_count,note,version,created_at,updated_at"
                + " from finance_fund_account where id = ?",
            id);
    if (row == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "资金账户不存在：" + id);
    }
    return row;
  }

  /** 取一行余额历史（不存在 ⇒ 404）。 */
  private Record requireBalance(UUID id) {
    Record row =
        sql.fetchOne(
            "select id,account_id,currency,legal_entity_id,legal_entity_code,legal_entity_name,"
                + " balance_amount,previous_amount,change_source,reason,source_system,"
                + " source_reference,operator_user_id,recorded_at,created_at"
                + " from finance_fund_account_balance where id = ?",
            id);
    if (row == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "余额历史不存在：" + id);
    }
    return row;
  }

  /**
   * 法人**实时校验**：读不到 ⇒ 503；读到了但没有这个法人 ⇒ 400。
   *
   * <p>🔴 <b>绝不</b>把「读不到」当成「校验通过」—— 那等于把
   * 「账户必须绑定法人」这条纪律架空虚化。
   */
  private FundAccountLegalEntityPort.LegalEntityRef requireLegalEntity(UUID id, String bearer) {
    var set = legalEntities.legalEntities(bearer);
    if (!set.available()) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "法人档案读不到（identity）："
              + set.note()
              + "。账户必须绑定法人，不接受未经校验的法人引用，请稍后重试");
    }
    return set.items().stream()
        .filter(e -> e.id().equals(id))
        .findFirst()
        .orElseThrow(
            () ->
                new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "法人 " + id + " 不存在或不属于当前租户：账户必须绑定法人，"
                        + "跨法人混用资金账户会导致收入归属错误"));
  }

  /** 账号脱敏：只留「前四位（够长时）+ **** + 后四位」，原始账号不落库。 */
  static String maskAccountNumber(String raw) {
    if (raw == null) return "";
    var s = raw.trim();
    if (s.isEmpty()) return "";
    if (s.length() < 8) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "账号至少 8 位才能脱敏存储：少于 8 位时后四位几乎等于完整账号，掩码没有意义");
    }
    var head = s.length() >= 12 ? s.substring(0, 4) : "";
    return head + "****" + s.substring(s.length() - 4);
  }

  /** 银行账户必须有开户行；非银行账户不许填开户行。 */
  private static void checkBankName(FinanceFundAccountType type, String bankName) {
    if (type == FinanceFundAccountType.BANK && bankName.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "银行账户必须填开户行");
    }
    if (type != FinanceFundAccountType.BANK && !bankName.isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, type.label() + "不填开户行（只有银行账户有开户行）");
    }
  }

  /** 系统生成的账户编号（用户没填编号时用）。 */
  private static String generatedAccountNo() {
    return ACCOUNT_NO_PREFIX
        + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(Locale.ROOT);
  }

  /** 账户档案行 → 传输模型。 */
  static FinanceFundAccountRow accountRow(Record r) {
    var type = FinanceFundAccountType.valueOf(r.get("account_type", String.class));
    var status = FinanceFundAccountStatus.valueOf(r.get("status", String.class));
    return new FinanceFundAccountRow(
        r.get("id", UUID.class),
        r.get("account_no", String.class),
        r.get("account_name", String.class),
        type.name(),
        type.label(),
        r.get("currency", String.class),
        r.get("legal_entity_id", UUID.class),
        r.get("legal_entity_code", String.class),
        r.get("legal_entity_name", String.class),
        r.get("bank_name", String.class),
        r.get("account_number_masked", String.class),
        r.get("account_number_tail", String.class),
        r.get("account_holder", String.class),
        status.name(),
        status.label(),
        money(r.get("current_balance", BigDecimal.class)),
        r.get("current_balance_at", OffsetDateTime.class),
        r.get("balance_record_count", Long.class),
        r.get("note", String.class),
        r.get("version", Long.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  /** 余额历史行 → 传输模型。 */
  static FinanceFundAccountBalanceRow balanceRow(Record r) {
    var source = FinanceFundBalanceSource.valueOf(r.get("change_source", String.class));
    return new FinanceFundAccountBalanceRow(
        r.get("id", UUID.class),
        r.get("account_id", UUID.class),
        r.get("currency", String.class),
        r.get("legal_entity_id", UUID.class),
        r.get("legal_entity_code", String.class),
        r.get("legal_entity_name", String.class),
        money(r.get("balance_amount", BigDecimal.class)),
        money(r.get("previous_amount", BigDecimal.class)),
        source.name(),
        source.label(),
        r.get("reason", String.class),
        r.get("source_system", String.class),
        r.get("source_reference", String.class),
        r.get("operator_user_id", UUID.class),
        r.get("recorded_at", OffsetDateTime.class),
        r.get("created_at", OffsetDateTime.class));
  }

  /** 金额统一到 4 位小数（BigDecimal；**禁浮点**）。 */
  static BigDecimal money(BigDecimal amount) {
    if (amount == null) return BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    return amount.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
  }

  private static String trim(String value) {
    return value == null ? "" : value.trim();
  }

  /** 沿 cause 链找一段文字（jOOQ 把 PG 的报错包了一层，真正的原因在 cause 里）。 */
  private static boolean messageChainContains(Throwable e, String needle) {
    for (Throwable cause = e; cause != null; cause = cause.getCause()) {
      var message = cause.getMessage();
      if (message != null && message.contains(needle)) return true;
      if (cause.getCause() == cause) break;
    }
    return false;
  }

  private static String rootMessage(Throwable e) {
    var message = e.getMessage();
    for (Throwable cause = e; cause != null; cause = cause.getCause()) {
      if (cause.getMessage() != null) message = cause.getMessage();
      if (cause.getCause() == cause) break;
    }
    return message == null ? "未知原因" : message;
  }

  /** 最近若干条余额历史（详情页用）。 */
  private FinanceFundAccountBalancePage balanceRows(UUID accountId, int page, int size) {
    var rows =
        sql.fetch(
            "select id,account_id,currency,legal_entity_id,legal_entity_code,legal_entity_name,"
                + " balance_amount,previous_amount,change_source,reason,source_system,"
                + " source_reference,operator_user_id,recorded_at,created_at"
                + " from finance_fund_account_balance where account_id = ?"
                + " order by recorded_at desc, id desc limit ? offset ?",
            accountId,
            size,
            page * size);
    return new FinanceFundAccountBalancePage(rows.map(FundAccountService::balanceRow), page, size, 0);
  }
}
