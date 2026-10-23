package com.starcon.identity;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 1.4.2.2 默认店铺与仓库 —— 业务行为。
 *
 * <p>覆盖：候选集真读上游、写入前逐条校验权限范围、读时复核并回落、
 * 读不到与「没有」分得开、显式清空不被回落覆盖、重置幂等。
 */
class DefaultContextIntegrationTest extends DefaultContextTestBase {

  @BeforeEach
  void reset() throws Exception {
    clearStubs();
    // 默认上下文落在固定的一行上（personal preference 是「一页一行」），
    // 上一个用例存过的默认值会留在同一个租户的同一行里 —— 每个用例开始前先清干净，
    // 否则「从没设过」这条断言会被上一个用例的残留打红。
    request(login(), "DELETE", "/identity/default-context", null, 200);
  }

  /** 错误体里的原因：identity 的错误体有两种形状（ProblemDetail 与默认错误体），都取出来。 */
  private String errorMessage(JsonNode body) {
    JsonNode detail = body.get("detail");
    if (detail != null && !detail.isNull()) return detail.asText();
    JsonNode message = body.get("message");
    return message == null || message.isNull() ? "" : message.asText();
  }

  private String legalEntity(String token, String code) throws Exception {
    request(token, "POST", "/identity/legal-entities", legalEntityInput(code, "USD"), 200);
    var list = request(token, "GET", "/identity/legal-entities", null, 200);
    for (JsonNode node : list) {
      if (code.equals(node.get("code").asText())) return node.get("id").asText();
    }
    throw new IllegalStateException("法人未创建：" + code);
  }

  private JsonNode put(String token, String shopId, String legalEntityId, String warehouseId)
      throws Exception {
    var body = new java.util.HashMap<String, Object>();
    body.put("shopId", shopId);
    body.put("legalEntityId", legalEntityId);
    body.put("warehouseId", warehouseId);
    return request(token, "PUT", "/identity/default-context", body, 200);
  }

  @Test
  void 未设过时返回stored为假且候选集来自上游() throws Exception {
    String token = login();
    UUID shopId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    stubShops(token, shopListJson(shopJson(shopId, "S-1", "美国店", "ACTIVE")));
    stubWarehouses(token, warehouseListJson(warehouseJson(warehouseId, "W-1", "深圳仓", "ACTIVE", false)));
    String legalEntityId = legalEntity(token, "LC" + suffix());

    var view = request(token, "GET", "/identity/default-context", null, 200);
    assertThat(view.get("stored").asBoolean()).as("从没设过就是 stored=false").isFalse();
    assertThat(view.get("shop").isNull()).as("没设过就没有默认店铺").isTrue();
    assertThat(view.get("updatedAt").isNull()).as("从没保存过就没有更新时间").isTrue();

    var options = request(token, "GET", "/identity/default-context/options", null, 200);
    assertThat(options.get("shops")).hasSize(1);
    assertThat(options.get("shops").get(0).get("code").asText()).isEqualTo("S-1");
    assertThat(options.get("warehouses")).hasSize(1);
    assertThat(options.get("legalEntities")).hasSize(1);
    assertThat(options.get("legalEntities").get(0).get("id").asText()).isEqualTo(legalEntityId);
    for (JsonNode source : options.get("sources")) {
      assertThat(source.get("available").asBoolean())
          .as("维度 %s 应当读得到", source.get("dimension").asText())
          .isTrue();
    }
  }

  @Test
  void 保存后读回生效的默认上下文() throws Exception {
    String token = login();
    UUID shopId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    stubShops(token, shopListJson(shopJson(shopId, "S-2", "德国店", "ACTIVE")));
    stubWarehouses(token, warehouseListJson(warehouseJson(warehouseId, "W-2", "宁波仓", "ACTIVE", false)));
    String legalEntityId = legalEntity(token, "LC" + suffix());

    var saved = put(token, shopId.toString(), legalEntityId, warehouseId.toString());
    assertThat(saved.get("stored").asBoolean()).isTrue();
    assertThat(saved.get("shop").get("id").asText()).isEqualTo(shopId.toString());
    assertThat(saved.get("shop").get("code").asText()).isEqualTo("S-2");
    assertThat(saved.get("legalEntity").get("id").asText()).isEqualTo(legalEntityId);
    assertThat(saved.get("warehouse").get("id").asText()).isEqualTo(warehouseId.toString());
    assertThat(saved.get("stale")).isEmpty();

    var reread = request(token, "GET", "/identity/default-context", null, 200);
    assertThat(reread.get("shop").get("id").asText()).isEqualTo(shopId.toString());
    assertThat(reread.get("warehouse").get("id").asText()).isEqualTo(warehouseId.toString());
    assertThat(reread.get("updatedAt").asText()).isNotBlank();
  }

  @Test
  void 选了不在可见清单里的店铺被拒() throws Exception {
    String token = login();
    UUID visible = UUID.randomUUID();
    stubShops(token, shopListJson(shopJson(visible, "S-3", "日本店", "ACTIVE")));
    stubWarehouses(token, warehouseListJson());

    var body =
        request(
            token,
            "PUT",
            "/identity/default-context",
            Map.of("shopId", UUID.randomUUID().toString()),
            400);
    assertThat(errorMessage(body)).contains("权限范围");

    // 被拒之后不能留下半条记录
    var view = request(token, "GET", "/identity/default-context", null, 200);
    assertThat(view.get("stored").asBoolean()).as("被拒的写入不得落库").isFalse();
  }

  @Test
  void 上游读不到时拒绝写入而不是先存着() throws Exception {
    String token = login();
    stubShopsFailure(token, 503);

    var body =
        request(
            token,
            "PUT",
            "/identity/default-context",
            Map.of("shopId", UUID.randomUUID().toString()),
            503);
    assertThat(errorMessage(body)).contains("读不到");

    var view = request(token, "GET", "/identity/default-context", null, 200);
    assertThat(view.get("stored").asBoolean()).as("读不到就不能确认在不在权限范围内，不得存").isFalse();
  }

  @Test
  void 店铺不在可见清单里时默认项自动失效() throws Exception {
    String token = login();
    UUID shopId = UUID.randomUUID();
    UUID myWarehouse = UUID.randomUUID();
    stubShops(token, shopListJson(shopJson(shopId, "S-4", "英国店", "ACTIVE")));
    stubWarehouses(
        token, warehouseListJson(warehouseJson(myWarehouse, "W-4", "上海仓", "ACTIVE", false)));
    put(token, shopId.toString(), null, myWarehouse.toString());

    // 权限变更（店铺转手 / 停用）⇒ 店铺不再在可见清单里
    stubShops(token, shopListJson());
    var view = request(token, "GET", "/identity/default-context", null, 200);
    assertThat(view.get("shop").isNull()).as("已不在可见清单里的默认店铺不再生效").isTrue();
    assertThat(view.get("stale")).hasSize(1);
    assertThat(view.get("stale").get(0).get("dimension").asText()).isEqualTo("SHOP");
    assertThat(view.get("stale").get(0).get("reason").asText()).contains("可见清单");
    assertThat(view.get("warehouse").get("id").asText())
        .as("仓库这一维度仍然有效，不该被回落覆盖")
        .isEqualTo(myWarehouse.toString());
  }

  @Test
  void 失效的默认仓库回落到租户自己的默认仓() throws Exception {
    String token = login();
    UUID shopId = UUID.randomUUID();
    UUID myWarehouse = UUID.randomUUID();
    UUID defaultWarehouse = UUID.randomUUID();
    stubShops(token, shopListJson(shopJson(shopId, "S-4B", "英国店", "ACTIVE")));
    stubWarehouses(
        token,
        warehouseListJson(
            warehouseJson(myWarehouse, "W-4", "上海仓", "ACTIVE", false),
            warehouseJson(defaultWarehouse, "W-DEFAULT", "默认发货仓", "ACTIVE", true)));
    put(token, shopId.toString(), null, myWarehouse.toString());

    // 我的仓不再可见（被归档 / 转走）⇒ 失效并回落到租户自己的默认仓
    stubWarehouses(
        token, warehouseListJson(warehouseJson(defaultWarehouse, "W-DEFAULT", "默认发货仓", "ACTIVE", true)));
    var view = request(token, "GET", "/identity/default-context", null, 200);
    assertThat(view.get("warehouse").get("id").asText())
        .as("失效后回落到租户自己的默认仓")
        .isEqualTo(defaultWarehouse.toString());
    assertThat(view.get("stale").get(0).get("dimension").asText()).isEqualTo("WAREHOUSE");
    assertThat(view.get("note").asText()).contains("回落");
  }

  @Test
  void 上游读不到与没有权限分得开() throws Exception {
    String token = login();
    UUID shopId = UUID.randomUUID();
    stubShops(token, shopListJson(shopJson(shopId, "S-5", "法国店", "ACTIVE")));
    stubWarehouses(token, warehouseListJson());
    put(token, shopId.toString(), null, null);

    // 上游挂掉 ⇒ 该维度「读不到」，而不是「你没权限」
    stubShopsFailure(token, 500);
    var view = request(token, "GET", "/identity/default-context", null, 200);
    var shop = source(view, "SHOP");
    assertThat(shop.get("available").asBoolean()).as("上游挂了就是读不到").isFalse();
    assertThat(shop.get("reason").asText()).contains("读不到");
    assertThat(view.get("stale").get(0).get("reason").asText())
        .as("读不到时不能断言「已不在权限范围内」")
        .contains("读不到");
  }

  @Test
  void 停用或归档的店铺不再作为默认值() throws Exception {
    String token = login();
    UUID shopId = UUID.randomUUID();
    stubShops(token, shopListJson(shopJson(shopId, "S-6", "西班牙店", "ACTIVE")));
    stubWarehouses(token, warehouseListJson());
    put(token, shopId.toString(), null, null);

    stubShops(token, shopListJson(shopJson(shopId, "S-6", "西班牙店", "ARCHIVED")));
    var view = request(token, "GET", "/identity/default-context", null, 200);
    assertThat(view.get("shop").isNull()).as("归档的店铺不再生效").isTrue();
    assertThat(view.get("stale").get(0).get("reason").asText()).contains("停用或归档");
  }

  @Test
  void 显式清空的维度不被租户默认仓回落覆盖() throws Exception {
    String token = login();
    UUID shopId = UUID.randomUUID();
    UUID defaultWarehouse = UUID.randomUUID();
    stubShops(token, shopListJson(shopJson(shopId, "S-7", "意大利店", "ACTIVE")));
    stubWarehouses(
        token, warehouseListJson(warehouseJson(defaultWarehouse, "W-DEFAULT", "默认发货仓", "ACTIVE", true)));

    // 三个维度全给 null = 用户明确清空
    put(token, null, null, null);
    var view = request(token, "GET", "/identity/default-context", null, 200);
    assertThat(view.get("stored").asBoolean()).isTrue();
    assertThat(view.get("warehouse").isNull()).as("明确清空的维度不该被回落覆盖").isTrue();
    assertThat(view.get("note").isNull()).as("没有回落就没有回落说明").isTrue();
  }

  @Test
  void 重置回到什么都没设且幂等() throws Exception {
    String token = login();
    UUID shopId = UUID.randomUUID();
    stubShops(token, shopListJson(shopJson(shopId, "S-8", "荷兰店", "ACTIVE")));
    stubWarehouses(token, warehouseListJson());
    put(token, shopId.toString(), null, null);

    request(token, "DELETE", "/identity/default-context", null, 200);
    var view = request(token, "GET", "/identity/default-context", null, 200);
    assertThat(view.get("stored").asBoolean()).as("重置后就是没存过").isFalse();
    assertThat(view.get("shop").isNull()).isTrue();

    // 幂等：本来没存过再删一次也算成功
    request(token, "DELETE", "/identity/default-context", null, 200);
  }

  @Test
  void 法人维度按权限码闸住() throws Exception {
    String owner = login();
    String tag = suffix();
    // 造一个**没有任何权限码**的成员：法人清单必须读不到
    UUID role =
        UUID.fromString(
            request(
                    owner,
                    "POST",
                    "/identity/roles",
                    Map.of(
                        "code",
                        "DC" + tag,
                        "name",
                        "默认上下文角色 " + tag,
                        "status",
                        "ACTIVE",
                        "permissions",
                        List.of()),
                    200)
                .asText());
    request(
        owner,
        "POST",
        "/identity/members",
        memberInput(
            "dc-" + tag.toLowerCase(),
            null,
            List.of(Map.of("roleId", role.toString(), "scopeMode", "ALL"))),
        200);
    String member = loginUser("dc-" + tag.toLowerCase());
    stubShops(member, shopListJson());
    stubWarehouses(member, warehouseListJson());

    var options = request(member, "GET", "/identity/default-context/options", null, 200);
    var legal = source(options, "LEGAL_ENTITY");
    assertThat(legal.get("available").asBoolean()).as("没有法人读取权限就读不到法人清单").isFalse();
    assertThat(legal.get("reason").asText()).contains("权限");
    assertThat(options.get("legalEntities")).isEmpty();

    // 店铺维度不受影响：它是上游的闸，上游给什么就是什么
    assertThat(source(options, "SHOP").get("available").asBoolean()).isTrue();
  }

  @Test
  void 能力声明如实报出三个维度的来源() throws Exception {
    String token = login();
    stubShops(token, shopListJson());
    stubWarehouses(token, warehouseListJson());

    var capabilities = request(token, "GET", "/identity/default-context/capabilities", null, 200);
    assertThat(capabilities.get("sources")).hasSize(3);
    assertThat(capabilities.get("rules").toString()).contains("权限范围内");
    assertThat(capabilities.get("boundaries").toString()).contains("只写");
  }

  private JsonNode source(JsonNode view, String dimension) {
    for (JsonNode node : view.get("sources")) {
      if (dimension.equals(node.get("dimension").asText())) return node;
    }
    throw new IllegalStateException("没有这个维度：" + dimension);
  }
}
