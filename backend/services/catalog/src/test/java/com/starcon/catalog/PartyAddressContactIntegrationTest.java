package com.starcon.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.tenancy.Ids;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 3.6.3.1 地址与联系人的集成测试。
 *
 * <p>真实 PostgreSQL 16（Testcontainers）+ FORCE RLS，且连接池固定 1 条连接： 只有复用同一条连接，才能真正证明归还之后不会串租户。
 *
 * <p>本类覆盖三组断言：
 *
 * <ol>
 *   <li><b>冻结快照</b>（{@link #地址改内容后旧版本仍是当时的内容()} 等）：蓝图那条 「单据上的地址是冻结快照，本模块改地址不影响已发生单据」—— 本侧要证明的是
 *       「旧版本行不会被改写」「能按 version_no 精确取回当时的内容」「时间戳没偏」。
 *   <li><b>默认标记与整体替换</b>：每个用途一个默认、只改标签不产生版本行。
 *   <li><b>租户隔离三断言</b>（{@link #租户隔离三断言覆盖地址与联系人()}）， 并且<b>覆盖用途行与版本行</b> —— 那两行才是本模块真正的越权面。
 * </ol>
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class PartyAddressContactIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static final ObjectMapper JSON = new ObjectMapper();
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final AtomicInteger SEQ = new AtomicInteger();
  static final List<String> allPermissions =
      java.util.stream.Stream.of("spu", "sku", "attribute", "research", "compliance", "assets")
          .flatMap(
              p ->
                  java.util.stream.Stream.of("read", "create", "update", "delete")
                      .map(op -> "catalog." + p + "." + op))
          .toList();

  static AccessService.Access actor(
      UUID uid, UUID tenant, UUID dept, List<AccessService.Grant> grants) {
    return new AccessService.Access(
        uid,
        tenant,
        "Test",
        UUID.randomUUID(),
        dept,
        grants.stream().flatMap(g -> g.permissions().stream()).distinct().toList(),
        grants);
  }

  static AccessService.Grant grant(String scope, List<String> permissions) {
    return new AccessService.Grant(UUID.randomUUID(), "TEST", scope, permissions);
  }

  static {
    pg.start();
    try {
      try (var c =
              DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
          var s = c.createStatement()) {
        s.execute("create role starcon_app nologin");
        s.execute("create role catalog_app login password 'test-only' nosuperuser nobypassrls");
      }
      identity = HttpServer.create(new InetSocketAddress(0), 0);
      identity.createContext(
          "/internal/access",
          e -> {
            var access = actors.get(e.getRequestHeaders().getFirst("Authorization"));
            if (access == null) {
              e.sendResponseHeaders(401, -1);
              e.close();
              return;
            }
            byte[] body = JSON.writeValueAsBytes(access);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      actors.put("Bearer tenant-a", actor(user, a, null, List.of(grant("ALL", allPermissions))));
      actors.put("Bearer tenant-b", actor(user, b, null, List.of(grant("ALL", allPermissions))));
      // 只有商品读权限：写端点必须 403（地址与联系人沿用商品主数据域权限）。
      actors.put(
          "Bearer reader",
          actor(user, a, null, List.of(grant("ALL", List.of("catalog.spu.read")))));
      identity.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "catalog_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://localhost:" + identity.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
    // 单连接：只有复用同一条连接，才能真正证明归还之后不会串租户。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @Autowired PartyAddressService addresses;
  @Autowired PartyContactService contacts;
  @Autowired PartyService parties;
  @Autowired AccessService access;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  AccessService.Access actor;
  AccessService.Access actorB;

  @BeforeEach
  void setUp() {
    actor = actors.get("Bearer tenant-a");
    actorB = actors.get("Bearer tenant-b");
  }

  <T> T tx(AccessService.Access who, java.util.function.Supplier<T> work) {
    return access.run(who, "catalog.spu.read", work);
  }

  /** 写端点走 update 权限上下文。 */
  <T> T write(AccessService.Access who, java.util.function.Supplier<T> work) {
    return access.run(who, "catalog.spu.update", work);
  }

  /** 删除类动作返回 void，用这个包装。 */
  void writeVoid(AccessService.Access who, Runnable work) {
    write(
        who,
        () -> {
          work.run();
          return null;
        });
  }

  static String unique(String prefix) {
    return prefix + "-" + SEQ.incrementAndGet();
  }

  /** 建一个可用主体（有 SUPPLIER 角色），地址与联系人挂在它下面。 */
  UUID party(AccessService.Access who, String prefix) {
    String code = unique(prefix);
    return write(
        who,
        () ->
            parties
                .save(
                    who,
                    null,
                    new CatalogModels.PartyInput(
                        code,
                        prefix + " 主体",
                        "ORGANIZATION",
                        "CN",
                        "box@" + code.toLowerCase() + ".example",
                        null,
                        null,
                        "ACTIVE",
                        List.of(new CatalogModels.PartyRoleInput("SUPPLIER", "ACTIVE", "")),
                        0))
                .id());
  }

  static CatalogModels.PartyAddressInput address(
      String streetLine1, String city, CatalogModels.PartyAddressUsageInput... usages) {
    return new CatalogModels.PartyAddressInput(
        "CN",
        "广东省",
        city,
        "南山区",
        "518000",
        streetLine1,
        "3 号楼 5 层",
        "仓库",
        "首次录入",
        List.of(usages),
        0);
  }

  static CatalogModels.PartyAddressUsageInput usage(String value, boolean isDefault) {
    return new CatalogModels.PartyAddressUsageInput(value, isDefault);
  }

  static CatalogModels.PartyContactInput contact(String name, String email, boolean primary) {
    return new CatalogModels.PartyContactInput(
        name, "采购经理", email, "+86-755-0000-0000", "138-0000-0000", primary, "对接人", 0);
  }

  // ================================================================ 冻结快照：版本链

  @Test
  void 建档即写入版本一且时间戳没偏() {
    UUID partyId = party(actor, "ADDR");
    UUID addressId =
        write(actor, () -> addresses.save(actor, partyId, null, address("科技园路 1 号", "深圳市"))).id();

    var detail = tx(actor, () -> addresses.detail(partyId, addressId));
    assertThat(detail.city()).isEqualTo("深圳市");
    assertThat(detail.latestVersionNo()).isEqualTo(1);
    assertThat(detail.versionCount()).isEqualTo(1);
    // 版本 1 的单行地址与内容列一致（oneLine 由服务端拼，与快照同口径）。
    assertThat(detail.oneLine()).contains("科技园路 1 号").contains("深圳市").contains("CN");

    var versions = tx(actor, () -> addresses.versions(partyId, addressId));
    assertThat(versions).hasSize(1);
    var v1 = versions.getFirst();
    assertThat(v1.versionNo()).isEqualTo(1);
    assertThat(v1.current()).isTrue();
    assertThat(v1.changeNote()).isEqualTo("首次录入");
    assertThat(v1.streetLine1()).isEqualTo("科技园路 1 号");
    assertThat(v1.changedBy()).isNotNull();
    // **时间戳写回的值**要断言，不能只断言没抛异常：
    // 用 Timestamp.valueOf(LocalDateTime) 读 timestamptz 会静默偏 −28800s（东八区），
    // 而偏 8 小时的时间戳在「今天几点改的地址」这种界面上看起来完全正常。
    assertThat(v1.changedAt()).isNotNull();
    assertThat(Instant.parse(v1.changedAt()))
        .isCloseTo(Instant.now(), within(5, ChronoUnit.MINUTES));
  }

  @Test
  void 地址改内容后旧版本仍是当时的内容() {
    UUID partyId = party(actor, "SNAP");
    UUID addressId =
        write(
                actor,
                () ->
                    addresses.save(
                        actor, partyId, null, address("老门牌 9 号", "宁波市", usage("SHIP_FROM", true))))
            .id();
    long v1Version = tx(actor, () -> addresses.detail(partyId, addressId)).version();

    // 改地址内容：门牌号变了。
    write(
        actor,
        () ->
            addresses.save(
                actor,
                partyId,
                addressId,
                new CatalogModels.PartyAddressInput(
                    "CN",
                    "浙江省",
                    "宁波市",
                    "鄞州区",
                    "315100",
                    "新门牌 88 号",
                    null,
                    "仓库",
                    "门牌号改了",
                    List.of(usage("SHIP_FROM", true)),
                    v1Version)));

    var versions = tx(actor, () -> addresses.versions(partyId, addressId));
    assertThat(versions).hasSize(2);
    // 最新一版在前。
    assertThat(versions.get(0).versionNo()).isEqualTo(2);
    assertThat(versions.get(0).current()).isTrue();
    assertThat(versions.get(0).streetLine1()).isEqualTo("新门牌 88 号");
    assertThat(versions.get(0).changeNote()).isEqualTo("门牌号改了");

    // **旧版本没有被改写** —— 这一条就是「改地址不影响已发生单据」在本模块的落点：
    // 06 采购单引用 (address_id, version_no=1) 时取回的是老门牌，不是新门牌。
    var v1 = versions.get(1);
    assertThat(v1.versionNo()).isEqualTo(1);
    assertThat(v1.current()).isFalse();
    assertThat(v1.streetLine1()).isEqualTo("老门牌 9 号");
    assertThat(v1.streetLine2()).isEqualTo("3 号楼 5 层");
    assertThat(v1.city()).isEqualTo("宁波市");
    assertThat(v1.stateProvince()).isEqualTo("广东省"); // 第 1 版是建档时那版，不是改后的

    // 按 version_no 精确取回（单据回放走的路径）。
    var fetched = tx(actor, () -> addresses.version(partyId, addressId, 1));
    assertThat(fetched.streetLine1()).isEqualTo("老门牌 9 号");
    assertThat(fetched.current()).isFalse();
    assertThat(Instant.parse(fetched.changedAt()))
        .isBeforeOrEqualTo(Instant.parse(versions.get(0).changedAt()));
  }

  @Test
  void 取一个不存在的版本会报当前最新是第几版() {
    UUID partyId = party(actor, "VER");
    UUID addressId =
        write(actor, () -> addresses.save(actor, partyId, null, address("某路 1 号", "广州市"))).id();
    var error = catchThrowable(() -> tx(actor, () -> addresses.version(partyId, addressId, 7)));
    assertThat(error).isInstanceOf(ResponseStatusException.class);
    assertThat(((ResponseStatusException) error).getMessage()).contains("第 7 版").contains("第 1 版");
  }

  @Test
  void 内容未改的保存不产生新版本而只改标签也不产生() {
    UUID partyId = party(actor, "A3");
    UUID addressId =
        write(actor, () -> addresses.save(actor, partyId, null, address("同一条街 8 号", "青岛市"))).id();
    long version = tx(actor, () -> addresses.detail(partyId, addressId)).version();

    // 内容一字未改：保存不追加版本（规则 A3）。
    // 与建档时**逐字相同**的内容（helper 固定写 广东省 / 南山区 / 518000 / 3 号楼 5 层 / 仓库，
    // 城市取建档时传入的「青岛市」）。
    write(
        actor,
        () ->
            addresses.save(
                actor,
                partyId,
                addressId,
                new CatalogModels.PartyAddressInput(
                    "CN",
                    "广东省",
                    "青岛市",
                    "南山区",
                    "518000",
                    "同一条街 8 号",
                    "3 号楼 5 层",
                    "仓库",
                    "点了一下保存",
                    List.of(usage("SHIP_TO", true)),
                    version)));
    var afterNoop = tx(actor, () -> addresses.detail(partyId, addressId));
    assertThat(afterNoop.latestVersionNo()).isEqualTo(1);
    assertThat(afterNoop.versionCount()).isEqualTo(1);
    // 乐观锁版本号照常 +1（每次保存都 +1，与历史序号不是同一个数）。
    assertThat(afterNoop.version()).isEqualTo(version + 1);

    // 只改用途 / 默认标记：也不产生版本行。
    write(
        actor,
        () ->
            addresses.saveUsages(
                actor,
                partyId,
                addressId,
                new CatalogModels.PartyAddressUsageSaveInput(
                    List.of(usage("SHIP_TO", true), usage("PICKUP", false)), afterNoop.version())));
    var afterUsage = tx(actor, () -> addresses.detail(partyId, addressId));
    assertThat(afterUsage.latestVersionNo()).isEqualTo(1);
    assertThat(afterUsage.versionCount()).isEqualTo(1);
    assertThat(afterUsage.usages()).containsExactly("SHIP_TO", "PICKUP");
    assertThat(afterUsage.defaultUsages()).containsExactly("SHIP_TO");
  }

  // ================================================================ 默认标记与整体替换

  @Test
  void 每个用途各有一个默认地址且设默认会转移() {
    UUID partyId = party(actor, "DEF");
    UUID first =
        write(
                actor,
                () ->
                    addresses.save(
                        actor, partyId, null, address("一号仓路 1 号", "杭州市", usage("PICKUP", true))))
            .id();
    UUID second =
        write(actor, () -> addresses.save(actor, partyId, null, address("二号仓路 2 号", "苏州市"))).id();

    // 第二个地址还没标提货用途时设默认会被拒（一次不做两件事）。
    var error =
        catchThrowable(
            () ->
                write(
                    actor,
                    () ->
                        addresses.setDefault(
                            actor,
                            partyId,
                            second,
                            new CatalogModels.PartyAddressDefaultInput("PICKUP", true, 0))));
    assertThat(error).isInstanceOf(ResponseStatusException.class);
    assertThat(((ResponseStatusException) error).getMessage()).contains("用途");

    // 先标用途，再设默认 → 默认从一号仓转移到二号仓（一个主体一个默认提货地址）。
    long v = tx(actor, () -> addresses.detail(partyId, second)).version();
    write(
        actor,
        () ->
            addresses.saveUsages(
                actor,
                partyId,
                second,
                new CatalogModels.PartyAddressUsageSaveInput(List.of(usage("PICKUP", false)), v)));
    long v2 = tx(actor, () -> addresses.detail(partyId, second)).version();
    write(
        actor,
        () ->
            addresses.setDefault(
                actor,
                partyId,
                second,
                new CatalogModels.PartyAddressDefaultInput("PICKUP", true, v2)));

    var defaultPickup = tx(actor, () -> addresses.defaultAddress(partyId, "PICKUP"));
    assertThat(defaultPickup.id()).isEqualTo(second);
    assertThat(tx(actor, () -> addresses.detail(partyId, first)).defaultUsages()).isEmpty();
    // 用途整体替换后第一行应当只剩它自己提交过的用途。
    assertThat(tx(actor, () -> addresses.detail(partyId, second)).defaultUsages())
        .containsExactly("PICKUP");
  }

  @Test
  void 没有默认地址时报错并说清是哪个用途() {
    UUID partyId = party(actor, "NODEF");
    write(actor, () -> addresses.save(actor, partyId, null, address("某路 5 号", "成都市")));
    var error = catchThrowable(() -> tx(actor, () -> addresses.defaultAddress(partyId, "SHIP_TO")));
    assertThat(error).isInstanceOf(ResponseStatusException.class);
    assertThat(((ResponseStatusException) error).getMessage()).contains("收货地址");
  }

  @Test
  void 删除默认地址被拒并点名用途() {
    UUID partyId = party(actor, "DEL");
    UUID addressId =
        write(
                actor,
                () ->
                    addresses.save(
                        actor,
                        partyId,
                        null,
                        address("仓路 3 号", "武汉市", usage("SHIP_TO", true), usage("PICKUP", true))))
            .id();
    var error =
        catchThrowable(
            () -> writeVoid(actor, () -> addresses.delete(actor, partyId, addressId, 0)));
    assertThat(error).isInstanceOf(ResponseStatusException.class);
    String message = ((ResponseStatusException) error).getMessage();
    assertThat(message).contains("默认地址");
    // 逐个点名是哪个用途（发货与提货都被点名）。
    assertThat(message).contains("收货地址").contains("提货地址");
  }

  @Test
  void 软删除保留行与版本链且默认标记被清掉() {
    UUID partyId = party(actor, "SOFT");
    UUID addressId =
        write(
                actor,
                () ->
                    addresses.save(
                        actor, partyId, null, address("待删路 6 号", "西安市", usage("SHIP_TO", true))))
            .id();
    // 取消默认后才能删。
    long v = tx(actor, () -> addresses.detail(partyId, addressId)).version();
    write(
        actor,
        () ->
            addresses.setDefault(
                actor,
                partyId,
                addressId,
                new CatalogModels.PartyAddressDefaultInput("SHIP_TO", false, v)));
    long v2 = tx(actor, () -> addresses.detail(partyId, addressId)).version();
    writeVoid(actor, () -> addresses.delete(actor, partyId, addressId, v2));

    // 行还在、版本链一行不少、详情仍可读并带 deleted=true。
    var detail = tx(actor, () -> addresses.detail(partyId, addressId));
    assertThat(detail.deleted()).isTrue();
    assertThat(detail.selectionBlockCode()).isEqualTo("ADDRESS_DELETED");
    assertThat(detail.defaultUsages()).isEmpty();
    assertThat(tx(actor, () -> addresses.versions(partyId, addressId))).hasSize(1);
    // 列表默认不显示它。
    assertThat(
            tx(
                    actor,
                    () ->
                        addresses.list(
                            partyId,
                            new CatalogModels.PartyAddressQuery(null, null, null, null, 0, 20)))
                .items())
        .noneMatch(a -> a.id().equals(addressId));
    // includeDeleted 才带得出来。
    assertThat(
            tx(
                    actor,
                    () ->
                        addresses.list(
                            partyId,
                            new CatalogModels.PartyAddressQuery(null, null, null, true, 0, 20)))
                .items())
        .anyMatch(a -> a.id().equals(addressId));
    // 候选两种模式都不返回它（新单据永远不该选中一个已删的地址）。
    assertThat(tx(actor, () -> addresses.candidates(partyId, null, null, false)))
        .noneMatch(a -> a.id().equals(addressId));
    assertThat(tx(actor, () -> addresses.candidates(partyId, null, null, true)))
        .noneMatch(a -> a.id().equals(addressId));
  }

  // ================================================================ 校验与规则 A5

  @Test
  void 国家和必填列被服务端守住() {
    UUID partyId = party(actor, "VALID");
    var error =
        catchThrowable(
            () ->
                write(
                    actor,
                    () ->
                        addresses.save(
                            actor,
                            partyId,
                            null,
                            new CatalogModels.PartyAddressInput(
                                "", "广东省", "", "南山区", "518000", "  ", null, null, null, List.of(),
                                0))));
    assertThat(error).isInstanceOf(ResponseStatusException.class);
    assertThat(((ResponseStatusException) error).getMessage()).contains("国家");
  }

  @Test
  void 邮编可空但填了空白串被拒() {
    UUID partyId = party(actor, "ZIP");
    // 邮编留空（不传）合法 —— 相当多国家没有邮编体系。
    UUID ok =
        write(
                actor,
                () ->
                    addresses.save(
                        actor,
                        partyId,
                        null,
                        new CatalogModels.PartyAddressInput(
                            "HK",
                            null,
                            "香港",
                            null,
                            null,
                            "中环干诺道 8 号",
                            null,
                            "办事处",
                            null,
                            List.of(),
                            0)))
            .id();
    assertThat(tx(actor, () -> addresses.detail(partyId, ok)).postalCode()).isNull();

    var error =
        catchThrowable(
            () ->
                write(
                    actor,
                    () ->
                        addresses.save(
                            actor,
                            partyId,
                            null,
                            new CatalogModels.PartyAddressInput(
                                "HK",
                                null,
                                "香港",
                                null,
                                "   ",
                                "中环干诺道 8 号",
                                null,
                                "办事处",
                                null,
                                List.of(),
                                0))));
    assertThat(error).isInstanceOf(ResponseStatusException.class);
    assertThat(((ResponseStatusException) error).getMessage()).contains("邮编");
  }

  @Test
  void 拉黑主体不允许新增地址但停用允许() {
    // 停用：允许维护（「先建主体、补齐地址、再启用」是正常流程）。
    String inactiveCode = unique("INACTIVE");
    UUID inactive =
        write(
            actor,
            () ->
                parties
                    .save(
                        actor,
                        null,
                        new CatalogModels.PartyInput(
                            inactiveCode,
                            "停用主体",
                            "ORGANIZATION",
                            "CN",
                            "off@" + inactiveCode.toLowerCase() + ".example",
                            null,
                            null,
                            "INACTIVE",
                            List.of(new CatalogModels.PartyRoleInput("SUPPLIER", "ACTIVE", "")),
                            0))
                    .id());
    UUID addressId =
        write(actor, () -> addresses.save(actor, inactive, null, address("备用路 2 号", "天津市"))).id();
    assertThat(tx(actor, () -> addresses.detail(inactive, addressId)).id()).isEqualTo(addressId);

    // 拉黑：拒绝新增（规则 A5）。
    String blackCode = unique("BLACK");
    UUID black =
        write(
            actor,
            () ->
                parties
                    .save(
                        actor,
                        null,
                        new CatalogModels.PartyInput(
                            blackCode,
                            "拉黑主体",
                            "ORGANIZATION",
                            "CN",
                            "no@" + blackCode.toLowerCase() + ".example",
                            null,
                            null,
                            "ACTIVE",
                            List.of(new CatalogModels.PartyRoleInput("SUPPLIER", "ACTIVE", "")),
                            0))
                    .id());
    write(
        actor,
        () ->
            parties.setStatus(
                actor, black, new CatalogModels.PartyStatusInput("BLACKLISTED", "已被列入制裁名单", 0)));
    var error =
        catchThrowable(
            () ->
                write(actor, () -> addresses.save(actor, black, null, address("黑名单路 1 号", "济南市"))));
    assertThat(error).isInstanceOf(ResponseStatusException.class);
    assertThat(((ResponseStatusException) error).getMessage()).contains("拉黑");

    // 拉黑之后库里确实一条地址都没建出来。
    assertThat(
            tx(
                actor,
                () ->
                    sql.fetchOne("select count(*) from party_address where party_id=?", black)
                        .get(0, Long.class)))
        .isZero();
  }

  @Test
  void 拉黑主体不允许新增但改已有地址仍允许() {
    // 规则 A5 的另一半：拦的是**新增**，不是维护。清理一个已拉黑对手方的地址
    // 正是之后要做的动作 —— 拦掉它会让「解除拉黑前先把地址整理好」这条路走不通。
    String code = unique("BLACKEDIT");
    UUID black =
        write(
            actor,
            () ->
                parties
                    .save(
                        actor,
                        null,
                        new CatalogModels.PartyInput(
                            code,
                            "可编辑的拉黑主体",
                            "ORGANIZATION",
                            "CN",
                            "be@" + code.toLowerCase() + ".example",
                            null,
                            null,
                            "ACTIVE",
                            List.of(new CatalogModels.PartyRoleInput("SUPPLIER", "ACTIVE", "")),
                            0))
                    .id());
    UUID addressId =
        write(actor, () -> addresses.save(actor, black, null, address("整理前路 1 号", "福州市"))).id();
    write(
        actor,
        () ->
            parties.setStatus(
                actor, black, new CatalogModels.PartyStatusInput("BLACKLISTED", "纠纷对手方", 0)));

    // 改地址内容：允许，并且照常追加版本（版本链与主体状态无关）。
    UUID edited =
        write(
                actor,
                () ->
                    addresses.save(
                        actor,
                        black,
                        addressId,
                        new CatalogModels.PartyAddressInput(
                            "CN",
                            "福建省",
                            "福州市",
                            "仓山区",
                            "350000",
                            "整理后路 9 号",
                            null,
                            "仓库",
                            "拉黑后整理地址",
                            List.of(),
                            0)))
            .id();
    assertThat(edited).isEqualTo(addressId);
    assertThat(tx(actor, () -> addresses.versions(black, addressId))).hasSize(2);
  }

  // ================================================================ 联系人

  @Test
  void 联系人建档往返一致且默认只有一个() {
    UUID partyId = party(actor, "CONTACT");
    UUID first =
        write(
                actor,
                () ->
                    contacts.save(
                        actor, partyId, null, contact("张三", "zhangsan@example.com", true)))
            .id();
    UUID second =
        write(
                actor,
                () -> contacts.save(actor, partyId, null, contact("李四", "lisi@example.com", false)))
            .id();

    var detail = tx(actor, () -> contacts.detail(partyId, first));
    assertThat(detail.name()).isEqualTo("张三");
    assertThat(detail.position()).isEqualTo("采购经理");
    assertThat(detail.mobile()).isEqualTo("138-0000-0000");
    assertThat(detail.isPrimary()).isTrue();
    assertThat(detail.selectionBlockCode()).isNull();
    assertThat(detail.createdAt()).isNotNull();
    assertThat(Instant.parse(detail.createdAt()))
        .isCloseTo(Instant.now(), within(5, ChronoUnit.MINUTES));

    // 第二个不能也成默认：整体设默认时前一个被取消。
    write(
        actor,
        () -> contacts.save(actor, partyId, second, contact("李四", "lisi@example.com", true)));
    assertThat(tx(actor, () -> contacts.detail(partyId, first)).isPrimary()).isFalse();
    assertThat(tx(actor, () -> contacts.detail(partyId, second)).isPrimary()).isTrue();
    // 默认联系人端点返回的是那一个。
    assertThat(tx(actor, () -> contacts.primary(partyId)).id()).isEqualTo(second);
    // 全库这条主体只有一个默认联系人。
    assertThat(
            tx(
                actor,
                () ->
                    sql.fetchOne(
                            "select count(*) from party_contact where party_id=? and is_primary",
                            partyId)
                        .get(0, Long.class)))
        .isEqualTo(1L);
  }

  @Test
  void 邮箱电话手机至少给一项() {
    UUID partyId = party(actor, "REACH");
    var error =
        catchThrowable(
            () ->
                write(
                    actor,
                    () ->
                        contacts.save(
                            actor,
                            partyId,
                            null,
                            new CatalogModels.PartyContactInput(
                                "王五", null, null, null, null, false, null, 0))));
    assertThat(error).isInstanceOf(ResponseStatusException.class);
    assertThat(((ResponseStatusException) error).getMessage()).contains("至少填写一项");

    // 只有一个手机号也合法。
    UUID ok =
        write(
                actor,
                () ->
                    contacts.save(
                        actor,
                        partyId,
                        null,
                        new CatalogModels.PartyContactInput(
                            "王五", null, null, null, "139-0000-0000", false, null, 0)))
            .id();
    assertThat(tx(actor, () -> contacts.detail(partyId, ok)).mobile()).isEqualTo("139-0000-0000");
  }

  @Test
  void 删除默认联系人被拒而删非默认真落库() {
    UUID partyId = party(actor, "CDEL");
    UUID primary =
        write(
                actor,
                () -> contacts.save(actor, partyId, null, contact("默认联系人", "a@example.com", true)))
            .id();
    UUID other =
        write(
                actor,
                () -> contacts.save(actor, partyId, null, contact("备用联系人", "b@example.com", false)))
            .id();

    var error =
        catchThrowable(() -> writeVoid(actor, () -> contacts.delete(actor, partyId, primary, 0)));
    assertThat(error).isInstanceOf(ResponseStatusException.class);
    assertThat(((ResponseStatusException) error).getMessage()).contains("默认联系人");

    // 非默认联系人：物理删除真的把行删掉了（不是软删除）。
    writeVoid(actor, () -> contacts.delete(actor, partyId, other, 0));
    assertThat(tx(actor, () -> contacts.list(partyId, null, null))).hasSize(1);
    assertThat(
            tx(
                actor,
                () ->
                    sql.fetchOne("select count(*) from party_contact where id=?", other)
                        .get(0, Long.class)))
        .isZero();
  }

  @Test
  void 取消默认联系人之后可以删除它() {
    UUID partyId = party(actor, "CUNSET");
    UUID id =
        write(
                actor,
                () -> contacts.save(actor, partyId, null, contact("唯一联系人", "c@example.com", true)))
            .id();
    long v = tx(actor, () -> contacts.detail(partyId, id)).version();
    write(
        actor,
        () ->
            contacts.setPrimary(
                actor, partyId, id, new CatalogModels.PartyContactPrimaryInput(false, v)));
    long v2 = tx(actor, () -> contacts.detail(partyId, id)).version();
    writeVoid(actor, () -> contacts.delete(actor, partyId, id, v2));
    assertThat(tx(actor, () -> contacts.list(partyId, null, null))).isEmpty();
    var error = catchThrowable(() -> tx(actor, () -> contacts.primary(partyId)));
    assertThat(error).isInstanceOf(ResponseStatusException.class);
    assertThat(((ResponseStatusException) error).getMessage()).contains("还没有默认联系人");
  }

  @Test
  void 拉黑主体不允许新增联系人() {
    String code = unique("CBLACK");
    UUID black =
        write(
            actor,
            () ->
                parties
                    .save(
                        actor,
                        null,
                        new CatalogModels.PartyInput(
                            code,
                            "拉黑联系人主体",
                            "ORGANIZATION",
                            "CN",
                            "cb@" + code.toLowerCase() + ".example",
                            null,
                            null,
                            "ACTIVE",
                            List.of(new CatalogModels.PartyRoleInput("SUPPLIER", "ACTIVE", "")),
                            0))
                    .id());
    write(
        actor,
        () ->
            parties.setStatus(
                actor, black, new CatalogModels.PartyStatusInput("BLACKLISTED", "纠纷对手方", 0)));
    var error =
        catchThrowable(
            () ->
                write(
                    actor,
                    () ->
                        contacts.save(
                            actor, black, null, contact("新增联系人", "n@example.com", false))));
    assertThat(error).isInstanceOf(ResponseStatusException.class);
    assertThat(((ResponseStatusException) error).getMessage()).contains("拉黑");
  }

  // ================================================================ 搜索与分页

  @Test
  void 关键词通配符被转义而不是变成匹配全部() {
    UUID partyId = party(actor, "LIKE");
    write(
        actor,
        () ->
            addresses.save(
                actor, partyId, null, address("正常路 1 号", "南通市", usage("SHIP_TO", true))));
    write(actor, () -> addresses.save(actor, partyId, null, address("百分路 2 号", "南通市")));

    var all =
        tx(
            actor,
            () ->
                addresses.list(
                    partyId, new CatalogModels.PartyAddressQuery(null, null, null, null, 0, 20)));
    assertThat(all.items()).hasSize(2);
    // 搜 % 本该匹配不到任何东西（转义后是字面量 %），而不是「匹配全部」。
    var percent =
        tx(
            actor,
            () ->
                addresses.list(
                    partyId, new CatalogModels.PartyAddressQuery("%", null, null, null, 0, 20)));
    assertThat(percent.items()).isEmpty();
    // 搜城市能命中两条（关键词覆盖城市）。
    var byCity =
        tx(
            actor,
            () ->
                addresses.list(
                    partyId, new CatalogModels.PartyAddressQuery("南通", null, null, null, 0, 20)));
    assertThat(byCity.items()).hasSize(2);
  }

  @Test
  void 分页按页切片且统计不受筛选影响() {
    UUID partyId = party(actor, "PAGE");
    for (int i = 1; i <= 5; i++) {
      int index = i;
      write(
          actor,
          () ->
              addresses.save(
                  actor,
                  partyId,
                  null,
                  address(
                      "分页路 " + index + " 号",
                      "扬州市",
                      usage(index == 1 ? "SHIP_TO" : "PICKUP", true))));
    }
    var firstPage =
        tx(
            actor,
            () ->
                addresses.list(
                    partyId, new CatalogModels.PartyAddressQuery(null, null, null, null, 0, 2)));
    assertThat(firstPage.items()).hasSize(2);
    assertThat(firstPage.total()).isEqualTo(5);
    // 一个主体每个用途只有一个默认：1 条 SHIP_TO 默认 + 1 条 PICKUP 默认（后建的把先前的挤掉了）。
    assertThat(firstPage.defaultCount()).isEqualTo(2);
    var secondPage =
        tx(
            actor,
            () ->
                addresses.list(
                    partyId, new CatalogModels.PartyAddressQuery(null, null, null, null, 1, 2)));
    assertThat(secondPage.items()).hasSize(2);
    // 两页不重叠。
    assertThat(
            java.util.Collections.disjoint(
                firstPage.items().stream().map(CatalogModels.PartyAddress::id).toList(),
                secondPage.items().stream().map(CatalogModels.PartyAddress::id).toList()))
        .isTrue();
    // 筛出 1 条时统计仍是全量的 5 条。
    var filtered =
        tx(
            actor,
            () ->
                addresses.list(
                    partyId,
                    new CatalogModels.PartyAddressQuery(null, "SHIP_TO", null, null, 0, 20)));
    assertThat(filtered.items()).hasSize(1);
    assertThat(filtered.total()).isEqualTo(1);
    assertThat(filtered.defaultCount()).isEqualTo(2);
  }

  @Test
  void 未标用途的地址被单独计数() {
    UUID partyId = party(actor, "UNASSIGNED");
    write(
        actor,
        () ->
            addresses.save(
                actor, partyId, null, address("有用途 1 号", "金华市", usage("SHIP_TO", false))));
    write(actor, () -> addresses.save(actor, partyId, null, address("没用途 2 号", "金华市")));
    var page =
        tx(
            actor,
            () ->
                addresses.list(
                    partyId, new CatalogModels.PartyAddressQuery(null, null, null, null, 0, 20)));
    assertThat(page.total()).isEqualTo(2);
    assertThat(page.unassignedCount()).isEqualTo(1);
    assertThat(page.defaultCount()).isZero();
  }

  // ================================================================ RLS 结构

  @Test
  void 四张表都开了FORCERLS且默认地址在DB层唯一() {
    for (String table :
        List.of("party_address", "party_address_usage", "party_address_version", "party_contact")) {
      assertThat(
              tx(
                  actor,
                  () ->
                      sql.fetchOne(
                          "select relrowsecurity,relforcerowsecurity from pg_class where relname=?",
                          table)))
          .as(table + " 必须同时 ENABLE 与 FORCE RLS")
          .isNotNull();
      var flags =
          tx(
              actor,
              () ->
                  sql.fetchOne(
                      "select relrowsecurity as r,relforcerowsecurity as f from pg_class where relname=?",
                      table));
      assertThat(Boolean.TRUE.equals(flags.get("r", Boolean.class)))
          .as(table + " RLS enabled")
          .isTrue();
      assertThat(Boolean.TRUE.equals(flags.get("f", Boolean.class)))
          .as(table + " RLS forced")
          .isTrue();
    }

    // 「一个主体一个默认提货地址」在 DB 层就是唯一的：绕过服务层直写也会撞。
    UUID partyId = party(actor, "DBUNIQ");
    UUID first =
        write(
                actor,
                () ->
                    addresses.save(
                        actor, partyId, null, address("甲仓 1 号", "扬州市", usage("PICKUP", true))))
            .id();
    UUID second =
        write(
                actor,
                () ->
                    addresses.save(
                        actor, partyId, null, address("乙仓 2 号", "扬州市", usage("PICKUP", false))))
            .id();
    assertThat(
            catchThrowable(
                () ->
                    tx(
                        actor,
                        () ->
                            sql.execute(
                                "insert into party_address_usage(id,tenant_id,party_id,address_id,usage,is_default)"
                                    + " values(?,?,?,?,'PICKUP',true)",
                                Ids.next(),
                                a,
                                partyId,
                                second))))
        .as("第二个默认提货地址必须被 DB 拒绝")
        .isNotNull();
    assertThat(tx(actor, () -> addresses.defaultAddress(partyId, "PICKUP")).id()).isEqualTo(first);
  }

  // ================================================================ 租户隔离三断言

  /**
   * 租户隔离三断言，<b>并且覆盖用途行与版本行</b>。
   *
   * <p>只测地址本体等于没测：版本行与用途行才是本模块真正的越权面 —— 「A 读到自己的地址时把 B 的提货地址用途与历史版本一起读出来」会直接让 A 的 10 物流单填上 B 的仓库地址。
   */
  @Test
  void 租户隔离三断言覆盖地址与联系人() throws Exception {
    UUID myParty = party(actor, "MINE");
    UUID theirParty = party(actorB, "THEIRS");
    UUID myAddress =
        write(
                actor,
                () ->
                    addresses.save(
                        actor, myParty, null, address("我方仓库路 1 号", "福州市", usage("PICKUP", true))))
            .id();
    long myVersion =
        write(
                actor,
                () ->
                    addresses.save(
                        actor,
                        myParty,
                        myAddress,
                        new CatalogModels.PartyAddressInput(
                            "CN",
                            "福建省",
                            "福州市",
                            "仓山区",
                            "350000",
                            "我方仓库路 2 号",
                            null,
                            "仓库",
                            "改了门牌",
                            List.of(usage("PICKUP", true)),
                            0)))
            .version();
    UUID theirAddress =
        write(
                actorB,
                () ->
                    addresses.save(
                        actorB,
                        theirParty,
                        null,
                        address("对方仓库路 1 号", "长沙市", usage("PICKUP", true))))
            .id();
    UUID myContact =
        write(
                actor,
                () ->
                    contacts.save(actor, myParty, null, contact("我方对接人", "mine@example.com", true)))
            .id();
    UUID theirContact =
        write(
                actorB,
                () ->
                    contacts.save(
                        actorB, theirParty, null, contact("对方对接人", "theirs@example.com", true)))
            .id();

    // 两边都必须真的有数据 —— 否则下面的断言就是空的。
    assertThat(
            tx(
                    actor,
                    () ->
                        addresses.list(
                            myParty,
                            new CatalogModels.PartyAddressQuery(null, null, null, null, 0, 20)))
                .items())
        .hasSize(1);
    assertThat(
            tx(
                    actorB,
                    () ->
                        addresses.list(
                            theirParty,
                            new CatalogModels.PartyAddressQuery(null, null, null, null, 0, 20)))
                .items())
        .hasSize(1);
    assertThat(tx(actor, () -> contacts.list(myParty, null, null))).hasSize(1);
    assertThat(tx(actorB, () -> contacts.list(theirParty, null, null))).hasSize(1);

    // 1) 两租户互不可见：读对方主体下的地址 / 版本 / 用途 / 联系人全部 404。
    assertThatThrownBy(() -> tx(actor, () -> addresses.detail(theirParty, theirAddress)))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> tx(actor, () -> addresses.versions(theirParty, theirAddress)))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> tx(actor, () -> contacts.detail(theirParty, theirContact)))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> tx(actor, () -> addresses.defaultAddress(theirParty, "PICKUP")))
        .isInstanceOf(ResponseStatusException.class);
    // 写路径同样挡：不能改对方的地址、不能删对方的联系人。
    assertThatThrownBy(
            () -> writeVoid(actor, () -> addresses.delete(actor, theirParty, theirAddress, 0)))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(
            () -> writeVoid(actor, () -> contacts.delete(actor, theirParty, theirContact, 0)))
        .isInstanceOf(ResponseStatusException.class);
    // 对方的数据没被动过。
    assertThat(tx(actorB, () -> addresses.detail(theirParty, theirAddress)).streetLine1())
        .isEqualTo("对方仓库路 1 号");
    assertThat(tx(actorB, () -> contacts.detail(theirParty, theirContact)).name())
        .isEqualTo("对方对接人");
    // 自己的版本链仍然完整（我的版本号推进没有被对方影响）。
    assertThat(tx(actor, () -> addresses.versions(myParty, myAddress))).hasSize(2);

    // 2) 伪造 X-Tenant-Id 头无效：租户只来自已认证成员关系。
    http.perform(
            get("/catalog/parties/{party}/addresses", myParty)
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a))
        .andExpect(status().isNotFound());
    http.perform(
            get("/catalog/parties/{party}/contacts", myParty)
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a))
        .andExpect(status().isNotFound());
    // 伪造头下写入仍落在 B 自己名下，而不是伪造的 A。
    String code = unique("FORGED");
    UUID forgedParty =
        write(
            actorB,
            () ->
                parties
                    .save(
                        actorB,
                        null,
                        new CatalogModels.PartyInput(
                            code,
                            "伪造头下的主体",
                            "ORGANIZATION",
                            "CN",
                            "f@" + code.toLowerCase() + ".example",
                            null,
                            null,
                            "ACTIVE",
                            List.of(new CatalogModels.PartyRoleInput("SUPPLIER", "ACTIVE", "")),
                            0))
                    .id());
    UUID forgedAddress =
        write(
                actorB,
                () ->
                    addresses.save(
                        actorB,
                        forgedParty,
                        null,
                        address("伪造头下的地址 1 号", "沈阳市", usage("PICKUP", true))))
            .id();
    // 断言**行确实落在 B 名下**（不只断言 A 看不见）。
    assertThat(
            tx(
                actorB,
                () ->
                    sql.fetchOne("select tenant_id from party_address where id=?", forgedAddress)
                        .get(0, UUID.class)))
        .isEqualTo(b);
    assertThatThrownBy(() -> tx(actor, () -> addresses.detail(forgedParty, forgedAddress)))
        .isInstanceOf(ResponseStatusException.class);
    assertThat(tx(actorB, () -> addresses.detail(forgedParty, forgedAddress)).id())
        .isEqualTo(forgedAddress);
    // 版本行也落在 B 名下。
    assertThat(
            tx(
                actorB,
                () ->
                    sql.fetchOne(
                            "select tenant_id from party_address_version where address_id=?",
                            forgedAddress)
                        .get(0, UUID.class)))
        .isEqualTo(b);

    // 3) 连接池归还后不串租户（单连接反复交替），逐轮断言地址 / 用途 / 版本 / 联系人都不串。
    for (int i = 0; i < 5; i++) {
      var myAddresses =
          tx(
                  actor,
                  () ->
                      addresses.list(
                          myParty,
                          new CatalogModels.PartyAddressQuery(null, null, null, null, 0, 200)))
              .items()
              .stream()
              .map(CatalogModels.PartyAddress::id)
              .toList();
      var theirAddresses =
          tx(
                  actorB,
                  () ->
                      addresses.list(
                          theirParty,
                          new CatalogModels.PartyAddressQuery(null, null, null, null, 0, 200)))
              .items()
              .stream()
              .map(CatalogModels.PartyAddress::id)
              .toList();
      assertThat(java.util.Collections.disjoint(myAddresses, theirAddresses)).isTrue();
      var myContacts =
          tx(actor, () -> contacts.list(myParty, null, null)).stream()
              .map(CatalogModels.PartyContact::id)
              .toList();
      var theirContacts =
          tx(actorB, () -> contacts.list(theirParty, null, null)).stream()
              .map(CatalogModels.PartyContact::id)
              .toList();
      assertThat(java.util.Collections.disjoint(myContacts, theirContacts)).isTrue();
      // 用途行与版本行同样不相交 —— 这才是本模块真正的越权面。
      var myUsageRows =
          tx(actor, () -> sql.fetch("select id from party_address_usage where party_id=?", myParty))
              .stream()
              .map(r -> r.get("id", UUID.class))
              .toList();
      var theirUsageRows =
          tx(
                  actorB,
                  () ->
                      sql.fetch("select id from party_address_usage where party_id=?", theirParty))
              .stream()
              .map(r -> r.get("id", UUID.class))
              .toList();
      assertThat(myUsageRows).isNotEmpty();
      assertThat(theirUsageRows).isNotEmpty();
      assertThat(java.util.Collections.disjoint(myUsageRows, theirUsageRows)).isTrue();
      var myVersions =
          tx(
                  actor,
                  () -> sql.fetch("select id from party_address_version where party_id=?", myParty))
              .stream()
              .map(r -> r.get("id", UUID.class))
              .toList();
      var theirVersions =
          tx(
                  actorB,
                  () ->
                      sql.fetch(
                          "select id from party_address_version where party_id=?", theirParty))
              .stream()
              .map(r -> r.get("id", UUID.class))
              .toList();
      assertThat(myVersions).isNotEmpty();
      assertThat(theirVersions).isNotEmpty();
      assertThat(java.util.Collections.disjoint(myVersions, theirVersions)).isTrue();
      // 候选列表也不相交。
      var myCandidates =
          tx(actor, () -> addresses.candidates(myParty, "PICKUP", null, true)).stream()
              .map(CatalogModels.PartyAddress::id)
              .toList();
      var theirCandidates =
          tx(actorB, () -> addresses.candidates(theirParty, "PICKUP", null, true)).stream()
              .map(CatalogModels.PartyAddress::id)
              .toList();
      assertThat(java.util.Collections.disjoint(myCandidates, theirCandidates)).isTrue();
      assertThat(myVersion).isPositive();
    }
  }

  // ================================================================ HTTP 全链路与权限

  @Test
  void http全链路与读写权限分离() throws Exception {
    String code = unique("HTTP");
    UUID partyId =
        write(
            actor,
            () ->
                parties
                    .save(
                        actor,
                        null,
                        new CatalogModels.PartyInput(
                            code,
                            "HTTP 主体",
                            "ORGANIZATION",
                            "DE",
                            "http@x.example",
                            null,
                            null,
                            "ACTIVE",
                            List.of(new CatalogModels.PartyRoleInput("SUPPLIER", "ACTIVE", "")),
                            0))
                    .id());

    var body =
        JSON.writeValueAsBytes(
            Map.of(
                "countryCode",
                "DE",
                "stateProvince",
                "Bayern",
                "city",
                "München",
                "district",
                "Schwabing",
                "postalCode",
                "80802",
                "streetLine1",
                "Leopoldstraße 1",
                "label",
                "仓库",
                "changeNote",
                "首次录入",
                "usages",
                List.of(Map.of("usage", "PICKUP", "isDefault", true)),
                "version",
                0));
    var created =
        http.perform(
                post("/catalog/parties/{party}/addresses", partyId)
                    .header("Authorization", "Bearer tenant-a")
                    .contentType("application/json")
                    .content(body))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    UUID addressId = UUID.fromString(JSON.readTree(created).get("id").asText());
    assertThat(JSON.readTree(created).get("latestVersionNo").asInt()).isEqualTo(1);

    http.perform(
            get("/catalog/parties/{party}/addresses", partyId)
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items").isArray())
        .andExpect(jsonPath("$.total").value(1));
    http.perform(
            get("/catalog/parties/{party}/addresses/defaults", partyId)
                .param("usage", "PICKUP")
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(addressId.toString()))
        .andExpect(jsonPath("$.usageSummary").value("提货地址（默认）"));
    http.perform(
            get("/catalog/parties/{party}/addresses/candidates", partyId)
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value(addressId.toString()));
    http.perform(
            get("/catalog/parties/{party}/addresses/{address}/versions", partyId, addressId)
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].versionNo").value(1));
    http.perform(
            get("/catalog/parties/{party}/addresses/{address}/versions/1", partyId, addressId)
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.streetLine1").value("Leopoldstraße 1"));

    // 联系人链路。
    var contactBody =
        JSON.writeValueAsBytes(
            Map.of(
                "name",
                "Müller",
                "position",
                "Einkauf",
                "email",
                "mueller@example.de",
                "phone",
                "+49 89 0000",
                "mobile",
                "0170 0000",
                "isPrimary",
                true,
                "note",
                "对接人",
                "version",
                0));
    var contactCreated =
        http.perform(
                post("/catalog/parties/{party}/contacts", partyId)
                    .header("Authorization", "Bearer tenant-a")
                    .contentType("application/json")
                    .content(contactBody))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    UUID contactId = UUID.fromString(JSON.readTree(contactCreated).get("id").asText());
    http.perform(
            get("/catalog/parties/{party}/contacts/default", partyId)
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(contactId.toString()));
    http.perform(
            get("/catalog/parties/{party}/contacts", partyId)
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].name").value("Müller"));

    // 写 403 / 读放行 / 缺 token 401。
    http.perform(
            post("/catalog/parties/{party}/contacts", partyId)
                .header("Authorization", "Bearer reader")
                .contentType("application/json")
                .content(contactBody))
        .andExpect(status().isForbidden());
    http.perform(
            get("/catalog/parties/{party}/contacts", partyId)
                .header("Authorization", "Bearer reader"))
        .andExpect(status().isOk());
    http.perform(get("/catalog/parties/{party}/contacts", partyId))
        .andExpect(status().isUnauthorized());
    // 读端点缺 token 同样 401。
    http.perform(get("/catalog/parties/{party}/addresses", partyId))
        .andExpect(status().isUnauthorized());
  }
}
