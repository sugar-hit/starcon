package com.starcon.crm;

import static com.starcon.crm.ProductReviewModels.*;

import com.starcon.crm.ProductReviewModels.CrmProductReviewCapabilities;
import com.starcon.crm.ProductReviewModels.CrmProductReviewCreateInput;
import com.starcon.crm.ProductReviewModels.CrmProductReviewDetail;
import com.starcon.crm.ProductReviewModels.CrmProductReviewPage;
import com.starcon.crm.ProductReviewModels.CrmProductReviewRow;
import com.starcon.crm.ProductReviewModels.CrmProductReviewShopView;
import com.starcon.crm.ProductReviewModels.CrmProductReviewStats;
import com.starcon.crm.ProductReviewModels.CrmProductReviewSyncInput;
import com.starcon.crm.ProductReviewModels.CrmProductReviewSyncResult;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 15.2.1.1 评价列表。
 *
 * <p>命名空间 {@code /crm/reviews}：前端入口同名（页面 {@code /crm/reviews}），
 * 浏览器只经 {@code /api/crm} 代理访问；后端路径按<b>服务</b>命名空间走，
 * 与 catalog（/catalog/...）、trade（/trade/...）同一纪律。
 *
 * <p>⚠️ 与同服务的 {@code /crm/review-requests}（15.2.2.1 索评）、
 * {@code /crm/review-rules}（15.2.2.2 索评规则）**刻意不共用命名空间**：
 * 那两个是「**要不要向这位买家发起一次索评**」（触达侧），本模块是
 * 「**各店铺收到的评价长什么样、整体评分在往哪个方向走**」（读侧 + 汇总侧）。
 *
 * <p>租户<b>不</b>来自任何请求头：先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 *
 * <p>权限码用 {@code crm.product_review.read} / {@code crm.product_review.update}：
 * 与同服务的 {@code crm.knowledge.*} / {@code crm.review.*} / {@code crm.rule.*} /
 * {@code crm.email.*} 同一族命名；<b>刻意不叫 {@code crm.review.*}</b> ——
 * 那个前缀归 15.2.2.1 索评，两件事共用会让「索评」与「评价」在权限上混成一团。
 */
@RestController
@RequestMapping("/crm/reviews")
public class ProductReviewController {

  private final AccessService access;
  private final ProductReviewService reviews;

  public ProductReviewController(AccessService access, ProductReviewService reviews) {
    this.access = access;
    this.reviews = reviews;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "crm.product_review." + permission);
    return access.run(actor, "crm.product_review." + permission, () -> work.apply(actor));
  }

  /** 能力声明：前端在渲染前就知道哪些动作存在，而不是靠试。 */
  @GetMapping("/capabilities")
  public CrmProductReviewCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", a -> reviews.capabilities());
  }

  /** 店铺目录（来自上游 channel 的真实清单）。上游读不到一律 503。 */
  @GetMapping("/shops")
  public List<CrmProductReviewShopView> shops(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", a -> reviews.shops(auth));
  }

  /** 评价列表（真分页 + 真筛选）。 */
  @GetMapping
  public CrmProductReviewPage list(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID shopId,
      @RequestParam(required = false) String platformCode,
      @RequestParam(required = false) Integer rating,
      @RequestParam(required = false) Boolean hasImage,
      @RequestParam(required = false) Boolean hasVideo,
      @RequestParam(required = false) Boolean unreplied,
      @RequestParam(required = false) String productRef,
      @RequestParam(required = false) String variantRef,
      @RequestParam(required = false) String salesOrderNo,
      @RequestParam(required = false) String buyerRef,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String reviewedFrom,
      @RequestParam(required = false) String reviewedTo,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(
        auth,
        "read",
        a ->
            reviews.list(
                reviews.filter(
                    shopId,
                    platformCode,
                    rating,
                    hasImage,
                    hasVideo,
                    unreplied,
                    productRef,
                    variantRef,
                    salesOrderNo,
                    buyerRef,
                    keyword,
                    reviewedFrom,
                    reviewedTo),
                page,
                size));
  }

  /**
   * 评分统计（趋势 + 分布 + 下滑维度定位）。
   *
   * <p>🔴 作用域按**平台评价时刻**算（不是登记时刻），窗口默认 30 天。
   */
  @GetMapping("/stats")
  public CrmProductReviewStats stats(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID shopId,
      @RequestParam(required = false) String platformCode,
      @RequestParam(required = false) Integer rating,
      @RequestParam(required = false) Boolean hasImage,
      @RequestParam(required = false) Boolean hasVideo,
      @RequestParam(required = false) Boolean unreplied,
      @RequestParam(required = false) String productRef,
      @RequestParam(required = false) String variantRef,
      @RequestParam(required = false) String salesOrderNo,
      @RequestParam(required = false) String buyerRef,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String reviewedFrom,
      @RequestParam(required = false) String reviewedTo,
      @RequestParam(required = false, defaultValue = "30") int days) {
    return call(
        auth,
        "read",
        a ->
            reviews.stats(
                reviews.filter(
                    shopId,
                    platformCode,
                    rating,
                    hasImage,
                    hasVideo,
                    unreplied,
                    productRef,
                    variantRef,
                    salesOrderNo,
                    buyerRef,
                    keyword,
                    reviewedFrom,
                    reviewedTo),
                days));
  }

  /**
   * 导出当前筛选结果 CSV（text/csv，由代理原样透传）。
   *
   * <p>与列表**同一套筛选条件**。超过上限时如实截断，并在
   * {@code X-Export-Truncated} / {@code X-Export-Row-Count} 上报出来。
   */
  @GetMapping(value = "/export", produces = "text/csv")
  public ResponseEntity<byte[]> export(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID shopId,
      @RequestParam(required = false) String platformCode,
      @RequestParam(required = false) Integer rating,
      @RequestParam(required = false) Boolean hasImage,
      @RequestParam(required = false) Boolean hasVideo,
      @RequestParam(required = false) Boolean unreplied,
      @RequestParam(required = false) String productRef,
      @RequestParam(required = false) String variantRef,
      @RequestParam(required = false) String salesOrderNo,
      @RequestParam(required = false) String buyerRef,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String reviewedFrom,
      @RequestParam(required = false) String reviewedTo) {
    var result =
        call(
            auth,
            "read",
            a ->
                reviews.exportCsv(
                    reviews.filter(
                        shopId,
                        platformCode,
                        rating,
                        hasImage,
                        hasVideo,
                        unreplied,
                        productRef,
                        variantRef,
                        salesOrderNo,
                        buyerRef,
                        keyword,
                        reviewedFrom,
                        reviewedTo)));
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.parseMediaType("text/csv; charset=UTF-8"));
    headers.set("X-Export-Row-Count", String.valueOf(result.rowCount()));
    headers.set("X-Export-Truncated", String.valueOf(result.truncated()));
    headers.set(
        HttpHeaders.CONTENT_DISPOSITION,
        "attachment; filename=\"product-reviews.csv\"; filename*=UTF-8''product-reviews.csv");
    return new ResponseEntity<>(
        result.csv().getBytes(java.nio.charset.StandardCharsets.UTF_8),
        headers,
        HttpStatus.OK);
  }

  /** 单条评价详情 = 评价本体 + 关联订单上下文（读不到时明确说明原因）。 */
  @GetMapping("/{reviewId}")
  public CrmProductReviewDetail detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID reviewId) {
    return call(auth, "read", a -> reviews.detail(reviewId, auth));
  }

  /** 登记一条评价（人工登记）。 */
  @PostMapping
  public CrmProductReviewRow create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody CrmProductReviewCreateInput input) {
    return call(auth, "update", a -> reviews.create(input, a.userId(), auth));
  }

  /**
   * 平台评价同步。
   *
   * <p>🔴 当前恒为 <b>409</b>：平台评价适配器未交付（capabilities 里
   * {@code platform_review_sync} 维度 available=false），同步请求**一行都不写**。
   */
  @PostMapping("/sync")
  public CrmProductReviewSyncResult sync(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody CrmProductReviewSyncInput input) {
    return call(auth, "update", a -> reviews.sync(input, auth));
  }
}
