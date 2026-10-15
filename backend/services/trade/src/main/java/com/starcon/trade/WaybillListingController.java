package com.starcon.trade;

import static com.starcon.trade.WaybillListingModels.*;

import jakarta.validation.Valid;
import java.time.OffsetDateTime;
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
 * 10.2.1.1 运单列表入口。
 *
 * <p>命名空间 {@code /trade/logistics/waybills}，与 10.2.1.2 的
 * {@code /trade/logistics/waybill-applications} <b>并列</b>而不是嵌套或覆盖：
 * 10.2.1.2 是「去换号」的申请动作（写入所有者），10.2.1.1 是「已有哪些运单」的
 * 清单（只读 + 列表级登记）。两个路径字面量不同，proxy 白名单也分得开。
 *
 * <p>八个形状，刻意逐个列出、不做通配：
 *
 * <ul>
 *   <li>{@code capabilities} 能力与上游可用性（含 10.3 / 10.1.2 / 8.3.4.2 / 10.2.1.3 均未交付）
 *   <li>{@code filter-options} 筛选下拉（<b>真实数据 DISTINCT</b>，不是硬编码词表）
 *   <li>{@code GET /list} 运单清单（真分页 + 真筛选 + 排序白名单，<b>汇总不受分页影响</b>）
 *   <li>{@code GET /{id}} 单票详情（清单行 + 运费历史 + 打印留痕 + 可执行动作）
 *   <li>{@code POST /{id}/freight} 登记运费（更正 = 追加 + 撤下旧行）
 *   <li>{@code POST /actions/apply} 批量申请运单（委派 10.2.1.2 的 retry）
 *   <li>{@code POST /actions/cancel} 批量取消（委派 10.2.1.2 的 cancel；已取号 409 → 10.2.1.3）
 *   <li>{@code POST /actions/print} 批量打印（只发已登记凭据 + 逐件留痕）
 * </ul>
 *
 * <p>清单走 {@code /list} 而不是裸路径：与既有 {@code sales-orders/list}（同仓同一份
 * 写法）一致，也让「裸路径 = 后端不存在的映射」这条反例在本文件里继续成立。
 *
 * <p>权限码<b>复用</b> {@code trade.fulfillment.read} / {@code trade.fulfillment.update}，
 * 不新起 {@code trade.waybill.*}：identity 只注册了既有的 fulfillment 页面资源权限码，
 * 用一个**没有被注册**的码会让连 OWNER 都 403。
 */
@RestController
@RequestMapping("/trade/logistics/waybills")
public class WaybillListingController {

  private final AccessService access;
  private final WaybillListingService waybills;

  public WaybillListingController(AccessService access, WaybillListingService waybills) {
    this.access = access;
    this.waybills = waybills;
  }

  /** 统一入口：先确认身份与权限（租户<b>不</b>来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(
      String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "trade." + permission);
    return access.run(actor, "trade." + permission, () -> work.apply(actor));
  }

  /**
   * 能力声明：<b>逐条</b>说清哪个维度真能用、哪个没有上游。
   *
   * <p>本端点存在的理由是蓝图那七个展示态里有<b>四个</b>（已揽收/运输中/已签收/异常）
   * 没有数据源，而「运单状态从承运商侧获取为准」这句话让运营理所当然地以为清单上
   * 会显示在途状态。不提前说清，界面上就会出现「已取号 = 一切正常」的误读。
   */
  @GetMapping("/capabilities")
  public WaybillListingCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "fulfillment.read", a -> waybills.capabilities());
  }

  /** 筛选下拉选项：渠道 / 承运商 / 目的国 / 发货仓，全部来自真实数据 DISTINCT。 */
  @GetMapping("/filter-options")
  public WaybillListFilterOptions filterOptions(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "fulfillment.read", a -> waybills.filterOptions());
  }

  /**
   * 运单清单。
   *
   * <p>汇总（{@code summary}）来自一条<b>不带 LIMIT/OFFSET</b> 的独立查询，
   * 永远覆盖筛选后的全量 —— 跟着分页走的汇总是列表页最常见的假活。
   *
   * @param dateFrom / dateTo 下单时间闭开区间，绑 {@link OffsetDateTime}。
   *     刻意用带时区的类型而不是 {@code LocalDateTime}：后者绑 timestamptz 会
   *     **静默偏 −28800 秒**（把 UTC 当本地），不抛异常、只是时刻错了 8 小时。
   */
  @GetMapping("/list")
  public WaybillListPage waybills(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String carrierCode,
      @RequestParam(required = false) String carrierChannel,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String destinationCountry,
      @RequestParam(required = false) String warehouseRef,
      @RequestParam(required = false) String freight,
      @RequestParam(required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime dateFrom,
      @RequestParam(required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime dateTo,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String sort,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(
        auth,
        "fulfillment.read",
        a ->
            waybills.waybills(
                carrierCode, carrierChannel, status, destinationCountry, warehouseRef, freight,
                dateFrom, dateTo, keyword, sort, page, size));
  }

  /** 单票详情：清单行 + 运费登记历史 + 打印留痕 + 可执行动作 + 边界声明。 */
  @GetMapping("/{id}")
  public WaybillListDetail waybill(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "fulfillment.read", a -> waybills.waybill(id));
  }

  /**
   * 登记一票运费（金额 BigDecimal + NUMERIC(19,4)，<b>禁浮点</b>）。
   *
   * <p>更正走「追加新行 + 撤下旧行」，刻意<b>没有</b>删除形状：运费进了
   * 10.4.1 的物流成本口径，删一笔等于让成本报表少一块而没人知道。
   */
  @PostMapping("/{id}/freight")
  public WaybillFreightRow registerFreight(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody WaybillFreightInput input) {
    return call(
        auth,
        "fulfillment.update",
        a -> waybills.registerFreight(id, input, a.membershipId()));
  }

  /**
   * 批量申请运单（失败重试）—— 写入<b>委派</b> 10.2.1.2。
   *
   * <p>逐条结果：批量里一条不适用不能把整批吞掉。不适用的行会明确说出
   * 「已取号 / 已在队列等窗口 / 已作废」是哪一种，而不是安静地什么都不做。
   */
  @PostMapping("/actions/apply")
  public WaybillBulkActionResult apply(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody WaybillBulkActionInput input) {
    return call(auth, "fulfillment.update", a -> waybills.applyBatch(input, a.membershipId()));
  }

  /** 批量取消：只作用于还没出号的申请；已取号的逐条点名 10.2.1.3（未交付）并不代做。 */
  @PostMapping("/actions/cancel")
  public WaybillBulkActionResult cancel(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody WaybillBulkActionInput input) {
    return call(auth, "fulfillment.update", a -> waybills.cancelBatch(input, a.membershipId()));
  }

  /** 批量打印：逐件发已登记的面单凭据并落打印留痕（不渲染 ZPL，8.3.4.2 未交付）。 */
  @PostMapping("/actions/print")
  public WaybillBulkActionResult print(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody WaybillBulkActionInput input) {
    return call(auth, "fulfillment.update", a -> waybills.printBatch(input, a.membershipId()));
  }
}
