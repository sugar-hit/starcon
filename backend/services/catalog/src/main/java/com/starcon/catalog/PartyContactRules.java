package com.starcon.catalog;

import static com.starcon.catalog.PartyAddressRules.BLOCK_PARTY_DELETED;
import static com.starcon.catalog.PartyAddressRules.BLOCK_PARTY_INACTIVE;
import static com.starcon.catalog.PartyRules.emptyToNull;
import static com.starcon.catalog.PartyRules.normalizeText;

/**
 * 3.6.3.1 联系人的纯规则：姓名与联系方式校验、至少给一项（规则 C1）、
 * 默认联系人约束与删除前置、选中阻断判定。
 *
 * <p>与 {@link PartyRules} / {@link PartyAddressRules} 同一批纪律：
 * 判定只有一处实现，规则 C1 与 C2 在
 * {@code V31__party_addresses_contacts.sql} 第九节有同样的文字，
 * 服务层这里与前端 {@code party-contact-model.ts} 是它的两份实现，改一处必须同步三处。
 *
 * <p>本类不碰数据库，可以在离线单测里穷举。
 */
public final class PartyContactRules {
  private PartyContactRules() {}

  // ============================================================ 长度上限（与 V31 列宽逐字一致）

  /** 姓名长度上限，与 {@code party_contact.name varchar(100)} 一致。 */
  public static final int MAX_NAME_LENGTH = 100;

  /** 职务长度上限，与 {@code party_contact.position varchar(100)} 一致。 */
  public static final int MAX_POSITION_LENGTH = 100;

  /** 邮箱长度上限 254，是 RFC 5321 的路径上限，不是随手取的数。 */
  public static final int MAX_EMAIL_LENGTH = 254;

  /** 电话 / 手机号长度上限，与 {@code varchar(64)} 一致（含国家码与分机）。 */
  public static final int MAX_PHONE_LENGTH = 64;

  /** 备注长度上限，与 {@code party_contact.note} 的服务层约束一致。 */
  public static final int MAX_NOTE_LENGTH = 2000;

  // ============================================================ 校验

  /** 姓名校验。必填：06 采购单上「供应商联系人」那一栏要的是人名，不是公司名。 */
  public static String nameProblem(String value) {
    String name = normalizeText(value);
    if (name.isEmpty()) return "联系人姓名不能为空：往来单位与对接人是两回事，主体名不能替代这里";
    if (name.length() > MAX_NAME_LENGTH) return "联系人姓名最多 " + MAX_NAME_LENGTH + " 个字符";
    return null;
  }

  public static String positionProblem(String value) {
    if (value == null || value.isEmpty()) return null;
    if (value.trim().isEmpty()) return "职务填了但只有空格，请清空或填写内容";
    if (value.length() > MAX_POSITION_LENGTH) return "职务最多 " + MAX_POSITION_LENGTH + " 个字符";
    return null;
  }

  /** 邮箱校验。与 {@link PartyRules#emailProblem} 同一口径。 */
  public static String emailProblem(String value) {
    if (value == null || value.isEmpty()) return null;
    String email = value.trim();
    if (email.length() > MAX_EMAIL_LENGTH) return "邮箱最多 " + MAX_EMAIL_LENGTH + " 个字符";
    if (email.contains(" ")) return "邮箱不能包含空格";
    if (!email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$"))
      return "邮箱格式不正确，请填写类似 purchasing@example.com 的地址";
    return null;
  }

  /**
   * 电话与手机号校验。与 {@link PartyRules#phoneProblem} 同一口径：
   * 只允许数字与 {@code + - ( ) . }，分机用 {@code -} 连接 ——
   * 允许字母会让 06 采购单上的号码在拨号时变成一串读不出来的字符。
   */
  public static String phoneProblem(String value, String label) {
    if (value == null || value.isEmpty()) return null;
    String phone = value.trim().replace(" ", "");
    if (phone.length() > MAX_PHONE_LENGTH) return label + "最多 " + MAX_PHONE_LENGTH + " 个字符";
    if (!phone.matches("^[+0-9()\\-.]+$"))
      return label + "只能包含数字与 + - ( ) . ，请填写如 +86-138-0000-0000（分机请用 - 连接）";
    return null;
  }

  public static String phoneProblem(String value) {
    return phoneProblem(value, "电话");
  }

  public static String mobileProblem(String value) {
    return phoneProblem(value, "手机号");
  }

  public static String noteProblem(String value) {
    if (value == null) return null;
    if (value.length() > MAX_NOTE_LENGTH) return "联系人备注最多 " + MAX_NOTE_LENGTH + " 个字符";
    return null;
  }

  /**
   * 联系方式整体校验：邮箱 / 电话 / 手机<b>至少给一项</b>（规则 C1）。
   *
   * <p>与 V27 的 P3 同源：三个都空的联系人是一行在 06 采购问交期、10 物流约提货、
   * 11 售后约退货时都用不了的记录，而它看上去和真联系人完全一样 ——
   * 那正是 3.9.2.2 僵尸主数据清理要处理的形态。所以被拒的应该是**建这一行**，
   * 不是等它变成僵尸之后再清理。
   */
  public static String reachProblem(String email, String phone, String mobile) {
    // 「填了但只有空格」先单独判：它比「至少给一项」更具体，而且直接说清了该做什么。
    // 不先判的话，下面那几条格式校验会把 "   " 报成「邮箱格式不正确」——
    // 那条提示让运营以为格式写错了，实际上他只是多按了几下空格。
    String dirty =
        firstNonNull(
            blankButPresent(email, "邮箱"), blankButPresent(phone, "电话"), blankButPresent(mobile, "手机号"));
    if (dirty != null) return dirty;
    String format = firstNonNull(emailProblem(email), phoneProblem(phone, "电话"), mobileProblem(mobile));
    if (format != null) return format;
    boolean blank =
        normalizeText(email).isEmpty()
            && normalizeText(phone).isEmpty()
            && normalizeText(mobile).isEmpty();
    if (blank)
      return "邮箱、电话、手机号至少填写一项：06 采购问交期、10 物流约提货、11 售后约退货都要靠它"
          + "联系到这个人，三项都空的联系人在任何一张单据上都无法使用";
    return null;
  }

  /** 只有空格、不是 null 也不是空串 —— 脏数据（DB 里存下来会像一个有值的字段）。 */
  private static String blankButPresent(String value, String label) {
    if (value == null || value.isEmpty()) return null;
    if (value.trim().isEmpty()) return label + "填了但只有空格，请清空或填写内容";
    return null;
  }

  /** 一条联系人请求的整体校验，返回第一条可执行的原因。 */
  public static String contactProblem(
      String name,
      String position,
      String email,
      String phone,
      String mobile,
      String note) {
    return firstNonNull(
        nameProblem(name),
        positionProblem(position),
        reachProblem(email, phone, mobile),
        noteProblem(note));
  }

  // ============================================================ C2 默认联系人

  /**
   * 删除前置：这条还是默认联系人（规则 C2）。
   *
   * <p>与地址的 A4 同一个理由：「这个供应商找谁签字」不能因为一次删除就静默消失。
   * 报错要说明该做什么（先改默认），而不是只说「不能删」。
   */
  public static String deleteProblem(boolean isPrimary) {
    if (!isPrimary) return null;
    return "这条还是默认联系人，请先把默认联系人改到别的联系人再删除："
        + "「这个供应商找谁签字」不能因为一次删除就静默消失";
  }

  /**
   * 设默认的前置：这条联系人自己已被软删除 —— 联系人没有软删除字段，
   * 这里只是把「行还在不在」这件事写成一个显式判定（服务层在查行时已经知道 404），
   * 保留这个方法是为了让 A6 / C2 的形状在两个模块里一致可读。
   */
  public static String primaryProblem(String partyStatus) {
    if (!"BLACKLISTED".equals(normalizeText(partyStatus))) return null;
    return "该往来单位在拉黑名单里，不能设默认联系人：请先解除拉黑"
        + "（停用状态下仍可指定默认联系人 —— 启用后立即生效）";
  }

  // ============================================================ A6 选中阻断（06 / 11 / 10 的下拉口径）

  /** 归属主体已被软删除。 */
  public static final String BLOCK_PARTY_DELETED = PartyAddressRules.BLOCK_PARTY_DELETED;

  /** 归属主体已停用。 */
  public static final String BLOCK_PARTY_INACTIVE = PartyAddressRules.BLOCK_PARTY_INACTIVE;

  /** 归属主体已拉黑。与 {@link PartyAddressRules#BLOCK_PARTY_BLACKLISTED} 同一个码。 */
  public static final String BLOCK_PARTY_BLACKLISTED = PartyAddressRules.BLOCK_PARTY_BLACKLISTED;

  /** 一次选中阻断判定所需的全部输入。联系人没有自己的软删除字段，所以只有主体两个开关。 */
  public record SelectionInputs(boolean partyDeleted, String partyStatus) {}

  /**
   * 判定一个联系人当前能否被下游单据选中（规则 A6）。
   *
   * <p>与 {@link PartyAddressRules#selectionBlock} 同一形态、同一优先级，
   * 差别只是联系人没有自己的删除状态，所以少一个 {@code ADDRESS_DELETED} 分支。
   *
   * @return null 表示可选；否则是原因码
   */
  public static String selectionBlock(SelectionInputs inputs) {
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
      case BLOCK_PARTY_DELETED -> "主体已删除";
      case BLOCK_PARTY_INACTIVE -> "主体已停用";
      case BLOCK_PARTY_BLACKLISTED -> "主体已拉黑";
      default -> "不可选";
    };
  }

  /** 原因码 → 运营该做什么。三个原因的处理动作不同，所以文案也不同。 */
  public static String selectionBlockMessage(String code, String contactName) {
    String who = contactName == null || contactName.isEmpty() ? "该联系人" : contactName;
    if (code == null) return who + "当前可用于新单据";
    return switch (code) {
      case BLOCK_PARTY_DELETED -> who + "所属的往来单位已被删除，不能用于新单据";
      case BLOCK_PARTY_INACTIVE -> who + "所属的往来单位已停用，启用后才能用于新单据";
      case BLOCK_PARTY_BLACKLISTED -> who + "所属的往来单位在拉黑名单里，解除拉黑后才能用于新单据";
      default -> who + "当前不可选";
    };
  }

  // ============================================================ 展示

  /** 联系人的展示名：有职务时带上职务（跨境场景里「采购经理」比名字本身更能定位对接人）。 */
  public static String displayName(String name, String position) {
    String who = normalizeText(name);
    String title = normalizeText(position);
    if (title.isEmpty()) return who;
    if (who.isEmpty()) return title;
    return who + "（" + title + "）";
  }

  /** 供服务层复用：空串归一成 null。 */
  static String blankToNull(String value) {
    return emptyToNull(value);
  }

  private static String firstNonNull(String... values) {
    for (String value : values) if (value != null) return value;
    return null;
  }
}