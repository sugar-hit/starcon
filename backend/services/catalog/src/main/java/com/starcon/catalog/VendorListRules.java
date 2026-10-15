package com.starcon.catalog;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;

/**
 * 6.1.1.1 供应商列表的纯规则：合作状态 / 合作等级 / 主体状态三张词表、排序白名单、指标窗口与比率计算。
 *
 * <p>与 {@link PartyRoleProfileRules} / {@link BrandRules} 同一纪律：<b>能在查询前判定的，就不要留到下游才炸</b>。
 * 「合作等级传了一个词表里没有的值」不会在列表上报错，它会静默筛出 0 条 —— 而运营看到的是一个空列表，
 * 于是以为这家供应商不存在。
 *
 * <p>规则 R1~R6 在 {@code V137__vendor_supply_perspective.sql} 第九节有同样的文字。
 *
 * <p>本类不碰数据库，可以在离线单测里穷举 —— {@code VendorListRulesTest} 就是这么用的。
 */
public final class VendorListRules {
  private VendorListRules() {}

  /** 词表选项：{@code value} 是入库形态，{@code label} 是界面显示，{@code hint} 说明它对下游的动作含义。 */
  public record Option(String value, String label, String hint) {}

  // ================================================================ 合作状态（本模块自有，写入所有权在这里）

  public static final String SUPPLY_PENDING_REVIEW = "PENDING_REVIEW";
  public static final String SUPPLY_ACTIVE = "ACTIVE";
  public static final String SUPPLY_SUSPENDED = "SUSPENDED";
  public static final String SUPPLY_TRIAL = "TRIAL";
  public static final String SUPPLY_TERMINATED = "TERMINATED";

  /**
   * 采购侧合作状态词表。
   *
   * <p><b>与 {@code party.status} 是两个不同的状态</b>（规则 R2）：party.status 说的是「这个往来单位本身还认不认」，
   * 本词表说的是「采购这条线上还跟不跟他做」。一个战略供应商可能主体照常 ACTIVE（11 售后单还要引用他），
   * 而采购侧已经 SUSPENDED（资质过期 / 报价全失效）。
   */
  public static final List<Option> SUPPLY_STATUSES =
      List.of(
          new Option(SUPPLY_PENDING_REVIEW, "待评估",
              "已建供应商身份但采购侧还没评估（**还没建运营档案的供应商就是这一档**），不进新建采购单的候选"),
          new Option(SUPPLY_ACTIVE, "合作中", "正常可选"),
          new Option(SUPPLY_SUSPENDED, "暂停下单", "主体仍可用（售后单还要引用），但采购侧不放新单，历史单据照常"),
          new Option(SUPPLY_TRIAL, "试单中", "采购侧正在验证质量与交期；与对方的合作等级 TRIAL 不是同一件事"),
          new Option(SUPPLY_TERMINATED, "已终止", "不再合作。保留行，历史采购单与对账单还要引用"));

  // ================================================================ 合作等级（3.6.1.2 的词表，本模块只读，规则 R1）

  /**
   * 合作等级词表 —— <b>只读</b>。
   *
   * <p>这份词表的写入所有权在 3.6.1.2（{@code party_supplier_profile.cooperation_level}）。
   * 本模块把它抄一份**只是为了前端下拉**（枚举只增不改这条承诺要在**一处**兑现，前端与后端各维护一份迟早会不一样）；
   * 本模块不提供任何写合作等级的入口 —— 那会是「同一实体两处可编辑」，也正是蓝图点名要避免的（规则 R1）。
   *
   * <p>取值与 V30 的 CHECK 逐字一致：STRATEGIC / PREFERRED / STANDARD / TRIAL / RISK。
   */
  public static final List<Option> COOPERATION_LEVELS =
      List.of(
          new Option("STRATEGIC", "战略供应商", "绑定多年、量价一议，缺货直接影响主推 listing"),
          new Option("PREFERRED", "优选供应商", "同品类内的第二选择，用于战略供应商断供时切换"),
          new Option("STANDARD", "常规供应商", "按目录价与标准账期走，新建供应商的默认档"),
          new Option("TRIAL", "试单供应商", "正在验证质量与交期，通常不给账期"),
          new Option("RISK", "风险供应商", "有质量或交期问题待观察，采购单必须人工确认"));

  /** 主体状态词表（3.6.1.1 的 {@code party.status}，本模块只读）。取值与 V27 的 CHECK 逐字一致。 */
  public static final List<Option> PARTY_STATUSES =
      List.of(
          new Option("ACTIVE", "可用", "可以用于新单据"),
          new Option("INACTIVE", "停用", "不再用于新单据，历史单据照常显示"),
          new Option("BLACKLISTED", "拉黑", "制裁名单 / 纠纷对手方；进出都要填理由"));

  /** 排序键的默认方向。比率类默认降序（差的排前面 —— 运营打开列表就是要找问题供应商）。 */
  public static final String SORT_PARTY_CODE = "partyCode";
  public static final String SORT_PARTY_NAME = "partyName";
  public static final String SORT_SUPPLY_STATUS = "supplyStatus";
  public static final String SORT_COOPERATION_LEVEL = "cooperationLevel";
  public static final String SORT_SUPPLY_SKU_COUNT = "supplySkuCount";
  public static final String SORT_PURCHASE_AMOUNT = "purchaseAmount";
  public static final String SORT_ON_TIME_RATE = "onTimeRate";
  public static final String SORT_QUALITY_RATE = "qualityRate";
  public static final String SORT_LAST_DELIVERY = "lastDeliveryAt";

  /** 比率类默认窗口：365 天。一个「终身累计」的准时率在供应商评审里没有意义 —— 那家三年前只接过一单的供应商会拿 100%。 */
  public static final int DEFAULT_METRIC_WINDOW_DAYS = 365;

  /** 窗口上限（天）：挡住把 metricFrom 设成 1970 年那种「其实是终身累计」但看不出区别的请求。 */
  public static final int MAX_METRIC_WINDOW_DAYS = 1095;

  /** 单页最大行数。供应商是租户级主数据，行数量级在千位。 */
  public static final int MAX_PAGE_SIZE = 200;

  // ================================================================ 规范化与前置条件

  /**
   * 规范化一个枚举入参（转大写 + 去空白）；空值 / 空串返回 null（= 不筛）。
   *
   * <p>不在这里判合法性：合法性由 {@link #enumProblem} 按具体词表给，这样每张表的报错文案能点出是哪一张。
   */
  public static String normalizeEnum(String value) {
    if (value == null) return null;
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed.toUpperCase(Locale.ROOT);
  }

  /** 合作状态是否在词表内（写入路径用）。 */
  public static String supplyStatusProblem(String status) {
    if (status == null || status.isBlank()) return "合作状态不能为空";
    String value = status.trim().toUpperCase(Locale.ROOT);
    for (Option option : SUPPLY_STATUSES) if (option.value().equals(value)) return null;
    return "无效的合作状态：" + status + "；取值：" + values(SUPPLY_STATUSES);
  }

  /** 合作等级是否在词表内（筛选路径用；只读，但非法值仍然要 400 而不是静默筛出空列表）。 */
  public static String cooperationLevelProblem(String level) {
    return enumProblem(level, COOPERATION_LEVELS, "合作等级");
  }

  /** 主体状态是否在词表内（筛选路径用）。 */
  public static String partyStatusProblem(String status) {
    return enumProblem(status, PARTY_STATUSES, "主体状态");
  }

  /** 币种格式：ISO 4217 三位大写。**刻意不校验它是不是一个真实存在的币种** —— 完整词表是 12 财务的资产。 */
  public static String currencyProblem(String currency) {
    if (currency == null || currency.isBlank()) return null;
    String value = currency.trim().toUpperCase(Locale.ROOT);
    return value.matches("[A-Z]{3}") ? null : "币种请用 ISO 4217 三位大写字母码：" + currency;
  }

  private static String enumProblem(String value, List<Option> table, String label) {
    String normalized = normalizeEnum(value);
    if (normalized == null) return null;
    for (Option option : table) if (option.value().equals(normalized)) return null;
    return "无效的" + label + "：" + value + "；取值：" + values(table);
  }

  /** 到货记录：数量 / 金额 / 日期关系。 */
  public static String deliveryProblem(
      BigDecimal quantity, BigDecimal amount, java.time.LocalDate dueDate, java.time.LocalDate receivedDate) {
    if (quantity == null || quantity.signum() <= 0) return "到货数量必须大于 0";
    if (amount == null || amount.signum() < 0) return "到货金额不能为负";
    if (dueDate == null || receivedDate == null) return "承诺到货日与实际到货日都必须填";
    if (receivedDate.isBefore(dueDate.minusYears(10)))
      return "实际到货日比承诺到货日早十年以上：这多半是把年份填错了";
    return null;
  }

  /** 质检记录：合格数不得超过检验数。 */
  public static String qualityProblem(BigDecimal inspected, BigDecimal qualified) {
    if (inspected == null || inspected.signum() <= 0) return "检验数量必须大于 0";
    if (qualified == null || qualified.signum() < 0) return "合格数量不能为负";
    if (qualified.compareTo(inspected) > 0)
      return "合格数量（" + qualified.toPlainString() + "）不能超过检验数量（" + inspected.toPlainString() + "）";
    return null;
  }

  // ================================================================ 指标窗口

  /**
   * 定出**实际生效**的指标窗口（起含、止含）。
   *
   * <p>两端都缺省：取「现在往前 {@link #DEFAULT_METRIC_WINDOW_DAYS} 天」。两端给了就直接用 —— 界面上按哪一段算，
   * 用户能自己选，而不是永远看一个写死的窗口。
   *
   * <p><b>起止颠倒时给可读原因而不是静默交换</b>：一个搞反的窗口会让准时率变成「这批货提前了多久」，
   * 而报表看上去完全正常。
   */
  public static VendorListModels.VendorMetricWindow metricWindow(
      OffsetDateTime from, OffsetDateTime to, OffsetDateTime now) {
    OffsetDateTime end = to == null ? now : to;
    OffsetDateTime start = from == null ? end.minusDays(DEFAULT_METRIC_WINDOW_DAYS) : from;
    CatalogService.check(!start.isAfter(end), "指标窗口的起点不能晚于终点");
    long days = Duration.between(start, end).toDays();
    CatalogService.check(
        days <= MAX_METRIC_WINDOW_DAYS,
        "指标窗口最长 " + MAX_METRIC_WINDOW_DAYS + " 天，收到的是 " + days + " 天");
    return new VendorListModels.VendorMetricWindow(start.toInstant().toString(), end.toInstant().toString());
  }

  // ================================================================ 比率计算（服务层聚合之外唯一实现，与前端展示同口径）

  /**
   * 准时交付率 = 准时次数 / 到货次数。
   *
   * <p><b>分母为 0 返回 null 而不是 0</b>（规则 R3）：「还没交货过」与「交货全部延期」是<b>相反</b>的两件事。
   * 折叠成 0 会让一个刚建档、正在试单的 TRIAL 供应商在列表上看起来是最差的那个，
   * 而实际上它还没有任何交付表现 —— 这是本模块最容易造成误判的一处。
   *
   * <p>用 {@link BigDecimal} 且 scale 定到 4 位、{@link RoundingMode#HALF_UP}：AGENTS.md 禁止浮点记账，
   * 而比率同样是数量比（次数），用 double 会在「33.33%」与「33.33%」这种边界上给出不稳定的尾数。
   */
  public static BigDecimal onTimeRate(Long onTime, Long total) {
    if (onTime == null || total == null || total == 0L) return null;
    return BigDecimal.valueOf(onTime)
        .divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP);
  }

  /**
   * 质量合格率 = 合格数量 / 检验数量。
   *
   * <p>按<b>数量</b>而不是批次：一批 10000 件合格 9999 与一批 3 件合格 1，按批次算都是 50%，
   * 按数量算是 99.99% 与 33.33% —— 后者才是采购真正承担的风险大小。
   */
  public static BigDecimal qualityRate(BigDecimal qualified, BigDecimal inspected) {
    if (qualified == null || inspected == null || inspected.signum() == 0) return null;
    return qualified.divide(inspected, 4, RoundingMode.HALF_UP);
  }

  /** 比率 → 百分比展示串（null → 「暂无数据」而不是「0%」，理由同 R3）。 */
  public static String rateLabel(BigDecimal rate) {
    return rate == null
        ? "暂无数据"
        : rate.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP).toPlainString() + "%";
  }

  /**
   * 逾期天数：早到 / 准时都是 <b>0</b>，迟到为正。
   *
   * <p><b>刻意不返回负数</b>：这个字段在界面上的标签是「逾期天数」，
   * 显示「-5」会被读成「提前 5 天逾期」这种读不通的东西。
   * 提前到了多少天要表达的话，用 {@code received_date < due_date} 本身（准时判定）就够了。
   */
  public static int daysLate(java.time.LocalDate dueDate, java.time.LocalDate receivedDate) {
    if (dueDate == null || receivedDate == null) return 0;
    return (int) Math.max(0, java.time.temporal.ChronoUnit.DAYS.between(dueDate, receivedDate));
  }

  /** 某行是否准时。与 SQL 的 {@code received_date <= due_date} 同口径（当天到货算准时）。 */
  public static boolean onTime(java.time.LocalDate dueDate, java.time.LocalDate receivedDate) {
    return dueDate != null && receivedDate != null && !receivedDate.isAfter(dueDate);
  }

  // ================================================================ 排序白名单

  /** 排序键 → 常量列名。查表命中，绝不接受外部拼接。 */
  private static final java.util.Map<String, String> SORT_COLUMNS =
      java.util.Map.of(
          SORT_PARTY_CODE, "p.code",
          SORT_PARTY_NAME, "p.name",
          SORT_SUPPLY_STATUS, "vsp.supply_status",
          SORT_COOPERATION_LEVEL, "psp.cooperation_level",
          SORT_SUPPLY_SKU_COUNT, "coalesce(q.sku_count,0)",
          SORT_PURCHASE_AMOUNT, "coalesce(amt.purchase_amount,0)",
          SORT_ON_TIME_RATE, "on_time_rate",
          SORT_QUALITY_RATE, "quality_rate",
          SORT_LAST_DELIVERY, "dv.last_delivery_at");

  /** 每个排序键的默认方向。比率类与计数类默认降序（找问题），文本类默认升序（好找）。 */
  private static final java.util.Map<String, Boolean> SORT_DESC =
      java.util.Map.of(
          SORT_PARTY_CODE, false,
          SORT_PARTY_NAME, false,
          SORT_SUPPLY_STATUS, false,
          SORT_COOPERATION_LEVEL, false,
          SORT_SUPPLY_SKU_COUNT, true,
          SORT_PURCHASE_AMOUNT, true,
          SORT_ON_TIME_RATE, true,
          SORT_QUALITY_RATE, true,
          SORT_LAST_DELIVERY, true);

  /**
   * 排序键查表：<b>大小写无关</b>。
   *
   * <p>前端列头传 {@code partyCode}、URL 参数传 {@code partycode} 或 {@code PARTYCODE} 都应命中同一条；
   * 查表命中不了就 400。规范化成全大写再比 {@code partyCode} 是<b>不</b>行的 ——
   * 两边形态不同，永远命中不了，于是每个合法排序键都报「未知」，而症状是「排序怎么用都不动」。
   */
  private static String canonicalSortKey(String key) {
    if (key == null) return null;
    String trimmed = key.trim();
    if (trimmed.isEmpty()) return null;
    for (String candidate : SORT_COLUMNS.keySet())
      if (candidate.equalsIgnoreCase(trimmed)) return candidate;
    return null;
  }

  /**
   * 排序键 → SQL 表达式。
   *
   * <p><b>这些表达式会被直接拼进 ORDER BY</b>，所以它们只能是 {@link #SORT_COLUMNS} 里写死的常量；
   * 客户端传进来的字符串只用于**查表命中**，命中不了就抛（由 controller 转成 400），绝不拼进 SQL
   * （与 {@code ExportDataSource.ExportSort} 同一纪律）。
   */
  public static String sortColumn(String key) {
    String canonical = canonicalSortKey(key);
    if (canonical == null) {
      // 空 = 走默认排序；非空却查不到 = 非法输入，两种不能混成一个异常。
      if (key == null || key.isBlank()) return SORT_COLUMNS.get(SORT_PARTY_CODE);
      throw new IllegalArgumentException(
          "未知的排序键：" + key + "；可用：" + String.join(" / ", sortKeys()));
    }
    return SORT_COLUMNS.get(canonical);
  }

  /** 排序键是否合法（给 controller 一个可读报错，不靠捕获 IllegalArgumentException）。 */
  public static String sortProblem(String key) {
    if (key == null || key.isBlank()) return null;
    return canonicalSortKey(key) == null
        ? "未知的排序键：" + key + "；可用：" + String.join(" / ", sortKeys())
        : null;
  }

  /** 该排序键的默认方向。 */
  public static boolean defaultDescFor(String key) {
    String canonical = canonicalSortKey(key);
    // 没给排序键时走默认排序 partyCode（升序），与 sortColumn 的回退一致
    if (canonical == null) canonical = SORT_PARTY_CODE;
    return SORT_DESC.get(canonical);
  }

  /** 该排序键是否是一个<b>可能为 null 的比率列</b>（调用方据此加 {@code nulls last}）。 */
  public static boolean isRateSort(String key) {
    String canonical = canonicalSortKey(key);
    return SORT_ON_TIME_RATE.equals(canonical) || SORT_QUALITY_RATE.equals(canonical);
  }

  /** 全部排序键（前端列头 + {@link VendorListModels.VendorListOptions} 的数据源）。 */
  public static List<String> sortKeys() {
    return List.of(
        SORT_PARTY_CODE,
        SORT_PARTY_NAME,
        SORT_SUPPLY_STATUS,
        SORT_COOPERATION_LEVEL,
        SORT_SUPPLY_SKU_COUNT,
        SORT_PURCHASE_AMOUNT,
        SORT_ON_TIME_RATE,
        SORT_QUALITY_RATE,
        SORT_LAST_DELIVERY);
  }

  /** 全部排序选项（含中文名与默认方向），前端直接渲染成可点的列头。 */
  public static List<VendorListModels.VendorSortOption> sorts() {
    var out = new java.util.ArrayList<VendorListModels.VendorSortOption>();
    for (String key : sortKeys()) out.add(new VendorListModels.VendorSortOption(key, sortLabel(key), defaultDescFor(key)));
    return out;
  }

  private static String sortLabel(String key) {
    return switch (canonicalSortKey(key)) {
      case SORT_PARTY_CODE -> "供应商编码";
      case SORT_PARTY_NAME -> "供应商名称";
      case SORT_SUPPLY_STATUS -> "合作状态";
      case SORT_COOPERATION_LEVEL -> "合作等级";
      case SORT_SUPPLY_SKU_COUNT -> "供货 SKU 数";
      case SORT_PURCHASE_AMOUNT -> "采购金额累计";
      case SORT_ON_TIME_RATE -> "准时交付率";
      case SORT_QUALITY_RATE -> "质量合格率";
      default -> "最近到货";
    };
  }


  private static String values(List<Option> table) {
    var out = new StringBuilder();
    for (Option option : table) {
      if (out.length() > 0) out.append(" / ");
      out.append(option.value());
    }
    return out.toString();
  }
}
