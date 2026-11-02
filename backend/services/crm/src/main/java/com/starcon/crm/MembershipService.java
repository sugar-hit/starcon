package com.starcon.crm;

import static com.starcon.crm.MembershipModels.*;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.starcon.crm.MembershipOrderFactsPort.OrderFact;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 15.6.1.1 会员与等级。
 *
 * <p>蓝图 15 卷 15.6.1.1 原话：
 *
 * <blockquote>
 * 职责：管理会员体系：会员注册与合并、等级规则（按累计消费/订单数/复购率自动升级降级）、等级权益（折扣、免邮、优先发货），支持与平台会员体系对接或独立建档。
 * 输入：订单与消费数据（9）；等级规则配置；平台会员数据同步。
 * 输出：会员档案 + 等级变更记录；权益生效配置（→ 5 价目 / 14 促销）。
 * 关系：上游 = 9 订单、15.3.1.2 客户档案；下游 = 5 定价、14 促销、15.6.2 复购。**会员等级必须由真实消费数据驱动**，手工挂等级会导致权益成本失控。
 * </blockquote>
 *
 * <p><b>本类落五条纪律</b>：
 *
 * <ol>
 *   <li><b>等级只能算出来，不能写进去</b>：本类<b>没有任何</b>接受「等级代码」入参的方法，
 *       等级只由 {@link #compute} 从上游真实订单事实算出。人工覆盖通道**未交付**
 *       （见 {@link #capabilities()}），DB 层还有一个触发器直接拒绝它。</li>
 *   <li><b>判定基于「拉取到的事实」</b>：订单事实经 {@link MembershipOrderFactsPort}
 *       从 trade 的授权 HTTP API 读；读不到就<b>显式失败</b>（503），
 *       绝不返回空集合冒充「这个租户没有订单」—— 那会让全员掉到 {@code NONE}。</li>
 *   <li><b>阈值是租户数据</b>：等级门槛落在 {@code crm_membership_level_rule}
 *       （带生效区间）。<b>代码里没有任何写死的门槛</b>。</li>
 *   <li><b>每一次判定都要能解释</b>：会员档案带 {@code levelReason}，
 *       等级变更记录带 {@code basis}（判定依据快照）。</li>
 *   <li><b>跨服务纪律</b>：上游 9 订单在 trade —— 本服务<b>一条 SQL 都不碰</b>对方的库；
 *       本库只存逻辑引用 + 读数，<b>不建跨库 FK</b>。</li>
 * </ol>
 */
@Service
public class MembershipService {

  // ================================================================ 常量

  /** 列表页最大条数（与本服务其它模块同一上限）。 */
  static final int MAX_PAGE_SIZE = 200;

  /** 一次重算最多落多少位会员（超出则如实报 truncated）。 */
  static final int MAX_MEMBERS_PER_RUN = 5000;

  /** 「未达任何等级」的等级代码 —— **一等状态**，不是「普通会员」。 */
  static final String LEVEL_NONE = "NONE";

  /** 金额与比率的标度（与 15.3.2.1 的同一口径）。 */
  static final int RATE_SCALE = 6;

  /**
   * 金额出参的**最少小数位**。
   *
   * <p>金额的业务精度是「分」，所以至少给两位；但库里 {@code numeric(19,4)} 可能存着
   * {@code 66.605} 这种更细的值 —— 那时**照原样出**（去尾零后仍有三位就出三位），
   * <b>不四舍五入</b>：出参显示的必须是「库里那个数」，不是「四舍五入后的另一个数」。
   */
  static final int MONEY_MIN_SCALE = 2;

  /**
   * 时刻出参格式：**统一换算成 UTC**，秒必写，毫秒/微秒有值才写，结尾恒为 {@code Z}。
   *
   * <p>🔴 为什么换算成 UTC：同一个绝对时刻用不同偏移量写出来会是两个字符串
   * （{@code 2026-10-10T08:00+08:00} 与 {@code 2026-10-10T00:00Z} 是同一时刻），
   * 拿字符串比大小 / 比相等都会得出「不是同一个时刻」的错结论。
   * 换算成 UTC 之后，<b>同一时刻只有一种写法</b>，跨时区协作不会踩坑。
   */
  private static final DateTimeFormatter MOMENT_TEXT =
      new DateTimeFormatterBuilder()
          .appendPattern("yyyy-MM-dd'T'HH:mm:ss")
          .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
          .appendLiteral('Z')
          .toFormatter()
          .withZone(ZoneOffset.UTC);

  private final DSLContext sql;
  private final MembershipOrderFactsPort orders;

  public MembershipService(DSLContext sql, MembershipOrderFactsPort orders) {
    this.sql = sql;
    this.orders = orders;
  }

  /** 判定依据快照的 JSON mapper：BigDecimal 按原样写出（不转成浮点）。 */
  private static final ObjectMapper JSON =
      com.fasterxml.jackson.databind.json.JsonMapper.builder()
          .addModule(new JavaTimeModule())
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .enable(SerializationFeature.WRITE_BIGDECIMAL_AS_PLAIN)
          .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
          .build();

  // ================================================================ 能力声明

  /**
   * 能力声明：每一维说清「有没有、依据是什么、缺了会怎样」。
   *
   * <p>🔴 三个维度刻意是 {@code available=false}：
   *
   * <ul>
   *   <li>{@code manual_level_override} —— <b>人工覆盖等级本轮未交付</b>：
   *       蓝图加粗句「手工挂等级会导致权益成本失控」。本模块<b>没有</b>任何
   *       能直接写等级的端点，DB 层也拒绝 {@code level_origin='MANUAL_OVERRIDE'}。</li>
   *   <li>{@code platform_membership_sync} —— <b>平台会员体系对接未交付</b>：
   *       channel 服务没有平台会员接口。本模块交付的是<b>独立建档</b>口径 ——
   *       会员档案由租户真实消费数据驱动。</li>
   *   <li>{@code points_and_redemption} —— 15.6.1.2 积分与权益<b>未交付</b>：
   *       本模块只管等级与等级权益（折扣 / 免邮 / 优先发货），不管积分。</li>
   * </ul>
   */
  public CrmMembershipCapabilities capabilities() {
    List<CrmMembershipCapabilityDimension> dimensions =
        List.of(
            new CrmMembershipCapabilityDimension(
                "order_facts",
                "订单与消费数据（累计消费 / 订单数 / 复购率的原料）",
                true,
                "上游 trade 9.1.1.1 全渠道统一订单列表，经授权 HTTP API 只读"
                    + "（GET /trade/sales-orders/list）",
                "9",
                "读不到一律 503，**绝不**返回空集合冒充「这个租户没有订单」——"
                    + "那会让全员掉到 NONE，等于把「读不到」当成「消费归零」"),
            new CrmMembershipCapabilityDimension(
                "customer_archive",
                "客户档案（会员挂在客户档案之下）",
                true,
                "本服务 V432 crm_customer_profile + V437 crm_customer_merge_mapping（**只读**）",
                "15.3.1.2",
                "本模块**一个字都不写**客户档案的表；合并后的会员主键取自 15.3.1.3 的逻辑客户号"),
            new CrmMembershipCapabilityDimension(
                "level_rules",
                "等级规则（按累计消费 / 订单数 / 复购率，带生效区间）",
                true,
                "本服务 V440 crm_membership_level_rule（**租户数据**，不是代码常量）",
                "",
                "门槛全部可配、可留空（只配一维也成立）；**代码里没有任何写死的门槛**。"
                    + "改门槛要新建一条规则（新的生效起点），旧的那条被关掉"),
            new CrmMembershipCapabilityDimension(
                "level_benefits",
                "等级权益（折扣 / 免邮 / 优先发货）",
                true,
                "本服务 V440 crm_membership_level_rule 的权益列 + GET /benefit-config 输出",
                "5 / 14",
                "本模块**只输出**权益生效配置，**不改**价目表 / 促销表（跨服务禁止）"),
            new CrmMembershipCapabilityDimension(
                "level_change_log",
                "等级变更记录（升级 / 降级 / 首次定级）",
                true,
                "本服务 V440 crm_membership_level_change（只追加，不删）",
                "",
                "每条变更带**判定依据快照**（basis）与变更理由；等级**不会回滚**，历史要能复核"),
            new CrmMembershipCapabilityDimension(
                "manual_level_override",
                "人工覆盖等级（直接指定某人是黄金会员）",
                false,
                "人工覆盖等级本轮未交付 —— 蓝图加粗句「手工挂等级会导致权益成本失控」",
                "15.6.1.1",
                "本模块**没有**任何能直接写等级的端点；DB 层触发器也拒绝 "
                    + "level_origin='MANUAL_OVERRIDE'。等级来源恒为 RULE"),
            new CrmMembershipCapabilityDimension(
                "platform_membership_sync",
                "与平台会员体系对接（Amazon / Shopify 会员等级同步）",
                false,
                "**channel 服务未交付平台会员接口**",
                "channel",
                "本模块交付的是**独立建档**口径：会员档案由租户真实消费数据驱动。"
                    + "平台侧会员等级**不回写**本服务（那会绕过「真实消费数据」这条硬约束）"),
            new CrmMembershipCapabilityDimension(
                "points_and_redemption",
                "积分获取 / 消耗与权益核销",
                false,
                "**15.6.1.2 积分与权益未交付**",
                "15.6.1.2",
                "本模块只管等级与等级权益；积分是另一格，本模块不代劳"));
    return new CrmMembershipCapabilities(
        dimensions,
        java.util.Arrays.stream(MembershipLevelOrigin.values()).map(Enum::name).toList(),
        java.util.Arrays.stream(MembershipChangeKind.values()).map(Enum::name).toList(),
        "🔴 **等级门槛是租户数据**（crm_membership_level_rule），可配、可留空、带生效区间。"
            + "代码里**没有任何写死的门槛**：不配规则就没有等级，全员 NONE。"
            + "三条门槛的口径是：累计消费 = 窗口内该币种下的消费合计；"
            + "订单数 = 窗口内订单总单数；"
            + "复购率 = 窗口内「非首单订单数 ÷ 订单数」（窗口内没订单时**不可判定**，不是 0）。",
        "会员主键：已合并（15.3.1.3 跨店合并）= 逻辑客户号；未合并 = `来源店铺:客户标识`。"
            + "上游 9 订单在 trade —— 本服务**只经授权 HTTP API 读**，"
            + "一条跨库 SQL / 跨库 FK / 跨库视图都没有。"
            + "本模块**只输出**权益生效配置给 5 定价 / 14 促销，不代劳改价目表或促销表。",
        "「会员等级必须由真实消费数据驱动」在三层落地："
            + "① 服务端**没有**任何接受等级代码的写路径；"
            + "② DB 层触发器拒绝人工覆盖（level_origin 只允许 RULE）；"
            + "③ 每条会员档案带判定理由、每条变更带依据快照，"
            + "报表按 level_origin 就能区分「规则算出来的」与「人工覆盖的」。");
  }

  // ================================================================ 会员档案列表

  /**
   * 会员档案列表：真分页（{@code LIMIT/OFFSET} + 稳定全序）+ 与分页无关的统计。
   */
  public CrmMembershipMemberPage memberPage(
      String levelCode,
      String sourceShop,
      String keyword,
      int page,
      int size,
      AccessService.Access actor) {

    List<Object> binds = new ArrayList<>();
    String where = memberWhere(levelCode, sourceShop, keyword, binds);

    long total =
        sql.fetchOne("select count(*) from crm_membership_profile m" + where, binds.toArray())
            .get(0, Long.class);

    int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    int safePage = Math.max(page, 0);

    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(safeSize);
    pageBinds.add(safePage * safeSize);

    List<Record> records =
        sql.fetch(
            "select m.* from crm_membership_profile m"
                + where
                + " order by m.level_rank desc, m.total_spend desc nulls last, m.member_key asc"
                + " limit ? offset ?",
            pageBinds.toArray());

    CrmMembershipStats stats = memberStats(where, binds);
    int totalPages = total <= 0 ? 0 : (int) ((total + safeSize - 1) / safeSize);
    return new CrmMembershipMemberPage(
        memberRows(records),
        safePage,
        safeSize,
        total,
        totalPages,
        stats,
        dataScopeOf(actor),
        "会员档案是**租户级**的（没有「归属人」这一维），所以 SELF / DEPARTMENT 两种范围"
            + "在会员档案上退化为租户级 —— 会员不按人切分。");
  }

  /** 会员列表的 WHERE 片段（列表与统计**共用同一份**，保证「统计的就是筛出来的」）。 */
  private String memberWhere(
      String levelCode, String sourceShop, String keyword, List<Object> binds) {
    StringBuilder where = new StringBuilder(" where m.tenant_id = ?");
    binds.add(currentTenant());
    if (blankToNull(levelCode) != null) {
      where.append(" and m.level_code = ?");
      binds.add(levelCode.trim().toUpperCase(Locale.ROOT));
    }
    if (blankToNull(sourceShop) != null) {
      where.append(" and m.source_shop = ?");
      binds.add(sourceShop.trim());
    }
    if (blankToNull(keyword) != null) {
      where.append(
          " and (strpos(lower(m.customer_ref), lower(?)) > 0"
              + " or strpos(lower(m.customer_name), lower(?)) > 0"
              + " or strpos(lower(m.member_key), lower(?)) > 0)");
      String kw = keyword.trim();
      binds.add(kw);
      binds.add(kw);
      binds.add(kw);
    }
    return where.toString();
  }

  /** 单个会员详情（会员档案 + 该会员的等级变更记录）。 */
  public CrmMembershipMemberDetail memberDetail(UUID id) {
    Record record =
        sql.fetchOne(
            "select * from crm_membership_profile where tenant_id = ? and id = ?",
            currentTenant(),
            id);
    if (record == null) throw notFound("会员档案不存在：" + id);
    List<CrmMembershipLevelChangeRow> changes =
        sql.fetch(
                "select * from crm_membership_level_change where tenant_id = ? and member_key = ?"
                    + " order by changed_at desc, id desc",
                currentTenant(),
                record.get("member_key", String.class))
            .stream()
            .map(this::levelChangeRow)
            .toList();
    return new CrmMembershipMemberDetail(memberRow(record), changes);
  }

  /**
   * 会员统计：与分页无关（单独一条统计查询，不是拿当前页聚合出来的）。
   *
   * <p>🔴 统计与列表**共用同一份 WHERE**：统计的就是筛出来的。
   */
  private CrmMembershipStats memberStats(String where, List<Object> whereBinds) {
    long total =
        sql.fetchOne(
                "select count(*) from crm_membership_profile m" + where, whereBinds.toArray())
            .get(0, Long.class);

    List<Object> unmatchedBinds = new ArrayList<>(whereBinds);
    unmatchedBinds.add(LEVEL_NONE);
    long unmatched =
        sql.fetchOne(
                "select count(*) from crm_membership_profile m" + where + " and m.level_code = ?",
                unmatchedBinds.toArray())
            .get(0, Long.class);

    // 升级 / 降级次数：**限定在筛出来的会员之内**（否则「统计的就是筛出来的」就是假的）。
    String memberKeys = "select m.member_key from crm_membership_profile m" + where;
    long upgraded = countChanges("UPGRADE", memberKeys, whereBinds);
    long downgraded = countChanges("DOWNGRADE", memberKeys, whereBinds);

    List<Object> byLevelBinds = new ArrayList<>(whereBinds);
    List<CrmMembershipLevelBucket> byLevel =
        sql.fetch(
                "select m.level_code, min(m.level_rank) as level_rank, count(*) as member_count"
                    + " from crm_membership_profile m"
                    + where
                    + " group by m.level_code order by min(m.level_rank) desc, m.level_code asc",
                byLevelBinds.toArray())
            .stream()
            .map(
                r ->
                    new CrmMembershipLevelBucket(
                        nz(r.get("level_code", String.class)),
                        levelNameOf(currentTenant(), nz(r.get("level_code", String.class))),
                        r.get("member_count", Long.class) == null ? 0L : r.get("member_count", Long.class)))
            .toList();

    // 分币种消费合计：**一币种一行**，绝不跨币种相加。
    // ⚠️ `jsonb_array_elements(...) as a(doc)`：**必须**给输出列起名字 ——
    //   直接写 `as a` 时 `a` 是表别名、列名仍是函数名，`a->>'currency'` 会报语法错。
    List<Object> spendBinds = new ArrayList<>(whereBinds);
    List<CrmMembershipAmount> spendByCurrency =
        sql.fetch(
                "select a.doc->>'currency' as currency,"
                    + " sum((a.doc->>'orderCount')::numeric)::int as order_count,"
                    + " sum((a.doc->>'totalSpend')::numeric) as total_spend"
                    + " from crm_membership_profile m,"
                    + " lateral jsonb_array_elements(m.amounts) as a(doc)"
                    + where
                    + " group by a.doc->>'currency' order by a.doc->>'currency' asc",
                spendBinds.toArray())
            .stream()
            .map(
                r ->
                    new CrmMembershipAmount(
                        nz(r.get("currency", String.class)),
                        r.get("order_count", Integer.class) == null ? 0 : r.get("order_count", Integer.class),
                        moneyText(r.get("total_spend", BigDecimal.class))))
            .toList();

    return new CrmMembershipStats(
        total,
        unmatched,
        upgraded,
        downgraded,
        byLevel,
        spendByCurrency,
        "🔴 统计口径：会员档案按**租户**计（没有「归属人」这一维）；"
            + "「未达任何等级」是**一等状态**（level_code='NONE'），不是「普通会员」；"
            + "金额**按币种分行**（跨币种的消费不能加成一个数）。"
            + "升级 / 降级次数统计的是**变更记录条数**，且**限定在当前筛选出来的会员之内**。");
  }

  /** 统计某一类等级变更的条数（限定在筛出来的会员之内）。 */
  private long countChanges(String kind, String memberKeys, List<Object> whereBinds) {
    List<Object> binds = new ArrayList<>();
    binds.add(currentTenant());
    binds.add(kind);
    binds.addAll(whereBinds);
    return sql.fetchOne(
            "select count(*) from crm_membership_level_change c"
                + " where c.tenant_id = ? and c.change_kind = ?"
                + " and c.member_key in (" + memberKeys + ")",
            binds.toArray())
        .get(0, Long.class);
  }

  // ================================================================ 等级规则

  /** 等级规则列表（真分页）。 */
  public CrmMembershipLevelRulePage levelRulePage(String levelCode, int page, int size) {
    List<Object> binds = new ArrayList<>();
    StringBuilder where = new StringBuilder(" where tenant_id = ?");
    binds.add(currentTenant());
    if (blankToNull(levelCode) != null) {
      where.append(" and level_code = ?");
      binds.add(levelCode.trim().toUpperCase(Locale.ROOT));
    }
    long total =
        sql.fetchOne("select count(*) from crm_membership_level_rule" + where, binds.toArray())
            .get(0, Long.class);
    int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    int safePage = Math.max(page, 0);
    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(safeSize);
    pageBinds.add(safePage * safeSize);
    List<Record> records =
        sql.fetch(
            "select * from crm_membership_level_rule"
                + where
                + " order by level_rank desc, effective_from desc, id desc limit ? offset ?",
            pageBinds.toArray());
    int totalPages = total <= 0 ? 0 : (int) ((total + safeSize - 1) / safeSize);
    return new CrmMembershipLevelRulePage(
        records.stream().map(this::levelRuleRow).toList(),
        safePage,
        safeSize,
        total,
        totalPages);
  }

  /** 单条等级规则。 */
  public CrmMembershipLevelRuleRow levelRule(UUID id) {
    Record record =
        sql.fetchOne(
            "select * from crm_membership_level_rule where tenant_id = ? and id = ?",
            currentTenant(),
            id);
    if (record == null) throw notFound("等级规则不存在：" + id);
    return levelRuleRow(record);
  }

  /**
   * 新建一条等级规则（+ 等级权益）。
   *
   * <p>🔴 <b>同一等级代码同时只能有一条「开着」的规则</b>：新建会把上一条关掉
   * （{@code effective_to = 新的生效起点}）—— 否则同一个等级代码会同时有两条生效规则，
   * 「哪条说了算」就成了猜。
   */
  @Transactional
  public CrmMembershipLevelRuleRow createLevelRule(CrmMembershipLevelRuleInput input) {
    String code = input.levelCode().trim().toUpperCase(Locale.ROOT);
    String name = input.levelName().trim();
    if (code.isEmpty()) throw bad("等级代码不能为空。");
    if (name.isEmpty()) throw bad("等级名称不能为空。");
    if (input.minTotalSpend() != null && blankToNull(input.minTotalSpendCurrency()) == null) {
      throw bad("给了消费门槛就必须同时给出币种：跨币种的金额不能比。");
    }
    if (input.minTotalSpend() != null && input.minTotalSpend().signum() < 0) {
      throw bad("消费门槛不能为负。");
    }
    if (input.discountRate() != null
        && (input.discountRate().signum() < 0 || input.discountRate().compareTo(BigDecimal.ONE) >= 0)) {
      throw bad("折扣率必须在 0（含）到 1（不含）之间：0.05 表示减免 5%。");
    }
    if (input.minRepurchaseRate() != null
        && (input.minRepurchaseRate().signum() < 0
            || input.minRepurchaseRate().compareTo(BigDecimal.ONE) > 0)) {
      throw bad("复购率门槛必须在 0 到 1 之间。");
    }
    OffsetDateTime effectiveFrom = parseMoment(input.effectiveFrom(), "生效起点");
    OffsetDateTime effectiveTo =
        blankToNull(input.effectiveTo()) == null ? null : parseMoment(input.effectiveTo(), "生效终点");
    if (effectiveTo != null && !effectiveFrom.isBefore(effectiveTo)) {
      throw bad("生效终点必须晚于生效起点。");
    }

    // 同一等级代码：关掉仍然「开着」的旧规则（只能关掉起点早于新规则的那条）。
    sql.execute(
        "update crm_membership_level_rule set effective_to = ?::timestamptz, updated_at = ?::timestamptz"
            + " where tenant_id = ? and level_code = ? and effective_to is null"
            + " and effective_from < ?::timestamptz",
        effectiveFrom,
        now(),
        currentTenant(),
        code,
        effectiveFrom);

    UUID id = UUID.randomUUID();
    try {
      sql.execute(
          "insert into crm_membership_level_rule"
              + "(id,tenant_id,level_code,level_name,level_rank,min_total_spend,"
              + " min_total_spend_currency,min_order_count,min_repurchase_rate,discount_rate,"
              + " free_shipping,priority_shipping,enabled,effective_from,effective_to,note,"
              + " created_at,updated_at)"
              + " values (?,?,?,?,?,?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz,?,"
              + " ?::timestamptz,?::timestamptz)",
          id,
          currentTenant(),
          code,
          name,
          input.levelRank(),
          input.minTotalSpend(),
          blankToNull(input.minTotalSpendCurrency()) == null
              ? null
              : input.minTotalSpendCurrency().trim().toUpperCase(Locale.ROOT),
          input.minOrderCount(),
          input.minRepurchaseRate(),
          input.discountRate(),
          input.freeShipping() != null && input.freeShipping(),
          input.priorityShipping() != null && input.priorityShipping(),
          input.enabled() == null || input.enabled(),
          effectiveFrom,
          effectiveTo,
          nz(input.note()),
          now(),
          now());
    } catch (DataIntegrityViolationException e) {
      throw conflict(
          "这个等级代码在这个生效起点上已经有一条规则了：请换一个生效起点，"
              + "或改用 PUT 关掉当前生效的那一条。");
    }
    return levelRule(id);
  }

  /**
   * 改一条等级规则。
   *
   * <p>🔴 <b>门槛与等级序不可就地修改</b>（不可变优先）：改了它，之前引用这条规则的
   * 会员判定就解释不通了。要改门槛请新建一条（新的生效起点）。
   * 这里只允许改「是否启用 / 备注 / 生效终点」。
   */
  @Transactional
  public CrmMembershipLevelRuleRow updateLevelRule(UUID id, CrmMembershipLevelRuleInput input) {
    CrmMembershipLevelRuleRow existing = levelRule(id);
    if (!existing.levelCode().equals(input.levelCode().trim().toUpperCase(Locale.ROOT))) {
      throw conflict("等级代码不可就地修改：请新建一条规则。");
    }
    if (!existing.levelName().equals(input.levelName().trim())) {
      throw conflict("等级名称不可就地修改：请新建一条规则。");
    }
    if (input.levelRank() != existing.levelRank()) {
      throw conflict("等级序不可就地修改：请新建一条规则（新的生效起点）。");
    }
    if (!sameDecimal(input.minTotalSpend(), decimalOf(existing.minTotalSpend()))
        || !sameText(input.minTotalSpendCurrency(), existing.minTotalSpendCurrency())) {
      throw conflict("消费门槛不可就地修改：请新建一条规则（新的生效起点）。");
    }
    if (!sameInteger(input.minOrderCount(), existing.minOrderCount())) {
      throw conflict("订单数门槛不可就地修改：请新建一条规则（新的生效起点）。");
    }
    if (!sameDecimal(input.minRepurchaseRate(), decimalOf(existing.minRepurchaseRate()))) {
      throw conflict("复购率门槛不可就地修改：请新建一条规则（新的生效起点）。");
    }
    // 🔴 权益字段：**没给 = 不改**（局部更新），给了不同值 = 不可就地修改。
    //   「没给」与「要置空」在这里不是一回事：置空权益没有意义（那是一条新规则的事），
    //   而「只改启用状态」是运营最常见的动作 —— 它不该因为没重复抄一遍权益就被拒。
    if (input.discountRate() != null
        && !sameDecimal(input.discountRate(), decimalOf(existing.discountRate()))) {
      throw conflict("折扣权益不可就地修改：请新建一条规则（新的生效起点）—— 权益改动也要留生效区间。");
    }
    if (input.freeShipping() != null
        && input.freeShipping() != existing.freeShipping()) {
      throw conflict("免邮权益不可就地修改：请新建一条规则（新的生效起点）。");
    }
    if (input.priorityShipping() != null
        && input.priorityShipping() != existing.priorityShipping()) {
      throw conflict("优先发货权益不可就地修改：请新建一条规则（新的生效起点）。");
    }
    // 🔴 生效终点：**没给 = 保持原样**。清空它等于「把一条已经关掉的规则重新打开」，
    //   那是改口径（不可变优先），不是「改启用状态」。
    String existingTo = blankToNull(existing.effectiveTo());
    OffsetDateTime effectiveTo;
    if (blankToNull(input.effectiveTo()) == null) {
      effectiveTo = existingTo == null ? null : parseMoment(existingTo, "生效终点");
    } else {
      effectiveTo = parseMoment(input.effectiveTo(), "生效终点");
    }
    if (effectiveTo != null && !effectiveTo.isAfter(parseMoment(existing.effectiveFrom(), "生效起点"))) {
      throw bad("生效终点必须晚于生效起点。");
    }
    sql.execute(
        "update crm_membership_level_rule set enabled = ?, note = ?, effective_to = ?::timestamptz,"
            + " updated_at = ?::timestamptz where tenant_id = ? and id = ?",
        input.enabled() == null ? existing.enabled() : input.enabled(),
        input.note() == null ? existing.note() : nz(input.note()),
        effectiveTo,
        now(),
        currentTenant(),
        id);
    return levelRule(id);
  }

  // ================================================================ 权益生效配置

  /**
   * 权益生效配置 —— 这是本模块交给 **5 定价 / 14 促销** 的东西。
   *
   * <p>🔴 本模块**只输出**这份配置，**不**去改价目表 / 促销表（跨服务禁止）。
   */
  public CrmMembershipBenefitConfig benefitConfig() {
    List<Record> records =
        sql.fetch(
            "select * from crm_membership_level_rule where tenant_id = ? and enabled = true"
                + " and effective_from <= now() and (effective_to is null or effective_to > now())"
                + " order by level_rank desc, level_code asc",
            currentTenant());
    List<CrmMembershipBenefitConfigRow> rows = new ArrayList<>();
    for (Record r : records) {
      BigDecimal discount = r.get("discount_rate", BigDecimal.class);
      boolean freeShipping = Boolean.TRUE.equals(r.get("free_shipping", Boolean.class));
      boolean priority = Boolean.TRUE.equals(r.get("priority_shipping", Boolean.class));
      rows.add(
          new CrmMembershipBenefitConfigRow(
              nz(r.get("level_code", String.class)),
              nz(r.get("level_name", String.class)),
              r.get("level_rank", Integer.class) == null ? 0 : r.get("level_rank", Integer.class),
              rateText(discount),
              freeShipping,
              priority,
              benefitSummary(discount, freeShipping, priority),
              appliesTo(r),
              momentText(r.get("effective_from", OffsetDateTime.class)),
              momentText(r.get("effective_to", OffsetDateTime.class)),
              true,
              List.of("5 定价", "14 促销"),
              nz(r.get("note", String.class))));
    }
    return new CrmMembershipBenefitConfig(
        rows,
        List.of("5 定价（价目表按等级取折扣）", "14 促销（促销按等级取免邮 / 优先发货）"),
        now().toString(),
        "🔴 这是**权益生效配置**，不是折扣执行："
            + "本模块**不改**价目表 / 促销表（跨服务禁止），只把「哪个等级享受什么权益、"
            + "什么条件生效」交给下游。未达任何等级（NONE）的会员**不在本表里** —— "
            + "没有等级就没有权益，把 NONE 写进配置会让下游给所有人打折。");
  }

  // ================================================================ 等级变更记录

  /** 等级变更记录列表（真分页 + 真筛选）。 */
  public CrmMembershipLevelChangePage levelChangePage(
      String memberKey, String changeKind, String keyword, int page, int size) {
    List<Object> binds = new ArrayList<>();
    StringBuilder where = new StringBuilder(" where tenant_id = ?");
    binds.add(currentTenant());
    if (blankToNull(memberKey) != null) {
      where.append(" and member_key = ?");
      binds.add(memberKey.trim());
    }
    if (blankToNull(changeKind) != null) {
      where.append(" and change_kind = ?");
      binds.add(changeKind.trim().toUpperCase(Locale.ROOT));
    }
    if (blankToNull(keyword) != null) {
      where.append(
          " and (strpos(lower(customer_ref), lower(?)) > 0"
              + " or strpos(lower(customer_name), lower(?)) > 0"
              + " or strpos(lower(member_key), lower(?)) > 0"
              + " or strpos(lower(to_level_code), lower(?)) > 0)");
      String kw = keyword.trim();
      binds.add(kw);
      binds.add(kw);
      binds.add(kw);
      binds.add(kw);
    }
    long total =
        sql.fetchOne("select count(*) from crm_membership_level_change" + where, binds.toArray())
            .get(0, Long.class);
    int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    int safePage = Math.max(page, 0);
    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(safeSize);
    pageBinds.add(safePage * safeSize);
    List<Record> records =
        sql.fetch(
            "select * from crm_membership_level_change"
                + where
                + " order by changed_at desc, id desc limit ? offset ?",
            pageBinds.toArray());
    int totalPages = total <= 0 ? 0 : (int) ((total + safeSize - 1) / safeSize);
    return new CrmMembershipLevelChangePage(
        records.stream().map(this::levelChangeRow).toList(),
        safePage,
        safeSize,
        total,
        totalPages);
  }

  // ================================================================ 重算

  /**
   * 从上游订单事实重算会员等级。
   *
   * <p>🔴 四条纪律：
   *
   * <ol>
   *   <li>上游订单事实读不到 ⇒ <b>抛 503</b>，一条会员档案都不落
   *       （绝不返回空集合冒充「这个租户没有订单」）。</li>
   *   <li>统计窗口的起止<b>成对</b>给；只给一个会让窗口两端对不上。
   *       都不给 ⇒ 默认窗口是**全部历史**（累计消费 / 订单数 / 复购率都按全量算）。</li>
   *   <li>窗口外的会员<b>不参与</b>本次判定（原等级与指标保持不变）——
   *       否则「窗口口径变了」会被误读成「消费下降了」。</li>
   *   <li>等级**只能**由规则算出：本方法不接受任何等级入参。</li>
   * </ol>
   */
  @Transactional
  public CrmMembershipComputeResult compute(
      CrmMembershipComputeInput input, UUID actorId, String bearer) {
    OffsetDateTime startedAt = now();
    String fromText = blankToNull(input == null ? null : input.windowFrom());
    String toText = blankToNull(input == null ? null : input.windowTo());
    if ((fromText == null) != (toText == null)) {
      throw bad("统计窗口的起止必须**成对**给：只给一个会让窗口的两端对不上。");
    }
    OffsetDateTime windowTo = startedAt;
    OffsetDateTime windowFrom = null;
    if (fromText != null) {
      windowFrom = parseMoment(fromText, "窗口起点");
      windowTo = parseMoment(toText, "窗口终点");
      if (!windowFrom.isBefore(windowTo)) throw bad("窗口起点必须早于终点。");
    }

    // ---- 1. 拉上游订单事实（读不到 ⇒ 503）
    List<OrderFact> facts = new ArrayList<>();
    boolean truncated = false;
    int page = 0;
    while (true) {
      var p = orders.orders(windowFrom, windowTo, page, bearer);
      facts.addAll(p.rows());
      long consumed = (long) (page + 1) * MembershipOrderFactsPort.PAGE_SIZE;
      if (p.rows().isEmpty() || consumed >= p.total()) break;
      page++;
      if (page >= MembershipOrderFactsPort.MAX_PAGES) {
        truncated = true;
        break;
      }
    }

    // ---- 2. 按「(来源店铺, 客户标识)」聚合成来源客户行
    Map<String, SourceAgg> sources = new LinkedHashMap<>();
    int negativeAmountSkipped = 0;
    for (OrderFact order : facts) {
      if (order.customerPartyId() == null) continue;
      // 只认已提交的订单：草稿单不算购买行为。
      if (!"SUBMITTED".equalsIgnoreCase(nz(order.documentStatus()))) continue;
      String rawShop = nz(order.orderSource()).trim();
      final String shop = rawShop.isEmpty() ? "UNKNOWN" : rawShop;
      String ref = order.customerPartyId().toString();
      SourceAgg agg = sources.computeIfAbsent(key(shop, ref), k -> new SourceAgg(shop, ref));
      agg.add(order);
      if (order.grandTotalAmount() != null && order.grandTotalAmount().signum() < 0) {
        negativeAmountSkipped++;
      }
    }

    // ---- 3. 合并口径（15.3.1.3 跨店合并，**只读**）
    MergeIndex merges = loadMergeIndex();

    // ---- 4. 聚合成「会员」（已合并的合成一个）
    Map<String, MemberAgg> members = new LinkedHashMap<>();
    for (SourceAgg source : sources.values()) {
      MergeInfo merge = merges.bySource.get(key(source.sourceShop, source.customerRef));
      String memberKey =
          merge == null
              ? key(source.sourceShop, source.customerRef)
              : merge.logicalRef;
      MemberAgg member = members.computeIfAbsent(memberKey, k -> new MemberAgg(memberKey));
      member.add(source, merge);
    }
    List<MemberAgg> all = new ArrayList<>(members.values());
    List<MemberAgg> scoped = all;
    if (all.size() > MAX_MEMBERS_PER_RUN) {
      scoped = all.subList(0, MAX_MEMBERS_PER_RUN);
      truncated = true;
    }

    // ---- 5. 生效中的等级规则（按判定时刻 windowTo 取，这是 as-of 口径）
    List<RuleView> rules = new ArrayList<>();
    for (Record r :
        sql.fetch(
            "select * from crm_membership_level_rule where tenant_id = ? and enabled = true"
                + " and effective_from <= ?::timestamptz"
                + " and (effective_to is null or effective_to > ?::timestamptz)"
                + " order by level_rank desc, effective_from desc, level_code asc",
            currentTenant(),
            windowTo,
            windowTo)) {
      rules.add(
          new RuleView(
              r.get("id", UUID.class),
              nz(r.get("level_code", String.class)),
              nz(r.get("level_name", String.class)),
              r.get("level_rank", Integer.class) == null ? 0 : r.get("level_rank", Integer.class),
              r.get("min_total_spend", BigDecimal.class),
              blankToNull(r.get("min_total_spend_currency", String.class)),
              r.get("min_order_count", Integer.class),
              r.get("min_repurchase_rate", BigDecimal.class),
              r.get("discount_rate", BigDecimal.class),
              Boolean.TRUE.equals(r.get("free_shipping", Boolean.class)),
              Boolean.TRUE.equals(r.get("priority_shipping", Boolean.class))));
    }

    // ---- 6. 既有会员档案（用来判断「升级 / 降级 / 首次定级」）
    Map<String, Record> existing = new LinkedHashMap<>();
    for (Record r :
        sql.fetch(
            "select * from crm_membership_profile where tenant_id = ?", currentTenant())) {
      existing.put(nz(r.get("member_key", String.class)), r);
    }

    // ---- 7. 逐会员判定
    int levelChanged = 0;
    int unmatched = 0;
    int currencySkipped = 0;
    for (MemberAgg member : scoped) {
      RuleView matched = null;
      boolean currencySkip = false;
      for (RuleView rule : rules) {
        if (rule.minTotalSpend() != null) {
          if (member.primaryCurrency() == null
              || !member.primaryCurrency().equals(rule.minTotalSpendCurrency())) {
            // 🔴 跨币种不能比：这条规则**对这位会员不适用**，换下一条继续试。
            currencySkip = true;
            continue;
          }
          if (member.totalSpend().compareTo(rule.minTotalSpend()) < 0) continue;
        }
        if (rule.minOrderCount() != null && member.orderCount() < rule.minOrderCount()) continue;
        if (rule.minRepurchaseRate() != null) {
          if (member.repurchaseRate() == null) continue;
          if (member.repurchaseRate().compareTo(rule.minRepurchaseRate()) < 0) continue;
        }
        matched = rule;
        break;
      }
      if (currencySkip) currencySkipped++;
      if (matched == null) unmatched++;

      String levelCode = matched == null ? LEVEL_NONE : matched.code();
      int levelRank = matched == null ? 0 : matched.rank();
      String reason = levelReason(member, matched, rules, currencySkip);

      Record previous = existing.get(member.memberKey);
      String previousLevel = previous == null ? null : nz(previous.get("level_code", String.class));
      int previousRank = previous == null ? 0 : nzInt(previous.get("level_rank", Integer.class));
      boolean firstRegistration = previous == null;
      boolean changed = firstRegistration || !levelCode.equals(previousLevel);

      Map<String, Object> basis = levelBasis(member, matched);

      writeMemberRow(member, levelCode, levelRank, reason, basis, windowFrom, windowTo, matched);

      if (changed) {
        String kind = firstRegistration ? "REGISTERED" : (levelRank > previousRank ? "UPGRADE" : "DOWNGRADE");
        writeLevelChange(
            member,
            previousLevel == null ? LEVEL_NONE : previousLevel,
            previousRank,
            levelCode,
            levelRank,
            kind,
            reason,
            basis,
            matched,
            // 🔴 changed_at = **这次判定发生的时刻**（startedAt），不是窗口终点。
            //   窗口终点是「按哪一刻的口径算」，两件事；用它当变更时刻的话，
            //   同一窗口的多次重算会落在同一个时间点上，列表排序只能靠随机 id 排 ——
            //   「最新的变更排在最前」就变成了掷骰子。
            startedAt,
            actorId);
        levelChanged++;
      }
    }

    // ---- 8. 窗口外的会员：**不参与**本次判定（原档案保持不变）
    //   这是刻意的：把「窗口口径变了」做成「消费下降了」是一次会误导运营的假降级。
    int untouched = existing.size() - scoped.size();

    // ---- 9. 运行记录
    String runNo = nextRunNo();
    UUID runId = UUID.randomUUID();
    Map<String, Object> factsSnapshot = new LinkedHashMap<>();
    factsSnapshot.put("memberCount", scoped.size());
    factsSnapshot.put("sourceCustomerCount", sources.size());
    factsSnapshot.put("levelChangedCount", levelChanged);
    factsSnapshot.put("ruleCount", rules.size());
    factsSnapshot.put("unmatchedCount", unmatched);
    factsSnapshot.put("currencySkippedCount", currencySkipped);
    factsSnapshot.put("negativeAmountSkipped", negativeAmountSkipped);
    factsSnapshot.put("untouchedMembers", untouched);
    factsSnapshot.put("truncated", truncated);
    factsSnapshot.put("windowFrom", windowFrom == null ? "ALL" : momentText(windowFrom));
    factsSnapshot.put("windowTo", momentText(windowTo));

    String failureReason =
        truncated
            ? "🔴 本次扫描**被截断**（订单翻页到上限），名单不是全量"
            : "";
    if (negativeAmountSkipped > 0) {
      failureReason =
          failureReason
              + (failureReason.isEmpty() ? "" : "；")
              + "有 "
              + negativeAmountSkipped
              + " 笔订单金额为负（异常数据），**未计入消费合计**（单数仍计）";
    }

    sql.execute(
        "insert into crm_membership_run"
            + "(id,tenant_id,run_no,status,window_from,window_to,member_count,level_changed_count,"
            + " rule_count,unmatched_count,currency_skipped_count,truncated,failure_reason,facts,"
            + " operator_id,started_at,finished_at,created_at)"
            + " values (?,?,?,'SUCCEEDED',?::timestamptz,?::timestamptz,?,?,?,?,?,?,?,?::jsonb,?,"
            + " ?::timestamptz,?::timestamptz,?::timestamptz)",
        runId,
        currentTenant(),
        runNo,
        windowFrom,
        windowTo,
        scoped.size(),
        levelChanged,
        rules.size(),
        unmatched,
        currencySkipped,
        truncated,
        failureReason,
        toJson(factsSnapshot),
        actorId,
        startedAt,
        now(),
        now());

    return new CrmMembershipComputeResult(
        runId,
        runNo,
        "SUCCEEDED",
        momentText(windowFrom),
        momentText(windowTo),
        scoped.size(),
        levelChanged,
        rules.size(),
        unmatched,
        currencySkipped,
        truncated,
        failureReason,
        momentText(startedAt),
        momentText(now()),
        "会员等级**全部**由上游 trade 的**真实订单事实**算出（授权 HTTP API 只读），"
            + "没有任何手工挂等级的入口。"
            + "本次判定窗口："
            + (windowFrom == null ? "全部历史" : momentText(windowFrom))
            + " → "
            + momentText(windowTo)
            + "。"
            + (rules.isEmpty()
                ? "🔴 租户**还没有配置任何生效中的等级规则**，所有会员都是「未达任何等级」。"
                : "生效中的等级规则 " + rules.size() + " 条。")
            + (unmatched > 0 ? " 有 " + unmatched + " 位会员未达任何等级。" : "")
            + (currencySkipped > 0
                ? " 有 " + currencySkipped + " 位会员因**币种对不上**（消费门槛带了币种）"
                    + "而跳过了带金额门槛的规则。"
                : "")
            + (untouched > 0
                ? " 窗口外还有 " + untouched + " 位会员**未参与**本次判定（等级与指标保持不变）——"
                    + "「窗口口径变了」不算「消费下降」。"
                : ""));
  }

  /** 重算运行记录列表（真分页）。 */
  public CrmMembershipRunPage runPage(int page, int size) {
    long total =
        sql.fetchOne("select count(*) from crm_membership_run where tenant_id = ?", currentTenant())
            .get(0, Long.class);
    int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    int safePage = Math.max(page, 0);
    List<Record> records =
        sql.fetch(
            "select * from crm_membership_run where tenant_id = ?"
                + " order by created_at desc, id desc limit ? offset ?",
            currentTenant(),
            safeSize,
            safePage * safeSize);
    int totalPages = total <= 0 ? 0 : (int) ((total + safeSize - 1) / safeSize);
    return new CrmMembershipRunPage(
        records.stream()
            .map(
                r ->
                    new CrmMembershipRunRow(
                        r.get("id", UUID.class),
                        nz(r.get("run_no", String.class)),
                        nz(r.get("status", String.class)),
                        momentText(r.get("window_from", OffsetDateTime.class)),
                        momentText(r.get("window_to", OffsetDateTime.class)),
                        nzInt(r.get("member_count", Integer.class)),
                        nzInt(r.get("level_changed_count", Integer.class)),
                        nzInt(r.get("rule_count", Integer.class)),
                        nzInt(r.get("unmatched_count", Integer.class)),
                        nzInt(r.get("currency_skipped_count", Integer.class)),
                        Boolean.TRUE.equals(r.get("truncated", Boolean.class)),
                        nz(r.get("failure_reason", String.class)),
                        readJsonObject(r.get("facts", String.class)),
                        blankToNull(
                            r.get("operator_id", UUID.class) == null
                                ? null
                                : r.get("operator_id", UUID.class).toString()),
                        momentText(r.get("started_at", OffsetDateTime.class)),
                        momentText(r.get("finished_at", OffsetDateTime.class))))
            .toList(),
        safePage,
        safeSize,
        total,
        totalPages);
  }

  // ================================================================ 写入

  private void writeMemberRow(
      MemberAgg member,
      String levelCode,
      int levelRank,
      String reason,
      Map<String, Object> basis,
      OffsetDateTime windowFrom,
      OffsetDateTime windowTo,
      RuleView matched) {
    sql.execute(
        "insert into crm_membership_profile"
            + "(id,tenant_id,member_key,customer_ref,source_shop,customer_party_id,customer_name,"
            + " logical_customer_id,logical_ref,member_count,source_shops,members,order_count,"
            + " total_spend,spend_currency,amounts,repurchase_rate,first_order_at,last_order_at,"
            + " level_code,level_rank,level_origin,level_rule_id,level_reason,level_evaluated_at,"
            + " registered_at,window_from,window_to,created_at,updated_at)"
            + " values (?,?,?,?,?,?,?,?,?,?,?::jsonb,?::jsonb,?,?,?,?::jsonb,?,?::timestamptz,"
            + " ?::timestamptz,?,?,?,?,?,?::timestamptz,?::timestamptz,?::timestamptz,"
            + " ?::timestamptz,?::timestamptz,?::timestamptz)"
            + " on conflict (tenant_id, member_key) do update set"
            + " customer_ref = excluded.customer_ref, source_shop = excluded.source_shop,"
            + " customer_party_id = excluded.customer_party_id, customer_name = excluded.customer_name,"
            + " logical_customer_id = excluded.logical_customer_id, logical_ref = excluded.logical_ref,"
            + " member_count = excluded.member_count, source_shops = excluded.source_shops,"
            + " members = excluded.members, order_count = excluded.order_count,"
            + " total_spend = excluded.total_spend, spend_currency = excluded.spend_currency,"
            + " amounts = excluded.amounts, repurchase_rate = excluded.repurchase_rate,"
            + " first_order_at = excluded.first_order_at, last_order_at = excluded.last_order_at,"
            + " level_code = excluded.level_code, level_rank = excluded.level_rank,"
            + " level_origin = excluded.level_origin, level_rule_id = excluded.level_rule_id,"
            + " level_reason = excluded.level_reason, level_evaluated_at = excluded.level_evaluated_at,"
            + " window_from = excluded.window_from, window_to = excluded.window_to,"
            + " updated_at = excluded.updated_at",
        UUID.randomUUID(),
        currentTenant(),
        member.memberKey,
        member.primary.customerRef,
        member.primary.sourceShop,
        member.primary.partyId,
        member.primary.customerName,
        member.logicalCustomerId,
        member.logicalRef,
        member.memberCount(),
        toJson(member.sourceShops()),
        toJson(member.memberSnapshots()),
        member.orderCount(),
        member.totalSpend(),
        member.primaryCurrency(),
        toJson(member.amounts()),
        member.repurchaseRate(),
        member.firstOrderAt(),
        member.lastOrderAt(),
        levelCode,
        levelRank,
        // 🔴 等级来源恒为 RULE：人工覆盖通道未交付，DB 层的触发器也会拒绝别的取值。
        "RULE",
        matched == null ? null : matched.id(),
        reason,
        now(),
        // 🔴 registered_at 只在**首次建档**时落，重算**不覆盖**（ON CONFLICT 分支不含它）。
        now(),
        windowFrom,
        windowTo,
        now(),
        now());
  }

  private void writeLevelChange(
      MemberAgg member,
      String fromLevel,
      int fromRank,
      String toLevel,
      int toRank,
      String kind,
      String reason,
      Map<String, Object> basis,
      RuleView matched,
      OffsetDateTime changedAt,
      UUID actorId) {
    sql.execute(
        "insert into crm_membership_level_change"
            + "(id,tenant_id,member_key,customer_ref,source_shop,customer_name,"
            + " from_level_code,to_level_code,from_level_rank,to_level_rank,change_kind,origin,"
            + " rule_id,reason,basis,changed_at,operator_id,created_at)"
            + " values (?,?,?,?,?,?,?,?,?,?,?,'RULE',?,?,?::jsonb,?::timestamptz,?,?::timestamptz)",
        UUID.randomUUID(),
        currentTenant(),
        member.memberKey,
        member.primary.customerRef,
        member.primary.sourceShop,
        member.primary.customerName,
        fromLevel,
        toLevel,
        fromRank,
        toRank,
        kind,
        matched == null ? null : matched.id(),
        reason,
        toJson(basis),
        changedAt,
        actorId,
        now());
  }

  // ================================================================ 聚合

  /** 读 15.3.1.3 跨店合并的**只读**映射：谁合并进了哪个逻辑客户。 */
  private MergeIndex loadMergeIndex() {
    Map<String, MergeInfo> bySource = new LinkedHashMap<>();
    for (Record r :
        sql.fetch(
            "select m.source_shop, m.customer_ref, m.role,"
                + " l.id as logical_id, l.logical_ref, l.display_name"
                + " from crm_customer_merge_mapping m"
                + " join crm_customer_merge_logical l on l.id = m.logical_customer_id"
                + " join crm_customer_profile p on p.id = m.customer_profile_id"
                + " where m.tenant_id = ? and m.state = 'ACTIVE'"
                + " and l.tenant_id = ? and l.status = 'ACTIVE' and p.tenant_id = ?",
            currentTenant(),
            currentTenant(),
            currentTenant())) {
      String shop = nz(r.get("source_shop", String.class));
      String ref = nz(r.get("customer_ref", String.class));
      bySource.put(
          key(shop, ref),
          new MergeInfo(
              r.get("logical_id", UUID.class),
              nz(r.get("logical_ref", String.class)),
              nz(r.get("display_name", String.class)),
              "PRIMARY".equalsIgnoreCase(nz(r.get("role", String.class)))));
    }
    return new MergeIndex(bySource);
  }

  /** 一个来源店铺下的一个客户（合并前的最小粒度）。 */
  private static final class SourceAgg {
    final String sourceShop;
    final String customerRef;
    UUID partyId;
    String customerName = "";
    final List<OrderFact> orders = new ArrayList<>();

    SourceAgg(String sourceShop, String customerRef) {
      this.sourceShop = sourceShop;
      this.customerRef = customerRef;
    }

    void add(OrderFact order) {
      orders.add(order);
      if (partyId == null) partyId = order.customerPartyId();
      if (customerName.isEmpty() && order.buyerName() != null) customerName = order.buyerName();
    }
  }

  /** 一位会员（可能由多个来源店铺客户行合并而成）。 */
  private static final class MemberAgg {
    final String memberKey;
    final List<SourceAgg> sources = new ArrayList<>();
    SourceAgg primary;
    UUID logicalCustomerId;
    String logicalRef;

    MemberAgg(String memberKey) {
      this.memberKey = memberKey;
    }

    void add(SourceAgg source, MergeInfo merge) {
      sources.add(source);
      if (merge != null) {
        logicalCustomerId = merge.logicalId();
        logicalRef = merge.logicalRef();
        if (merge.primary()) primary = source;
      }
      if (primary == null) primary = source;
    }

    int memberCount() {
      return sources.size();
    }

    List<String> sourceShops() {
      Set<String> shops = new LinkedHashSet<>();
      for (SourceAgg source : sources) shops.add(source.sourceShop);
      return new ArrayList<>(shops);
    }

    List<Map<String, Object>> memberSnapshots() {
      List<Map<String, Object>> out = new ArrayList<>();
      for (SourceAgg source : sources) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("customerRef", source.customerRef);
        item.put("sourceShop", source.sourceShop);
        item.put("customerName", source.customerName);
        item.put("orderCount", source.orders.size());
        out.add(item);
      }
      return out;
    }

    /** 窗口内订单总单数（跨币种可加：单数不是金额）。 */
    int orderCount() {
      int total = 0;
      for (SourceAgg source : sources) total += source.orders.size();
      return total;
    }

    /** 分币种明细：一币种一行，绝不跨币种相加。 */
    List<CurrencyAmount> amounts() {
      Map<String, int[]> counts = new TreeMap<>();
      Map<String, BigDecimal> spends = new TreeMap<>();
      for (SourceAgg source : sources) {
        for (OrderFact order : source.orders) {
          String currency = nz(order.currency()).trim().toUpperCase(Locale.ROOT);
          if (currency.isEmpty()) currency = "UNKNOWN";
          counts.merge(currency, new int[] {1}, (a, b) -> new int[] {a[0] + b[0]});
          BigDecimal amount = order.grandTotalAmount();
          if (amount == null || amount.signum() < 0) continue;
          spends.merge(currency, amount, BigDecimal::add);
        }
      }
      List<CurrencyAmount> out = new ArrayList<>();
      for (Map.Entry<String, int[]> entry : counts.entrySet()) {
        out.add(
            new CurrencyAmount(
                entry.getKey(),
                entry.getValue()[0],
                spends.getOrDefault(entry.getKey(), BigDecimal.ZERO).setScale(4, RoundingMode.HALF_UP)));
      }
      return out;
    }

    /** 主币种 = 窗口内消费最多的那个币种（并列时比单数，再并列按字母序）。 */
    String primaryCurrency() {
      List<CurrencyAmount> amounts = amounts();
      if (amounts.isEmpty()) return null;
      CurrencyAmount best = amounts.get(0);
      for (CurrencyAmount candidate : amounts) {
        int cmp = candidate.totalSpend().compareTo(best.totalSpend());
        if (cmp > 0) {
          best = candidate;
        } else if (cmp == 0 && candidate.orderCount() > best.orderCount()) {
          best = candidate;
        }
      }
      return best.currency();
    }

    /** 主币种下的窗口内累计消费。 */
    BigDecimal totalSpend() {
      String currency = primaryCurrency();
      if (currency == null) return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
      for (CurrencyAmount amount : amounts()) {
        if (amount.currency().equals(currency)) return amount.totalSpend();
      }
      return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
    }

    /**
     * 复购率 = 窗口内「非首单订单数 ÷ 订单数」。
     *
     * <p>🔴 窗口内没有订单时是 <b>null（不可判定）</b>，不是 0 —— 报 0% 会被读成
     * 「这个人一次都没复购」。
     */
    BigDecimal repurchaseRate() {
      int count = orderCount();
      if (count <= 0) return null;
      return BigDecimal.valueOf(count - 1)
          .divide(BigDecimal.valueOf(count), RATE_SCALE, RoundingMode.HALF_UP);
    }

    OffsetDateTime firstOrderAt() {
      OffsetDateTime first = null;
      for (SourceAgg source : sources) {
        for (OrderFact order : source.orders) {
          OffsetDateTime moment = orderMoment(order);
          if (moment != null && (first == null || moment.isBefore(first))) first = moment;
        }
      }
      return first;
    }

    OffsetDateTime lastOrderAt() {
      OffsetDateTime last = null;
      for (SourceAgg source : sources) {
        for (OrderFact order : source.orders) {
          OffsetDateTime moment = orderMoment(order);
          if (moment != null && (last == null || moment.isAfter(last))) last = moment;
        }
      }
      return last;
    }
  }

  private static OffsetDateTime orderMoment(OrderFact order) {
    return order.submittedAt() != null ? order.submittedAt() : order.createdAt();
  }

  private record MergeInfo(UUID logicalId, String logicalRef, String displayName, boolean primary) {}

  /**
   * 内部分币种明细（<b>BigDecimal</b>）。
   *
   * <p>🔴 与出参的 {@link CrmMembershipAmount}（<b>十进制文本</b>）分开：
   * 聚合与判定要在 Java 里做十进制运算（跨币种不能加、主币种要按金额挑），
   * 出参才转文本。中间那一步如果提前变成文本，就得反复 parse 回来 ——
   * 那是把「算」与「显示」搅在一起。
   */
  private record CurrencyAmount(String currency, int orderCount, BigDecimal totalSpend) {}

  private record MergeIndex(Map<String, MergeInfo> bySource) {}

  /** 一条生效中的等级规则（判定时的快照）。 */
  private record RuleView(
      UUID id,
      String code,
      String name,
      int rank,
      BigDecimal minTotalSpend,
      String minTotalSpendCurrency,
      Integer minOrderCount,
      BigDecimal minRepurchaseRate,
      BigDecimal discountRate,
      boolean freeShipping,
      boolean priorityShipping) {}

  // ================================================================ 判定说明

  /** 判定理由（凭什么落这一级）—— 写在会员档案上，必须能回答。 */
  private String levelReason(
      MemberAgg member, RuleView matched, List<RuleView> rules, boolean currencySkip) {
    if (matched == null) {
      StringBuilder sb = new StringBuilder("未达任何等级");
      if (rules.isEmpty()) {
        sb.append("：租户还没有配置任何生效中的等级规则");
      } else {
        sb.append("：已配 ").append(rules.size()).append(" 条生效规则，均未满足");
      }
      if (currencySkip) {
        sb.append("；其中带金额门槛的规则因**币种对不上**而对该会员不适用");
      }
      sb.append("。本次窗口消费：")
          .append(member.orderCount())
          .append(" 单")
          .append(
              member.primaryCurrency() == null
                  ? "（主币种未定，无有效消费）"
                  : "，主币种 "
                      + member.primaryCurrency()
                      + " 累计 "
                      + member.totalSpend().toPlainString());
      return sb.toString();
    }
    StringBuilder sb = new StringBuilder("命中等级 ")
        .append(matched.code())
        .append("（")
        .append(matched.name())
        .append("，等级序 ")
        .append(matched.rank())
        .append("）：");
    List<String> parts = new ArrayList<>();
    if (matched.minTotalSpend() != null) {
      parts.add(
          "累计消费 "
              + member.totalSpend().toPlainString()
              + " "
              + member.primaryCurrency()
              + " ≥ "
              + matched.minTotalSpend().toPlainString()
              + " "
              + matched.minTotalSpendCurrency());
    }
    if (matched.minOrderCount() != null) {
      parts.add("订单数 " + member.orderCount() + " ≥ " + matched.minOrderCount());
    }
    if (matched.minRepurchaseRate() != null) {
      parts.add(
          "复购率 "
              + (member.repurchaseRate() == null ? "不可判定" : member.repurchaseRate().toPlainString())
              + " ≥ "
              + matched.minRepurchaseRate().toPlainString());
    }
    if (parts.isEmpty()) {
      sb.append("该等级未配任何门槛（任何有真实消费的会员都命中）。");
    } else {
      sb.append(String.join("；", parts)).append("。");
    }
    sb.append("本次窗口消费 ")
        .append(member.orderCount())
        .append(" 单")
        .append(
            member.primaryCurrency() == null
                ? "（无有效消费金额）"
                : "，主币种 " + member.primaryCurrency() + " 累计 " + member.totalSpend().toPlainString());
    return sb.toString();
  }

  /** 判定依据快照（落进等级变更记录，事后可复核）。 */
  private Map<String, Object> levelBasis(MemberAgg member, RuleView matched) {
    Map<String, Object> basis = new LinkedHashMap<>();
    basis.put("orderCount", member.orderCount());
    // 🔴 快照里的金额与比率也是**十进制文本**：快照要能逐位复核，
    //   而 JSON number 会在解析时把 1.0000 归一成 1、把 300.0000 归一成 3E+2。
    basis.put("totalSpend", moneyText(member.totalSpend()));
    basis.put("spendCurrency", member.primaryCurrency());
    basis.put("repurchaseRate", computedRateText(member.repurchaseRate()));
    basis.put("memberCount", member.memberCount());
    basis.put("sourceShops", member.sourceShops());
    Map<String, Object> thresholds = new LinkedHashMap<>();
    if (matched != null) {
      thresholds.put("ruleId", matched.id().toString());
      thresholds.put("levelCode", matched.code());
      thresholds.put("minTotalSpend", moneyText(matched.minTotalSpend()));
      thresholds.put("minTotalSpendCurrency", matched.minTotalSpendCurrency());
      thresholds.put("minOrderCount", matched.minOrderCount());
      thresholds.put("minRepurchaseRate", rateText(matched.minRepurchaseRate()));
    }
    basis.put("thresholds", thresholds);
    return basis;
  }

  // ================================================================ 行映射

  private List<CrmMembershipMemberRow> memberRows(List<Record> records) {
    List<CrmMembershipMemberRow> out = new ArrayList<>();
    for (Record r : records) out.add(memberRow(r));
    return out;
  }

  private CrmMembershipMemberRow memberRow(Record r) {
    String origin = nz(r.get("level_origin", String.class));
    return new CrmMembershipMemberRow(
        r.get("id", UUID.class),
        nz(r.get("member_key", String.class)),
        nz(r.get("customer_ref", String.class)),
        nz(r.get("source_shop", String.class)),
        nz(r.get("customer_name", String.class)),
        blankToNull(r.get("logical_ref", String.class)),
        nzInt(r.get("member_count", Integer.class)),
        readStringList(r.get("source_shops", String.class)),
        nzInt(r.get("order_count", Integer.class)),
        moneyText(r.get("total_spend", BigDecimal.class)),
        blankToNull(r.get("spend_currency", String.class)),
        readAmounts(r.get("amounts", String.class)),
        computedRateText(r.get("repurchase_rate", BigDecimal.class)),
        momentText(r.get("first_order_at", OffsetDateTime.class)),
        momentText(r.get("last_order_at", OffsetDateTime.class)),
        nz(r.get("level_code", String.class)),
        nzInt(r.get("level_rank", Integer.class)),
        origin,
        originLabel(origin),
        blankToNull(
            r.get("level_rule_id", UUID.class) == null ? null : r.get("level_rule_id", UUID.class).toString()),
        nz(r.get("level_reason", String.class)),
        momentText(r.get("level_evaluated_at", OffsetDateTime.class)),
        momentText(r.get("registered_at", OffsetDateTime.class)),
        momentText(r.get("window_from", OffsetDateTime.class)),
        momentText(r.get("window_to", OffsetDateTime.class)));
  }

  private CrmMembershipLevelChangeRow levelChangeRow(Record r) {
    String kind = nz(r.get("change_kind", String.class));
    String origin = nz(r.get("origin", String.class));
    return new CrmMembershipLevelChangeRow(
        r.get("id", UUID.class),
        nz(r.get("member_key", String.class)),
        nz(r.get("customer_ref", String.class)),
        nz(r.get("source_shop", String.class)),
        nz(r.get("customer_name", String.class)),
        nz(r.get("from_level_code", String.class)),
        nz(r.get("to_level_code", String.class)),
        nzInt(r.get("from_level_rank", Integer.class)),
        nzInt(r.get("to_level_rank", Integer.class)),
        kind,
        changeKindLabel(kind),
        origin,
        originLabel(origin),
        blankToNull(r.get("rule_id", UUID.class) == null ? null : r.get("rule_id", UUID.class).toString()),
        nz(r.get("reason", String.class)),
        readJsonObject(r.get("basis", String.class)),
        momentText(r.get("changed_at", OffsetDateTime.class)),
        blankToNull(
            r.get("operator_id", UUID.class) == null ? null : r.get("operator_id", UUID.class).toString()));
  }

  private CrmMembershipLevelRuleRow levelRuleRow(Record r) {
    OffsetDateTime from = r.get("effective_from", OffsetDateTime.class);
    OffsetDateTime to = r.get("effective_to", OffsetDateTime.class);
    boolean effectiveNow = from != null && !from.isAfter(now()) && (to == null || to.isAfter(now()));
    return new CrmMembershipLevelRuleRow(
        r.get("id", UUID.class),
        nz(r.get("level_code", String.class)),
        nz(r.get("level_name", String.class)),
        nzInt(r.get("level_rank", Integer.class)),
        moneyText(r.get("min_total_spend", BigDecimal.class)),
        blankToNull(r.get("min_total_spend_currency", String.class)),
        r.get("min_order_count", Integer.class),
        rateText(r.get("min_repurchase_rate", BigDecimal.class)),
        rateText(r.get("discount_rate", BigDecimal.class)),
        Boolean.TRUE.equals(r.get("free_shipping", Boolean.class)),
        Boolean.TRUE.equals(r.get("priority_shipping", Boolean.class)),
        Boolean.TRUE.equals(r.get("enabled", Boolean.class)),
        momentText(from),
        momentText(to),
        effectiveNow,
        nz(r.get("note", String.class)),
        momentText(r.get("created_at", OffsetDateTime.class)));
  }

  // ================================================================ 工具

  /** 等级名（从生效中的规则里查；查不到就退回等级代码本身）。 */
  private String levelNameOf(UUID tenant, String levelCode) {
    if (LEVEL_NONE.equals(levelCode)) return "未达任何等级";
    String name =
        sql.fetchOne(
                "select level_name from crm_membership_level_rule"
                    + " where tenant_id = ? and level_code = ? order by effective_from desc limit 1",
                tenant,
                levelCode)
            .get(0, String.class);
    return name == null ? levelCode : name;
  }

  private static String originLabel(String code) {
    for (MembershipLevelOrigin origin : MembershipLevelOrigin.values()) {
      if (origin.name().equals(code)) return origin.label();
    }
    return code;
  }

  private static String changeKindLabel(String code) {
    for (MembershipChangeKind kind : MembershipChangeKind.values()) {
      if (kind.name().equals(code)) return kind.label();
    }
    return code;
  }

  private static String benefitSummary(
      BigDecimal discount, boolean freeShipping, boolean priority) {
    List<String> parts = new ArrayList<>();
    if (discount != null) {
      parts.add("减免 " + discount.multiply(BigDecimal.valueOf(100)).stripTrailingZeros().toPlainString() + "%");
    }
    if (freeShipping) parts.add("免邮");
    if (priority) parts.add("优先发货");
    return parts.isEmpty() ? "无权益" : String.join(" · ", parts);
  }

  private static String appliesTo(Record r) {
    List<String> parts = new ArrayList<>();
    BigDecimal spend = r.get("min_total_spend", BigDecimal.class);
    if (spend != null) {
      parts.add(
          "累计消费 ≥ " + spend.toPlainString() + " " + nz(r.get("min_total_spend_currency", String.class)));
    }
    Integer orders = r.get("min_order_count", Integer.class);
    if (orders != null) parts.add("订单数 ≥ " + orders);
    BigDecimal rate = r.get("min_repurchase_rate", BigDecimal.class);
    if (rate != null) parts.add("复购率 ≥ " + rate.toPlainString());
    return parts.isEmpty() ? "无门槛（任何有真实消费的会员都命中）" : String.join(" 且 ", parts);
  }

  private UUID currentTenant() {
    return sql.fetchOne("select nullif(current_setting('app.current_tenant',true),'')::uuid")
        .get(0, UUID.class);
  }

  /**
   * 当前时刻。
   *
   * <p>🔴 取自**数据库的 now()**：相对时间口径（规则是否在生效期内、等级何时判定）
   * 一律用 DB 时钟，不拿 JVM 墙钟与 DB 时钟比大小。
   */
  private OffsetDateTime now() {
    return sql.fetchOne("select now()").get(0, OffsetDateTime.class);
  }

  private static String key(String sourceShop, String customerRef) {
    return sourceShop + " " + customerRef;
  }

  private static String nz(Object value) {
    return value == null ? "" : String.valueOf(value);
  }

  /** 整数的空值兜底（null ⇒ 0）。金额与比率**不**用它 —— 那些必须保持 null（不可判定）。 */
  private static int nzInt(Integer value) {
    return value == null ? 0 : value;
  }

  private static String blankToNull(String value) {
    if (value == null) return null;
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private static boolean sameDecimal(BigDecimal left, BigDecimal right) {
    if (left == null || right == null) return left == null && right == null;
    return left.compareTo(right) == 0;
  }

  /** 十进制文本 → BigDecimal（出参是文本，比较时得还原成数比，别比字符串）。 */
  private static BigDecimal decimalOf(String text) {
    if (text == null || text.isBlank()) return null;
    try {
      return new BigDecimal(text.trim());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static boolean sameInteger(Integer left, Integer right) {
    if (left == null || right == null) return left == null && right == null;
    return left.equals(right);
  }

  private static boolean sameText(String left, String right) {
    String a = blankToNull(left);
    String b = blankToNull(right);
    return a == null ? b == null : a.equalsIgnoreCase(b);
  }

  /**
   * 时刻 → 出参文本（<b>统一换算成 UTC</b>，秒必写，秒以下有值才写）。
   *
   * <p>🔴 为什么换算成 UTC：同一个绝对时刻用不同偏移量写出来会是两个字符串
   * （{@code 2026-10-10T08:00+08:00} 与 {@code 2026-10-10T00:00Z} 是同一时刻），
   * 拿字符串比相等会得出「不是同一个时刻」的错结论。换算成 UTC 之后，
   * <b>同一时刻只有一种写法</b>。
   */
  private static String momentText(OffsetDateTime moment) {
    return moment == null ? null : MOMENT_TEXT.format(moment);
  }

  private static OffsetDateTime parseMoment(String value, String label) {
    try {
      return OffsetDateTime.parse(value.trim());
    } catch (RuntimeException e) {
      throw bad(label + "必须是 ISO-8601 带偏移量的时刻（例：2026-10-10T08:00:00+08:00）。");
    }
  }

  private static String toJson(Object value) {
    try {
      return JSON.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> readJsonObject(String raw) {
    if (raw == null || raw.isBlank()) return Map.of();
    try {
      JsonNode root = JSON.readTree(raw);
      if (root == null || !root.isObject()) return Map.of();
      Map<String, Object> out = new LinkedHashMap<>();
      root.fields().forEachRemaining(e -> out.put(e.getKey(), toPlainValue(e.getValue())));
      return out;
    } catch (Exception e) {
      return Map.of();
    }
  }

  /** jsonb 节点 → Java 值（**数字一律 BigDecimal，绝不 double**）。 */
  private static Object toPlainValue(JsonNode node) {
    if (node == null || node.isNull()) return null;
    if (node.isNumber()) return node.decimalValue();
    if (node.isTextual()) return node.textValue();
    if (node.isBoolean()) return node.booleanValue();
    if (node.isArray()) {
      List<Object> out = new ArrayList<>();
      for (JsonNode item : node) out.add(toPlainValue(item));
      return out;
    }
    if (node.isObject()) {
      Map<String, Object> out = new LinkedHashMap<>();
      node.fields().forEachRemaining(e -> out.put(e.getKey(), toPlainValue(e.getValue())));
      return out;
    }
    return node.asText();
  }

  /**
   * jsonb 数组 → 列表。
   *
   * <p>🔴 <b>走 {@code readTree} 而不是 {@code readValue(raw, Object.class)}</b>：
   * 后者把 JSON 数字读成 {@code double} —— 那会让金额在读回来的时候**先走一遍浮点**，
   * 而本模块的金额口径是「禁浮点」。{@code readTree} + {@code USE_BIG_DECIMAL_FOR_FLOATS}
   * 读出来的是 {@link BigDecimal}，全程不碰浮点。
   */
  private static List<Map<String, Object>> readObjectList(String raw) {
    if (raw == null || raw.isBlank()) return List.of();
    try {
      JsonNode parsed = JSON.readTree(raw);
      if (parsed == null || !parsed.isArray()) return List.of();
      List<Map<String, Object>> out = new ArrayList<>();
      for (JsonNode item : parsed) {
        if (!item.isObject()) continue;
        Map<String, Object> map = new LinkedHashMap<>();
        item.fields().forEachRemaining(e -> map.put(e.getKey(), toPlainValue(e.getValue())));
        out.add(map);
      }
      return out;
    } catch (Exception e) {
      return List.of();
    }
  }

  private static List<String> readStringList(String raw) {
    if (raw == null || raw.isBlank()) return List.of();
    try {
      JsonNode parsed = JSON.readTree(raw);
      if (parsed == null || !parsed.isArray()) return List.of();
      List<String> out = new ArrayList<>();
      for (JsonNode item : parsed) {
        if (item != null && !item.isNull()) out.add(item.asText());
      }
      return out;
    } catch (Exception e) {
      return List.of();
    }
  }

  private static List<CrmMembershipAmount> readAmounts(String raw) {
    List<CrmMembershipAmount> out = new ArrayList<>();
    for (Map<String, Object> item : readObjectList(raw)) {
      String currency = nz(item.get("currency"));
      int count = 0;
      try {
        Object value = item.get("orderCount");
        if (value instanceof Number number) {
          count = number.intValue();
        } else if (value != null) {
          count = Integer.parseInt(String.valueOf(value));
        }
      } catch (RuntimeException e) {
        count = 0;
      }
      BigDecimal spend = null;
      Object rawSpend = item.get("totalSpend");
      if (rawSpend instanceof BigDecimal number) {
        spend = number;
      } else if (rawSpend instanceof Number number) {
        spend = new BigDecimal(number.toString());
      } else if (rawSpend != null) {
        try {
          spend = new BigDecimal(String.valueOf(rawSpend));
        } catch (RuntimeException e) {
          spend = null;
        }
      }
      out.add(new CrmMembershipAmount(currency, count, moneyText(spend)));
    }
    return out;
  }

  // ================================================================ 十进制文本口径

  /**
   * 金额 → 十进制文本（去尾零 + <b>至少两位小数</b>，<b>不四舍五入</b>）。
   *
   * <p>出参显示的必须是「库里那个数」：{@code 1.0000} → {@code 1.00}，
   * {@code 66.6050} → {@code 66.605}（多位就出多位），{@code 0} → {@code 0.00}。
   */
  private static String moneyText(BigDecimal value) {
    if (value == null) return null;
    BigDecimal stripped = value.stripTrailingZeros();
    int scale = Math.max(stripped.scale(), MONEY_MIN_SCALE);
    return stripped.setScale(scale, RoundingMode.UNNECESSARY).toPlainString();
  }

  /**
   * 配置来的比率 → 十进制文本（<b>去尾零</b>：运营写 {@code 0.05} 就是 {@code 0.05}，
   * 不显示成库里补零后的 {@code 0.050000}）。
   */
  private static String rateText(BigDecimal value) {
    if (value == null) return null;
    BigDecimal stripped = value.stripTrailingZeros();
    if (stripped.signum() == 0) return "0";
    return stripped.toPlainString();
  }

  /**
   * 判定算出来的比率（复购率）→ 十进制文本（<b>固定 6 位小数</b>）。
   *
   * <p>🔴 与配置来的比率**故意不同标度**：复购率是<b>算出来的判定口径</b>，
   * 固定 6 位才能逐位复核（{@code 0.750000} 而不是 {@code 0.75}）；
   * 配置里的比率是<b>运营写下的意图</b>，照原样显示。
   */
  private static String computedRateText(BigDecimal value) {
    if (value == null) return null;
    return value.setScale(RATE_SCALE, RoundingMode.HALF_UP).toPlainString();
  }

  /** 刷新运行号：MB-yyyyMMdd-000001（序列单独授权，见 V440 授权段）。 */
  private String nextRunNo() {
    var row =
        sql.fetchOne(
            "select nextval('crm_membership_run_no_seq') as seq,"
                + " to_char(now() at time zone 'utc','YYYYMMDD') as day");
    long seq = row.get("seq", Long.class);
    String day = row.get("day", String.class);
    return "MB-" + day + "-" + String.format("%06d", seq);
  }

  private static ResponseStatusException bad(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private static ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }

  private static ResponseStatusException notFound(String message) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
  }

  private static String dataScopeOf(AccessService.Access actor) {
    String scope = "ALL";
    if (actor.grants() != null) {
      for (AccessService.Grant grant : actor.grants()) {
        if ("ALL".equals(grant.scopeMode())) return "ALL";
        if ("SELF".equals(grant.scopeMode())) scope = "SELF";
        else if ("DEPARTMENT".equals(grant.scopeMode()) && "ALL".equals(scope)) scope = "DEPARTMENT";
      }
    }
    return scope;
  }
}
