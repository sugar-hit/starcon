package com.starcon.identity;

import static com.starcon.identity.NotificationInboxModels.NotificationType;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * 1.3.1.1 通知列表 —— 类型词表与通知键的**纯函数**校验规则。
 *
 * <p>刻意做成不碰数据库的纯函数：这些规则要能被单元测试逐条钉住
 * （哪些键被拒、哪些类型被拒、上限是多少），而不是只能靠集成测试间接观察。
 *
 * <p><b>它守的是「键与词表的形状」</b>：
 *
 * <ul>
 *   <li><b>类型词表是受控的</b>：只有这五个码（与蓝图的五个上游一一对应）能被接受，
 *       自由文本一律拒绝 —— 订阅项与已读标记都必须是这五类之一。</li>
 *   <li><b>键的形状是受控的</b>：{@code <类型>:<上游主键>}，主键的字符集被收紧，
 *       不接收路径分隔符之类的字符。</li>
 * </ul>
 *
 * <p><b>为什么不在前端校验就够</b>：前端是可以被绕过的 ——
 * 任何人拿自己的 token 就能直接打这些接口。所以「键的形状」与「类型词表」
 * 必须在服务端再钉一遍，否则它只是「我们约定不改」，不是「改不了」。
 * 数据库侧另有一道 CHECK 约束（V373），绕过服务层直连 SQL 也一样被拒。
 */
public final class NotificationInboxRules {

  private NotificationInboxRules() {}

  /**
   * 通知类型词表（**受控**，与 V373 的 CHECK 约束逐字同词表）。
   *
   * <p>这五个码与蓝图点名的五个上游一一对应，一个不多一个不少：
   * 06 缺货 / 09 超时 / 10 异常 / 12 对账差异 / 15 站内信。
   */
  public static final List<NotificationType> TYPES =
      List.of(
          new NotificationType(
              "STOCKOUT", "库存缺货", "07 库存", "warehouse/inventory/stockout-alerts/stockouts"),
          new NotificationType(
              "TIMELINESS_OVERDUE", "履约超时", "09 订单", "trade/fulfillment/timeliness/overdue"),
          new NotificationType("SYNC_FAILURE", "同步失败", "02 渠道", "channel/sync-failures"),
          new NotificationType(
              "RECONCILIATION_DIFF", "对账差异", "12 财务", "finance/supplier-reconciliation"),
          new NotificationType("MESSAGE", "站内信", "15 客户", "crm/message-inbox/messages"));

  /** 单次批量标记的键数上限（防呆，同时把请求体压在合理尺寸内）。 */
  public static final int MAX_KEYS = 500;

  /** 上游主键的字符集：字母 / 数字 / 点 / 下划线 / 连字符 / 冒号（冒号允许带一段后缀）。 */
  public static final Pattern REF = Pattern.compile("^[A-Za-z0-9._:-]{1,120}$");

  /** 类型码的字符集。 */
  public static final Pattern TYPE_CODE = Pattern.compile("^[A-Z][A-Z_]{1,39}$");

  /**
   * 一条通知的键：类型 + 上游主键。
   *
   * @param type 受控类型码
   * @param ref 上游主键（可带一段后缀，如 {@code <factId>:<overdueKind>}）
   */
  public record NotificationKey(String type, String ref) {
    /** 对外暴露的键形状：{@code <类型>:<上游主键>}。 */
    public String key() {
      return type + ":" + ref;
    }
  }

  /** 类型码是否在受控词表里。 */
  public static boolean isKnownType(String code) {
    if (code == null) return false;
    for (NotificationType type : TYPES) {
      if (type.code().equals(code)) return true;
    }
    return false;
  }

  /** 词表里的类型码（按词表顺序）。 */
  public static List<String> typeCodes() {
    List<String> codes = new ArrayList<>(TYPES.size());
    for (NotificationType type : TYPES) codes.add(type.code());
    return codes;
  }

  /**
   * 归一化一个类型码。
   *
   * @throws ResponseStatusException 类型码不在受控词表里（400）
   */
  public static String normalizeType(String rawCode) {
    String code = rawCode == null ? "" : rawCode.trim();
    if (!isKnownType(code)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "通知类型不在受控词表里：" + describe(code));
    }
    return code;
  }

  /**
   * 归一化一批类型码（订阅用）：去重 + 按**词表顺序**排序。
   *
   * <p>按词表顺序排序而不是按输入顺序：这样「订阅集合」在界面与存储里是稳定的，
   * 两次提交同一组类型得到同一份载荷，不会因为前端数组顺序不同而产生无谓的 diff。
   *
   * @throws ResponseStatusException 有类型码不在词表里（400）；或集合为空（400）
   */
  public static List<String> normalizeTypes(List<String> rawCodes) {
    if (rawCodes == null || rawCodes.isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "至少订阅一类通知类型（不能一个都不订阅）");
    }
    LinkedHashSet<String> wanted = new LinkedHashSet<>();
    for (String raw : rawCodes) {
      wanted.add(normalizeType(raw));
    }
    List<String> ordered = new ArrayList<>(wanted.size());
    for (NotificationType type : TYPES) {
      if (wanted.contains(type.code())) ordered.add(type.code());
    }
    return ordered;
  }

  /**
   * 归一化一批通知键：逐条解析 + 校验，**任何一条不合法就整体拒绝**。
   *
   * <p>整体拒绝（而不是「跳过不合法的那几条」）是刻意的：批量标记是写操作，
   * 静默跳过几条会让调用方以为「全都标上了」—— 那比直接报错更糟。
   *
   * @throws ResponseStatusException 键数超限 / 形状不合法 / 类型不在词表里（400）
   */
  public static List<NotificationKey> normalizeKeys(List<String> rawKeys) {
    if (rawKeys == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "通知键集合不能为空");
    }
    if (rawKeys.size() > MAX_KEYS) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "单次最多处理 " + MAX_KEYS + " 条通知（收到 " + rawKeys.size() + " 条）");
    }
    List<NotificationKey> keys = new ArrayList<>(rawKeys.size());
    LinkedHashSet<String> seen = new LinkedHashSet<>();
    for (String raw : rawKeys) {
      NotificationKey key = parseKey(raw);
      if (seen.add(key.key())) keys.add(key);
    }
    return keys;
  }

  /**
   * 解析一个通知键：{@code <类型>:<上游主键>}。
   *
   * @throws ResponseStatusException 形状不合法或类型不在词表里（400）
   */
  public static NotificationKey parseKey(String rawKey) {
    String key = rawKey == null ? "" : rawKey.trim();
    int colon = key.indexOf(':');
    if (colon <= 0 || colon == key.length() - 1) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "通知键形状不合法（应为 <类型>:<上游主键>）：" + describe(key));
    }
    String code = key.substring(0, colon);
    String ref = key.substring(colon + 1);
    if (!TYPE_CODE.matcher(code).matches()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "通知键的类型段不合法：" + describe(code));
    }
    if (!isKnownType(code)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "通知类型不在受控词表里：" + describe(code));
    }
    if (!REF.matcher(ref).matches()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "通知键的上游主键段不合法：" + describe(ref));
    }
    return new NotificationKey(code, ref);
  }

  private static String describe(String value) {
    if (value == null) return "(空)";
    return value.length() > 80 ? value.substring(0, 80) + "…" : value;
  }
}
