package com.starcon.trade;

import static com.starcon.trade.SupplierClaimModels.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Service;

/**
 * 11.2.3.3 供应商索赔。
 *
 * <p><b>本类是 V188 五张新表的写入所有者</b>（supplier_claim / supplier_claim_item /
 * supplier_claim_negotiation / supplier_claim_settlement / supplier_claim_event），
 * 对 V179 的 {@code return_quality_inspection}、V180 的 {@code product_issue_attribution}、
 * V185 的 {@code quality_supplier_ref} <b>只读</b>。
 * 刻意不加对外外键、不 ALTER 既有表 ⇒ 跨车道 DDL 回归面 = 0（同批另有三条车道也在往 trade 落迁移）。
 *
 * <p><b>七条硬纪律</b>：
 *
 * <ol>
 *   <li><b>每一条查询都显式带 {@code tenant_id} 谓词</b>，不依赖 RLS 当唯一防线。
 *       索赔单里同时有供应商名、批次、SKU、金额与协商记录，是同一条链上信息最密的一张单据；
 *       端点又大多是<b>按 id 直达</b>的 URL —— 漏掉租户谓词就是跨租户泄露。</li>
 *   <li><b>索赔基数不由调用方给</b>（硬规则 2）：基数 = 质检单原值，
 *       本行上限 = 原值 × 索赔数量 ÷ 实检数量，两者都由 DB 触发器取
 *       （V188 守卫②），传输层<b>没有</b>这两个字段。</li>
 *   <li><b>责任环节必须可索赔</b>（硬规则 1）：生产 / 描述不符才可索赔；
 *       运输、仓储、买家因素、责任待定一律拒收，并说清楚为什么。</li>
 *   <li><b>「没有财务确认就没有生效」</b>：登记赔付时生效金额恒为 0，
 *       只有财务确认那一刻才由 DB 把它改成登记金额；服务层<b>刻意没有</b>
 *       「直接置为已结算」的入口。</li>
 *   <li><b>登记赔付幂等</b>：已有待确认的分录时重复登记**返回同一条**，
 *       不新建第二条（重复冲减是资损事故）。</li>
 *   <li><b>时间绑定</b>：时间一律 {@code ?::timestamptz} + {@link OffsetDateTime}。
 *       {@code Timestamp.valueOf(LocalDateTime)} 会静默偏 −28800s，
 *       而协商记录的时刻偏 8 小时会让「供应商什么时候答复的」整体算错。</li>
 *   <li><b>状态流转只有一个入口</b>：每次改状态都过版本校验，都写留痕。</li>
 * </ol>
 */
@Service
public class SupplierClaimService {

  /** 列表单页上限。 */
  private static final int MAX_PAGE_SIZE = 200;

  /** 金额刻度：4 位（与 sales_order_line.unit_price、质检残值同刻度）。 */
  private static final int MONEY_SCALE = 4;

  /** 数量刻度：6 位（AGENTS.md：数量 NUMERIC(19,6)）。 */
  private static final int QTY_SCALE = 6;

  /** 可向供应商索赔的责任环节（与 V188 守卫② 逐字一致）。 */
  private static final String CLAIMABLE_STAGES_SQL = "('PRODUCTION','DESCRIPTION')";

  private final DSLContext sql;

  public SupplierClaimService(DSLContext sql) {
    this.sql = sql;
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：职责句的四个动词逐块报可用性，下游逐个报 delivered=false 与归属模块号、
   * 本模块<b>刻意不做</b>的事、两条硬规则与状态机的口径。
   *
   * <p>让界面能说清「为什么这笔冲减还没生效」「为什么这一批不能索赔」，
   * 而不是让它们显示成空白让人以为「这些能力没做」。
   */
  public SupplierClaimCapabilities capabilities() {
    return new SupplierClaimCapabilities(
        List.of(
            "质检结论 return_quality_inspection（V179，同库只读）—— 索赔基数（原值）、币种、实检数量的真值来源",
            "责任判定 product_issue_attribution（V180，同库只读）—— 责任环节、批次快照、SKU 快照的真值来源",
            "供应商引用镜像 quality_supplier_ref（V185，同库只读）—— 供应商主数据在 catalog 独立库，跨库读不到",
            "索赔单 supplier_claim + 明细 supplier_claim_item（V188）",
            "协商记录 supplier_claim_negotiation（V188，追加式，序号由 DB 排）",
            "赔付/冲减应付分录 supplier_claim_settlement（V188，**trade 侧自有事实记录**，生效金额由 DB 钉死）",
            "索赔留痕 supplier_claim_event（V188，追加式，运行角色 UPDATE/DELETE 已收回）"),
        List.of(
            section("batchAttribution", "关联批次与责任判定", true, null,
                "索赔明细**必须同时挂上**一条已判定的质检结论与一条已归因的责任判定，"
                    + "且两者的 inspection_id 必须一致（同一批货）。"
                    + "批次与 SKU 快照由 DB 从归因单取，调用方传不进来。",
                "supplier_claim_item + V188 守卫②"),
            section("claimAmount", "索赔金额", true, null,
                "**基数不由调用方给**：索赔基数 = 质检单原值（订单行单价 × 实收数量），"
                    + "本行上限 = 原值 × 索赔数量 ÷ 实检数量，由 DB 触发器取。"
                    + "「4 件里只坏 1 件却按 4 件的价索赔」在本模块没有代码路径。",
                "supplier_claim_item + V188 守卫②"),
            section("negotiation", "协商记录", true, null,
                "追加式证据链，序号由 DB 排（协商的先后顺序是证据链的一部分）。"
                    + "「达成金额」只出现在 type='AGREEMENT' 的记录上，"
                    + "索赔单头的达成金额由 DB 校验「必须等于那条记录里的金额」⇒ 全库只有一个答案。",
                "supplier_claim_negotiation + V188 守卫④"),
            section("settlement", "赔付与冲减应付处理", true, null,
                "赔付方式两种：**冲减应付**（在货款里扣）/ **供应商退款**（对方把钱退回来）。"
                    + "两者的财务确认要求**完全一样** —— 蓝图说的是「冲减应付**或**退款记录」，"
                    + "二者都**不是自动到账**。",
                "supplier_claim_settlement"),
            section("financeConfirm", "财务确认冲减", true, null,
                "登记赔付时生效金额**恒为 0**；只有财务确认那一刻才由 DB 把它改成登记金额，"
                    + "并把索赔单推到「已结算」终态。",
                "supplier_claim_settlement + V188 守卫⑦ / 表级 CHECK"),
            section("supplierRating", "供应商评级 / 改进建议", false, "6.1.1.3 / 11.5.2.1",
                "**未交付**。本模块的索赔与协商记录是评级与质量分析的输入，不产出它们的结论。",
                null),
            section("purchaseReconciliation", "采购对账（索赔进对账单）", false, "6.5.1",
                "**未交付**。本模块**不写对账单、不建共享视图** —— "
                    + "6.5.1 属系统 06（trade + catalog），跨服务只允许走授权 API / event。",
                null),
            section("financeService", "12 财务（冲减应付入账）", false, "12 财务",
                "**finance 服务当前不存在**（backend/services/ 下只有 identity / catalog / "
                    + "channel / warehouse / trade / crm）。"
                    + "⇒ 冲减应付当前以 **trade 侧自有事实记录**落地，"
                    + "与 12 财务的最终对账留待 finance 落地后经授权 API / event 衔接。",
                null)),
        List.of(
            related("MODULE_11_2_3_1", "11.2.3.1 质检与判定", true, null,
                "已判定的质检结论是索赔的**举证材料与基数来源**（V179，已交付，同库只读）。",
                "/aftersales/return-quality"),
            related("MODULE_11_1_2_2", "11.1.2.2 商品问题归因", true, null,
                "已归因的责任判定决定「该找谁赔」（V180，已交付，同库只读）。",
                "/aftersales/attributions"),
            related("MODULE_11_5_2_1", "11.5.2.1 质量分析", true, null,
                "供应商引用镜像 quality_supplier_ref 的所有者（V185，已交付，同库只读）。",
                "/aftersales/quality-analysis"),
            related("MODULE_12_FINANCE", "12 财务（冲减应付）", false, "12 财务",
                "**未交付且服务不存在** ⇒ 本模块只落 trade 侧自有事实记录，"
                    + "跨服务写账被 AGENTS.md 模块边界纪律明令禁止。",
                null),
            related("MODULE_6_5_1", "6.5.1 采购对账", false, "6.5.1",
                "**未交付**。跨服务只允许走授权 API / event，**禁止跨服务 SQL / 跨库 FK / 共享视图**。",
                null),
            related("MODULE_1_CATALOG", "1 商品中心（供应商主数据）", false, "1 商品中心 / 10 接口",
                "供应商主数据在 catalog 服务的**独立数据库**里，跨库读不到 ⇒ "
                    + "本模块引用 trade 侧镜像表 quality_supplier_ref。",
                null)),
        List.of(
            action("CREATE_CLAIM", "发起索赔", true, "POST", "/trade/supplier-claims", false,
                "供应商引用必须是 ACTIVE", null, null, null),
            action("ADD_ITEM", "加入索赔明细", true, "POST",
                "/trade/supplier-claims/{id}/items", false,
                "仅 DRAFT；质检结论已判定 + 责任判定已归因 + 环节可索赔", null, null, null),
            action("SUBMIT", "提交索赔", true, "POST", "/trade/supplier-claims/{id}/submit", false,
                "仅 DRAFT，且至少一条明细、金额 > 0", null, null, null),
            action("RECORD_NEGOTIATION", "记录协商", true, "POST",
                "/trade/supplier-claims/{id}/negotiations", false,
                "仅已提交 / 协商中", null, null, null),
            action("AGREE", "置为已达成", true, "POST", "/trade/supplier-claims/{id}/agree", false,
                "仅协商中，且已有一条「达成一致」的协商记录", null, null, null),
            action("REGISTER_SETTLEMENT", "登记赔付", true, "POST",
                "/trade/supplier-claims/{id}/settlements", false,
                "仅已达成；登记后**等待财务确认才生效**", null, null, null),
            action("CONFIRM_SETTLEMENT", "财务确认冲减", true, "POST",
                "/trade/supplier-claims/{id}/settlements/{settlementId}/confirm", true,
                "仅待财务确认；需回填财务单据号", null, null, null),
            action("REJECT_SETTLEMENT", "财务驳回赔付", true, "POST",
                "/trade/supplier-claims/{id}/settlements/{settlementId}/reject", true,
                "仅待财务确认；必须写理由", null, null, null),
            action("REJECT_CLAIM", "驳回索赔", true, "POST", "/trade/supplier-claims/{id}/reject", true,
                "仅已提交 / 协商中；必须写理由", null, null, null),
            action("WITHDRAW_CLAIM", "撤回索赔", true, "POST", "/trade/supplier-claims/{id}/withdraw",
                true, "仅草稿 / 已提交 / 协商中；必须写理由", null, null, null),
            action("SETTLE_DIRECTLY", "直接置为已结算", false, null, null, false, null, null, "12 财务",
                "**刻意不提供**：蓝图加粗句「**索赔不是自动到账**」—— "
                    + "已结算只能由「财务确认赔付分录」这一个动作驱动，没有第二个入口。")),
        List.of(
            boundary("不提供「直接置为已结算」的端点", "12 财务",
                "已结算只能由财务确认赔付分录驱动。一个绕过确认的入口等于让「索赔到了账」变成默认值。"),
            boundary("传输层没有索赔基数 / 原值 / 生效金额字段", "12 财务",
                "基数由公式从质检原值取；生效金额由财务确认那一刻的 DB 触发器写。"
                    + "可填的金额等于把财务口径交给每个调用方自己编。"),
            boundary("不写任何财务分录、不建对账视图、不跨库 FK", "12 财务 / 6.5.1 / 1 商品中心",
                "跨服务禁止 SQL。供应商主数据、批次真值、对账单都在别的服务的独立库里。"),
            boundary("终态不可逆：已结算 / 已驳回 / 已撤回整行冻结", "12 财务",
                "索赔单要拿去和供应商对质，「报了多少、协商成多少、冲减了多少」必须只有一个答案。"),
            boundary("明细只在草稿期可增删，且一条质检结论只能被索赔一次", "6.5.1",
                "重复索赔会让同一批货的损失被算两遍；已提交的索赔金额是发给供应商的数，改了要重发。")),
        "DRAFT → SUBMITTED → NEGOTIATING → AGREED → SETTLED（终态）"
            + " / REJECTED（终态）/ WITHDRAWN（终态）。"
            + "⚠️ **协商是必经环节**：提交后必须先记协商才能进协商中，"
            + "协商中必须有一条「达成一致」的记录才能置为已达成。",
        "索赔基数 = 质检单原值（订单行单价 × 实收数量，V179 算的真值）；"
            + "本行上限 = round(原值 × 索赔数量 ÷ 实检数量, 4)。"
            + "⚠️ 传输层**没有**基数字段，DB 触发器取数并比对。NUMERIC(19,4) + BigDecimal，**禁浮点**。",
        "「**没有财务确认就没有生效**」落成结构而不是文案："
            + "① 赔付分录的 requested_amount（登记）与 effective_amount（已生效）是两列，"
            + "登记时 effective 恒为 0；"
            + "② 表级 CHECK：未 CONFIRMED ⇒ effective = 0，CONFIRMED ⇒ effective = requested "
            + "且确认人 / 确认时刻 / 财务单据号齐全；"
            + "③ 索赔单头上的 applied_offset_amount 由 DB 从赔付分录重算；"
            + "④ 已确认的分录与已结算的单**整行冻结**。"
            + "⚠️ 下游 12 财务（finance 服务）**当前不存在** ⇒ 冲减应付当前以 trade 侧自有事实记录落地。",
        "协商记录是**追加式证据链**：序号由 DB 排（调用方填序号就等于让它变成可以改的东西）；"
            + "「达成金额」只出现在 type='AGREEMENT' 的记录上，"
            + "索赔单头的达成金额由 DB 校验「必须等于那条记录里的金额」⇒ 全库只有一个答案。");
  }

  // ================================================================ 列表

  /** 索赔单列表：真分页 + 多维筛选 + 排序白名单 + 跨全表汇总。 */
  public SupplierClaimPage listClaims(SupplierClaimListQuery query) {
    int p = query.page() == null || query.page() < 0 ? 0 : query.page();
    int s = query.size() == null || query.size() <= 0 ? 20 : Math.min(query.size(), MAX_PAGE_SIZE);
    SupplierClaimSort sort = SupplierClaimSort.parse(query.sort());

    List<Object> binds = new ArrayList<>();
    List<String> where = conditions(query, binds);
    String clause = " where " + String.join(" and ", where);

    String from =
        " from supplier_claim c"
            + " left join lateral ("
            + "   select count(*) as item_count from supplier_claim_item i"
            + "    where i.tenant_id = c.tenant_id and i.claim_id = c.id) ic on true";

    long total = sql.fetchOne("select count(*)" + from + clause, binds.toArray()).get(0, Long.class);

    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(s);
    pageBinds.add((long) p * s);
    List<SupplierClaimRow> rows =
        sql.fetch(
                "select c.*, coalesce(ic.item_count, 0) as item_count"
                    + from
                    + clause
                    + " order by "
                    + sort.sql()
                    + " limit ? offset ?",
                pageBinds.toArray())
            .map(SupplierClaimService::mapClaimRow);

    return new SupplierClaimPage(
        rows,
        p,
        s,
        total,
        summary(clause, binds),
        OffsetDateTime.now(ZoneOffset.UTC),
        "真分页（LIMIT/OFFSET + 独立 COUNT），汇总与排序不受分页影响。",
        "⚠️ **冲减应付在财务确认之前不生效**：汇总里的 totalAppliedOffsetAmount 只累加"
            + "已被财务确认（settlement_status='CONFIRMED'）的生效金额，"
            + "待确认的赔付仍挂在 pendingFinanceConfirmCount 上。",
        "责任环节筛选只认**可索赔**的两个：生产（PRODUCTION）/ 描述不符（DESCRIPTION）。"
            + "运输、仓储、买家因素、责任待定一律不可向供应商索赔 —— 详见能力声明的 blockedReason。",
        "排序白名单五个键（created_desc / submitted_asc / amount_desc / offset_asc / supplier_asc）；"
            + "其余取值落回 created_desc，不接受任意表达式。");
  }

  /** 列表的 where 条件与绑定（筛选维度：状态 / 供应商 / 责任环节 / 金额区间 / 时间 / 关键字）。 */
  private List<String> conditions(SupplierClaimListQuery query, List<Object> binds) {
    List<String> where = new ArrayList<>();
    where.add("c.tenant_id = ?::uuid");
    binds.add(currentTenant());

    if (isSet(query.status())) {
      where.add("c.claim_status = ?");
      binds.add(parseEnum(SupplierClaimStatus.class, query.status(), "索赔状态").name());
    }
    if (query.supplierRefId() != null) {
      where.add("c.supplier_ref_id = ?::uuid");
      binds.add(query.supplierRefId());
    }
    if (isSet(query.liabilityStage())) {
      // ⚠️ 只认可索赔环节：不可索赔的环节**筛不出来**（而不是筛出来再显示一堆不能索赔的行）。
      String stage = query.liabilityStage().trim();
      if (!SupplierClaimableStage.claimable(stage)) {
        where.add("1 = 0");
      } else {
        where.add(
            "exists (select 1 from supplier_claim_item i"
                + "   join product_issue_attribution a"
                + "     on a.tenant_id = i.tenant_id and a.id = i.attribution_id"
                + "  where i.tenant_id = c.tenant_id and i.claim_id = c.id and a.attributed_stage = ?)");
        binds.add(stage);
      }
    }
    if (isSet(query.offsetStatus())) {
      where.add("c.offset_status = ?");
      binds.add(parseEnum(SupplierOffsetStatus.class, query.offsetStatus(), "冲减状态").name());
    }
    if (query.minAmount() != null) {
      where.add("c.claimed_amount >= ?::numeric");
      binds.add(money(query.minAmount(), "金额下限"));
    }
    if (query.maxAmount() != null) {
      where.add("c.claimed_amount <= ?::numeric");
      binds.add(money(query.maxAmount(), "金额上限"));
    }
    if (query.from() != null) {
      where.add("c.created_at >= ?::timestamptz");
      binds.add(query.from());
    }
    if (query.to() != null) {
      where.add("c.created_at <= ?::timestamptz");
      binds.add(query.to());
    }
    if (isSet(query.keyword())) {
      where.add("(c.claim_no ilike ? or c.supplier_code ilike ? or c.supplier_name ilike ?)");
      String like = "%" + query.keyword().trim() + "%";
      binds.add(like);
      binds.add(like);
      binds.add(like);
    }
    return where;
  }

  /** 页汇总：跨全表算，不随分页变小。 */
  private SupplierClaimSummary summary(String clause, List<Object> binds) {
    Record r =
        sql.fetchOne(
            "select count(*) as total,"
                + " count(*) filter (where c.claim_status = 'DRAFT') as draft,"
                + " count(*) filter (where c.claim_status = 'SUBMITTED') as submitted,"
                + " count(*) filter (where c.claim_status = 'NEGOTIATING') as negotiating,"
                + " count(*) filter (where c.claim_status = 'AGREED') as agreed,"
                + " count(*) filter (where c.claim_status = 'SETTLED') as settled,"
                + " count(*) filter (where c.claim_status = 'REJECTED') as rejected,"
                + " count(*) filter (where c.claim_status = 'WITHDRAWN') as withdrawn,"
                + " coalesce(sum(c.claimed_amount), 0) as total_claimed,"
                + " coalesce(sum(c.agreed_amount), 0) as total_agreed,"
                + " coalesce(sum(c.applied_offset_amount), 0) as total_applied,"
                + " count(*) filter (where c.offset_status = 'PENDING_FINANCE_CONFIRM') as pending_finance"
                + " from supplier_claim c" + clause,
            binds.toArray());
    return new SupplierClaimSummary(
        r.get("total", Long.class),
        r.get("draft", Long.class),
        r.get("submitted", Long.class),
        r.get("negotiating", Long.class),
        r.get("agreed", Long.class),
        r.get("settled", Long.class),
        r.get("rejected", Long.class),
        r.get("withdrawn", Long.class),
        r.get("total_claimed", BigDecimal.class),
        r.get("total_agreed", BigDecimal.class),
        r.get("total_applied", BigDecimal.class),
        r.get("pending_finance", Long.class));
  }

  // ================================================================ 详情

  /** 索赔单全景：头 + 明细 + 协商 + 赔付 + 留痕 + 当前可执行动作。 */
  public SupplierClaimDetail claimDetail(UUID id) {
    Record head = requireClaim(id);
    List<SupplierClaimItemRow> items =
        sql.fetch(
                "select * from supplier_claim_item where tenant_id = ?::uuid and claim_id = ?::uuid"
                    + " order by created_at, id",
                currentTenant(),
                id)
            .map(
                r ->
                    new SupplierClaimItemRow(
                        r.get("id", UUID.class),
                        r.get("inspection_id", UUID.class),
                        r.get("attribution_id", UUID.class),
                        attributionStageOf(r.get("attribution_id", UUID.class)),
                        stageLabel(attributionStageOf(r.get("attribution_id", UUID.class))),
                        r.get("lot_id", UUID.class),
                        r.get("lot_code", String.class),
                        r.get("sku_id", UUID.class),
                        r.get("sku_code", String.class),
                        r.get("sku_name", String.class),
                        r.get("claim_qty", BigDecimal.class),
                        r.get("claim_amount", BigDecimal.class),
                        r.get("claim_currency", String.class),
                        r.get("claimable_base_amount", BigDecimal.class),
                        r.get("max_claim_amount", BigDecimal.class),
                        r.get("created_at", OffsetDateTime.class)));

    List<SupplierClaimNegotiationRow> negotiations =
        sql.fetch(
                "select * from supplier_claim_negotiation where tenant_id = ?::uuid and claim_id = ?::uuid"
                    + " order by negotiation_seq",
                currentTenant(),
                id)
            .map(
                r -> {
                  String type = r.get("negotiation_type", String.class);
                  String role = r.get("participant_role", String.class);
                  return new SupplierClaimNegotiationRow(
                      r.get("id", UUID.class),
                      r.get("negotiation_seq", Integer.class),
                      type,
                      SupplierNegotiationType.valueOf(type).label(),
                      role,
                      SupplierNegotiationRole.valueOf(role).label(),
                      r.get("proposed_amount", BigDecimal.class),
                      r.get("agreed_amount", BigDecimal.class),
                      r.get("negotiation_note", String.class),
                      r.get("occurred_at", OffsetDateTime.class),
                      r.get("created_at", OffsetDateTime.class));
                });

    List<SupplierClaimSettlementRow> settlements =
        sql.fetch(
                "select * from supplier_claim_settlement where tenant_id = ?::uuid and claim_id = ?::uuid"
                    + " order by created_at, id",
                currentTenant(),
                id)
            .map(
                r -> {
                  String method = r.get("settlement_method", String.class);
                  String status = r.get("settlement_status", String.class);
                  return new SupplierClaimSettlementRow(
                      r.get("id", UUID.class),
                      method,
                      SupplierSettlementMethod.valueOf(method).label(),
                      r.get("settlement_currency", String.class),
                      r.get("requested_amount", BigDecimal.class),
                      r.get("effective_amount", BigDecimal.class),
                      status,
                      SupplierSettlementStatus.valueOf(status).label(),
                      r.get("finance_reference", String.class),
                      r.get("confirmed_by", UUID.class),
                      r.get("confirmed_at", OffsetDateTime.class),
                      r.get("reject_reason", String.class),
                      r.get("created_at", OffsetDateTime.class),
                      r.get("version", Long.class));
                });

    List<SupplierClaimEventRow> events =
        sql.fetch(
                "select * from supplier_claim_event where tenant_id = ?::uuid and claim_id = ?::uuid"
                    + " order by occurred_at, id",
                currentTenant(),
                id)
            .map(
                r ->
                    new SupplierClaimEventRow(
                        r.get("id", UUID.class),
                        r.get("event_type", String.class),
                        eventLabel(r.get("event_type", String.class)),
                        r.get("from_status", String.class),
                        r.get("to_status", String.class),
                        r.get("amount", BigDecimal.class),
                        r.get("reason_note", String.class),
                        r.get("occurred_at", OffsetDateTime.class)));

    return new SupplierClaimDetail(
        mapClaimRow(head),
        items,
        negotiations,
        settlements,
        events,
        actionsOf(head, items.size(), negotiations, settlements),
        OffsetDateTime.now(ZoneOffset.UTC),
        "索赔单里的每一个金额都有出处：claimedAmount = Σ 明细金额（DB 重算），"
            + "claimableBaseAmount = Σ 明细基数（= 质检原值），"
            + "agreedAmount 只能来自「达成一致」那条协商记录，"
            + "appliedOffsetAmount 只在财务确认后才 > 0。");
  }

  // ================================================================ 可索赔候选

  /**
   * 可索赔候选：已判定 + 已归因 + 环节可索赔 + 尚未被索赔过。
   *
   * <p>这是「发起索赔」的**唯一入口素材**：界面不该让操作员手填质检结论 id 与责任判定 id。
   */
  public SupplierClaimCandidatePage candidates(String keyword, Integer page, Integer size) {
    int p = page == null || page < 0 ? 0 : page;
    int s = size == null || size <= 0 ? 20 : Math.min(size, MAX_PAGE_SIZE);

    List<Object> binds = new ArrayList<>();
    List<String> where = new ArrayList<>();
    where.add("i.tenant_id = ?::uuid");
    binds.add(currentTenant());
    where.add("i.inspection_status = 'CONCLUDED'");
    where.add("a.attribution_status = 'ATTRIBUTED'");
    where.add("a.attributed_stage in " + CLAIMABLE_STAGES_SQL);
    // 已被索赔过的质检结论不再出现在候选里。
    where.add(
        "not exists (select 1 from supplier_claim_item x"
            + "   where x.tenant_id = i.tenant_id and x.inspection_id = i.id)");
    if (isSet(keyword)) {
      where.add("(i.sku_code ilike ? or i.sku_name ilike ? or i.inspection_no ilike ? or a.lot_code ilike ?)");
      String like = "%" + keyword.trim() + "%";
      binds.add(like);
      binds.add(like);
      binds.add(like);
      binds.add(like);
    }
    String clause = " where " + String.join(" and ", where);

    String from =
        " from return_quality_inspection i"
            + " join product_issue_attribution a"
            + "   on a.tenant_id = i.tenant_id and a.inspection_id = i.id";

    long total = sql.fetchOne("select count(*)" + from + clause, binds.toArray()).get(0, Long.class);

    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(s);
    pageBinds.add((long) p * s);
    List<SupplierClaimCandidateRow> rows =
        sql.fetch(
                "select i.id as inspection_id, i.inspection_no, i.verdict, i.verdict_label,"
                    + " i.conclusion_note, i.sku_id, i.sku_code, i.sku_name, i.batch_no,"
                    + " i.inspected_qty, i.original_value, i.salvage_currency, i.concluded_at,"
                    + " a.id as attribution_id, a.attribution_no, a.attributed_stage, a.stage_label,"
                    + " a.lot_id, a.lot_code, a.lot_supplier_ref_id,"
                    + " round(i.original_value, 4) as max_claim_amount"
                    + from
                    + clause
                    + " order by i.concluded_at desc nulls last, i.id desc"
                    + " limit ? offset ?",
                pageBinds.toArray())
            .map(
                r -> {
                  String stage = r.get("attributed_stage", String.class);
                  return new SupplierClaimCandidateRow(
                      r.get("inspection_id", UUID.class),
                      r.get("inspection_no", String.class),
                      r.get("verdict", String.class),
                      r.get("verdict_label", String.class),
                      r.get("conclusion_note", String.class),
                      r.get("attribution_id", UUID.class),
                      r.get("attribution_no", String.class),
                      stage,
                      r.get("stage_label", String.class),
                      r.get("lot_id", UUID.class),
                      r.get("lot_code", String.class),
                      r.get("lot_supplier_ref_id", UUID.class),
                      r.get("sku_id", UUID.class),
                      r.get("sku_code", String.class),
                      r.get("sku_name", String.class),
                      r.get("batch_no", String.class),
                      r.get("inspected_qty", BigDecimal.class),
                      r.get("original_value", BigDecimal.class),
                      r.get("max_claim_amount", BigDecimal.class),
                      r.get("salvage_currency", String.class),
                      r.get("concluded_at", OffsetDateTime.class));
                });

    return new SupplierClaimCandidatePage(
        rows,
        total,
        OffsetDateTime.now(ZoneOffset.UTC),
        "候选 = 已判定的质检结论 × 已归因的责任判定，且责任环节落在可索赔集合（生产 / 描述不符）。"
            + "已被索赔过的质检结论不再出现（一条质检结论只能被索赔一次）。"
            + "maxClaimAmount = 质检原值（全量），按索赔数量折算由 DB 在写入时算。");
  }

  // ================================================================ 写

  /** 建索赔单（恒为草稿）。 */
  public SupplierClaimDetail createClaim(SupplierClaimCreate request, UUID userId) {
    if (request.supplierRefId() == null) {
      throw new IllegalArgumentException("必须给出供应商（供应商主数据在 catalog 独立库，本模块引用的内部供应商口径）");
    }
    SupplierClaimReason reason =
        parseEnum(SupplierClaimReason.class, request.claimReasonCode(), "索赔原因");
    String currency = requireCurrency(request.claimCurrency());
    if (!isSet(request.claimReasonNote())) {
      throw new IllegalArgumentException(
          "必须写索赔理由：发出去的索赔单要能让供应商看懂赔的是哪几批货、为什么赔");
    }

    long next = sql.fetchOne("select nextval('supplier_claim_no_seq')").get(0, Long.class);
    UUID id = UUID.randomUUID();
    sql.execute(
        "insert into supplier_claim(id,tenant_id,claim_no,supplier_ref_id,claim_reason_code,"
            + "claim_reason_note,claim_currency,claim_status,created_by)"
            + " values(?::uuid,?::uuid,?,?::uuid,?,?,?,'DRAFT',?::uuid)",
        id,
        currentTenant(),
        "SCL" + next,
        request.supplierRefId(),
        reason.name(),
        request.claimReasonNote().trim(),
        currency,
        userId);
    return claimDetail(id);
  }

  /** 加一条索赔明细（仅草稿）。 */
  public SupplierClaimDetail addItem(UUID claimId, SupplierClaimItemCreate request, UUID userId) {
    Record head = requireClaim(claimId);
    requireDraft(head);
    if (request.inspectionId() == null || request.attributionId() == null) {
      throw new IllegalArgumentException("必须同时给出质检结论与责任判定（蓝图输入句要求两者都有）");
    }
    if (request.claimAmount() == null) {
      throw new IllegalArgumentException("必须给出索赔金额");
    }
    BigDecimal amount = money(request.claimAmount(), "索赔金额");

    // 数量不给时按该质检结论的实检数量全额索赔（真值从上游取，不是猜的）。
    BigDecimal qty = request.claimQty();
    if (qty == null) {
      qty =
          sql.fetchOne(
                  "select inspected_qty from return_quality_inspection"
                      + " where tenant_id = ?::uuid and id = ?::uuid",
                  currentTenant(),
                  request.inspectionId())
              .get("inspected_qty", BigDecimal.class);
    }
    BigDecimal quantity = qty(qty, "索赔数量");

    // 已索赔过的质检结论直接拦（DB 上还有唯一索引兜底）。
    long used =
        sql.fetchOne(
                "select count(*) from supplier_claim_item"
                    + " where tenant_id = ?::uuid and inspection_id = ?::uuid",
                currentTenant(),
                request.inspectionId())
            .get(0, Long.class);
    if (used > 0) {
      throw new IllegalArgumentException(
          "该质检结论已经被索赔过了（一条质检结论只能被索赔一次，否则同一批货的损失会被算两遍）");
    }

    sql.execute(
        "insert into supplier_claim_item(id,tenant_id,claim_id,inspection_id,attribution_id,"
            + "claim_qty,claim_amount,claim_currency,created_by)"
            + " values(?::uuid,?::uuid,?::uuid,?::uuid,?::uuid,?::numeric,?::numeric,?,?::uuid)",
        UUID.randomUUID(),
        currentTenant(),
        claimId,
        request.inspectionId(),
        request.attributionId(),
        quantity,
        amount,
        head.get("claim_currency", String.class),
        userId);
    return claimDetail(claimId);
  }

  /** 移除一条索赔明细（仅草稿）。 */
  public SupplierClaimDetail removeItem(UUID claimId, UUID itemId, UUID userId) {
    Record head = requireClaim(claimId);
    requireDraft(head);
    int deleted =
        sql.execute(
            "delete from supplier_claim_item where tenant_id = ?::uuid and id = ?::uuid and claim_id = ?::uuid",
            currentTenant(),
            itemId,
            claimId);
    if (deleted == 0) {
      throw new IllegalArgumentException("索赔明细不存在或不属于当前租户：" + itemId);
    }
    return claimDetail(claimId);
  }

  /** 提交索赔（草稿 → 已提交）。 */
  public SupplierClaimDetail submit(UUID claimId, SupplierClaimSubmit request, UUID userId) {
    Record head = requireClaim(claimId);
    requireVersion(head.get("version", Long.class), request.expectedVersion(), "索赔单");
    requireStatus(head, java.util.Set.of(SupplierClaimStatus.DRAFT), "提交");
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    requireChanged(
        sql.execute(
            "update supplier_claim set claim_status = 'SUBMITTED', submitted_by = ?::uuid,"
                + " submitted_at = ?::timestamptz, updated_at = ?::timestamptz, version = version + 1"
                + " where tenant_id = ?::uuid and id = ?::uuid and claim_status = 'DRAFT'",
            userId,
            now,
            now,
            currentTenant(),
            claimId),
        "提交索赔");
    return claimDetail(claimId);
  }

  /** 记一条协商记录（已提交 / 协商中）。 */
  public SupplierClaimDetail recordNegotiation(
      UUID claimId, SupplierNegotiationCreate request, UUID userId) {
    requireClaim(claimId);
    SupplierNegotiationType type =
        parseEnum(SupplierNegotiationType.class, request.negotiationType(), "协商类型");
    SupplierNegotiationRole role =
        parseEnum(SupplierNegotiationRole.class, request.participantRole(), "协商参与方");
    OffsetDateTime occurredAt =
        request.occurredAt() == null ? OffsetDateTime.now(ZoneOffset.UTC) : request.occurredAt();
    BigDecimal proposed =
        request.proposedAmount() == null ? null : money(request.proposedAmount(), "提议金额");
    BigDecimal agreed =
        request.agreedAmount() == null ? null : money(request.agreedAmount(), "达成金额");
    if (type.carriesAgreementAmount() && (agreed == null || agreed.signum() <= 0)) {
      throw new IllegalArgumentException("「达成一致」的协商记录必须写达成金额");
    }
    if (!type.carriesAgreementAmount() && agreed != null) {
      throw new IllegalArgumentException("只有「达成一致」的协商记录才能带达成金额");
    }
    if (proposed == null && !isSet(request.negotiationNote())) {
      throw new IllegalArgumentException("协商记录至少要有提议金额或一段说明");
    }
    sql.execute(
        "insert into supplier_claim_negotiation(id,tenant_id,claim_id,negotiation_type,"
            + "participant_role,proposed_amount,agreed_amount,negotiation_note,occurred_at,recorded_by)"
            + " values(?::uuid,?::uuid,?::uuid,?,?,?::numeric,?::numeric,?,?::timestamptz,?::uuid)",
        UUID.randomUUID(),
        currentTenant(),
        claimId,
        type.name(),
        role.name(),
        proposed,
        agreed,
        trimOrEmpty(request.negotiationNote()),
        occurredAt,
        userId);
    return claimDetail(claimId);
  }

  /** 进入协商（已提交 → 协商中）。 */
  public SupplierClaimDetail startNegotiation(UUID claimId, Long expectedVersion, UUID userId) {
    Record head = requireClaim(claimId);
    requireVersion(head.get("version", Long.class), expectedVersion, "索赔单");
    requireStatus(head, java.util.Set.of(SupplierClaimStatus.SUBMITTED), "进入协商");
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    requireChanged(
        sql.execute(
            "update supplier_claim set claim_status = 'NEGOTIATING', updated_at = ?::timestamptz,"
                + " version = version + 1"
                + " where tenant_id = ?::uuid and id = ?::uuid and claim_status = 'SUBMITTED'",
            now,
            currentTenant(),
            claimId),
        "进入协商");
    return claimDetail(claimId);
  }

  /** 置为已达成（协商中 → 已达成）。达成金额由 DB 从「达成一致」那条协商记录取。 */
  public SupplierClaimDetail agree(UUID claimId, SupplierClaimAgree request, UUID userId) {
    Record head = requireClaim(claimId);
    requireVersion(head.get("version", Long.class), request.expectedVersion(), "索赔单");
    requireStatus(head, java.util.Set.of(SupplierClaimStatus.NEGOTIATING), "置为已达成");
    BigDecimal agreed = agreedAmountOf(claimId);
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    requireChanged(
        sql.execute(
            "update supplier_claim set claim_status = 'AGREED', agreed_amount = ?::numeric,"
                + " updated_at = ?::timestamptz, version = version + 1"
                + " where tenant_id = ?::uuid and id = ?::uuid and claim_status = 'NEGOTIATING'",
            agreed,
            now,
            currentTenant(),
            claimId),
        "置为已达成");
    return claimDetail(claimId);
  }

  /** 驳回索赔（已提交 / 协商中 → 已驳回）。 */
  public SupplierClaimDetail rejectClaim(UUID claimId, SupplierClaimReject request, UUID userId) {
    Record head = requireClaim(claimId);
    requireVersion(head.get("version", Long.class), request.expectedVersion(), "索赔单");
    if (!isSet(request.rejectReason())) {
      throw new IllegalArgumentException("驳回必须写理由（驳回是要发给供应商的，得让人知道为什么不给赔）");
    }
    requireStatus(
        head,
        java.util.Set.of(SupplierClaimStatus.SUBMITTED, SupplierClaimStatus.NEGOTIATING),
        "驳回");
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    requireChanged(
        sql.execute(
            "update supplier_claim set claim_status = 'REJECTED', rejected_by = ?::uuid,"
                + " rejected_at = ?::timestamptz, reject_reason = ?, updated_at = ?::timestamptz,"
                + " version = version + 1"
                + " where tenant_id = ?::uuid and id = ?::uuid"
                + " and claim_status in ('SUBMITTED','NEGOTIATING')",
            userId,
            now,
            request.rejectReason().trim(),
            now,
            currentTenant(),
            claimId),
        "驳回索赔");
    return claimDetail(claimId);
  }

  /** 撤回索赔（草稿 / 已提交 / 协商中 → 已撤回）。 */
  public SupplierClaimDetail withdrawClaim(
      UUID claimId, SupplierClaimWithdraw request, UUID userId) {
    Record head = requireClaim(claimId);
    requireVersion(head.get("version", Long.class), request.expectedVersion(), "索赔单");
    if (!isSet(request.withdrawReason())) {
      throw new IllegalArgumentException("撤回必须写理由");
    }
    requireStatus(
        head,
        java.util.Set.of(
            SupplierClaimStatus.DRAFT,
            SupplierClaimStatus.SUBMITTED,
            SupplierClaimStatus.NEGOTIATING),
        "撤回");
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    requireChanged(
        sql.execute(
            "update supplier_claim set claim_status = 'WITHDRAWN', withdrawn_by = ?::uuid,"
                + " withdrawn_at = ?::timestamptz, withdraw_reason = ?, updated_at = ?::timestamptz,"
                + " version = version + 1"
                + " where tenant_id = ?::uuid and id = ?::uuid"
                + " and claim_status in ('DRAFT','SUBMITTED','NEGOTIATING')",
            userId,
            now,
            request.withdrawReason().trim(),
            now,
            currentTenant(),
            claimId),
        "撤回索赔");
    return claimDetail(claimId);
  }

  /**
   * 登记赔付 / 冲减应付（<b>幂等</b>：已有待确认的分录时返回同一条，不新建第二条）。
   *
   * <p>⚠️ 登记时生效金额恒为 0 —— 「索赔不是自动到账」这句话在**这一行**就落成了结构。
   */
  public SupplierClaimDetail registerSettlement(
      UUID claimId, SupplierSettlementCreate request, UUID userId) {
    Record head = requireClaim(claimId);
    if (!"AGREED".equals(head.get("claim_status", String.class))) {
      throw new IllegalArgumentException(
          "只有已达成一致的索赔单才能登记赔付（当前状态："
              + head.get("claim_status", String.class) + "）");
    }
    SupplierSettlementMethod method =
        parseEnum(SupplierSettlementMethod.class, request.settlementMethod(), "赔付方式");
    BigDecimal amount = money(request.requestedAmount(), "赔付金额");
    if (amount.signum() <= 0) {
      throw new IllegalArgumentException("赔付金额必须大于 0");
    }

    // ---- 幂等：已有待确认的分录就返回它，不新建第二条（重复冲减是资损事故）----
    Record pending =
        sql.fetchOne(
            "select * from supplier_claim_settlement"
                + " where tenant_id = ?::uuid and claim_id = ?::uuid"
                + " and settlement_status = 'PENDING_FINANCE_CONFIRM'"
                + " order by created_at desc limit 1",
            currentTenant(),
            claimId);
    if (pending != null) {
      return claimDetail(claimId);
    }

    BigDecimal agreed = head.get("agreed_amount", BigDecimal.class);
    if (agreed != null && amount.compareTo(agreed) > 0) {
      throw new IllegalArgumentException(
          "登记赔付金额（" + amount + "）不得超过达成金额（" + agreed + "）");
    }
    sql.execute(
        "insert into supplier_claim_settlement(id,tenant_id,claim_id,settlement_method,"
            + "settlement_currency,requested_amount,effective_amount,settlement_status,created_by)"
            + " values(?::uuid,?::uuid,?::uuid,?,?,?::numeric,0,'PENDING_FINANCE_CONFIRM',?::uuid)",
        UUID.randomUUID(),
        currentTenant(),
        claimId,
        method.name(),
        head.get("claim_currency", String.class),
        amount,
        userId);
    return claimDetail(claimId);
  }

  /**
   * 财务确认冲减生效。
   *
   * <p>⚠️ 这是**唯一**能让冲减生效的入口：确认那一刻由 DB 触发器把
   * {@code effective_amount} 从 0 改成登记金额，并把索赔单推到「已结算」终态。
   */
  public SupplierClaimDetail confirmSettlement(
      UUID claimId, UUID settlementId, SupplierSettlementConfirm request, UUID userId) {
    requireClaim(claimId);
    requireSettlement(claimId, settlementId);
    if (!isSet(request.financeReference())) {
      throw new IllegalArgumentException(
          "财务确认必须回填财务单据号：事后要答得出「冲的是哪一笔」，"
              + "只写「已确认」的话三个月后没人说得清这笔钱扣在了哪张应付单上");
    }
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    sql.execute(
        "update supplier_claim_settlement set settlement_status = 'CONFIRMED',"
            + " effective_amount = requested_amount, finance_reference = ?,"
            + " confirmed_by = ?::uuid, confirmed_at = ?::timestamptz, updated_at = ?::timestamptz,"
            + " version = version + 1"
            + " where tenant_id = ?::uuid and id = ?::uuid and settlement_status = 'PENDING_FINANCE_CONFIRM'",
        request.financeReference().trim(),
        userId,
        now,
        now,
        currentTenant(),
        settlementId);
    return claimDetail(claimId);
  }

  /** 财务驳回赔付。 */
  public SupplierClaimDetail rejectSettlement(
      UUID claimId, UUID settlementId, SupplierSettlementReject request, UUID userId) {
    requireClaim(claimId);
    requireSettlement(claimId, settlementId);
    if (!isSet(request.rejectReason())) {
      throw new IllegalArgumentException("财务驳回必须写理由");
    }
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    sql.execute(
        "update supplier_claim_settlement set settlement_status = 'REJECTED',"
            + " effective_amount = 0, rejected_by = ?::uuid, rejected_at = ?::timestamptz,"
            + " reject_reason = ?, updated_at = ?::timestamptz, version = version + 1"
            + " where tenant_id = ?::uuid and id = ?::uuid and settlement_status = 'PENDING_FINANCE_CONFIRM'",
        userId,
        now,
        request.rejectReason().trim(),
        now,
        currentTenant(),
        settlementId);
    return claimDetail(claimId);
  }

  // ================================================================ 内部

  /** 索赔单头（带租户谓词；不存在与不属于本租户是同一句话）。 */
  private Record requireClaim(UUID id) {
    Record r =
        sql.fetchOne(
            "select c.*, (select count(*) from supplier_claim_item i"
                + "   where i.tenant_id = c.tenant_id and i.claim_id = c.id) as item_count"
                + " from supplier_claim c where c.tenant_id = ?::uuid and c.id = ?::uuid",
            currentTenant(),
            id);
    if (r == null) {
      throw new IllegalArgumentException("索赔单不存在或不属于当前租户：" + id);
    }
    return r;
  }

  private void requireSettlement(UUID claimId, UUID settlementId) {
    Record r =
        sql.fetchOne(
            "select * from supplier_claim_settlement"
                + " where tenant_id = ?::uuid and id = ?::uuid and claim_id = ?::uuid",
            currentTenant(),
            settlementId,
            claimId);
    if (r == null) {
      throw new IllegalArgumentException("赔付分录不存在或不属于当前租户：" + settlementId);
    }
  }

  /**
   * 状态前置校验：当前状态不在允许集合里就**立刻**报错。
   *
   * <p>⚠️ 刻意不只靠 UPDATE 的 WHERE 子句：那样状态不匹配时会**静默不改**（0 行受影响），
   * 调用方拿到的是「成功」但其实什么都没发生 —— 界面会显示「已撤回」而单子还在原地。
   */
  private void requireStatus(Record head, java.util.Set<SupplierClaimStatus> allowed, String what) {
    SupplierClaimStatus status =
        SupplierClaimStatus.valueOf(head.get("claim_status", String.class));
    if (!allowed.contains(status)) {
      throw new IllegalArgumentException(
          "索赔单当前是「" + status.label() + "」，不能" + what
              + "（允许的状态："
              + allowed.stream().map(SupplierClaimStatus::label).toList()
              + "）");
    }
  }

  /** UPDATE 必须真的改到行：0 行受影响说明状态已经变了，不能当成「成功」。 */
  private void requireChanged(int affected, String what) {
    if (affected == 0) {
      throw new IllegalArgumentException(
          what + "没有生效：索赔单的状态已经被别处改动过（或已进终态），请刷新后重试");
    }
  }

  private void requireDraft(Record head) {
    String status = head.get("claim_status", String.class);
    if (!"DRAFT".equals(status)) {
      throw new IllegalArgumentException(
          "索赔单「" + status + "」不能改明细（已提交的索赔金额是发给供应商的数，改了要重发）："
              + head.get("id", UUID.class));
    }
  }

  private void requireVersion(long current, Long expected, String what) {
    if (expected == null) {
      throw new IllegalArgumentException("必须给出 expectedVersion（乐观锁，避免两个人同时改同一张索赔单互相覆盖）");
    }
    if (expected != current) {
      throw new IllegalArgumentException(
          what + "版本不符（当前 " + current + "，请求 " + expected + "），请刷新后重试");
    }
  }

  /** 「达成一致」那条协商记录里的达成金额（全库唯一的出处）。 */
  private BigDecimal agreedAmountOf(UUID claimId) {
    BigDecimal agreed =
        sql.fetchOne(
                "select max(agreed_amount) from supplier_claim_negotiation"
                    + " where tenant_id = ?::uuid and claim_id = ?::uuid"
                    + " and negotiation_type = 'AGREEMENT' and agreed_amount is not null",
                currentTenant(),
                claimId)
            .get(0, BigDecimal.class);
    if (agreed == null) {
      throw new IllegalArgumentException(
          "还没有「达成一致」的协商记录，不能置为已达成（先记一条 type=AGREEMENT 的协商记录）");
    }
    return agreed;
  }

  /** 归因单的责任环节（明细行要显示「为什么这批货能索赔」）。 */
  private String attributionStageOf(UUID attributionId) {
    if (attributionId == null) {
      return null;
    }
    Record r =
        sql.fetchOne(
            "select attributed_stage from product_issue_attribution"
                + " where tenant_id = ?::uuid and id = ?::uuid",
            currentTenant(),
            attributionId);
    return r == null ? null : r.get("attributed_stage", String.class);
  }

  private static SupplierClaimRow mapClaimRow(Record r) {
    String status = r.get("claim_status", String.class);
    String reason = r.get("claim_reason_code", String.class);
    String offset = r.get("offset_status", String.class);
    return new SupplierClaimRow(
        r.get("id", UUID.class),
        r.get("claim_no", String.class),
        r.get("supplier_ref_id", UUID.class),
        r.get("supplier_code", String.class),
        r.get("supplier_name", String.class),
        reason,
        SupplierClaimReason.valueOf(reason).label(),
        r.get("claim_currency", String.class),
        r.get("claimed_amount", BigDecimal.class),
        r.get("claimable_base_amount", BigDecimal.class),
        r.get("agreed_amount", BigDecimal.class),
        status,
        SupplierClaimStatus.valueOf(status).label(),
        offset,
        SupplierOffsetStatus.valueOf(offset).label(),
        r.get("applied_offset_amount", BigDecimal.class),
        r.get("item_count", Long.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("submitted_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class),
        r.get("version", Long.class));
  }

  /** 当前可执行动作：每个不可执行的动作都带**为什么**。 */
  private List<SupplierClaimAction> actionsOf(
      Record head,
      int itemCount,
      List<SupplierClaimNegotiationRow> negotiations,
      List<SupplierClaimSettlementRow> settlements) {
    String status = head.get("claim_status", String.class);
    SupplierClaimStatus st = SupplierClaimStatus.valueOf(status);
    UUID id = head.get("id", UUID.class);
    BigDecimal claimed = head.get("claimed_amount", BigDecimal.class);
    long version = head.get("version", Long.class);
    boolean draft = st == SupplierClaimStatus.DRAFT;
    boolean submitted = st == SupplierClaimStatus.SUBMITTED;
    boolean negotiating = st == SupplierClaimStatus.NEGOTIATING;
    boolean agreed = st == SupplierClaimStatus.AGREED;
    boolean pendingSettlement =
        settlements.stream().anyMatch(s -> "PENDING_FINANCE_CONFIRM".equals(s.settlementStatus()));
    boolean hasAgreement =
        negotiations.stream().anyMatch(n -> "AGREEMENT".equals(n.negotiationType()));

    List<SupplierClaimAction> actions = new ArrayList<>();
    actions.add(
        action(
            "ADD_ITEM",
            "加入索赔明细",
            draft,
            "POST",
            "/trade/supplier-claims/" + id + "/items",
            false,
            "仅草稿",
            draft ? null : "索赔单已是「" + st.label() + "」，明细不能再改"));
    actions.add(
        action(
            "SUBMIT",
            "提交索赔",
            draft && itemCount > 0 && claimed != null && claimed.signum() > 0,
            "POST",
            "/trade/supplier-claims/" + id + "/submit",
            false,
            "仅草稿，且至少一条明细、金额 > 0",
            submitBlocked(draft, itemCount, claimed)));
    actions.add(
        action(
            "RECORD_NEGOTIATION",
            "记录协商",
            submitted || negotiating,
            "POST",
            "/trade/supplier-claims/" + id + "/negotiations",
            false,
            "仅已提交 / 协商中",
            (submitted || negotiating) ? null : "协商只发生在已提交与协商中两个状态"));
    actions.add(
        action(
            "AGREE",
            "置为已达成",
            negotiating && hasAgreement,
            "POST",
            "/trade/supplier-claims/" + id + "/agree",
            false,
            "仅协商中，且已有一条「达成一致」的协商记录",
            negotiating
                ? (hasAgreement ? null : "还没有「达成一致」的协商记录")
                : "只有协商中的索赔单可以置为已达成"));
    actions.add(
        action(
            "REGISTER_SETTLEMENT",
            "登记赔付",
            agreed && !pendingSettlement,
            "POST",
            "/trade/supplier-claims/" + id + "/settlements",
            false,
            "仅已达成；登记后**等待财务确认才生效**",
            agreed
                ? (pendingSettlement ? "已有一条待财务确认的赔付分录（重复登记是幂等的，不会重复冲减）" : null)
                : "只有已达成一致的索赔单才能登记赔付"));
    actions.add(
        action(
            "CONFIRM_SETTLEMENT",
            "财务确认冲减",
            pendingSettlement,
            "POST",
            "/trade/supplier-claims/" + id + "/settlements/{settlementId}/confirm",
            true,
            "仅待财务确认；需回填财务单据号",
            pendingSettlement ? null : "没有待财务确认的赔付分录"));
    actions.add(
        action(
            "REJECT_CLAIM",
            "驳回索赔",
            submitted || negotiating,
            "POST",
            "/trade/supplier-claims/" + id + "/reject",
            true,
            "仅已提交 / 协商中；必须写理由",
            (submitted || negotiating) ? null : "只有已提交或协商中的索赔单可以驳回"));
    actions.add(
        action(
            "WITHDRAW_CLAIM",
            "撤回索赔",
            draft || submitted || negotiating,
            "POST",
            "/trade/supplier-claims/" + id + "/withdraw",
            true,
            "仅草稿 / 已提交 / 协商中；必须写理由",
            (draft || submitted || negotiating) ? null : "已达成或已终结的索赔单不能撤回"));
    return actions;
  }

  private static String submitBlocked(boolean draft, int itemCount, BigDecimal claimed) {
    if (!draft) {
      return "只有草稿可以提交";
    }
    if (itemCount == 0) {
      return "还没有明细：发出去的索赔单要能被对方看懂要赔哪几批货";
    }
    if (claimed == null || claimed.signum() <= 0) {
      return "索赔金额必须大于 0";
    }
    return null;
  }

  // ================================================================ 小工具

  private UUID currentTenant() {
    return UUID.fromString(sql.fetchOne("select current_setting('app.current_tenant')").get(0, String.class));
  }

  /** 金额：4 位小数，禁浮点。 */
  private static BigDecimal money(BigDecimal value, String label) {
    if (value == null) {
      throw new IllegalArgumentException("必须给出" + label);
    }
    BigDecimal scaled = value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    if (scaled.signum() < 0) {
      throw new IllegalArgumentException(label + "不能为负：" + value);
    }
    return scaled;
  }

  /** 数量：6 位小数，禁浮点。 */
  private static BigDecimal qty(BigDecimal value, String label) {
    if (value == null) {
      throw new IllegalArgumentException("必须给出" + label);
    }
    BigDecimal scaled = value.setScale(QTY_SCALE, RoundingMode.HALF_UP);
    if (scaled.signum() <= 0) {
      throw new IllegalArgumentException(label + "必须大于 0：" + value);
    }
    return scaled;
  }

  private static String requireCurrency(String raw) {
    if (!isSet(raw)) {
      throw new IllegalArgumentException("必须给出索赔币种（索赔的是那批货的钱，币种跟着那批货走）");
    }
    String currency = raw.trim().toUpperCase(java.util.Locale.ROOT);
    if (!currency.matches("^[A-Z]{3}$")) {
      throw new IllegalArgumentException("币种必须是三位大写字母：" + raw);
    }
    return currency;
  }

  private static <E extends Enum<E>> E parseEnum(Class<E> type, String raw, String label) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException("必须给出" + label);
    }
    try {
      return Enum.valueOf(type, raw.trim());
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          label + "取值非法：" + raw + "（可选：" + java.util.Arrays.toString(type.getEnumConstants()) + "）");
    }
  }

  private static boolean isSet(String value) {
    return value != null && !value.isBlank();
  }

  private static String trimOrEmpty(String value) {
    return isSet(value) ? value.trim() : "";
  }

  private static String stageLabel(String stage) {
    if (stage == null) {
      return "";
    }
    return switch (stage) {
      case "PRODUCTION" -> "生产";
      case "DESCRIPTION" -> "描述不符";
      case "TRANSPORT" -> "运输";
      case "WAREHOUSE" -> "仓储";
      case "BUYER_FACTOR" -> "买家因素";
      case "UNDETERMINED" -> "责任待定";
      default -> stage;
    };
  }

  private static String eventLabel(String code) {
    return switch (code) {
      case "CREATED" -> "登记索赔草稿";
      case "ITEM_ADDED" -> "加入索赔明细";
      case "ITEM_REMOVED" -> "移除索赔明细";
      case "SUBMITTED" -> "提交索赔";
      case "NEGOTIATION_STARTED" -> "进入协商";
      case "NEGOTIATION_RECORDED" -> "记录协商";
      case "AGREED" -> "协商达成一致";
      case "REJECTED" -> "驳回索赔";
      case "WITHDRAWN" -> "撤回索赔";
      case "SETTLEMENT_REGISTERED" -> "登记赔付";
      case "SETTLEMENT_CONFIRMED" -> "财务确认冲减";
      case "SETTLEMENT_REJECTED" -> "财务驳回赔付";
      case "SETTLED" -> "已结算";
      default -> code;
    };
  }

  private static SupplierClaimSectionCapability section(
      String section, String label, boolean available, String ownerModule, String reason,
      String landsIn) {
    return new SupplierClaimSectionCapability(section, label, available, ownerModule, reason, landsIn);
  }

  private static SupplierClaimRelatedModule related(
      String id, String label, boolean delivered, String ownerModule, String reason, String href) {
    return new SupplierClaimRelatedModule(id, label, delivered, ownerModule, reason, href);
  }

  private static SupplierClaimCapabilityAction action(
      String code, String label, boolean allowed, String method, String path, boolean required,
      String precondition, String blockingReason, String ownerModule, String unavailableReason) {
    return new SupplierClaimCapabilityAction(
        code, label, allowed, method, path, required, precondition, blockingReason, ownerModule,
        unavailableReason);
  }

  private static SupplierClaimBoundary boundary(String boundary, String ownerModule, String reason) {
    return new SupplierClaimBoundary(boundary, ownerModule, reason);
  }

  private static SupplierClaimAction action(
      String code,
      String label,
      boolean allowed,
      String method,
      String path,
      boolean required,
      String precondition,
      String blockedReason) {
    return new SupplierClaimAction(
        code, label, allowed, method, path, required, precondition, blockedReason);
  }

  /** 索赔单排序白名单。 */
  private enum SupplierClaimSort {
    CREATED_DESC("c.created_at desc, c.id desc"),
    SUBMITTED_ASC("c.submitted_at asc nulls last, c.id desc"),
    AMOUNT_DESC("c.claimed_amount desc nulls last, c.id desc"),
    OFFSET_ASC("c.applied_offset_amount asc nulls last, c.created_at desc, c.id desc"),
    SUPPLIER_ASC("c.supplier_code, c.created_at desc, c.id desc");

    private final String sql;

    SupplierClaimSort(String sql) {
      this.sql = sql;
    }

    String sql() {
      return sql;
    }

    static SupplierClaimSort parse(String raw) {
      if (raw == null) {
        return CREATED_DESC;
      }
      return switch (raw) {
        case "created_desc" -> CREATED_DESC;
        case "submitted_asc" -> SUBMITTED_ASC;
        case "amount_desc" -> AMOUNT_DESC;
        case "offset_asc" -> OFFSET_ASC;
        case "supplier_asc" -> SUPPLIER_ASC;
        default -> CREATED_DESC;
      };
    }
  }
}
