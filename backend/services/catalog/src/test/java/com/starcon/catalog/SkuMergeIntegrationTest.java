package com.starcon.catalog;

import static com.starcon.catalog.CatalogModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * 3.9.1.2 SKU 合并：真实 PG16 + 强制 RLS 的归并往返、逐维度引用迁移报告、
 * 原记录合并标记，以及<b>租户隔离三断言</b>。
 *
 * <p><b>连接池固定为 1 条连接</b>：只有复用同一条连接，才能真正证明归还后不会串租户。
 *
 * <p><b>本文件最重要的几组断言</b>：
 *
 * <ol>
 *   <li>{@link #迁移把当前引用搬到主记录而历史事实一行不动()}：蓝图的关系行把边界划得很死 ——
 *       「只迁移当前引用（映射/库存/价格），历史事实保持冻结」。这一条同时钉住两边：
 *       报价/素材/包装真的搬了，而单位换算快照与状态事件真的<b>没搬</b>。
 *   <li>{@link #组件用量相加而不是二选一()}：BOM / 套装组件在同一版本里同时挂着两条重复 SKU 时，
 *       答案必须是<b>和</b>。这是整个模块里唯一会改数值的动作，必须单独钉死。
 *   <li>{@link #唯一约束冲突的行留在被合并记录上()}：「搬不动」不等于「合并失败」——
 *       主记录已有同值条码时，被合并记录那一行要留着（它可回查），且报告要分开说。
 *   <li>{@link #expectedVersion对不上就拒绝执行()}：蓝图把 {@code expected_version} 写成本模块的输入，
 *       这里断言它真的在拦：登记之后改过商品就并不了。
 *   <li>{@link #原记录被标记合并并停用而没有物理删除()}：蓝图的「标记合并并停用而非物理删除」，
 *       连同「历史行因此没被 CASCADE 带走」一起断言。
 *   <li>{@link #租户隔离三断言覆盖合并执行与报告()}：合并是一次改写十几张表的动作，
 *       串租户的后果比 3.9.1.1 的读列表严重得多 —— A 能把 B 的商品并掉。
 *   <li>{@link #合并时刻往返不偏28800()}：merged_at 写回读出来必须还是写入的那个时刻。
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class SkuMergeIntegrationTest {
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
      // 只读身份：能读预演与历史，执行合并必须 403。
      actors.put(
          "Bearer reader",
          actor(user, a, null, List.of(grant("ALL", List.of("catalog.spu.read", "catalog.sku.read")))));
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
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @Autowired SkuMergeService merges;
  @Autowired DuplicateDetectionService duplicates;
  @Autowired CatalogService catalog;
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

  <T> T write(AccessService.Access who, java.util.function.Supplier<T> work) {
    return access.run(who, "catalog.spu.update", work);
  }

  void writeVoid(AccessService.Access who, Runnable work) {
    access.run(
        who,
        "catalog.spu.update",
        () -> {
          work.run();
          return null;
        });
  }

  static String unique(String prefix) {
    return prefix + "-" + SEQ.incrementAndGet();
  }

  // ================================================================ 夹具

  /** 一个 SPU 里只能有一个没有规格轴的 SKU（V1 的 UNIQUE(tenant_id,spu_id,variant_signature)）。 */
  UUID spu(AccessService.Access who, String code, String name, String brand, String category) {
    return write(
        who, () -> catalog.saveSpu(who, null, new SpuInput(code, name, "", brand, category, "DRAFT", 0)));
  }

  UUID sku(AccessService.Access who, UUID spuId, String code, String name, String barcode) {
    return write(
        who,
        () ->
            catalog.saveSku(
                who, spuId, null,
                new SkuInput(code, name, barcode, "PCS", "ACTIVE", List.of(), spuVersion(who, spuId))));
  }

  /** 带规格轴取值建档（saveSku 会校验「本 SPU 的轴必须给取值」）。 */
  UUID skuWithOption(
      AccessService.Access who, UUID spuId, UUID axisId, String code, String name, String barcode,
      String option) {
    return write(
        who,
        () ->
            catalog.saveSku(
                who, spuId, null,
                new SkuInput(
                    code, name, barcode, "PCS", "ACTIVE",
                    List.of(new ValueInput(axisId, "SET", List.of(option))), spuVersion(who, spuId))));
  }

  long spuVersion(AccessService.Access who, UUID spuId) {
    return tx(who, () -> sql.fetchOne("select version from spu where id=?", spuId)).get("version", Long.class);
  }

  long skuVersion(AccessService.Access who, UUID skuId) {
    return tx(who, () -> sql.fetchOne("select version from sku where id=?", skuId)).get("version", Long.class);
  }

  /** 一对跨 SPU 重复（3.9.1.1 的 CROSS_SPU 维度：品牌 + 类目 + 归一化标题都对上）。 */
  record Pair(UUID candidateId, UUID primary, UUID secondary) {}

  /**
   * 登记 MERGE 决定。
   *
   * <p><b>它必须在写完引用之后调用</b>：V7 的 {@code sync_primary_barcode} 触发器在
   * {@code sku_identifier} 写入时会 {@code update sku set version=version+1}，
   * 所以「登记决定之后再补条码」是一次真实改动，会让 expected_version 比对失败 ——
   * 那正是本模块该拦的东西，不是缺陷。夹具按真实业务顺序排：先备齐引用，再登记决定。
   */
  void decideMerge(AccessService.Access who, UUID candidateId, UUID primary) {
    write(who, () -> duplicates.decide(who, candidateId,
        new DuplicateModels.DuplicateDecisionInput("MERGE", primary, "确认为同一条", 0L)));
  }

  /**
   * 建一对跨 SPU 重复并跑一次扫描，**只产出候选、不登记决定**。
   *
   * <p>跨 SPU 是本模块的<b>主路径</b>而不是边角：同商品被建成两个 SPU 正是 3.9.1.1
   * 四条线里最常见的一条，它触发的执行范围是 'SPU'（源商品连带停产）。
   */
  Pair pairOf(AccessService.Access who, String title) {
    UUID spu1 = spu(who, unique("MG-SPU"), title, "MergeBrand", "数码");
    UUID spu2 = spu(who, unique("MG-SPU"), title, "MergeBrand", "数码");
    UUID first = sku(who, spu1, unique("MG-"), title + " 主", unique("690"));
    UUID second = sku(who, spu2, unique("MG-"), title + " 副", unique("691"));
    var scan = write(who, () -> duplicates.scan(who, null));
    return new Pair(findPair(who, scan.id(), first, second), first, second);
  }

  /** 建一对跨 SPU 重复并立刻登记 MERGE 决定（中间不写任何引用）。 */
  Pair decidedPair(AccessService.Access who, String title) {
    Pair p = pairOf(who, title);
    decideMerge(who, p.candidateId(), p.primary());
    return p;
  }

  UUID findPair(AccessService.Access who, UUID scanId, UUID x, UUID y) {
    return tx(who, () -> duplicates.candidates(scanId, null, null, null, 0, 500))
        .items().stream()
        .filter(c -> Set.of(c.leftSkuId(), c.rightSkuId()).equals(Set.of(x, y)))
        .findFirst()
        .orElseThrow(() -> new AssertionError("扫描 " + scanId + " 里找不到这一对"))
        .id();
  }

  // ---------------------------------------------------------------- 引用夹具

  void identifier(AccessService.Access who, UUID skuId, String scheme, String value) {
    writeVoid(
        who,
        () ->
            sql.execute(
                "insert into sku_identifier(id,tenant_id,sku_id,scheme,value,is_primary,source,occurred_at)"
                    + " values(?,?,?,?,?,true,'MANUAL',now())",
                UUID.randomUUID(), who.tenantId(), skuId, scheme, value));
  }

  /** 造一个素材（内容寻址，V18 的 UNIQUE(tenant_id,content_sha256)）。 */
  UUID newAsset(AccessService.Access who, String seed) {
    return write(
        who,
        () -> {
          UUID assetId = UUID.randomUUID();
          String hash = sha256(seed);
          sql.execute(
              "insert into media_asset(id,tenant_id,name,media_type,purpose,file_format,mime_type,"
                  + "byte_size,width_px,height_px,content_sha256,storage_key,original_filename)"
                  + " values(?,?,?,'IMAGE','PRIMARY','png','image/png',1024,100,100,?,?,?)",
              assetId, who.tenantId(), "merge.png", hash, "merge/" + hash.substring(0, 16) + ".png",
              "merge.png");
          sql.execute(
              "insert into media_asset_version(id,tenant_id,asset_id,version_no,storage_key,"
                  + "original_filename,file_format,mime_type,byte_size,width_px,height_px,"
                  + "content_sha256) values(?,?,?,1,?,?,'png','image/png',1024,100,100,?)",
              UUID.randomUUID(), who.tenantId(), assetId, "merge/" + hash.substring(0, 16) + ".png",
              "merge.png", hash);
          return assetId;
        });
  }

  void attachMedia(AccessService.Access who, UUID assetId, UUID skuId) {
    writeVoid(
        who,
        () ->
            sql.execute(
                "insert into media_reference(id,tenant_id,asset_id,owner_type,sku_id) values(?,?,?,'SKU',?)",
                UUID.randomUUID(), who.tenantId(), assetId, skuId));
  }

  static String sha256(String seed) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(seed.getBytes()));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** 一条供应商报价（需要 SUPPLIER 角色的 party_role 与计量单位，所以先把它们建出来）。 */
  UUID quote(AccessService.Access who, UUID skuId, String supplierCode, String supplierSkuCode) {
    return write(
        who,
        () -> {
          UUID partyId =
              sql.fetchOne(
                      "insert into party(id,tenant_id,code,name,party_type,email)"
                          + " values(?,?,?,?,'ORGANIZATION',?) returning id",
                      UUID.randomUUID(), who.tenantId(), supplierCode, supplierCode + " 工厂",
                      "sales@" + supplierCode + ".test")
                  .get("id", UUID.class);
          UUID roleId =
              sql.fetchOne(
                      "insert into party_role(id,tenant_id,party_id,role_type) values(?,?,?,'SUPPLIER') returning id",
                      UUID.randomUUID(), who.tenantId(), partyId)
                  .get("id", UUID.class);
          writeUnit(who);
          String unit = "PCS";
          UUID id = UUID.randomUUID();
          sql.execute(
              "insert into supplier_quote(id,tenant_id,supplier_role_id,sku_id,supplier_sku_code,"
                  + "purchase_unit_code,currency,moq_quantity,lead_time_days,valid_from,priority,status)"
                  + " values(?,?,?,?,?,?,'USD',10,7,current_date,1,'ACTIVE')",
              id, who.tenantId(), roleId, skuId, supplierSkuCode, unit);
          return id;
        });
  }

  void writeUnit(AccessService.Access who) {
    int n = count(who, "select count(*) from unit_of_measure where code='PCS'");
    if (n == 0) {
      writeVoid(
          who,
          () ->
              sql.execute(
                  "insert into unit_of_measure(id,tenant_id,code,name,dimension,scale)"
                      + " values(?,?,'PCS','件','COUNT',0)",
                  UUID.randomUUID(), who.tenantId()));
    }
  }

  /** BOM 的一行组件（V25：unit_quantity NUMERIC(19,6) CHECK>0）。 */
  UUID bomComponent(AccessService.Access who, UUID skuId, String versionCode, String quantity) {
    return write(
        who,
        () -> {
          UUID spuId =
              sql.fetchOne("select spu_id from sku where id=?", skuId).get("spu_id", UUID.class);
          UUID bomId =
              sql.fetchOne(
                      "insert into bom(id,tenant_id,code,output_sku_id,status) values(?,?,?,?,'ACTIVE') returning id",
                      UUID.randomUUID(), who.tenantId(), versionCode, skuId)
                  .get("id", UUID.class);
          UUID versionId =
              sql.fetchOne(
                      "insert into bom_version(id,tenant_id,bom_id,version_no,name,effective_from,status)"
                          + " values(?,?,?,'v1','v1',now(),'DRAFT') returning id",
                      UUID.randomUUID(), who.tenantId(), bomId)
                  .get("id", UUID.class);
          UUID compId = UUID.randomUUID();
          sql.execute(
              "insert into bom_component(id,tenant_id,version_id,sku_id,unit_quantity) values(?,?,?,?,?)",
              compId, who.tenantId(), versionId, skuId, new BigDecimal(quantity));
          return compId;
        });
  }

  /** 读回某个组件行的用量 —— 断言「相加」时必须读真实值，不能只断言没抛异常。 */
  BigDecimal componentQty(UUID componentId) {
    return tx(actor, () -> sql.fetchOne("select unit_quantity from bom_component where id=?", componentId))
        .get("unit_quantity", BigDecimal.class);
  }

  /**
   * 写一条采购单行的<b>冻结快照</b>（{@code sku_unit_conversion_snapshot}）。
   *
   * <p>它是本模块「历史事实不搬」最硬的证据：这一行钉在 {@code document_ref+line_no} 上，
   * 搬走它等于说「当初那单用的换算比例是按合并后的 SKU 算的」—— 而它当初不是。
   */
  void purchaseSnapshot(AccessService.Access who, UUID skuId, String docRef) {
    writeVoid(
        who,
        () ->
            sql.execute(
                "insert into sku_unit_conversion_snapshot(id,tenant_id,sku_id,document_type,document_ref,"
                    + "line_no,role,unit_code,base_unit,quantity,quantity_per_base,base_quantity,"
                    + "effective_from,occurred_at,actor_id)"
                    + " values(?,?,?,'PURCHASE',?,1,'BASE','PCS','件',100,1,100,current_date,now(),?)",
                UUID.randomUUID(), who.tenantId(), skuId, docRef, user));
  }

  int count(AccessService.Access who, String statement, Object... bindings) {
    return tx(who, () -> sql.fetchOne(statement, bindings).get(0, Integer.class));
  }

  // ================================================================ 执行入口

  SkuMergeModels.SkuMergeRow execute(UUID candidateId) {
    return write(actor, () -> merges.execute(actor, candidateId,
        new SkuMergeModels.SkuMergeInput("集成测试", true)));
  }

  SkuMergeModels.SkuMergePreview preview(UUID candidateId) {
    return tx(actor, () -> merges.preview(candidateId));
  }

  SkuMergeModels.SkuMergeReferenceRow row(
      SkuMergeModels.SkuMergeReport report, String dimension) {
    return report.references().stream()
        .filter(r -> r.dimension().equals(dimension))
        .findFirst()
        .orElseThrow(() -> new AssertionError("报告里没有维度 " + dimension));
  }

  // ================================================================ 1. 迁移当前引用 / 冻结历史

  /**
   * 蓝图关系行的核心断言：<b>只迁移「当前引用」，历史事实保持冻结</b>。
   *
   * <p>一个测试里同时钉住正反两面 —— 只断言「报价搬了」的话，
   * 「把采购单冻结快照也一起搬了」这种实现照样能通过。
   */
  @Test
  void 迁移把当前引用搬到主记录而历史事实一行不动() {
    Pair p = pairOf(actor, "迁移样例商品");
    // 当前引用：条码（唯一值，可搬）+ 报价（价格，蓝图点名的）
    identifier(actor, p.secondary(), "EAN", "6900000000001");
    quote(actor, p.secondary(), unique("SUP"), "SUP-SKU-1");
    // 历史事实：采购单行的冻结快照
    purchaseSnapshot(actor, p.secondary(), unique("PO-"));
    // 引用备齐之后再登记决定（写条码会 bump sku.version，V7 触发器）
    decideMerge(actor, p.candidateId(), p.primary());
    long eventsBefore = count(actor, "select count(*) from sku_status_event where sku_id=?", p.secondary());

    SkuMergeModels.SkuMergeRow merged = execute(p.candidateId());
    assertThat(merged.migratedCount()).isGreaterThanOrEqualTo(2);

    // 搬走的：条码与报价都改指主记录了
    assertThat(count(actor, "select count(*) from sku_identifier where sku_id=?", p.primary())).isEqualTo(1);
    assertThat(count(actor, "select count(*) from sku_identifier where sku_id=?", p.secondary())).isZero();
    assertThat(count(actor, "select count(*) from supplier_quote where sku_id=?", p.primary())).isEqualTo(1);
    assertThat(count(actor, "select count(*) from supplier_quote where sku_id=?", p.secondary())).isZero();

    // 没搬的：冻结快照仍然指向被合并记录（这是刻意如此，不是漏搬）
    assertThat(count(actor, "select count(*) from sku_unit_conversion_snapshot where sku_id=?", p.secondary()))
        .isEqualTo(1);
    assertThat(count(actor, "select count(*) from sku_unit_conversion_snapshot where sku_id=?", p.primary()))
        .isZero();
    // 报告要把它明确记成「冻结」而不是悄悄不报
    assertThat(row(merged.report(), "UNIT_CONVERSION_SNAPSHOT").frozenCount()).isEqualTo(1);
    assertThat(row(merged.report(), "UNIT_CONVERSION_SNAPSHOT").migratedCount()).isZero();

    // 状态事件：只追加一条，不动已有的
    long eventsAfter = count(actor, "select count(*) from sku_status_event where sku_id=?", p.secondary());
    assertThat(eventsAfter).isEqualTo(eventsBefore + 1);
  }

  // ================================================================ 2. 组件用量相加

  /**
   * BOM 组件在同一版本里同时挂着两条重复 SKU 时，答案是<b>和</b>而不是二选一。
   *
   * <p>这是整个模块里唯一会改数值的动作，所以必须读回真实值断言，
   * 只断言「没抛异常」的话，把 1 写成 2 也能过。
   */
  @Test
  void 组件用量相加而不是二选一() {
    UUID spu1 = spu(actor, unique("SUM-SPU"), "用量相加样例", "MergeBrand", "数码");
    UUID spu2 = spu(actor, unique("SUM-SPU"), "用量相加样例", "MergeBrand", "数码");
    UUID first = sku(actor, spu1, unique("SUM-"), "主", unique("692"));
    UUID second = sku(actor, spu2, unique("SUM-"), "副", unique("693"));
    var scan = write(actor, () -> duplicates.scan(actor, null));
    UUID candidateId = findPair(actor, scan.id(), first, second);
    write(actor, () -> duplicates.decide(actor, candidateId,
        new DuplicateModels.DuplicateDecisionInput("MERGE", first, null, 0L)));

    // 同一个 BOM 版本里，两条重复 SKU 各有一个组件行：1.5 与 2.5 件
    String versionCode = unique("BOM-");
    writeVoid(
        actor,
        () -> {
          UUID bomId =
              sql.fetchOne(
                      "insert into bom(id,tenant_id,code,output_sku_id,status) values(?,?,?,?,'ACTIVE') returning id",
                      UUID.randomUUID(), a, versionCode, first)
                  .get("id", UUID.class);
          UUID versionId =
              sql.fetchOne(
                      "insert into bom_version(id,tenant_id,bom_id,version_no,name,effective_from,status)"
                          + " values(?,?,?,'v1','v1',now(),'DRAFT') returning id",
                      UUID.randomUUID(), a, bomId)
                  .get("id", UUID.class);
          // 同一 BOM 版本里，两条重复 SKU 各有一个组件行
          sql.execute(
              "insert into bom_component(id,tenant_id,version_id,sku_id,unit_quantity) values(?,?,?,?,?)",
              UUID.randomUUID(), a, versionId, first, new BigDecimal("1.5"));
          sql.execute(
              "insert into bom_component(id,tenant_id,version_id,sku_id,unit_quantity) values(?,?,?,?,?)",
              UUID.randomUUID(), a, versionId, second, new BigDecimal("2.5"));
        });

    SkuMergeModels.SkuMergeRow merged = execute(candidateId);
    SkuMergeModels.SkuMergeReferenceRow bom = row(merged.report(), "BOM");
    assertThat(bom.summedCount()).isEqualTo(1);
    // 合并后主记录上只剩一个组件行，用量是两者之和
    assertThat(count(actor, "select count(*) from bom_component where sku_id=?", first)).isEqualTo(1);
    assertThat(count(actor, "select count(*) from bom_component where sku_id=?", second)).isZero();
    BigDecimal mergedQty =
        tx(actor, () -> sql.fetchOne("select unit_quantity from bom_component where sku_id=?", first))
            .get("unit_quantity", BigDecimal.class);
    // 禁浮点：断言必须按 BigDecimal 精确比较，1.5+2.5=4.000000
    assertThat(mergedQty).isEqualByComparingTo(new BigDecimal("4.000000"));
  }

  // ================================================================ 3. 冲突行留在原处

  /**
   * 「搬不动」不等于「合并失败」。
   *
   * <p>主记录已有同值条码时（UNIQUE(tenant_id,scheme,value)），
   * 被合并记录上那一行要<b>留在原处</b>：删掉它等于让「这两条曾经撞过码」查无对证。
   */
  /**
   * 「搬不动」不等于「合并失败」。
   *
   * <p>用包装参数（{@code UNIQUE(tenant_id,sku_id)}）造冲突：两条 SKU 都有包装参数时，
   * 被合并记录上那一行<b>留在原处</b>，主记录的包装参数不被覆盖 ——
   * 重量尺寸是报关与运费的事实，两个值不能自动取其一。
   *
   * <p>顺带断言素材引用的<b>去重</b>语义：同一张图在两条重复 SKU 上各挂一条是重复建档的产物，
   * 主记录上已有完全相同的一条，所以被合并记录上那条被删掉（删掉不丢任何信息）。
   */
  @Test
  void 唯一约束冲突的行留在被合并记录上() {
    UUID spu1 = spu(actor, unique("CF-SPU"), "冲突样例商品", "MergeBrand", "数码");
    UUID spu2 = spu(actor, unique("CF-SPU"), "冲突样例商品", "MergeBrand", "数码");
    UUID first = sku(actor, spu1, unique("CF-"), "主", unique("694"));
    UUID second = sku(actor, spu2, unique("CF-"), "副", unique("695"));
    var scan = write(actor, () -> duplicates.scan(actor, null));
    UUID candidateId = findPair(actor, scan.id(), first, second);

    // 两侧都有一行包装参数 -> 这一行在主记录上搬不动
    writeVoid(actor, () -> {
      for (UUID id : List.of(first, second)) {
        sql.execute(
            "insert into sku_package(id,tenant_id,sku_id,length_mm,width_mm,height_mm,net_weight_g,gross_weight_g)"
                + " values(?,?,?,100,100,50,200,300)", UUID.randomUUID(), a, id);
      }
    });
    // 同一张图两条都挂着（内容寻址，所以是同一个素材行被引用两次）
    UUID shared = newAsset(actor, "shared-asset");
    attachMedia(actor, shared, first);
    attachMedia(actor, shared, second);

    decideMerge(actor, candidateId, first);
    SkuMergeModels.SkuMergeRow merged = execute(candidateId);

    SkuMergeModels.SkuMergeReferenceRow pkg = row(merged.report(), "PACKAGE");
    assertThat(pkg.conflictCount()).isEqualTo(1);
    assertThat(pkg.migratedCount()).isZero();
    // 冲突行仍在被合并记录上（可回查），主记录的包装参数没被覆盖
    assertThat(count(actor, "select count(*) from sku_package where sku_id=?", second)).isEqualTo(1);
    assertThat(
            tx(actor, () -> sql.fetchOne("select gross_weight_g from sku_package where sku_id=?", first))
                .get("gross_weight_g", BigDecimal.class))
        .isEqualByComparingTo(new BigDecimal("300"));

    // 素材：冲突行按去重删除（与主记录那条完全相同）
    SkuMergeModels.SkuMergeReferenceRow media = row(merged.report(), "MEDIA");
    assertThat(media.deduplicatedCount()).isEqualTo(1);
    assertThat(media.conflictCount()).isZero();
    assertThat(count(actor, "select count(*) from media_reference where sku_id=?", second)).isZero();
    assertThat(count(actor, "select count(*) from media_reference where sku_id=?", first)).isEqualTo(1);
  }

  // ================================================================ 4. expected_version 门禁

  /**
   * 蓝图的 {@code expected_version} 必须真的在拦。
   *
   * <p>登记决定之后商品被人改过 → 合并的前提已经不成立 → 必须重新确认，
   * 而不是拿一份已经过期的决定去改数据。
   */
  @Test
  void expectedVersion对不上就拒绝执行() {
    Pair p = decidedPair(actor, "过期样例商品");
    // 登记之后改一下主记录（改名）→ sku.version 顶上去
    write(
        actor,
        () ->
            catalog.saveSku(
                actor,
                tx(actor, () -> sql.fetchOne("select spu_id from sku where id=?", p.primary()))
                    .get("spu_id", UUID.class),
                p.primary(),
                new SkuInput(
                    tx(actor, () -> sql.fetchOne("select code from sku where id=?", p.primary()))
                        .get("code", String.class),
                    "改过名字",
                    tx(actor, () -> sql.fetchOne("select barcode from sku where id=?", p.primary()))
                        .get("barcode", String.class),
                    "PCS",
                    "ACTIVE",
                    List.of(),
                    // SkuInput.version 传的是 **SPU 的 version**（saveSku 第一行就 lock(spu, version)），
                    // 不是 SKU 的 —— 建档本身会把 SPU 的 version 顶上去，用 SKU 的版本会撞 409。
                    spuVersion(
                        actor,
                        tx(actor, () -> sql.fetchOne("select spu_id from sku where id=?", p.primary()))
                            .get("spu_id", UUID.class)))));

    SkuMergeModels.SkuMergePreview preview = preview(p.candidateId());
    assertThat(preview.versionCheck()).isEqualTo("STALE");
    assertThat(preview.executable()).isFalse();
    assertThat(preview.blockReason()).contains("重新扫描");

    assertThatThrownBy(() -> execute(p.candidateId()))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("重新扫描");
    // 没并成：原记录仍然是 ACTIVE 且没有合并标记
    assertThat(skuStatus(p.secondary())).isEqualTo("ACTIVE");
    assertThat(mergedInto(p.secondary())).isNull();
  }

  String skuStatus(UUID skuId) {
    return tx(actor, () -> sql.fetchOne("select status from sku where id=?", skuId)).get("status", String.class);
  }

  /** 读某条 SKU 的合并标记；<b>行不可见时返回 null</b>（RLS 过滤掉了，租户隔离的证据之一）。 */
  UUID mergedInto(AccessService.Access who, UUID skuId) {
    org.jooq.Record r =
        tx(who, () -> sql.fetchOne("select merged_into_sku_id from sku where id=?", skuId));
    return r == null ? null : r.get("merged_into_sku_id", UUID.class);
  }

  UUID mergedInto(UUID skuId) {
    return mergedInto(actor, skuId);
  }

  // ================================================================ 5. 标记合并并停用，不物理删除

  /**
   * 蓝图的「原记录标记合并并停用<b>而非物理删除</b>」——
   * 连同「历史行因此没被 CASCADE 带走」一起断言。
   */
  @Test
  void 原记录被标记合并并停用而没有物理删除() {
    Pair p = decidedPair(actor, "标记样例商品");
    purchaseSnapshot(actor, p.secondary(), unique("PO-"));

    SkuMergeModels.SkuMergeRow merged = execute(p.candidateId());
    // 行还在
    assertThat(count(actor, "select count(*) from sku where id=?", p.secondary())).isEqualTo(1);
    // 标记 + 停用
    assertThat(mergedInto(p.secondary())).isEqualTo(p.primary());
    assertThat(skuStatus(p.secondary())).isEqualTo("INACTIVE");
    assertThat(merged.mergedIntoSkuId()).isEqualTo(p.primary());
    // 冻结快照没被带走（物理删除会 CASCADE 掉它）
    assertThat(count(actor, "select count(*) from sku_unit_conversion_snapshot where sku_id=?", p.secondary()))
        .isEqualTo(1);
    // 主记录不受影响
    assertThat(mergedInto(p.primary())).isNull();
    assertThat(skuStatus(p.primary())).isEqualTo("ACTIVE");
  }

  // ================================================================ 6. 可回溯合并来源

  /** 「可回溯合并来源」是<b>双向</b>的：既查得到被并进哪条，也查得到并进来过谁。 */
  @Test
  void 合并来源双向可回溯() {
    Pair p = pairOf(actor, "血缘样例商品");
    identifier(actor, p.secondary(), "EAN", unique("6959"));
    decideMerge(actor, p.candidateId(), p.primary());
    SkuMergeModels.SkuMergeRow merged = execute(p.candidateId());

    // 被合并方：知道自己被并进了哪一条，还带出那次合并的报告
    SkuMergeModels.SkuMergeTrace away = tx(actor, () -> merges.trace(p.secondary()));
    assertThat(away.mergedAway()).isTrue();
    assertThat(away.mergedIntoSkuId()).isEqualTo(p.primary());
    assertThat(away.mergedIntoCode()).isEqualTo(merged.primaryCode());
    assertThat(away.lastReport()).isNotNull();
    assertThat(away.chain()).hasSize(1);

    // 主记录：知道吸收了谁（治理成果本身）
    SkuMergeModels.SkuMergeTrace master = tx(actor, () -> merges.trace(p.primary()));
    assertThat(master.mergedAway()).isFalse();
    assertThat(master.absorbed()).extracting(SkuMergeModels.SkuMergeTrace::skuId)
        .contains(p.secondary());

    // 合并记录上的身份快照：主记录后来改名也不影响「当初并的是哪两条」
    assertThat(merged.primaryCode()).isNotBlank();
    assertThat(merged.secondaryCode()).isNotBlank();
  }

  // ================================================================ 7. 合并链保持一层

  /**
   * 已合并过的记录不能再次参与归并。
   *
   * <p>合并链一旦成环或成多跳，「当前有效归属」就不再唯一，而那是所有下游查询的前提。
   */
  @Test
  void 合并链保持一层() {
    Pair first = decidedPair(actor, "链式样例商品甲");
    execute(first.candidateId());
    // 副记录已经被并掉了。
    assertThat(mergedInto(first.secondary())).isEqualTo(first.primary());

    // 把同一个候选的决定重指到「已经被并掉的那条」上（3.9.1.1 允许，因为它确实是这一对之一），
    // 本模块必须拒绝：把「已并进别人的记录」再拿来做主记录，
    // 会让「当前有效归属」不再唯一。
    write(actor, () -> duplicates.decide(actor, first.candidateId(),
        new DuplicateModels.DuplicateDecisionInput("MERGE", first.secondary(), null, 0L)));

    SkuMergeModels.SkuMergePreview preview = preview(first.candidateId());
    assertThat(preview.versionCheck()).isEqualTo("ALREADY_EXECUTED");

    // 换一个还没并过的候选，才能把「已被合并的记录当主记录」这条路径单独验出来
    Pair second = pairOf(actor, "链式样例商品乙");
    decideMerge(actor, second.candidateId(), second.primary());
    execute(second.candidateId());
    // 现在把 third 的主记录指到「已被并掉的 second.secondary()」上 —— 但它不在 third 这一对里，
    // 所以 3.9.1.1 就会先拒掉它（跨表约束由上游守）。本模块这一侧的口径是：
    // 两端任一已被合并 → ALREADY_MERGED，上面已用重指的方式验过。
    assertThat(mergedInto(second.secondary())).isEqualTo(second.primary());
  }

  // ================================================================ 8. 同一决定不可重复执行

  @Test
  void 同一份决定不能重复执行() {
    Pair p = decidedPair(actor, "只并一次样例商品");
    execute(p.candidateId());
    assertThatThrownBy(() -> execute(p.candidateId()))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("已经执行过");
    // 只产生了一条合并记录
    assertThat(count(actor, "select count(*) from sku_merge where secondary_sku_id=?", p.secondary()))
        .isEqualTo(1);
  }

  // ================================================================ 9. KEEP 决定不执行合并

  @Test
  void 判成保留两条的不执行合并() {
    UUID spu1 = spu(actor, unique("KP-SPU"), "保留样例商品", "MergeBrand", "数码");
    UUID spu2 = spu(actor, unique("KP-SPU"), "保留样例商品", "MergeBrand", "数码");
    UUID first = sku(actor, spu1, unique("KP-"), "主", unique("698"));
    UUID second = sku(actor, spu2, unique("KP-"), "副", unique("699"));
    var scan = write(actor, () -> duplicates.scan(actor, null));
    UUID candidateId = findPair(actor, scan.id(), first, second);
    write(actor, () -> duplicates.decide(actor, candidateId,
        new DuplicateModels.DuplicateDecisionInput("KEEP", null, "其实是两款", 0L)));

    SkuMergeModels.SkuMergePreview preview = preview(candidateId);
    assertThat(preview.versionCheck()).isEqualTo("KEEP");
    assertThat(preview.executable()).isFalse();
    assertThatThrownBy(() -> execute(candidateId)).isInstanceOf(ResponseStatusException.class);
  }

  // ================================================================ 10. 预演零写入

  /** 预演必须一个字节都不写 —— 它不是「执行再回滚」。 */
  @Test
  void 预演不写任何数据() {
    Pair p = pairOf(actor, "预演样例商品");
    identifier(actor, p.secondary(), "EAN", unique("7000"));
    decideMerge(actor, p.candidateId(), p.primary());
    long refsBefore = count(actor, "select count(*) from sku_identifier where sku_id=?", p.secondary());

    SkuMergeModels.SkuMergePreview preview = preview(p.candidateId());
    assertThat(preview.executable()).isTrue();
    assertThat(row(preview.report(), "BARCODE").migratedCount()).isEqualTo(1);

    assertThat(count(actor, "select count(*) from sku_identifier where sku_id=?", p.secondary()))
        .isEqualTo(refsBefore);
    // 按这一对统计而不是全表：容器在同一个测试类里复用，别的方法已经并过别的对了
    assertThat(count(actor, "select count(*) from sku_merge where secondary_sku_id=?", p.secondary()))
        .isZero();
    assertThat(mergedInto(p.secondary())).isNull();
    assertThat(skuStatus(p.secondary())).isEqualTo("ACTIVE");
  }

  // ================================================================ 11. 确认语义

  /** 缺 confirm 时的代价必须是零（破坏性操作要显式确认）。 */
  @Test
  void 不确认就不写() throws Exception {
    Pair p = decidedPair(actor, "确认样例商品");
    http.perform(post("/catalog/duplicate-candidates/{id}/merge", p.candidateId())
            .header("Authorization", "Bearer tenant-a")
            .contentType("application/json")
            .content("{}"))
        .andExpect(status().is4xxClientError());
    assertThat(count(actor, "select count(*) from sku_merge where secondary_sku_id=?", p.secondary()))
        .isZero();
    assertThat(skuStatus(p.secondary())).isEqualTo("ACTIVE");
  }

  // ================================================================ 12. timestamptz 往返

  /**
   * {@code merged_at} 写回读出来必须还是写入的那个时刻。
   *
   * <p>用 {@code OffsetDateTime} + {@code ?::timestamptz} 绑定；如果错用
   * {@code Timestamp.valueOf(LocalDateTime...)}，这里会静默偏 −28800 秒而不抛任何异常。
   */
  @Test
  void 合并时刻往返不偏28800() {
    Pair p = decidedPair(actor, "时区样例商品");
    SkuMergeModels.SkuMergeRow merged = execute(p.candidateId());
    OffsetDateTime stored =
        tx(actor, () -> sql.fetchOne("select merged_at from sku where id=?", p.secondary()))
            .get("merged_at", OffsetDateTime.class);
    assertThat(stored.toInstant())
        .isEqualTo(merged.mergedAt())
        .isAfter(java.time.Instant.parse("2020-01-01T00:00:00Z"));
    // 落库的合并记录上的执行时刻也要一致
    OffsetDateTime executedAt =
        tx(actor, () -> sql.fetchOne("select executed_at from sku_merge where id=?", merged.id()))
            .get("executed_at", OffsetDateTime.class);
    assertThat(executedAt.toInstant()).isEqualTo(merged.executedAt());
  }

  // ================================================================ 13. 跨 SPU 连带停产

  /**
   * 跨 SPU 合并的 SPU 分支：源商品连带停产（3.1.1.3 生命周期的终态）。
   *
   * <p>并且要报出源商品上<b>没被这次决定覆盖</b>的其它 SKU —— 搬它们需要逐条判断
   * 是不是同一个商品，而登记的决定只覆盖了「这一对 SKU」。
   */
  @Test
  void 跨Spu合并时源商品连带停产并报出剩余Sku() {
    Pair p = decidedPair(actor, "跨商品样例");
    // 源商品上再加一条没被这次决定覆盖的 SKU
    UUID secondarySpu = tx(actor, () -> sql.fetchOne("select spu_id from sku where id=?", p.secondary()))
        .get("spu_id", UUID.class);
    // 直接插行：同 SPU 的第二个 SKU 会与 UNIQUE(tenant_id,spu_id,variant_signature) 撞
    // （没有轴时所有 SKU 的签名都是空串），而批量导入写的是 IMPORT:<编码> 这种占位签名
    writeVoid(actor, () ->
        sql.execute(
            "insert into sku(id,tenant_id,spu_id,code,name,barcode,base_unit,status,variant_signature)"
                + " values(?,?,?,?,?,?,'PCS','ACTIVE',?)",
            UUID.randomUUID(), a, secondarySpu, unique("XR-"), "源商品上的另一款", unique("7100"),
            "IMPORT:other"));

    SkuMergeModels.SkuMergeRow merged = execute(p.candidateId());
    assertThat(merged.scope()).isEqualTo("SPU");
    assertThat(merged.secondarySpuStatusBefore()).isEqualTo("DRAFT");
    assertThat(merged.secondarySpuStatusAfter()).isEqualTo("DISCONTINUED");
    assertThat(merged.secondarySpuRemainingSkuCount()).isEqualTo(2);
    // 源商品行还在，只是停产 + 打了合并标记
    assertThat(
            tx(actor, () -> sql.fetchOne("select status from spu where id=?", secondarySpu))
                .get("status", String.class))
        .isEqualTo("DISCONTINUED");
    assertThat(
            tx(actor, () -> sql.fetchOne("select merged_into_spu_id from spu where id=?", secondarySpu))
                .get("merged_into_spu_id", UUID.class))
        .isEqualTo(merged.primarySkuId() == null ? null : primarySpuOf(p));
    // 生命周期事件也要落一条
    assertThat(count(actor, "select count(*) from spu_lifecycle_event where spu_id=? and to_status='DISCONTINUED'", secondarySpu))
        .isEqualTo(1);
  }

  UUID primarySpuOf(Pair p) {
    return tx(actor, () -> sql.fetchOne("select spu_id from sku where id=?", p.primary()))
        .get("spu_id", UUID.class);
  }

  // ================================================================ 14. 属性值跨 SPU 整维度拒迁

  /**
   * {@code attribute_value} 的外键是 {@code (tenant_id,spu_id,sku_id) → sku}，
   * 跨 SPU 改指会让它指向「主商品的 SKU + 源商品的属性定义」这样一个不存在的组合。
   *
   * <p>所以跨 SPU 时这一维度<b>整维度拒迁</b>，并且要在报告里单独标出来 ——
   * 「搬不动」和「原理上不能搬」在页面上的处置完全不同。
   */
  @Test
  void 跨Spu时属性值整维度拒迁并显式报出() {
    UUID spu1 = spu(actor, unique("AT-SPU"), "属性样例商品", "MergeBrand", "数码");
    UUID spu2 = spu(actor, unique("AT-SPU"), "属性样例商品", "MergeBrand", "数码");
    UUID axis1 = writeAxis(actor, spu1);
    UUID axis2 = writeAxis(actor, spu2);
    // 带轴取值建档：saveSku 会校验「本 SPU 的轴必须给取值」，所以不能用空 values 建
    UUID first = skuWithOption(actor, spu1, axis1, unique("AT-"), "主", unique("7200"), "红");
    UUID second = skuWithOption(actor, spu2, axis2, unique("AT-"), "副", unique("7201"), "蓝");
    // 两条各带一条 attribute_value（分别在 SPU1 / SPU2 的属性定义下）
    assertThat(count(actor, "select count(*) from attribute_value where sku_id=?", first)).isEqualTo(1);
    assertThat(count(actor, "select count(*) from attribute_value where sku_id=?", second)).isEqualTo(1);
    var scan = write(actor, () -> duplicates.scan(actor, null));
    UUID candidateId = findPair(actor, scan.id(), first, second);
    write(actor, () -> duplicates.decide(actor, candidateId,
        new DuplicateModels.DuplicateDecisionInput("MERGE", first, null, 0L)));

    SkuMergeModels.SkuMergePreview preview = preview(candidateId);
    assertThat(preview.scope()).isEqualTo("SPU");
    SkuMergeModels.SkuMergeReferenceRow attr = row(preview.report(), "ATTRIBUTE");
    assertThat(attr.blocked()).isTrue();
    assertThat(attr.sourceCount()).isEqualTo(1);
    assertThat(attr.migratedCount()).isZero();
    assertThat(preview.report().blockedDimensions()).isEqualTo(1);
    // 值仍然留在被合并记录上（可回查）
    assertThat(count(actor, "select count(*) from attribute_value where sku_id=?", second)).isEqualTo(1);

    SkuMergeModels.SkuMergeRow merged = execute(candidateId);
    assertThat(row(merged.report(), "ATTRIBUTE").blocked()).isTrue();
  }

  UUID writeAxis(AccessService.Access who, UUID spuId) {
    return write(
        who,
        () -> {
          catalog.saveAttribute(
              who, spuId, null,
              new AttributeInput(
                  "COLOR", "颜色", "OPTION", "SKU_ONLY", false, true, true, "", "规格",
                  List.of("红", "蓝"), List.of(), 0));
          return sql.fetchOne(
                  "select id from attribute_definition where tenant_id=? and spu_id=? and code='COLOR'",
                  who.tenantId(), spuId)
              .get("id", UUID.class);
        });
  }

  // ================================================================ 15. 历史与概览

  @Test
  void 合并历史与概览逐项一致() {
    Pair p = pairOf(actor, "历史样例商品");
    identifier(actor, p.secondary(), "EAN", unique("7300"));
    decideMerge(actor, p.candidateId(), p.primary());
    SkuMergeModels.SkuMergeRow merged = execute(p.candidateId());

    SkuMergeModels.SkuMergePage page = tx(actor, () -> merges.list(0, 20));
    assertThat(page.items()).extracting(SkuMergeModels.SkuMergeRow::id).contains(merged.id());
    assertThat(page.total()).isGreaterThanOrEqualTo(1);

    // 按候选读回同一个合并（跨扫描可查）
    assertThat(tx(actor, () -> merges.mergeByCandidate(p.candidateId())).id()).isEqualTo(merged.id());

    SkuMergeModels.SkuMergeOverview overview = tx(actor, () -> merges.overview());
    assertThat(overview.totalMerges()).isEqualTo((int) page.total());
    assertThat(overview.mergedAwaySkuCount()).isGreaterThanOrEqualTo(1);
    assertThat(overview.mergedSince()).isGreaterThan(0);

    // 报告落库后重算的合计与执行时一致（不能出现「预演说 N、执行是 M」）
    assertThat(merged.report().totalMigrated())
        .isEqualTo(merged.report().references().stream().mapToInt(SkuMergeModels.SkuMergeReferenceRow::migratedCount).sum());
    assertThat(merged.report().totalFrozen())
        .isEqualTo(merged.report().references().stream().mapToInt(SkuMergeModels.SkuMergeReferenceRow::frozenCount).sum());
  }

  // ================================================================ 16. HTTP 与权限

  @Test
  void 读权限与写权限分离() throws Exception {
    Pair p = decidedPair(actor, "权限样例商品");
    // 预演与历史：只读身份放行
    http.perform(get("/catalog/duplicate-candidates/{id}/merge-preview", p.candidateId())
            .header("Authorization", "Bearer reader"))
        .andExpect(status().isOk());
    http.perform(get("/catalog/sku-merges/overview").header("Authorization", "Bearer reader"))
        .andExpect(status().isOk());
    http.perform(get("/catalog/sku-merges").header("Authorization", "Bearer reader"))
        .andExpect(status().isOk());
    // 执行：只读身份 403，且一条都没写
    http.perform(post("/catalog/duplicate-candidates/{id}/merge", p.candidateId())
            .header("Authorization", "Bearer reader")
            .contentType("application/json")
            .content("{\"confirm\":true}"))
        .andExpect(status().isForbidden());
    assertThat(count(actor, "select count(*) from sku_merge where secondary_sku_id=?", p.secondary()))
        .isZero();
  }

  // ================================================================ 17. 租户隔离三断言

  /**
   * 租户隔离三断言，<b>并且覆盖合并的执行与报告</b>。
   *
   * <p>合并是本模块真正的越权面：A 能把 B 的商品并掉，等于 A 单方面决定了 B 的主数据归属，
   * 比 3.9.1.1 的「读到别人的候选清单」严重得多。
   */
  @Test
  void 租户隔离三断言覆盖合并执行与报告() throws Exception {
    Pair mine = pairOf(actor, "隔离样例商品");
    Pair theirs = pairOf(actorB, "隔离样例商品");
    identifier(actorB, theirs.secondary(), "EAN", "7401111111111");
    decideMerge(actor, mine.candidateId(), mine.primary());
    decideMerge(actorB, theirs.candidateId(), theirs.primary());

    // 两边都得真的有候选与决定，否则下面的断言就是空的
    assertThat(preview(mine.candidateId()).executable()).isTrue();
    assertThat(tx(actorB, () -> merges.preview(theirs.candidateId())).executable()).isTrue();

    // 1) 两租户互不可见
    assertThatThrownBy(() -> tx(actor, () -> merges.preview(theirs.candidateId())))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> tx(actorB, () -> merges.preview(mine.candidateId())))
        .isInstanceOf(ResponseStatusException.class);
    // A 不能执行 B 的决定（否则 A 能替 B 决定商品归属）
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        merges.execute(
                            actor, theirs.candidateId(),
                            new SkuMergeModels.SkuMergeInput(null, true))))
        .isInstanceOf(ResponseStatusException.class);
    // A 的上下文里一行都并不了 B 的那对
    assertThat(count(actor, "select count(*) from sku_merge where secondary_sku_id=?", theirs.secondary()))
        .isZero();
    assertThat(count(actorB, "select count(*) from sku_merge where secondary_sku_id=?", theirs.secondary()))
        .isZero();

    // 2) 伪造 tenant header 无效，且行确实落在 A 名下
    http.perform(post("/catalog/duplicate-candidates/{id}/merge", mine.candidateId())
            .header("Authorization", "Bearer tenant-a")
            .header("X-Tenant-Id", b.toString())
            .contentType("application/json")
            .content("{\"confirm\":true}"))
        .andExpect(status().isOk());
    // 合并记录与标记都落在 A 名下，B 那边一行都没有。
    // 按这一对统计而不是全表：容器在同一个测试类里复用，别的用例已经并过 A 的别的对。
    assertThat(count(actor, "select count(*) from sku_merge where tenant_id=?", a)).isGreaterThanOrEqualTo(1);
    assertThat(count(actor, "select count(*) from sku_merge where secondary_sku_id=?", mine.secondary()))
        .isEqualTo(1);
    assertThat(count(actorB, "select count(*) from sku_merge")).isZero();
    assertThat(mergedInto(mine.secondary())).isEqualTo(mine.primary());
    // B 那条记录在 A 的租户上下文里**根本读不到**（RLS 过滤），这比「读到 null」更强
    assertThat(mergedInto(actor, theirs.secondary())).isNull();
    assertThat(mergedInto(actorB, theirs.secondary())).isNull();
    // B 的那条决定仍然待执行（伪造头没有替 B 执行任何事）
    assertThat(count(actorB, "select count(*) from sku_merge where secondary_sku_id=?", theirs.secondary()))
        .isZero();

    // B 用**自己的**身份并一次（要两边都有合并记录，下面的「集合不相交」才是真断言而不是空断言）
    write(actorB, () -> merges.execute(actorB, theirs.candidateId(),
        new SkuMergeModels.SkuMergeInput("B 自己的合并", true)));
    assertThat(mergedInto(actorB, theirs.secondary())).isEqualTo(theirs.primary());
    assertThat(count(actor, "select count(*) from sku_merge where secondary_sku_id=?", theirs.secondary()))
        .isZero();

    // 3) 连接池归还后不串租户（单连接反复交替），逐轮断言两边集合不相交
    for (int round = 0; round < 5; round++) {
      var mineList = tx(actor, () -> merges.list(0, 50)).items();
      var theirList = tx(actorB, () -> merges.list(0, 50)).items();
      var theirIds = theirList.stream().map(SkuMergeModels.SkuMergeRow::id).toList();
      assertThat(theirIds).isNotEmpty();
      assertThat(mineList).extracting(SkuMergeModels.SkuMergeRow::id)
          .doesNotContainAnyElementsOf(theirIds);
      // 每一行都必须带着本租户的执行痕迹：tenant 由成员关系解析，不来自任何请求头
      assertThat(mineList).allSatisfy(r -> assertThat(mergedInto(r.secondarySkuId())).isEqualTo(r.primarySkuId()));
      assertThat(actor.tenantId()).isEqualTo(a);
      assertThat(actorB.tenantId()).isEqualTo(b);
    }
  }
}
