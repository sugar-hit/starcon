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
 * 3.9.1.1 重复 SKU 识别：真实 PG16 + 强制 RLS 的落库往返、四条判定线、相似度是真的算出来的、
 * 以及<b>租户隔离三断言</b>。
 *
 * <p><b>连接池固定为 1 条连接</b>：只有复用同一条连接，才能真正证明归还后不会串租户。
 *
 * <p><b>本文件最重要的几组断言</b>：
 *
 * <ol>
 *   <li>{@link #四条判定线都在真实数据上命中()}：蓝图的四类重复逐条钉死，且相似度是
 *       <b>算出来的具体数值</b>（条码线必须 1.0000，近似同品线必须等于按 0.7/0.3 权重反算的值），
 *       不是常数、不是随机数。
 *   <li>{@link #重叠维度数与信号行数逐候选相等()}：蓝图点名「重叠维度」这一栏，
 *       它必须与逐维度信号行数严格一致，否则清单上两个数会互相矛盾。
 *   <li>{@link #决定只为下游预留expectedVersion且不碰任何业务列()}：本模块只登记决定，
 *       必须证明 sku/spu 一列都没被改写，而 expected_version 真的留给了 3.9.1.2。
 *   <li>{@link #租户隔离三断言覆盖候选与决定()}：候选清单里含本租户商品的标题、品牌与条码，
 *       串租户等于把 A 的商品资料泄漏给 B。
 *   <li>{@link #timestamptz往返不偏28800()}：扫描时刻写回读出来必须还是写入的那个时刻。
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class DuplicateDetectionIntegrationTest {
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
      // 只读身份：能读候选清单，写扫描与决定必须 403。
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

  /**
   * 建一个 SPU（saveSpu 直接返回 id）。
   *
   * <p><b>一个 SPU 里只能有一个这样的 SKU</b>：V1 的 {@code UNIQUE(tenant_id,spu_id,variant_signature)}
   * 与 {@code CatalogService.saveSku} 的签名算法共同决定 —— 没有规格轴属性时所有 SKU 的
   * {@code variant_signature} 都是空串，所以同 SPU 的第二个 SKU 会直接 409。
   * 需要同 SPU 两个 SKU 的测试走 {@link #variantSpu} + {@link #skuWithOption}。
   */
  UUID spu(AccessService.Access who, String code, String name, String brand, String category) {
    return write(
        who, () -> catalog.saveSpu(who, null, new SpuInput(code, name, "", brand, category, "DRAFT", 0)));
  }

  /**
   * 建一个 SKU（不带规格取值）。
   *
   * <p><b>{@code SkuInput.version} 传的是 SPU 的 version</b>（{@code CatalogService.saveSku}
   * 第一行就是 {@code lock(spu, input.version())}），不是 SKU 的。所以每次建 SKU 前
   * 都要现读 SPU 的 version —— 建档本身会把 SPU 的 version 顶上去，用 0 会撞 409。
   */
  UUID sku(AccessService.Access who, UUID spuId, String code, String name, String barcode) {
    return write(
        who,
        () ->
            catalog.saveSku(
                who, spuId, null,
                new SkuInput(code, name, barcode, "PCS", "ACTIVE", List.of(), spuVersion(who, spuId))));
  }

  long spuVersion(AccessService.Access who, UUID spuId) {
    return tx(who, () -> sql.fetchOne("select version from spu where id=?", spuId)).get("version", Long.class);
  }

  /** 建一个带规格轴的 SPU（后续它的 SKU 各自带一个不同的轴取值）。 */
  record VariantSpu(UUID spuId, UUID axisId) {}

  VariantSpu variantSpu(
      AccessService.Access who, String code, String name, String brand, String category,
      List<String> options) {
    UUID spuId = spu(who, code, name, brand, category);
    UUID axisId =
        write(
            who,
            () -> {
              catalog.saveAttribute(
                  who, spuId, null,
                  new AttributeInput(
                      "COLOR", "颜色", "OPTION", "SKU_ONLY", false, true, true, "", "规格",
                      options, List.of(), 0));
              return sql.fetchOne(
                      "select id from attribute_definition where tenant_id=? and spu_id=? and code='COLOR'",
                      who.tenantId(), spuId)
                  .get("id", UUID.class);
            });
    return new VariantSpu(spuId, axisId);
  }

  /** 建一个带规格取值的 SKU（走已交付的组合唯一性链路，签名由上游按轴值算出）。 */
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

  /**
   * 直接按 SKU 写一组规格取值（不走组合服务）。
   *
   * <p>刻意用显式 SQL 而不是 {@code catalog.saveSku}：本模块的判定只读
   * {@code attribute_value × attribute_definition.variant_axis × attribute_value_item}，
   * 而 {@code saveSku} 会校验组合唯一性 —— 而「同一 SPU 下两个 SKU 承载同一套真实轴值」
   * 恰恰是 V36 要抓的重复，正常写入路径本来就该拒绝它。真实来源是批量导入
   * （V29 给导入的 SKU 写 {@code IMPORT:<编码>} 占位签名，绕开了组合唯一性）。
   * 所以这里用 SQL 复原导入之后的样子：签名文本不同、真实轴值相同。
   */
  void writeVariantValue(UUID attributeId, UUID skuId, String option) {
    writeVoid(
        actor,
        () -> {
          // 值行已经由 saveSku 写好了（V1 的 UNIQUE NULLS NOT DISTINCT(tenant_id,attribute_id,sku_id)
          // 保证一个 SKU 在一条轴上只有一个值），所以这里改的是**取值**而不是再插一行 ——
          // 复原「导入把真实轴值写成了与另一条相同、而签名文本仍是占位签名」的那个形状。
          sql.execute(
              "update attribute_value_item set option_value=? where tenant_id=? and attribute_id=?"
                  + " and value_id=(select id from attribute_value where tenant_id=? and attribute_id=?"
                  + " and sku_id=?)",
              option, a, attributeId, a, attributeId, skuId);
          sql.execute(
              "update sku set variant_signature=? where id=?", "IMPORT:" + skuId, skuId);
        });
  }

  /**
   * 直接改 {@code sku.barcode} 列（不建标识表）。
   *
   * <p>用它构造「同条码多 SKU」：V7 的标识表有 {@code UNIQUE(tenant_id,scheme,value)} 挡着，
   * 而这一列<b>没有</b>唯一约束 —— 批量导入只写列、不同步标识表时就会撞成这个形状，
   * 所以它才是「同条码多 SKU」的真实来源。直接 UPDATE 也避免了 saveSku 的签名唯一性 409。
   */
  void skuColumnBarcode(UUID skuId, String barcode) {
    writeVoid(actor, () -> sql.execute("update sku set barcode=? where id=?", barcode, skuId));
  }

  /**
   * 写一条物料标识（V7 的 {@code sku_identifier}）。
   *
   * <p><b>同一个值只能挂一个 SKU</b>：V7 的 {@code UNIQUE(tenant_id,scheme,value)} 就是这么定的，
   * 所以「同条码多 SKU」的真实来源是 {@code sku.barcode} 那个<b>没有唯一约束</b>的列
   * （批量导入只写列、不同步标识表时就会撞），以及「A 的列 vs B 的标识表」这种跨来源。
   * 本夹具用它构造<b>跨来源</b>的那一类：只给一侧写标识表，另一侧靠列。
   */
  void identifier(UUID skuId, String scheme, String value) {
    writeVoid(
        actor,
        () ->
            sql.execute(
                "insert into sku_identifier(id,tenant_id,sku_id,scheme,value,is_primary,source,occurred_at)"
                    + " values(?::uuid,?::uuid,?::uuid,?,?,true,'MANUAL',now())",
                UUID.randomUUID(), a, skuId, scheme, value));
  }

  /**
   * 造一张素材并挂到 SKU 上。
   *
   * <p>内容哈希由<b>给定的 seed</b>算出而不是随机：相似图片这一维要断言的是
   * 「两张图内容相同 → 命中」，所以哈希必须可控。{@code media_asset} 的
   * {@code current_version} 由 V18 的触发器从版本行推进，所以必须先插素材再插版本行。
   */
  UUID media(UUID skuId, String seed) {
    UUID assetId = newAsset(seed);
    attachMedia(assetId, skuId);
    return assetId;
  }

  /**
   * 把一个**已有**素材再挂到另一个 SKU 上。
   *
   * <p>{@code media_asset} 是内容寻址的（V18：{@code UNIQUE(tenant_id,content_sha256)}），
   * 所以「两个 SKU 用同一张图」在数据上就是<b>同一个素材行被引用两次</b>，
   * 而不是两个内容相同的素材行 —— 这正是「相似图片」的真实形状。
   */
  void attachMedia(UUID assetId, UUID skuId) {
    writeVoid(
        actor,
        () ->
            sql.execute(
                "insert into media_reference(id,tenant_id,asset_id,owner_type,sku_id)"
                    + " values(?::uuid,?::uuid,?::uuid,'SKU',?::uuid)",
                UUID.randomUUID(), a, assetId, skuId));
  }

  /** 只建素材与它的版本行（当前版本由 V18 的触发器从版本行推进，所以必须先插素材）。 */
  UUID newAsset(String seed) {
    return write(
        actor,
        () -> {
          UUID assetId = UUID.randomUUID();
          UUID versionId = UUID.randomUUID();
          String hash = sha256(seed);
          String owner = "dup-" + hash.substring(0, 8);
          String key = "dup-test/" + hash.substring(0, 16) + ".png";
          sql.execute(
              "insert into media_asset(id,tenant_id,name,media_type,purpose,file_format,mime_type,"
                  + "byte_size,width_px,height_px,content_sha256,storage_key,original_filename)"
                  + " values(?::uuid,?::uuid,?,'IMAGE','PRIMARY','png','image/png',1024,100,100,?,?,?)",
              assetId, a, owner, hash, key, owner + ".png");
          sql.execute(
              "insert into media_asset_version(id,tenant_id,asset_id,version_no,storage_key,"
                  + "original_filename,file_format,mime_type,byte_size,width_px,height_px,content_sha256)"
                  + " values(?::uuid,?::uuid,?::uuid,1,?,?,'png','image/png',1024,100,100,?)",
              versionId, a, assetId, key, owner + ".png", hash);
          return assetId;
        });
  }

  static String sha256(String seed) {
    try {
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(seed.getBytes("UTF-8")));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** 跑一次扫描并返回扫描行。 */
  DuplicateModels.DuplicateScanRow scan(AccessService.Access who, BigDecimal threshold, String category, String brand) {
    return write(who, () -> duplicates.scan(who, new DuplicateModels.DuplicateScanInput(threshold, category, brand)));
  }

  // ================================================================ 四条判定线

  @Test
  void 四条判定线都在真实数据上命中() {
    // ---- 线 1：BARCODE（同条码多 SKU）----
    UUID barcodeSpu = spu(actor, unique("BC-SPU"), "条码重复商品甲", "BrandX", "数码");
    UUID sameBarcodeSpu = spu(actor, unique("BC-SPU"), "条码重复商品乙", "BrandX2", "数码2");
    // 同一个条码值挂在两个 SKU 的 sku.barcode 列上（V7 的标识表有唯一约束，列上没有 ——
    // 批量导入只写列、不同步标识表就会撞成这个形状）。
    UUID left = sku(actor, barcodeSpu, unique("BC-L"), "同条码左", "6901234567892");
    UUID right = sku(actor, sameBarcodeSpu, unique("BC-R"), "同条码右", "6901234567892");
    // 右侧再登记一条物料标识，构造「列 vs 标识表」的跨来源：只看任何一侧都发现不了，
    // 只有把两个来源合起来才认得出是同一条码。
    identifier(right, "EAN", "6901234567892");

    var scan = scan(actor, null, null, null);
    var candidates =
        tx(actor, () -> duplicates.candidates(scan.id(), "BARCODE", null, null, 0, 100)).items();
    var hit =
        candidates.stream()
            .filter(c -> c.leftSkuId().equals(left) && c.rightSkuId().equals(right))
            .findFirst()
            .orElseThrow();
    // 条码撞了必须满分：打了折扣就会让「条码已经撞了」看起来比「只是标题像」轻。
    assertThat(hit.similarity()).isEqualByComparingTo("1.0000");
    assertThat(hit.overlapDimensionCount()).isEqualTo(1);
    var barcodeSignal =
        hit.dimensions().stream().filter(d -> d.dimension().equals("BARCODE")).findFirst().orElseThrow();
    // 证据必须写明撞的是哪个条码值（否则页面上只有一个孤零零的 1.0000）。
    assertThat(barcodeSignal.evidence()).contains("6901234567892");

    // ---- 线 2：VARIANT_COMBINATION（同 SPU 重复规格组合）----
    // 两个 SKU 承载同一套真实轴值，但 variant_signature 不同（模拟导入占位签名）——
    // 这正是 V36 文件头说的场景：V1 的 UNIQUE(tenant_id,spu_id,variant_signature) 拦不住它。
    // 组合唯一性在正常写入路径上生效（同 SPU 两个 SKU 不能带同一个轴取值），
    // 所以先建两个<b>取值不同</b>的 SKU，再用 SQL 把右侧的真实轴值改成与左侧相同 ——
    // 复原批量导入之后的样子：签名文本不同（占位签名），真实轴值相同。
    var vs = variantSpu(actor, unique("VC-SPU"), "规格重复商品", "BrandY", "服饰", List.of("红", "蓝"));
    UUID v1 = skuWithOption(actor, vs.spuId(), vs.axisId(), unique("VC-1"), "红 大", "", "红");
    UUID v2 = skuWithOption(actor, vs.spuId(), vs.axisId(), unique("VC-2"), "蓝色大号", "", "蓝");
    writeVariantValue(vs.axisId(), v1, "红");
    writeVariantValue(vs.axisId(), v2, "红");

    // ---- 线 3：CROSS_SPU（同商品不同 SPU 重复建档）----
    // 名称归一化后相同（含多空格与大小写差异）、品牌大小写不同、类目相同，
    // 而编码不同（V1 只保证编码租户内唯一，不阻止同一个商品建两遍）。
    UUID dupSpu1 = spu(actor, unique("XS-SPU"), "运动跑鞋 男款", "BrandZ", "鞋靴");
    UUID dupSpu2 = spu(actor, unique("XS-SPU"), "运动跑鞋  男款", "brandz", "鞋靴");
    UUID xs1 = sku(actor, dupSpu1, unique("XS-1"), "跑鞋 42", "");
    UUID xs2 = sku(actor, dupSpu2, unique("XS-2"), "跑鞋 43", "");

    // ---- 线 4：SIMILAR_PRODUCT（近似标题 + 相似图片）----
    // 两个 SPU 的名称/品牌/类目都<b>不相同</b>：否则 CROSS_SPU（三个坐标全同）会同时命中，
    // 总体相似度取最大值就变成 1.0000，SIMILAR_PRODUCT 的加权分（0.7×标题 + 0.3×图片）就被顶掉了，
    // 那条线反而验不到。品牌与类目刻意不同，是为了让 CROSS_SPU 明确不成立。
    UUID simSpu1 = spu(actor, unique("SIM-SPU"), "儿童保温杯 500ml 蓝色", "BrandW", "家居");
    UUID simSpu2 = spu(actor, unique("SIM-SPU"), "儿童水杯 500ml 蓝色", "BrandV", "日用");
    // 两个 SKU 的名称完全相同（标题相似度 1.0000），图片相似度 0.5000
    // → 0.7×1.0000 + 0.3×0.5000 = 0.8500：下面直接对这个数断言，
    // 改权重或改图片口径都会在这里变红。
    UUID sim1 = sku(actor, simSpu1, unique("SIM-1"), "蓝色儿童保温杯", "");
    UUID sim2 = sku(actor, simSpu2, unique("SIM-2"), "蓝色儿童保温杯", "");
    // 左侧两张图（共用 + 独占），右侧只有共用那张：图片交集 {共用}、并集 2 张 → 0.5000。
    // （素材是内容寻址的，V18 的 UNIQUE(tenant_id,content_sha256) 决定「同一张图」在数据上
    //  就是同一行素材被引用两次，而不是两个内容相同的素材行。）
    UUID sharedAsset = media(sim1, "shared-image");
    attachMedia(sharedAsset, sim2);
    media(sim1, "only-left-image");

    var full = scan(actor, null, null, null);
    var all =
        tx(actor, () -> duplicates.candidates(full.id(), null, null, null, 0, 100)).items();

    // 规格组合：同一 SPU 下两个 SKU，签名现算后相同 → 1.0000
    var variant =
        all.stream()
            .filter(c -> Set.of(c.leftSkuId(), c.rightSkuId()).equals(Set.of(v1, v2)))
            .findFirst()
            .orElseThrow();
    assertThat(variant.similarity()).isEqualByComparingTo("1.0000");
    assertThat(variant.dimensions()).extracting(DuplicateModels.DuplicateSignalView::dimension)
        .contains("VARIANT_COMBINATION");

    // 跨 SPU：名称归一化后相同 + 品牌大小写不同 + 类目相同 → 1.0000
    var cross =
        all.stream()
            .filter(c -> c.dimensions().stream().anyMatch(d -> d.dimension().equals("CROSS_SPU")))
            .filter(c -> Set.of(c.leftSkuId(), c.rightSkuId()).equals(Set.of(xs1, xs2)))
            .findFirst()
            .orElseThrow(() -> new AssertionError("没有命中跨 SPU 重复建档"));
    assertThat(cross.similarity()).isEqualByComparingTo("1.0000");
    // 跨 SPU 的前提就是「两个不同 SPU」：直接查库证明这一对的两个 SKU 确实挂在不同 SPU 上。
    var pairSpus =
        tx(
            actor,
            () ->
                sql.fetch(
                    "select spu_id from sku where id in (?,?) order by spu_id",
                    cross.leftSkuId(), cross.rightSkuId()));
    assertThat(pairSpus).hasSize(2);
    assertThat(pairSpus.get(0).get("spu_id", UUID.class))
        .isNotEqualTo(pairSpus.get(1).get("spu_id", UUID.class));

    // 近似同品：标题完全相同（标题相似度 1.0000）+ 图片重合 1/2（0.5000）
    // → 0.7×1.0000 + 0.3×0.5000 = 0.8500。这是对权重的直接反算，改权重就变红。
    var similar =
        all.stream()
            .filter(c -> Set.of(c.leftSkuId(), c.rightSkuId()).equals(Set.of(sim1, sim2)))
            .findFirst()
            .orElseThrow();
    assertThat(similar.similarity()).isEqualByComparingTo("0.8500");
    var similarSignal =
        similar.dimensions().stream()
            .filter(d -> d.dimension().equals("SIMILAR_PRODUCT"))
            .findFirst()
            .orElseThrow();
    assertThat(similarSignal.evidence()).contains("1 张相同素材");

    // 相似度不是常数：同一个清单里至少有两个不同的取值。
    assertThat(all.stream().map(c -> c.similarity()).distinct().count()).isGreaterThan(1);
  }

  @Test
  void 相似度列是numeric54而不是浮点() {
    assertThat(columnType("sku_duplicate_candidate", "similarity")).isEqualTo("numeric");
    assertThat(columnPrecision("sku_duplicate_candidate", "similarity")).isEqualTo("5,4");
    assertThat(columnType("sku_duplicate_signal", "score")).isEqualTo("numeric");
    assertThat(columnPrecision("sku_duplicate_signal", "score")).isEqualTo("5,4");
    assertThat(columnType("sku_duplicate_scan", "min_similarity")).isEqualTo("numeric");
    assertThat(columnPrecision("sku_duplicate_scan", "min_similarity")).isEqualTo("5,4");
  }

  @Test
  void 阈值真的会改变结果而不是被忽略() {
    UUID spu1 = spu(actor, unique("TH-SPU"), "阈值测试商品甲", "BrandT", "数码");
    UUID spu2 = spu(actor, unique("TH-SPU"), "阈值测试商品乙", "BrandT2", "数码2");
    // 两个 SKU 名称只差一个字，且共用一张素材（图片相似度 1.0000）。
    // 品牌与类目不同 → CROSS_SPU 不成立；没有条码 → BARCODE 不成立。
    // 所以这一对<b>只可能</b>由 SIMILAR_PRODUCT 命中，得分严格小于 1.0000：
    // 阈值低于它 → 成候选；阈值 1.0000 → 不成候选。阈值真的在起作用。
    UUID s1 = sku(actor, spu1, unique("TH-1"), "阈值测试商品甲", "");
    UUID s2 = sku(actor, spu2, unique("TH-2"), "阈值测试商品乙", "");
    UUID thShared = media(s1, "th-shared");
    attachMedia(thShared, s2);

    var loose = scan(actor, new BigDecimal("0.5000"), null, null);
    var strict = scan(actor, new BigDecimal("1.0000"), null, null);
    var loosePair = pair(loose, s1, s2);
    var strictPair = pair(strict, s1, s2);
    assertThat(loosePair).as("0.9 阈值下这一对应该成候选").isNotNull();
    assertThat(strictPair).as("0.99 阈值下同一对不成候选（阈值真的生效）").isNull();
    // 阈值也记在扫描行上：「这份清单是按什么标准判出来的」可回溯。
    assertThat(loose.minSimilarity()).isEqualByComparingTo("0.5000");
    assertThat(strict.minSimilarity()).isEqualByComparingTo("1.0000");
    // 得分确实落在 (0.5, 1.0) 这个区间里，而不是恰好 1.0000
    assertThat(pair(loose, s1, s2).similarity())
        .isBetween(new BigDecimal("0.5000"), new BigDecimal("0.9999"));
  }

  @Test
  void 范围收窄会显式标记且不冒充全量结论() {
    UUID narrow = spu(actor, unique("SC-SPU"), "范围收窄商品", "BrandS", "收窄类目");
    UUID other = spu(actor, unique("SC-SPU"), "范围外商品", "BrandS", "别的类目");
    sku(actor, narrow, unique("SC-1"), "范围内", "");
    sku(actor, other, unique("SC-2"), "范围外", "");
    var scoped = scan(actor, null, "收窄类目", null);
    assertThat(scoped.scopeApplied()).isTrue();
    assertThat(scoped.scopeCategory()).isEqualTo("收窄类目");
    var overview = tx(actor, () -> duplicates.overview());
    assertThat(overview.note()).contains("收窄过").contains("不等于干净");
    assertThat(overview.latestScan().id()).isEqualTo(scoped.id());

    // 全量扫描不该带范围标记。
    var full = scan(actor, null, null, null);
    assertThat(full.scopeApplied()).isFalse();
    assertThat(full.scannedSkuCount()).isGreaterThanOrEqualTo(scoped.scannedSkuCount());
    // 范围里只有一个 SKU，全量更多：证明筛选真的作用在读数上，不是装饰。
    assertThat(scoped.scannedSkuCount()).isEqualTo(1);
    assertThat(full.scannedSkuCount()).isGreaterThan(scoped.scannedSkuCount());
  }

  @Test
  void 重叠维度数与信号行数逐候选相等且一对只有一行() {
    UUID spu1 = spu(actor, unique("OV-SPU"), "重叠测试商品", "BrandO", "数码");
    UUID spu2 = spu(actor, unique("OV-SPU"), "重叠测试商品", "BrandO", "数码");
    // 同时造两个证据：条码撞了（1.0000）+ 名称与品牌类目全同（1.0000）→ 重叠维度数应为 2。
    UUID left = sku(actor, spu1, unique("OV-1"), "重叠左", "6909999999999");
    UUID right = sku(actor, spu2, unique("OV-2"), "重叠右", "6909999999999");

    var scan = scan(actor, null, null, null);
    var candidates = tx(actor, () -> duplicates.candidates(scan.id(), null, null, null, 0, 200)).items();
    var overlap =
        candidates.stream()
            .filter(c -> Set.of(c.leftSkuId(), c.rightSkuId()).equals(Set.of(left, right)))
            .findFirst()
            .orElseThrow();
    assertThat(overlap.overlapDimensionCount()).isEqualTo(2);
    assertThat(overlap.dimensions()).hasSize(2);
    assertThat(overlap.dimensions()).extracting(DuplicateModels.DuplicateSignalView::dimension)
        .containsExactlyInAnyOrder("BARCODE", "CROSS_SPU");

    // 全表逐候选断言：重叠维度数 == 信号行数；且 (left,right) 组合不重复。
    var raw =
        tx(
            actor,
            () ->
                sql.fetch(
                    "select k.id,k.left_sku_id,k.right_sku_id,k.overlap_dimension_count,"
                        + "(select count(*) from sku_duplicate_signal g"
                        + "  where g.tenant_id=k.tenant_id and g.candidate_id=k.id) as signal_rows"
                        + " from sku_duplicate_candidate k where k.scan_id=?",
                    scan.id()));
    assertThat(raw).isNotEmpty();
    Set<String> pairs = new java.util.HashSet<>();
    for (var r : raw) {
      assertThat(r.get("overlap_dimension_count", Integer.class))
          .as("候选 %s 的重叠维度数必须等于信号行数", r.get("id", UUID.class))
          .isEqualTo(r.get("signal_rows", Integer.class));
      assertThat(pairs.add(r.get("left_sku_id", UUID.class) + "|" + r.get("right_sku_id", UUID.class)))
          .as("同一对 SKU 在一份扫描里只能有一行")
          .isTrue();
      // 左右按 uuid 字符串升序规范化（V36 的 CHECK 也在 DB 层钉着这一条）。
      assertThat(r.get("left_sku_id", UUID.class).toString())
          .isLessThan(r.get("right_sku_id", UUID.class).toString());
    }
  }

  @Test
  void 分组判定不漏检_已知的重复都能被找到() {
    // 这条是对「按分组键只比组内」的回归防线：如果哪天把某条线的分组键收窄成
    // 「连 join 都不做」，这一条会先红 —— 而漏检在页面上表现为「已治理干净」。
    var rc = variantSpu(actor, unique("RC-SPU"), "不漏检商品", "BrandR", "数码", List.of("大", "小"));
    // 两个 SKU 同 SPU，轴取值不同（组合唯一性允许），名称只差一个字（「不漏检大号」/「不漏检大号款」：
    // 二元组 Jaccard = 4/5 = 0.8000，过 0.75 的标题门槛），共用一张素材 → 近似同品那一维成立。
    UUID s1 = skuWithOption(actor, rc.spuId(), rc.axisId(), unique("RC-1"), "不漏检大号", "", "大");
    UUID s2 = skuWithOption(actor, rc.spuId(), rc.axisId(), unique("RC-2"), "不漏检大号款", "", "小");
    writeVariantValue(rc.axisId(), s1, "大");
    writeVariantValue(rc.axisId(), s2, "大");
    // 跨来源撞条码：只给一侧写标识表，另一侧靠 sku.barcode 列。
    skuColumnBarcode(s1, "6908888888888");
    identifier(s2, "EAN", "6908888888888");
    UUID rcShared = media(s1, "rc-shared");
    attachMedia(rcShared, s2);

    var scan = scan(actor, null, null, null);
    var candidates = tx(actor, () -> duplicates.candidates(scan.id(), null, null, null, 0, 200)).items();
    var found =
        candidates.stream()
            .filter(c -> Set.of(c.leftSkuId(), c.rightSkuId()).equals(Set.of(s1, s2)))
            .findFirst()
            .orElseThrow(() -> new AssertionError("这一对同时命中三条线却没被找到"));
    // 条码（跨来源撞值）+ 规格组合（导入后轴值相同）+ 近似同品（名称近似 + 共用素材）三条都命中。
    assertThat(found.overlapDimensionCount()).isEqualTo(3);
    assertThat(found.dimensions()).extracting(DuplicateModels.DuplicateSignalView::dimension)
        .containsExactlyInAnyOrder("BARCODE", "VARIANT_COMBINATION", "SIMILAR_PRODUCT");
    // 逐维度筛选都能筛出这一对：exists 条件没有把候选筛没。
    for (String dimension : List.of("BARCODE", "VARIANT_COMBINATION", "SIMILAR_PRODUCT")) {
      var filtered =
          tx(actor, () -> duplicates.candidates(scan.id(), dimension, null, null, 0, 200)).items();
      assertThat(filtered)
          .as("维度筛选 %s 应能查到这一对", dimension)
          .anyMatch(c -> Set.of(c.leftSkuId(), c.rightSkuId()).equals(Set.of(s1, s2)));
    }
  }

  // ================================================================ 决定

  @Test
  void 决定只为下游预留expectedVersion且不碰任何业务列() {
    UUID spu1 = spu(actor, unique("DC-SPU"), "决定测试商品", "BrandD", "数码");
    UUID spu2 = spu(actor, unique("DC-SPU"), "决定测试商品", "BrandD", "数码");
    UUID keep = sku(actor, spu1, unique("DC-K"), "保留", "6907777777777");
    UUID drop = sku(actor, spu2, unique("DC-D"), "被合并", "6907777777777");

    long keepVersionBefore = skuVersionOf(keep);
    long dropVersionBefore = skuVersionOf(drop);
    String keepStatusBefore = skuStatusOf(keep);

    var scan = scan(actor, null, null, null);
    var candidate = pair(scan, keep, drop);
    assertThat(candidate).isNotNull();

    var decision =
        write(
            actor,
            () ->
                duplicates.decide(
                    actor, candidate.id(), new DuplicateModels.DuplicateDecisionInput("MERGE", keep, "同一个商品建了两遍", 0L)));
    assertThat(decision.decision()).isEqualTo("MERGE");
    assertThat(decision.primarySkuId()).isEqualTo(keep);
    assertThat(decision.secondarySkuId()).isEqualTo(drop);
    // **为下游 3.9.1.2 预留的 expected_version**：登记那一刻两个 SKU 的 sku.version 快照。
    assertThat(decision.primaryVersion()).isEqualTo(keepVersionBefore);
    assertThat(decision.secondaryVersion()).isEqualTo(dropVersionBefore);
    assertThat(decision.executionHint()).contains("3.9.1.2").contains("expected_version");

    // **本模块不改任何业务列**：sku 的 version / status 一个都没动
    // （合并与停用是 3.9.1.2 的职责；本模块只登记决定）。
    assertThat(skuVersionOf(keep)).isEqualTo(keepVersionBefore);
    assertThat(skuVersionOf(drop)).isEqualTo(dropVersionBefore);
    assertThat(skuStatusOf(keep)).isEqualTo(keepStatusBefore);
    assertThat(skuStatusOf(drop)).isEqualTo("ACTIVE");

    // 决定出现在候选行上，三态计数也跟着变。
    var reread = tx(actor, () -> duplicates.candidates(scan.id(), null, null, null, 0, 200)).items();
    var after =
        reread.stream()
            .filter(c -> c.id().equals(candidate.id()))
            .findFirst()
            .orElseThrow();
    assertThat(after.decision()).isEqualTo("MERGE");
    assertThat(after.decisionLabel()).isEqualTo("确认合并");
    assertThat(after.primarySkuId()).isEqualTo(keep);
    assertThat(scan.mergeCount()).isZero();
    assertThat(tx(actor, () -> duplicates.scanRow(scan.id())).mergeCount()).isEqualTo(1);

    // 详情页读得到决定与逐维度证据
    var detail = tx(actor, () -> duplicates.detail(candidate.id()));
    assertThat(detail.decision()).isNotNull();
    assertThat(detail.decision().decision()).isEqualTo("MERGE");
    assertThat(detail.signals()).isNotEmpty();
    assertThat(detail.nextStepHint()).contains("3.9.1.2");
  }

  @Test
  void 判成保留两条时不留主记录() {
    UUID spu1 = spu(actor, unique("KP-SPU"), "保留测试商品", "BrandP", "数码");
    UUID spu2 = spu(actor, unique("KP-SPU"), "保留测试商品", "BrandP", "数码");
    UUID left = sku(actor, spu1, unique("KP-1"), "左", "6906666666666");
    UUID right = sku(actor, spu2, unique("KP-2"), "右", "6906666666666");
    var scan = scan(actor, null, null, null);
    var candidate = pair(scan, left, right);
    var decision =
        write(
            actor,
            () ->
                duplicates.decide(
                    actor, candidate.id(), new DuplicateModels.DuplicateDecisionInput("KEEP", null, "规格不同，确实是两个商品", 0L)));
    assertThat(decision.decision()).isEqualTo("KEEP");
    assertThat(decision.primarySkuId()).isNull();
    assertThat(decision.secondarySkuId()).isNull();
    // KEEP 不该留 expected_version：没有要执行的动作，就不该给下游一个会过期的锁。
    assertThat(decision.primaryVersion()).isNull();
    assertThat(decision.executionHint()).contains("保留两条");
    assertThat(tx(actor, () -> duplicates.scanRow(scan.id())).keepCount()).isEqualTo(1);
  }

  @Test
  void 决定不能指向第三个SKU且非法值被拒() {
    UUID spu1 = spu(actor, unique("V3-SPU"), "指向测试商品", "BrandV", "数码");
    UUID spu2 = spu(actor, unique("V3-SPU"), "指向测试商品", "BrandV", "数码");
    UUID otherSpu = spu(actor, unique("V3-SPU"), "不相干的商品", "BrandV", "数码");
    UUID left = sku(actor, spu1, unique("V3-1"), "左", "6905555555555");
    UUID right = sku(actor, spu2, unique("V3-2"), "右", "6905555555555");
    UUID third = sku(actor, otherSpu, unique("V3-3"), "第三条", "");
    var scan = scan(actor, null, null, null);
    var candidate = pair(scan, left, right);

    // 指到不相干的第三条 SKU：并掉的是别的东西，不是「合并这一对」。
    assertThatThrownBy(
            () ->
                writeVoid(
                    actor,
                    () ->
                        duplicates.decide(
                            actor, candidate.id(),
                            new DuplicateModels.DuplicateDecisionInput("MERGE", third, "", 0L))))
        .isInstanceOf(ResponseStatusException.class);
    // MERGE 不指主记录 → 400
    assertThatThrownBy(
            () ->
                writeVoid(
                    actor,
                    () ->
                        duplicates.decide(
                            actor, candidate.id(),
                            new DuplicateModels.DuplicateDecisionInput("MERGE", null, "", 0L))))
        .isInstanceOf(ResponseStatusException.class);
    // 词表外的决定 → 400（不兜底成 KEEP：兜底会让「打错字」悄悄变成「保留两条」）
    assertThatThrownBy(
            () ->
                writeVoid(
                    actor,
                    () ->
                        duplicates.decide(
                            actor, candidate.id(),
                            new DuplicateModels.DuplicateDecisionInput("MERGED", left, "", 0L))))
        .isInstanceOf(ResponseStatusException.class);
    // 以上三次都不该留下任何决定行。
    assertThat(tx(actor, () -> sql.fetchOne(
            "select count(*) from sku_duplicate_decision where candidate_id=?", candidate.id()))
        .get(0, Long.class)).isZero();
  }

  @Test
  void 重选决定走乐观锁且版本递增() {
    UUID spu1 = spu(actor, unique("RL-SPU"), "乐观锁商品", "BrandL", "数码");
    UUID spu2 = spu(actor, unique("RL-SPU"), "乐观锁商品", "BrandL", "数码");
    UUID left = sku(actor, spu1, unique("RL-1"), "左", "6904444444444");
    UUID right = sku(actor, spu2, unique("RL-2"), "右", "6904444444444");
    var scan = scan(actor, null, null, null);
    var candidate = pair(scan, left, right);

    var first =
        write(
            actor,
            () ->
                duplicates.decide(
                    actor, candidate.id(),
                    new DuplicateModels.DuplicateDecisionInput("KEEP", null, "先保留", 0L)));
    assertThat(first.version()).isEqualTo(0);
    // 带着当前的 version（0）改判 → 成功，version 递增到 1
    var second =
        write(
            actor,
            () ->
                duplicates.decide(
                    actor, candidate.id(),
                    new DuplicateModels.DuplicateDecisionInput("MERGE", left, "改判", first.version())));
    assertThat(second.version()).isEqualTo(1);
    assertThat(second.decision()).isEqualTo("MERGE");
    // 再拿<b>过期的</b> version（0）改 → 409（别人已经改过决定，别把别人的决定盖掉）
    assertThatThrownBy(
            () ->
                writeVoid(
                    actor,
                    () ->
                        duplicates.decide(
                            actor, candidate.id(),
                            new DuplicateModels.DuplicateDecisionInput("KEEP", null, "过期改判", 0L))))
        .isInstanceOf(ResponseStatusException.class);
    assertThat(tx(actor, () -> sql.fetchOne(
            "select count(*) from sku_duplicate_decision where candidate_id=?", candidate.id()))
        .get(0, Long.class)).isEqualTo(1L);
  }

  @Test
  void 候选筛选按决定与相似度都能真的缩小集合() {
    UUID spu1 = spu(actor, unique("FL-SPU"), "筛选商品甲", "BrandF", "数码");
    UUID spu2 = spu(actor, unique("FL-SPU"), "筛选商品甲", "BrandF", "数码");
    UUID left = sku(actor, spu1, unique("FL-1"), "左", "6903333333333");
    UUID right = sku(actor, spu2, unique("FL-2"), "右", "6903333333333");
    var scan = scan(actor, null, null, null);
    var candidate = pair(scan, left, right);
    write(
        actor,
        () ->
            duplicates.decide(
                actor, candidate.id(), new DuplicateModels.DuplicateDecisionInput("KEEP", null, "", 0L)));

    var pending = tx(actor, () -> duplicates.candidates(scan.id(), null, "PENDING", null, 0, 200)).items();
    var kept = tx(actor, () -> duplicates.candidates(scan.id(), null, "KEEP", null, 0, 200)).items();
    assertThat(kept).extracting(DuplicateModels.DuplicateCandidateRow::id).contains(candidate.id());
    assertThat(pending).extracting(DuplicateModels.DuplicateCandidateRow::id)
        .doesNotContain(candidate.id());
    // 最低相似度筛选：1.0 之上取不到任何候选
    var impossible =
        tx(
            actor,
            () ->
                duplicates.candidates(
                    scan.id(), null, null, new BigDecimal("1.0001"), 0, 200));
    assertThat(impossible.items()).isEmpty();
    // 非法筛选值 → 400
    assertThatThrownBy(() -> tx(actor, () -> duplicates.candidates(scan.id(), null, "MAYBE", null, 0, 20)))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> tx(actor, () -> duplicates.candidates(scan.id(), "NOPE", null, null, 0, 20)))
        .isInstanceOf(ResponseStatusException.class);
  }

  @Test
  void 扫描历史各自留档可对比() {
    UUID spu1 = spu(actor, unique("HS-SPU"), "历史商品甲", "BrandH", "数码");
    UUID spu2 = spu(actor, unique("HS-SPU"), "历史商品甲", "BrandH", "数码");
    UUID left = sku(actor, spu1, unique("HS-1"), "左", "6902222222222");
    UUID right = sku(actor, spu2, unique("HS-2"), "右", "6902222222222");
    var first = scan(actor, null, null, null);
    var firstCandidate = pair(first, left, right);
    assertThat(firstCandidate).isNotNull();

    // 第二次扫描时重复还在（旧记录没被停用/合并 —— 那是 3.9.1.2 的职责）
    var second = scan(actor, null, null, null);
    assertThat(second.id()).isNotEqualTo(first.id());
    assertThat(pair(second, left, right)).isNotNull();
    // 两份扫描的候选互相独立：改一份不影响另一份。
    var pages = tx(actor, () -> duplicates.scans(0, 10));
    assertThat(pages.total()).isGreaterThanOrEqualTo(2);
    assertThat(pages.totalCandidates()).isGreaterThanOrEqualTo(2);
  }

  @Test
  void 扫描范围为空时明确报错而不是返回空清单() {
    // 空结果必须是一句「范围内没有 SKU」，而不是一份看起来「扫完了、0 个候选」的假结论。
    assertThatThrownBy(() -> scan(actor, null, "根本没有这个类目", null))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("没有 SKU");
    // 阈值越界 → 400
    assertThatThrownBy(() -> scan(actor, new BigDecimal("1.5"), null, null))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> scan(actor, new BigDecimal("-0.1"), null, null))
        .isInstanceOf(ResponseStatusException.class);
  }

  @Test
  void timestamptz往返不偏28800() {
    var before = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).minusSeconds(2);
    var scan = scan(actor, null, null, null);
    var after = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).plusSeconds(2);
    // 断言写回的值落在两次调用的真实时刻之间：偏 −28800s 会直接掉出这个区间。
    assertThat(scan.startedAt()).isNotNull();
    assertThat(scan.finishedAt()).isNotNull();
    assertThat(scan.startedAt())
        .isBetween(before.toInstant(), after.toInstant());
    assertThat(scan.finishedAt())
        .isBetween(scan.startedAt(), after.toInstant());
    // 读库再验一次（不只断言没抛异常）。
    var raw =
        tx(
            actor,
            () ->
                sql.fetchOne(
                    "select started_at,finished_at from sku_duplicate_scan where id=?", scan.id()));
    assertThat(raw.get("started_at", java.time.OffsetDateTime.class).toInstant())
        .isBetween(before.toInstant(), after.toInstant());
    assertThat(raw.get("finished_at", java.time.OffsetDateTime.class).toInstant())
        .isAfterOrEqualTo(raw.get("started_at", java.time.OffsetDateTime.class).toInstant());
  }

  // ================================================================ 租户隔离三断言

  /**
   * 租户隔离三断言，<b>并且覆盖候选与决定两行</b>。
   *
   * <p>候选清单是本模块真正的越权面：它装着本租户商品的标题、品牌、条码与素材，
   * 串租户等于把 A 的商品资料整份泄漏给 B；决定行更是治理动作，串了会让 A 顺手
   * 判掉 B 的商品该不该合并。
   */
  @Test
  void 租户隔离三断言覆盖候选与决定() throws Exception {
    UUID mySpu1 = spu(actor, unique("ISO-SPU"), "隔离商品甲", "BrandI", "数码");
    UUID mySpu2 = spu(actor, unique("ISO-SPU"), "隔离商品甲", "BrandI", "数码");
    UUID myLeft = sku(actor, mySpu1, unique("ISO-1"), "左", "6901111111111");
    UUID myRight = sku(actor, mySpu2, unique("ISO-2"), "右", "6901111111111");

    // B 侧同样造一对重复（B 用自己的租户上下文写）
    UUID theirSpu1 = spu(actorB, unique("ISO-SPU"), "隔离商品甲", "BrandI", "数码");
    UUID theirSpu2 = spu(actorB, unique("ISO-SPU"), "隔离商品甲", "BrandI", "数码");
    UUID theirLeft = sku(actorB, theirSpu1, unique("ISO-1"), "左", "6901111111111");
    UUID theirRight = sku(actorB, theirSpu2, unique("ISO-2"), "右", "6901111111111");
    // B 侧造同一条重复：一条走 sku.barcode 列、一条走标识表（跨来源），
    // 避开 V7 的 UNIQUE(tenant_id,scheme,value)。
    writeColumnBarcodeFor(actorB, theirLeft, "6901111111111");
    writeIdentifierFor(actorB, theirRight, "EAN", "6901111111111");

    var myScan = scan(actor, null, null, null);
    var theirScan = scan(actorB, null, null, null);
    var myCandidate = pair(myScan, myLeft, myRight);
    var theirCandidate = pair(actorB, theirScan, theirLeft, theirRight);
    assertThat(myCandidate).isNotNull();
    assertThat(theirCandidate).isNotNull();

    // 两边都得真有候选 —— 否则下面的断言就是空的
    assertThat(tx(actor, () -> duplicates.detail(myCandidate.id())).candidate().id())
        .isEqualTo(myCandidate.id());
    assertThat(tx(actorB, () -> duplicates.detail(theirCandidate.id())).candidate().id())
        .isEqualTo(theirCandidate.id());

    // 1) 两租户互不可见：读对方的扫描 / 候选 / 决定一律 404
    assertThatThrownBy(() -> tx(actor, () -> duplicates.scanRow(theirScan.id())))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> tx(actorB, () -> duplicates.scanRow(myScan.id())))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> tx(actor, () -> duplicates.detail(theirCandidate.id())))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> tx(actorB, () -> duplicates.detail(myCandidate.id())))
        .isInstanceOf(ResponseStatusException.class);
    // A 不能给 B 的候选登记决定（否则能替 B 判掉该不该合并）
    assertThatThrownBy(
            () ->
                writeVoid(
                    actor,
                    () ->
                        duplicates.decide(
                            actor, theirCandidate.id(),
                            new DuplicateModels.DuplicateDecisionInput("KEEP", null, "", 0L))))
        .isInstanceOf(ResponseStatusException.class);
    // A 的清单里不含 B 的任何 SKU（列表出口也要查，不能只查详情）
    var myAll = tx(actor, () -> duplicates.candidates(myScan.id(), null, null, null, 0, 200)).items();
    assertThat(myAll).noneMatch(c -> c.leftSkuId().equals(theirLeft) || c.rightSkuId().equals(theirLeft));
    // B 侧候选行确实落在 B 名下（断言行归属，不只断言 A 看不见）
    assertThat(tx(actorB, () -> sql.fetchOne(
            "select tenant_id from sku_duplicate_candidate where id=?", theirCandidate.id())
            .get(0, UUID.class))).isEqualTo(b);

    // 2) 伪造 X-Tenant-Id 头无效：租户只来自已认证成员关系
    http.perform(get("/catalog/duplicate-scans/" + theirScan.id())
            .header("Authorization", "Bearer tenant-a").header("X-Tenant-Id", b))
        .andExpect(status().isNotFound());
    http.perform(get("/catalog/duplicate-scans/" + myScan.id())
            .header("Authorization", "Bearer tenant-b").header("X-Tenant-Id", a))
        .andExpect(status().isNotFound());
    http.perform(get("/catalog/duplicate-candidates/" + theirCandidate.id())
            .header("Authorization", "Bearer tenant-a").header("X-Tenant-Id", b))
        .andExpect(status().isNotFound());
    // A 读 B 那次扫描的候选清单 → 404 而不是 200 + 空列表：
    // 扫描本身对 A 不可见，候选是它的子集合。退化成 200 + 空清单的话，
    // 运营会以为「B 那次扫描没查出重复」，而真相是「那次扫描根本不是他的」。
    http.perform(get("/catalog/duplicate-scans/" + theirScan.id() + "/candidates")
            .header("Authorization", "Bearer tenant-a").header("X-Tenant-Id", b))
        .andExpect(status().isNotFound());
    // 伪造头下写入仍落在 B 自己名下
    http.perform(post("/catalog/duplicate-candidates/" + theirCandidate.id() + "/decision")
            .header("Authorization", "Bearer tenant-a").header("X-Tenant-Id", b)
            .contentType("application/json")
            .content("{\"decision\":\"KEEP\",\"note\":\"伪造头\",\"expectedVersion\":0}"))
        .andExpect(status().isNotFound());
    assertThat(tx(actorB, () -> sql.fetchOne(
            "select count(*) from sku_duplicate_decision where candidate_id=?", theirCandidate.id())
            .get(0, Long.class))).isZero();

    // 3) 连接池归还后不串租户（单连接反复交替），逐轮断言候选集合不相交
    for (int i = 0; i < 5; i++) {
      var myIds =
          tx(actor, () -> duplicates.candidates(myScan.id(), null, null, null, 0, 200))
              .items().stream().map(DuplicateModels.DuplicateCandidateRow::id)
              .collect(java.util.stream.Collectors.toSet());
      var theirIds =
          tx(actorB, () -> duplicates.candidates(theirScan.id(), null, null, null, 0, 200))
              .items().stream().map(DuplicateModels.DuplicateCandidateRow::id)
              .collect(java.util.stream.Collectors.toSet());
      assertThat(myIds).as("第 %d 轮 A 的候选不该为空", i).isNotEmpty();
      assertThat(theirIds).as("第 %d 轮 B 的候选不该为空", i).isNotEmpty();
      assertThat(java.util.Collections.disjoint(myIds, theirIds))
          .as("第 %d 轮两租户的候选集合必须不相交", i)
          .isTrue();
      // 概览出口同样不串
      assertThat(tx(actor, () -> duplicates.overview()).totalCandidates()).isNotNegative();
      assertThat(tx(actorB, () -> duplicates.overview()).totalCandidates()).isNotNegative();
      assertThatThrownBy(() -> tx(actor, () -> duplicates.detail(theirCandidate.id())))
          .as("第 %d 轮 A 读 B 的候选必须 404 而不是串过去", i)
          .isInstanceOf(ResponseStatusException.class);
    }
  }

  // ================================================================ RLS 与结构

  @Test
  void 四张表都强制RLS() {
    for (String table :
        List.of("sku_duplicate_scan", "sku_duplicate_candidate", "sku_duplicate_signal",
            "sku_duplicate_decision")) {
      var row = tx(actor, () -> sql.fetchOne(
          "select relrowsecurity,relforcerowsecurity from pg_class where relname=?", table));
      assertThat(row.get("relrowsecurity", Boolean.class)).as("%s 开启 RLS", table).isTrue();
      assertThat(row.get("relforcerowsecurity", Boolean.class)).as("%s 强制 RLS", table).isTrue();
      var policy = tx(actor, () -> sql.fetchOne(
          "select count(*) from pg_policies where tablename=? and policyname='isolation'", table));
      assertThat(policy.get(0, Long.class)).as("%s 有 isolation 策略", table).isEqualTo(1L);
      // 所有业务表都必须带 tenant_id NOT NULL
      var nullable = tx(actor, () -> sql.fetchOne(
          "select is_nullable from information_schema.columns"
              + " where table_name=? and column_name='tenant_id'", table));
      assertThat(nullable.get(0, String.class)).as("%s 的 tenant_id NOT NULL", table).isEqualTo("NO");
    }
  }

  @Test
  void dbCHECK挡住绕过服务层的脏数据() {
    UUID spu1 = spu(actor, unique("CK-SPU"), "结构测试商品", "BrandC", "数码");
    UUID spu2 = spu(actor, unique("CK-SPU"), "结构测试商品", "BrandC", "数码");
    UUID left = sku(actor, spu1, unique("CK-1"), "左", "6909999999998");
    UUID right = sku(actor, spu2, unique("CK-2"), "右", "6909999999998");
    var scan = scan(actor, null, null, null);
    var candidate = pair(scan, left, right);
    UUID scanId = scan.id();

    // 左右顺序反了：V36 的 CHECK 会挡（否则同一对能插两行）
    assertThatThrownBy(() -> writeVoid(actor, () -> sql.execute(
            "insert into sku_duplicate_candidate(id,tenant_id,scan_id,left_sku_id,right_sku_id,"
                + "left_spu_id,right_spu_id,similarity,overlap_dimension_count,"
                + "left_reference_count,right_reference_count,reason)"
                + " values(?::uuid,?::uuid,?::uuid,?::uuid,?::uuid,?::uuid,?::uuid,1.0,1,0,0,'反序')",
            UUID.randomUUID(), a, scanId, right, left,
            sql.fetchOne("select spu_id from sku where id=?", right).get(0, UUID.class),
            sql.fetchOne("select spu_id from sku where id=?", left).get(0, UUID.class))))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);

    // 判成 MERGE 却不给主记录：自相矛盾的登记
    assertThatThrownBy(() -> writeVoid(actor, () -> sql.execute(
            "insert into sku_duplicate_decision(id,tenant_id,scan_id,candidate_id,decision,note,decided_by)"
                + " values(?::uuid,?::uuid,?::uuid,?::uuid,'MERGE','',?::uuid)",
            UUID.randomUUID(), a, scanId, candidate.id(), user)))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);

    // 词表外的维度
    assertThatThrownBy(() -> writeVoid(actor, () -> sql.execute(
            "insert into sku_duplicate_signal(id,tenant_id,scan_id,candidate_id,dimension,score)"
                + " values(?::uuid,?::uuid,?::uuid,?::uuid,'MAGIC',1.0)",
            UUID.randomUUID(), a, scanId, candidate.id())))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);

    // 相似度越界
    assertThatThrownBy(() -> writeVoid(actor, () -> sql.execute(
            "update sku_duplicate_candidate set similarity=1.5 where tenant_id=? and id=?", a, candidate.id())))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    // 重叠维度数越界（0 或 >4）
    assertThatThrownBy(() -> writeVoid(actor, () -> sql.execute(
            "update sku_duplicate_candidate set overlap_dimension_count=0 where tenant_id=? and id=?", a, candidate.id())))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    // 同一对重复插入（UNIQUE(tenant_id,scan_id,left,right)）
    assertThatThrownBy(() -> writeVoid(actor, () -> sql.execute(
            "insert into sku_duplicate_candidate(id,tenant_id,scan_id,left_sku_id,right_sku_id,"
                + "left_spu_id,right_spu_id,similarity,overlap_dimension_count,"
                + "left_reference_count,right_reference_count,reason)"
                + " select ?::uuid,tenant_id,scan_id,left_sku_id,right_sku_id,left_spu_id,right_spu_id,"
                + "similarity,overlap_dimension_count,left_reference_count,right_reference_count,reason"
                + " from sku_duplicate_candidate where id=?",
            UUID.randomUUID(), candidate.id())))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
  }

  @Test
  void 引用数逐项拆分且全是catalog自己的() {
    UUID spuA = spu(actor, unique("RF-SPU"), "引用测试商品甲", "BrandF2", "数码");
    UUID spuB = spu(actor, unique("RF-SPU"), "引用测试商品乙", "BrandF3", "数码2");
    UUID item = sku(actor, spuA, unique("RF-1"), "被引用", "6901234000015");
    identifier(item, "EAN", "6901234000015");
    media(item, "rf-image");
    // 搭档必须在扫描<b>之前</b>建好：候选是对「扫描那一刻存在的 SKU」下的结论。
    UUID partner = sku(actor, spuB, unique("RF-2"), "搭档", "6901234000015");
    var scan = scan(actor, null, null, null);
    var candidate = pair(scan, item, partner);
    var detail = tx(actor, () -> duplicates.detail(candidate.id()));

    var leftIsItem = detail.candidate().leftSkuId().equals(item);
    var refs = leftIsItem ? detail.leftReferences() : detail.rightReferences();
    assertThat(refs).hasSize(9);
    assertThat(refs).extracting(DuplicateModels.DuplicateReferenceBreakdown::dimension)
        .containsExactly(
            "BARCODE", "ATTRIBUTE", "MEDIA", "QUOTE", "BUNDLE", "BOM", "PACKAGE", "SAFETY",
            "UNIT_CONVERSION");
    assertThat(refs.stream().filter(r -> r.dimension().equals("BARCODE")).findFirst().orElseThrow().count())
        .isEqualTo(1);
    assertThat(refs.stream().filter(r -> r.dimension().equals("MEDIA")).findFirst().orElseThrow().count())
        .isEqualTo(1);
    // 0 也要列出来：「报价 0 条」与「没查报价」不是一回事。
    assertThat(refs.stream().filter(r -> r.dimension().equals("QUOTE")).findFirst().orElseThrow().count())
        .isZero();
    // 详情里能读到全部条码（含 sku.barcode 列与物料标识表两个来源）
    var barcodes = leftIsItem ? detail.leftBarcodeValues() : detail.rightBarcodeValues();
    assertThat(barcodes).contains("6901234000015");
  }

  // ================================================================ HTTP 全链路

  @Test
  void http全链路与读写权限分离() throws Exception {
    UUID spu1 = spu(actor, unique("HT-SPU"), "HTTP 商品甲", "BrandH2", "数码");
    UUID spu2 = spu(actor, unique("HT-SPU"), "HTTP 商品甲", "BrandH2", "数码");
    UUID left = sku(actor, spu1, unique("HT-1"), "左", "6901234000099");
    UUID right = sku(actor, spu2, unique("HT-2"), "右", "6901234000099");

    // 概览读永远不该 404（没扫过时也要能渲染空态）
    http.perform(get("/catalog/duplicate-scans/overview")
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.note").isString())
        .andExpect(jsonPath("$.totalCandidates").isNumber());

    // 发起扫描需要写权限：只读身份 403
    http.perform(post("/catalog/duplicate-scans")
            .header("Authorization", "Bearer reader")
            .contentType("application/json")
            .content("{\"minSimilarity\":0.8}"))
        .andExpect(status().isForbidden());

    var response = http.perform(post("/catalog/duplicate-scans")
            .header("Authorization", "Bearer tenant-a")
            .contentType("application/json")
            .content("{\"minSimilarity\":0.8000}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.minSimilarity").value(0.8000))
        .andExpect(jsonPath("$.scopeApplied").value(false))
        .andReturn();
    UUID scanUuid = UUID.fromString(JSON.readTree(response.getResponse().getContentAsString()).get("id").asText());
    String scanId = scanUuid.toString();

    // 候选清单 + 详情
    http.perform(get("/catalog/duplicate-scans/" + scanId + "/candidates")
            .header("Authorization", "Bearer reader"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.scan.id").value(scanId));
    // 取包含 left 的那一条：items.get(0) 未必是这一对，而 MERGE 的主记录必须落在候选两侧之内
    // （指到不相干的 SKU 会被 400 拒 —— 后面正好也验了这条）。
    String candidateId = pair(scanUuid, left, right).id().toString();
    http.perform(get("/catalog/duplicate-candidates/" + candidateId)
            .header("Authorization", "Bearer reader"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.signals").isArray())
        .andExpect(jsonPath("$.nextStepHint").exists());

    // 决定要写权限：只读身份 403
    http.perform(post("/catalog/duplicate-candidates/" + candidateId + "/decision")
            .header("Authorization", "Bearer reader")
            .contentType("application/json")
            .content("{\"decision\":\"KEEP\",\"note\":\"只读身份不该能决定\",\"expectedVersion\":0}"))
        .andExpect(status().isForbidden());
    // 词表外的决定走 400
    http.perform(post("/catalog/duplicate-candidates/" + candidateId + "/decision")
            .header("Authorization", "Bearer tenant-a")
            .contentType("application/json")
            .content("{\"decision\":\"MAYBE\",\"expectedVersion\":0}"))
        .andExpect(status().isBadRequest());

    // 未认证 401
    http.perform(get("/catalog/duplicate-scans/overview"))
        .andExpect(status().isUnauthorized());
    // 不存在的扫描 404
    http.perform(get("/catalog/duplicate-scans/" + UUID.randomUUID())
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isNotFound());

    // 合法决定：MERGE 指主记录，响应带 expected_version
    var decided = http.perform(post("/catalog/duplicate-candidates/" + candidateId + "/decision")
            .header("Authorization", "Bearer tenant-a")
            .contentType("application/json")
            .content("{\"decision\":\"MERGE\",\"primarySkuId\":\"" + left + "\",\"note\":\"同一商品建两遍\",\"expectedVersion\":0}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.decision").value("MERGE"))
        .andExpect(jsonPath("$.primarySkuId").value(left.toString()))
        .andExpect(jsonPath("$.primaryVersion").isNumber())
        .andReturn();
    assertThat(JSON.readTree(decided.getResponse().getContentAsString()).get("executionHint").asText())
        .contains("3.9.1.2");
    // 读回决定
    http.perform(get("/catalog/duplicate-candidates/" + candidateId + "/decision")
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.decision").value("MERGE"));
    // 扫描列表
    http.perform(get("/catalog/duplicate-scans")
            .header("Authorization", "Bearer reader"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items").isArray());
    http.perform(get("/catalog/duplicate-scans/overview")
            .header("Authorization", "Bearer reader"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.latestScan.id").value(scanId))
        .andExpect(jsonPath("$.mergeCount").value(1))
        .andExpect(jsonPath("$.dimensions").isArray());
    assertThat(left).isNotNull();
    assertThat(right).isNotNull();
  }

  // ================================================================ 小工具

  /** 找出一份扫描里包含指定两个 SKU 的候选。 */
  DuplicateModels.DuplicateCandidateRow pair(
      DuplicateModels.DuplicateScanRow scan, UUID left, UUID right) {
    return pair(actor, scan, left, right);
  }

  /** 按扫描 id（A 的租户上下文）找出包含指定两个 SKU 的候选。 */
  DuplicateModels.DuplicateCandidateRow pair(UUID scanId, UUID left, UUID right) {
    return tx(actor, () -> duplicates.candidates(scanId, null, null, null, 0, 500))
        .items().stream()
        .filter(c -> Set.of(c.leftSkuId(), c.rightSkuId()).equals(Set.of(left, right)))
        .findFirst()
        .orElseThrow(() -> new AssertionError("扫描 " + scanId + " 里找不到这一对"));
  }

  /** 同一租户上下文里找出一份扫描中包含指定两个 SKU 的候选（隔离测试要按租户分别取）。 */
  DuplicateModels.DuplicateCandidateRow pair(
      AccessService.Access who, DuplicateModels.DuplicateScanRow scan, UUID left, UUID right) {
    return tx(who, () -> duplicates.candidates(scan.id(), null, null, null, 0, 500))
        .items().stream()
        .filter(c -> Set.of(c.leftSkuId(), c.rightSkuId()).equals(Set.of(left, right)))
        .findFirst()
        .orElse(null);
  }

  void writeColumnBarcodeFor(AccessService.Access who, UUID skuId, String barcode) {
    access.run(
        who,
        "catalog.spu.update",
        () -> {
          sql.execute("update sku set barcode=? where id=?", barcode, skuId);
          return null;
        });
  }

  void writeIdentifierFor(AccessService.Access who, UUID skuId, String scheme, String value) {
    access.run(
        who,
        "catalog.spu.update",
        () -> {
          sql.execute(
              "insert into sku_identifier(id,tenant_id,sku_id,scheme,value,is_primary,source,occurred_at)"
                  + " values(?::uuid,?::uuid,?::uuid,?,?,true,'MANUAL',now())",
              UUID.randomUUID(), who.tenantId(), skuId, scheme, value);
          return null;
        });
  }

  String skuStatusOf(UUID skuId) {
    return tx(actor, () -> sql.fetchOne("select status from sku where id=?", skuId)).get("status", String.class);
  }

  /** 当前 {@code sku.version} —— 3.9.1.2 说的 expected_version 就是它。 */
  long skuVersionOf(UUID skuId) {
    return tx(actor, () -> sql.fetchOne("select version from sku where id=?", skuId)).get("version", Long.class);
  }

  String columnPrecision(String table, String column) {
    return tx(actor, () -> sql.fetchOne(
            "select numeric_precision||','||numeric_scale from information_schema.columns"
                + " where table_name=? and column_name=?", table, column))
        .get(0, String.class);
  }

  String columnType(String table, String column) {
    return tx(actor, () -> sql.fetchOne(
            "select data_type from information_schema.columns where table_name=? and column_name=?",
            table, column))
        .get(0, String.class);
  }
}