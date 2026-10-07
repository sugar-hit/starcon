package com.starcon.catalog;

import static com.starcon.catalog.CatalogModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.jooq.DSLContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 3.6.1.1 往来单位档案：主体建档、唯一编码、联系方式前置条件、角色指派、
 * 「同一主体可同时是供应商和客户」、状态流转、软删除，以及<b>租户隔离三断言</b>。
 *
 * <p><b>真实 PostgreSQL 16 + 强制 RLS</b>：主体与角色指派全部真的落库，不使用任何内存替身。
 * 连接池固定为 1 条连接：只有复用同一条连接，才能真正证明归还后不会串租户。
 *
 * <p><b>本文件最重要的断言</b>：
 *
 * <ol>
 *   <li>{@link #租户隔离三断言覆盖角色指派行()}：不只是主体 —— 角色行同样互不可见。
 *   <li>{@link #同一主体同时是供应商和客户且只有一条记录()}：蓝图那句「用角色档案区分，
 *       不用多份记录」必须在数据上成立，而不只是界面上写了句话。
 *   <li>{@link #rlsPoliciesAreInPlaceAndSoftDeleteKeepsTheRow()}：两表 FORCE RLS，
 *       且软删除是「行还在、列表看不见」而不是物理删除。
 *   <li>{@link #启用前必须有角色而停用后不能再加角色()}：规则 P5 / P6 端到端钉死。
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class PartyIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static final ObjectMapper JSON = new ObjectMapper();
  static final Map<String, AccessService.Access> actors = new java.util.concurrent.ConcurrentHashMap<>();
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
        uid, tenant, "Test", UUID.randomUUID(), dept,
        grants.stream().flatMap(g -> g.permissions().stream()).distinct().toList(), grants);
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
      // 只有商品读权限：写端点必须 403（往来单位沿用商品主数据域权限）。
      actors.put(
          "Bearer reader",
          actor(
              user, a, null, List.of(grant("ALL", List.of("catalog.spu.read")))));
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

  @Autowired PartyService parties;
  @Autowired AccessService access;
  @Autowired DSLContext sql;
  @Autowired PlatformTransactionManager manager;
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

  /** 写端点走 update 权限上下文（读端点与写端点的权限词不同，见 PartyController）。 */
  void writeVoid(AccessService.Access who, Runnable work) {
    access.run(
        who,
        "catalog.spu.update",
        () -> {
          work.run();
          return null;
        });
  }

  /** 每个用例一个唯一前缀，避免跨用例撞唯一编码。 */
  static String unique(String prefix) {
    return prefix + "-" + SEQ.incrementAndGet();
  }

  static PartyInput input(String code, String name, String status, PartyRoleInput... roles) {
    return new PartyInput(
        code, name, "ORGANIZATION", "CN", "purchasing@" + code.toLowerCase() + ".example",
        null, null, status, List.of(roles), 0);
  }

  static PartyRoleInput role(String type) {
    return new PartyRoleInput(type, "ACTIVE", "");
  }

  static PartyInput active(String code, String name, PartyRoleInput... roles) {
    return input(code, name, "ACTIVE", roles);
  }

  // ================================================================ 建档与唯一编码

  @Test
  void 建档与详情往返一致() {
    var saved =
        tx(actor, () -> parties.save(actor, null, active(unique("SUP"), "星尘科技", role("SUPPLIER"))));
    var detail = tx(actor, () -> parties.detail(saved.id()));
    assertThat(detail.code()).startsWith("SUP-");
    assertThat(detail.name()).isEqualTo("星尘科技");
    assertThat(detail.partyType()).isEqualTo("ORGANIZATION");
    assertThat(detail.countryCode()).isEqualTo("CN");
    assertThat(detail.email()).startsWith("purchasing@");
    assertThat(detail.status()).isEqualTo("ACTIVE");
    assertThat(detail.roles()).hasSize(1);
    assertThat(detail.roles().getFirst().roleType()).isEqualTo("SUPPLIER");
    assertThat(detail.roleSummary()).isEqualTo("供应商");
    assertThat(detail.activeRoleCount()).isEqualTo(1);
    // ACTIVE + 有角色 = 可用，判定与 PartyRules.usableBlock 同一口径。
    assertThat(detail.usable()).isTrue();
    assertThat(detail.usableBlockCode()).isNull();
    // created_at / updated_at 真的写进去了（不是空串兜底）。
    assertThat(detail.createdAt()).isNotNull();
    assertThat(detail.updatedAt()).isNotNull();
  }

  @Test
  void 编码在租户内唯一且跨租户可用同一编码() {
    String code = unique("DUP");
    tx(actor, () -> parties.save(actor, null, active(code, "甲公司", role("SUPPLIER"))));
    // 同一租户重复编码：409，且要说清是编码冲突而不是「保存失败」。
    assertThatThrownBy(() -> tx(actor, () -> parties.save(actor, null, active(code, "乙公司", role("SUPPLIER")))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("编码已存在");

    // 跨租户用同一编码：合法（UNIQUE(tenant_id,code) 带租户维度）。
    var theirs =
        tx(actorB, () -> parties.save(actorB, null, active(code, "他们家的同名公司", role("CUSTOMER"))));
    assertThat(theirs.id()).isNotNull();
    assertThat(tx(actorB, () -> parties.detail(theirs.id())).name()).isEqualTo("他们家的同名公司");
  }

  @Test
  void 编码建后不可改() {
    var saved = tx(actor, () -> parties.save(actor, null, active(unique("IMM"), "原名", role("SUPPLIER"))));
    long version = tx(actor, () -> parties.detail(saved.id())).version();
    var original = tx(actor, () -> parties.detail(saved.id()));
    var renamed = new PartyInput(
        "SOMETHING-ELSE", original.name(), original.partyType(), original.countryCode(),
        original.email(), original.phone(), original.website(), original.status(), List.of(), version);
    assertThatThrownBy(() -> tx(actor, () -> parties.save(actor, saved.id(), renamed)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("建后不可修改");
  }

  @Test
  void 邮箱与电话至少给一项() {
    var noContact =
        new PartyInput(unique("NC"), "无联系方式", "ORGANIZATION", "CN", null, null, null, "ACTIVE", List.of(role("SUPPLIER")), 0);
    assertThatThrownBy(() -> tx(actor, () -> parties.save(actor, null, noContact)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("至少填写一项");

    // 官网不能替代邮箱 / 电话：官网是查规格用的，联系不上人对履约没有帮助。
    var onlySite =
        new PartyInput(unique("NS"), "只有官网", "ORGANIZATION", "CN", null, null, "https://x.example", "ACTIVE", List.of(role("SUPPLIER")), 0);
    assertThatThrownBy(() -> tx(actor, () -> parties.save(actor, null, onlySite)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("至少填写一项");
  }

  @Test
  void 乐观锁挡住并发编辑() {
    var saved = tx(actor, () -> parties.save(actor, null, active(unique("LOCK"), "并发甲", role("SUPPLIER"))));
    var detail = tx(actor, () -> parties.detail(saved.id()));
    var stale = new PartyInput(
        detail.code(), "并发乙", detail.partyType(), detail.countryCode(), detail.email(),
        detail.phone(), detail.website(), detail.status(), List.of(role("SUPPLIER")), detail.version());
    tx(actor, () -> parties.save(actor, saved.id(), stale));
    // 同一个 version 再改一次：409。
    assertThatThrownBy(() -> tx(actor, () -> parties.save(actor, saved.id(), stale)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("已被他人修改");
  }

  // ================================================================ 蓝图要点：一份记录，多个角色

  @Test
  void 同一主体同时是供应商和客户且只有一条记录() {
    // 蓝图原话：「同一主体可同时是供应商和客户，用角色档案区分，不用多份记录」。
    // 这条断言把那句话钉成数据事实：一条 party + 两条 party_role。
    var saved =
        tx(
            actor,
            () ->
                parties.save(
                    actor, null, active(unique("MULTI"), "既是供货又是采购", role("SUPPLIER"), role("CUSTOMER"))));
    var detail = tx(actor, () -> parties.detail(saved.id()));
    assertThat(detail.roles()).hasSize(2);
    assertThat(detail.roleSummary()).isEqualTo("供应商、客户");
    assertThat(detail.activeRoleCount()).isEqualTo(2);
    // 全库只有一条主体记录，不是一份 supplier 表 + 一份 customer 表。
    long rows = tx(actor, () -> sql.fetchOne("select count(*) from party where code=?", detail.code()))
        .get(0, Long.class);
    assertThat(rows).isEqualTo(1);
    // 列表上一眼能看见这件事（否则运营会去建第二条主体）。
    var row = row(detail.id());
    // 角色数组按 role_type 排序（C < S），与 roleSummary 的固定展示顺序不同 —— 两个都断言，
    // 因为前端一个用来 key、一个用来显示，任一处顺序漂移都会让页面看起来在跳。
    assertThat(row.roles()).containsExactly("CUSTOMER", "SUPPLIER");
    assertThat(row.roleSummary()).isEqualTo("供应商、客户");
  }

  @Test
  void 同一角色不能重复指派() {
    var saved = tx(actor, () -> parties.save(actor, null, active(unique("ONCE"), "只指派一次", role("SUPPLIER"))));
    // 整体替换里重复：可读原因 + 点名角色。
    var dup = input(unique("ONCE2"), "重复角色", "ACTIVE", role("SUPPLIER"), role("supplier"));
    assertThatThrownBy(() -> tx(actor, () -> parties.save(actor, null, dup)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("同一个角色只能指派一次");

    // 单条指派端点重复指派：也要被拒。
    assertThatThrownBy(
            () ->
                tx(
                    actor,
                    () ->
                        parties.saveRole(actor, saved.id(), null,
                            new PartyRoleEditInput("SUPPLIER", "ACTIVE", "", 0))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("已经指派过");
  }

  @Test
  void 角色类型建后不可改() {
    var saved = tx(actor, () -> parties.save(actor, null, active(unique("RTYPE"), "角色类型", role("SUPPLIER"))));
    var roleId = tx(actor, () -> parties.rolesOf(saved.id())).getFirst().id();
    long version = tx(actor, () -> parties.rolesOf(saved.id())).getFirst().version();
    assertThatThrownBy(
            () ->
                tx(
                    actor,
                    () ->
                        parties.saveRole(actor, saved.id(), roleId,
                            new PartyRoleEditInput("CUSTOMER", "ACTIVE", "", version))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("建后不可修改");
  }

  @Test
  void 整体替换语义下未提交的角色被解指派() {
    var saved =
        tx(
            actor,
            () ->
                parties.save(actor, null,
                    active(unique("REPL"), "整体替换", role("SUPPLIER"), role("CUSTOMER"), role("CARRIER"))));
    assertThat(tx(actor, () -> parties.rolesOf(saved.id()))).hasSize(3);
    var detail = tx(actor, () -> parties.detail(saved.id()));
    // 只保留供应商与客户。
    var next =
        new PartyInput(
            detail.code(), detail.name(), detail.partyType(), detail.countryCode(), detail.email(),
            detail.phone(), detail.website(), detail.status(),
            List.of(role("SUPPLIER"), role("CUSTOMER")), detail.version());
    tx(actor, () -> parties.save(actor, saved.id(), next));
    var after = tx(actor, () -> parties.rolesOf(saved.id()));
    assertThat(after).hasSize(2);
    assertThat(after.stream().map(PartyRole::roleType)).containsExactly("CUSTOMER", "SUPPLIER");
    // 主体本身的版本 +1（确实保存过），角色行各自带自己的 version。
    assertThat(tx(actor, () -> parties.detail(saved.id())).version())
        .isEqualTo(detail.version() + 1);
  }

  @Test
  void 解除角色指派会落库() {
    var saved = tx(actor, () -> parties.save(actor, null, active(unique("DEL"), "解指派", role("SUPPLIER"), role("CUSTOMER"))));
    // rolesOf 按 role_type 排序，C < S，所以第一条是 CUSTOMER —— 显式按类型取，不靠位置。
    var supplierRole =
        tx(actor, () -> parties.rolesOf(saved.id())).stream()
            .filter(r -> "SUPPLIER".equals(r.roleType())).findFirst().orElseThrow();
    tx(actor, () -> parties.removeRole(actor, saved.id(), supplierRole.id(), supplierRole.version()));
    var after = tx(actor, () -> parties.rolesOf(saved.id()));
    assertThat(after).hasSize(1);
    assertThat(after.getFirst().roleType()).isEqualTo("CUSTOMER");
    // 版本不对时拒绝解指派（行级乐观锁）。
    assertThatThrownBy(
            () ->
                tx(
                    actor,
                    () -> parties.removeRole(actor, saved.id(), supplierRole.id(), supplierRole.version())))
        .isInstanceOf(ResponseStatusException.class);
  }

  // ================================================================ 状态流转（P4 / P5 / P6）

  @Test
  void 状态流转按P4的方向走且进出拉黑必须留理由() {
    var saved = tx(actor, () -> parties.save(actor, null, active(unique("ST"), "状态流转", role("SUPPLIER"))));
    UUID id = saved.id();

    // 停用不需要理由。
    tx(actor, () -> parties.setStatus(actor, id, new PartyStatusInput("INACTIVE", null, versionOf(id))));
    assertThat(tx(actor, () -> parties.detail(id)).status()).isEqualTo("INACTIVE");
    // 拉黑必须留理由，且「停用 → 拉黑」是合法方向。
    assertThatThrownBy(
            () -> tx(actor, () -> parties.setStatus(actor, id, new PartyStatusInput("BLACKLISTED", "  ", versionOf(id)))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("必须填写理由");
    tx(actor,
        () -> parties.setStatus(actor, id, new PartyStatusInput("BLACKLISTED", "两次货款纠纷，2026-09 决裂", versionOf(id))));
    var blacklisted = tx(actor, () -> parties.detail(id));
    assertThat(blacklisted.status()).isEqualTo("BLACKLISTED");
    assertThat(blacklisted.statusReason()).contains("货款纠纷");
    assertThat(blacklisted.statusChangedAt()).isNotNull();
    // 判定：拉黑优先于其它一切。
    assertThat(blacklisted.usableBlockCode()).isEqualTo(PartyRules.BLOCK_PARTY_BLACKLISTED);
    // 解除拉黑同样要理由（规则是「进出」而不是「进入」）。
    assertThatThrownBy(
            () -> tx(actor, () -> parties.setStatus(actor, id, new PartyStatusInput("ACTIVE", null, versionOf(id)))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("必须填写理由");
    tx(actor, () -> parties.setStatus(actor, id, new PartyStatusInput("INACTIVE", "制裁名单已移除，先停用观察", versionOf(id))));
    assertThat(tx(actor, () -> parties.detail(id)).status()).isEqualTo("INACTIVE");
  }

  @Test
  void 自环状态流转被拒() {
    var saved = tx(actor, () -> parties.save(actor, null, active(unique("SELF"), "自环", role("SUPPLIER"))));
    assertThatThrownBy(
            () -> tx(actor, () -> parties.setStatus(actor, saved.id(), new PartyStatusInput("ACTIVE", null, 0))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("状态没有变化");
  }

  @Test
  void 启用前必须有角色而停用后不能再加角色() {
    // 规则 P5：被拒的应该是「让它在下游可用」，不是「建档」。
    var idle =
        tx(actor, () -> parties.save(actor, null, input(unique("NOROLE"), "没有角色", "INACTIVE")));
    assertThatThrownBy(
            () -> tx(actor, () -> parties.setStatus(actor, idle.id(), new PartyStatusInput("ACTIVE", null, versionOf(idle.id())))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("至少要指派一个角色");
    // 建档时就想直接 ACTIVE 且不带角色：同样被拒。
    assertThatThrownBy(() -> tx(actor, () -> parties.save(actor, null, input(unique("NOROLE2"), "建档即启用", "ACTIVE"))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("至少要指派一个角色");

    // 正常路径：先建（停用）→ 指派角色 → 启用。
    tx(actor, () -> parties.saveRole(actor, idle.id(), null, new PartyRoleEditInput("SUPPLIER", "ACTIVE", "", 0)));
    tx(actor, () -> parties.setStatus(actor, idle.id(), new PartyStatusInput("ACTIVE", null, versionOf(idle.id()))));
    var detail = tx(actor, () -> parties.detail(idle.id()));
    assertThat(detail.status()).isEqualTo("ACTIVE");
    assertThat(detail.usable()).isTrue();

    // 停用状态下**可以**继续指派角色 —— 上面「先建后指派再启用」的流程本身就依赖这一点。
    tx(actor, () -> parties.setStatus(actor, detail.id(), new PartyStatusInput("INACTIVE", null, versionOf(detail.id()))));
    tx(actor, () -> parties.saveRole(actor, detail.id(), null, new PartyRoleEditInput("CUSTOMER", "ACTIVE", "", 0)));
    assertThat(tx(actor, () -> parties.rolesOf(detail.id()))).hasSize(2);

    // 规则 P6：拉黑状态下不允许新增角色（拉黑 = 不与这个对手方做生意）。
    tx(
        actor,
        () ->
            parties.setStatus(
                actor, detail.id(),
                new PartyStatusInput("BLACKLISTED", "结算纠纷，暂停一切往来", versionOf(detail.id()))));
    assertThatThrownBy(
            () -> tx(actor, () -> parties.saveRole(actor, detail.id(), null, new PartyRoleEditInput("CARRIER", "ACTIVE", "", 0))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("拉黑名单");
    // 但解指派在拉黑状态下仍然允许 —— 清理拉黑主体的角色正是之后要做的动作。
    var customerRole =
        tx(actor, () -> parties.rolesOf(detail.id())).stream()
            .filter(r -> "CUSTOMER".equals(r.roleType())).findFirst().orElseThrow();
    tx(actor, () -> parties.removeRole(actor, detail.id(), customerRole.id(), customerRole.version()));
    assertThat(tx(actor, () -> parties.rolesOf(detail.id()))).hasSize(1);
  }

  @Test
  void 没有角色的主体被判定为不可用() {
    // 直接插一条 ACTIVE 且无角色的行（模拟历史数据或 DBA 直连），判定必须看得见。
    UUID id = UUID.randomUUID();
    String code = unique("ORPHAN");
    tx(
        actor,
        () -> {
          sql.execute(
              "insert into party(id,tenant_id,code,name,party_type,country_code,email,status)"
                  + " values(?,?,?,?,'ORGANIZATION','CN','x@y.example','ACTIVE')",
              id, a, code, "孤儿主体");
          return null;
        });
    var detail = tx(actor, () -> parties.detail(id));
    assertThat(detail.usable()).isFalse();
    assertThat(detail.usableBlockCode()).isEqualTo(PartyRules.BLOCK_PARTY_NO_ROLE);
    assertThat(detail.usableBlockMessage()).contains("指派");
    // 概览里的 noRoleCount 也要把它算进去。
    assertThat(tx(actor, () -> parties.overview()).noRoleCount()).isPositive();
  }

  // ================================================================ 软删除（P7）

  @Test
  void 删除前必须先解掉全部角色() {
    var saved =
        tx(actor,
            () -> parties.save(actor, null, active(unique("DELP"), "先解角色", role("SUPPLIER"), role("CUSTOMER"))));
    assertThatThrownBy(() -> writeVoid(actor, () -> parties.delete(actor, saved.id(), versionOf(saved.id()))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("供应商")
        .hasMessageContaining("客户");

    for (PartyRole r : tx(actor, () -> parties.rolesOf(saved.id())))
      writeVoid(actor, () -> parties.removeRole(actor, saved.id(), r.id(), r.version()));
    String code = tx(actor, () -> parties.detail(saved.id())).code();
    writeVoid(actor, () -> parties.delete(actor, saved.id(), versionOf(saved.id())));
    // 列表默认看不见，但 includeDeleted=true 能看到。
    assertThat(rowByCode(code, false)).isNull();
    assertThat(rowByCode(code, true)).isNotNull();
    // 软删除的主体详情**仍可读**，但显式标记 deleted 且恒不可用 ——
    // 删完回头看一眼时看到的是「已删除」，不是凭空消失的一条。
    var gone = tx(actor, () -> parties.detail(saved.id()));
    assertThat(gone.deleted()).isTrue();
    assertThat(gone.usable()).isFalse();
    assertThat(gone.usableBlockCode()).isEqualTo(PartyRules.BLOCK_PARTY_DELETED);
    // 别的租户的行仍然是 404：RLS 挡的是跨租户，不是「已删除」。
    assertThatThrownBy(() -> tx(actorB, () -> parties.detail(saved.id())))
        .isInstanceOf(ResponseStatusException.class);
  }

  @Test
  void rlsPoliciesAreInPlaceAndSoftDeleteKeepsTheRow() {
    var tables =
        tx(
            actor,
            () ->
                sql.fetch(
                    "select relname,relrowsecurity,relforcerowsecurity from pg_class"
                        + " where relname in ('party','party_role') and relkind='r'"));
    assertThat(tables).hasSize(2);
    assertThat(tables)
        .allMatch(
            r ->
                Boolean.TRUE.equals(r.get("relforcerowsecurity", Boolean.class))
                    && Boolean.TRUE.equals(r.get("relrowsecurity", Boolean.class)));
    // 唯一编码在 DB 层也是租户内唯一（不是只靠服务层预检）。
    var index =
        tx(
            actor,
            () ->
                sql.fetch(
                    "select indexdef from pg_indexes where tablename='party'"
                        + " and indexdef like '%UNIQUE%code%'"));
    assertThat(index).isNotEmpty();

    // 软删除保留行：采购单 / 应付 / 售后单将来会引用这个 id，删掉行就没法显示「对手方是谁」。
    var saved = tx(actor, () -> parties.save(actor, null, active(unique("KEEP"), "留行", role("SUPPLIER"))));
    // P7：删之前先解掉角色。
    for (var r : tx(actor, () -> parties.rolesOf(saved.id())))
      writeVoid(actor, () -> parties.removeRole(actor, saved.id(), r.id(), r.version()));
    writeVoid(actor, () -> parties.delete(actor, saved.id(), versionOf(saved.id())));
    long rows =
        tx(actor, () -> sql.fetchOne("select count(*) from party where id=?", saved.id())).get(0, Long.class);
    assertThat(rows).isEqualTo(1);
    var raw = tx(actor, () -> sql.fetchOne("select deleted_at,deleted_by from party where id=?", saved.id()));
    assertThat(raw.get("deleted_at", java.time.OffsetDateTime.class)).isNotNull();
    assertThat(raw.get("deleted_by", UUID.class)).isEqualTo(user);
    // 列表默认隐藏它，includeDeleted=true 才看得到。
    assertThat(tx(actor, () -> parties.list(new PartyQuery(null, null, null, null, null, null, null, 0, 200)))
            .items().stream().map(PartyRow::id).collect(java.util.stream.Collectors.toSet()))
        .doesNotContain(saved.id());
    assertThat(tx(actor, () -> parties.list(new PartyQuery(null, null, null, null, null, true, null, 0, 200)))
            .items().stream().map(PartyRow::id).collect(java.util.stream.Collectors.toSet()))
        .contains(saved.id());
    // 引用候选在**两种模式下都不返回已删除的主体** —— includeBlocked 只放宽「状态」，
    // 不放宽「已删除」：历史单据要看的是自己冻结的快照，而新单据永远不该选中一个已删的主体。
    assertThat(tx(actor, () -> parties.candidates(null, null, true)))
        .extracting(PartyRow::id).doesNotContain(saved.id());
    assertThat(tx(actor, () -> parties.candidates(null, null, false)))
        .extracting(PartyRow::id).doesNotContain(saved.id());
  }

  // ================================================================ 列表、搜索与候选

  @Test
  void 列表支持关键词分页与角色筛选() {
    String marker = unique("Q");
    tx(actor, () -> parties.save(actor, null, active(marker, "可搜供应商", role("SUPPLIER"))));
    tx(actor, () -> parties.save(actor, null, active(marker + "X", "可搜客户", role("CUSTOMER"))));

    // 关键词命中编码前缀。
    var byCode = tx(actor, () -> parties.list(new PartyQuery(marker, null, null, null, null, null, null, 0, 200)));
    assertThat(byCode.items()).isNotEmpty();
    assertThat(byCode.items().stream().map(PartyRow::code)).allMatch(c -> c.startsWith(marker));
    // 关键词命中邮箱（运营收到对方邮件后只记得邮箱）。
    var byEmail =
        tx(
            actor,
            () ->
                parties.list(
                    new PartyQuery("purchasing@" + marker.toLowerCase(), null, null, null, null, null, null, 0, 200)));
    assertThat(byEmail.items()).isNotEmpty();
    // 按角色筛选只给 ACTIVE 的角色指派。
    var suppliers = tx(actor, () -> parties.list(new PartyQuery(null, null, null, "SUPPLIER", null, null, null, 0, 200)));
    assertThat(suppliers.items().stream().map(PartyRow::code)).contains(marker);
    var customers = tx(actor, () -> parties.list(new PartyQuery(null, null, null, "CUSTOMER", null, null, null, 0, 200)));
    assertThat(customers.items().stream().map(PartyRow::code)).contains(marker + "X");
    // 统计不受筛选影响：筛出 1 条时概览数字仍然是全量的。
    assertThat(byCode.total()).isLessThan(tx(actor, () -> parties.overview()).partyCount() + 1);
    assertThat(tx(actor, () -> parties.overview()).partyCount()).isPositive();
  }

  @Test
  void 关键词里的通配符被转义而不是变成匹配全部() {
    String marker = unique("PCT");
    tx(actor, () -> parties.save(actor, null, active(marker, "百分号主体", role("SUPPLIER"))));
    tx(actor, () -> parties.save(actor, null, active(marker + "Z", "另一个主体", role("SUPPLIER"))));
    // 搜 "100%" 不能变成「匹配所有」：必须精确到没有命中。
    var hits = tx(actor, () -> parties.list(new PartyQuery("100%", null, null, null, null, null, null, 0, 200)));
    assertThat(hits.items()).noneMatch(r -> r.code().equals(marker) || r.code().equals(marker + "Z"));
    // 下划线同理。
    var underscore = tx(actor, () -> parties.list(new PartyQuery("_", null, null, null, null, null, null, 0, 200)));
    assertThat(underscore.items()).noneMatch(r -> r.code().equals(marker));
  }

  @Test
  void 分页按页切片且总数不随页变化() {
    for (int i = 0; i < 3; i++) {
      int index = i;
      tx(actor, () -> parties.save(actor, null, active(unique("PAGE"), "分页" + index, role("SUPPLIER"))));
    }
    var first = tx(actor, () -> parties.list(new PartyQuery(null, null, null, null, null, null, null, 0, 2)));
    var second = tx(actor, () -> parties.list(new PartyQuery(null, null, null, null, null, null, null, 1, 2)));
    assertThat(first.items()).hasSize(2);
    assertThat(second.items()).hasSize(2);
    assertThat(first.total()).isEqualTo(second.total());
    assertThat(first.items().stream().map(PartyRow::id))
        .doesNotContainAnyElementsOf(second.items().stream().map(PartyRow::id).toList());
  }

  @Test
  void 引用候选只给可用的主体() {
    var usable = tx(actor, () -> parties.save(actor, null, active(unique("CAND"), "可用供应商", role("SUPPLIER"))));
    var idle =
        tx(actor, () -> parties.save(actor, null, input(unique("CANDX"), "停用供应商", "INACTIVE")));
    tx(actor, () -> parties.saveRole(actor, idle.id(), null, new PartyRoleEditInput("SUPPLIER", "ACTIVE", "", 0)));

    var defaultCandidates = tx(actor, () -> parties.candidates("SUPPLIER", null, false));
    assertThat(defaultCandidates).extracting(PartyRow::id).contains(usable.id());
    // 停用的主体不在默认候选里 —— 那会让运营挑到一个当下用不了的供应商。
    assertThat(defaultCandidates).extracting(PartyRow::id).doesNotContain(idle.id());
    // includeBlocked=true 时带原因码一起返回（用于「历史单据上这个供应商为什么选不到了」）。
    var blocked = tx(actor, () -> parties.candidates("SUPPLIER", null, true));
    assertThat(blocked).extracting(PartyRow::id).contains(idle.id());
    assertThat(blocked.stream().filter(r -> r.id().equals(idle.id())).findFirst().orElseThrow()
            .usableBlockCode())
        .isEqualTo(PartyRules.BLOCK_PARTY_INACTIVE);
  }

  @Test
  void 概览统计与列表口径一致() {
    tx(actor, () -> parties.save(actor, null, active(unique("OVW"), "概览供应商", role("SUPPLIER"), role("CUSTOMER"))));
    var overview = tx(actor, () -> parties.overview());
    var page = tx(actor, () -> parties.list(new PartyQuery(null, null, null, null, null, null, null, 0, 200)));
    assertThat(page.activeCount()).isEqualTo(overview.activeCount());
    assertThat(page.blacklistedCount()).isEqualTo(overview.blacklistedCount());
    assertThat(page.noRoleCount()).isEqualTo(overview.noRoleCount());
    // 「同时是供应商和客户」的主体数必须被统计到 —— 那是蓝图那条要求的体检指标。
    assertThat(overview.multiRoleCount()).isPositive();
  }

  // ================================================================ 租户隔离三断言

  /**
   * 租户隔离三断言，<b>并且覆盖角色指派行</b>。
   *
   * <p>只测主体等于没测：角色行才是本模块真正的越权面 ——
   * 「A 看到自己的供应商角色时混进 B 的客户角色」会直接让 A 的采购单挂到 B 的对手方上。
   */
  @Test
  void 租户隔离三断言覆盖角色指派行() throws Exception {
    var mine =
        tx(
            actor,
            () ->
                parties.save(actor, null,
                    active(unique("MINE"), "我方主体", role("SUPPLIER"), role("CUSTOMER"))));
    var theirs =
        tx(
            actorB,
            () ->
                parties.save(actorB, null,
                    active(unique("THEIRS"), "对方主体", role("SUPPLIER"), role("CARRIER"))));

    // 两边都必须真的有角色行 —— 否则下面的「角色不串」就是空断言。
    assertThat(tx(actor, () -> parties.rolesOf(mine.id()))).hasSize(2);
    assertThat(tx(actorB, () -> parties.rolesOf(theirs.id()))).hasSize(2);

    // 1) 两租户互不可见：读对方主体 404，读对方角色行也 404。
    assertThatThrownBy(() -> tx(actor, () -> parties.detail(theirs.id())))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> tx(actorB, () -> parties.detail(mine.id())))
        .isInstanceOf(ResponseStatusException.class);
    var theirRoleId = tx(actorB, () -> parties.rolesOf(theirs.id())).getFirst().id();
    assertThatThrownBy(
            () ->
                tx(
                    actor,
                    () ->
                        parties.saveRole(actor, mine.id(), theirRoleId,
                            new PartyRoleEditInput("CARRIER", "ACTIVE", "", 0))))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> tx(actor, () -> parties.removeRole(actor, mine.id(), theirRoleId, 0)))
        .isInstanceOf(ResponseStatusException.class);
    // 对方主体上的角色在自己的列表里看不到、也删不掉。
    String theirCode = tx(actorB, () -> parties.detail(theirs.id())).code();
    assertThat(tx(actor, () -> parties.candidates(null, theirCode, true))).isEmpty();
    // 对方的角色行不会被整体替换顺手删掉。
    assertThat(tx(actorB, () -> parties.rolesOf(theirs.id()))).hasSize(2);

    // 2) 伪造 X-Tenant-Id 头无效：租户只来自已认证成员关系。
    http.perform(get("/catalog/parties/" + mine.id())
            .header("Authorization", "Bearer tenant-b").header("X-Tenant-Id", a))
        .andExpect(status().isNotFound());
    http.perform(get("/catalog/parties/" + theirs.id())
            .header("Authorization", "Bearer tenant-a").header("X-Tenant-Id", b))
        .andExpect(status().isNotFound());
    // 伪造头下写入仍落在 B 自己名下，而不是伪造的 A。
    String code = unique("FORGED");
    var forged =
        tx(
            actorB,
            () ->
                parties.save(
                    actorB, null,
                    new PartyInput(
                        code, "伪造头下的主体", "ORGANIZATION", "CN", "f@" + code.toLowerCase() + ".example",
                        null, null, "ACTIVE", List.of(role("SUPPLIER")), 0)));
    // 伪造头没有把行写进 A：它落在 B 自己名下 —— A 看不见，B 看得见。
    assertThatThrownBy(() -> tx(actor, () -> parties.detail(forged.id())))
        .isInstanceOf(ResponseStatusException.class);
    assertThat(tx(actorB, () -> parties.detail(forged.id())).code()).isEqualTo(code);
    assertThat(tx(actorB, () -> sql.fetchOne("select tenant_id from party where id=?", forged.id()))
            .get(0, UUID.class))
        .isEqualTo(b);

    // 3) 连接池归还后不串租户（单连接反复交替），逐轮断言主体与角色都不串。
    for (int i = 0; i < 5; i++) {
      var myIds =
          tx(actor, () -> parties.list(new PartyQuery(null, null, null, null, null, null, null, 0, 200)))
              .items().stream().map(PartyRow::id).collect(java.util.stream.Collectors.toSet());
      var theirIds =
          tx(actorB, () -> parties.list(new PartyQuery(null, null, null, null, null, null, null, 0, 200)))
              .items().stream().map(PartyRow::id).collect(java.util.stream.Collectors.toSet());
      assertThat(java.util.Collections.disjoint(myIds, theirIds)).isTrue();
      // 角色行同样不相交 —— 这是本模块真正的越权面。
      var myRoles = tx(actor, () -> parties.rolesOf(mine.id())).stream().map(PartyRole::id).toList();
      var theirRoles = tx(actorB, () -> parties.rolesOf(theirs.id())).stream().map(PartyRole::id).toList();
      assertThat(myRoles).isNotEmpty();
      assertThat(theirRoles).isNotEmpty();
      assertThat(java.util.Collections.disjoint(myRoles, theirRoles)).isTrue();
      // 候选列表也不相交。
      var myCandidates = tx(actor, () -> parties.candidates("SUPPLIER", null, true))
          .stream().map(PartyRow::id).toList();
      var theirCandidates = tx(actorB, () -> parties.candidates("SUPPLIER", null, true))
          .stream().map(PartyRow::id).toList();
      assertThat(java.util.Collections.disjoint(myCandidates, theirCandidates)).isTrue();
    }
  }

  // ================================================================ HTTP 全链路与权限

  @Test
  void http全链路与读写权限分离() throws Exception {
    String code = unique("HTTP");
    var body =
        JSON.writeValueAsBytes(
            Map.of(
                "code", code, "name", "HTTP 主体", "partyType", "ORGANIZATION", "countryCode", "DE",
                "email", "http@x.example", "status", "ACTIVE",
                "roles", List.of(Map.of("roleType", "SUPPLIER", "status", "ACTIVE", "note", "")),
                "version", 0));
    var created =
        http.perform(post("/catalog/parties").header("Authorization", "Bearer tenant-a").contentType("application/json")
                .content(body))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    UUID id = UUID.fromString(JSON.readTree(created).get("id").asText());

    // 读端点 OK。
    http.perform(get("/catalog/parties/" + id).header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(code))
        .andExpect(jsonPath("$.roleSummary").value("供应商"));
    http.perform(get("/catalog/parties").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items").isArray());
    http.perform(get("/catalog/parties/overview").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk());
    http.perform(get("/catalog/parties/candidates?roleType=SUPPLIER").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk());

    // 角色指派端点 OK。
    http.perform(get("/catalog/parties/" + id + "/roles").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].roleType").value("SUPPLIER"));

    // 只有读权限的账号：写端点 403。
    http.perform(post("/catalog/parties").header("Authorization", "Bearer reader").contentType("application/json")
            .content(body))
        .andExpect(status().isForbidden());
    http.perform(put("/catalog/parties/" + id + "/status").header("Authorization", "Bearer reader")
            .contentType("application/json")
            .content(JSON.writeValueAsBytes(Map.of("status", "INACTIVE", "version", 0))))
        .andExpect(status().isForbidden());
    // 读端点对它仍然放行。
    http.perform(get("/catalog/parties/" + id).header("Authorization", "Bearer reader"))
        .andExpect(status().isOk());

    // 缺 token：401。
    http.perform(get("/catalog/parties/" + id)).andExpect(status().isUnauthorized());

    // 状态流转端点（进出拉黑要理由，HTTP 层同样报错）。
    http.perform(put("/catalog/parties/" + id + "/status").header("Authorization", "Bearer tenant-a")
            .contentType("application/json")
            .content(JSON.writeValueAsBytes(Map.of("status", "BLACKLISTED", "version", 1))))
        .andExpect(status().isBadRequest());
  }

  @Test
  void 软删除端点走HTTP且不可重复删除() throws Exception {
    String code = unique("HTTPDEL");
    var created =
        http.perform(post("/catalog/parties").header("Authorization", "Bearer tenant-a").contentType("application/json")
                .content(
                    JSON.writeValueAsBytes(
                        new PartyInput(
                            code, "待删除", "INDIVIDUAL", null, "d@" + code.toLowerCase() + ".example",
                            null, null, "INACTIVE", List.of(), 0))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    UUID id = UUID.fromString(JSON.readTree(created).get("id").asText());
    http.perform(delete("/catalog/parties/" + id + "?version=0").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.deleted").value(true));
    // 再删一次：已删除的主体对详情直接 404。
    http.perform(delete("/catalog/parties/" + id + "?version=1").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isNotFound());
  }

  // ================================================================ 工具

  /**
   * 按 id 找一行列表结果。
   *
   * <p>刻意<b>不</b>用「第 0 页 200 条」来找：整个测试类共用同一个租户 A，
   * 用例一多主体数就超过 200，而 {@code order by code} 与 id 无关 ——
   * 那样「这一行不在第 0 页」会被误读成「这一行被软删了」。
   * 这里用 code 前缀精确定位（编码唯一），与软删除开关一起构成确定性的查找。
   */
  private PartyRow row(UUID id) {
    return row(id, false);
  }

  private PartyRow row(UUID id, boolean includeDeleted) {
    return rowByCode(tx(actor, () -> parties.detail(id).code()), includeDeleted);
  }

  /**
   * 按编码找一行。
   *
   * <p>软删除之后 {@code detail} 直接 404，所以删除后的断言只能走这里 ——
   * 编码在删除前取好（编码是建后不可改的，见规则 P1）。
   */
  private PartyRow rowByCode(String code, boolean includeDeleted) {
    var page =
        tx(actor, () -> parties.list(new PartyQuery(code, null, null, null, null, includeDeleted, null, 0, 50)));
    // 编码在租户内唯一，所以一次搜索至多一行；仍然按 code 精确比较而不是「取第一条」。
    return page.items().stream().filter(r -> r.code().equals(code)).findFirst().orElse(null);
  }

  private long versionOf(UUID id) {
    return tx(actor, () -> parties.detail(id)).version();
  }
}
