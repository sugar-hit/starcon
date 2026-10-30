package com.starcon.platform;

import static com.starcon.platform.ApiCatalogModels.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;

/**
 * 把 OpenAPI 的 schema 节点读成「字段清单」与「示例」。
 *
 * <p>🔴 <b>本类一个字都不许编</b>：字段名、类型、必填、说明、示例**全部**来自 spec 节点。
 * 唯一「生成」的地方是**示例值** —— 当 spec 自己没给示例时，按 schema 的类型/格式/enum
 * 生成一个，并**明确标出来**（{@code source = GENERATED_FROM_SCHEMA}）。
 *
 * <p>为什么允许生成示例、却不允许手写文档：
 * 手写文档会与实现**漂移**（改了代码没人改文档）；按 schema 生成的示例**每次都跟着 schema 走**，
 * 而且页面明确标着「按 schema 生成，不是官方示例」——它不会被误当成契约的一部分。
 *
 * <p>⚠️ {@code $ref} 一律**当场解引用**（{@code #/components/schemas/Name}），
 * 解不开就原样返回（不猜、不报错）：一个解不开的引用不该把整个目录页拖垮。
 */
final class ApiSchemaReader {

  /** 示例生成的深度上限（再深就只留必填字段）。 */
  private static final int MAX_DEPTH = 3;

  /** 一个对象最多展开多少个字段。 */
  private static final int MAX_FIELDS = 40;

  private ApiSchemaReader() {}

  // ================================================================ 类型名

  /** 类型的显示口径（{@code string(date-time)} / {@code array} / 组件名）。 */
  static String typeName(JsonNode spec, JsonNode schema) {
    if (schema == null || schema.isMissingNode() || schema.isNull()) return "";
    // 🔴 先看**原始**节点有没有 $ref：解引用之后组件名就丢了（会退化成 "object"），
    //   而组件名比 "object" 有用得多 —— 接入方要照着它去查文档。
    if (schema.has("$ref")) {
      var ref = schema.path("$ref").asText("");
      var slash = ref.lastIndexOf('/');
      return slash >= 0 ? ref.substring(slash + 1) : ref;
    }
    var resolved = resolve(spec, schema);
    var type = resolved.path("type").asText("");
    if (type.isEmpty()) {
      if (resolved.has("properties")) return "object";
      if (resolved.has("items")) return "array";
      return "";
    }
    var format = resolved.path("format").asText("");
    if (!format.isEmpty()) return type + "(" + format + ")";
    return type;
  }

  // ================================================================ 字段清单

  /** 读一个对象 schema 的字段清单（名称 / 类型 / 必填 / 说明 / 示例）。 */
  static List<ApiCatalogField> fields(JsonNode spec, JsonNode schema) {
    var result = new ArrayList<ApiCatalogField>();
    var resolved = resolve(spec, schema);
    var properties = resolved.path("properties");
    if (!properties.isObject()) return result;
    var required = resolved.path("required");
    var count = 0;
    var iterator = properties.fields();
    while (iterator.hasNext() && count < MAX_FIELDS) {
      var entry = iterator.next();
      var name = entry.getKey();
      var propertySchema = entry.getValue();
      var isRequired = false;
      if (required.isArray()) {
        for (var r : required) {
          if (r.asText().equals(name)) {
            isRequired = true;
            break;
          }
        }
      }
      result.add(
          new ApiCatalogField(
              name,
              typeName(spec, propertySchema),
              isRequired,
              propertySchema.path("description").asText(""),
              scalarExample(spec, propertySchema)));
      count++;
    }
    return result;
  }

  // ================================================================ 示例

  /**
   * 生成一个示例。
   *
   * @return {@code source=SPEC} 表示 spec 自带；{@code GENERATED_FROM_SCHEMA} 表示按 schema 生成；
   *     {@code NONE} 表示两者都没有
   */
  static ApiCatalogExample example(JsonNode spec, JsonNode schema) {
    var resolved = resolve(spec, schema);
    if (resolved.isMissingNode() || resolved.isNull()) return new ApiCatalogExample("NONE", null);
    if (resolved.has("example")) {
      return new ApiCatalogExample("SPEC", resolved.path("example").toString());
    }
    var examples = resolved.path("examples");
    if (examples.isArray() && examples.size() > 0) {
      return new ApiCatalogExample("SPEC", examples.get(0).toString());
    }
    if (examples.isObject()) {
      var iterator = examples.fields();
      if (iterator.hasNext()) {
        return new ApiCatalogExample("SPEC", iterator.next().getValue().toString());
      }
    }
    if (resolved.has("default")) {
      return new ApiCatalogExample("SPEC", resolved.path("default").toString());
    }
    if (resolved.has("enum")) {
      var enums = resolved.path("enum");
      if (enums.isArray() && enums.size() > 0) {
        return new ApiCatalogExample("GENERATED_FROM_SCHEMA", enums.get(0).toString());
      }
    }
    var generated = build(spec, resolved, 0);
    if (generated == null || generated.isMissingNode()) return new ApiCatalogExample("NONE", null);
    return new ApiCatalogExample("GENERATED_FROM_SCHEMA", generated.toString());
  }

  /** 标量字段的示例值（对象 / 数组不展开 —— 字段清单里只给一个能看懂的标量）。 */
  static String scalarExample(JsonNode spec, JsonNode schema) {
    var resolved = resolve(spec, schema);
    if (resolved.has("example")) return resolved.path("example").asText();
    var type = resolved.path("type").asText("");
    if ("string".equals(type)) {
      var format = resolved.path("format").asText("");
      return switch (format) {
        case "uuid" -> "00000000-0000-0000-0000-000000000000";
        case "date-time" -> "2026-01-01T00:00:00Z";
        case "date" -> "2026-01-01";
        case "email" -> "user@example.com";
        case "uri", "url" -> "https://example.com";
        default -> "string";
      };
    }
    if ("integer".equals(type) || "number".equals(type)) return "0";
    if ("boolean".equals(type)) return "true";
    return "";
  }

  /** 按 schema 生成一个示例值（对象 / 数组按深度展开）。 */
  private static JsonNode build(JsonNode spec, JsonNode schema, int depth) {
    if (schema == null || schema.isMissingNode() || schema.isNull()) return null;
    var resolved = resolve(spec, schema);
    if (resolved.has("example")) return resolved.path("example");
    if (resolved.has("default")) return resolved.path("default");
    if (resolved.has("enum")) {
      var enums = resolved.path("enum");
      if (enums.isArray() && enums.size() > 0) return enums.get(0);
    }
    // allOf：合并所有子 schema 的属性（spec 里最常见的组合写法）。
    if (resolved.has("allOf")) {
      var merged = JsonNodeFactory.instance.objectNode();
      for (var part : resolved.path("allOf")) {
        var piece = build(spec, part, depth);
        if (piece != null && piece.isObject()) {
          merged.setAll((ObjectNode) piece);
        }
      }
      if (!merged.isEmpty()) return merged;
    }
    // oneOf / anyOf：取第一个（多态 schema 没有唯一示例，取第一个并标成「按 schema 生成」）。
    if (resolved.has("oneOf") || resolved.has("anyOf")) {
      var list = resolved.has("oneOf") ? resolved.path("oneOf") : resolved.path("anyOf");
      if (list.isArray() && list.size() > 0) return build(spec, list.get(0), depth);
    }

    var type = resolved.path("type").asText("");
    if (type.isEmpty() && resolved.has("properties")) type = "object";
    if (type.isEmpty() && resolved.has("items")) type = "array";

    switch (type) {
      case "object" -> {
        var properties = resolved.path("properties");
        if (!properties.isObject()) return JsonNodeFactory.instance.objectNode();
        var result = JsonNodeFactory.instance.objectNode();
        var required = resolved.path("required");
        var count = 0;
        var iterator = properties.fields();
        while (iterator.hasNext() && count < MAX_FIELDS) {
          var entry = iterator.next();
          var name = entry.getKey();
          var isRequired = false;
          if (required.isArray()) {
            for (var r : required) {
              if (r.asText().equals(name)) {
                isRequired = true;
                break;
              }
            }
          }
          // 深度超了只留必填字段：否则一个三层嵌套的 DTO 会撑出一个巨大的示例。
          if (depth >= MAX_DEPTH && !isRequired) continue;
          var value = build(spec, entry.getValue(), depth + 1);
          if (value != null) result.set(name, value);
          count++;
        }
        return result;
      }
      case "array" -> {
        var result = JsonNodeFactory.instance.arrayNode();
        var items = resolved.path("items");
        if (!items.isMissingNode()) {
          var value = build(spec, items, depth + 1);
          if (value != null) ((ArrayNode) result).add(value);
        }
        return result;
      }
      case "string" -> {
        var format = resolved.path("format").asText("");
        return JsonNodeFactory.instance.textNode(
            switch (format) {
              case "uuid" -> "00000000-0000-0000-0000-000000000000";
              case "date-time" -> "2026-01-01T00:00:00Z";
              case "date" -> "2026-01-01";
              case "email" -> "user@example.com";
              case "uri", "url" -> "https://example.com";
              default -> "string";
            });
      }
      case "integer" -> {
        return JsonNodeFactory.instance.numberNode(0);
      }
      case "number" -> {
        return JsonNodeFactory.instance.numberNode(0);
      }
      case "boolean" -> {
        return JsonNodeFactory.instance.booleanNode(true);
      }
      case "null" -> {
        return JsonNodeFactory.instance.nullNode();
      }
      default -> {
        return JsonNodeFactory.instance.objectNode();
      }
    }
  }

  // ================================================================ $ref

  /**
   * 解开 {@code #/components/schemas/Name}（解不开就原样返回）。
   *
   * <p>⚠️ {@code #/components/schemas/X} 是 **JSON Reference**，而 Jackson 的
   * {@code at()} 只吃 **JSON Pointer**（必须以 {@code /} 开头）——
   * 直接把引用丢给 {@code at()} 会抛 {@code IllegalArgumentException:
   * JSON Pointer expression must start with '/'}。所以这里先把 {@code #} 去掉。
   */
  static JsonNode resolve(JsonNode spec, JsonNode schema) {
    if (schema == null || !schema.has("$ref")) return schema;
    var ref = schema.path("$ref").asText("");
    if (!ref.startsWith("#/")) return schema;
    var resolved = spec.at(ref.substring(1));
    return resolved.isMissingNode() ? schema : resolved;
  }
}
