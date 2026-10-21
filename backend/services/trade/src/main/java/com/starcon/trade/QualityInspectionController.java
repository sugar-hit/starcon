package com.starcon.trade;

import static com.starcon.trade.QualityInspectionModels.*;

import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
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
 * 6.4.2.1 质检单。
 *
 * <p>命名空间 {@code /trade/quality-inspections}：后端命名空间<b>按服务走</b>（与 6.2.1.3 的
 * {@code /trade/purchase-plans}、6.4.1.1 的 {@code /trade/processing} 同一纪律）。
 * 前端路由是 {@code /purchase/quality} —— 那是<b>前端</b>路由，与后端命名空间是两件事。
 *
 * <p>⚠️ 与 11.x 的<b>退货质检</b>（{@code /trade/return-quality}）是**两个模块**：
 * 退货质检判的是「退回来的货」，本模块判的是「进来的货」。两者共用「质检」这个词，
 * 但表、端点、权限码、DTO 前缀全部分开（{@code return_quality_*} vs {@code quality_inspection_*}，
 * {@code QualityInspection*} vs {@code IncomingQualityInspection*}）。
 *
 * <p>权限码沿用 {@code trade.<page>.<action>}：{@code trade.quality-inspection.read} /
 * {@code trade.quality-inspection.update}；让步接收另需
 * {@code trade.quality-inspection.concession}（写在每一条让步记录上，并且**真的校验持有**）。
 * 本车道<b>不改 identity 的权限真相源</b>，而是沿用 trade 服务既有的权限码体系。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 */
@RestController
@RequestMapping("/trade/quality-inspections")
public class QualityInspectionController {

  private final AccessService access;
  private final QualityInspectionService service;

  public QualityInspectionController(AccessService access, QualityInspectionService service) {
    this.access = access;
    this.service = service;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "trade." + permission);
    return access.run(actor, "trade." + permission, () -> work.apply(actor));
  }

  // ================================================================ 能力声明

  /**
   * 本模块的能力与上下游可用性。
   *
   * <p>存在的理由：收货在 warehouse、安全属性在 catalog、库存分流在 warehouse ——
   * 哪一个接通了必须由接口自己报出。**尤其是分流**：判完之后不合格的货到底有没有
   * 进待检 bucket，是本模块最要紧的一个事实。
   */
  @GetMapping("/capabilities")
  public IncomingQualityInspectionCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "quality-inspection.read", a -> service.capabilities());
  }

  // ================================================================ 下拉

  /** 建质检单需要的模板下拉与词表。 */
  @GetMapping("/options")
  public IncomingQualityInspectionOptions options(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "quality-inspection.read", a -> service.options());
  }

  // ================================================================ 质检模板

  /** 质检模板列表（真分页 + 真筛选）。 */
  @GetMapping("/templates")
  public IncomingTemplatePage templates(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String inspectionType,
      @RequestParam(required = false) String status,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "quality-inspection.read",
        a -> service.pageTemplates(keyword, inspectionType, status, page, size));
  }

  /** 质检模板详情：头 + 检验项。 */
  @GetMapping("/templates/{id}")
  public IncomingTemplateDetail template(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "quality-inspection.read", a -> service.templateDetail(id));
  }

  /** 建质检模板（含检验项与抽样规则）。 */
  @PostMapping("/templates")
  public IncomingTemplateRow createTemplate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody IncomingTemplateInput input) {
    return call(auth, "quality-inspection.update", a -> service.createTemplate(input, a.userId()));
  }

  /** 改质检模板。抽样规则改了只影响**之后**新开的质检单（已开的带规则快照）。 */
  @PutMapping("/templates/{id}")
  public IncomingTemplateRow updateTemplate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody IncomingTemplateInput input) {
    return call(auth, "quality-inspection.update", a -> service.updateTemplate(id, input, a.userId()));
  }

  /** 启用模板。 */
  @PostMapping("/templates/{id}/activate")
  public IncomingTemplateRow activateTemplate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(
        auth,
        "quality-inspection.update",
        a -> service.setTemplateStatus(id, IncomingTemplateStatus.ACTIVE));
  }

  /** 停用模板（停用后不能再用它开新单，已开的单照常）。 */
  @PostMapping("/templates/{id}/retire")
  public IncomingTemplateRow retireTemplate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(
        auth,
        "quality-inspection.update",
        a -> service.setTemplateStatus(id, IncomingTemplateStatus.INACTIVE));
  }

  // ================================================================ 质检单

  /** 质检单列表（真分页 + 真筛选 + 真排序）。 */
  @GetMapping
  public IncomingOrderPage orders(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String result,
      @RequestParam(required = false) String inspectionType,
      @RequestParam(required = false) String receiptCode,
      @RequestParam(required = false) String skuCode,
      @RequestParam(required = false, defaultValue = "created") String sort,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "quality-inspection.read",
        a ->
            service.pageOrders(
                keyword, status, result, inspectionType, receiptCode, skuCode, sort, page, size));
  }

  /** 汇总视图：可售量与不可售量分列。 */
  @GetMapping("/summary")
  public IncomingInspectionSummary summary(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String currencyCode) {
    return call(auth, "quality-inspection.read", a -> service.summary(currencyCode));
  }

  /**
   * 建质检单。
   *
   * <p>到货量来自 8 收货（服务端读上游后写入）；应抽数量按模板的抽样规则在服务端算 ——
   * 前端传的抽样数一律不认。
   */
  @PostMapping
  public IncomingOrderRow createOrder(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody IncomingOrderInput input) {
    return call(
        auth, "quality-inspection.update", a -> service.createOrder(input, a.userId(), auth));
  }

  /** 质检单详情：头 + 逐项结论 + 让步记录 + 缺项 + 分流。 */
  @GetMapping("/{id}")
  public IncomingOrderDetail order(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "quality-inspection.read", a -> service.orderDetail(id));
  }

  /** 录入逐项检验结论（外观 / 功能 / 规格 / 包装 / 认证标识，逐项记录并带扣量）。 */
  @PostMapping("/{id}/items")
  public IncomingOrderItemRow recordItem(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody IncomingOrderItemInput input) {
    return call(
        auth, "quality-inspection.update", a -> service.recordItem(id, input, a.userId()));
  }

  /**
   * 登记让步接收。
   *
   * <p><b>原因与权限码缺一拒绝</b>（蓝图 6.4.2.2 加粗句的纪律）；
   * 而且服务层会核对记录人**真的持有**那个权限码 —— 只写一个码不算放行。
   */
  @PostMapping("/{id}/concessions")
  public IncomingConcessionRow addConcession(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody IncomingConcessionInput input) {
    return call(
        auth,
        "quality-inspection.update",
        a -> service.addConcession(id, input, a.userId(), a.permissions()));
  }

  /** 完结质检单：判出三量、结论与分流意图。 */
  @PostMapping("/{id}/complete")
  public IncomingOrderRow complete(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody(required = false) IncomingCompleteInput input) {
    return call(
        auth,
        "quality-inspection.update",
        a ->
            service.complete(
                id,
                input == null ? new IncomingCompleteInput(null, null) : input,
                a.userId()));
  }

  /** 作废质检单。理由必填。 */
  @PostMapping("/{id}/cancel")
  public IncomingOrderRow cancel(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody IncomingCancelInput input) {
    return call(auth, "quality-inspection.update", a -> service.cancel(id, input));
  }

  // ================================================================ 导出

  /**
   * 导出 CSV 给质检员。
   *
   * <p>真导出：一张质检单 + 逐项结论 + 让步记录，三段都在文件里 ——
   * 只导出三量会把「凭什么让步」丢掉，而那正是争议时最需要的那几行。
   */
  @GetMapping(value = "/{id}/export", produces = "text/csv")
  public ResponseEntity<byte[]> export(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    byte[] bytes = call(auth, "quality-inspection.read", a -> service.exportCsv(id));
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_TYPE, "text/csv; charset=UTF-8")
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=\"quality-inspection-" + id + ".csv\"")
        .body(bytes);
  }
}
