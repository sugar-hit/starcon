package com.starcon.catalog;

import static com.starcon.catalog.PartyRules.emptyToNull;
import static com.starcon.catalog.PartyRules.normalizeText;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 3.6.3.1 地址的纯规则：国家 / 必填列校验、用途词表与约束、默认标记规则、 内容变更判定（版本链何时追加）、软删除前置条件、选中阻断判定。
 *
 * <p>与 {@link PartyRules} 同一条纪律，两条都写在这里：
 *
 * <ol>
 *   <li><b>能在保存前判定的，就不要留到下游才炸</b>。一个「收件人是谁都没填」的联系人 不会在保存时报错，它会在 06 采购问交期、10 物流约提货、11 售后约退货三处
 *       各自用不同的方式暴露 —— 而那时已经有单子挂在它上面了。
 *   <li><b>判定只有一处实现</b>。规则 A1~A6 在 {@code V31__party_addresses_contacts.sql} 第九节有同样的文字， 服务层这里与前端
 *       {@code party-contact-model.ts} 是它的两份实现，改一处必须同步三处。
 * </ol>
 *
 * <p>类名不叫 {@code PartyAddress}：那是被静态导入的 DTO record（{@link CatalogModels}）， 两者在同一批文件里会直接撞名，编译期才炸。与
 * {@link PartyRules} 同一个理由。
 *
 * <p>本类不碰数据库，可以在离线单测里穷举。
 */
public final class PartyAddressRules {
  private PartyAddressRules() {}

  // ============================================================ 长度上限（与 V31 列宽逐字一致）

  /** 国家代码长度上限，与 {@code party_address.country_code varchar(2)} 一致。 */
  public static final int MAX_COUNTRY_LENGTH = 2;

  /** 省份 / 州长度上限，与 {@code party_address.state_province varchar(100)} 一致。 */
  public static final int MAX_STATE_PROVINCE_LENGTH = 100;

  /** 城市长度上限，与 {@code party_address.city varchar(100)} 一致。 */
  public static final int MAX_CITY_LENGTH = 100;

  /** 区 / 县长度上限，与 {@code party_address.district varchar(100)} 一致。 */
  public static final int MAX_DISTRICT_LENGTH = 100;

  /** 邮编长度上限，与 {@code party_address.postal_code varchar(32)} 一致。 */
  public static final int MAX_POSTAL_CODE_LENGTH = 32;

  /** 街道行 1 / 行 2 长度上限，与 {@code party_address.street_line1/2 varchar(200)} 一致。 */
  public static final int MAX_STREET_LENGTH = 200;

  /** 地址名长度上限，与 {@code party_address.label varchar(100)} 一致。 */
  public static final int MAX_LABEL_LENGTH = 100;

  /** 变更说明长度上限。给到 200 是因为它要能写「门牌号改了，之前的是仓库大门」。 */
  public static final int MAX_CHANGE_NOTE_LENGTH = 200;

  /** 一个地址可承担的用途数量上限。与 {@code USAGES} 词表同长 —— 三个用途都已用满。 */
  public static final int MAX_USAGES = 3;

  // ============================================================ 词表

  /**
   * 地址用途词表：收货 / 发货 / 提货。
   *
   * <p>与 {@code V31} 第三节的 CHECK 逐字一致，也与前端 {@code ADDRESS_USAGES} 一致。 三个值刻意不合并：{@code SHIP_FROM}（06
   * 采购的发货地址）与 {@code PICKUP} （10 物流的提货地址）在多数场景是同一个物理地点，但在单据上各自带理由， 而且「每个用途各有一个默认」要能在数据上落成部分唯一索引。
   */
  public static final Set<String> USAGES = Set.of("SHIP_TO", "SHIP_FROM", "PICKUP");

  /** 用途的展示顺序：收货在前（最常被 06 采购取用），提货在后（10 物流）。 */
  private static final List<String> USAGE_ORDER = List.of("SHIP_TO", "SHIP_FROM", "PICKUP");

  // ============================================================ 规范化

  /** 国家代码规范化：去空白 + 大写（ISO 3166-1 alpha-2）。 */
  public static String normalizeCountry(String value) {
    return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
  }

  /** 用途规范化：去空白 + 大写。 */
  public static String normalizeUsage(String value) {
    return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
  }

  // ============================================================ A1 列校验

  /** 国家校验。**必填**（规则 A1）：地址必须落在某个国家里。 */
  public static String countryProblem(String value) {
    String code = normalizeCountry(value);
    if (code.isEmpty()) return "国家不能为空：跨境地址的国家决定报关、运费分区与税务辖区";
    if (code.length() > MAX_COUNTRY_LENGTH) return "国家代码最多 2 个字符";
    if (!code.matches("^[A-Z]{2}$")) return "国家代码请用 ISO 3166-1 alpha-2 两位代码，如 CN / DE / US";
    return null;
  }

  public static String stateProvinceProblem(String value) {
    return optionalProblem(value, MAX_STATE_PROVINCE_LENGTH, "省 / 州");
  }

  /** 城市校验。必填：跨国地址里 city 是运费分区与税务辖区的主要输入。 */
  public static String cityProblem(String value) {
    String city = normalizeText(value);
    if (city.isEmpty()) return "城市不能为空";
    if (city.length() > MAX_CITY_LENGTH) return "城市最多 " + MAX_CITY_LENGTH + " 个字符";
    return null;
  }

  public static String districtProblem(String value) {
    return optionalProblem(value, MAX_DISTRICT_LENGTH, "区 / 县");
  }

  /** 邮编校验。<b>可空</b>（规则 A1）：相当多国家没有邮编体系（HK / AE / 部分非洲国家）， 把它设成必填会逼运营编一个假邮编，而假邮编会让面单打印失败。 */
  public static String postalCodeProblem(String value) {
    return optionalProblem(value, MAX_POSTAL_CODE_LENGTH, "邮编");
  }

  /** 街道行 1 校验。必填。 */
  public static String streetLine1Problem(String value) {
    String street = normalizeText(value);
    if (street.isEmpty()) return "街道地址不能为空";
    if (street.length() > MAX_STREET_LENGTH) return "街道地址最多 " + MAX_STREET_LENGTH + " 个字符";
    return null;
  }

  public static String streetLine2Problem(String value) {
    return optionalProblem(value, MAX_STREET_LENGTH, "街道地址行 2");
  }

  public static String labelProblem(String value) {
    return optionalProblem(value, MAX_LABEL_LENGTH, "地址名");
  }

  public static String changeNoteProblem(String value) {
    String note = normalizeText(value);
    if (note.length() > MAX_CHANGE_NOTE_LENGTH) return "变更说明最多 " + MAX_CHANGE_NOTE_LENGTH + " 个字符";
    return null;
  }

  /**
   * 可空列的校验：要么不填，要么填了非空白且不超长。
   *
   * <p>「填了但全是空格」要拒：那是脏数据（DB 里出现一个看起来像有值、实际是空串的邮编）， 与 V27 对 email / phone 的同一口径。
   */
  private static String optionalProblem(String value, int max, String label) {
    if (value == null) return null;
    if (value.isEmpty()) return null;
    if (value.trim().isEmpty()) return label + "填了但只有空格，请清空或填写内容";
    if (value.length() > max) return label + "最多 " + max + " 个字符";
    return null;
  }

  /**
   * 一个地址请求的整体校验。
   *
   * <p>返回<b>第一条</b>可执行的原因（不是全部）：表单一次只该红一处， 而一次给七条会让运营不知道先改哪个。
   */
  public static String addressProblem(
      String countryCode,
      String city,
      String streetLine1,
      String stateProvince,
      String district,
      String postalCode,
      String streetLine2,
      String label,
      String changeNote) {
    return firstNonNull(
        countryProblem(countryCode),
        cityProblem(city),
        streetLine1Problem(streetLine1),
        stateProvinceProblem(stateProvince),
        districtProblem(district),
        postalCodeProblem(postalCode),
        streetLine2Problem(streetLine2),
        labelProblem(label),
        changeNoteProblem(changeNote));
  }

  // ============================================================ A2 用途与默认

  public static String usageProblem(String value) {
    String usage = normalizeUsage(value);
    if (usage.isEmpty()) return "地址用途不能为空";
    if (!USAGES.contains(usage)) return "地址用途只能是收货（SHIP_TO）、发货（SHIP_FROM）或提货（PICKUP）";
    return null;
  }

  /**
   * 用途清单的整体校验：词表 + 同一用途不重复（规则 A2）。
   *
   * <p>{@code null} 元素按「删掉这一条」处理（契约里数组元素带 {@code | null}， 与 {@code PartyRules.rolesProblem} 同一约定）。
   */
  public static String usagesProblem(List<CatalogModels.PartyAddressUsageInput> usages) {
    if (usages == null || usages.isEmpty()) return null;
    if (usages.size() > MAX_USAGES) return "一条地址最多承担 " + MAX_USAGES + " 个用途（收货 / 发货 / 提货）";
    Set<String> seen = new LinkedHashSet<>();
    for (CatalogModels.PartyAddressUsageInput usage : usages) {
      if (usage == null) continue;
      String problem = usageProblem(usage.usage());
      if (problem != null) return problem;
      String key = normalizeUsage(usage.usage());
      if (!seen.add(key)) return "同一个用途只能标一次：" + usageLabel(key);
    }
    return null;
  }

  /**
   * 新增地址 / 联系人时的**主体状态**前置（规则 A5）。
   *
   * <p>与 V27 的 P6 同源同理由：拉黑是「不与这个对手方做生意」， 给这样的对手方新增提货地址与对接人正是要拦的动作；而停用状态**允许**维护 ——
   * 「先建主体、停用状态下把地址与联系人补齐、再启用」是正常业务流。
   *
   * @param action 「新增地址」还是「新增联系人」，只影响文案
   */
  public static String createUnderPartyProblem(String partyStatus, String action) {
    if (!"BLACKLISTED".equals(normalizeText(partyStatus))) return null;
    return "该往来单位在拉黑名单里，不能再" + action + "：请先解除拉黑" + "（停用状态可以正常维护地址与联系人 —— 那是「先建主体、补齐资料、再启用」的正常流程）";
  }

  /** 删除前置：这条地址还是某个用途的默认（规则 A4）。{@code defaultUsages} 是默认用途名列表。 */
  public static String deleteProblem(List<String> defaultUsages) {
    if (defaultUsages == null || defaultUsages.isEmpty()) return null;
    String named =
        defaultUsages.stream()
            .map(PartyAddressRules::usageLabel)
            .reduce((a, b) -> a + "、" + b)
            .orElse("");
    return "这条地址还是"
        + named
        + "的默认地址，请先把默认改到别的地址再删除："
        + "直接删掉的后果是「这个供应商没有提货地址了」，而 10 物流的单子上已经填好了原地址";
  }

  // ============================================================ A3 内容变更判定（版本链何时追加）

  /** 参与「内容是否真的变了」比对的地址列，顺序与写入顺序一致（漏一列就会漏判一次变更）。 */
  public static String contentFingerprint(
      String countryCode,
      String stateProvince,
      String city,
      String district,
      String postalCode,
      String streetLine1,
      String streetLine2,
      String label) {
    return String.join(
        "\u0000",
        normalizeCountry(countryCode),
        textOrEmpty(stateProvince),
        normalizeText(city),
        textOrEmpty(district),
        textOrEmpty(postalCode),
        normalizeText(streetLine1),
        textOrEmpty(streetLine2),
        textOrEmpty(label));
  }

  /** 可空列的比对口径：null 与空串视为同一个「没填」，否则「清空邮编」会被判成两次变更。 */
  private static String textOrEmpty(String value) {
    return value == null ? "" : value.trim();
  }

  /**
   * 是否要追加一条版本行（规则 A3）。
   *
   * <p>内容一字未改就**不**追加：否则版本列表里全是无法区分的行， 而它要回答的问题是「这个地址被改过几次、每次改成什么样」。 只改用途 / 只改默认标记也走这条路径判定为 false
   * —— 那是标签变化，不是地址变化， 快照要冻结的是印在面单上的那个地址（V31 第四节）。
   */
  public static boolean appendsVersion(String storedFingerprint, String submittedFingerprint) {
    return !storedFingerprint.equals(submittedFingerprint);
  }

  // ============================================================ A6 选中阻断（06 / 10 的下拉口径）

  /** 地址自身已被软删除。 */
  public static final String BLOCK_ADDRESS_DELETED = "ADDRESS_DELETED";

  /** 归属主体已被软删除。与 {@link PartyRules#BLOCK_PARTY_DELETED} 同一个码。 */
  public static final String BLOCK_PARTY_DELETED = PartyRules.BLOCK_PARTY_DELETED;

  /** 归属主体已停用。 */
  public static final String BLOCK_PARTY_INACTIVE = PartyRules.BLOCK_PARTY_INACTIVE;

  /** 归属主体已拉黑。 */
  public static final String BLOCK_PARTY_BLACKLISTED = PartyRules.BLOCK_PARTY_BLACKLISTED;

  /** 一次选中阻断判定所需的全部输入。抽成 record 是为了让纯规则不依赖数据库行。 */
  public record SelectionInputs(boolean addressDeleted, boolean partyDeleted, String partyStatus) {}

  /**
   * 判定一条地址当前能否被下游单据选中（规则 A6）。
   *
   * <p>这是本模块交给 06 采购（发货 / 收货地址）与 10 物流（提货地址）的那份口径， 与 {@link PartyRules#usableBlock}
   * 同一纪律：<b>唯一实现</b>， 列表行、详情、默认地址、候选四个出口都调它。前端只把 {@code code} 翻成要显示的话， 不在浏览器里再判一次。
   *
   * <p>判定顺序即优先级：先地址已删除、再主体已删除、再拉黑、再停用。 对一个已删除的主体报「已停用」是误导。
   *
   * @return null 表示可选；否则是原因码
   */
  public static String selectionBlock(SelectionInputs inputs) {
    if (inputs.addressDeleted()) return BLOCK_ADDRESS_DELETED;
    if (inputs.partyDeleted()) return BLOCK_PARTY_DELETED;
    String status = normalizeText(inputs.partyStatus());
    if ("BLACKLISTED".equals(status)) return BLOCK_PARTY_BLACKLISTED;
    if (!"ACTIVE".equals(status)) return BLOCK_PARTY_INACTIVE;
    return null;
  }

  /** 原因码 → 界面标签。与 {@link #selectionBlock} 同一份词表。 */
  public static String selectionBlockLabel(String code) {
    if (code == null) return "可选";
    return switch (code) {
      case BLOCK_ADDRESS_DELETED -> "地址已删除";
      case BLOCK_PARTY_DELETED -> "主体已删除";
      case BLOCK_PARTY_INACTIVE -> "主体已停用";
      case BLOCK_PARTY_BLACKLISTED -> "主体已拉黑";
      default -> "不可选";
    };
  }

  /** 原因码 → 运营该做什么。四个原因的处理动作不同，所以文案也不同。 */
  public static String selectionBlockMessage(String code, String addressLine) {
    String who = addressLine == null || addressLine.isEmpty() ? "该地址" : addressLine;
    if (code == null) return who + "当前可用于新单据";
    return switch (code) {
      case BLOCK_ADDRESS_DELETED -> who + "已被删除，不能用于新单据（已发生单据上的地址照常显示）";
      case BLOCK_PARTY_DELETED -> who + "所属的往来单位已被删除，不能用于新单据";
      case BLOCK_PARTY_INACTIVE -> who + "所属的往来单位已停用，启用后才能用于新单据";
      case BLOCK_PARTY_BLACKLISTED -> who + "所属的往来单位在拉黑名单里，解除拉黑后才能用于新单据";
      default -> who + "当前不可选";
    };
  }

  // ============================================================ 展示

  public static String usageLabel(String value) {
    String usage = normalizeUsage(value);
    return switch (usage) {
      case "SHIP_TO" -> "收货地址";
      case "SHIP_FROM" -> "发货地址";
      case "PICKUP" -> "提货地址";
      default -> usage.isEmpty() ? "未知用途" : usage;
    };
  }

  /** 用途的固定展示顺序（收货 → 发货 → 提货）。前端徽章与下拉都用它，顺序才稳定。 */
  public static List<String> orderedUsages(List<String> usages) {
    List<String> present = new ArrayList<>();
    for (String usage : USAGE_ORDER)
      if (normalizeUsage(usage) != null && contains(usages, usage)) present.add(usage);
    return present;
  }

  private static boolean contains(List<String> usages, String value) {
    if (usages == null) return false;
    String target = normalizeUsage(value);
    for (String usage : usages) if (normalizeUsage(usage).equals(target)) return true;
    return false;
  }

  /** 界面上一行的用途摘要，如「收货地址 · 默认发货地址」。前端不重算，后端给全。 */
  public static String usageSummary(List<String> usages, List<String> defaultUsages) {
    if (usages == null || usages.isEmpty()) return "未标用途";
    List<String> parts = new ArrayList<>();
    for (String usage : orderedUsages(usages))
      parts.add(contains(defaultUsages, usage) ? usageLabel(usage) + "（默认）" : usageLabel(usage));
    return String.join(" · ", parts);
  }

  /**
   * 拼一条单行地址（面单 / 列表 / 候选下拉都用它）。
   *
   * <p>由服务端拼而不是前端拼：各家地址的顺序不一样（美式「城市, 州 邮编」、 大陆式「省市区街道」），浏览器里拼出来的结果与 10 物流打印出来的对不上 是最难查的一类问题。
   */
  public static String oneLine(
      String streetLine1,
      String streetLine2,
      String district,
      String city,
      String stateProvince,
      String postalCode,
      String countryCode) {
    List<String> parts = new ArrayList<>();
    addIfPresent(parts, streetLine1);
    addIfPresent(parts, streetLine2);
    addIfPresent(parts, district);
    addIfPresent(parts, city);
    addIfPresent(parts, stateProvince);
    addIfPresent(parts, postalCode);
    // 国家**总是**追加（V31 里 country_code NOT NULL）：面单与报关都要它，
    // 而漏掉它的症状是「寄得到、报不了关」。
    if (countryCode != null && !countryCode.trim().isEmpty())
      parts.add(normalizeCountry(countryCode));
    return String.join(" ", parts);
  }

  private static void addIfPresent(List<String> parts, String value) {
    String text = normalizeText(value);
    if (!text.isEmpty()) parts.add(text);
  }

  private static String firstNonNull(String... values) {
    for (String value : values) if (value != null) return value;
    return null;
  }

  /** 供服务层复用：空串归一成 null（DB 里「没填」只存 null）。 */
  static String blankToNull(String value) {
    return emptyToNull(value);
  }
}
