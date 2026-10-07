package com.starcon.catalog;

import static com.starcon.catalog.CompletenessScoreModels.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.*;

/**
 * 3.9.2.1 完整度评分：评分台账、评分卡、缺失项清单、批量补全入口，
 * 以及评分所依赖的两类 catalog 侧声明数据（报关属性与认证）。
 *
 * <p>路由分三段，理由是<b>读写语义不同</b>：
 *
 * <ul>
 *   <li>{@code /catalog/completeness/*} —— 评分的读出口与批量入口（台账 / 评分卡 / 清单 / 预检）。
 *       运营要问的是「哪些商品刊登不了」「该先补哪一条」，主体不固定，所以是集合资源。
 *   <li>{@code /catalog/completeness/customs/skus/{skuId}} —— 报关属性声明（V37 第二节）。
 *       它挂在 SKU 下按单主体寻址，与 {@code PhysicalSafetyController} 的
 *       {@code /catalog/physical-safety/skus/{skuId}} 同一形状。
 *   <li>{@code /catalog/completeness/certifications/skus/{skuId}[/{certId}]} —— 认证声明，
 *       一个 SKU 多条（CE / FCC / UL ...），所以要多一层 {@code {certId}}。
 * </ul>
 *
 * <p><b>预检端点是给下游业务用的</b>（4.2.3.2 刊登预检、报关流程）：
 * 它们要的是「放行 / 拦住 + 拦住的原因」，所以与给人看的列表分开 ——
 * 一个 200 + {@code allowed=false} 加一份 blockers 才够下游把单子退回来并指出该补什么。
 *
 * <p>权限沿用商品主数据域：读 {@code catalog.spu.read}，写 {@code catalog.spu.update}，
 * 与 3.6.2.1 完全一致。刻意不混用合规域权限（混用会出现"能看评分却改不了报关属性"
 * 这种半截权限 —— 3.7.2.1 已在物理与安全属性上拒绝过这个方案）。
 *
 * <p>租户一律来自已认证成员关系（{@link AccessService} 向 identity 解析），
 * <b>不读 {@code X-Tenant-Id} 头</b>：那是客户端自称的租户，AGENTS.md 明令禁止。
 */
@RestController
@RequestMapping("/catalog/completeness")
public class CompletenessScoreController {
  private final AccessService access;
  private final CompletenessScoreService service;

  public CompletenessScoreController(AccessService access, CompletenessScoreService service) {
    this.access = access;
    this.service = service;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "catalog." + permission);
    return access.run(actor, "catalog." + permission, () -> work.apply(actor));
  }

  // ------------------------------------------------------------ 概览与词表

  /** 概览：分档分布、刊登/清关就绪数与各维度缺口数。 */
  @GetMapping("/overview")
  public CompletenessOverview overview(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "spu.read", a -> service.overview());
  }

  /** 词表选项：六个维度、分档、主体类型、认证状态与图片阈值（与后端同源）。 */
  @GetMapping("/options")
  public CompletenessOptions options(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "spu.read", a -> service.options());
  }

  // ------------------------------------------------------------ 台账与评分卡

  /**
   * 评分台账：分页 + 关键词 / 主体类型 / 分档 / 状态 / 就绪状态 / 缺失项筛选。
   *
   * <p>默认按分数<b>升序</b>：治理页先看要补的，而不是先看最好的。
   */
  @GetMapping
  public CompletenessPage list(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String ownerType,
      @RequestParam(required = false) String grade,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) UUID spuId,
      @RequestParam(required = false) String categoryName,
      @RequestParam(required = false) Boolean listingReadyOnly,
      @RequestParam(required = false) Boolean missingOnly,
      @RequestParam(required = false) String orderBy,
      @RequestParam(required = false) Boolean ascending,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(
        auth,
        "spu.read",
        a ->
            service.list(new CompletenessQuery(
                keyword, ownerType, grade, null, spuId, categoryName, status,
                listingReadyOnly, missingOnly, orderBy, ascending, page, size)));
  }

  /**
   * 评分卡（蓝图「输出」那一行的读出口）。
   *
   * <p>返回逐条检查项（含已满足的）与缺失项清单，
   * 以及<b>两个独立</b>的就绪结论（刊登 / 清关）与各自的阻塞原因。
   */
  @GetMapping("/{ownerType}/{ownerId}")
  public CompletenessScorecard card(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String ownerType,
      @PathVariable UUID ownerId) {
    return call(auth, "spu.read", a -> service.card(ownerType, ownerId));
  }

  // ------------------------------------------------------------ 缺失项清单（批量补全入口）

  /**
   * 缺失项清单：按缺失码聚合，受影响主体数降序。
   *
   * <p>每条给出「全店有多少个主体缺这一项」与前几个代表性主体 ——
   * 补全从影响面最大的那条开始，因为修好一条报关规则可能同时让几十个 SKU 的
   * 清关就绪结论翻转。
   */
  @GetMapping("/gaps")
  public CompletenessGapPage gaps(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String ownerType,
      @RequestParam(required = false) String grade,
      @RequestParam(required = false) String dimension,
      @RequestParam(required = false) UUID spuId,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) Boolean listingReadyOnly,
      @RequestParam(required = false) Boolean missingOnly,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(
        auth,
        "spu.read",
        a ->
            service.gaps(new CompletenessQuery(
                keyword, ownerType, grade, dimension, spuId, null, status,
                listingReadyOnly, missingOnly, null, null, page, size)));
  }

  /** 缺失项统计：补全工作台顶部的进度数字。 */
  @GetMapping("/gaps/summary")
  public GapSummary gapSummary(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String ownerType) {
    return call(
        auth,
        "spu.read",
        a -> service.gapSummary(new CompletenessQuery(
            null, ownerType, null, null, null, null, null, null, null, null, null, 0, 20)));
  }

  // ------------------------------------------------------------ 批量评估（批量补全入口）

  /**
   * 批量评估并落快照。
   *
   * <p>调用方只传主体 id，<b>分数由服务端现算</b>：一个"把分数传进来让我存一下"的端点
   * 等于给完整度发了一张可自填的通行证，而完整度直接决定能否刊登与能否清关。
   *
   * <p>逐条成败都返回，不做"整体成功/失败"：批量补全天然是"好的补了、还差的留着"，
   * 一个整体 500 会让人以为一条都没补上。
   */
  @PostMapping("/evaluations")
  public CompletenessEvaluationResult evaluate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody CompletenessEvaluationInput input) {
    return call(auth, "spu.update", a -> service.evaluate(a, input));
  }

  /** 评估历史（回答「当时为什么被拦住」—— 现算的评分答不了过去）。 */
  @GetMapping("/evaluations")
  public CompletenessEvaluationPage evaluations(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID ownerId,
      @RequestParam(required = false) String ownerType,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(auth, "spu.read", a -> service.evaluations(ownerId, ownerType, page, size));
  }

  /** 一条评估快照。 */
  @GetMapping("/evaluations/{id}")
  public CompletenessEvaluation evaluation(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "spu.read", a -> service.evaluation(id));
  }

  // ------------------------------------------------------------ 预检出口（供 4.2.3.2 / 报关流程）

  /**
   * 刊登预检（供下游 4.2.3.2 调用）。
   *
   * <p>返回 {@code allowed} + {@code blockers}（逐个主体带缺失码与阻塞原因），
   * 而不是只给一个布尔：布尔答不出「这 3 个 SPU 为什么被拦住」。
   *
   * <p>清单长度放在<b>请求体</b>而不是 URL 上：一次预检的 SPU 可能上百个，
   * 拼进 query 迟早撞长度上限。
   */
  @PostMapping("/precheck/listing")
  public ListingPrecheck listingPrecheck(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody Map<String, List<String>> body) {
    return call(auth, "spu.read", a -> service.listingPrecheck(ids(body.get("spuIds"))));
  }

  /** 清关预检（供报关流程调用；同一口径，链换成清关）。 */
  @PostMapping("/precheck/customs")
  public CustomsPrecheck customsPrecheck(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody Map<String, List<String>> body) {
    return call(auth, "spu.read", a -> service.customsPrecheck(ids(body.get("skuIds"))));
  }

  private static List<UUID> ids(List<String> raw) {
    var parsed = new java.util.ArrayList<UUID>();
    if (raw == null) return parsed;
    for (String value : raw) {
      if (value == null || value.isBlank()) continue;
      parsed.add(UUID.fromString(value.trim()));
    }
    return parsed;
  }

  // ------------------------------------------------------------ 报关属性（catalog 侧声明数据）

  /** 某个 SKU 的报关属性；<b>无行返回 200 + null</b> 而不是 404（"还没登记"是正常状态）。 */
  @GetMapping("/customs/skus/{skuId}")
  public CustomsDeclaration customs(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID skuId) {
    return call(auth, "spu.read", a -> service.customsOf(skuId));
  }

  /**
   * 写报关属性（新建或整体替换）。
   *
   * <p>{@code version} 是行级乐观锁：改的时候必须回传读到的版本号，否则 409。
   * 无行时传 0 表示新建。
   *
   * <p>HS 编码与申报品名<b>可空</b>：报关属性是逐步补全的，建档时常常只有品名、
   * HS 编码要等供应商确认。要求全填会让"建一行"都做不了，而缺什么由评分报。
   */
  @PutMapping("/customs/skus/{skuId}")
  public CustomsDeclaration saveCustoms(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID skuId,
      @Valid @RequestBody CustomsDeclarationInput input) {
    return call(auth, "spu.update", a -> service.saveCustoms(a, skuId, input));
  }

  /** 删报关属性：删掉之后评分退回「尚未登记」（一个有意义且可逆的状态）。 */
  @DeleteMapping("/customs/skus/{skuId}")
  public Map<String, Boolean> deleteCustoms(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID skuId,
      @RequestParam long version) {
    call(
        auth,
        "spu.update",
        a -> {
          service.deleteCustoms(a, skuId, version);
          return Boolean.TRUE;
        });
    return Map.of("deleted", true);
  }

  // ------------------------------------------------------------ 认证（catalog 侧声明数据）

  /** 某个 SKU 的认证集合 + 现算的四态结论（规则 R5）。 */
  @GetMapping("/certifications/skus/{skuId}")
  public CertificationSet certifications(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID skuId) {
    return call(auth, "spu.read", a -> service.certifications(skuId));
  }

  /**
   * 新建一条认证声明。
   *
   * <p>{@code status=NOT_REQUIRED} 是这里最要紧的一格：多数品类本来就不强制认证，
   * 没有「显式记录无需认证」这个状态，这些商品会永远停在「认证缺失」，
   * 而运营无论怎么补都补不上 —— 最后整个认证维度变成一片没人看的红色。
   */
  @PostMapping("/certifications/skus/{skuId}")
  public Certification createCertification(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID skuId,
      @Valid @RequestBody CertificationInput input) {
    return call(auth, "spu.update", a -> service.saveCertification(a, skuId, null, input));
  }

  /** 改一条认证声明（行级乐观锁）。 */
  @PutMapping("/certifications/skus/{skuId}/{certId}")
  public Certification updateCertification(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID skuId,
      @PathVariable UUID certId,
      @Valid @RequestBody CertificationInput input) {
    return call(auth, "spu.update", a -> service.saveCertification(a, skuId, certId, input));
  }

  /** 删一条认证。 */
  @DeleteMapping("/certifications/skus/{skuId}/{certId}")
  public Map<String, Boolean> deleteCertification(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID skuId,
      @PathVariable UUID certId,
      @RequestParam long version) {
    call(
        auth,
        "spu.update",
        a -> {
          service.deleteCertification(a, certId, version);
          return Boolean.TRUE;
        });
    return Map.of("deleted", true);
  }
}
