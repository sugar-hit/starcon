package com.starcon.trade;

import static com.starcon.trade.PurchaseOrderApprovalModels.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
 * 6.3.1.3 采购单审批（卷 06 采购与供应 / L3 6.3.1 采购单的第三格）。
 *
 * <p>蓝图 6.3.1.3 原文：「按金额阈值与采购员分级审批，支持批量审批、驳回并附意见，
 * 审批通过后锁定单据生成应付。」输入「待审批采购单；17 组织的审批规则（金额阈值、层级）」，
 * 输出「审批结果 + 审批链路记录；通过后触发 12 生成应付、8 允许收货」。
 *
 * <p><b>本模块与 6.3.1.1 / 6.3.1.2 的关系</b>：采购单这个聚合（头 + 行 + 留痕）归 6.3.1.1 所有；
 * 6.3.1.2 负责建单与提交，**只把单推到 {@code PENDING_APPROVAL}**，不冒充审批状态。
 * 本模块**不改那三张表的结构**，只做三件事：
 *
 * <ol>
 *   <li>把「按金额阈值 + 采购员分级 ⇒ 几级审批、几个人批」的规则**自己**承载下来
 *       （17 组织的审批规则服务不提供「采购员分级」这一维 ⇒ 规则表在 trade 侧）；</li>
 *   <li>在提交那一刻**冻结**审批链，产出审批结果与审批链路记录；</li>
 *   <li>通过后登记应付（trade 侧事实记录）+ 落收货放行事实，
 *       并由 DB 触发器钉死「审批后数量与单价不可改」。</li>
 * </ol>
 *
 * <p><b>六个最容易做错的口径，本类显式钉死：</b>
 *
 * <ol>
 *   <li><b>审批结论只能由本模块产生</b>：调用方传「我已审批」这种结论一律不接受 ——
 *       能不能批由「规则命中 + 审批人配置 + 权限」在服务端算出来。</li>
 *   <li><b>不能自审</b>：采购员不能审批自己提交的采购单。少了这一条，
 *       「按采购员分级审批」整套管控就等于没有。</li>
 *   <li><b>一个人在一张单上只批一次</b>：不能自己批两次凑够人数
 *       （DB 侧 {@code UNIQUE(tenant_id,case_id,approver_user_id)} 兜底）。</li>
 *   <li><b>登记额与生效额分两列</b>：应付登记额是审批通过时的金额，
 *       <b>生效额在财务确认之前恒为 0</b>（12 财务在本仓不存在 ⇒ 不假装钱已付出去）。</li>
 *   <li><b>不跨币种相加</b>：汇总里的金额按币种分列，绝不把 USD 与 CNY 加成一个数。</li>
 *   <li><b>「没配规则」不是「不用审」</b>：一条规则都没命中时走默认档（最低层级、1 人），
 *       审批这一环不能被悄悄跳过。</li>
 * </ol>
 *
 * <p><b>时间绑定</b>：{@code timestamptz} 一律 {@code ?::timestamptz} + {@link OffsetDateTime}，
 * 且时刻取自数据库（{@code select now()}）—— {@code Timestamp.valueOf(LocalDateTime)} 会静默偏 −28800s。
 *
 * <p><b>金额</b>：一律 BigDecimal + DB 的 numeric，禁浮点。金额列一律 numeric(19,4)。
 */
@Service
public class PurchaseOrderApprovalService {

  /** 读权限码（与 6.3.1.1 / 6.3.1.2 同一套，identity 零改动）。 */
  public static final String READ_PERMISSION = "trade.purchase.read";

  /** 写权限码（规则与审批人配置）。 */
  public static final String UPDATE_PERMISSION = "trade.purchase.update";

  /**
   * 审批权限码（**独立**的一条 —— 审批与编辑是两种不同的权力，
   * 照 11.2.3.3 的 {@code trade.aftersales.finance} 同款：谁能把单批成已批，是另一件事）。
   */
  public static final String APPROVE_PERMISSION = "trade.purchase.approve";

  /** 财务确认权限码（把登记的应付变成生效的应付）。 */
  public static final String FINANCE_PERMISSION = "trade.purchase.finance";

  /** 数据范围：全租户。 */
  public static final String SCOPE_ALL = "ALL";

  /** 数据范围：本部门。 */
  public static final String SCOPE_DEPARTMENT = "DEPARTMENT";

  /** 数据范围：本人负责。 */
  public static final String SCOPE_SELF = "SELF";

  /** 金额精度（与 11.2.3.3 的 supplier_claim_settlement 同一口径）。 */
  private static final int AMOUNT_SCALE = 4;

  /** 批量审批里每张单一个保存点（失败的那张回滚到它，不影响其它张）。 */
  private static final String SAVEPOINT = "purchase_order_approval_batch_item";

  /** 应付登记还没发生（视图状态；DB 里没有这一行）。 */
  private static final String PAYABLE_NONE = "NONE";

  /** 收货放行还没发生（视图状态；DB 里没有这一行）。 */
  private static final String RELEASE_NONE = "NONE";

  private final DSLContext sql;

  public PurchaseOrderApprovalService(DSLContext sql) {
    this.sql = sql;
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：逐条说清本模块**真的**能做什么、哪些维度归别的模块。
   *
   * <p>存在的理由与 6.3.1.1 / 6.3.1.2 一致：让界面能说清「为什么没有生效的应付金额」、
   * 「为什么审批规则要自己配」，而不是让它们显示成空白让人以为「这单没有这些值」。
   */
  public PurchaseOrderApprovalCapabilities capabilities() {
    return new PurchaseOrderApprovalCapabilities(
        List.of(
            new PurchaseOrderApprovalCapability(
                "amountThresholdApproval",
                "按金额阈值审批",
                true,
                "6.3.1.3 采购单审批",
                "规则表自己配（租户级）：（采购员分级, 币种, 金额区间）⇒ 要求的审批层级与人数；"
                    + "金额区间是半开区间 [下限, 上限)，相邻规则既不重叠也不留缝。"),
            new PurchaseOrderApprovalCapability(
                "buyerLevelApproval",
                "按采购员分级审批",
                true,
                "6.3.1.3 采购单审批",
                "采购员分级（初级/高级/采购经理）是**规则的一个维度** —— 分级不同的采购员，"
                    + "同一笔金额命中的是不同的档。⚠️ 17 组织的审批规则服务不提供这一维，"
                    + "所以分级表在 trade 侧（purchase_order_approval_buyer）。"),
            new PurchaseOrderApprovalCapability(
                "batchApproval",
                "批量审批 / 批量驳回",
                true,
                "6.3.1.3 采购单审批",
                "一次提交多个采购单，逐条给结论（成功条数 + 失败原因），"
                    + "**不静默丢弃**失败的那几张。"),
            new PurchaseOrderApprovalCapability(
                "rejectWithReason",
                "驳回并附意见",
                true,
                "6.3.1.3 采购单审批",
                "驳回**必须**附意见（「驳回而不说为什么」等于没驳回）；"
                    + "意见进审批链路记录，事后可追溯。"),
            new PurchaseOrderApprovalCapability(
                "approvalChain",
                "审批链路记录",
                true,
                "6.3.1.3 采购单审批",
                "每一步记：谁批的、以什么层级、按哪条规则、批当时的金额与币种、意见是什么。"
                    + "记录**追加式**（运行角色 UPDATE/DELETE 已收回）。"),
            new PurchaseOrderApprovalCapability(
                "lockAfterApproval",
                "审批后锁定单据",
                true,
                "6.3.1.3 采购单审批",
                "DB 触发器钉死：提交之后（PENDING_APPROVAL 及以后）采购单行的**数量与单价不可改** ——"
                    + "如需变更，走变更单或红冲重下，避免已生成应付与实际收货对不上。"),
            new PurchaseOrderApprovalCapability(
                "payableRegistration",
                "生成应付（登记）",
                true,
                "6.3.1.3 采购单审批",
                "审批通过即登记应付：**登记额**是审批通过时的金额，**生效额恒为 0** ——"
                    + "12 财务（finance 服务）在本仓不存在 ⇒ 只登记事实，不假装钱已付出去。"),
            new PurchaseOrderApprovalCapability(
                "receiptRelease",
                "允许收货（放行事实）",
                true,
                "6.3.1.3 采购单审批",
                "审批通过即落一条**收货放行事实**。⚠️ 08 仓储在独立库 ⇒ 本模块不改 warehouse 的表、"
                    + "不建跨库 FK；收货资格的真正放行由 warehouse 侧经授权 API / event 衔接。"),
            new PurchaseOrderApprovalCapability(
                "financeConfirmation",
                "财务确认（生效应付）",
                true,
                "6.3.1.3 采购单审批",
                "登记的应付可被**显式确认**（需独立权限码 trade.purchase.finance）："
                    + "确认 ⇒ 生效额 = 登记额且确认人与财务单据号齐全；未确认 ⇒ 生效额恒为 0。"),
            new PurchaseOrderApprovalCapability(
                "identityApproval",
                "17 组织的通用审批流",
                false,
                "17 组织（identity 服务）",
                "本模块**不**把审批委托给 identity 的通用审批流：identity 的规则维度里"
                    + "没有「采购员分级」，也没有「金额授权区间」；要表达就得改 identity 的规则模型 —— "
                    + "那是另一个服务的写入所有权。⇒ 本模块自持规则，边界清楚、判定唯一。"),
            new PurchaseOrderApprovalCapability(
                "financeService",
                "12 财务的应付单据与税额",
                false,
                "12 财务（finance 服务当前不存在）",
                "本仓 backend/services/ 下没有 finance 服务 ⇒ 本模块**不造税率表**、"
                    + "不跨库读 finance、不建跨服务 FK 或视图。应付的最终对账留待 finance 落地后"
                    + "经授权 API / event 衔接。"),
            new PurchaseOrderApprovalCapability(
                "warehouseReceipt",
                "08 仓储的实际收货",
                false,
                "08 仓储作业（warehouse）",
                "warehouse 在独立库 ⇒ 本模块只落**放行事实**，不代写收货单、不改库存。"),
            new PurchaseOrderApprovalCapability(
                "changeOrder",
                "变更单 / 红冲重下",
                false,
                "6.3.1 采购单（未在本模块内交付）",
                "审批通过的采购单要改数量与单价 ⇒ 走变更单或红冲重下。"
                    + "本模块**只锁不改**，不代写变更单（避免同一张单有两份金额真相）。")),
        "本模块的能力声明按「职责句」逐块列出：能做的标 true 并写清前提，"
            + "未交付的下游标 false 并点名归属模块与原因。");
  }

  // ================================================================ 规则

  /** 规则列表（租户级配置，数量有限 ⇒ 一次返回全部，不假装分页）。 */
  public PurchaseOrderApprovalRuleList rules() {
    List<PurchaseOrderApprovalRuleView> items = new ArrayList<>();
    for (Record r :
        sql.fetch(
            "select * from purchase_order_approval_rule order by priority asc, created_at asc, id asc")) {
      items.add(ruleView(r));
    }
    return new PurchaseOrderApprovalRuleList(
        items,
        items.size(),
        "规则是租户级配置，数量有限，一次返回全部；命中的那一条由审批单在提交那一刻冻结。");
  }

  /** 新建规则。 */
  public PurchaseOrderApprovalRuleView createRule(
      PurchaseOrderApprovalRuleInput input, UUID actorId) {
    if (input == null) {
      throw badRequest("规则内容不能为空");
    }
    String ruleNo = requireText(input.ruleNo(), "规则号不能为空");
    String ruleName = requireText(input.ruleName(), "规则名称不能为空");
    String buyerLevel = normalizeBuyerLevel(input.buyerLevel());
    String currency = normalizeCurrency(input.currency());
    BigDecimal min = amount(input.minAmount(), "金额下限");
    BigDecimal max = amount(input.maxAmount(), "金额上限");
    if (max != null && max.compareTo(min) <= 0) {
      throw badRequest("金额上限必须大于下限（区间是半开的 [下限, 上限)）");
    }
    String tier = normalizeTier(input.requiredTier());
    int approvals = input.requiredApprovals() == null ? 1 : input.requiredApprovals();
    if (approvals < 1 || approvals > 5) {
      throw badRequest("审批人数必须在 1 到 5 之间");
    }
    int priority = input.priority() == null ? 100 : input.priority();

    UUID id = UUID.randomUUID();
    try {
      sql.execute(
          "insert into purchase_order_approval_rule(id,tenant_id,rule_no,rule_name,buyer_level,currency,"
              + " min_amount,max_amount,required_tier,required_approvals,priority,status,note,"
              + " created_by,created_at,updated_at,version)"
              + " values (?::uuid,?::uuid,?::varchar,?::varchar,?::varchar,?::varchar,"
              + " ?::numeric,?::numeric,?::varchar,?::integer,?::integer,'ACTIVE',?::varchar,"
              + " ?::uuid,?::timestamptz,?::timestamptz,0)",
          id,
          currentTenant(),
          ruleNo,
          ruleName,
          buyerLevel,
          currency,
          min,
          max,
          tier,
          approvals,
          priority,
          nz(input.note()),
          actorId,
          dbNow(),
          dbNow());
    } catch (org.jooq.exception.DataAccessException e) {
      if (String.valueOf(e.getMessage()).contains("purchase_order_approval_rule_tenant_id_rule_no_key")) {
        throw conflict("规则号「" + ruleNo + "」在本租户已存在");
      }
      throw e;
    }
    return ruleView(requireRule(id));
  }

  /** 改规则（乐观锁）。规则号建后不可改 —— 改了会让历史审批单里的「按哪条规则批的」对不上。 */
  public PurchaseOrderApprovalRuleView updateRule(
      UUID id, PurchaseOrderApprovalRuleInput input, UUID actorId) {
    Record current = requireRule(id);
    if (input == null) {
      throw badRequest("规则内容不能为空");
    }
    if (input.version() == null) {
      throw badRequest("改规则必须带 version（乐观锁）");
    }
    if (!nz(input.ruleNo()).isBlank() && !input.ruleNo().equals(current.get("rule_no", String.class))) {
      throw badRequest("规则号建后不可改（改了会让历史审批单对不上依据的规则）");
    }
    String ruleName = requireText(input.ruleName(), "规则名称不能为空");
    String buyerLevel = normalizeBuyerLevel(input.buyerLevel());
    String currency = normalizeCurrency(input.currency());
    BigDecimal min = amount(input.minAmount(), "金额下限");
    BigDecimal max = amount(input.maxAmount(), "金额上限");
    if (max != null && max.compareTo(min) <= 0) {
      throw badRequest("金额上限必须大于下限（区间是半开的 [下限, 上限)）");
    }
    String tier = normalizeTier(input.requiredTier());
    int approvals = input.requiredApprovals() == null ? 1 : input.requiredApprovals();
    if (approvals < 1 || approvals > 5) {
      throw badRequest("审批人数必须在 1 到 5 之间");
    }
    int priority = input.priority() == null ? 100 : input.priority();

    int updated =
        sql.execute(
            "update purchase_order_approval_rule set rule_name=?::varchar,buyer_level=?::varchar,"
                + " currency=?::varchar,min_amount=?::numeric,max_amount=?::numeric,"
                + " required_tier=?::varchar,required_approvals=?::integer,priority=?::integer,"
                + " note=?::varchar,updated_at=?::timestamptz,version=version+1"
                + " where id=?::uuid and version=?",
            ruleName,
            buyerLevel,
            currency,
            min,
            max,
            tier,
            approvals,
            priority,
            nz(input.note()),
            dbNow(),
            id,
            input.version());
    if (updated == 0) {
      throw conflict("规则已被别人改过，请刷新后重试");
    }
    return ruleView(requireRule(id));
  }

  /** 启用 / 停用规则。 */
  public PurchaseOrderApprovalRuleView setRuleStatus(
      UUID id, String status, Long version, UUID actorId) {
    requireRule(id);
    if (version == null) {
      throw badRequest("启用 / 停用必须带 version（乐观锁）");
    }
    String target = "ACTIVE".equals(status) ? "ACTIVE" : "INACTIVE";
    int updated =
        sql.execute(
            "update purchase_order_approval_rule set status=?::varchar,updated_at=?::timestamptz,"
                + " version=version+1 where id=?::uuid and version=?",
            target,
            dbNow(),
            id,
            version);
    if (updated == 0) {
      throw conflict("规则已被别人改过，请刷新后重试");
    }
    return ruleView(requireRule(id));
  }

  // ================================================================ 审批成员

  /** 采购员分级 + 审批人/层级配置（一次返回两张表）。 */
  public PurchaseOrderApprovalMembers members() {
    List<PurchaseOrderApprovalBuyerView> buyers = new ArrayList<>();
    for (Record r :
        sql.fetch(
            "select * from purchase_order_approval_buyer order by buyer_level asc, user_name asc, id asc")) {
      buyers.add(buyerView(r));
    }
    List<PurchaseOrderApprovalApproverView> approvers = new ArrayList<>();
    for (Record r :
        sql.fetch(
            "select * from purchase_order_approval_approver order by approval_tier asc, user_name asc, id asc")) {
      approvers.add(approverView(r));
    }
    return new PurchaseOrderApprovalMembers(
        buyers,
        approvers,
        "采购员分级决定「这张单命中哪一档规则」，审批人配置决定「谁能批这一笔」—— 两张表各管一件事。");
  }

  /** 登记 / 改一个采购员的分级（同一个人在本租户只有一个分级）。 */
  public PurchaseOrderApprovalBuyerView upsertBuyer(
      PurchaseOrderApprovalBuyerInput input, UUID actorId) {
    if (input == null || input.userId() == null) {
      throw badRequest("必须指定采购员");
    }
    String level = normalizeBuyerLevelStrict(input.buyerLevel());
    String status = "INACTIVE".equals(input.status()) ? "INACTIVE" : "ACTIVE";
    OffsetDateTime now = dbNow();
    int updated =
        sql.execute(
            "update purchase_order_approval_buyer set buyer_level=?::varchar,user_name=?::varchar,"
                + " status=?::varchar,note=?::varchar,updated_at=?::timestamptz,version=version+1"
                + " where tenant_id=?::uuid and user_id=?::uuid",
            level,
            nz(input.userName()),
            status,
            nz(input.note()),
            now,
            currentTenant(),
            input.userId());
    if (updated == 0) {
      sql.execute(
          "insert into purchase_order_approval_buyer(id,tenant_id,user_id,user_name,buyer_level,status,"
              + " note,created_by,created_at,updated_at,version)"
              + " values (?::uuid,?::uuid,?::uuid,?::varchar,?::varchar,?::varchar,?::varchar,"
              + " ?::uuid,?::timestamptz,?::timestamptz,0)",
          UUID.randomUUID(),
          currentTenant(),
          input.userId(),
          nz(input.userName()),
          level,
          status,
          nz(input.note()),
          actorId,
          now,
          now);
    }
    return buyerView(
        sql.fetchOne(
            "select * from purchase_order_approval_buyer where tenant_id=?::uuid and user_id=?::uuid",
            currentTenant(),
            input.userId()));
  }

  /** 登记 / 改一个审批人（同一个人同一层级只有一条；不同层级可以有多条）。 */
  public PurchaseOrderApprovalApproverView upsertApprover(
      PurchaseOrderApprovalApproverInput input, UUID actorId) {
    if (input == null || input.userId() == null) {
      throw badRequest("必须指定审批人");
    }
    String tier = normalizeTier(input.approvalTier());
    String currency = normalizeCurrency(input.currency());
    BigDecimal min = amount(input.minAmount(), "授权下限");
    BigDecimal max = amount(input.maxAmount(), "授权上限");
    if (max != null && max.compareTo(min) <= 0) {
      throw badRequest("授权上限必须大于下限（区间是半开的 [下限, 上限)）");
    }
    String status = "INACTIVE".equals(input.status()) ? "INACTIVE" : "ACTIVE";
    OffsetDateTime now = dbNow();
    int updated =
        sql.execute(
            "update purchase_order_approval_approver set user_name=?::varchar,currency=?::varchar,"
                + " min_amount=?::numeric,max_amount=?::numeric,status=?::varchar,note=?::varchar,"
                + " updated_at=?::timestamptz,version=version+1"
                + " where tenant_id=?::uuid and user_id=?::uuid and approval_tier=?::varchar",
            nz(input.userName()),
            currency,
            min,
            max,
            status,
            nz(input.note()),
            now,
            currentTenant(),
            input.userId(),
            tier);
    if (updated == 0) {
      sql.execute(
          "insert into purchase_order_approval_approver(id,tenant_id,user_id,user_name,approval_tier,"
              + " currency,min_amount,max_amount,status,note,created_by,created_at,updated_at,version)"
              + " values (?::uuid,?::uuid,?::uuid,?::varchar,?::varchar,?::varchar,?::numeric,"
              + " ?::numeric,?::varchar,?::varchar,?::uuid,?::timestamptz,?::timestamptz,0)",
          UUID.randomUUID(),
          currentTenant(),
          input.userId(),
          nz(input.userName()),
          tier,
          currency,
          min,
          max,
          status,
          nz(input.note()),
          actorId,
          now,
          now);
    }
    return approverView(
        sql.fetchOne(
            "select * from purchase_order_approval_approver where tenant_id=?::uuid and user_id=?::uuid"
                + " and approval_tier=?::varchar",
            currentTenant(),
            input.userId(),
            tier));
  }

  // ================================================================ 审批台账

  /**
   * 审批台账（真分页 + 筛选 + 汇总）。
   *
   * @param caseStatus 审批单状态（PENDING / APPROVED / REJECTED）；空 = 全部
   * @param tier 要求的审批层级（L1 / L2 / L3）
   * @param currency 币种
   * @param supplierCode 供应商编码（模糊）
   * @param keyword 关键字：采购单号 / 供应商编码 / 供应商名（模糊）
   */
  public PurchaseOrderApprovalPage list(
      String caseStatus,
      String tier,
      String currency,
      String supplierCode,
      String keyword,
      int page,
      int size,
      UUID actorId,
      UUID actorDepartmentId,
      String scope) {
    int pageNo = Math.max(page, 0);
    int pageSize = size <= 0 ? 20 : Math.min(size, 200);

    StringBuilder from =
        new StringBuilder(
            " from purchase_order o join purchase_order_approval_case c"
                + " on c.tenant_id = o.tenant_id and c.order_id = o.id where 1=1");
    List<Object> binds = new ArrayList<>();

    if (isSet(caseStatus)) {
      from.append(" and c.status = ?::varchar");
      binds.add(caseStatus.trim().toUpperCase());
    }
    if (isSet(tier)) {
      from.append(" and c.required_tier = ?::varchar");
      binds.add(tier.trim().toUpperCase());
    }
    if (isSet(currency)) {
      from.append(" and o.currency = ?::varchar");
      binds.add(currency.trim().toUpperCase());
    }
    if (isSet(supplierCode)) {
      from.append(" and o.supplier_code ilike ?::varchar");
      binds.add("%" + supplierCode.trim() + "%");
    }
    if (isSet(keyword)) {
      // 真实模糊检索：采购单号、供应商编码、供应商名。
      // ⚠️ 刻意**不含**金额的模糊匹配 —— 那会让「搜 100」返回一堆无关单。
      String pattern = "%" + keyword.trim() + "%";
      from.append(
          " and (o.order_no ilike ?::varchar or o.supplier_code ilike ?::varchar"
              + " or o.supplier_name ilike ?::varchar)");
      binds.add(pattern);
      binds.add(pattern);
      binds.add(pattern);
    }
    // 数据范围：ALL / DEPARTMENT / SELF。**不接受请求参数传入**。
    appendScope(from, binds, scope, actorId, actorDepartmentId);

    Record head =
        sql.fetchOne("select count(*) as total" + from, binds.toArray());
    long total = head.get("total", Long.class);

    // ⚠️ 分页参数必须**并进同一个绑定数组**（与 6.3.1.1 同一写法）：
    //    把数组和分页参数分成两个 varargs 传，jOOQ 会把数组当成一个元素，
    //    LIMIT 拿到错误的类型 ⇒ 直接语法错（而不是悄悄少查几行）。
    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(pageSize);
    pageBinds.add((long) pageNo * pageSize);

    List<Record> rows =
        sql.fetch(
            "select o.id as order_id,o.order_no,o.status as order_status,o.supplier_code,o.supplier_name,"
                + " o.currency,o.total_amount,o.owner_user_id,c.id as case_id,c.buyer_user_id,"
                + " c.buyer_level,c.required_tier,c.required_approvals,c.approved_count,"
                + " c.status as case_status,c.rule_name,c.order_amount,c.currency as case_currency"
                + from
                + " order by o.created_at desc, o.id desc limit ? offset ?",
            pageBinds.toArray());

    List<PurchaseOrderApprovalOrderRow> items = new ArrayList<>();
    for (Record r : rows) {
      items.add(orderRow(r, actorId));
    }

    return new PurchaseOrderApprovalPage(
        items, total, pageNo, pageSize, summary(from.toString(), binds));
  }

  /**
   * 汇总：三种状态的单数 + <b>按币种分列</b>的待审金额。
   *
   * <p>统计的是**命中筛选条件的全部行**（与分页无关），而不是当前页。
   * ⚠️ 金额**绝不跨币种相加** —— 把 USD 与 CNY 加成一个数看起来能对账，其实对不上。
   */
  private PurchaseOrderApprovalSummary summary(String from, List<Object> binds) {
    Record counts =
        sql.fetchOne(
            "select count(*) filter (where c.status = 'PENDING') as pending,"
                + " count(*) filter (where c.status = 'APPROVED') as approved,"
                + " count(*) filter (where c.status = 'REJECTED') as rejected"
                + from,
            binds.toArray());
    List<PurchaseOrderApprovalAmountByCurrency> amounts = new ArrayList<>();
    for (Record r :
        sql.fetch(
            "select o.currency, sum(c.order_amount) as amount, count(*) as cnt"
                + from
                + " and c.status = 'PENDING' group by o.currency order by o.currency",
            binds.toArray())) {
      amounts.add(
          new PurchaseOrderApprovalAmountByCurrency(
              r.get("currency", String.class), r.get("amount", BigDecimal.class), r.get("cnt", Long.class)));
    }
    return new PurchaseOrderApprovalSummary(
        counts.get("pending", Long.class),
        counts.get("approved", Long.class),
        counts.get("rejected", Long.class),
        amounts);
  }

  // ================================================================ 详情

  /** 审批详情（单 + 审批链 + 采购单留痕 + 应付登记 + 收货放行 + 当前可执行动作）。 */
  public PurchaseOrderApprovalDetail detail(
      UUID orderId, UUID actorId, UUID actorDepartmentId, String scope) {
    Record order = requireOrder(orderId, actorId, actorDepartmentId, scope);
    Record c = ensureCase(orderId);

    List<PurchaseOrderApprovalChainRow> chain = new ArrayList<>();
    for (Record r :
        sql.fetch(
            "select * from purchase_order_approval_record where case_id=?::uuid order by seq asc",
            c.get("id", UUID.class))) {
      chain.add(
          new PurchaseOrderApprovalChainRow(
              r.get("seq", Integer.class),
              r.get("action", String.class),
              r.get("approval_tier", String.class),
              r.get("approver_user_id", UUID.class),
              nz(r.get("approver_name", String.class)),
              r.get("order_amount", BigDecimal.class),
              r.get("currency", String.class),
              nz(r.get("rule_name", String.class)),
              nz(r.get("buyer_level", String.class)),
              nz(r.get("note", String.class)),
              r.get("occurred_at", OffsetDateTime.class)));
    }

    List<PurchaseOrderApprovalEventRow> events = new ArrayList<>();
    for (Record r :
        sql.fetch(
            "select * from purchase_order_event where order_id=?::uuid order by occurred_at asc, id asc",
            orderId)) {
      events.add(
          new PurchaseOrderApprovalEventRow(
              r.get("event_type", String.class),
              r.get("from_status", String.class),
              r.get("to_status", String.class),
              nz(r.get("note", String.class)),
              r.get("actor_id", UUID.class),
              r.get("occurred_at", OffsetDateTime.class)));
    }

    PurchaseOrderApprovalPayableView payable = payableOf(orderId);
    PurchaseOrderApprovalReceiptReleaseView release = receiptReleaseOf(orderId);

    String blocked =
        blockedReason(
            c.get("id", UUID.class),
            c.get("buyer_user_id", UUID.class),
            nz(c.get("required_tier", String.class)),
            c.get("order_amount", BigDecimal.class),
            nz(c.get("currency", String.class)),
            actorId);
    boolean pending =
        "PENDING".equals(c.get("status", String.class))
            && "PENDING_APPROVAL".equals(order.get("status", String.class));

    return new PurchaseOrderApprovalDetail(
        orderId,
        nz(order.get("order_no", String.class)),
        nz(order.get("status", String.class)),
        nz(order.get("supplier_code", String.class)),
        nz(order.get("supplier_name", String.class)),
        nz(order.get("currency", String.class)),
        c.get("order_amount", BigDecimal.class),
        c.get("buyer_user_id", UUID.class),
        buyerName(c.get("buyer_user_id", UUID.class)),
        nz(c.get("buyer_level", String.class)),
        nz(c.get("status", String.class)),
        nz(c.get("rule_name", String.class)),
        nz(c.get("rule_match", String.class)),
        nz(c.get("required_tier", String.class)),
        c.get("required_approvals", Integer.class),
        c.get("approved_count", Integer.class),
        chain,
        events,
        payable,
        release,
        pending && blocked == null,
        pending && blocked == null,
        blocked);
  }

  // ================================================================ 审批动作

  /**
   * 审批通过。
   *
   * <p>🔴 一次审批走完：落链路留痕 → 推进审批单 →（凑齐人数时）推进采购单状态 →
   * 登记应付 + 落收货放行事实 + 写采购单留痕。全部在**同一个事务**里，
   * 中途失败整笔回滚 —— 不会出现「单子已批但应付没登记」的半截状态。
   */
  public PurchaseOrderApprovalDetail approve(
      UUID orderId,
      PurchaseOrderApprovalDecisionRequest request,
      UUID actorId,
      UUID actorDepartmentId,
      String scope) {
    return decide(orderId, request, actorId, actorDepartmentId, scope, true);
  }

  /**
   * 驳回（<b>意见必填</b>）。
   *
   * <p>驳回后采购单进入 {@code REJECTED}（DB 状态机钉死的既有跃迁），
   * 剩余审批不再需要（审批单同时置为 REJECTED 并记下驳回人与时刻）。
   */
  public PurchaseOrderApprovalDetail reject(
      UUID orderId,
      PurchaseOrderApprovalDecisionRequest request,
      UUID actorId,
      UUID actorDepartmentId,
      String scope) {
    String note = request == null ? "" : nz(request.note()).trim();
    if (note.isEmpty()) {
      throw badRequest("驳回必须附意见（「驳回而不说为什么」等于没驳回）");
    }
    return decide(orderId, request, actorId, actorDepartmentId, scope, false);
  }

  /**
   * 批量审批 / 批量驳回。
   *
   * <p>🔴 **逐条给结论**：一张批不过的不影响其它张；失败原因逐条返回，
   * **绝不**静默丢弃（「丢掉的采购单 = 没人看的采购单」）。
   */
  public PurchaseOrderApprovalBatchResult batch(
      PurchaseOrderApprovalBatchRequest request,
      UUID actorId,
      UUID actorDepartmentId,
      String scope) {
    if (request == null || request.orderIds() == null || request.orderIds().isEmpty()) {
      throw badRequest("批量审批至少要选中一张采购单");
    }
    String action = nz(request.action()).trim().toUpperCase();
    if (!"APPROVE".equals(action) && !"REJECT".equals(action)) {
      throw badRequest("批量动作只能是 APPROVE 或 REJECT");
    }
    boolean approve = "APPROVE".equals(action);
    String note = nz(request.note()).trim();
    if (!approve && note.isEmpty()) {
      throw badRequest("批量驳回必须附意见");
    }

    List<PurchaseOrderApprovalBatchItem> items = new ArrayList<>();
    int ok = 0;
    for (UUID orderId : request.orderIds()) {
      String orderNo = "";
      // 🔴 **保存点**：一张失败不能把它的半截写入留在事务里 ——
      //    否则「审批留痕已写、审批单没推进」这种不一致会被提交进库。
      //    回滚到保存点 = 这一张单相当于没发生过，其它张不受影响。
      sql.execute(sql.savepoint(SAVEPOINT));
      try {
        Record order = sql.fetchOne("select order_no from purchase_order where id=?::uuid", orderId);
        orderNo = order == null ? "" : nz(order.get("order_no", String.class));
        decide(
            orderId,
            new PurchaseOrderApprovalDecisionRequest(note),
            actorId,
            actorDepartmentId,
            scope,
            approve);
        items.add(new PurchaseOrderApprovalBatchItem(orderId, orderNo, true, "已处理"));
        ok++;
      } catch (RuntimeException e) {
        // ⚠️ 一张失败**不打断**整批：逐条记录原因，让调用方知道哪几张没成、为什么。
        items.add(
            new PurchaseOrderApprovalBatchItem(orderId, orderNo, false, failureMessage(e)));
        rollbackTo(SAVEPOINT);
      } finally {
        releaseQuietly(SAVEPOINT);
      }
    }
    return new PurchaseOrderApprovalBatchResult(
        items,
        ok,
        items.size() - ok,
        approve ? "批量审批" : "批量驳回");
  }

  /**
   * 审批 / 驳回的共同实现。
   *
   * @param approve true = 通过；false = 驳回
   */
  private PurchaseOrderApprovalDetail decide(
      UUID orderId,
      PurchaseOrderApprovalDecisionRequest request,
      UUID actorId,
      UUID actorDepartmentId,
      String scope,
      boolean approve) {
    Record order = requireOrder(orderId, actorId, actorDepartmentId, scope);
    String orderStatus = nz(order.get("status", String.class));
    if (!"PENDING_APPROVAL".equals(orderStatus)) {
      throw conflict("这张采购单当前是「" + orderStatusLabel(orderStatus) + "」，不在待审批状态");
    }

    Record c = ensureCase(orderId);
    String caseStatus = nz(c.get("status", String.class));
    if (!"PENDING".equals(caseStatus)) {
      throw conflict(
          "这张采购单的审批已经"
              + ("APPROVED".equals(caseStatus) ? "通过" : "驳回")
              + "，不能重复审批");
    }

    String requiredTier = nz(c.get("required_tier", String.class));
    BigDecimal amount = c.get("order_amount", BigDecimal.class);
    String currency = nz(c.get("currency", String.class));

    String blocked =
        blockedReason(
            c.get("id", UUID.class), c.get("buyer_user_id", UUID.class), requiredTier, amount, currency, actorId);
    if (blocked != null) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, blocked);
    }

    Record approver = chosenApprover(requiredTier, amount, currency, actorId);
    String tier = nz(approver.get("approval_tier", String.class));
    String note = request == null ? "" : nz(request.note()).trim();
    if (!approve && note.isEmpty()) {
      throw badRequest("驳回必须附意见（「驳回而不说为什么」等于没驳回）");
    }

    UUID caseId = c.get("id", UUID.class);
    UUID tenantId = currentTenant();
    OffsetDateTime now = dbNow();

    int seq =
        sql.fetchOne(
                "select count(*) from purchase_order_approval_record where case_id=?::uuid", caseId)
            .get(0, Long.class)
            .intValue()
            + 1;

    // ---- 审批链路留痕（追加式）----
    sql.execute(
        "insert into purchase_order_approval_record(id,tenant_id,order_id,case_id,seq,action,"
            + " approval_tier,approver_user_id,approver_name,order_amount,currency,rule_id,rule_name,"
            + " buyer_level,note,occurred_at,created_at)"
            + " values (?::uuid,?::uuid,?::uuid,?::uuid,?::integer,?::varchar,?::varchar,?::uuid,"
            + " ?::varchar,?::numeric,?::varchar,?::uuid,?::varchar,?::varchar,?::varchar,"
            + " ?::timestamptz,?::timestamptz)",
        UUID.randomUUID(),
        tenantId,
        orderId,
        caseId,
        seq,
        approve ? "APPROVED" : "REJECTED",
        tier,
        actorId,
        nz(approver.get("user_name", String.class)),
        amount,
        currency,
        c.get("rule_id", UUID.class),
        nz(c.get("rule_name", String.class)),
        nz(c.get("buyer_level", String.class)),
        note,
        now,
        now);

    // ---- 推进审批单（乐观锁：两个人同时批 ⇒ 后到的那个拿到 409 而不是覆盖）----
    long caseVersion = c.get("version", Long.class);
    if (approve) {
      int updated =
          sql.execute(
              "update purchase_order_approval_case set approved_count=approved_count+1,"
                  + " status=case when approved_count+1 >= required_approvals then 'APPROVED'"
                  + " else 'PENDING' end,updated_at=?::timestamptz,version=version+1"
                  + " where id=?::uuid and version=? and status='PENDING'",
              now,
              caseId,
              caseVersion);
      if (updated == 0) {
        throw conflict("有人刚刚也批了这张采购单，请刷新后重试");
      }
      if ("APPROVED".equals(caseStatusOf(caseId))) {
        completeApproval(order, c, actorId, now, note);
      }
    } else {
      int updated =
          sql.execute(
              "update purchase_order_approval_case set status='REJECTED',rejected_by=?::uuid,"
                  + " rejected_at=?::timestamptz,reject_reason=?::varchar,updated_at=?::timestamptz,"
                  + " version=version+1 where id=?::uuid and version=? and status='PENDING'",
              actorId,
              now,
              note,
              now,
              caseId,
              caseVersion);
      if (updated == 0) {
        throw conflict("有人刚刚也处理了这张采购单，请刷新后重试");
      }
      completeRejection(order, actorId, now, note);
    }

    return detail(orderId, actorId, actorDepartmentId, scope);
  }

  /**
   * 审批通过的最后一步：推进采购单状态 + 登记应付 + 落收货放行事实 + 写采购单留痕。
   *
   * <p>⚠️ 采购单的状态跃迁走的是 V254 的既有状态机（{@code PENDING_APPROVAL → APPROVED}），
   * 本模块**不发明新跃迁、也不改那个触发器**。
   */
  private void completeApproval(
      Record order, Record c, UUID actorId, OffsetDateTime now, String note) {
    UUID orderId = order.get("id", UUID.class);

    // ---- 应付登记（12 财务不存在 ⇒ trade 侧自有事实记录；生效额恒为 0）----
    // ⚠️ 金额为 0 的采购单登记不出应付 —— 但**不静默跳过**：先把原因抛出来，
    //    由调用方去核对行金额（这一单不该被当成「已批但没有应付」）。
    BigDecimal registered =
        c.get("order_amount", BigDecimal.class).setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
    if (registered.compareTo(BigDecimal.ZERO) <= 0) {
      throw conflict("这张采购单的金额是 0，登记不出应付；请先核对采购单的行金额");
    }

    int updated =
        sql.execute(
            "update purchase_order set status='APPROVED',updated_at=?::timestamptz,version=version+1"
                + " where id=?::uuid and status='PENDING_APPROVAL'",
            now,
            orderId);
    if (updated == 0) {
      throw conflict("这张采购单的状态已被别人改动，请刷新后重试");
    }

    sql.execute(
        "insert into purchase_order_payable(id,tenant_id,order_id,payable_no,currency,"
            + " registered_amount,effective_amount,status,created_by,created_at,updated_at,version)"
            + " values (?::uuid,?::uuid,?::uuid,?::varchar,?::varchar,?::numeric,0,"
            + " 'PENDING_FINANCE_CONFIRM',?::uuid,?::timestamptz,?::timestamptz,0)"
            + " on conflict (tenant_id,order_id) do nothing",
        UUID.randomUUID(),
        currentTenant(),
        orderId,
        "PAP-" + sql.fetchOne("select nextval('purchase_order_payable_no_seq')").get(0, Long.class),
        nz(c.get("currency", String.class)),
        registered,
        actorId,
        now,
        now);

    // ---- 收货放行事实（08 仓储在独立库 ⇒ 只落事实，不代写收货）----
    sql.execute(
        "insert into purchase_order_receipt_release(id,tenant_id,order_id,status,released_by,"
            + " released_at,release_note,created_at,updated_at,version)"
            + " values (?::uuid,?::uuid,?::uuid,'RELEASED',?::uuid,?::timestamptz,?::varchar,"
            + " ?::timestamptz,?::timestamptz,0) on conflict (tenant_id,order_id) do nothing",
        UUID.randomUUID(),
        currentTenant(),
        orderId,
        actorId,
        now,
        "审批通过，允许收货（08 仓储侧经授权 API / event 衔接实际收货）",
        now,
        now);

    appendOrderEvent(orderId, "PENDING_APPROVAL", "APPROVED", note, actorId, now);
  }

  /** 驳回的最后一步：采购单进入 REJECTED + 写采购单留痕。 */
  private void completeRejection(
      Record order, UUID actorId, OffsetDateTime now, String note) {
    UUID orderId = order.get("id", UUID.class);
    int updated =
        sql.execute(
            "update purchase_order set status='REJECTED',updated_at=?::timestamptz,version=version+1"
                + " where id=?::uuid and status='PENDING_APPROVAL'",
            now,
            orderId);
    if (updated == 0) {
      throw conflict("这张采购单的状态已被别人改动，请刷新后重试");
    }
    appendOrderEvent(orderId, "PENDING_APPROVAL", "REJECTED", note, actorId, now);
  }

  /** 写 6.3.1.1 的采购单留痕（追加式；event_type 沿用既有枚举，不新增）。 */
  private void appendOrderEvent(
      UUID orderId, String from, String to, String note, UUID actorId, OffsetDateTime now) {
    sql.execute(
        "insert into purchase_order_event(id,tenant_id,order_id,event_type,from_status,to_status,"
            + " note,actor_id,occurred_at,created_at)"
            + " values (?::uuid,?::uuid,?::uuid,'STATUS_CHANGED',?::varchar,?::varchar,?::varchar,"
            + " ?::uuid,?::timestamptz,?::timestamptz)",
        UUID.randomUUID(),
        currentTenant(),
        orderId,
        from,
        to,
        nz(note),
        actorId,
        now,
        now);
  }

  // ================================================================ 应付台账

  /** 应付登记台账（真分页）。 */
  public PurchaseOrderApprovalPayablePage payables(String status, int page, int size) {
    int pageNo = Math.max(page, 0);
    int pageSize = size <= 0 ? 20 : Math.min(size, 200);
    StringBuilder from = new StringBuilder(" from purchase_order_payable where 1=1");
    List<Object> binds = new ArrayList<>();
    if (isSet(status)) {
      from.append(" and status = ?::varchar");
      binds.add(status.trim().toUpperCase());
    }
    long total = sql.fetchOne("select count(*) as total" + from, binds.toArray()).get("total", Long.class);
    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(pageSize);
    pageBinds.add((long) pageNo * pageSize);
    List<PurchaseOrderApprovalPayableView> items = new ArrayList<>();
    for (Record r :
        sql.fetch(
            "select *" + from + " order by created_at desc, id desc limit ? offset ?",
            pageBinds.toArray())) {
      items.add(payableView(r));
    }
    return new PurchaseOrderApprovalPayablePage(
        items,
        total,
        pageNo,
        pageSize,
        "登记额 = 审批通过时的采购单金额；生效额在财务确认之前**恒为 0**（12 财务未交付）。");
  }

  /**
   * 财务确认（登记的应付变生效）。
   *
   * <p>⚠️ 表级 CHECK 钉死：确认 ⇒ 生效额 = 登记额且确认人与财务单据号齐全。
   */
  public PurchaseOrderApprovalPayableView confirmPayable(
      UUID payableId, String financeReference, UUID actorId) {
    String reference = nz(financeReference).trim();
    if (reference.isEmpty()) {
      throw badRequest("财务确认必须带财务单据号（事后要答得出「冲的是哪一笔」）");
    }
    OffsetDateTime now = dbNow();
    int updated =
        sql.execute(
            "update purchase_order_payable set status='CONFIRMED',effective_amount=registered_amount,"
                + " finance_reference=?::varchar,confirmed_by=?::uuid,confirmed_at=?::timestamptz,"
                + " updated_at=?::timestamptz,version=version+1"
                + " where id=?::uuid and status='PENDING_FINANCE_CONFIRM'",
            reference,
            actorId,
            now,
            now,
            payableId);
    if (updated == 0) {
      throw conflict("这笔应付登记不存在，或已被确认 / 驳回");
    }
    return payableView(requirePayable(payableId));
  }

  /** 财务驳回（登记的应付作废；生效额仍为 0）。 */
  public PurchaseOrderApprovalPayableView rejectPayable(UUID payableId, String reason, UUID actorId) {
    String why = nz(reason).trim();
    if (why.isEmpty()) {
      throw badRequest("驳回应付必须写原因");
    }
    OffsetDateTime now = dbNow();
    int updated =
        sql.execute(
            "update purchase_order_payable set status='REJECTED',effective_amount=0,"
                + " rejected_by=?::uuid,rejected_at=?::timestamptz,reject_reason=?::varchar,"
                + " updated_at=?::timestamptz,version=version+1"
                + " where id=?::uuid and status='PENDING_FINANCE_CONFIRM'",
            actorId,
            now,
            why,
            now,
            payableId);
    if (updated == 0) {
      throw conflict("这笔应付登记不存在，或已被确认 / 驳回");
    }
    return payableView(requirePayable(payableId));
  }

  // ================================================================ 审批人判定

  /**
   * 当前登录人此刻**能不能批**（返回 null = 能批；返回字符串 = 不能批的原因）。
   *
   * <p>判定链（顺序即优先级）：在册审批人 → 不是自审 → 层级够 → 金额授权够 → 没批过。
   *
   * <p>⚠️ 这里返回的是**原因**而不是布尔 —— 界面要能说清「为什么这一格是灰的」，
   * 只给一个 false 会让审批人以为系统坏了。
   */
  private String blockedReason(
      UUID caseId,
      UUID buyerUserId,
      String requiredTier,
      BigDecimal amount,
      String currency,
      UUID actorId) {
    if (actorId == null) {
      return "拿不到当前操作人";
    }
    if (buyerUserId != null && buyerUserId.equals(actorId)) {
      return "不能审批自己提交的采购单（这单是 " + buyerName(buyerUserId) + " 提交的）";
    }
    List<Record> rows =
        sql.fetch(
            "select * from purchase_order_approval_approver where user_id=?::uuid and status='ACTIVE'",
            actorId);
    if (rows.isEmpty()) {
      return "你不是本租户在册的审批人（没有启用中的审批人配置）";
    }

    boolean tierTooLow = true;
    String bandProblem = null;
    String currencyProblem = null;
    for (Record row : rows) {
      String tier = nz(row.get("approval_tier", String.class));
      if (tierRank(tier) < tierRank(requiredTier)) {
        continue;
      }
      tierTooLow = false;
      String rowCurrency = nz(row.get("currency", String.class));
      if (!"ANY".equals(rowCurrency) && !rowCurrency.equals(currency)) {
        currencyProblem = "你的金额授权是 " + rowCurrency + " 口径，这一单是 " + currency;
        continue;
      }
      BigDecimal min = row.get("min_amount", BigDecimal.class);
      BigDecimal max = row.get("max_amount", BigDecimal.class);
      if (min != null && amount.compareTo(min) < 0) {
        bandProblem = "这一单的金额低于你的授权下限";
        continue;
      }
      if (max != null && amount.compareTo(max) >= 0) {
        bandProblem = "这一单的金额超过你的授权上限";
        continue;
      }
      // 层级与金额都够 ⇒ 只差「没批过」这一条。
      Long already =
          sql.fetchOne(
                  "select count(*) from purchase_order_approval_record where case_id=?::uuid"
                      + " and approver_user_id=?::uuid",
                  caseId,
                  actorId)
              .get(0, Long.class);
      if (already != null && already > 0) {
        return "你已经在这张采购单上批过了（不能自己批两次凑人数）";
      }
      return null;
    }

    if (tierTooLow) {
      return "你的审批层级低于这张单要求的层级（要求 " + requiredTier + "）";
    }
    if (currencyProblem != null) {
      return currencyProblem;
    }
    return bandProblem == null ? "你当前的金额授权覆盖不了这一单" : bandProblem;
  }

  /** 在够格的审批人行里挑出「层级与金额都够」的那一条（找不到就抛 403）。 */
  private Record chosenApprover(
      String requiredTier, BigDecimal amount, String currency, UUID actorId) {
    List<Record> rows =
        sql.fetch(
            "select * from purchase_order_approval_approver where user_id=?::uuid and status='ACTIVE'",
            actorId);
    for (Record row : rows) {
      if (tierRank(nz(row.get("approval_tier", String.class))) < tierRank(requiredTier)) {
        continue;
      }
      String rowCurrency = nz(row.get("currency", String.class));
      if (!"ANY".equals(rowCurrency) && !rowCurrency.equals(currency)) {
        continue;
      }
      BigDecimal min = row.get("min_amount", BigDecimal.class);
      BigDecimal max = row.get("max_amount", BigDecimal.class);
      if (min != null && amount.compareTo(min) < 0) {
        continue;
      }
      if (max != null && amount.compareTo(max) >= 0) {
        continue;
      }
      return row;
    }
    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "你当前的审批层级与金额授权覆盖不了这一单");
  }

  // ================================================================ 取数辅助

  /** 取（或兜底建）这张采购单的审批单。 */
  private Record ensureCase(UUID orderId) {
    Record c =
        sql.fetchOne("select * from purchase_order_approval_case where order_id=?::uuid", orderId);
    if (c != null) {
      return c;
    }
    // 兜底：本模块交付之前提交的单（或直接种进来的单）没有审批链 ——
    // 按规则现判一次并落一条，**不假装它已经批过**。
    Record m =
        sql.fetchOne(
            "select * from purchase_order_approval_match(?::uuid, ?::uuid)", currentTenant(), orderId);
    if (m == null) {
      throw notFound("这张采购单不存在或不属于当前租户");
    }
    sql.execute(
        "insert into purchase_order_approval_case(id,tenant_id,order_id,case_no,rule_id,rule_name,"
            + " rule_match,buyer_user_id,buyer_level,order_amount,currency,required_tier,"
            + " required_approvals,approved_count,status,created_at,updated_at,version)"
            + " values (?::uuid,?::uuid,?::uuid,?::varchar,?::uuid,?::varchar,?::varchar,?::uuid,"
            + " ?::varchar,?::numeric,?::varchar,?::varchar,?::integer,0,'PENDING',"
            + " ?::timestamptz,?::timestamptz,0) on conflict (tenant_id,order_id) do nothing",
        UUID.randomUUID(),
        currentTenant(),
        orderId,
        "PAC-" + sql.fetchOne("select nextval('purchase_order_approval_case_no_seq')").get(0, Long.class),
        m.get("rule_id", UUID.class),
        nz(m.get("rule_name", String.class)),
        nz(m.get("rule_match", String.class)),
        m.get("buyer_user_id", UUID.class),
        nz(m.get("buyer_level", String.class)),
        m.get("amount", BigDecimal.class),
        nz(m.get("currency", String.class)),
        nz(m.get("required_tier", String.class)),
        m.get("required_approvals", Integer.class),
        dbNow(),
        dbNow());
    return sql.fetchOne("select * from purchase_order_approval_case where order_id=?::uuid", orderId);
  }

  /** 取采购单（带数据范围）。取不到 = 不存在或不属于当前租户。 */
  private Record requireOrder(UUID orderId, UUID actorId, UUID actorDepartmentId, String scope) {
    StringBuilder sqlText =
        new StringBuilder("select o.* from purchase_order o where o.id=?::uuid and 1=1");
    List<Object> binds = new ArrayList<>();
    binds.add(orderId);
    appendScope(sqlText, binds, scope, actorId, actorDepartmentId);
    Record order = sql.fetchOne(sqlText.toString(), binds.toArray());
    if (order == null) {
      throw notFound("这张采购单不存在或不属于当前租户");
    }
    return order;
  }

  /** 数据范围：ALL / DEPARTMENT / SELF（**不接受请求参数传入**）。 */
  private void appendScope(
      StringBuilder where, List<Object> binds, String scope, UUID actorId, UUID actorDepartmentId) {
    if ("DEPARTMENT".equals(scope)) {
      if (actorDepartmentId == null) {
        throw badRequest("数据范围限定为部门时必须能解析出当前成员所属部门");
      }
      where.append(" and o.owner_department_id = ?::uuid");
      binds.add(actorDepartmentId);
    } else if ("SELF".equals(scope)) {
      if (actorId == null) {
        throw badRequest("数据范围限定为本人时必须能解析出操作人");
      }
      where.append(" and o.owner_user_id = ?::uuid");
      binds.add(actorId);
    }
  }

  private Record requireRule(UUID id) {
    Record r = sql.fetchOne("select * from purchase_order_approval_rule where id=?::uuid", id);
    if (r == null) {
      throw notFound("这条审批规则不存在或不属于当前租户");
    }
    return r;
  }

  private Record requirePayable(UUID id) {
    Record r = sql.fetchOne("select * from purchase_order_payable where id=?::uuid", id);
    if (r == null) {
      throw notFound("这笔应付登记不存在或不属于当前租户");
    }
    return r;
  }

  /**
   * 应付登记视图。
   *
   * <p>🔴 <b>永远返回一个对象</b>：还没登记应付时 status='NONE'、金额为 null。
   * 绝不返回 null、也绝不用 0 冒充「这笔应付是 0 元」。
   */
  private PurchaseOrderApprovalPayableView payableOf(UUID orderId) {
    Record r = sql.fetchOne("select * from purchase_order_payable where order_id=?::uuid", orderId);
    if (r == null) {
      return new PurchaseOrderApprovalPayableView(
          null, "", "", null, null, PAYABLE_NONE, "", null, null, null);
    }
    return payableView(r);
  }

  /**
   * 收货放行事实视图。
   *
   * <p>🔴 同样永远返回一个对象：还没放行时 status='NONE'。
   */
  private PurchaseOrderApprovalReceiptReleaseView receiptReleaseOf(UUID orderId) {
    Record r = sql.fetchOne("select * from purchase_order_receipt_release where order_id=?::uuid", orderId);
    if (r == null) {
      return new PurchaseOrderApprovalReceiptReleaseView(null, orderId, RELEASE_NONE, null, null, "");
    }
    return new PurchaseOrderApprovalReceiptReleaseView(
        r.get("id", UUID.class),
        r.get("order_id", UUID.class),
        nz(r.get("status", String.class)),
        r.get("released_by", UUID.class),
        r.get("released_at", OffsetDateTime.class),
        nz(r.get("release_note", String.class)));
  }

  /** 审批单当前状态（更新后回读，避免用更新前的快照判断「够不够人数」）。 */
  private String caseStatusOf(UUID caseId) {
    return nz(
        sql.fetchOne("select status from purchase_order_approval_case where id=?::uuid", caseId)
            .get("status", String.class));
  }

  private String buyerName(UUID userId) {
    if (userId == null) {
      return "";
    }
    Record r =
        sql.fetchOne(
            "select user_name from purchase_order_approval_buyer where user_id=?::uuid", userId);
    if (r != null && isSet(r.get("user_name", String.class))) {
      return nz(r.get("user_name", String.class));
    }
    return "";
  }

  // ================================================================ 视图装配

  private PurchaseOrderApprovalOrderRow orderRow(Record r, UUID actorId) {
    UUID orderId = r.get("order_id", UUID.class);
    UUID caseId = r.get("case_id", UUID.class);
    String blocked =
        caseId == null
            ? "这张采购单还没有审批链"
            : blockedReason(
                caseId,
                r.get("buyer_user_id", UUID.class),
                nz(r.get("required_tier", String.class)),
                r.get("order_amount", BigDecimal.class),
                nz(r.get("case_currency", String.class)),
                actorId);
    return new PurchaseOrderApprovalOrderRow(
        orderId,
        nz(r.get("order_no", String.class)),
        nz(r.get("order_status", String.class)),
        nz(r.get("supplier_code", String.class)),
        nz(r.get("supplier_name", String.class)),
        nz(r.get("currency", String.class)),
        caseId == null ? r.get("total_amount", BigDecimal.class) : r.get("order_amount", BigDecimal.class),
        r.get("owner_user_id", UUID.class),
        buyerName(r.get("owner_user_id", UUID.class)),
        nz(r.get("buyer_level", String.class)),
        nz(r.get("required_tier", String.class)),
        r.get("required_approvals", Integer.class),
        r.get("approved_count", Integer.class),
        nz(r.get("case_status", String.class)),
        nz(r.get("rule_name", String.class)),
        blocked == null,
        blocked == null ? "" : blocked);
  }

  private PurchaseOrderApprovalRuleView ruleView(Record r) {
    return new PurchaseOrderApprovalRuleView(
        r.get("id", UUID.class),
        nz(r.get("rule_no", String.class)),
        nz(r.get("rule_name", String.class)),
        nz(r.get("buyer_level", String.class)),
        nz(r.get("currency", String.class)),
        r.get("min_amount", BigDecimal.class),
        r.get("max_amount", BigDecimal.class),
        nz(r.get("required_tier", String.class)),
        r.get("required_approvals", Integer.class),
        r.get("priority", Integer.class),
        nz(r.get("status", String.class)),
        nz(r.get("note", String.class)),
        r.get("version", Long.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  private PurchaseOrderApprovalBuyerView buyerView(Record r) {
    return new PurchaseOrderApprovalBuyerView(
        r.get("id", UUID.class),
        r.get("user_id", UUID.class),
        nz(r.get("user_name", String.class)),
        nz(r.get("buyer_level", String.class)),
        nz(r.get("status", String.class)),
        nz(r.get("note", String.class)),
        r.get("version", Long.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  private PurchaseOrderApprovalApproverView approverView(Record r) {
    return new PurchaseOrderApprovalApproverView(
        r.get("id", UUID.class),
        r.get("user_id", UUID.class),
        nz(r.get("user_name", String.class)),
        nz(r.get("approval_tier", String.class)),
        nz(r.get("currency", String.class)),
        r.get("min_amount", BigDecimal.class),
        r.get("max_amount", BigDecimal.class),
        nz(r.get("status", String.class)),
        nz(r.get("note", String.class)),
        r.get("version", Long.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  private PurchaseOrderApprovalPayableView payableView(Record r) {
    return new PurchaseOrderApprovalPayableView(
        r.get("id", UUID.class),
        nz(r.get("payable_no", String.class)),
        nz(r.get("currency", String.class)),
        r.get("registered_amount", BigDecimal.class),
        r.get("effective_amount", BigDecimal.class),
        nz(r.get("status", String.class)),
        nz(r.get("finance_reference", String.class)),
        r.get("confirmed_by", UUID.class),
        r.get("confirmed_at", OffsetDateTime.class),
        r.get("created_at", OffsetDateTime.class));
  }

  // ================================================================ 工具

  private static int tierRank(String tier) {
    return switch (tier == null ? "" : tier) {
      case "L1" -> 1;
      case "L2" -> 2;
      case "L3" -> 3;
      default -> 0;
    };
  }

  private static String normalizeTier(String tier) {
    String t = nz(tier).trim().toUpperCase();
    if (!t.equals("L1") && !t.equals("L2") && !t.equals("L3")) {
      throw badRequest("审批层级只能是 L1 / L2 / L3");
    }
    return t;
  }

  private static String normalizeBuyerLevel(String level) {
    String l = nz(level).trim().toUpperCase();
    if (l.isEmpty() || l.equals("ANY")) {
      return "ANY";
    }
    return normalizeBuyerLevelStrict(l);
  }

  private static String normalizeBuyerLevelStrict(String level) {
    String l = nz(level).trim().toUpperCase();
    if (!l.equals("JUNIOR") && !l.equals("SENIOR") && !l.equals("MANAGER")) {
      throw badRequest("采购员分级只能是 JUNIOR / SENIOR / MANAGER");
    }
    return l;
  }

  private static String normalizeCurrency(String currency) {
    String c = nz(currency).trim().toUpperCase();
    if (c.isEmpty() || c.equals("ANY")) {
      return "ANY";
    }
    if (!c.matches("^[A-Z]{3}$")) {
      throw badRequest("币种必须是三位大写字母（或 ANY 表示不限币种）");
    }
    return c;
  }

  private static BigDecimal amount(BigDecimal value, String label) {
    if (value == null) {
      return BigDecimal.ZERO;
    }
    if (value.signum() < 0) {
      throw badRequest(label + "不能是负数");
    }
    return value.setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
  }

  private static String requireText(String value, String message) {
    String v = nz(value).trim();
    if (v.isEmpty()) {
      throw badRequest(message);
    }
    return v;
  }

  private static boolean isSet(String value) {
    return value != null && !value.isBlank();
  }

  private static String nz(String value) {
    return value == null ? "" : value;
  }

  private static String orderStatusLabel(String status) {
    return switch (status == null ? "" : status) {
      case "DRAFT" -> "草稿";
      case "PENDING_APPROVAL" -> "待审批";
      case "APPROVED" -> "已审批";
      case "REJECTED" -> "已驳回";
      case "IN_FULFILLMENT" -> "执行中";
      case "CLOSED" -> "已关闭";
      default -> status;
    };
  }

  /** 批量里的失败原因：把 HTTP 状态与消息原样带上，别吞。 */
  private static String failureMessage(RuntimeException e) {
    if (e instanceof ResponseStatusException rse) {
      return rse.getReason() == null ? rse.getStatusCode().toString() : rse.getReason();
    }
    return nz(e.getMessage()).isEmpty() ? e.getClass().getSimpleName() : e.getMessage();
  }

  /** 回滚到保存点（批量里那一张失败的单）。 */
  private void rollbackTo(String savepoint) {
    try {
      sql.execute(sql.rollback().toSavepoint(savepoint));
    } catch (RuntimeException e) {
      // 回滚失败是**严重**的：这一张单的半截写入可能留在事务里。
      // 不吞掉它 —— 让整批失败，比提交一条不一致的审批留痕安全。
      throw new IllegalStateException("批量审批里有一张单回滚失败，整批作废：" + e.getMessage(), e);
    }
  }

  /** 释放保存点（失败也不该影响业务结果）。 */
  private void releaseQuietly(String savepoint) {
    try {
      sql.execute(sql.releaseSavepoint(savepoint));
    } catch (RuntimeException ignored) {
      // 释放失败无害：事务提交 / 回滚时 PostgreSQL 会自己清掉它。
    }
  }

  /** 数据库口径的当前时刻（timestamptz ⇒ OffsetDateTime，不会有时区偏移）。 */
  private OffsetDateTime dbNow() {
    return sql.fetchOne("select now()").get(0, OffsetDateTime.class);
  }

  /** 当前绑定的租户（由 tenancy 库在事务内设置，**不是**客户端头里的那个）。 */
  private UUID currentTenant() {
    return sql.fetchOne("select nullif(current_setting('app.current_tenant', true),'')::uuid")
        .get(0, UUID.class);
  }

  private static ResponseStatusException notFound(String message) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
  }

  private static ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }

  /**
   * 业务拒绝一律 400，并把**能看懂的原因**原样带出去。
   *
   * <p>⚠️ 刻意**不用** {@code IllegalArgumentException}：trade 的 {@code ApiErrorHandler}
   * 把它翻成一句通用的「字段格式不正确，请检查类型、数量和必填项」，
   * 「驳回必须附意见」这种关键信息会被那句话吞掉 ——
   * 而审批这一环最需要的就是让人知道**到底哪一条不满足**。
   */
  private static ResponseStatusException badRequest(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }
}
