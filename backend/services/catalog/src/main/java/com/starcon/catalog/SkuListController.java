package com.starcon.catalog;

import static com.starcon.catalog.CatalogModels.*;

import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * 3.1.2.1 SKU 列表：分页检索、批量操作与导出。权限沿用 SKU 档案页的 catalog.sku.* 。
 *
 * <p>这是仓储、采购、财务共用的事实入口，因此读权限统一用 {@code catalog.sku.read}，
 * 写操作分别对应 update / delete 之外的批量语义：改状态需要 {@code catalog.sku.update}。
 */
@RestController
@RequestMapping("/catalog")
public class SkuListController {
  private final AccessService access;
  private final SkuListService service;

  public SkuListController(AccessService access, SkuListService service) {
    this.access = access;
    this.service = service;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "catalog." + permission);
    return access.run(actor, "catalog." + permission, () -> work.apply(actor));
  }

  /** 列表筛选条件。价格/库存区间与缺货、在售开关会原样传给 service，由它按投影可用性决定拒绝还是执行。 */
  @GetMapping("/skus")
  public SkuPage list(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID spuId,
      @RequestParam(required = false) String category,
      @RequestParam(required = false) List<String> status,
      @RequestParam(required = false) String q,
      @RequestParam(required = false) String barcode,
      @RequestParam(required = false) String spec,
      @RequestParam(required = false) BigDecimal onHandMin,
      @RequestParam(required = false) BigDecimal onHandMax,
      @RequestParam(required = false) BigDecimal priceMin,
      @RequestParam(required = false) BigDecimal priceMax,
      @RequestParam(required = false) Boolean outOfStock,
      @RequestParam(required = false) Boolean onSale,
      @RequestParam(required = false) String platform,
      @RequestParam(defaultValue = "updated_at") String sort,
      @RequestParam(defaultValue = "desc") String order,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(
        auth,
        "sku.read",
        a ->
            service.page(
                new SkuListQuery(
                    spuId,
                    category,
                    status,
                    q,
                    barcode,
                    spec,
                    onHandMin,
                    onHandMax,
                    priceMin,
                    priceMax,
                    outOfStock,
                    onSale,
                    platform,
                    sort,
                    order,
                    page,
                    size)));
  }

  /** 能力声明：前端据此决定哪些筛选控件该禁用，而不是自己猜。 */
  @GetMapping("/skus/capabilities")
  public SkuListCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "sku.read", a -> service.capabilities());
  }

  @PostMapping("/skus/batch")
  public SkuBatchResult batch(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody SkuBatchInput input) {
    return call(auth, "sku.update", a -> service.batch(a, input));
  }

  /**
   * 导出 CSV：StreamingResponseBody 分批写出，峰值内存与结果集大小无关。
   *
   * <p>每批在流式线程里重新开租户事务——租户上下文是事务级 GUC，controller 那个事务
   * 在响应开始前就结束了，跨线程复用它的连接会丢掉租户绑定。
   */
  @GetMapping(value = "/skus/export", produces = "text/csv")
  public ResponseEntity<StreamingResponseBody> export(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID spuId,
      @RequestParam(required = false) String category,
      @RequestParam(required = false) List<String> status,
      @RequestParam(required = false) String q,
      @RequestParam(required = false) String barcode,
      @RequestParam(required = false) String spec,
      @RequestParam(required = false) String sort,
      @RequestParam(defaultValue = "desc") String order) {
    var actor = access.require(auth, "catalog.sku.read");
    var query =
        new SkuListQuery(
            spuId, category, status, q, barcode, spec, null, null, null, null, null, null, null,
            sort, order, 0, 0);
    // 先在租户事务里校验条件（未接入投影的筛选在这里报 422），别等到已经开始写流才失败。
    access.run(
        actor,
        "catalog.sku.read",
        () -> {
          service.requireProjections(query);
          return null;
        });
    StreamingResponseBody body =
        out -> service.streamCsv(actor, query, chunk -> write(out, chunk));
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_TYPE, "text/csv; charset=UTF-8")
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=\"catalog-skus-" + Instant.now().toEpochMilli() + ".csv\"")
        .body(body);
  }

  private static void write(java.io.OutputStream out, String chunk) {
    try {
      out.write(chunk.getBytes(StandardCharsets.UTF_8));
      out.flush();
    } catch (java.io.IOException e) {
      // 客户端提前断开（关掉下载页）是正常行为，不该当成服务端错误往上抛。
      throw new org.springframework.web.client.RestClientException("导出已中断", e);
    }
  }
}
