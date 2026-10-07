package com.starcon.catalog;

import static com.starcon.catalog.DuplicateModels.*;

import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 3.9.1.1 重复 SKU 识别的端点。
 *
 * <p>蓝图：「识别重复/近重复商品……输出候选与相似度供人工确认」，输出里还有「确认合并/保留决定」。
 * 端点按这条链路排：<b>扫一次 → 看候选 → 看单个候选的证据 → 登记决定</b>。
 *
 * <pre>
 *   GET  /catalog/duplicate-scans/overview            概览（最近一次扫描 + 四维分布 + 三态计数）
 *   POST /catalog/duplicate-scans                     发起一次扫描（阈值 + 可选范围）
 *   GET  /catalog/duplicate-scans                     扫描运行列表（历史都在，可对比治理前后）
 *   GET  /catalog/duplicate-scans/{id}                单次扫描统计
 *   GET  /catalog/duplicate-scans/{id}/candidates     候选清单（分页 + 维度/决定/最低相似度筛选）
 *   GET  /catalog/duplicate-candidates/{id}           单个候选详情（两侧身份 + 逐维度证据 + 引用拆分）
 *   POST /catalog/duplicate-candidates/{id}/decision  登记合并/保留决定（**不执行合并**）
 *   GET  /catalog/duplicate-candidates/{id}/decision  读回已登记的决定
 * </pre>
 *
 * <p><b>为什么候选 ID 不在 {@code duplicate-scans/{id}} 下面</b>：候选详情与决定要能
 * 在扫描之间被引用（页面上「治理后重新扫一次，上一轮这条候选判成什么」要查得到），
 * 所以候选是与扫描<b>平级</b>的一级资源，而不是某次扫描的子资源 ——
 * 挂在扫描下会让「上一轮那条候选」这个引用无处可寻。
 * 租户隔离仍由 {@code sku_duplicate_candidate.tenant_id} 的 RLS 独立守住。
 *
 * <p><b>权限沿用 {@code catalog.spu.read} / {@code catalog.spu.update}</b>，与 3.8.1.1/3.8.1.2
 * 同一口径：候选清单读的是商品主数据、写的是主数据自己的派生数据，不引入新词表
 * （新词表要改 identity 服务的迁移，属于另一个服务的写入所有权）。
 * 注意 V3 的 {@code sku_read_scope}：以 {@code catalog.spu.read} 身份读 SKU 行时额外要求
 * {@code catalog.sku.read}，所以候选页的只读角色要同时有这两个权限（与 3.6.2.1 报价页同一口径）。
 *
 * <p><b>租户只来自已认证成员关系</b>：本控制器没有任何参数是 tenantId，
 * 一律取 {@code AccessService.Access.tenantId()}。客户端传 {@code X-Tenant-Id} 不会被读，
 * 带伪造头访问别人的扫描/候选得到的是 404（RLS 已过滤跨租户的行）。
 */
@RestController
@RequestMapping("/catalog")
public class DuplicateDetectionController {
  private final AccessService access;
  private final DuplicateDetectionService duplicates;

  public DuplicateDetectionController(AccessService access, DuplicateDetectionService duplicates) {
    this.access = access;
    this.duplicates = duplicates;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "catalog." + permission);
    return access.run(actor, "catalog." + permission, () -> work.apply(actor));
  }

  /**
   * 概览：最近一次扫描 + 四维分布 + 待确认/已合并/已保留三态计数。
   *
   * <p>没扫过时返回的是<b>空态而不是 404</b>：页面首屏要能直接渲染「还没有扫描过」，
   * 拿到 404 就得先区分「没扫过」与「无权访问」两种情况，而这两者对运营是同一件事（都是空的）。
   */
  @GetMapping("/duplicate-scans/overview")
  public DuplicateOverview overview(@RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "spu.read", actor -> duplicates.overview());
  }

  /**
   * 发起一次扫描。
   *
   * <p>同步返回而不是受理后轮询：相似度要算的是「当前全量商品与 SKU 的关系」，
   * 单租户一次扫描是毫秒到秒级，没有排队与重试的必要，
   * 做成异步只会让页面多一个「扫完了吗」的状态机（对照 V29 导入向导：那个异步是因为要上传并逐行落文件）。
   *
   * <p>支持按类目 / 品牌收窄（{@code category} / {@code brand}）：大租户能分片扫，
   * 代价是<b>会漏掉跨范围的重复</b>，所以响应里的 {@code scopeApplied} 与 {@code note}
   * 会显式说明这一点，页面必须把它显示出来 ——
   * 分片扫描后「0 个候选」与全量扫描的「0 个候选」不是同一个结论。
   */
  @PostMapping("/duplicate-scans")
  public DuplicateScanRow scan(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody(required = false) DuplicateScanInput input) {
    return call(auth, "spu.update", actor -> duplicates.scan(actor, input == null ? new DuplicateScanInput(null, null, null) : input));
  }

  /** 扫描运行列表：每次扫描都独立留档，治理前后各扫一次就能对比（候选数从 87 降到 6）。 */
  @GetMapping("/duplicate-scans")
  public DuplicateScanPage scans(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(auth, "spu.read", actor -> duplicates.scans(page, size));
  }

  /** 单次扫描统计（含决定三态计数）。 */
  @GetMapping("/duplicate-scans/{id}")
  public DuplicateScanRow scan(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth, @PathVariable UUID id) {
    return call(auth, "spu.read", actor -> duplicates.scanRow(id));
  }

  /**
   * 候选清单（蓝图的「重复候选清单」本体）。
   *
   * <p>三种筛选与三种用户意图一一对应：{@code dimension}（只看条码撞了的）、
   * {@code decision}（只看待确认的 / 判了合并的）、{@code minSimilarity}（只要高分的那批）。
   * 可任意组合，因为「条码撞了而且还没人判」是最常见的一种查法。
   */
  @GetMapping("/duplicate-scans/{id}/candidates")
  public DuplicateCandidatePage candidates(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam(required = false) String dimension,
      @RequestParam(required = false) String decision,
      @RequestParam(required = false) BigDecimal minSimilarity,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(
        auth,
        "spu.read",
        actor -> duplicates.candidates(id, dimension, decision, minSimilarity, page, size));
  }

  /**
   * 单个候选详情：两侧商品身份 + 现算的规格组合 + 全部条码 + 逐维度得分与证据 +
   * 九类引用逐项拆分 + 已登记的决定。
   *
   * <p>页面靠它回答「到底该不该合并」：证据是可核对的（撞的是哪个条码、重合的是哪几张图），
   * 引用数是逐项的（报价 3 条 vs 素材 3 张的处理成本完全不同）。
   */
  @GetMapping("/duplicate-candidates/{id}")
  public DuplicateCandidateDetail candidate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth, @PathVariable UUID id) {
    return call(auth, "spu.read", actor -> duplicates.detail(id));
  }

  /**
   * 登记合并 / 保留决定（蓝图的「确认合并/保留决定」）。
   *
   * <p><b>本端点不执行合并</b>：它是 3.9.1.2 的上游输入，不是它的替代品。
   * 判成 {@code MERGE} 时必须指明主记录（必须是这一对里的一个），服务端会记下登记那一刻
   * 两个 SKU 的 {@code version} —— 那就是 3.9.1.2 说的 {@code expected_version}，
   * 执行前必须比对，对不上说明登记之后商品被人改过。
   *
   * <p>重选决定走同一个端点（带 {@code expectedVersion} 乐观锁）：一个候选一份当前决定，
   * 不存逐轮流水 —— 审计流水是 17.5.3.1 的职责。
   */
  @PostMapping("/duplicate-candidates/{id}/decision")
  public DuplicateDecisionRow decide(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody DuplicateDecisionInput input) {
    return call(auth, "spu.update", actor -> duplicates.decide(actor, id, input));
  }

  /** 读回已登记的决定（页面重进时不必先拉整份详情）。 */
  @GetMapping("/duplicate-candidates/{id}/decision")
  public DuplicateDecisionRow decision(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth, @PathVariable UUID id) {
    return call(auth, "spu.read", actor -> duplicates.decisionRow(id));
  }
}