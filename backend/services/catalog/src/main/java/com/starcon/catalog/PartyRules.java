package com.starcon.catalog;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 3.6.1.1 往来单位档案的纯规则：编码与名称规范化、主体类型与联系方式校验、 状态词表、状态流转判定、角色词表与角色指派约束、软删除前置条件。
 *
 * <p>与 {@link BrandRules} / {@link CategoryTreeRules} / {@link SpecAxisRules} / {@link UnitRules}
 * 同一条纪律：**能在保存前判定的，就不要留到下游才炸**。 一条既没有邮箱也没有电话的往来单位不会在建档时报错，它会在 06 采购问交期、 11 售后问退货、12
 * 财务对账的三处各自用不同的方式暴露出来 —— 而那时已经有一张单子挂在它上面了。
 *
 * <p>与 {@link BrandRules} 同一批纪律的另一条：**判定只有一处实现**。 P1~P7 七条语义在 {@code V27__party_profile.sql}
 * 第六节有同样的文字， 服务层这里与前端 {@code party-model.ts} 是它的两份实现，改一处必须同步三处。
 *
 * <p>本类不碰数据库，可以在离线单测里穷举。
 *
 * <p>类名不叫 {@code Party}：那是被静态导入的 DTO record（{@link CatalogModels}）， 两者在同一批文件里会直接撞名，编译期才炸。与 {@link
 * BrandRules} 同一个理由。
 */
public final class PartyRules {
  private PartyRules() {}

  // ============================================================ 长度上限（与 V27 列宽逐字一致）

  /** 编码长度上限，与 {@code party.code varchar(64)} 一致。 */
  public static final int MAX_CODE_LENGTH = 64;

  /** 名称长度上限，与 {@code party.name varchar(200)} 一致。 */
  public static final int MAX_NAME_LENGTH = 200;

  /** 邮箱长度上限 254，是 RFC 5321 的路径上限，不是随手取的数。 */
  public static final int MAX_EMAIL_LENGTH = 254;

  /** 电话长度上限，与 {@code party.phone varchar(64)} 一致（含国家码与分机）。 */
  public static final int MAX_PHONE_LENGTH = 64;

  /** 官网长度上限，与 {@code party.website varchar(500)} 一致。 */
  public static final int MAX_WEBSITE_LENGTH = 500;

  /** 状态理由长度上限。给到 500 是因为拉黑理由经常要写「哪一次纠纷、第几单」。 */
  public static final int MAX_STATUS_REASON_LENGTH = 500;

  /** 角色备注长度上限，与 {@code party_role.note} 的服务层约束一致。 */
  public static final int MAX_ROLE_NOTE_LENGTH = 2000;

  /**
   * 单个主体可指派的角色数量上限。
   *
   * <p>蓝图只给了三种角色（供应商/客户/承运商），所以真实上限是 3；留 10 是为了 3.6.1.2 落地后如果扩展角色类型（分销商、代理商）不至于撞上限。
   */
  public static final int MAX_ROLES = 10;

  // ============================================================ 词表

  /** 主体类型：企业 / 个人。蓝图职责第一句的「企业或个人」。 */
  public static final Set<String> PARTY_TYPES = Set.of("ORGANIZATION", "INDIVIDUAL");

  /** 主体状态：可用 / 停用 / 拉黑。 */
  public static final Set<String> STATUSES = Set.of("ACTIVE", "INACTIVE", "BLACKLISTED");

  /** 角色类型：供应商 / 客户 / 承运商。蓝图职责点名的三个身份。 */
  public static final Set<String> ROLE_TYPES = Set.of("SUPPLIER", "CUSTOMER", "CARRIER");

  /** 角色指派自身的启停。 */
  public static final Set<String> ROLE_STATUSES = Set.of("ACTIVE", "INACTIVE");

  /**
   * 状态流转表（规则 **P4**）。key = 变更前，value = 允许变更到的状态。
   *
   * <p>刻意**不**包含自环（「停用 → 停用」）：一次「启用」如果本来就已启用， 应当报「状态没有变化」而不是悄悄 +1 版本号骗过乐观锁 ——
   * 那种静默成功最难排查，因为它看起来完全正常。
   */
  public static final java.util.Map<String, Set<String>> STATUS_TRANSITIONS =
      java.util.Map.of(
          "ACTIVE", Set.of("INACTIVE", "BLACKLISTED"),
          "INACTIVE", Set.of("ACTIVE", "BLACKLISTED"),
          // 拉黑只能转停用或解除拉黑（见 P4 注释：两个状态的业务含义不同）。
          "BLACKLISTED", Set.of("INACTIVE", "ACTIVE"));

  // ============================================================ 规范化

  /**
   * 编码规范化：去空白 + 大写。
   *
   * <p>形态与 {@link BrandRules#normalizeCode} 逐字一致：编码是 06 采购单 / 12 应付 / 11
   * 售后单对齐这个主体的机器标识，不接受中文，也不接受大小写两套写法 —— 两套写法的症状是「同一个供应商建了两次编码」而两边都不知道对方存在。
   */
  public static String normalizeCode(String value) {
    return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
  }

  public static String normalizeName(String value) {
    return value == null ? "" : value.trim();
  }

  public static String normalizeText(String value) {
    return value == null ? "" : value.trim();
  }

  public static String normalizeStatus(String value) {
    return value == null ? "" : value.trim();
  }

  public static String normalizeRoleType(String value) {
    return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
  }

  /** 空串归一成 null：DB 里存「没填」只存 null，空白串是脏数据。 */
  public static String emptyToNull(String value) {
    String trimmed = normalizeText(value);
    return trimmed.isEmpty() ? null : trimmed;
  }

  // ============================================================ P1 编码

  /** 编码校验。返回 null 表示可用；否则给出可执行的原因。 */
  public static String codeProblem(String value) {
    String code = normalizeCode(value);
    if (code.isEmpty()) return "往来单位编码不能为空";
    if (code.length() > MAX_CODE_LENGTH) return "往来单位编码最多 " + MAX_CODE_LENGTH + " 个字符";
    if (!code.matches("[A-Z0-9][A-Z0-9_-]{0,63}")) return "往来单位编码请使用英文字母、数字、下划线或短横线，长度 1~64 位";
    return null;
  }

  /**
   * 编码是否允许修改（规则 **P1**）。
   *
   * <p>code 是下游单据对齐这个主体的锚点，改编码会让已有单据在下一次读取时 突然「找不到这个供应商」。要改请新建一条主体。
   *
   * @return null 表示允许；否则是拒绝原因
   */
  public static String codeChangeProblem(String storedCode, String submittedCode) {
    String stored = normalizeCode(storedCode);
    String submitted = normalizeCode(submittedCode);
    if (stored.equals(submitted)) return null;
    return "往来单位编码建后不可修改：采购单、应付账款与售后单都按 code 对齐这个主体，" + "改编码会让已有单据在下一次读取时报「找不到这个往来单位」。要改请新建一条主体";
  }

  // ============================================================ P2 名称与主体类型

  /**
   * 名称校验。
   *
   * <p>企业的法定名称要带后缀（Co., Ltd / GmbH / S.A.），200 列是为此留的； 只判长度与非空，不判字符集 —— 中文与日文公司名是常态。
   */
  public static String nameProblem(String value) {
    String name = normalizeName(value);
    if (name.isEmpty()) return "往来单位名称不能为空";
    if (name.length() > MAX_NAME_LENGTH) return "往来单位名称最多 " + MAX_NAME_LENGTH + " 个字符";
    return null;
  }

  /** 主体类型校验（规则 **P2**）。 */
  public static String partyTypeProblem(String value) {
    String type = value == null ? "" : value.trim();
    if (type.isEmpty()) return "主体类型不能为空";
    if (!PARTY_TYPES.contains(type)) return "主体类型只能是 ORGANIZATION（企业）或 INDIVIDUAL（个人）";
    return null;
  }

  /**
   * 注册国校验：可空；填了就必须是 ISO 3166-1 alpha-2 两位大写。
   *
   * <p>小写会被规范化成大写，所以 {@code cn} 与 {@code CN} 视为同一个国家 —— 混用大小写的症状是按国家分组统计时同一个国家出现两行。
   */
  public static String countryProblem(String value) {
    String code = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    if (code.isEmpty()) return null; // 可空，不是漏填
    if (code.length() != 2) return "国家代码请用 ISO 3166-1 alpha-2 两位代码，如 CN / DE / US";
    if (!code.matches("[A-Z]{2}")) return "国家代码请用 ISO 3166-1 alpha-2 两位代码，如 CN / DE / US";
    return null;
  }

  /** 国家代码规范化（去空白 + 大写）；空串归 null。 */
  public static String normalizeCountry(String value) {
    String code = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    return code.isEmpty() ? null : code;
  }

  // ============================================================ P3 联系方式

  /** 邮箱校验。刻意**只**查形态不查可达性 —— 校验「能不能收到信」要发一次网络请求， 那会让每次保存都卡住几秒，而拼错的邮箱在 12 财务对账那天一定会被发现。 */
  public static String emailProblem(String value) {
    String email = normalizeText(value);
    if (email.isEmpty()) return null; // 可空，由 contactProblem 决定整体是否够用
    if (email.length() > MAX_EMAIL_LENGTH) return "邮箱最多 " + MAX_EMAIL_LENGTH + " 个字符";
    if (email.contains(" ")) return "邮箱不能包含空格";
    // 简单形态：本地部分 + @ + 域名，且域名里必须有一个点且不含空格。
    // 不追求 RFC 完全合规（引号本地部分那种在 B2B 场景里见不到），
    // 但足以挡住「漏打 @」「把两个邮箱粘在一起」这两类真实事故。
    if (!email.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+"))
      return "邮箱格式不正确，请填写类似 purchasing@example.com 的地址";
    return null;
  }

  /**
   * 电话规范化：去首尾空白 + <b>去掉内部所有空格</b>。
   *
   * <p>刻意<b>不是</b>「含空格就拒」：B2B 场景里 {@code +86 (138) 0000-0000} 是最常见的写法之一，为它弹一条「不能包含空格」只会让人绕开校验。
   * 空格对拨打没有任何影响，所以规范化掉比拒绝掉更贴近真实用途 （与 {@link BrandRules#logoProblem} 拒空格的结论正好相反 —— 那里是
   * URI，空格会让引用失效；这里是电话号码，格式化才是常态）。
   */
  public static String normalizePhone(String value) {
    return normalizeText(value).replace(" ", "");
  }

  /**
   * 电话校验：可空；去掉空格后必须只剩可拨打的字符。
   *
   * <p>拒绝中文「转」（{@code 13800001234转123}）：它让这列不再是可拨打的串， 而分机有国际通用的写法（{@code
   * 13800001234-123}），要求运营改用那种更省事。
   */
  public static String phoneProblem(String value) {
    String phone = normalizePhone(value);
    if (phone.isEmpty()) return null;
    if (phone.length() > MAX_PHONE_LENGTH) return "电话最多 " + MAX_PHONE_LENGTH + " 个字符";
    if (!phone.matches("[+0-9()\\-.]+"))
      return "电话只能包含数字与 + - ( ) . ，请填写如 +86-138-0000-0000" + "（分机请用 - 连接，如 +86-138-0000-0000-123）";
    return null;
  }

  /** 官网校验：可空；填了不能含空格（未编码的空格不是合法 URI）。 */
  public static String websiteProblem(String value) {
    String site = normalizeText(value);
    if (site.isEmpty()) return null;
    if (site.length() > MAX_WEBSITE_LENGTH) return "官网最多 " + MAX_WEBSITE_LENGTH + " 个字符";
    if (site.contains(" ")) return "官网不能包含空格，请填写完整 URL 或不含空格的域名";
    return null;
  }

  /**
   * 联系方式整体校验（规则 **P3**）：**邮箱与电话至少给一项**。
   *
   * <p>一张既没有邮箱也没有电话的主体档案，在 06 采购（问交期）、11 售后（问退货）、 12 财务（对账）三处都用不了。允许它建档只会造出一批「查得到但联系不上」的僵尸记录， 而
   * 3.9.2.2 僵尸主数据清理要处理的正是这类东西。
   *
   * <p><b>被拒的应该是建档</b>，因为这种记录从一开始就无法履约 —— 与 V16 对「建档不要求授权凭证、被拒的应该是刊登」不同：
   * 凭证是<b>可以稍后补</b>的运营事实，联系方式不是（补联系方式是新建一条主体的事）。
   */
  public static String contactProblem(String email, String phone, String website) {
    String emailIssue = emailProblem(email);
    if (emailIssue != null) return emailIssue;
    String phoneIssue = phoneProblem(phone);
    if (phoneIssue != null) return phoneIssue;
    String siteIssue = websiteProblem(website);
    if (siteIssue != null) return siteIssue;
    if (normalizeText(email).isEmpty() && normalizePhone(phone).isEmpty())
      return "邮箱与电话至少填写一项：06 采购问交期、11 售后问退货、12 财务对账都要靠它联系到这个往来单位，" + "两项都空的档案在任何一张单据上都无法使用";
    return null;
  }

  // ============================================================ P4 状态流转

  /** 状态词表校验。 */
  public static String statusProblem(String value) {
    String status = normalizeStatus(value);
    if (status.isEmpty()) return "状态不能为空";
    if (!STATUSES.contains(status)) return "状态只能是 ACTIVE（可用）、INACTIVE（停用）或 BLACKLISTED（拉黑）";
    return null;
  }

  /**
   * 状态流转校验（规则 **P4**）：方向合法 + 进出拉黑必须留理由。
   *
   * <p>「进出拉黑必须留理由」的理由：没有理由的拉黑在三个月后没人敢解开， 也没人知道该不该解开；而 {@code BLACKLISTED → ACTIVE}（解除拉黑）同样要理由，
   * 因为制裁解除通常需要留档，而「我们自己决定恢复合作」不该借用这个动作。
   *
   * @param from 变更前状态；null 视为新建（新建不走流转判定）
   * @param to 目标状态
   * @param reason 变更理由
   * @return null 表示允许；否则是拒绝原因
   */
  public static String transitionProblem(String from, String to, String reason) {
    String target = normalizeStatus(to);
    String statusIssue = statusProblem(target);
    if (statusIssue != null) return statusIssue;
    String current = normalizeStatus(from);
    if (current.isEmpty()) return null; // 新建：状态由输入直接决定，不走流转判定
    if (!STATUSES.contains(current)) return "当前主体状态异常（" + current + "），请联系管理员";
    if (current.equals(target)) return "状态没有变化（" + target + " → " + target + "）";
    if (!STATUS_TRANSITIONS.getOrDefault(current, Set.of()).contains(target))
      return "不允许的状态流转："
          + current
          + " → "
          + target
          + "。可用流转为 "
          + String.join(" / ", new java.util.TreeSet<>(STATUS_TRANSITIONS.get(current)));
    if (requiresReason(current, target)) {
      String text = normalizeText(reason);
      if (text.isEmpty()) return "进出拉黑状态必须填写理由：三个月后没人能记得这个供应商为什么被拉黑，" + "也没有人敢决定要不要解开它";
      if (text.length() > MAX_STATUS_REASON_LENGTH)
        return "状态理由最多 " + MAX_STATUS_REASON_LENGTH + " 个字符";
    }
    return null;
  }

  /**
   * 某次流转是否必须带理由。
   *
   * <p>规则是「<b>进出</b> BLACKLISTED」而不是「进入 BLACKLISTED」： 解除拉黑通常需要留档（谁批的、依据什么），而只进不出的写法会让
   * 「制裁名单已移除」这个事实无处可查。
   */
  public static boolean requiresReason(String from, String to) {
    return "BLACKLISTED".equals(normalizeStatus(from)) || "BLACKLISTED".equals(normalizeStatus(to));
  }

  /** 该流转是否合法（只看方向，不看理由）。列表页与规则说明用。 */
  public static boolean canTransition(String from, String to) {
    String current = normalizeStatus(from);
    String target = normalizeStatus(to);
    if (current.isEmpty()) return STATUSES.contains(target);
    if (current.equals(target)) return false;
    return STATUS_TRANSITIONS.getOrDefault(current, Set.of()).contains(target);
  }

  /** 某状态下允许流转到的目标集合（界面上把不可点的按钮直接禁用，而不是点了才报错）。 */
  public static Set<String> allowedTargets(String from) {
    String current = normalizeStatus(from);
    if (current.isEmpty()) return STATUSES;
    return STATUS_TRANSITIONS.getOrDefault(current, Set.of());
  }

  // ============================================================ P5 / P6 角色

  /** 角色类型校验。 */
  public static String roleTypeProblem(String value) {
    String role = normalizeRoleType(value);
    if (role.isEmpty()) return "角色类型不能为空";
    if (!ROLE_TYPES.contains(role)) return "角色类型只能是 SUPPLIER（供应商）、CUSTOMER（客户）或 CARRIER（承运商）";
    return null;
  }

  /** 角色指派状态校验。 */
  public static String roleStatusProblem(String value) {
    String status = value == null ? "" : value.trim();
    if (status.isEmpty()) return "角色状态不能为空";
    if (!ROLE_STATUSES.contains(status)) return "角色状态只能是 ACTIVE 或 INACTIVE";
    return null;
  }

  /** 角色备注校验：可空，非空则不能超长。 */
  public static String roleNoteProblem(String value) {
    String note = normalizeText(value);
    if (note.length() > MAX_ROLE_NOTE_LENGTH) return "角色备注最多 " + MAX_ROLE_NOTE_LENGTH + " 个字符";
    return null;
  }

  /**
   * 一整组角色指派的校验：逐条 + 两条集合级约束。
   *
   * <p>集合级约束在服务层做（{@code party_role} 的 {@code UNIQUE(tenant_id,party_id,role_type)} 已经挡住重复写入，
   * 但重复提交会变成一条约束冲突，界面上只看到一句 500）：
   *
   * <ul>
   *   <li>同一角色只能有一条 —— 正常化之后比较（{@code supplier} 与 {@code SUPPLIER} 算同一个），
   *       这正是蓝图「用角色档案区分，不用多份记录」在输入侧的口径；
   *   <li>数量上限（{@link #MAX_ROLES}）。
   * </ul>
   */
  public static String rolesProblem(java.util.List<CatalogModels.PartyRoleInput> roles) {
    if (roles == null || roles.isEmpty()) return null;
    if (roles.size() > MAX_ROLES) return "单个往来单位最多指派 " + MAX_ROLES + " 个角色";
    Set<String> seen = new LinkedHashSet<>();
    for (CatalogModels.PartyRoleInput role : roles) {
      if (role == null) return "角色列表里不能有空项";
      String typeIssue = roleTypeProblem(role.roleType());
      if (typeIssue != null) return typeIssue;
      String statusIssue = roleStatusProblem(role.status());
      if (statusIssue != null) return statusIssue;
      String noteIssue = roleNoteProblem(role.note());
      if (noteIssue != null) return noteIssue;
      if (!seen.add(normalizeRoleType(role.roleType())))
        return "同一个角色只能指派一次：" + normalizeRoleType(role.roleType()) + "（一个主体可以同时是供应商和客户，但供应商身份只有一条）";
    }
    return null;
  }

  /**
   * 新增角色指派是否被允许（规则 **P6**）。
   *
   * <p><b>只对拉黑状态拒</b>，停用状态允许：拉黑是「不与这个对手方做生意」， 给它新增一个商业身份正是要拦的动作；而 INACTIVE 是「我们自己暂时不用这个主体」， 规则 P5
   * 的正常流程恰恰要求在停用状态下先把角色挂好再启用 （建档即 ACTIVE 且必须带角色，会让「先建主体、后定角色」这个正常业务流走不通）。
   *
   * <p>解指派与改备注在任何状态下都允许 —— 清理停用 / 拉黑主体的角色正是这两件事之后要做的动作。
   */
  public static String assignRoleProblem(
      String partyStatus, String roleType, boolean alreadyAssigned) {
    String statusIssue = statusProblem(partyStatus);
    if (statusIssue != null) return statusIssue;
    String typeIssue = roleTypeProblem(roleType);
    if (typeIssue != null) return typeIssue;
    if ("BLACKLISTED".equals(normalizeStatus(partyStatus)))
      return "往来单位在拉黑名单里，不能再指派新角色：请先解除拉黑"
          + "（停用状态可以正常指派角色 —— 那是「先建主体、挂好角色、再启用」的正常流程；"
          + "解指派与改备注在拉黑状态下也不受此限制）";
    if (alreadyAssigned) return "该主体已经指派过 " + normalizeRoleType(roleType) + " 角色，请直接编辑那一条而不是重复指派";
    return null;
  }

  /**
   * 启用（{@code → ACTIVE}）时的主体角色前置条件（规则 **P5**）。
   *
   * <p>没有角色的主体是一条填在所有下拉里、但谁都选不出意义的记录。 允许「先建主体、后指派角色」是正常业务流（服务层不阻止建档）， 但**启用**时才拦住 ——
   * 被拒的应该是「让它在下游可用」，不是「建档」。
   *
   * @param activeRoleCount 该主体当前 ACTIVE 的角色条数
   * @return null 表示允许；否则是拒绝原因
   */
  public static String activateRoleRequirementProblem(String partyStatus, int activeRoleCount) {
    if (!"ACTIVE".equals(normalizeStatus(partyStatus))) return null;
    if (activeRoleCount > 0) return null;
    return "启用前至少要指派一个角色（供应商 / 客户 / 承运商）："
        + "没有角色的往来单位在 06 采购、11 售后、12 财务的下拉里谁都选不出意义，"
        + "只是一条查得到但用不了的记录";
  }

  /**
   * 软删除的前置条件（规则 **P7**）。
   *
   * <p>删除后主体对列表不可见，而它的角色行还在、详情端点直接 404 —— 那种「角色还在但主体不见了」的状态没有任何人能解释，运营也无法自己收拾干净。
   * 所以删除前必须解掉全部角色，报错逐个点名还挂着哪些。
   *
   * @param roleTypes 该主体当前挂着的角色类型（用于点名）
   * @return null 表示允许；否则是拒绝原因
   */
  public static String deleteProblem(java.util.List<String> roleTypes) {
    if (roleTypes == null || roleTypes.isEmpty()) return null;
    java.util.List<String> names = new java.util.ArrayList<>();
    for (String type : roleTypes) names.add(roleLabel(normalizeRoleType(type)));
    return "该往来单位还挂着角色（"
        + String.join("、", names)
        + "），请先解除这些角色指派再删除：删除后主体对列表不可见，"
        + "而角色行还在、详情页直接 404，那种状态没人能解释";
  }

  // ============================================================ 标签（给界面与错误提示用）

  /** 主体类型 → 中文标签。与 V27 的 CHECK 同一词表。 */
  public static String partyTypeLabel(String value) {
    String type = value == null ? "" : value.trim();
    if ("ORGANIZATION".equals(type)) return "企业";
    if ("INDIVIDUAL".equals(type)) return "个人";
    return type.isEmpty() ? "未知" : type;
  }

  /** 主体状态 → 中文标签。 */
  public static String statusLabel(String value) {
    String status = normalizeStatus(value);
    if ("ACTIVE".equals(status)) return "可用";
    if ("INACTIVE".equals(status)) return "停用";
    if ("BLACKLISTED".equals(status)) return "拉黑";
    return status.isEmpty() ? "未知" : status;
  }

  /** 角色类型 → 中文标签。 */
  public static String roleLabel(String value) {
    String role = normalizeRoleType(value);
    if ("SUPPLIER".equals(role)) return "供应商";
    if ("CUSTOMER".equals(role)) return "客户";
    if ("CARRIER".equals(role)) return "承运商";
    return role.isEmpty() ? "未知" : role;
  }

  /**
   * 「这个主体扮演哪些角色」的中文串，给列表页一列直接显示。
   *
   * <p>蓝图那句「同一主体可同时是供应商和客户」在界面上就要一眼看见 —— 如果要逐个点开详情才知道，运营就会去建第二条记录，那正是本模块要消灭的形态。
   *
   * @param roleTypes 角色类型集合（ACTIVE 的）
   * @return 逗号分隔的中文串；没有任何 ACTIVE 角色时给「未指派角色」而不是空串
   */
  public static String roleSummary(java.util.Collection<String> roleTypes) {
    if (roleTypes == null || roleTypes.isEmpty()) return "未指派角色";
    // 按固定词表顺序输出，避免同一组角色在不同查询下顺序不同而让前端 key 抖动。
    java.util.List<String> ordered = new java.util.ArrayList<>();
    for (String role : new String[] {"SUPPLIER", "CUSTOMER", "CARRIER"})
      if (roleTypes.contains(role)) ordered.add(roleLabel(role));
    for (String role : roleTypes) if (!ordered.contains(roleLabel(role))) ordered.add(role);
    return String.join("、", ordered);
  }

  /** 状态流转的人类可读说明，列表页与详情页的提示文案用。 */
  public static String transitionHint(String from, String to) {
    if (requiresReason(from, to)) return "这次流转需要填写理由（进出拉黑状态必须留档）";
    return null;
  }

  // ============================================================ 可用性判定（06 / 11 / 12 的下拉口径）

  /** 主体已停用。 */
  public static final String BLOCK_PARTY_INACTIVE = "PARTY_INACTIVE";

  /** 主体已拉黑（制裁名单 / 纠纷对手方）。 */
  public static final String BLOCK_PARTY_BLACKLISTED = "PARTY_BLACKLISTED";

  /** 主体是 ACTIVE 但一个 ACTIVE 角色都没有 —— 规则 P5 的另一半。 */
  public static final String BLOCK_PARTY_NO_ROLE = "PARTY_NO_ROLE";

  /** 主体已被软删除。 */
  public static final String BLOCK_PARTY_DELETED = "PARTY_DELETED";

  /** 一次可用性判定所需的全部输入。抽成 record 是为了让纯规则不依赖数据库行。 */
  public record UsableInputs(boolean deleted, String status, int activeRoleCount) {}

  /**
   * 判定一个往来单位当前能否被下游单据选中。
   *
   * <p>这是本模块交给 06 采购 / 11 售后 / 12 财务的那份口径（蓝图「输出」= 档案 + 角色档案）， 与 {@code BrandRules.publishBlock}
   * 同一纪律：<b>唯一实现</b>，列表行、详情、候选三个出口都调它。 前端只把 {@code code} 翻成要显示的话（{@code party-model.ts}），不在浏览器里再判一次
   * —— 两处各判一次的症状是「页面说这个供应商可选、采购单说不可选」。
   *
   * <p>判定顺序即优先级：先删除、再拉黑、再停用、最后才是没角色。 这样 {@code blockCode} 永远是<b>最该先解决的那一条</b>：
   * 对一个已删除的主体报「没有角色」是误导（解指派角色并不能让它回来）。
   *
   * @return null 表示可用；否则是原因码
   */
  public static String usableBlock(UsableInputs inputs) {
    if (inputs.deleted()) return BLOCK_PARTY_DELETED;
    String status = normalizeStatus(inputs.status());
    if ("BLACKLISTED".equals(status)) return BLOCK_PARTY_BLACKLISTED;
    if (!"ACTIVE".equals(status)) return BLOCK_PARTY_INACTIVE;
    if (inputs.activeRoleCount() <= 0) return BLOCK_PARTY_NO_ROLE;
    return null;
  }

  /** 原因码 → 界面标签。与 {@link #usableBlock} 同一份词表。 */
  public static String usableBlockLabel(String code) {
    if (code == null) return "可用";
    return switch (code) {
      case BLOCK_PARTY_INACTIVE -> "已停用";
      case BLOCK_PARTY_BLACKLISTED -> "已拉黑";
      case BLOCK_PARTY_NO_ROLE -> "未指派角色";
      case BLOCK_PARTY_DELETED -> "已删除";
      default -> "不可用";
    };
  }

  /** 原因码 → 运营该做什么。四个原因的处理动作不同，所以文案也不同。 */
  public static String usableBlockMessage(String code, String name) {
    String who = name == null || name.isEmpty() ? "该往来单位" : name;
    if (code == null) return who + "当前可用于新单据";
    return switch (code) {
      case BLOCK_PARTY_INACTIVE -> who + "已停用，启用后才能用于新单据（历史单据照常显示）";
      case BLOCK_PARTY_BLACKLISTED -> who + "在拉黑名单里，解除拉黑后才能用于新单据";
      case BLOCK_PARTY_NO_ROLE -> who + "还没有任何 ACTIVE 角色，指派供应商 / 客户 / 承运商身份后即可使用";
      case BLOCK_PARTY_DELETED -> who + "已被删除，不能用于新单据";
      default -> who + "当前不可用";
    };
  }
}
