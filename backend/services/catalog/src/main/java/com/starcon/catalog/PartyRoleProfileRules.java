package com.starcon.catalog;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 3.6.1.2 角色档案的纯规则：供应商（账期 / MOQ / 合作等级 / 结算方式）、
 * 客户（分级 / 默认币种 / 信用额度 / 账期）、承运商（接口类型 / 账号 / 服务能力）
 * 的词表、规范化与保存前置条件。
 *
 * <p>与 {@link PartyRules} / {@link BrandRules} / {@link UnitRules} 同一条纪律：
 * <b>能在保存前判定的，就不要留到下游才炸</b>。
 * 「结算方式是预付却填了 90 天账期」不会在建档时报错，它会在 06 采购单带上 90 天、
 * 12 应付按 90 天后到期日去催款时暴露 —— 而那时已经有货发出去了。
 *
 * <p>规则 R1~R7 在 {@code V30__party_role_profiles.sql} 第七节有同样的文字，
 * 服务层这里与前端 {@code party-role-profile-model.ts} 是它的三份实现，改一处必须同步三处。
 *
 * <p>本类不碰数据库，可以在离线单测里穷举。
 *
 * <p>类名不叫 {@code PartyRoleProfile}：那是被静态导入的 DTO record
 * （{@link CatalogModels}），两者在同一批文件里会直接撞名，编译期才炸。
 * 与 {@link PartyRules} 同一个理由。
 */
public final class PartyRoleProfileRules {
  private PartyRoleProfileRules() {}

  // ================================================================ 长度上限（与 V30 列宽逐字一致）

  /** 供应商 / 客户档案备注上限，与 {@code note text} 的服务层约束一致。 */
  public static final int MAX_NOTE_LENGTH = 2000;

  /** 承运商服务范围说明上限。给到 2000 是因为航线限制要写「只做东南亚，不接化工品」这种整句。 */
  public static final int MAX_SCOPE_NOTE_LENGTH = 2000;

  /** 承运商账号标识上限，与 {@code account_ref varchar(128)} 一致。 */
  public static final int MAX_ACCOUNT_REF_LENGTH = 128;

  /** 计量单位代码上限，与 {@code moq_unit_code varchar(32)} 及 {@code unit_of_measure.code} 一致。 */
  public static final int MAX_UNIT_CODE_LENGTH = 32;

  /**
   * 账期上限（天）。365 是"一年"这一口语化上限：
   * 它挡住误填的 99999（那是把年填进了天），同时不挡住真实存在的年度账期。
   * 与 V30 两条表的 CHECK 逐字一致。
   */
  public static final int MAX_PAYMENT_TERMS_DAYS = 365;

  /** MOQ 数量精度上限（6 位小数），与 {@code moq_quantity numeric(19,6)} 一致。 */
  public static final int MOQ_SCALE = 6;

  /** 信用额度精度上限（4 位小数），与 {@code default_credit_limit numeric(19,4)} 一致。 */
  public static final int CREDIT_LIMIT_SCALE = 4;

  /** 信用额度整数位上限（19,4 里整数部分最多 15 位）。用 {@code BigDecimal} 而非 double 判。 */
  public static final int CREDIT_LIMIT_PRECISION = 15;

  /** 单个承运商可勾选的服务能力数量上限。九个词表全选也只有 9，这里是防手滑，不是容量约束。 */
  public static final int MAX_SERVICE_CAPABILITIES = 12;

  // ================================================================ 词表（语义见 V30 第三 / 四 / 五节）

  /** 合作等级（供应商）。 */
  public static final Set<String> COOPERATION_LEVELS =
      Set.of("STRATEGIC", "PREFERRED", "STANDARD", "TRIAL", "RISK");

  /** 结算方式（供应商）。 */
  public static final Set<String> SETTLEMENT_METHODS =
      Set.of("PREPAID", "ON_DELIVERY", "CREDIT", "LETTER_OF_CREDIT");

  /** 客户分级。 */
  public static final Set<String> CUSTOMER_LEVELS =
      Set.of("STRATEGIC", "KEY", "STANDARD", "PROSPECT", "RISK");

  /** 接口类型（承运商）。 */
  public static final Set<String> INTERFACE_TYPES =
      Set.of("API", "SFTP_FILE", "WEB_PORTAL", "EMAIL");

  /** 服务能力（承运商）。 */
  public static final Set<String> SERVICE_CAPABILITIES =
      Set.of(
          "AIR", "SEA", "RAIL", "TRUCK", "EXPRESS",
          "DOOR_TO_DOOR", "CUSTOMS", "INSURED", "URGENT");

  /** 供应商标识这张表对应的角色类型（规则 **R3**）。 */
  public static final String ROLE_SUPPLIER = "SUPPLIER";

  /** 客户标识这张表对应的角色类型（规则 **R3**）。 */
  public static final String ROLE_CUSTOMER = "CUSTOMER";

  /** 承运商标识这张表对应的角色类型（规则 **R3**）。 */
  public static final String ROLE_CARRIER = "CARRIER";

  // ================================================================ 通用规范化

  /**
   * 文本规范化：去首尾空白，空串归成「没有」。
   *
   * <p>与 {@link PartyRules#normalizeText} 同一实现：空串与 null 在业务上是同一件事
   * （"没填备注"），让它们在库里长得不一样只会在列表上多出几个看不出区别的空格子。
   */
  public static String normalizeText(String value) {
    return value == null ? "" : value.trim();
  }

  /** 枚举规范化：大写 + 去空白。空串归成 null（"没填"）。 */
  public static String normalizeEnum(String value) {
    String text = normalizeText(value);
    if (text.isEmpty()) return null;
    return text.toUpperCase(Locale.ROOT);
  }

  /** 币种规范化：ISO 4217 三位大写；空串归 null。 */
  public static String normalizeCurrency(String value) {
    String text = normalizeText(value).toUpperCase(Locale.ROOT);
    return text.isEmpty() ? null : text;
  }

  /** 计量单位代码规范化：去空白 + 大写（unit_of_measure.code 允许中文，大写只影响拉丁字母）。 */
  public static String normalizeUnitCode(String value) {
    String text = normalizeText(value).toUpperCase(Locale.ROOT);
    return text.isEmpty() ? null : text;
  }

  // ================================================================ 供应商：账期

  /**
   * 账期校验（天）。
   *
   * <p>0 是合法值：预付 / 货到付款的供应商就该是 0（规则 **R1**），
   * 而"没填账期"与"账期就是 0"在跨境 B2B 里是同一件事 —— 没有账期就是现结。
   * 所以这里没有"必填"，也没有把 0 当成漏填。
   *
   * <p>负数没有业务含义（那意味着"倒欠对方 30 天"），上限 365 挡住把年填成天。
   */
  public static String paymentTermsProblem(Integer days) {
    if (days == null) return null; // 落库取 0（NOT NULL DEFAULT 0）
    if (days < 0) return "账期不能是负数（到期日必须晚于单据日）";
    if (days > MAX_PAYMENT_TERMS_DAYS) return "账期最多 " + MAX_PAYMENT_TERMS_DAYS + " 天，请确认没有把年填成天";
    return null;
  }

  /** 账期归一：null → 0（对应「不给账期 / 现结」）。 */
  public static int normalizePaymentTerms(Integer days) {
    return days == null ? 0 : days;
  }

  // ================================================================ 供应商：MOQ 数量与单位

  /**
   * MOQ 校验（规则：数量与单位成对存在）。
   *
   * <p>{@code quantity} 可空；可填则必须 &gt; 0 且小数位 ≤ 6。
   * 0 与负数都没有意义 —— MOQ 为 0 意味着"不设最小起订量"，那是**没有这一项**，
   * 而它在库里表达成 NULL，不是表达成 0（与"没设额度 = 不限制"同源的口径）。
   *
   * @param unitCode 采购单位代码，可空；与 quantity 必须同时有值或同时为空
   */
  public static String moqProblem(BigDecimal quantity, String unitCode) {
    String unit = normalizeUnitCode(unitCode);
    boolean hasQuantity = quantity != null;
    boolean hasUnit = unit != null && !unit.isEmpty();
    if (hasQuantity != hasUnit)
      return hasQuantity
          ? "填了最小起订量就必须填采购单位（同样是「100」，在「件」和「箱」下差几十倍）"
          : "填了采购单位就必须填最小起订量，只给单位没有数量说不清起订门槛";
    if (!hasQuantity) return null;
    if (quantity.signum() <= 0) return "最小起订量必须大于 0（不设起订量请留空，不要填 0）";
    if (quantity.scale() > MOQ_SCALE) return "最小起订量最多 " + MOQ_SCALE + " 位小数";
    if (quantity.precision() - quantity.scale() > CREDIT_LIMIT_PRECISION)
      return "最小起订量的整数部分太长，请确认没有多打一个数量级";
    if (unit.length() > MAX_UNIT_CODE_LENGTH)
      return "采购单位代码最多 " + MAX_UNIT_CODE_LENGTH + " 个字符";
    return null;
  }

  /**
   * 数量归一到存储精度：小数位多于 {@code MOQ_SCALE} 时按 HALF_UP 收，
   * 而不是直接拒 —— 这一条只用于把已经通过 {@link #moqProblem} 的值定标，
   * 避免 {@code 0.0000001} 这种在前端输入框里手抖出来的值落库后多出一串零。
   *
   * <p>刻意用 {@link BigDecimal} 而非 double：MOQ 是采购量的下限，
   * 浮点的 0.1+0.2 类误差落在"起订量"上会让一行订单被静默判成不足起订。
   */
  public static BigDecimal normalizeQuantity(BigDecimal value, int scale) {
    if (value == null) return null;
    BigDecimal scaled = value.setScale(scale, java.math.RoundingMode.HALF_UP);
    return scaled.signum() == 0 ? null : scaled;
  }

  // ================================================================ 供应商：合作等级与结算方式（规则 R1）

  /** 合作等级校验。空串按 {@code STANDARD} 处理（新建供应商的默认档）。 */
  public static String cooperationLevelProblem(String value) {
    String level = normalizeEnum(value);
    if (level == null) return null;
    if (!COOPERATION_LEVELS.contains(level))
      return "合作等级只能是 STRATEGIC（战略）/ PREFERRED（优选）/ STANDARD（常规）/ TRIAL（试单）/ RISK（风险）";
    return null;
  }

  /** 合作等级归一：空 → {@code STANDARD}（对应 V30 的 NOT NULL DEFAULT）。 */
  public static String normalizeCooperationLevel(String value) {
    String level = normalizeEnum(value);
    return level == null ? "STANDARD" : level;
  }

  /** 结算方式校验。空串按 {@code PREPAID} 处理（不给账期时的默认形态）。 */
  public static String settlementMethodProblem(String value) {
    String method = normalizeEnum(value);
    if (method == null) return null;
    if (!SETTLEMENT_METHODS.contains(method))
      return "结算方式只能是 PREPAID（预付）/ ON_DELIVERY（货到付款）/ CREDIT（账期结算）/ LETTER_OF_CREDIT（信用证）";
    return null;
  }

  /** 结算方式归一：空 → {@code PREPAID}。 */
  public static String normalizeSettlementMethod(String value) {
    String method = normalizeEnum(value);
    return method == null ? "PREPAID" : method;
  }

  /**
   * 结算方式与账期的自洽校验（规则 **R1**）。
   *
   * <p>「预付 / 货到付款 / 信用证」一律要求账期为 0，「账期结算」要求账期 &gt; 0。
   * 这条规则在 V30 里也有对应的 DB CHECK，两处同源：DB 兜住绕过服务层的写法，
   * 服务层给出可读的原因（填了 0 天的信用证结算，DB 报的是 constraint name，人看不懂）。
   */
  public static String settlementConsistencyProblem(String method, int days) {
    String normalized = normalizeSettlementMethod(method);
    String terms = settlementMethodLabel(normalized);
    if ("CREDIT".equals(normalized)) {
      if (days <= 0) return "结算方式是「" + terms + "」时账期必须大于 0 天（0 天等于预付，与所选的结算方式矛盾）";
      return null;
    }
    if (days != 0)
      return "结算方式是「" + terms + "」时不占用账期，请把账期改为 0 天"
          + "（要用账期结算请把结算方式选成「" + settlementMethodLabel("CREDIT") + "」）";
    return null;
  }

  // ================================================================ 客户：币种、额度、分级

  /** 默认币种校验：可空；填了必须 ISO 4217 三位大写。 */
  public static String currencyProblem(String value) {
    String currency = normalizeCurrency(value);
    if (currency == null) return null;
    if (!currency.matches("[A-Z]{3}")) return "币种请用 ISO 4217 三位代码，如 USD / EUR / CNY";
    return null;
  }

  /**
   * 信用额度校验（规则 **R2**）。
   *
   * <p>可空：<b>没设额度在业务上等于不限制</b>，这是有意义的"没有"，不是漏填
   * （与"信用额度 = 0 = 不许赊账"是两种相反的默认，所以 0 必须被当成非法值而不是"最严格的额度"）。
   *
   * <p>精度：最多 {@link #CREDIT_LIMIT_SCALE} 位小数、整数部分最多
   * {@link #CREDIT_LIMIT_PRECISION} 位，与 {@code numeric(19,4)} 一致。
   */
  public static String creditLimitProblem(BigDecimal limit) {
    if (limit == null) return null;
    if (limit.signum() <= 0)
      return "信用额度必须大于 0（不设额度请留空 —— 「没设额度」表示不限制赊账，"
          + "而「额度为 0」表示完全不许赊，两者是相反的默认）";
    if (limit.scale() > CREDIT_LIMIT_SCALE) return "信用额度最多 " + CREDIT_LIMIT_SCALE + " 位小数";
    if (limit.precision() - limit.scale() > CREDIT_LIMIT_PRECISION)
      return "信用额度的整数部分太长，请确认币种与数量级（例如 100000 USD）";
    return null;
  }

  /**
   * 额度与币种必须成对（规则 **R2**）。
   *
   * <p>只判"有额度必须有币种"这一个方向：反过来（有钱种没额度）是正常的 ——
   * 新客户可能已经约定了用美元结算但还没谈额度，而 V30 里 default_currency 刻意 NOT NULL 之外可空。
   */
  public static String creditLimitCurrencyProblem(BigDecimal limit, String currency) {
    if (limit == null) return null;
    if (normalizeCurrency(currency) == null)
      return "填了信用额度就必须填默认币种 —— 12 应收拿它和发票币种对比，没币种的额度只能靠假设，"
          + "而假设错了不会报错";
    return null;
  }

  /** 客户分级校验。空串按 {@code STANDARD} 处理。 */
  public static String customerLevelProblem(String value) {
    String level = normalizeEnum(value);
    if (level == null) return null;
    if (!CUSTOMER_LEVELS.contains(level))
      return "客户分级只能是 STRATEGIC（战略）/ KEY（重点）/ STANDARD（普通）/ PROSPECT（潜在）/ RISK（风险）";
    return null;
  }

  /** 客户分级归一：空 → {@code STANDARD}。 */
  public static String normalizeCustomerLevel(String value) {
    String level = normalizeEnum(value);
    return level == null ? "STANDARD" : level;
  }

  /**
   * 风险客户的额度守卫（规则 **R4**）。
   *
   * <p>只拦这一种组合：「RISK 客户 + 仍有额度」。
   * 给一个被记录过逾期的客户留额度，12 应收会照着放单，而唯一的风险信号（分级）被额度列盖住了。
   * 反向不拦：普通客户有额度是正常业务。
   */
  public static String riskCustomerCreditProblem(String level, BigDecimal limit) {
    if (limit == null) return null;
    if ("RISK".equals(normalizeCustomerLevel(level)))
      return "风险客户的信用额度请留空（先清掉逾期记录、把分级改回正常档，再恢复额度）";
    return null;
  }

  // ================================================================ 承运商：接口类型、账号、服务能力

  /** 接口类型校验。空串按 {@code API} 处理。 */
  public static String interfaceTypeProblem(String value) {
    String type = normalizeEnum(value);
    if (type == null) return null;
    if (!INTERFACE_TYPES.contains(type))
      return "接口类型只能是 API（系统对接）/ SFTP_FILE（文件交换）/ WEB_PORTAL（平台下单）/ EMAIL（邮件往来）";
    return null;
  }

  /** 接口类型归一：空 → {@code API}。 */
  public static String normalizeInterfaceType(String value) {
    String type = normalizeEnum(value);
    return type == null ? "API" : type;
  }

  /**
   * 账号标识校验。
   *
   * <p>必填：承运商档案没有账号在 10 物流里用不了（API 取不到凭据、平台单登不进去）。
   * 与 3.6.1.1 规则 P3「联系方式至少给一项，被拒的应该是建档」同源 ——
   * 缺账号的承运商档案从一开始就无法履约。
   *
   * <p>只判形态，**不判是否是密钥**：档案里按 AGENTS.md 的秘密管理口径只存账号标识，
   * 凭据由部署侧从 secret manager 按这个键注入。写成明文口令的那种值人在页面上是看不出来的，
   * 只能靠文档约定 + 部署检查，所以这里刻意只挡空白。
   */
  public static String accountRefProblem(String value) {
    String account = normalizeText(value);
    if (account.isEmpty()) return "承运商账号标识不能为空（没有账号的档案在 10 物流里取不到凭据、也登不进平台）";
    if (account.length() > MAX_ACCOUNT_REF_LENGTH)
      return "账号标识最多 " + MAX_ACCOUNT_REF_LENGTH + " 个字符";
    return null;
  }

  /**
   * 服务能力校验。
   *
   * <p>逐个元素判，且**大小写必须与词表逐字相同**（{@code normalizeEnum} 会把
   * {@code sea} 变成 {@code SEA} 再判）。
   * 这不是洁癖：查询侧是 {@code service_capabilities @> ARRAY['SEA']}，
   * 一个存成 {@code 'sea'} 的能力在那个查询里是**静默不匹配** —— 不报错，只是永远筛不出来，
   * 而症状是「明明勾了海运，10 物流的筛选里就是看不到这家」。
   */
  public static String serviceCapabilitiesProblem(List<String> values) {
    List<String> capabilities = normalizeCapabilities(values);
    if (capabilities.size() > MAX_SERVICE_CAPABILITIES)
      return "服务能力最多 " + MAX_SERVICE_CAPABILITIES + " 项（可用能力一共只有 " + SERVICE_CAPABILITIES.size() + " 项）";
    for (String capability : capabilities)
      if (!SERVICE_CAPABILITIES.contains(capability)) {
        List<String> labels = new ArrayList<>();
        for (Option option : capabilityOptions()) labels.add(option.code() + "（" + option.label() + "）");
        return "服务能力「" + capability + "」不在词表里，可用值：" + String.join(" / ", labels);
      }
    return null;
  }

  /**
   * 服务能力规范化：大写 + 去空白 + 去重，且**保持词表顺序**输出。
   *
   * <p>按固定顺序而不是按输入顺序：同一个承运商在不同人手里保存两次，
   * {@code ['SEA','AIR']} 与 {@code ['AIR','SEA']} 必须落成同一行，
   * 否则 {@code array_agg} 出来的展示顺序会随谁最后保存而抖动，界面上的能力标签会重排。
   */
  public static List<String> normalizeCapabilities(List<String> values) {
    Set<String> submitted = new LinkedHashSet<>();
    if (values != null)
      for (String value : values) {
        String normalized = normalizeEnum(value);
        if (normalized != null) submitted.add(normalized);
      }
    List<String> ordered = new ArrayList<>();
    for (String capability : CAPABILITY_ORDER) if (submitted.contains(capability)) ordered.add(capability);
    // 词表之外的值也保留（校验会给出可读报错，但归一化不该悄悄吞掉用户输入）
    for (String capability : submitted) if (!ordered.contains(capability)) ordered.add(capability);
    return ordered;
  }

  /**
   * 能力值 → {@code text[]} 绑定形式。
   *
   * <p>用 <b>带引号的数组字面量</b>而不是拼字符串：能力值来自用户提交，
   * 拼进 SQL 字面量就是一条注入通道（哪怕 jOOQ 会把整个字符串当绑定参数传给 PostgreSQL，
   * 让 PostgreSQL 去解析一个由用户拼出来的数组字面量仍然不该发生）。
   * 这里显式转义单引号，保证任何输入都只能成为一个普通元素。
   */
  public static String toPgArray(List<String> values) {
    List<String> normalized = normalizeCapabilities(values);
    if (normalized.isEmpty()) return "{}";
    StringBuilder out = new StringBuilder("{");
    for (int i = 0; i < normalized.size(); i++) {
      if (i > 0) out.append(',');
      out.append('"').append(normalized.get(i).replace("\"", "\"\"")).append('"');
    }
    return out.append('}').toString();
  }

  /** 能力值的固定顺序（与 V30 第五节的词表注释逐字一致）。 */
  private static final String[] CAPABILITY_ORDER = {
    "AIR", "SEA", "RAIL", "TRUCK", "EXPRESS", "DOOR_TO_DOOR", "CUSTOMS", "INSURED", "URGENT"
  };

  // ================================================================ 标签（与 V30 的词表注释同一份口径）

  /** 合作等级 → 中文标签。 */
  public static String cooperationLevelLabel(String value) {
    String level = normalizeEnum(value);
    if (level == null) return "";
    return switch (level) {
      case "STRATEGIC" -> "战略供应商";
      case "PREFERRED" -> "优选供应商";
      case "STANDARD" -> "常规供应商";
      case "TRIAL" -> "试单供应商";
      case "RISK" -> "风险供应商";
      default -> level;
    };
  }

  /** 结算方式 → 中文标签。 */
  public static String settlementMethodLabel(String value) {
    String method = normalizeEnum(value);
    if (method == null) return "";
    return switch (method) {
      case "PREPAID" -> "预付";
      case "ON_DELIVERY" -> "货到付款";
      case "CREDIT" -> "账期结算";
      case "LETTER_OF_CREDIT" -> "信用证";
      default -> method;
    };
  }

  /** 客户分级 → 中文标签。 */
  public static String customerLevelLabel(String value) {
    String level = normalizeEnum(value);
    if (level == null) return "";
    return switch (level) {
      case "STRATEGIC" -> "战略客户";
      case "KEY" -> "重点客户";
      case "STANDARD" -> "普通客户";
      case "PROSPECT" -> "潜在客户";
      case "RISK" -> "风险客户";
      default -> level;
    };
  }

  /** 接口类型 → 中文标签。 */
  public static String interfaceTypeLabel(String value) {
    String type = normalizeEnum(value);
    if (type == null) return "";
    return switch (type) {
      case "API" -> "系统对接";
      case "SFTP_FILE" -> "文件交换";
      case "WEB_PORTAL" -> "平台下单";
      case "EMAIL" -> "邮件往来";
      default -> type;
    };
  }

  /** 服务能力 → 中文标签（给列表页的标签组用）。 */
  public static String capabilityLabel(String value) {
    String capability = normalizeEnum(value);
    if (capability == null) return "";
    return switch (capability) {
      case "AIR" -> "空运";
      case "SEA" -> "海运";
      case "RAIL" -> "铁路";
      case "TRUCK" -> "陆运";
      case "EXPRESS" -> "国际快递";
      case "DOOR_TO_DOOR" -> "门到门";
      case "CUSTOMS" -> "代理清关";
      case "INSURED" -> "保价";
      case "URGENT" -> "加急";
      default -> capability;
    };
  }

  /** 服务能力集合 → 中文串（列表页与详情页的标签组文案）。 */
  public static String capabilitySummary(java.util.Collection<String> values) {
    List<String> normalized = normalizeCapabilities(values == null ? List.of() : new ArrayList<>(values));
    if (normalized.isEmpty()) return "未登记服务能力";
    List<String> labels = new ArrayList<>(normalized.size());
    for (String capability : normalized) labels.add(capabilityLabel(capability));
    return String.join("、", labels);
  }

  // ================================================================ 枚举选项（前端下拉的数据源，与词表同源）

  /** 合作等级选项：code + 中文标签，按业务权重降序（战略在前）。 */
  public static List<Option> cooperationLevelOptions() {
    return List.of(
        new Option("STRATEGIC", cooperationLevelLabel("STRATEGIC")),
        new Option("PREFERRED", cooperationLevelLabel("PREFERRED")),
        new Option("STANDARD", cooperationLevelLabel("STANDARD")),
        new Option("TRIAL", cooperationLevelLabel("TRIAL")),
        new Option("RISK", cooperationLevelLabel("RISK")));
  }

  /** 结算方式选项。 */
  public static List<Option> settlementMethodOptions() {
    return List.of(
        new Option("PREPAID", settlementMethodLabel("PREPAID")),
        new Option("ON_DELIVERY", settlementMethodLabel("ON_DELIVERY")),
        new Option("CREDIT", settlementMethodLabel("CREDIT")),
        new Option("LETTER_OF_CREDIT", settlementMethodLabel("LETTER_OF_CREDIT")));
  }

  /** 客户分级选项。 */
  public static List<Option> customerLevelOptions() {
    return List.of(
        new Option("STRATEGIC", customerLevelLabel("STRATEGIC")),
        new Option("KEY", customerLevelLabel("KEY")),
        new Option("STANDARD", customerLevelLabel("STANDARD")),
        new Option("PROSPECT", customerLevelLabel("PROSPECT")),
        new Option("RISK", customerLevelLabel("RISK")));
  }

  /** 接口类型选项。 */
  public static List<Option> interfaceTypeOptions() {
    return List.of(
        new Option("API", interfaceTypeLabel("API")),
        new Option("SFTP_FILE", interfaceTypeLabel("SFTP_FILE")),
        new Option("WEB_PORTAL", interfaceTypeLabel("WEB_PORTAL")),
        new Option("EMAIL", interfaceTypeLabel("EMAIL")));
  }

  /** 服务能力选项：按词表顺序（空运 → 海运 → … → 加急），不是按字母序。 */
  public static List<Option> capabilityOptions() {
    List<Option> options = new ArrayList<>(CAPABILITY_ORDER.length);
    for (String capability : CAPABILITY_ORDER) options.add(new Option(capability, capabilityLabel(capability)));
    return options;
  }

  /** 下拉选项：code + 中文标签。 */
  public record Option(String code, String label) {}

  // ================================================================ 规则 R3：档案与角色的对应

  /**
   * 「这个主体有没有被指派 X 角色」的判定（规则 **R3**）。
   *
   * <p>传入的是<b>角色类型 code</b>（{@code SUPPLIER} 那种）而不是中文标签：
   * 与库里查出来的 {@code role_type} 列逐字比较才不会因为"标签写了个字"而永远不相等
   * —— 那种错误不会抛异常，只会让这句校验永远返回"还没指派"，于是**所有**保存都被拒。
   *
   * <p>返回可读的 409 文案而不是 null —— 运营点「供应商档案」时如果他还没指派供应商身份，
   * 报错必须是「先指派身份」，而不是「档案不存在」或一个 constraint name。
   * 顺带把已指派的身份列出来：多数「没指派」的成因是"以为指派过了"，直接告诉他现在有什么更快。
   *
   * @param roleType 角色类型 code
   * @param assignedRoleTypes 该主体当前已指派的角色类型 code 集合
   */
  public static String roleMissingProblem(String roleType, java.util.Collection<String> assignedRoleTypes) {
    String code = normalizeEnum(roleType);
    if (assignedRoleTypes != null && assignedRoleTypes.contains(code)) return null;
    String label = PartyRules.roleLabel(code);
    String assigned =
        assignedRoleTypes == null || assignedRoleTypes.isEmpty()
            ? "该往来单位目前一个角色都没有"
            : "该往来单位已指派：" + PartyRules.roleSummary(assignedRoleTypes);
    return "该往来单位还没有被指派「" + label + "」身份（" + assigned + "）。"
        + "请先指派角色再维护它的角色档案 —— 角色档案只能挂在已指派的身份上，"
        + "否则 06 采购 / 12 应收不知道该带出哪一列默认值";
  }

  /** 三张表对应的角色类型 code（服务层按它选表 / 选 SQL）。 */
  public static String roleTypeFor(String profileKind) {
    String kind = normalizeEnum(profileKind);
    if (kind == null) return null;
    return switch (kind) {
      case "SUPPLIER" -> ROLE_SUPPLIER;
      case "CUSTOMER" -> ROLE_CUSTOMER;
      case "CARRIER" -> ROLE_CARRIER;
      default -> null;
    };
  }

  /** 角色档案种类校验（给 {@code {roleType}} 路径段用：SUPPLIER / CUSTOMER / CARRIER）。 */
  public static String profileKindProblem(String value) {
    String kind = normalizeEnum(value);
    if (kind == null) return "角色档案类型不能为空";
    if (roleTypeFor(kind) == null) return "角色档案类型只能是 SUPPLIER（供应商）/ CUSTOMER（客户）/ CARRIER（承运商）";
    return null;
  }
}