package com.starcon.identity;

import static com.starcon.identity.AccountLocaleModels.AccountLocaleCapabilities;
import static com.starcon.identity.AccountLocaleModels.AccountLocaleInput;
import static com.starcon.identity.AccountLocaleModels.AccountLocaleView;
import static com.starcon.identity.AccountLocaleModels.AccountLocaleCapabilityDimension;

import com.starcon.identity.UserPreferenceModels.UserPreferenceInput;
import com.starcon.identity.UserPreferenceModels.UserPreferenceView;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 1.4.2.1 语言与时区 —— 「界面语言 + 展示时区」的读写。
 *
 * <p><b>存储复用 1.4.1.2 的 {@code user_preference} 表</b>（页面 key = {@code account.locale}）：
 * 本模块记的是「我上次选的界面语言与展示时区」，与「我上次把列表调成什么样」同属
 * <b>纯个人偏好</b>，同属 identity 服务，同一张表、同一套 jOOQ 显式 SQL、同一套载荷形状校验
 * （{@link UserPreferencePayloadRules}）。所以这里<b>不建表、不 ALTER</b>，
 * 读写直接走 {@link UserPreferenceService}。
 *
 * <p><b>本模块额外做的一件事</b>：{@link UserPreferencePayloadRules} 守的是「结构安全」
 * （键名、深度、体积、敏感字段），<b>不</b>守「这个时区是不是真的存在」。
 * 所以本服务在写入前补一层语义校验（{@link AccountLocaleRules}），
 * 读取时再做一层<b>防御性回落</b>：万一有人绕过本服务、用通用偏好端点写了
 * {@code {"timezone":"Not/AZone"}}，读回来时必须回落到默认时区并说清原因，
 * 而不是把一个渲染会抛异常的时区发给前端。
 *
 * <p><b>三条语义边界</b>（蓝图原文，写在这里以免被后来人改坏）：
 *
 * <ol>
 *   <li><b>不改变存储时区</b>：库里一律 {@code timestamptz}，本模块只影响展示层。
 *       本服务不读任何业务单据的时间列，也不写任何时间列。</li>
 *   <li><b>不定义业务日期口径</b>：「订单属于哪一天」由各单据自己定义，
 *       本模块不提供任何「全局业务时区」。</li>
 *   <li><b>不改既有渲染</b>：本模块提供「时区参数 + 渲染口径」，
 *       全站每个 {@code new Date()} 的迁移是另一个模块的事。</li>
 * </ol>
 *
 * <p><b>租户与 membership_id 都来自已认证会话</b>：两者都不是请求参数，更不是请求头。
 */
@Service
public class AccountLocaleService {

  private final UserPreferenceService preferences;

  public AccountLocaleService(UserPreferenceService preferences) {
    this.preferences = preferences;
  }

  /** 读我当前生效的界面语言与展示时区（没存过就是默认值，{@code stored=false}）。 */
  public AccountLocaleView read(UUID tenantId, UUID membershipId) {
    return toView(preferences.read(tenantId, membershipId, AccountLocaleRules.PAGE_KEY));
  }

  /**
   * 保存我选的界面语言与展示时区（**upsert**：同一 page key 覆盖，不新增行）。
   *
   * <p>两个字段都可以只给一个：没给的保持原值（没存过就保持默认）。
   */
  public AccountLocaleView save(UUID tenantId, UUID membershipId, AccountLocaleInput input) {
    AccountLocaleView current = read(tenantId, membershipId);
    String locale =
        input == null || input.locale() == null
            ? current.locale()
            : AccountLocaleRules.normalizeLocale(input.locale());
    String timezone =
        input == null || input.timezone() == null
            ? current.timezone()
            : AccountLocaleRules.normalizeTimezone(input.timezone());
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("locale", locale);
    payload.put("timezone", timezone);
    UserPreferenceView stored =
        preferences.save(
            tenantId, membershipId, AccountLocaleRules.PAGE_KEY, new UserPreferenceInput(payload));
    return toView(stored);
  }

  /** 重置（删掉那一行 = 回到默认）。幂等：本来没存过也算成功。 */
  public int reset(UUID tenantId, UUID membershipId) {
    return preferences.reset(tenantId, membershipId, AccountLocaleRules.PAGE_KEY);
  }

  /**
   * 能力声明：**逐条如实报**。
   *
   * <p>做不到的维度写 {@code available=false}，并写清「缺什么、归谁」——
   * 一个只报「语言不可用」而不说原因的开关，界面上没法解释，用户只会以为坏了。
   */
  public AccountLocaleCapabilities capabilities() {
    List<AccountLocaleCapabilityDimension> dimensions =
        List.of(
            new AccountLocaleCapabilityDimension(
                "displayTimezone",
                true,
                "1.4.2.1",
                "展示时区可读、可写、可重置，并且真实影响时间显示；"
                    + "只作用于展示层，存储时区仍然是 timestamptz。",
                List.of()),
            new AccountLocaleCapabilityDimension(
                "interfaceLocale",
                false,
                "未立项（本仓蓝图里没有界面 i18n 运行时这一格）",
                "界面语言当前只有简体中文可用：仓库没有 i18n 运行时（语言上下文）与语言包，"
                    + "全站文案仍是硬编码中文，所以不提供语言切换开关 —— "
                    + "提供了也只是假开关。数据侧已按 zh-CN 落库，语言包到位后直接可用。",
                List.of(
                    "i18n 运行时（语言上下文 + 缺键回落 + 语言切换入口）",
                    "语言包（zh-CN / en-US 两套文案）",
                    "全站文案的 t() 化（这是最大的一块工作量）")),
            new AccountLocaleCapabilityDimension(
                "platformSiteTimezoneCatalog",
                true,
                "02 渠道与店铺（2.1.1.3 站点与国家参数）",
                "平台站点时区清单来自 channel 服务的 marketplace.default_timezone，"
                    + "只读；identity 不跨库读 channel。",
                List.of()),
            new AccountLocaleCapabilityDimension(
                "businessDateSemantics",
                false,
                "各单据模块（09 订单履约 / 11 售后 / 12 财务 …）",
                "「订单属于哪一天」由各单据自己的口径决定，本模块不提供全局业务时区，"
                    + "也不改任何单据的日期分组逻辑。",
                List.of()));
    List<String> boundaries =
        List.of(
            "不改变存储时区：库里一律 timestamptz，本模块只影响展示层。",
            "不定义业务日期口径：业务日期由各单据自己定义，本模块不提供全局业务时区。",
            "不改既有渲染：本模块提供时区参数与显示口径，全站时间渲染的逐页迁移是另一个模块的事。");
    return new AccountLocaleCapabilities(
        "1.4.2.1",
        dimensions,
        AccountLocaleRules.SUPPORTED_LOCALES,
        AccountLocaleRules.PLANNED_LOCALES,
        AccountLocaleRules.DEFAULT_LOCALE,
        AccountLocaleRules.DEFAULT_TIMEZONE,
        AccountLocaleRules.DISPLAY_CONVENTION,
        boundaries);
  }

  /**
   * 把存下来的载荷映射成「当前生效」的值。
   *
   * <p><b>回落而不是报错</b>：偏好是锦上添花，读不到 / 不认识就按默认值继续，
   * 绝不因为一条老数据让整页崩掉。但回落必须<b>说清原因</b>（{@code fallbackReason}），
   * 否则用户会以为自己选的时区生效了。
   */
  private AccountLocaleView toView(UserPreferenceView stored) {
    Map<String, Object> payload = stored.preference();
    List<String> reasons = new ArrayList<>();

    String locale = asText(payload.get("locale"));
    if (locale == null) {
      locale = AccountLocaleRules.DEFAULT_LOCALE;
      if (stored.stored()) reasons.add("没有记录界面语言，已按默认 " + locale + " 显示");
    } else if (!AccountLocaleRules.SUPPORTED_LOCALES.contains(locale)) {
      reasons.add(AccountLocaleRules.localeFallbackReason(locale));
      locale = AccountLocaleRules.DEFAULT_LOCALE;
    }

    String timezone = asText(payload.get("timezone"));
    if (timezone == null) {
      timezone = AccountLocaleRules.DEFAULT_TIMEZONE;
      if (stored.stored()) reasons.add("没有记录展示时区，已按默认 " + timezone + " 显示");
    } else if (!AccountLocaleRules.isKnownTimezone(timezone)) {
      reasons.add(AccountLocaleRules.timezoneFallbackReason(timezone));
      timezone = AccountLocaleRules.DEFAULT_TIMEZONE;
    }

    return new AccountLocaleView(
        stored.stored(),
        locale,
        timezone,
        AccountLocaleRules.DEFAULT_LOCALE,
        AccountLocaleRules.DEFAULT_TIMEZONE,
        stored.updatedAt(),
        reasons.isEmpty() ? null : String.join("；", reasons));
  }

  private String asText(Object value) {
    if (!(value instanceof String text)) return null;
    String trimmed = text.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
