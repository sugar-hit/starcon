package com.starcon.trade;

import static com.starcon.trade.SourceAlternativeModels.*;

import jakarta.validation.Valid;
import java.util.List;
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
 * 6.1.2.2 同款货源推荐。
 *
 * <p>命名空间 {@code /trade/source-alternatives}：卷 06 的前端入口是 {@code /supply/alternatives}，
 * 浏览器只经 /api/trade 代理访问；后端路径按**服务**命名空间走，与 6.1.2.1（前�� /supply/sources、
 * 后端 /trade/sources）、6.1.1.3（前端 /supply/ratings、后端 /trade/supplier-ratings）同一纪律。
 *
 * <p>权限码用 {@code trade.alternatives.read} / {@code trade.alternatives.update}：
 * 发起推荐、人工判定、生成采购申请都是写动作，不是读权限能带过去的。
 *
 * <p>租户**不**来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，再在一个事务里绑定
 * {@code app.current_tenant}，由 RLS 在数据库层强制隔离。伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 */
@RestController
@RequestMapping("/trade/source-alternatives")
public class SourceAlternativeController {

  private final AccessService access;
  private final SourceAlternativeService alternatives;

  public SourceAlternativeController(AccessService access, SourceAlternativeService alternatives) {
    this.access = access;
    this.alternatives = alternatives;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "trade." + permission);
    return access.run(actor, "trade." + permission, () -> work.apply(actor));
  }

  // ================================================================ 能力声明 / 词表 / 概览

  /**
   * 本模块的能力与上下游可用性。
   *
   * <p>存在的理由与 9.3.2.2 一致：把「哪些输入真有依据」「哪些下游还没交付」「各平台是否已接线」
   * 显式报出来，而不是让调用方从 404 或空数组里去猜。
   */
  @GetMapping("/capabilities")
  public SourceAlternativeCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "alternatives.read", a -> alternatives.capabilities());
  }

  /** 词表选项（前端下拉的数据源，与后端同源）。 */
  @GetMapping("/options")
  public SourceAlternativeOptions options(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "alternatives.read", a -> alternatives.options());
  }

  /** 目标仓与法人（生成采购申请时必填；仓库服务未接线时如实报「不可用」）。 */
  @GetMapping("/targets")
  public SourceAlternativeTargetOptions targets(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "alternatives.read", a -> alternatives.targets(auth));
  }

  /** 概览（不受筛选与分页影响）。 */
  @GetMapping("/overview")
  public SourceAlternativeOverview overview(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "alternatives.read", a -> alternatives.overview());
  }

  // ================================================================ 规则与权重

  /** 当前生效的规则与排序权重（库里没有就用内置默认值，并把 configured=false 如实报出来）。 */
  @GetMapping("/policy")
  public SourceAlternativePolicy policy(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "alternatives.read", a -> alternatives.policy());
  }

  /** 改规则与权重（权重和必须精确等于 1）。 */
  @PutMapping("/policy")
  public SourceAlternativePolicy savePolicy(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody SourceAlternativePolicyInput input) {
    return call(auth, "alternatives.update", a -> alternatives.savePolicy(input));
  }

  // ================================================================ 推荐批次

  /** 推荐批次列表（真分页 + 真筛选）。 */
  @GetMapping("/runs")
  public SourceAlternativeRunPage runs(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID skuId,
      @RequestParam(required = false) String reason,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(
        auth,
        "alternatives.read",
        a -> alternatives.pageRuns(skuId, reason, status, keyword, page, size));
  }

  /** 批次详情：批次头 + 候选 + 判定留痕 + 价格对比。 */
  @GetMapping("/runs/{runId}")
  public SourceAlternativeRunDetail run(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID runId) {
    return call(auth, "alternatives.read", a -> alternatives.run(runId));
  }

  /**
   * 发起一次推荐（实时算候选并落成一批快照）。
   *
   * <p>候选的证据是**那一刻**的事实，所以落快照：三天后回看「当时推荐了什么、凭什么」，
   * 必须能看到当时的价与交期。
   */
  @PostMapping("/runs")
  public SourceAlternativeRunDetail createRun(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody SourceAlternativeCreateRunInput input) {
    return call(auth, "alternatives.update", a -> alternatives.createRun(input, a.userId(), auth));
  }

  /** 实时候选预览（**不落库**）：正式发起推荐之前先看比价。 */
  @GetMapping("/skus/{skuId}/candidates")
  public List<SourceAlternativeCandidateRow> preview(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID skuId) {
    return call(auth, "alternatives.read", a -> alternatives.preview(skuId, auth));
  }

  // ================================================================ 判定

  /** 人工判定一条候选：确认是同款（CONFIRMED）/ 否决（REJECTED，**必须写理由**）。 */
  @PostMapping("/candidates/{candidateId}/decision")
  public SourceAlternativeDecisionRow decide(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID candidateId,
      @Valid @RequestBody SourceAlternativeDecisionInput input) {
    return call(auth, "alternatives.update", a -> alternatives.decide(candidateId, input, a.userId()));
  }

  /**
   * 一键生成采购申请（6.2.2.1）。
   *
   * <p>弱匹配必须已被人工确认；候选必须有供应商；数量不得低于起订量 —— 三道闸在服务层，
   * 「弱匹配必须人工确认」那道在 DB 层还有一道。
   */
  @PostMapping("/candidates/{candidateId}/purchase-request")
  public SourceAlternativePurchaseRequestResult createPurchaseRequest(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID candidateId,
      @Valid @RequestBody SourceAlternativePurchaseRequestInput input) {
    return call(
        auth,
        "alternatives.update",
        a -> alternatives.createPurchaseRequest(candidateId, input, a.userId(), auth));
  }

  // 裸路径 /trade/source-alternatives **刻意不放行**到 api-proxy：后端没有「一条不存在的汇总列表接口」，
  // 放行它会让界面以为可用。
}
