package com.starcon.warehouse;

import static com.starcon.warehouse.AllocationPolicyModels.*;

import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 7.8.1.1 分配策略配置。
 *
 * <p>蓝图：「定义同一总可用库存分配给多个店铺/平台的方式：按销量占比比例分配、按固定优先级先到先得、 按店铺类型（自营/托管）优先级、留出不分配缓冲。支持 SKU
 * 或店铺维度的策略覆盖。输入：分配策略配置 （方法、参数、生效范围）；当前可用量；各店铺历史销量。输出：生效的分配策略；各店铺分摊额。」
 *
 * <p>命名空间与 7.1~7.5 同一套：{@code /warehouse/inventory/allocation-policies}，权限码 {@code
 * warehouse.inventory.read} / {@code warehouse.inventory.update}。 端点刻意独立成 {@code
 * AllocationPolicyController} 而不是并进 {@code InventoryController}： 那是 7.1.1.1
 * 那条车道的文件，各车道并行时各改各的才不会撞（同 7.4.2.2 的做法）。
 *
 * <p><b>整个路径上没有 DELETE 端点，而且这不是遗漏</b>：策略档与分摊批次都是事实， 改策略走「收口旧档 + 开新档」、作废走 {@code /retire}，分摊批次
 * append-only。 想知道「能不能物理删掉一条分摊算法配置」，答案是不能 —— V68 的 REVOKE DELETE 与不可变触发器 会在权限层和数据库层各挡一次。
 */
@RestController
@RequestMapping("/warehouse")
public class AllocationPolicyController {

  private final AccessService access;
  private final AllocationPolicyService allocation;

  public AllocationPolicyController(AccessService access, AllocationPolicyService allocation) {
    this.access = access;
    this.allocation = allocation;
  }

  /** 统一入口：先向 identity 确认身份与权限（租户**不**来自任何客户端头）， 再在一个事务里绑定 RLS 租户上下文执行查询（与 7.4.2.2 同一个 call）。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "warehouse." + permission);
    return access.run(actor, "warehouse." + permission, () -> work.apply(actor));
  }

  // ------------------------------------------------------------ 能力

  /**
   * 分摊能力：三种方法、三个范围档、五种店铺类型，以及三条必须如实讲清楚的说明 （销量数据为何不能自动派生、缓冲与 7.2.2.1 的平台回写缓冲为何是两个东西、下游交接）。
   *
   * <p>前端据此渲染选项与提示，而不是把规则硬编码在界面上。
   */
  @GetMapping("/inventory/allocation-policies/capabilities")
  public AllocationCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "inventory.read", a -> allocation.capabilities());
  }

  // ------------------------------------------------------------ 策略分档

  /** 分摊策略分档列表（真分页；{@code effectiveOnly=true} 只看此刻仍在生效的档）。 */
  @GetMapping("/inventory/allocation-policies")
  public AllocationPolicyPage policies(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String scopeType,
      @RequestParam(required = false) UUID referenceId,
      @RequestParam(required = false) String method,
      @RequestParam(required = false, defaultValue = "false") boolean effectiveOnly,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "inventory.read",
        a ->
            allocation.page(
                new AllocationPolicyQuery(
                    scopeType, referenceId, method, effectiveOnly, keyword, page, size)));
  }

  /**
   * 解析某一时刻真正生效的策略（SKU 档 &gt; 店铺档 &gt; 全租户兜底档）。
   *
   * <p>返回里的 {@code usedFallback} 与 {@code note} 如实说明用的是哪一档。没有配置时返回 {@code
   * policy=null}，调用方据此拒绝下单，而不是让各店各自判断有没有货 —— 那正是蓝图红线里「所有店铺都以为有货」的超卖。
   */
  @GetMapping("/inventory/allocation-policies/effective")
  public AllocationPolicyEffective effective(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String scopeType,
      @RequestParam(required = false) UUID referenceId,
      @RequestParam(required = false) UUID skuId,
      @RequestParam(required = false) String at) {
    return call(
        auth,
        "inventory.read",
        a -> allocation.effective(scopeType, referenceId, skuId, moment(at)));
  }

  /** 策略详情（含成员名册）。跨租户在 RLS 下查不到 → 404，不泄露存在性。 */
  @GetMapping("/inventory/allocation-policies/{id}")
  public AllocationPolicyDetail policy(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "inventory.read", a -> allocation.detail(id));
  }

  /**
   * 新建一档分摊策略。
   *
   * <p>权限码是 {@code warehouse.inventory.update} 而不是 read：分摊算法是防超卖的那道闸门， 把「改这道闸门」挂在读权限上，那道闸门等于没有。
   */
  @PostMapping("/inventory/allocation-policies")
  public AllocationPolicySaved create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody AllocationPolicyInput input) {
    return call(auth, "inventory.update", a -> allocation.save(null, input, a.userId()));
  }

  /**
   * 换版：收口当前在效的那一档，再开新档。
   *
   * <p>刻意不给「改这一档」的 PUT —— 策略内容写定后不可改（V68 触发器在 DB 层挡住）， 改策略只能通过收口 + 新开，历史那一档因此永远是「当时真正生效的那套算法」的凭据。
   */
  @PostMapping("/inventory/allocation-policies/{id}/versions")
  public AllocationPolicySaved supersede(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody AllocationPolicyInput input) {
    return call(auth, "inventory.update", a -> allocation.save(id, input, a.userId()));
  }

  /**
   * 作废一档（收口它的生效区间）。
   *
   * <p>用 POST 而不是 DELETE：作废是审计事件，档位行必须永久保留。
   */
  @PostMapping("/inventory/allocation-policies/{id}/retire")
  public AllocationPolicySaved retire(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam(required = false) String at) {
    return call(auth, "inventory.update", a -> allocation.retire(id, moment(at)));
  }

  // ------------------------------------------------------------ 销量登记

  /**
   * 登记一条店铺历史销量。
   *
   * <p>它是本服务**唯一的销量真相来源**：库存流水没有店铺维度、订单域（05 卷）未交付， 所以销量是登记进来的事实而不是从别处算出来的。{@code /capabilities}
   * 会把这件事讲清楚。
   */
  @PostMapping("/inventory/allocation-sales")
  public AllocationShopSalesSaved recordSales(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody AllocationShopSalesInput input) {
    return call(auth, "inventory.update", a -> allocation.recordSales(input, a.userId()));
  }

  /** 某 SKU 在一个日期区间内各店的销量名册（比例分配的分母来源）。 */
  @GetMapping("/inventory/allocation-sales")
  public List<SaleRow> sales(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam UUID skuId,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
    return call(auth, "inventory.read", a -> allocation.sales(skuId, from, to));
  }

  // ------------------------------------------------------------ 分摊

  /**
   * 分摊**试算**。一行都不写。
   *
   * <p>用 POST 而不是 GET：请求体是一组 SKU，放 URL 里会撞长度限制 （与 {@code POST /inventory/availability}、7.2.2.1 的
   * calculation 同一理由）。
   *
   * <p><b>刻意忽略请求体里的 {@code persist}</b>，无条件按试算处理：本端点是 read 权限， 而落库写的是不可改的分摊批次（下游 7.8.2.1
   * 读它、超卖复盘靠它）。 让一个只读调用方靠一个布尔字段就能写批次，那道写闸门等于没有。
   */
  @PostMapping("/inventory/allocation-policies/calculation")
  public List<AllocationResult> calculate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody AllocationRequest request) {
    return call(
        auth, "inventory.read", a -> allocation.allocate(withoutPersist(request), a.userId()));
  }

  /**
   * 分摊并落库。
   *
   * <p>与 {@code /calculation} 刻意分成两个端点而不是靠一个 {@code persist} 布尔切换： 「看一眼」和「写一笔事实」的风险等级不同，权限也不同 ——
   * 落库要 update 权限， 试算只读配置，read 权限就够。
   */
  @PostMapping("/inventory/allocation-policies/runs")
  public List<AllocationResult> run(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody AllocationRequest request) {
    return call(
        auth,
        "inventory.update",
        a -> allocation.allocate(withoutPersist(request, true), a.userId()));
  }

  /**
   * 改写请求体里的 {@code persist}。
   *
   * <p>刻意**不**把它从 schema 里删掉：同一个请求体在两个端点上是同一个形状， 前端就不用为「试算」和「落库」写两套类型；而开关的真值由**端点**决定，不由客户端决定。
   */
  private static AllocationRequest withoutPersist(AllocationRequest request) {
    return withoutPersist(request, false);
  }

  private static AllocationRequest withoutPersist(AllocationRequest request, boolean persist) {
    return request == null
        ? null
        : new AllocationRequest(
            request.skuIds(),
            request.availableQuantity(),
            request.referenceId(),
            request.scopeType(),
            request.at(),
            persist);
  }

  /** 分摊批次列表（真分页；行数与「分到 0 件的行数」由明细现算）。 */
  @GetMapping("/inventory/allocation-policies/runs")
  public AllocationRunPage runs(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID skuId,
      @RequestParam(required = false) String method,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(auth, "inventory.read", a -> allocation.runs(skuId, method, page, size));
  }

  /** 分摊批次详情 = 摘要 + 逐店明细（含每个店铺为什么拿到 / 没拿到货）。 */
  @GetMapping("/inventory/allocation-policies/runs/{runId}")
  public AllocationRunDetail runDetail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID runId) {
    return call(auth, "inventory.read", a -> allocation.run(runId));
  }

  /** 解析可选的 ISO-8601 时刻；解析不了就报 400 而不是悄悄用当前时刻。 */
  private static java.time.OffsetDateTime moment(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return java.time.OffsetDateTime.parse(raw);
    } catch (RuntimeException e) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.BAD_REQUEST,
          "时刻格式非法："
              + raw
              + "（应为 ISO-8601 带偏移，例如 2026-10-07T10:00:00+08:00）。"
              + "悄悄改成当前时刻会让「看某一刻谁生效」这个问题得到一个假的答案。");
    }
  }
}
