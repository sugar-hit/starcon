package com.starcon.platform;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * 18.1.1.2 的 schema 读取器单测（**不起容器**，秒级）。
 *
 * <p>🔴 这里守着两件最容易悄悄做错的事：
 *
 * <ol>
 *   <li><b>{@code $ref} 必须解得开</b>：{@code #/components/schemas/X} 是 JSON Reference，
 *       而 Jackson 的 {@code at()} 只吃 JSON Pointer（以 {@code /} 开头）。
 *       直接把引用丢给 {@code at()} 会抛 IllegalArgumentException ——
 *       表现是「接口详情整个打不开」，而不是「某个字段没显示」。
 *   <li><b>示例的出处必须分得清</b>：spec 自带（SPEC）与按 schema 生成
 *       （GENERATED_FROM_SCHEMA）是两回事，混起来就是把手写的东西冒充契约。
 * </ol>
 */
class ApiCatalogSchemaReaderTest {

  static final ObjectMapper json = new ObjectMapper();

  static final String SPEC =
      """
      {
        "openapi": "3.0.1",
        "info": {"title": "catalog 服务", "version": "0.1.0"},
        "paths": {
          "/catalog/spus": {
            "get": {
              "parameters": [
                {"name": "keyword", "in": "query", "required": false,
                 "description": "关键词", "schema": {"type": "string"}}
              ],
              "responses": {
                "200": {"description": "OK", "content": {"application/json":
                  {"schema": {"$ref": "#/components/schemas/SpuPage"}}}}
              }
            }
          }
        },
        "components": {"schemas": {
          "SpuPage": {"type": "object", "properties": {
            "items": {"type": "array", "items": {"$ref": "#/components/schemas/Spu"}},
            "total": {"type": "integer"}}, "required": ["items", "total"]},
          "Spu": {"type": "object", "properties": {
            "id": {"type": "string", "format": "uuid"},
            "code": {"type": "string"}}, "required": ["id", "code"]}
        }}
      }
      """;

  @Test
  void 引用能解开并读出字段() throws Exception {
    var spec = json.readTree(SPEC);
    var schema = spec.at("/paths/~1catalog~1spus/get/responses/200/content/application~1json/schema");
    assertThat(schema.path("$ref").asText()).isEqualTo("#/components/schemas/SpuPage");

    var fields = ApiSchemaReader.fields(spec, schema);
    assertThat(fields).extracting(ApiCatalogModels.ApiCatalogField::name)
        .containsExactly("items", "total");
    assertThat(fields.get(0).required()).isTrue();
    assertThat(fields.get(1).required()).isTrue();
    // 嵌套的 $ref 也要能解开（items 是数组，元素是 $ref）。
    assertThat(fields.get(0).type()).isEqualTo("array");
  }

  @Test
  void 类型名对组件引用显示组件名() throws Exception {
    var spec = json.readTree(SPEC);
    var schema = spec.at("/components/schemas/SpuPage");
    assertThat(ApiSchemaReader.typeName(spec, schema)).isEqualTo("object");
    var ref = spec.at("/paths/~1catalog~1spus/get/responses/200/content/application~1json/schema");
    assertThat(ApiSchemaReader.typeName(spec, ref)).isEqualTo("SpuPage");
  }

  @Test
  void 示例的出处分得清() throws Exception {
    var spec = json.readTree(SPEC);
    var schema = spec.at("/paths/~1catalog~1spus/get/responses/200/content/application~1json/schema");
    var example = ApiSchemaReader.example(spec, schema);
    assertThat(example.source()).isEqualTo("GENERATED_FROM_SCHEMA");
    assertThat(example.json()).contains("items").contains("total");

    // spec 自带示例时，source 必须是 SPEC（不能被当成生成的）。
    var withExample =
        json.readTree(
            """
            {"type":"object","properties":{"a":{"type":"string","example":"AAA"}},
             "example":{"a":"AAA"}}
            """);
    assertThat(ApiSchemaReader.example(withExample, withExample).source()).isEqualTo("SPEC");
  }

  @Test
  void 标量示例按格式给() throws Exception {
    var spec = json.readTree(SPEC);
    var uuid = spec.at("/components/schemas/Spu/properties/id");
    assertThat(ApiSchemaReader.scalarExample(spec, uuid)).isEqualTo("00000000-0000-0000-0000-000000000000");
    var plain = spec.at("/components/schemas/Spu/properties/code");
    assertThat(ApiSchemaReader.scalarExample(spec, plain)).isEqualTo("string");
  }

  @Test
  void 解不开的引用原样返回而不是抛异常() throws Exception {
    var spec = json.readTree(SPEC);
    var broken = json.readTree("{\"$ref\":\"#/components/schemas/Nope\"}");
    assertThat(ApiSchemaReader.fields(spec, broken)).isEmpty();
  }
}
