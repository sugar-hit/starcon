package com.starcon.catalog;

import static com.starcon.catalog.DuplicateModels.*;
import static com.starcon.catalog.DuplicateRules.*;

import com.starcon.tenancy.Ids;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 3.9.1.1 重复 SKU 识别：扫描全量商品与 SKU，产出重复候选清单，并登记人工的合并/保留决定。
 *
 * <p>蓝图原文：「职责：识别重复/近重复商品：同 SPU 下重复规格组合、同商品不同 SPU 重复建档、 同条码多 SKU、近似标题+相似图片疑似同品，输出候选与相似度供人工确认。」
 * 「输入：商品与 SKU 全量；条码、标题、属性、图片特征。」 「输出：重复候选清单（相似度、重叠维度、影响引用数）；确认合并/保留决定。」 「关系：上游 = 3.1.1.1/3.1.2.1；下游
 * = 3.9.1.2。<b>重复 SKU 会污染销量、采购、库存与利润统计</b>。」
 *
 * <p><b>本模块只识别 + 登记决定，不执行合并</b>：合并是 3.9.1.2 的职责， 它执行时要带着本模块登记的 {@code primary_version} / {@code
 * secondary_version} 回来比对 （那就是蓝图说的 {@code expected_version}）。本类<b>不改 sku / spu 的任何一列</b>， 也不碰
 * listing、库存、采购、价格 —— 那些是别的服务的写入所有权（AGENTS.md 模块边界纪律）。
 *
 * <h2>为什么扫描在应用层而不是一条 SQL</h2>
 *
 * <p>相似度是<b>算</b>出来的（Jaccard / 编辑距离 / 集合交并），不是查出来的； 而「把全量 SKU 两两比一遍」在 PG 里只能靠递归 CTE 硬写，既慢又不可审阅。
 * 本类分两步，两步都可审阅：
 *
 * <ol>
 *   <li><b>用显式 SQL 把事实读上来</b>（本类 {@code load*} 方法）：SKU 与其 SPU 的身份与标题/ 品牌/类目、全部条码（{@code
 *       sku.barcode} + V7 物料标识表）、按轴值现算的规格组合、 素材内容哈希、九类 catalog 引用计数。全部走 jOOQ 显式 SQL，不用 JPA 懒加载。
 *   <li><b>判定与打分交给 {@link DuplicateRules} 的纯函数</b>，落库再走显式 SQL。
 * </ol>
 *
 * <h2>分组键是无损的（不会漏检）</h2>
 *
 * <p>两两比较是 O(n²)，所以四条线各自先按<b>判定的前提</b>分组，只在组内比：
 *
 * <ul>
 *   <li>BARCODE 的前提是「归一化条码相等」→ 按条码值分组；
 *   <li>CROSS_SPU 的前提是「归一化标题 + 品牌 + 类目三者全同」→ 按这三者的复合键分组；
 *   <li>SIMILAR_PRODUCT 的前提里就有「至少重合一张图片」→ 按共享素材内容哈希分组 （{@link DuplicateRules#similarProductSignal}
 *       在没有共享哈希时直接返回 null， 所以按哈希分组对这一维<b>不丢任何候选</b>）；
 *   <li>VARIANT_COMBINATION 的前提是「签名相同或轴值有交集」→ 同 SPU 内按共享的 {@code 轴=取值} 分组。Jaccard&gt;0
 *       蕴含至少共享一个轴值，所以也是无损的。
 * </ul>
 *
 * <p>四个前提都是判定的<b>必要条件</b>，所以分组只影响速度，不影响召回 —— 这一条由集成测试「稀疏全量扫描不漏已知重复」逐维钉死。
 *
 * <h2>引用数只算 catalog 自己的</h2>
 *
 * <p>{@link #REFERENCE_QUERIES} 是 catalog 自有的九类引用。04 映射 / 07 库存 / 09 订单的引用
 * 在别的服务的库里，跨库读是模块边界纪律明令禁止的，所以那部分留给 3.9.1.2 走各自授权 API。 逐项拆分而不是只给总数：运营要判断「合并不合并得动」时，需要知道是哪几类引用在挡路
 * （报价有 3 条 vs 素材 3 张，处理成本完全不同）。
 */
@Service
public class DuplicateDetectionService {
  /** 单页最大行数。 */
  static final int MAX_PAGE_SIZE = 100;

  /**
   * 单次扫描的 SKU 上限。
   *
   * <p>超过就<b>明确报错并让用户收窄范围</b>，而不是截断了悄悄扫一半： 一份「看起来扫完了其实只扫了前 20000 个 SKU」的候选清单，比扫不出来糟糕得多 ——
   * 运营会拿它当「已经干净了」的结论。
   */
  static final int MAX_SCAN_SKUS = 20000;

  /**
   * 引用计数的九类分项：{@code {维度, 中文名, 表, 额外条件}}（顺序即页面展示顺序）。
   *
   * <p><b>表名与条件写在一起而不是写两条成品 SQL</b>：分组的（全量聚合）与单 SKU 的 （详情页逐项拆分）两条查询必须永远同源，分开写两份字符串迟早会只改到一处。
   *
   * <p>「额外条件」里的 {@code sku_id is not null} 是必需的：{@code attribute_value} 的 {@code sku_id} 可空（公共属性值不挂
   * SKU），可空列上等值匹配会漏掉它们， 而 {@code media_reference} 的 owner_type 形状约束（V18）同样要求显式限定。
   *
   * <p>九类里<b>没有</b> {@code sku.barcode} 列：V7 的触发器把它同步成主条码的投影， 再数一次会让「条码引用」虚高一倍。条码本身在 {@code
   * BARCODE} 项里按 {@code sku_identifier} 计数。
   */
  private static final List<String[]> REFERENCE_QUERIES =
      List.of(
          new String[] {"BARCODE", "条码与物料标识", "sku_identifier", "1=1"},
          new String[] {"ATTRIBUTE", "属性值", "attribute_value", "sku_id is not null"},
          new String[] {
            "MEDIA", "素材引用", "media_reference", "owner_type='SKU' and sku_id is not null"
          },
          new String[] {"QUOTE", "供应商报价", "supplier_quote", "1=1"},
          new String[] {"BUNDLE", "套装组件", "bundle_component", "1=1"},
          new String[] {"BOM", "BOM 组件", "bom_component", "1=1"},
          new String[] {"PACKAGE", "包装参数", "sku_package", "1=1"},
          new String[] {"SAFETY", "安全属性", "sku_safety_attribute", "1=1"},
          new String[] {"UNIT_CONVERSION", "单位换算快照", "sku_unit_conversion_snapshot", "1=1"});

  private final DSLContext sql;

  public DuplicateDetectionService(DSLContext sql) {
    this.sql = sql;
  }

  // ================================================================ 扫描语料（全部显式 SQL）

  /** 一个 SKU 在扫描中需要的全部事实。 */
  static final class SkuFacts {
    UUID skuId;
    UUID spuId;
    String skuCode = "";
    String skuName = "";
    String spuCode = "";
    String spuName = "";
    String brand = "";
    String category = "";

    /** 存在 sku.barcode 列里的条码。 */
    final List<String> columnBarcodes = new ArrayList<>();

    /** V7 物料标识表里的条码（与上一项分开留，详情页要分开展示来源）。 */
    final List<String> identifierBarcodes = new ArrayList<>();

    /** 按轴值现算的规格组合签名；为空 = 一个轴值都解析不出来。 */
    String variantSignature = "";

    /** 挂在本 SKU 上的素材内容哈希（去重）。 */
    final Set<String> mediaHashes = new LinkedHashSet<>();

    /** 九类引用计数，缺项为 0。 */
    final Map<String, Integer> references = new LinkedHashMap<>();

    /** 全部条码（两个来源合起来归一化去重）。 */
    Set<String> allBarcodes() {
      Set<String> out = new LinkedHashSet<>();
      for (String value : identifierBarcodes) {
        String normalized = DuplicateRules.normalizeBarcode(value);
        if (!normalized.isEmpty()) out.add(normalized);
      }
      for (String value : columnBarcodes) {
        String normalized = DuplicateRules.normalizeBarcode(value);
        if (!normalized.isEmpty()) out.add(normalized);
      }
      return out;
    }

    int referenceTotal() {
      return references.values().stream().mapToInt(Integer::intValue).sum();
    }
  }

  /** 读入全量语料（可按类目 / 品牌收窄）。 */
  private List<SkuFacts> loadCorpus(UUID tenantId, String category, String brand) {
    StringBuilder where = new StringBuilder(" where s.tenant_id=?");
    List<Object> binds = new ArrayList<>();
    binds.add(tenantId);
    if (!category.isEmpty()) {
      where.append(" and lower(p.category)=lower(?)");
      binds.add(category);
    }
    if (!brand.isEmpty()) {
      where.append(" and lower(p.brand)=lower(?)");
      binds.add(brand);
    }
    List<Record> rows =
        sql.fetch(
            "select s.id,s.spu_id,s.code as sku_code,s.name as sku_name,s.barcode,"
                + "p.code as spu_code,p.name as spu_name,p.brand,p.category"
                + " from sku s join spu p on p.tenant_id=s.tenant_id and p.id=s.spu_id"
                + where
                + " order by s.id",
            binds.toArray());

    Map<UUID, SkuFacts> facts = new LinkedHashMap<>();
    for (Record r : rows) {
      SkuFacts f = new SkuFacts();
      f.skuId = r.get("id", UUID.class);
      f.spuId = r.get("spu_id", UUID.class);
      f.skuCode = nvl(r.get("sku_code", String.class));
      f.skuName = nvl(r.get("sku_name", String.class));
      f.spuCode = nvl(r.get("spu_code", String.class));
      f.spuName = nvl(r.get("spu_name", String.class));
      f.brand = nvl(r.get("brand", String.class));
      f.category = nvl(r.get("category", String.class));
      String barcode = nvl(r.get("barcode", String.class));
      if (!barcode.isEmpty()) f.columnBarcodes.add(barcode);
      facts.put(f.skuId, f);
    }
    if (facts.isEmpty()) return List.of();

    loadIdentifierBarcodes(tenantId, facts);
    loadVariantCombinations(tenantId, facts);
    loadMediaHashes(tenantId, facts);
    loadReferenceCounts(tenantId, facts);
    return new ArrayList<>(facts.values());
  }

  /**
   * 条码：{@code sku.barcode} 列 + V7 物料标识表。
   *
   * <p>两个来源都要，因为「同一条码被录进两个地方」正是批量导入最常见的错： A 的 {@code sku.barcode} 与 B 的 {@code
   * sku_identifier.value} 同值时， 只看任何一侧都发现不了这个重复。
   */
  private void loadIdentifierBarcodes(UUID tenantId, Map<UUID, SkuFacts> facts) {
    for (Record r :
        sql.fetch(
            "select sku_id,value from sku_identifier where tenant_id=? and btrim(value)<>'' order by sku_id,id",
            tenantId)) {
      SkuFacts f = facts.get(r.get("sku_id", UUID.class));
      if (f != null) f.identifierBarcodes.add(r.get("value", String.class));
    }
  }

  /**
   * 规格组合：<b>按轴值现算</b>，不复用 {@code sku.variant_signature} 列。
   *
   * <p>查询形状与 3.1.3.1 {@code VariantCombinationService} 的「已存在的真实 SKU」来源完全一致 （{@code
   * attribute_value.state='SET'} × {@code attribute_definition.variant_axis} × {@code
   * attribute_value_item.option_value}），这样本模块判出的组合与商品页显示的组合 是同一个东西；拼装也复用 {@link
   * CombinationRules#signature}，不写第二套拼接规则。
   */
  private void loadVariantCombinations(UUID tenantId, Map<UUID, SkuFacts> facts) {
    var parts =
        sql
            .fetch(
                "select v.sku_id,d.code,i.option_value from attribute_value v"
                    + " join attribute_definition d"
                    + "   on d.tenant_id=v.tenant_id and d.id=v.attribute_id and d.variant_axis"
                    + " join attribute_value_item i"
                    + "   on i.tenant_id=v.tenant_id and i.value_id=v.id and i.attribute_id=d.id"
                    + " where v.tenant_id=? and v.sku_id is not null and v.state='SET'"
                    + "   and i.option_value is not null"
                    + " order by v.sku_id,d.code,i.ordinal",
                tenantId)
            .stream()
            .collect(
                java.util.stream.Collectors.groupingBy(
                    r -> r.get("sku_id", UUID.class),
                    java.util.stream.Collectors.mapping(
                        r ->
                            new CombinationRules.Part(
                                r.get("code", String.class), r.get("option_value", String.class)),
                        java.util.stream.Collectors.toList())));
    for (Map.Entry<UUID, List<CombinationRules.Part>> entry : parts.entrySet()) {
      SkuFacts f = facts.get(entry.getKey());
      if (f == null) continue;
      // 解析不出轴值（一个都取不到，或同一条轴重复）时签名会是 null：留空，
      // 该 SKU 不参与规格组合维度，但仍会出现在候选清单与详情里。
      var signature = CombinationRules.signature(entry.getValue(), Set.of());
      f.variantSignature = signature.signature() == null ? "" : signature.signature();
    }
  }

  /** 素材内容哈希：只认 {@code owner_type='SKU'} 的引用（V18 的形状约束保证 sku_id 非空）。 */
  private void loadMediaHashes(UUID tenantId, Map<UUID, SkuFacts> facts) {
    for (Record r :
        sql.fetch(
            "select r.sku_id,a.content_sha256 from media_reference r"
                + " join media_asset a on a.tenant_id=r.tenant_id and a.id=r.asset_id"
                + " where r.tenant_id=? and r.owner_type='SKU' and r.sku_id is not null",
            tenantId)) {
      SkuFacts f = facts.get(r.get("sku_id", UUID.class));
      if (f != null) f.mediaHashes.add(r.get("content_sha256", String.class));
    }
  }

  /** 九类引用计数（逐类一条聚合 SQL，合并进内存，避免 N+1）。 */
  private void loadReferenceCounts(UUID tenantId, Map<UUID, SkuFacts> facts) {
    for (String[] query : REFERENCE_QUERIES) {
      Map<UUID, Integer> counts = new HashMap<>();
      for (Record r :
          sql.fetch(
              "select sku_id,count(*) as c from "
                  + query[2]
                  + " where tenant_id=? and "
                  + query[3]
                  + " group by sku_id",
              tenantId)) counts.put(r.get("sku_id", UUID.class), r.get("c", Integer.class));
      for (Map.Entry<UUID, SkuFacts> entry : facts.entrySet()) {
        Integer count = counts.get(entry.getKey());
        entry.getValue().references.put(query[0], count == null ? 0 : count);
      }
    }
  }

  // ================================================================ 扫描

  /**
   * 扫一次全量（或按类目/品牌收窄的）商品与 SKU，产出候选清单并落库。
   *
   * <p>每一次调用都留下一份<b>独立且不可变</b>的结论，所以「治理之后重新扫一次， 候选数从 87 降到 6」这句话需要两次扫描都在场才能说 —— 这与 V34 校验报告的 「快照 +
   * 过期标记」不同：那边是因为上游预校验会就地覆盖，本模块的候选从不覆盖， 所以不需要过期标记。
   */
  public DuplicateScanRow scan(AccessService.Access actor, DuplicateScanInput input) {
    UUID tenantId = actor.tenantId();
    BigDecimal threshold =
        DuplicateRules.minSimilarity(input == null ? null : input.minSimilarity());
    CatalogService.check(
        threshold.signum() >= 0 && threshold.compareTo(BigDecimal.ONE) <= 0, "相似度阈值必须在 0 到 1 之间");
    String category = trimTo(input == null ? null : input.category());
    String brand = trimTo(input == null ? null : input.brand());
    CatalogService.check(category.length() <= 100, "类目最多 100 字");
    CatalogService.check(brand.length() <= 100, "品牌最多 100 字");

    OffsetDateTime startedAt = OffsetDateTime.now(ZoneOffset.UTC);
    List<SkuFacts> corpus = loadCorpus(tenantId, category, brand);
    CatalogService.check(!corpus.isEmpty(), "扫描范围内没有 SKU：换一个类目或品牌再试，或先建商品");
    CatalogService.check(
        corpus.size() <= MAX_SCAN_SKUS,
        "范围内有 "
            + corpus.size()
            + " 个 SKU，超过单次扫描上限 "
            + MAX_SCAN_SKUS
            + "：请按类目或品牌分片扫描（注意分片会漏掉跨范围的重复）");

    Map<Pair, List<Signal>> hits = detect(corpus, threshold);

    Map<UUID, SkuFacts> byId = new LinkedHashMap<>();
    for (SkuFacts f : corpus) byId.put(f.skuId, f);
    int signalCount = hits.values().stream().mapToInt(List::size).sum();
    int spuCount = (int) corpus.stream().map(f -> f.spuId).distinct().count();

    UUID scanId = Ids.next();
    boolean scopeApplied = !category.isEmpty() || !brand.isEmpty();
    // 时间列走 timestamptz：显式绑定 OffsetDateTime 并在 values 位置写 ?::timestamptz
    // （用 Timestamp.valueOf(LocalDateTime) 会静默偏 −28800s，这是仓库里反复踩过的坑）。
    sql.execute(
        "insert into sku_duplicate_scan(id,tenant_id,min_similarity,scope_category,scope_brand,"
            + "scope_applied,scanned_sku_count,scanned_spu_count,candidate_count,signal_count,"
            + "started_at,finished_at,duration_ms,actor_id)"
            + " values(?::uuid,?::uuid,?::numeric,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz,?,?::uuid)",
        scanId,
        tenantId,
        threshold,
        category,
        brand,
        scopeApplied,
        corpus.size(),
        spuCount,
        hits.size(),
        signalCount,
        startedAt,
        startedAt,
        0L,
        actor.userId());

    // 候选按（相似度倒序、两侧 id）稳定排序后写入，让 id 顺序与「谁该先看」一致。
    List<Map.Entry<Pair, List<Signal>>> ordered = new ArrayList<>(hits.entrySet());
    ordered.sort(
        Comparator.<Map.Entry<Pair, List<Signal>>, BigDecimal>comparing(
                e -> DuplicateRules.candidate(e.getValue()).similarity())
            .reversed()
            .thenComparing(e -> e.getKey().left().toString())
            .thenComparing(e -> e.getKey().right().toString()));

    long durationMs =
        Duration.between(startedAt.toInstant(), OffsetDateTime.now(ZoneOffset.UTC).toInstant())
            .toMillis();
    for (Map.Entry<Pair, List<Signal>> entry : ordered) {
      Pair pair = entry.getKey();
      Candidate candidate = DuplicateRules.candidate(entry.getValue());
      SkuFacts left = byId.get(pair.left());
      SkuFacts right = byId.get(pair.right());
      UUID candidateId = Ids.next();
      sql.execute(
          "insert into sku_duplicate_candidate(id,tenant_id,scan_id,left_sku_id,right_sku_id,"
              + "left_spu_id,right_spu_id,similarity,overlap_dimension_count,"
              + "left_reference_count,right_reference_count,reason,created_at)"
              + " values(?::uuid,?::uuid,?::uuid,?::uuid,?::uuid,?::uuid,?::uuid,?::numeric,?,?,?,?,?::timestamptz)",
          candidateId,
          tenantId,
          scanId,
          pair.left(),
          pair.right(),
          left.spuId,
          right.spuId,
          candidate.similarity(),
          candidate.overlapDimensionCount(),
          left.referenceTotal(),
          right.referenceTotal(),
          candidate.reason(),
          OffsetDateTime.now(ZoneOffset.UTC));
      for (Signal signal : candidate.signals()) {
        sql.execute(
            "insert into sku_duplicate_signal(id,tenant_id,scan_id,candidate_id,dimension,score,evidence,created_at)"
                + " values(?::uuid,?::uuid,?::uuid,?::uuid,?,?::numeric,?,?::timestamptz)",
            Ids.next(),
            tenantId,
            scanId,
            candidateId,
            signal.dimension().name(),
            signal.score(),
            signal.evidence(),
            OffsetDateTime.now(ZoneOffset.UTC));
      }
    }
    sql.execute(
        "update sku_duplicate_scan set duration_ms=? where tenant_id=? and id=?",
        durationMs,
        tenantId,
        scanId);
    audit(actor, scanId, "SkuDuplicateScanRun");
    return scanRow(scanId);
  }

  /** 一对 SKU，按 uuid 字符串升序规范化（与 V36 的 CHECK 同序）。 */
  record Pair(UUID left, UUID right) {
    static Pair of(UUID a, UUID b) {
      return a.toString().compareTo(b.toString()) <= 0 ? new Pair(a, b) : new Pair(b, a);
    }
  }

  /** 四条线的分组判定。分组键是各维度的<b>必要条件</b>，所以只影响速度不影响召回。 */
  private Map<Pair, List<Signal>> detect(List<SkuFacts> corpus, BigDecimal threshold) {
    Map<Pair, List<Signal>> hits = new LinkedHashMap<>();

    // 线 1：BARCODE —— 按归一化条码值分组。
    Map<String, List<SkuFacts>> byBarcode = new LinkedHashMap<>();
    for (SkuFacts f : corpus)
      for (String barcode : f.allBarcodes())
        byBarcode.computeIfAbsent(barcode, k -> new ArrayList<>()).add(f);
    for (List<SkuFacts> group : byBarcode.values())
      forEachPair(
          group,
          (a, b) -> {
            Signal signal = barcodeSignal(a.allBarcodes(), b.allBarcodes(), threshold);
            if (signal != null)
              hits.computeIfAbsent(Pair.of(a.skuId, b.skuId), k -> new ArrayList<>()).add(signal);
          });

    // 线 2：VARIANT_COMBINATION —— 同 SPU 内按共享的「轴=取值」分组。
    // Jaccard>0 蕴含至少共享一个轴值，所以这组分法对相等与部分重叠两种判定都无损。
    Map<UUID, Map<String, List<SkuFacts>>> bySpuAxis = new LinkedHashMap<>();
    for (SkuFacts f : corpus) {
      if (f.variantSignature.isEmpty()) continue;
      for (String token : f.variantSignature.split("\\|"))
        bySpuAxis
            .computeIfAbsent(f.spuId, k -> new LinkedHashMap<>())
            .computeIfAbsent(token, k -> new ArrayList<>())
            .add(f);
    }
    for (Map<String, List<SkuFacts>> axisGroups : bySpuAxis.values())
      for (List<SkuFacts> group : axisGroups.values())
        forEachPair(
            group,
            (a, b) -> {
              if (a.skuId.equals(b.skuId)) return;
              Signal signal = variantSignal(a.variantSignature, b.variantSignature, threshold);
              if (signal != null)
                hits.computeIfAbsent(Pair.of(a.skuId, b.skuId), k -> new ArrayList<>()).add(signal);
            });

    // 线 3：CROSS_SPU —— 按「归一化标题 + 品牌 + 类目」复合键分组（组内必是不同 SPU）。
    Map<String, List<SkuFacts>> byCrossSpuKey = new LinkedHashMap<>();
    for (SkuFacts f : corpus) {
      String key =
          normalizeTitle(f.spuName)
              + KEY_SEPARATOR
              + normalizeTitle(f.brand)
              + KEY_SEPARATOR
              + normalizeTitle(f.category);
      byCrossSpuKey.computeIfAbsent(key, k -> new ArrayList<>()).add(f);
    }
    for (List<SkuFacts> group : byCrossSpuKey.values())
      forEachPair(
          group,
          (a, b) -> {
            Signal signal =
                crossSpuSignal(
                    a.spuId.toString(),
                    b.spuId.toString(),
                    a.spuName,
                    b.spuName,
                    a.brand,
                    b.brand,
                    a.category,
                    b.category,
                    threshold);
            if (signal != null)
              hits.computeIfAbsent(Pair.of(a.skuId, b.skuId), k -> new ArrayList<>()).add(signal);
          });

    // 线 4：SIMILAR_PRODUCT —— 按共享素材内容哈希分组。
    // 判定前提本身就含「至少重合一张图」（没有共享哈希时 similarProductSignal 直接返回 null），
    // 所以这组分法对这一维是无损的。
    Map<String, List<SkuFacts>> byMediaHash = new LinkedHashMap<>();
    for (SkuFacts f : corpus)
      for (String hash : f.mediaHashes)
        byMediaHash.computeIfAbsent(hash, k -> new ArrayList<>()).add(f);
    for (List<SkuFacts> group : byMediaHash.values())
      forEachPair(
          group,
          (a, b) -> {
            if (a.skuId.equals(b.skuId)) return;
            Signal signal =
                similarProductSignal(a.skuName, b.skuName, a.mediaHashes, b.mediaHashes, threshold);
            if (signal != null)
              hits.computeIfAbsent(Pair.of(a.skuId, b.skuId), k -> new ArrayList<>()).add(signal);
          });
    return hits;
  }

  /** 组内两两比较（组内成员按 skuId 去重，避免同一 SKU 因多个共享值重复配对）。 */
  private static void forEachPair(
      List<SkuFacts> group, java.util.function.BiConsumer<SkuFacts, SkuFacts> work) {
    List<SkuFacts> unique =
        new ArrayList<>(
            new LinkedHashMap<UUID, SkuFacts>() {
              {
                group.forEach(f -> put(f.skuId, f));
              }
            }.values());
    if (unique.size() < 2) return;
    for (int i = 0; i < unique.size(); i++)
      for (int j = i + 1; j < unique.size(); j++) work.accept(unique.get(i), unique.get(j));
  }

  // ================================================================ 读取

  /** 扫描列表（按时间倒序）+ 每份扫描的三态决定计数。 */
  public DuplicateScanPage scans(int page, int size) {
    int p = Math.max(0, page);
    int s = size <= 0 ? 20 : Math.min(size, MAX_PAGE_SIZE);
    long total =
        sql.fetchOne("select count(*) from sku_duplicate_scan where tenant_id=?", currentTenantId())
            .get(0, Long.class);
    if (total == 0) return new DuplicateScanPage(List.of(), p, s, 0, 0);
    var rows =
        sql.fetch(
            "select id from sku_duplicate_scan where tenant_id=? order by started_at desc,id desc limit ? offset ?",
            currentTenantId(),
            s,
            (long) p * s);
    List<DuplicateScanRow> items = new ArrayList<>();
    for (Record r : rows) items.add(scanRow(r.get("id", UUID.class)));
    int totalCandidates =
        sql.fetchOne(
                "select coalesce(sum(candidate_count),0) from sku_duplicate_scan where tenant_id=?",
                currentTenantId())
            .get(0, Integer.class);
    return new DuplicateScanPage(List.copyOf(items), p, s, total, totalCandidates);
  }

  /** 单次扫描详情（含决定计数）。 */
  public DuplicateScanRow scanRow(UUID scanId) {
    Record row =
        sql.fetchOne(
            "select * from sku_duplicate_scan where tenant_id=? and id=?",
            currentTenantId(),
            scanId);
    if (row == null) throw CatalogService.missing();
    return scanRowOf(row);
  }

  private DuplicateScanRow scanRowOf(Record row) {
    UUID scanId = row.get("id", UUID.class);
    Map<String, Integer> decisions = decisionCounts(scanId);
    return new DuplicateScanRow(
        scanId,
        row.get("min_similarity", BigDecimal.class),
        row.get("scope_category", String.class),
        row.get("scope_brand", String.class),
        row.get("scope_applied", Boolean.class),
        row.get("scanned_sku_count", Integer.class),
        row.get("scanned_spu_count", Integer.class),
        row.get("candidate_count", Integer.class),
        row.get("signal_count", Integer.class),
        instant(row.get("started_at", OffsetDateTime.class)),
        instant(row.get("finished_at", OffsetDateTime.class)),
        row.get("duration_ms", Long.class),
        row.get("actor_id", UUID.class),
        decisions.getOrDefault("PENDING", 0),
        decisions.getOrDefault("MERGE", 0),
        decisions.getOrDefault("KEEP", 0));
  }

  private Map<String, Integer> decisionCounts(UUID scanId) {
    Map<String, Integer> counts = new LinkedHashMap<>();
    counts.put("PENDING", 0);
    counts.put("MERGE", 0);
    counts.put("KEEP", 0);
    for (Record r :
        sql.fetch(
            "select coalesce(d.decision,'PENDING') as state,count(*) as c"
                + " from sku_duplicate_candidate k"
                + " left join sku_duplicate_decision d"
                + "   on d.tenant_id=k.tenant_id and d.candidate_id=k.id"
                + " where k.tenant_id=? and k.scan_id=? group by 1",
            currentTenantId(),
            scanId)) counts.put(r.get("state", String.class), r.get("c", Integer.class));
    return counts;
  }

  /** 总览：最近一次扫描 + 四维分布 + 三态决定数。 */
  public DuplicateOverview overview() {
    Record latest =
        sql.fetchOne(
            "select id from sku_duplicate_scan where tenant_id=? order by started_at desc,id desc limit 1",
            currentTenantId());
    if (latest == null)
      return new DuplicateOverview(
          null, List.of(), 0, 0, 0, 0, 0, "还没有扫描过：点「开始扫描」读全量商品与 SKU，扫完才有候选清单。");
    UUID scanId = latest.get("id", UUID.class);
    DuplicateScanRow scan = scanRow(scanId);
    Map<String, Record> stats = new LinkedHashMap<>();
    for (Record r :
        sql.fetch(
            "select dimension,count(*) as pair_count,max(score) as top_score"
                + " from sku_duplicate_signal where tenant_id=? and scan_id=? group by dimension",
            currentTenantId(),
            scanId)) stats.put(r.get("dimension", String.class), r);
    List<DuplicateDimensionStat> dimensions = new ArrayList<>();
    for (DuplicateRules.Dimension d : DuplicateRules.Dimension.values()) {
      Record r = stats.get(d.name());
      dimensions.add(
          new DuplicateDimensionStat(
              d.name(),
              d.label(),
              r == null ? 0 : r.get("pair_count", Integer.class),
              r == null ? null : r.get("top_score", BigDecimal.class)));
    }
    return new DuplicateOverview(
        scan,
        dimensions,
        scan.pendingCount(),
        scan.mergeCount(),
        scan.keepCount(),
        scan.candidateCount(),
        scan.scannedSkuCount(),
        scan.scopeApplied()
            ? "本次扫描按「类目 "
                + (scan.scopeCategory().isEmpty() ? "全部" : scan.scopeCategory())
                + " / 品牌 "
                + (scan.scopeBrand().isEmpty() ? "全部" : scan.scopeBrand())
                + "」收窄过，跨范围的重复不会被发现 —— 0 个候选不等于干净。"
            : "本次扫描覆盖全量商品与 SKU，未收窄范围。");
  }

  /**
   * 候选清单（分页 + 维度 / 决定 / 最低相似度三种筛选，可任意组合）。
   *
   * <p>维度筛选用 {@code exists} 而不是 join：同一对命中四条线时 join 会产生 4 行， 分页的 {@code total} 与实际候选数对不上（这正是 V36
   * 把「一对一行」当硬约束的原因）。
   */
  public DuplicateCandidatePage candidates(
      UUID scanId,
      String dimension,
      String decision,
      BigDecimal minSimilarity,
      int page,
      int size) {
    DuplicateScanRow scan = scanRow(scanId);
    int p = Math.max(0, page);
    int s = size <= 0 ? 20 : Math.min(size, MAX_PAGE_SIZE);
    DuplicateRules.Dimension dim = null;
    if (dimension != null && !dimension.isBlank()) {
      try {
        dim = DuplicateRules.dimensionByName(dimension);
      } catch (IllegalArgumentException e) {
        // 词表外的维度必须显式失败：兜底成「不筛」会让用户以为自己在按某条线筛选，
        // 实际拿到的是全量清单 —— 而治理重复时看错一份清单的代价比报个错大得多。
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "未知的检测维度：" + dimension);
      }
    }
    String state =
        decision == null || decision.isBlank()
            ? null
            : decision.trim().toUpperCase(java.util.Locale.ROOT);
    CatalogService.check(
        state == null || Set.of("PENDING", "MERGE", "KEEP").contains(state),
        "决定筛选只能是 PENDING / MERGE / KEEP");

    StringBuilder where = new StringBuilder(" where k.tenant_id=? and k.scan_id=?");
    List<Object> binds = new ArrayList<>();
    binds.add(currentTenantId());
    binds.add(scanId);
    if (dim != null) {
      where.append(
          " and exists(select 1 from sku_duplicate_signal g where g.tenant_id=k.tenant_id"
              + " and g.candidate_id=k.id and g.dimension=?)");
      binds.add(dim.name());
    }
    if (minSimilarity != null) {
      where.append(" and k.similarity>=?::numeric");
      binds.add(DuplicateRules.scale(minSimilarity));
    }
    String decisionJoin =
        " left join sku_duplicate_decision d on d.tenant_id=k.tenant_id and d.candidate_id=k.id";
    if (state != null) {
      if ("PENDING".equals(state)) where.append(" and d.id is null");
      else {
        where.append(" and d.decision=?");
        binds.add(state);
      }
    }

    long total =
        sql.fetchOne(
                "select count(*) from sku_duplicate_candidate k" + decisionJoin + where,
                binds.toArray())
            .get(0, Long.class);
    if (total == 0) return new DuplicateCandidatePage(List.of(), p, s, 0, scan);

    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(s);
    pageBinds.add((long) p * s);
    var rows =
        sql.fetch(
            "select k.*,l.code as left_sku_code,l.name as left_sku_name,lp.code as left_spu_code,"
                + "lp.name as left_spu_name,r.code as right_sku_code,r.name as right_sku_name,"
                + "rp.code as right_spu_code,rp.name as right_spu_name,"
                + "d.decision,d.primary_sku_id,d.decided_at"
                + " from sku_duplicate_candidate k"
                + " join sku l on l.tenant_id=k.tenant_id and l.id=k.left_sku_id"
                + " join sku r on r.tenant_id=k.tenant_id and r.id=k.right_sku_id"
                + " join spu lp on lp.tenant_id=k.tenant_id and lp.id=k.left_spu_id"
                + " join spu rp on rp.tenant_id=k.tenant_id and rp.id=k.right_spu_id"
                + decisionJoin
                + where
                + " order by k.similarity desc,k.id limit ? offset ?",
            pageBinds.toArray());
    List<DuplicateCandidateRow> items = new ArrayList<>(rows.size());
    for (Record row : rows) items.add(candidateRow(row, signalsOf(row.get("id", UUID.class))));
    return new DuplicateCandidatePage(List.copyOf(items), p, s, total, scan);
  }

  /** 单个候选详情：两侧完整身份 + 逐维度信号 + 引用拆分 + 已登记的决定。 */
  public DuplicateCandidateDetail detail(UUID candidateId) {
    Record row =
        sql.fetchOne(
            "select k.*,l.code as left_sku_code,l.name as left_sku_name,l.variant_signature as left_signature,"
                + "lp.code as left_spu_code,lp.name as left_spu_name,"
                + "r.code as right_sku_code,r.name as right_sku_name,r.variant_signature as right_signature,"
                + "rp.code as right_spu_code,rp.name as right_spu_name,"
                + "d.id as decision_id,d.decision,d.primary_sku_id,d.secondary_sku_id,"
                + "d.primary_version,d.secondary_version,d.note as decision_note,d.version as decision_version,"
                + "d.decided_by,d.decided_at"
                + " from sku_duplicate_candidate k"
                + " join sku l on l.tenant_id=k.tenant_id and l.id=k.left_sku_id"
                + " join sku r on r.tenant_id=k.tenant_id and r.id=k.right_sku_id"
                + " join spu lp on lp.tenant_id=k.tenant_id and lp.id=k.left_spu_id"
                + " join spu rp on rp.tenant_id=k.tenant_id and rp.id=k.right_spu_id"
                + " left join sku_duplicate_decision d on d.tenant_id=k.tenant_id and d.candidate_id=k.id"
                + " where k.tenant_id=? and k.id=?",
            currentTenantId(),
            candidateId);
    if (row == null) throw CatalogService.missing();
    List<DuplicateSignalView> signals = signalsOf(candidateId);

    UUID leftSkuId = row.get("left_sku_id", UUID.class);
    UUID rightSkuId = row.get("right_sku_id", UUID.class);
    DuplicateCandidateRow candidate = candidateRow(row, signals);
    // 决定行单独读：detail 这条 join 出来的候选行里没有两个 SKU 的编码，
    // 而页面要在决定上直接显示「保留 XXX 并入 YYY」，所以走一次专门查询（decisionRow）。
    DuplicateDecisionRow decision =
        row.get("decision_id", UUID.class) == null ? null : decisionRow(candidateId);

    // 规格组合签名按轴值现算（不读 sku.variant_signature 列，理由见 loadVariantCombinations）。
    // 留空表示这个 SKU 一个轴值都解析不出来 —— 页面要显示出来，
    // 运营需要知道它为什么没被规格组合这一维命中。
    String leftSignature = derivedSignature(leftSkuId);
    String rightSignature = derivedSignature(rightSkuId);

    return new DuplicateCandidateDetail(
        candidate,
        leftSignature,
        rightSignature,
        barcodesOf(leftSkuId),
        barcodesOf(rightSkuId),
        breakdown(leftSkuId),
        breakdown(rightSkuId),
        signals,
        decision,
        "本模块只登记决定，不执行合并。判成合并后由 3.9.1.2 执行："
            + "它会带着本决定里的 expected_version 回来比对，对不上说明登记之后商品被改过，"
            + "需要重新确认再合并。");
  }

  /** 两个 SKU 现算的规格组合签名（与扫描时同一条 SQL、同一套拼装）。 */
  private String derivedSignature(UUID skuId) {
    var parts =
        sql
            .fetch(
                "select d.code,i.option_value from attribute_value v"
                    + " join attribute_definition d"
                    + "   on d.tenant_id=v.tenant_id and d.id=v.attribute_id and d.variant_axis"
                    + " join attribute_value_item i"
                    + "   on i.tenant_id=v.tenant_id and i.value_id=v.id and i.attribute_id=d.id"
                    + " where v.tenant_id=? and v.sku_id=? and v.state='SET' and i.option_value is not null"
                    + " order by d.code,i.ordinal",
                currentTenantId(),
                skuId)
            .stream()
            .map(
                r ->
                    new CombinationRules.Part(
                        r.get("code", String.class), r.get("option_value", String.class)))
            .toList();
    var signature = CombinationRules.signature(parts, Set.of());
    return signature.signature() == null ? "" : signature.signature();
  }

  /** 一个 SKU 的全部条码（{@code sku.barcode} 列 + 物料标识表）。 */
  private List<String> barcodesOf(UUID skuId) {
    List<String> out = new ArrayList<>();
    Record column =
        sql.fetchOne(
            "select barcode from sku where tenant_id=? and id=?", currentTenantId(), skuId);
    if (column != null) {
      String value = nvl(column.get("barcode", String.class));
      if (!value.isEmpty()) out.add(value);
    }
    for (Record r :
        sql.fetch(
            "select value from sku_identifier where tenant_id=? and sku_id=? order by id",
            currentTenantId(),
            skuId)) out.add(r.get("value", String.class));
    return List.copyOf(out);
  }

  /** 引用计数逐项拆分（九类全列，0 也列出来 —— 「报价 0 条」与「没查报价」不是一回事）。 */
  private List<DuplicateReferenceBreakdown> breakdown(UUID skuId) {
    List<DuplicateReferenceBreakdown> out = new ArrayList<>();
    for (String[] query : REFERENCE_QUERIES) {
      int count =
          sql.fetchOne(
                  "select count(*) from "
                      + query[2]
                      + " where tenant_id=? and "
                      + query[3]
                      + " and sku_id=?",
                  currentTenantId(),
                  skuId)
              .get(0, Integer.class);
      out.add(new DuplicateReferenceBreakdown(query[0], query[1], count));
    }
    return out;
  }

  /** 一个候选的逐维度信号（按维度固定顺序）。 */
  private List<DuplicateSignalView> signalsOf(UUID candidateId) {
    List<DuplicateSignalView> out = new ArrayList<>();
    Map<String, Record> byDimension = new LinkedHashMap<>();
    for (Record r :
        sql.fetch(
            "select dimension,score,evidence from sku_duplicate_signal where tenant_id=? and candidate_id=?",
            currentTenantId(),
            candidateId)) byDimension.put(r.get("dimension", String.class), r);
    for (DuplicateRules.Dimension d : DuplicateRules.Dimension.values()) {
      Record r = byDimension.get(d.name());
      if (r == null) continue;
      out.add(
          new DuplicateSignalView(
              d.name(),
              d.label(),
              r.get("score", BigDecimal.class),
              r.get("evidence", String.class)));
    }
    return out;
  }

  private DuplicateCandidateRow candidateRow(Record row, List<DuplicateSignalView> signals) {
    String decision = row.get("decision", String.class);
    return new DuplicateCandidateRow(
        row.get("id", UUID.class),
        row.get("scan_id", UUID.class),
        row.get("left_sku_id", UUID.class),
        row.get("left_sku_code", String.class),
        row.get("left_sku_name", String.class),
        row.get("left_spu_code", String.class),
        row.get("left_spu_name", String.class),
        row.get("right_sku_id", UUID.class),
        row.get("right_sku_code", String.class),
        row.get("right_sku_name", String.class),
        row.get("right_spu_code", String.class),
        row.get("right_spu_name", String.class),
        row.get("similarity", BigDecimal.class),
        row.get("overlap_dimension_count", Integer.class),
        signals,
        row.get("left_reference_count", Integer.class),
        row.get("right_reference_count", Integer.class),
        row.get("reason", String.class),
        decision,
        DuplicateRules.decisionLabel(decision),
        row.get("primary_sku_id", UUID.class),
        instant(row.get("decided_at", OffsetDateTime.class)));
  }

  /** 给下游 3.9.1.2 的一句话交接说明（写清它拿到 expected_version 后要做什么）。 */
  private static String decisionHint(String decision) {
    if ("MERGE".equals(decision))
      return "3.9.1.2 执行合并时必须带着 primary_version / secondary_version 作为 expected_version 比对："
          + "对不上说明登记之后商品被改过，合并前提已不成立，需要重新确认。";
    if ("KEEP".equals(decision)) return "已判为保留两条：3.9.1.2 不应对这一对做任何归并。若后来发现判错，改决定即可（带 version 乐观锁）。";
    return "尚未决定：本模块只登记决定，不执行合并。";
  }

  // ================================================================ 人工决定

  /**
   * 登记人工决定（MERGE / KEEP）。
   *
   * <p><b>本方法不改 sku / spu 的任何一列</b>：执行合并是 3.9.1.2 的职责。 这里只做三件蓝图点名的事：把决定登记成可查的事实、把主记录指出来、 把登记那一刻的
   * {@code sku.version} 留给 3.9.1.2 当 {@code expected_version}。
   *
   * <p><b>MERGE 必须指主记录，且必须是这一对里的一个</b>：不指主记录的「确认合并」 无法执行，而指到第三个 SKU 的主记录会把不相干的两条并掉。 两条都由 DB CHECK
   * 与这里各挡一次（CHECK 管「MERGE 与主记录同时有或同时没有」， 这里管「主记录在候选的两侧之内」）。
   */
  public DuplicateDecisionRow decide(
      AccessService.Access actor, UUID candidateId, DuplicateDecisionInput input) {
    UUID tenantId = actor.tenantId();
    Record candidate =
        sql.fetchOne(
            "select id,scan_id,left_sku_id,right_sku_id from sku_duplicate_candidate where tenant_id=? and id=?",
            tenantId,
            candidateId);
    if (candidate == null) throw CatalogService.missing();
    String decision =
        input.decision() == null ? "" : input.decision().trim().toUpperCase(java.util.Locale.ROOT);
    CatalogService.check(
        Set.of("MERGE", "KEEP").contains(decision), "决定只能是 MERGE（确认合并）或 KEEP（保留两条）");
    String note = trimTo(input.note());
    CatalogService.check(note.length() <= 1000, "决定理由最多 1000 字");

    UUID leftSkuId = candidate.get("left_sku_id", UUID.class);
    UUID rightSkuId = candidate.get("right_sku_id", UUID.class);
    UUID primarySkuId = null;
    UUID secondarySkuId = null;
    if ("MERGE".equals(decision)) {
      primarySkuId = input.primarySkuId();
      CatalogService.check(primarySkuId != null, "判成合并必须指明保留哪一条（主记录）");
      CatalogService.check(
          primarySkuId.equals(leftSkuId) || primarySkuId.equals(rightSkuId),
          "主记录必须是这个候选里的两条之一：并到别的 SKU 上不是「合并这一对」");
      secondarySkuId = primarySkuId.equals(leftSkuId) ? rightSkuId : leftSkuId;
    }

    // expected_version 快照：登记这一刻两个 SKU 的 sku.version。
    // 3.9.1.2 拿着它回来比对，对不上就说明决定的前提已经被改动了。
    // KEEP 时留 null：没有要执行的动作，就不该给下游留一个会过期的锁
    // （一个永远不会被消费的 expected_version 只会让人误以为「随时可以执行合并」）。
    Long primaryVersion = null;
    Long secondaryVersion = null;
    if ("MERGE".equals(decision)) {
      primaryVersion = skuVersion(tenantId, primarySkuId);
      secondaryVersion = skuVersion(tenantId, secondarySkuId);
    }

    long expected = input.expectedVersion() == null ? 0L : input.expectedVersion();
    int updated =
        sql.execute(
            "update sku_duplicate_decision set decision=?,primary_sku_id=?,secondary_sku_id=?,"
                + "primary_version=?,secondary_version=?,note=?,version=version+1,decided_by=?,decided_at=now()"
                + " where tenant_id=? and candidate_id=? and version=?",
            decision,
            primarySkuId,
            secondarySkuId,
            primaryVersion,
            secondaryVersion,
            note,
            actor.userId(),
            tenantId,
            candidateId,
            expected);
    if (updated == 0) {
      // 没有决定行时 version 记为 0；此时并发插入由 UNIQUE(tenant_id,candidate_id) 挡住。
      CatalogService.check(expected == 0, "这个候选的决定已被他人修改，请刷新后重试");
      int inserted =
          sql.execute(
              "insert into sku_duplicate_decision(id,tenant_id,scan_id,candidate_id,decision,"
                  + "primary_sku_id,secondary_sku_id,primary_version,secondary_version,note,version,"
                  + "decided_by,decided_at,updated_at)"
                  + " values(?::uuid,?::uuid,?::uuid,?::uuid,?,?::uuid,?::uuid,?,?,?,0,?::uuid,now(),now())"
                  + " on conflict (tenant_id,candidate_id) do nothing",
              Ids.next(),
              tenantId,
              candidate.get("scan_id", UUID.class),
              candidateId,
              decision,
              primarySkuId,
              secondarySkuId,
              primaryVersion,
              secondaryVersion,
              note,
              actor.userId());
      CatalogService.check(inserted == 1, "这个候选的决定已被他人修改，请刷新后重试");
    }
    audit(actor, candidateId, "SkuDuplicateDecided");
    return decisionRow(candidateId);
  }

  private long skuVersion(UUID tenantId, UUID skuId) {
    Record row =
        sql.fetchOne("select version from sku where tenant_id=? and id=?", tenantId, skuId);
    if (row == null) throw CatalogService.missing();
    return row.get("version", Long.class);
  }

  /** 读回一条决定（含两个 SKU 的编码，让页面上不用再查一次）。 */
  public DuplicateDecisionRow decisionRow(UUID candidateId) {
    Record row =
        sql.fetchOne(
            "select d.*,p.code as primary_code,s.code as secondary_code"
                + " from sku_duplicate_decision d"
                + " left join sku p on p.tenant_id=d.tenant_id and p.id=d.primary_sku_id"
                + " left join sku s on s.tenant_id=d.tenant_id and s.id=d.secondary_sku_id"
                + " where d.tenant_id=? and d.candidate_id=?",
            currentTenantId(),
            candidateId);
    if (row == null) throw CatalogService.missing();
    String decision = row.get("decision", String.class);
    return new DuplicateDecisionRow(
        row.get("id", UUID.class),
        row.get("candidate_id", UUID.class),
        row.get("scan_id", UUID.class),
        decision,
        DuplicateRules.decisionLabel(decision),
        row.get("primary_sku_id", UUID.class),
        row.get("primary_code", String.class),
        row.get("secondary_sku_id", UUID.class),
        row.get("secondary_code", String.class),
        row.get("primary_version", Long.class),
        row.get("secondary_version", Long.class),
        row.get("note", String.class),
        row.get("version", Long.class),
        row.get("decided_by", UUID.class),
        instant(row.get("decided_at", OffsetDateTime.class)),
        decisionHint(decision));
  }

  // ================================================================ 公共

  private void audit(AccessService.Access actor, UUID resourceId, String action) {
    sql.execute(
        "insert into audit_event(id,tenant_id,actor_id,resource_id,action) values(?::uuid,?::uuid,?::uuid,?::uuid,?)",
        Ids.next(),
        actor.tenantId(),
        actor.userId(),
        resourceId,
        action);
  }

  UUID currentTenantId() {
    return sql.fetchOne("select nullif(current_setting('app.current_tenant',true),'')::uuid")
        .get(0, UUID.class);
  }

  private static String nvl(String value) {
    return value == null ? "" : value;
  }

  private static String trimTo(String value) {
    return value == null ? "" : value.trim();
  }

  /** {@code OffsetDateTime → Instant}；timestamptz 出口统一走 Instant，页面拿到的是带 Z 的时刻。 */
  private static java.time.Instant instant(OffsetDateTime value) {
    return value == null ? null : value.toInstant();
  }
}
