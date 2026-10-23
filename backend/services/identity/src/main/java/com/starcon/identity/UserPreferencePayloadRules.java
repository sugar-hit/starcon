package com.starcon.identity;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * 1.4.1.2 列表与表格偏好 —— 载荷的**纯函数**校验规则。
 *
 * <p>刻意做成不碰数据库的纯函数：这些规则要能被单元测试逐条钉住
 * （哪些键被拒、哪些值被拒、多深多大），而不是只能靠集成测试间接观察。
 *
 * <p><b>它守的是「结构安全」，不是「页面语义」</b>：
 *
 * <ul>
 *   <li><b>结构</b>（本类负责，服务端强制）：载荷必须是 JSON 对象；键名走安全字符集；
 *       深度、键数、数组长度、字符串长度、总体积都有上限；<b>敏感键名直接拒绝</b>。
 *   <li><b>页面语义</b>（前端负责，服务端不猜）：「这个 page_key 的合法列有哪些」
 *       「pageSize 的取值范围」「哪些筛选条件可以持久化」——
 *       只有渲染这个页面的代码知道这些，服务端猜不出来就只会写出假校验。
 * </ul>
 *
 * <p><b>为什么服务端仍然要拦敏感键</b>：蓝图的硬约束是「筛选条件里的敏感值不得进
 * preference JSON」。前端会守（自由文本筛选根本不持久化），但**前端是可以被绕过的** ——
 * 任何人拿自己的 token 就能直接打这个接口。所以这条规则必须在服务端再钉一遍，
 * 否则它只是「我们约定不改」，不是「改不了」。
 */
public final class UserPreferencePayloadRules {

  private UserPreferencePayloadRules() {}

  /** 页面 key 词表：小写字母 / 数字 / 点 / 下划线 / 连字符，1..160。 */
  public static final Pattern PAGE_KEY = Pattern.compile("^[a-z0-9][a-z0-9._-]{0,159}$");

  /** 载荷里允许出现的键名：字母 / 数字 / 点 / 下划线 / 连字符，1..64。 */
  public static final Pattern KEY = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

  /** 整个载荷的序列化上限。16 KB 足够放下几百列 + 几十个筛选条件。 */
  public static final int MAX_PAYLOAD_BYTES = 16 * 1024;

  /** 顶层键数上限。 */
  public static final int MAX_KEYS = 64;

  /** 嵌套深度上限。偏好载荷本该是「键 → 标量 / 一层数组」，两层以上就该是别的形状了。 */
  public static final int MAX_DEPTH = 4;

  /** 单个字符串值的长度上限。 */
  public static final int MAX_STRING_LENGTH = 512;

  /** 单个数组的元素个数上限。 */
  public static final int MAX_ARRAY_ITEMS = 64;

  /**
   * 敏感键名词干（比较时统一小写并去掉 {@code - _ .} 分隔符）。
   *
   * <p>用**词干包含**而不是全词相等：{@code accessToken} / {@code access_token} /
   * {@code ACCESS-TOKEN} 都要拦住，所以先归一再判断。
   *
   * <p>宁可**误伤**也不放过：拦下一个真名（比如 {@code password}）的代价，
   * 是那个筛选条件不再被持久化；放过一个密码的代价，是明文口令进了个人偏好表。
   */
  private static final List<String> SENSITIVE_KEY_STEMS =
      List.of(
          "password",
          "passwd",
          "pwd",
          "token",
          "secret",
          "credential",
          "cookie",
          "privatekey",
          "apikey",
          "idcard",
          "idnumber",
          "idno",
          "identityno",
          "passport",
          "ssn",
          "bankaccount",
          "bankcard",
          "cardno",
          "cardnumber",
          "cvv",
          "authorization");

  /**
   * 看起来像凭据的**值**。
   *
   * <p>键名拦住了「字段叫 password」，但拦不住「字段叫 note、值里贴了一段 token」。
   * 这几条正则只认**形状**（PEM 头、JWT 头、{@code Bearer } / {@code Basic } 前缀、
   * {@code key=value} 形式的敏感赋值），不会误伤正常业务值。
   */
  private static final List<Pattern> CREDENTIAL_VALUES =
      List.of(
          Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----"),
          Pattern.compile("(?i)^bearer\\s+\\S+"),
          Pattern.compile("(?i)^basic\\s+\\S+"),
          Pattern.compile("(?i)^eyJ[A-Za-z0-9_-]{16,}\\.[A-Za-z0-9_-]{8,}\\S*$"),
          Pattern.compile("(?i)(password|passwd|pwd|token|secret|apikey)\\s*="));

  private static final ObjectMapper JSON = new ObjectMapper();

  /** 页面 key 归一化：去首尾空白 + 词表校验。不合法一律 400（不是静默改名）。 */
  public static String normalizePageKey(String raw) {
    String pageKey = raw == null ? "" : raw.trim();
    if (!PAGE_KEY.matcher(pageKey).matches()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "页面 key 不合法：只能用小写字母、数字、点、下划线与连字符，长度 1..160");
    }
    return pageKey;
  }

  /**
   * 校验并归一化一份偏好载荷。
   *
   * @return 归一化后的载荷（保持键的插入顺序，方便前端 diff 与测试断言）
   * @throws ResponseStatusException 载荷不合法时（400，带具体原因）
   */
  public static Map<String, Object> validate(Map<String, Object> raw) {
    if (raw == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "偏好载荷不能为空");
    }
    if (raw.size() > MAX_KEYS) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "偏好载荷的键太多：最多 " + MAX_KEYS + " 个");
    }
    String serialized = write(raw);
    if (serialized.getBytes(StandardCharsets.UTF_8).length > MAX_PAYLOAD_BYTES) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "偏好载荷太大：最多 " + MAX_PAYLOAD_BYTES + " 字节");
    }
    Map<String, Object> out = new LinkedHashMap<>();
    for (Map.Entry<String, Object> entry : raw.entrySet()) {
      String key = entry.getKey();
      requireSafeKey(key);
      out.put(key, validateValue(key, entry.getValue(), 1));
    }
    return out;
  }

  /** 把数据库里的 jsonb 文本读回载荷。读不出来时退回空对象（**不报错**：老行不该让页面崩）。 */
  public static Map<String, Object> read(String json) {
    if (json == null || json.isBlank()) return Map.of();
    try {
      Map<String, Object> parsed = JSON.readValue(json, new TypeReference<>() {});
      return parsed == null ? Map.of() : parsed;
    } catch (Exception e) {
      return Map.of();
    }
  }

  /** 载荷序列化成 jsonb 文本。 */
  public static String write(Map<String, Object> payload) {
    try {
      return JSON.writeValueAsString(payload);
    } catch (Exception e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "偏好载荷无法序列化");
    }
  }

  private static void requireSafeKey(String key) {
    if (key == null || !KEY.matcher(key).matches()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "偏好键名不合法：" + describe(key));
    }
    if (isSensitive(key)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "偏好里不允许出现敏感字段：" + key);
    }
  }

  private static Object validateValue(String key, Object value, int depth) {
    if (depth > MAX_DEPTH) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "偏好嵌套太深：最多 " + MAX_DEPTH + " 层");
    }
    if (value == null || value instanceof Boolean || value instanceof Number) return value;
    if (value instanceof String text) {
      if (text.length() > MAX_STRING_LENGTH) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "偏好值太长（上限 " + MAX_STRING_LENGTH + " 字符）：" + key);
      }
      if (looksLikeCredential(text)) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "偏好值看起来像凭据，已拒绝写入：" + key);
      }
      return text;
    }
    if (value instanceof List<?> items) {
      if (items.size() > MAX_ARRAY_ITEMS) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "偏好数组太长（上限 " + MAX_ARRAY_ITEMS + " 项）：" + key);
      }
      List<Object> out = new ArrayList<>(items.size());
      for (Object item : items) out.add(validateValue(key, item, depth + 1));
      return out;
    }
    if (value instanceof Map<?, ?> nested) {
      if (nested.size() > MAX_KEYS) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "偏好嵌套对象键太多（上限 " + MAX_KEYS + "）：" + key);
      }
      Map<String, Object> out = new LinkedHashMap<>();
      for (Map.Entry<?, ?> entry : nested.entrySet()) {
        String nestedKey = String.valueOf(entry.getKey());
        requireSafeKey(nestedKey);
        out.put(nestedKey, validateValue(nestedKey, entry.getValue(), depth + 1));
      }
      return out;
    }
    throw new ResponseStatusException(
        HttpStatus.BAD_REQUEST, "偏好值类型不受支持：" + key);
  }

  /** 键名归一化后是否命中敏感词干。 */
  public static boolean isSensitive(String key) {
    if (key == null) return false;
    String normalized = key.toLowerCase(Locale.ROOT).replaceAll("[-_.\\s]", "");
    for (String stem : SENSITIVE_KEY_STEMS) {
      if (normalized.contains(stem)) return true;
    }
    return false;
  }

  /** 值是否长得像凭据。 */
  public static boolean looksLikeCredential(String value) {
    if (value == null || value.isEmpty()) return false;
    for (Pattern pattern : CREDENTIAL_VALUES) {
      if (pattern.matcher(value).find()) return true;
    }
    return false;
  }

  private static String describe(String key) {
    if (key == null) return "(空)";
    return key.length() > 64 ? key.substring(0, 64) + "…" : key;
  }
}
