package com.starcon.channel;

import static com.starcon.channel.LogisticsCostAnalysisModels.*;

import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 10.6.1.1 成本分析。
 *
 * <p>端点按蓝图模块号标注，方便对照 10-logistics-and-freight.md。
 *
 * <p>🔴 本控制器唯一不可让步的约束是蓝图那句加粗句
 * 「<b>物流成本常被低估</b>：真实成本 = 尾程 + 头程分摊 + 包材 + 关税 + 仓储费，
 * 报表必须全口径」。它在端点层的落点是三件事：
 *
 * <ol>
 *   <li><b>报表与对比都逐币种</b>：{@code overview} / {@code by-channel} 的每一个金额
 *       都带币种，跨币种绝不相加。合计只在同币种内成立。
 *   <li><b>缺项显式暴露</b>：{@code overview} 返回的 {@code components} 里
 *       五个分量<b>每一位都在</b>；取不到的那一位 {@code available=false}
 *       且带 {@code ownerModule} / {@code unavailableReason}，
 *       <b>不是 0</b>。{@code coverage.missingComponents} 说清合计少算了谁。
 *   <li><b>上游读不到就 503</b>：trade / warehouse 不可达时整体失败，
 *       绝不返回「尾程 0 元」冒充「上游没有运费」——
 *       「没有运费数据」与「上游挂了」在账上是两件完全不同的事。
 * </ol>
 *
 * <p>权限码<b>复用</b> identity 已有的 {@code channel.operation.*}
 * （与 10.1.1.1 承运商、10.1.1.2 渠道配置、10.1.2.2 比价、10.2.2.2 面单模板同族），
 * <b>不新增权限码</b>，identity 服务零改动，跨车道回归面 = 0。
 */
@RestController
@RequestMapping("/channel/logistics-cost-analysis")
public class LogisticsCostAnalysisController {
  private final AccessService access;
  private final LogisticsCostAnalysisService service;

  public LogisticsCostAnalysisController(AccessService access, LogisticsCostAnalysisService service) {
    this.access = access;
    this.service = service;
  }

  /**
   * 统一入口：先确认身份与权限（租户<b>不</b>来自客户端头），再在事务里绑定 RLS 租户上下文。
   *
   * <p>注意 {@code run} 传的是**调用方自己的 bearer**：跨服务读 trade / warehouse 时
   * 转发的也是它（见 {@code TradeFreightQueryPort} / {@code WarehouseCostQueryPort}），
   * 本服务<b>绝不替对方决定租户</b>。
   */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, permission);
    return access.run(actor, permission, () -> work.apply(actor));
  }

  private void callVoid(String bearer, String permission, Consumer<AccessService.Access> work) {
    var actor = access.require(bearer, permission);
    access.run(
        actor,
        permission,
        () -> {
          work.accept(actor);
          return null;
        });
  }

  // ==================================================================
  // 能力声明
  // ==================================================================

  /**
   * 能力声明：五个成本分量逐位报可得性、来源与口径。
   *
   * <p>包材与仓储费在本仓库**真的没有事实来源**，这两位如实标
   * {@code available=false} + {@code ownerModule} + 原因 + 影响 ——
   * 而不是给一个 0（0 会被读成「这项成本不要钱」）。
   */
  @GetMapping("/capabilities")
  public CostCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.capabilities());
  }

  // ==================================================================
  // 成本参数（租户维护的参数口径）
  // ==================================================================

  /** 成本参数清单（真分页 + 全量汇总）。 */
  @GetMapping("/parameters")
  public LogisticsCostParameterPage parameters(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "channel.operation.read", a -> service.parameters(page, size));
  }

  /**
   * 维护一条成本参数（同一个分量 + 币种只有一行，改就是改）。
   *
   * <p>只接受 PACKAGING：其它分量的成本都有事实来源，开参数入口等于制造第二个真相。
   */
  @PostMapping("/parameters")
  public LogisticsCostParameterRow saveParameter(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody LogisticsCostParameterRequest request) {
    return call(auth, "channel.operation.update", a -> service.saveParameter(request));
  }

  /** 删掉一条参数：那个币种的包材位立刻回到「缺项」，不会留下一个 0。 */
  @DeleteMapping("/parameters/{id}")
  public org.springframework.http.ResponseEntity<Void> deleteParameter(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    callVoid(auth, "channel.operation.delete", a -> service.deleteParameter(parseId(id)));
    return org.springframework.http.ResponseEntity.status(HttpStatus.NO_CONTENT).build();
  }

  // ==================================================================
  // 成本报表
  // ==================================================================

  /**
   * 成本报表（总览）。
   *
   * <p>时间窗是<b>闭开区间</b> {@code [from, to)}，绑 {@link OffsetDateTime}：
   * 刻意用带时区的类型而不是 {@code LocalDateTime}，后者绑 timestamptz 会
   * <b>静默偏 −28800 秒</b>（把 UTC 当本地），不抛异常、只是时刻错了 8 小时。
   */
  @GetMapping("/overview")
  public CostOverview overview(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime dateFrom,
      @RequestParam(required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime dateTo) {
    return call(auth, "channel.operation.read", a -> service.overview(auth, dateFrom, dateTo));
  }

  /** 各渠道成本对比：按（承运商 + 渠道）分组，**逐币种**给指标。 */
  @GetMapping("/by-channel")
  public CostChannelComparison byChannel(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime dateFrom,
      @RequestParam(required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime dateTo) {
    return call(auth, "channel.operation.read", a -> service.byChannel(auth, dateFrom, dateTo));
  }

  /**
   * 超成本订单清单。
   *
   * @param multiplier 超成本倍数（单位重量运费 ÷ 同币种平均值）。缺省 1.5 倍。
   */
  @GetMapping("/over-cost-orders")
  public CostOverCostOrderPage overCostOrders(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime dateFrom,
      @RequestParam(required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime dateTo,
      @RequestParam(required = false) BigDecimal multiplier,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(
        auth,
        "channel.operation.read",
        a -> service.overCostOrders(auth, dateFrom, dateTo, multiplier, page, size));
  }

  // ==================================================================
  // 头程分摊规则（版本化）
  // ==================================================================

  /** 分摊规则版本列表（真分页）。历史版本一直看得见。 */
  @GetMapping("/allocation-rules")
  public CostAllocationRulePage rules(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String status,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(auth, "channel.operation.read", a -> service.rules(status, page, size));
  }

  /**
   * 新建一个规则<b>版本</b>。
   *
   * <p>只新建版本，不改既有版本（蓝图 5.2.1.3 加粗句：调整必须留历史版本，
   * 不能就地覆盖）。启用新版本时旧现行版本转 RETIRED，<b>旧行留档</b>。
   */
  @PostMapping("/allocation-rules")
  public CostAllocationRuleRow createRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody CostAllocationRuleRequest request) {
    return call(
        auth,
        "channel.operation.create",
        a -> service.createRule(request, a.userId()));
  }

  // ==================================================================
  // 头程分摊单
  // ==================================================================

  /** 分摊单列表（真分页 + 全量汇总，汇总不受分页影响）。 */
  @GetMapping("/allocations")
  public CostAllocationPage allocations(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String status,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(auth, "channel.operation.read", a -> service.allocations(status, page, size));
  }

  /** 分摊单详情：头 + 逐行明细（分摊明细本身）。 */
  @GetMapping("/allocations/{id}")
  public CostAllocationDetail allocation(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.read", a -> service.allocation(parseId(id)));
  }

  /**
   * 对一张头程发运单做分摊。
   *
   * <p>🔴 头程发运单来自 warehouse（跨服务只读），读不到就是 404 / 503，
   * <b>不空集合</b>。逐行金额用最大余数法分配，合计严格等于来源总额 ——
   * 分摊是拆分，不是缩水。
   */
  @PostMapping("/allocations")
  public CostAllocationRow createAllocation(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody CostAllocationRequest request) {
    return call(
        auth,
        "channel.operation.create",
        a -> service.createAllocation(request, a.userId(), auth));
  }

  /**
   * 结账：锁定这张分摊单。
   *
   * <p>锁定后**不可再改**（V213 触发器钉死）：金额、基准、规则、结账时点都动不了。
   * 它是「这批成本已经出账」的凭据。
   */
  @PostMapping("/allocations/{id}/lock")
  public CostAllocationRow lock(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody CostAllocationLockRequest request) {
    return call(
        auth,
        "channel.operation.update",
        a -> service.lock(parseId(id), request, a.userId()));
  }

  /** 路径里的 id 必须是真 UUID：不是就 400，而不是 500。 */
  private static UUID parseId(String id) {
    try {
      return UUID.fromString(id);
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "id 不是合法的 UUID");
    }
  }
}
