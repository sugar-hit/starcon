package com.starcon.trade;

import static com.starcon.trade.SupplierRatingModels.*;

import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 6.1.1.3 供应商评级。
 *
 * <p>命名空间 {@code /trade/supplier-ratings}：卷 06 的前端入口是 {@code /supply}，
 * 浏览器只经 /api/trade 代理访问；后端路径按<b>服务</b>命名空间走，与 6.1.2.1（前端 /supply/sources、
 * 后端 /trade/sources）、6.1.2.3（前端 /supply/qualifications、后端 /trade/supplier-qualifications）
 * 同一纪律。
 *
 * <p>权限码用 {@code trade.ratings.read} / {@code trade.ratings.update}：
 * 算评级、改权重、人工加权分都是写动作，不是读权限能带过去的。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 */
@RestController
@RequestMapping("/trade/supplier-ratings")
public class SupplierRatingController {

  private final AccessService access;
  private final SupplierRatingService ratings;

  public SupplierRatingController(AccessService access, SupplierRatingService ratings) {
    this.access = access;
    this.ratings = ratings;
  }

  /** 统一入口：先确认身份与权限（租户<b>不</b>来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "trade." + permission);
    return access.run(actor, "trade." + permission, () -> work.apply(actor));
  }

  // ================================================================ 能力声明 / 词表 / 概览

  /**
   * 本模块的能力与上下游可用性。
   *
   * <p>存在的理由与 6.1.2.3 一致：把「哪些维度真有依据」「哪些下游还没接上」「口径是什么」
   * 显式报出来，而不是让调用方从 404 或空数组里去猜。
   */
  @GetMapping("/capabilities")
  public SupplierRatingCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "ratings.read", a -> ratings.capabilities());
  }

  /** 词表选项（前端下拉的数据源，与后端同源）。 */
  @GetMapping("/options")
  public SupplierRatingOptions options(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "ratings.read", a -> ratings.options());
  }

  /** 概览（不受筛选与分页影响）。 */
  @GetMapping("/overview")
  public SupplierRatingOverview overview(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) SupplierRatingPeriodType periodType,
      @RequestParam(required = false) LocalDate periodStart) {
    return call(auth, "ratings.read", a -> ratings.overview(periodType, periodStart));
  }

  // ================================================================ 权重配置

  /** 权重配置（租户级；没配时返回系统默认口径并标 configured=false）。 */
  @GetMapping("/policy")
  public SupplierRatingPolicy policy(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) SupplierRatingPeriodType periodType) {
    return call(auth, "ratings.read", a -> ratings.policy(periodType));
  }

  /** 修改权重配置。五个权重之和必须精确等于 1（服务层与 DB CHECK 各拦一道）。 */
  @PutMapping("/policy")
  public SupplierRatingPolicy putPolicy(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody SupplierRatingPolicyInput input) {
    return call(auth, "ratings.update", a -> ratings.putPolicy(input));
  }

  // ================================================================ 评级结果

  /**
   * 计算一个周期的评级。
   *
   * <p>写动作：它会**覆盖**同周期既有的评级行（人工调整保留），并按需留变动历史。
   * 刻意<b>不</b>做成自动定时任务：周期口径与权重都是租户自己的经营决定，
   * 由界面显式触发更可控。
   */
  @PostMapping("/compute")
  public SupplierRatingComputeResult compute(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) SupplierRatingPeriodType periodType,
      @RequestParam(required = false) LocalDate periodStart) {
    return call(auth, "ratings.update", a -> ratings.compute(periodType, periodStart, auth));
  }

  /** 评级列表（真分页 + 真筛选 + 真排序）。 */
  @GetMapping("/ratings")
  public SupplierRatingPage ratings(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) SupplierRatingPeriodType periodType,
      @RequestParam(required = false) LocalDate periodStart,
      @RequestParam(required = false) UUID supplierRefId,
      @RequestParam(required = false) SupplierRatingGrade grade,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String sort,
      @RequestParam(required = false, defaultValue = "true") Boolean desc,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(
        auth,
        "ratings.read",
        a ->
            ratings.pageRatings(
                new SupplierRatingQuery(
                    periodType, periodStart, supplierRefId, grade, keyword, sort, desc, page, size)));
  }

  /** 单个评级详情：结果 + 人工调整留痕 + 变动历史。 */
  @GetMapping("/ratings/{resultId}")
  public SupplierRatingDetail rating(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID resultId) {
    return call(auth, "ratings.read", a -> ratings.rating(resultId));
  }

  /**
   * 人工加权分。
   *
   * <p>🔴 蓝图加粗句「评级必须由客观数据算出，人工加权分需单独标记」的动作出口：
   * 人工**只改最终分**，客观分不动；理由必填；调整明细与变动历史各留一行。
   */
  @PostMapping("/ratings/{resultId}/adjustment")
  public SupplierRatingRow adjust(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID resultId,
      @Valid @RequestBody SupplierRatingAdjustmentInput input) {
    return call(auth, "ratings.update", a -> ratings.adjust(resultId, input, a.userId()));
  }

  // ================================================================ 变动历史 / 优先级

  /** 评级变动历史（真分页 + 真筛选）。 */
  @GetMapping("/changes")
  public SupplierRatingChangePage changes(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) SupplierRatingPeriodType periodType,
      @RequestParam(required = false) UUID supplierRefId,
      @RequestParam(required = false) SupplierRatingChangeType changeType,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(
        auth,
        "ratings.read",
        a -> ratings.changes(periodType, supplierRefId, changeType, page, size));
  }

  /**
   * 默认供应商优先级（供 6.2.1.2 补货建议消费）。
   *
   * <p>只给排序与依据，<b>不替下游选供应商</b>。
   */
  @GetMapping("/priorities")
  public SupplierRatingPriorityList priorities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) SupplierRatingPeriodType periodType,
      @RequestParam(required = false) LocalDate periodStart) {
    return call(auth, "ratings.read", a -> ratings.priorities(periodType, periodStart));
  }

  // 裸路径 /trade/supplier-ratings **刻意不放行**到 api-proxy：后端没有
  // 「一条不存在的汇总列表接口」，放行它会让界面以为可用。
}
