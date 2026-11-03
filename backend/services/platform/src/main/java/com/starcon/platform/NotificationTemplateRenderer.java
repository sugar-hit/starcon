package com.starcon.platform;

import static com.starcon.platform.NotificationTemplateModels.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 18.5.1.1 的**渲染引擎**：{@code {{变量}}} 占位符的解析、校验与渲染。
 *
 * <p>🔴 <b>这一整条链路不依赖任何外部系统</b> —— 它是纯规则，真实实现、真实生效、真实拦得住。
 *
 * <p><b>四条纪律（本引擎的全部意义就在这四条）：</b>
 *
 * <ol>
 *   <li><b>缺变量 ⇒ 报错，并点名缺哪个</b>。不渲染成空串，不留下 {@code {{xxx}}} 残渣 ——
 *       一条通知里留着 {@code {{买家昵称}}} 发出去，比发不出去更糟。
 *   <li><b>未知变量 ⇒ 报错，并点名是哪个</b>。正文里写了没声明的变量，
 *       说明模板与变量定义对不上 —— 这时**渲染不出来**才是对的。
 *   <li><b>多余变量 ⇒ 报错</b>。给了模板用不上的取值，说明调用方与模板版本对不上 ——
 *       与其默默忽略，不如当场说清。
 *   <li><b>语法错误 ⇒ 报错</b>。占位符没闭合、变量名里有非法字符，都不是「能渲染的模板」。
 * </ol>
 *
 * <p>另外还有一条**类型校验**（{@code INVALID_VALUE}）：{@code NUMBER} 的取值必须能解析成数字、
 * {@code DATE} 必须能解析成时间、{@code CURRENCY} 必须能解析成金额（可带货币代码）。
 * 一条 {@code {{amount}}} 里塞了「十二块」，到下游算账时才炸，比在这里炸贵得多。
 *
 * <p><b>渲染失败时 {@code renderedSubject} / {@code renderedBody} 一定是 {@code null}</b> ——
 * 调用方拿到的一定是「要么完整的一版正文，要么一份点名问题的错误清单」，没有第三种。
 */
final class NotificationTemplateRenderer {

  private NotificationTemplateRenderer() {}

  // ---------------------------------------------------------------- 口径常量

  /** 变量名形状：字母 / 数字 / 下划线。 */
  private static final Pattern VARIABLE_NAME = Pattern.compile("^[A-Za-z0-9_]{1,64}$");

  /** 错误码（写进响应体，前端按码显示不同的话）。 */
  static final String CODE_SYNTAX = "SYNTAX_ERROR";

  static final String CODE_MISSING = "MISSING_VARIABLE";
  static final String CODE_UNKNOWN = "UNKNOWN_VARIABLE";
  static final String CODE_EXTRA = "EXTRA_VARIABLE";
  static final String CODE_INVALID_VALUE = "INVALID_VALUE";
  static final String CODE_INVALID_DECLARATION = "INVALID_DECLARATION";

  // ---------------------------------------------------------------- 解析结果

  /** 解析结果：占位符名字（去重、保序）+ 语法错误。 */
  record Parsed(List<String> names, List<NotifyTemplateRenderError> errors) {}

  // ---------------------------------------------------------------- 解析

  /**
   * 解析一段文本里的占位符。
   *
   * @param content 正文或标题
   */
  static Parsed parse(String content) {
    var names = new ArrayList<String>();
    var errors = new ArrayList<NotifyTemplateRenderError>();
    if (content == null || content.isEmpty()) return new Parsed(names, errors);

    var i = 0;
    while (i < content.length()) {
      var open = content.indexOf("{{", i);
      if (open < 0) {
        // 没有 "{{" 了：后面若还有落单的 "}}" 就是语法错误。
        if (content.indexOf("}}", i) >= 0) {
          errors.add(
              new NotifyTemplateRenderError(
                  CODE_SYNTAX, "文本里有落单的 }}（没有配对的 {{）", List.of()));
        }
        break;
      }
      var close = content.indexOf("}}", open + 2);
      if (close < 0) {
        errors.add(
            new NotifyTemplateRenderError(
                CODE_SYNTAX, "占位符没有闭合：出现了 {{ 却没有配对的 }}", List.of()));
        break;
      }
      var raw = content.substring(open + 2, close);
      var name = raw.trim();
      if (!VARIABLE_NAME.matcher(name).matches()) {
        errors.add(
            new NotifyTemplateRenderError(
                CODE_SYNTAX,
                "变量名非法：{{" + raw + "}}（只能是字母 / 数字 / 下划线，且不能为空）",
                List.of(name.isEmpty() ? raw : name)));
      } else if (!names.contains(name)) {
        names.add(name);
      }
      i = close + 2;
    }
    return new Parsed(names, errors);
  }

  // ---------------------------------------------------------------- 变量定义校验

  /**
   * 校验变量定义本身。
   *
   * <p>登记时就把**定义写错**的模板挡在门外，比渲染时才发现省事得多。
   */
  static List<NotifyTemplateRenderError> validateDeclarations(List<NotifyTemplateVariableDefinition> definitions) {
    var errors = new ArrayList<NotifyTemplateRenderError>();
    if (definitions == null) return errors;
    var seen = new LinkedHashSet<String>();
    for (var definition : definitions) {
      var name = definition == null ? null : definition.name();
      if (name == null || name.isBlank()) {
        errors.add(new NotifyTemplateRenderError(CODE_INVALID_DECLARATION, "变量定义缺名字", List.of()));
        continue;
      }
      var trimmed = name.trim();
      if (!VARIABLE_NAME.matcher(trimmed).matches()) {
        errors.add(
            new NotifyTemplateRenderError(
                CODE_INVALID_DECLARATION,
                "变量名非法：" + trimmed + "（只能是字母 / 数字 / 下划线，且不能为空）",
                List.of(trimmed)));
        continue;
      }
      if (!seen.add(trimmed)) {
        errors.add(
            new NotifyTemplateRenderError(
                CODE_INVALID_DECLARATION, "变量重复声明：" + trimmed, List.of(trimmed)));
        continue;
      }
      if (NotifyTemplateVariableType.parse(definition.type()) == null) {
        errors.add(
            new NotifyTemplateRenderError(
                CODE_INVALID_DECLARATION,
                "变量 " + trimmed + " 的类型只能是 TEXT / NUMBER / DATE / CURRENCY",
                List.of(trimmed)));
      }
      var defaultValue = definition.defaultValue();
      if (definition.required() && defaultValue != null && !defaultValue.isBlank()) {
        errors.add(
            new NotifyTemplateRenderError(
                CODE_INVALID_DECLARATION,
                "变量 " + trimmed + " 是必填的，不能同时有默认值（要么必填，要么有默认值）",
                List.of(trimmed)));
      }
    }
    return errors;
  }

  // ---------------------------------------------------------------- 渲染

  /**
   * 渲染一版模板。
   *
   * @param subject 标题（可空）
   * @param body 正文
   * @param definitions 变量定义（**封闭词表**：正文里出现的变量必须在里面）
   * @param values 取值（键是变量名）
   * @param templateId 关联的模板 id（可空：草稿预览）
   * @param versionId 关联的版本 id（可空）
   * @param templateKey 模板 key（可空）
   * @param language 语言（可空）
   * @param versionNo 版本号（可空）
   */
  static NotifyTemplateRenderResult render(
      String subject,
      String body,
      List<NotifyTemplateVariableDefinition> definitions,
      Map<String, String> values,
      UUID templateId,
      UUID versionId,
      String templateKey,
      String language,
      Integer versionNo) {
    var errors = new ArrayList<NotifyTemplateRenderError>();
    errors.addAll(validateDeclarations(definitions));

    var declared = new LinkedHashMap<String, NotifyTemplateVariableDefinition>();
    if (definitions != null) {
      for (var definition : definitions) {
        if (definition == null || definition.name() == null) continue;
        declared.put(definition.name().trim(), definition);
      }
    }

    var subjectParsed = parse(subject);
    var bodyParsed = parse(body);
    errors.addAll(subjectParsed.errors());
    errors.addAll(bodyParsed.errors());

    // ① 正文 / 标题里出现、但没声明的变量 ⇒ 未知变量（点名）。
    var unknown = new LinkedHashSet<String>();
    for (var name : subjectParsed.names()) {
      if (!declared.containsKey(name)) unknown.add(name);
    }
    for (var name : bodyParsed.names()) {
      if (!declared.containsKey(name)) unknown.add(name);
    }
    if (!unknown.isEmpty()) {
      errors.add(
          new NotifyTemplateRenderError(
              CODE_UNKNOWN,
              "正文里有没声明的变量："
                  + String.join(" / ", unknown)
                  + "。变量定义里没有它们，服务端不知道该填什么。",
              List.copyOf(unknown)));
    }

    // ② 取值：缺的报「缺变量」，多的报「多余变量」，类型不对报「类型不对」。
    var bound = new LinkedHashMap<String, String>();
    var missing = new ArrayList<String>();
    var invalid = new ArrayList<String>();
    for (var entry : declared.entrySet()) {
      var name = entry.getKey();
      var definition = entry.getValue();
      var raw = values == null ? null : values.get(name);
      var value = raw == null || raw.isBlank() ? null : raw;
      if (value == null) {
        var fallback = definition.defaultValue();
        if (fallback != null && !fallback.isBlank()) {
          bound.put(name, fallback);
          continue;
        }
        missing.add(name);
        continue;
      }
      var problem = valueProblem(definition, value);
      if (problem != null) {
        invalid.add(name + "（" + problem + "）");
        continue;
      }
      bound.put(name, value);
    }
    if (!missing.isEmpty()) {
      errors.add(
          new NotifyTemplateRenderError(
              CODE_MISSING,
              "缺少变量取值：" + String.join(" / ", missing) + "。不给值就会在正文里留下占位符。",
              List.copyOf(missing)));
    }
    if (!invalid.isEmpty()) {
      errors.add(
          new NotifyTemplateRenderError(
              CODE_INVALID_VALUE,
              "变量取值不符合声明的类型：" + String.join(" / ", invalid),
              List.copyOf(invalid)));
    }

    // ③ 给了模板用不上的取值 ⇒ 多余变量（点名）。这不是「忽略一下」的事：
    //    说明调用方与模板版本对不上。
    var extra = new ArrayList<String>();
    if (values != null) {
      for (var key : values.keySet()) {
        if (key == null || key.isBlank()) continue;
        if (!declared.containsKey(key.trim())) extra.add(key.trim());
      }
    }
    if (!extra.isEmpty()) {
      errors.add(
          new NotifyTemplateRenderError(
              CODE_EXTRA,
              "给了模板没声明的取值：" + String.join(" / ", extra) + "。请先在变量定义里声明它们。",
              List.copyOf(extra)));
    }

    if (!errors.isEmpty()) {
      return new NotifyTemplateRenderResult(
          false,
          summarize(errors),
          null,
          null,
          definitions == null ? List.of() : definitions,
          List.copyOf(errors),
          templateId,
          versionId,
          templateKey,
          language,
          versionNo);
    }

    return new NotifyTemplateRenderResult(
        true,
        "渲染成功。",
        substitute(subject, bound),
        substitute(body, bound),
        definitions == null ? List.of() : definitions,
        List.of(),
        templateId,
        versionId,
        templateKey,
        language,
        versionNo);
  }

  /**
   * 校验「正文里用的变量」与「变量定义」是否对得上（登记模板时用）。
   *
   * @return 错误清单（空 = 对得上）
   */
  static List<NotifyTemplateRenderError> validateUsage(
      String subject, String body, List<NotifyTemplateVariableDefinition> definitions) {
    var errors = new ArrayList<NotifyTemplateRenderError>();
    errors.addAll(validateDeclarations(definitions));
    var declared = new LinkedHashSet<String>();
    if (definitions != null) {
      for (var definition : definitions) {
        if (definition != null && definition.name() != null) declared.add(definition.name().trim());
      }
    }
    var unknown = new LinkedHashSet<String>();
    for (var content : List.of(subject == null ? "" : subject, body == null ? "" : body)) {
      var parsed = parse(content);
      errors.addAll(parsed.errors());
      for (var name : parsed.names()) {
        if (!declared.contains(name)) unknown.add(name);
      }
    }
    if (!unknown.isEmpty()) {
      errors.add(
          new NotifyTemplateRenderError(
              CODE_UNKNOWN,
              "正文里有没声明的变量："
                  + String.join(" / ", unknown)
                  + "。请在变量定义里声明它们（否则它们永远填不上）。",
              List.copyOf(unknown)));
    }
    return errors;
  }

  // ---------------------------------------------------------------- 内部

  /** 把 {@code {{变量}}} 换成取值。取值表里没有的键原样留着（调用方已保证不会发生）。 */
  private static String substitute(String content, Map<String, String> bound) {
    if (content == null || content.isEmpty()) return content == null ? "" : content;
    var out = new StringBuilder();
    var i = 0;
    while (i < content.length()) {
      var open = content.indexOf("{{", i);
      if (open < 0) {
        out.append(content, i, content.length());
        break;
      }
      var close = content.indexOf("}}", open + 2);
      if (close < 0) {
        out.append(content, i, content.length());
        break;
      }
      out.append(content, i, open);
      var name = content.substring(open + 2, close).trim();
      var value = bound.get(name);
      out.append(value == null ? content.substring(open, close + 2) : value);
      i = close + 2;
    }
    return out.toString();
  }

  /** 取值与声明类型不符时给一句人话；没问题返回 {@code null}。 */
  private static String valueProblem(NotifyTemplateVariableDefinition definition, String value) {
    var type = NotifyTemplateVariableType.parse(definition.type());
    if (type == null) return null;
    switch (type) {
      case NUMBER:
        try {
          new BigDecimal(value.trim());
          return null;
        } catch (NumberFormatException e) {
          return "NUMBER 必须是数字，实际是「" + value + "」";
        }
      case DATE:
        if (parsesAsDate(value)) return null;
        return "DATE 必须是 ISO-8601 日期或时间，实际是「" + value + "」";
      case CURRENCY:
        if (parsesAsCurrency(value)) return null;
        return "CURRENCY 必须是金额（可带货币代码，如「USD 12.50」），实际是「" + value + "」";
      case TEXT:
      default:
        return null;
    }
  }

  private static boolean parsesAsDate(String value) {
    var trimmed = value.trim();
    try {
      OffsetDateTime.parse(trimmed);
      return true;
    } catch (DateTimeParseException ignored) {
      // 继续试日期。
    }
    try {
      LocalDate.parse(trimmed);
      return true;
    } catch (DateTimeParseException ignored) {
      return false;
    }
  }

  /** 金额：可带 3 位货币代码（前后都行），本体必须能解析成数字。 */
  private static boolean parsesAsCurrency(String value) {
    var trimmed = value.trim();
    var rest = trimmed;
    if (rest.length() > 3 && rest.substring(0, 3).matches("^[A-Za-z]{3}$")) {
      rest = rest.substring(3).trim();
    } else if (rest.length() > 3 && rest.substring(rest.length() - 3).matches("^[A-Za-z]{3}$")) {
      rest = rest.substring(0, rest.length() - 3).trim();
    }
    if (rest.isEmpty()) return false;
    try {
      new BigDecimal(rest);
      return true;
    } catch (NumberFormatException e) {
      return false;
    }
  }

  /** 错误小结：一句话说完哪儿错了。 */
  private static String summarize(List<NotifyTemplateRenderError> errors) {
    if (errors.isEmpty()) return "渲染成功。";
    var parts = new ArrayList<String>();
    for (var error : errors) {
      parts.add(error.message());
    }
    return String.join("；", parts);
  }

  /** 便捷入口：解析一段文本里的占位符名字。 */
  static List<String> placeholderNames(String content) {
    return parse(content).names();
  }

  /** 便捷入口：正文与标题里的占位符名字合起来。 */
  static List<String> placeholderNames(String subject, String body) {
    var names = new ArrayList<String>();
    for (var name : parse(subject == null ? "" : subject).names()) {
      if (!names.contains(name)) names.add(name);
    }
    for (var name : parse(body == null ? "" : body).names()) {
      if (!names.contains(name)) names.add(name);
    }
    return names;
  }
}
