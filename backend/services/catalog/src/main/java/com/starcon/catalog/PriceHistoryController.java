package com.starcon.catalog;

import com.starcon.catalog.PriceHistoryModels.PriceHistoryChangeInput;
import com.starcon.catalog.PriceHistoryModels.PriceHistoryChangeQuery;
import com.starcon.catalog.PriceHistoryModels.PriceHistoryChangeDetail;
import com.starcon.catalog.PriceHistoryModels.PriceHistoryChangeListPage;
import com.starcon.catalog.PriceHistoryModels.PriceHistoryChangeSaved;
import com.starcon.catalog.PriceHistoryModels.PriceHistoryComparisonPage;
import com.starcon.catalog.PriceHistoryModels.PriceHistoryComparisonQuery;
import com.starcon.catalog.PriceHistoryModels.PriceHistoryCapabilities;
import com.starcon.catalog.PriceHistoryModels.PriceHistoryReasonInput;
import com.starcon.catalog.PriceHistoryModels.PriceHistoryReasonRow;
import com.starcon.catalog.PriceHistoryModels.PriceHistorySalesTotal;
import com.starcon.catalog.PriceHistoryModels.PriceHistoryTimeline;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
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
 * 5.5.1.1 价格历史。
 *
 * <p>命名空间 {@code /catalog/price-history}：页面路由 {@code /pricing/history}。
 * 与同 L3 的 5.5.2.1 异常告警（{@code /catalog/price-anomaly-alerts}）**不共用端点** ——
 * 两个模块回答的是两个不同的问题（一个是「这个价怎么变过来的」，一个是「这个价有没有问题」）。
 *
 * <p>权限沿用商品主数据域：读 {@code catalog.spu.read}，写 {@code catalog.spu.update}。
 * 不新造权限词表 —— 新词表要改 identity 服务的迁移，那是另一个服务的写入所有权。
 *
 * <p><b>租户不来自任何请求头</b>：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 客户端伪造的 {@code X-Tenant-Id} 进不来也改不动它。
 */
@RestController
@RequestMapping("/catalog/price-history")
public class PriceHistoryController {

  private final AccessService access;
  private final PriceHistoryService history;

  public PriceHistoryController(AccessService access, PriceHistoryService history) {
    this.access = access;
    this.history = history;
  }

  /** 统一入口：先向 identity 确认身份与权限（租户**不**来自任何客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "catalog." + permission);
    return access.run(actor, "catalog." + permission, () -> work.apply(actor));
  }

  /**
   * 能力声明：价格 / 销量 / 利润三个维度的真值与缺口。
   *
   * <p>蓝图加粗句「<b>价格历史 + 销量 + 利润三者对齐，才能回答"降价到底有没有用"</b>」
   * 是本页存在的理由，也是本页最容易被糊弄过去的地方 —— 所以它被固定放在最上面。
   */
  @GetMapping("/capabilities")
  public PriceHistoryCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "spu.read", a -> history.capabilities());
  }

  /**
   * 价格变更时间轴（真筛选 + 真分页 + 真排序）。
   *
   * <p>筛选维度：SKU / 店铺 / 时间范围 / 变更来源 / 原因 / 方向 / 关键字。
   */
  @GetMapping("/changes")
  public PriceHistoryChangeListPage changes(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) UUID skuId,
      @RequestParam(required = false) String platformCode,
      @RequestParam(required = false) String siteCode,
      @RequestParam(required = false) String changeSource,
      @RequestParam(required = false) String reasonCode,
      @RequestParam(required = false) String direction,
      @RequestParam(required = false) LocalDate windowFrom,
      @RequestParam(required = false) LocalDate windowTo,
      @RequestParam(required = false) String sort,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    var query =
        new PriceHistoryChangeQuery(
            keyword,
            skuId,
            platformCode,
            siteCode,
            changeSource,
            reasonCode,
            direction,
            windowFrom,
            windowTo,
            sort,
            page == null ? 0 : page,
            size == null ? 20 : size);
    return call(auth, "spu.read", a -> history.listChanges(query));
  }

  /** 单条变更详情（变更事实 + 前后相邻的变更）。 */
  @GetMapping("/changes/{id}")
  public PriceHistoryChangeDetail change(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "spu.read", a -> history.getChange(id));
  }

  /**
   * 登记一次价格变更（人工）。
   *
   * <p>🔴 变更事实**只追加**：想改价就再登记一条，不在原地修改已经发生过的历史。
   */
  @PostMapping("/changes")
  public PriceHistoryChangeSaved register(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody PriceHistoryChangeInput input) {
    return call(auth, "spu.update", a -> history.register(a, auth, input));
  }

  /**
   * 一个 listing（SKU × 店铺）的价格时间轴。
   *
   * <p>时间轴上同时给出 5.3.2.2 价格规则的**待执行建议**（只读引用，尚未生效）。
   */
  @GetMapping("/timeline")
  public PriceHistoryTimeline timeline(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam UUID skuId,
      @RequestParam String platformCode,
      @RequestParam String siteCode,
      @RequestParam(required = false) LocalDate windowFrom,
      @RequestParam(required = false) LocalDate windowTo) {
    return call(
        auth, "spu.read", a -> history.timeline(skuId, platformCode, siteCode, windowFrom, windowTo));
  }

  /**
   * 「降价到底有没有用」的对比视图：价怎么变的、利润怎么变的、销量怎么变的。
   *
   * <p>🔴 销量维度**当前未接线**（上游端点未交付）⇒ 销量三列为 null 并写明原因，
   * **不是 0**。页面上必须显示成「未接线」而不是「销量 0」。
   */
  @GetMapping("/comparison")
  public PriceHistoryComparisonPage comparison(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) UUID skuId,
      @RequestParam(required = false) String platformCode,
      @RequestParam(required = false) String siteCode,
      @RequestParam(required = false) LocalDate windowFrom,
      @RequestParam(required = false) LocalDate windowTo,
      @RequestParam(required = false) Integer salesWindowDays,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    var query =
        new PriceHistoryComparisonQuery(
            keyword,
            skuId,
            platformCode,
            siteCode,
            windowFrom,
            windowTo,
            salesWindowDays == null ? 0 : salesWindowDays,
            page == null ? 0 : page,
            size == null ? 20 : size);
    return call(auth, "spu.read", a -> history.comparison(query, auth));
  }

  /**
   * 一段区间的销量事实。
   *
   * <p>🔴 上游不可达 / 未接线 ⇒ <b>503</b>，不用 0 或空数组冒充销量。
   */
  @GetMapping("/sales")
  public PriceHistorySalesTotal sales(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam UUID skuId,
      @RequestParam String platformCode,
      @RequestParam String siteCode,
      @RequestParam String from,
      @RequestParam String to) {
    return call(
        auth,
        "spu.read",
        a -> {
          try {
            return history.sales(
                auth, skuId, platformCode, siteCode, OffsetDateTime.parse(from), OffsetDateTime.parse(to));
          } catch (SalesFactPort.SalesUnavailableException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
          }
        });
  }

  /** 变更原因字典（全部，含已停用的）。 */
  @GetMapping("/reasons")
  public List<PriceHistoryReasonRow> reasons(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "spu.read", a -> history.reasons());
  }

  /** 保存一条变更原因（按 code 覆盖）。 */
  @PostMapping("/reasons")
  public PriceHistoryReasonRow saveReason(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody PriceHistoryReasonInput input) {
    return call(auth, "spu.update", a -> history.saveReason(a, input));
  }
}
