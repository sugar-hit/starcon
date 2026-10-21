package com.starcon.catalog;

import static com.starcon.catalog.BatchCalculationModels.*;

import com.starcon.catalog.BatchCalculationModels.BatchCalculationCapabilities;
import com.starcon.catalog.BatchCalculationModels.BatchCalculationRequest;
import com.starcon.catalog.BatchCalculationModels.BatchResultPage;
import com.starcon.catalog.BatchCalculationModels.BatchRunDetail;
import com.starcon.catalog.BatchCalculationModels.BatchRunPage;
import com.starcon.catalog.BatchCalculationModels.ResultQuery;
import com.starcon.catalog.ChannelPlatformPort.ChannelPlatformUnavailableException;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
 * 5.3.1.2 批量测算。
 *
 * <p>命名空间 {@code /catalog/batch-calculations}：页面路由 {@code /pricing/calculator}
 * （与 5.3.1.1 利润测算器**共用** —— 前端按分区块组织，兄弟模块不重写对方的骨架）。
 * 端点各自独立：批量测算是「对一批 SKU 跑测算」，利润测算是「一次测算 + 方案」。
 *
 * <p>权限沿用商品主数据域：读 {@code catalog.spu.read}，写 {@code catalog.spu.update}。
 * 不新造权限词表 —— 新词表要改 identity 服务的迁移，那是另一个服务的写入所有权。
 *
 * <p><b>租户不来自任何请求头</b>：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 客户端伪造的 {@code X-Tenant-Id} 进不来也改不动它。
 */
@RestController
@RequestMapping("/catalog/batch-calculations")
public class BatchCalculationController {

  private final AccessService access;
  private final BatchCalculationService calculations;

  public BatchCalculationController(AccessService access, BatchCalculationService calculations) {
    this.access = access;
    this.calculations = calculations;
  }

  /** 统一入口：先向 identity 确认身份与权限（租户**不**来自任何客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "catalog." + permission);
    return access.run(actor, "catalog." + permission, () -> work.apply(actor));
  }

  /**
   * 能力声明：售价来源、上游真实交付状态、跳过规则、筛选与导出的边界。
   *
   * <p>页面上把它固定显示在顶部 —— 运营要拿这一页的数字决定「涨价还是清仓」，
   * 口径必须摆在数字旁边。
   */
  @GetMapping("/capabilities")
  public BatchCalculationCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "spu.read", a -> calculations.capabilities());
  }

  /**
   * 目标店铺（站点）列表：来自 2.1.1.1 平台目录。
   *
   * <p>🔴 上游不可达时返回 <b>503</b>，不用空列表冒充「没有店铺」。
   */
  @GetMapping("/sites")
  public java.util.List<ProfitCalculatorModels.SiteOption> sites(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(
        auth,
        "spu.read",
        a -> {
          try {
            return calculations.sites(auth);
          } catch (ChannelPlatformUnavailableException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
          }
        });
  }

  /** 发起一次批量测算：解析范围 → 取价 → 逐 SKU 跑 5.3.1.1 的测算 → 落库。 */
  @PostMapping("/runs")
  public BatchRunDetail createRun(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody BatchCalculationRequest request) {
    return call(auth, "spu.update", a -> calculations.createRun(a, request, auth));
  }

  /** 批次列表（真分页 + 读时汇总）。 */
  @GetMapping("/runs")
  public BatchRunPage listRuns(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    return call(
        auth,
        "spu.read",
        a -> calculations.listRuns(page == null ? 0 : page, size == null ? 20 : size));
  }

  /** 批次详情 = 批次本身 + 读时汇总。 */
  @GetMapping("/runs/{id}")
  public BatchRunDetail getRun(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "spu.read", a -> calculations.getRun(id));
  }

  /** 结果列表（真分页 + 真筛选 + 真排序）。 */
  @GetMapping("/runs/{id}/results")
  public BatchResultPage listResults(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam(required = false) BigDecimal marginBelow,
      @RequestParam(required = false) BigDecimal priceBelow,
      @RequestParam(required = false) String flag,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String sort,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    var query =
        new ResultQuery(
            marginBelow,
            priceBelow,
            flag,
            status,
            keyword,
            sort,
            page == null ? 0 : page,
            size == null ? 20 : size);
    return call(auth, "spu.read", a -> calculations.listResults(id, query));
  }

  /**
   * 导出 CSV：与列表**共用同一套筛选** —— 导出的就是查到的那些行。
   *
   * <p>🔴 超上限**明确报错**，不静默截断。
   */
  @GetMapping(value = "/runs/{id}/export", produces = "text/csv")
  public ResponseEntity<byte[]> export(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam(required = false) BigDecimal marginBelow,
      @RequestParam(required = false) BigDecimal priceBelow,
      @RequestParam(required = false) String flag,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String sort) {
    var query = new ResultQuery(marginBelow, priceBelow, flag, status, keyword, sort, 0, 20);
    var bytes = call(auth, "spu.read", a -> calculations.exportCsv(id, query));
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_TYPE, "text/csv; charset=UTF-8")
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=\"batch-calculation-" + id + ".csv\"")
        .body(bytes);
  }
}
