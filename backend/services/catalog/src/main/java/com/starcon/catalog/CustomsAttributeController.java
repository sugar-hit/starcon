package com.starcon.catalog;

import static com.starcon.catalog.CustomsAttributeModels.*;

import jakarta.validation.Valid;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.*;

/**
 * 3.7.1.1 HS 编码与申报要素：报关属性读写、申报要素维护、审核状态机与
 * 供 10 报关引用的只读出口。
 *
 * <p>路径挂在 {@code /catalog/customs-attributes} 之下，与 {@code /catalog/physical-safety}
 * （3.7.2.1）、{@code /catalog/sku-attribute-override}（3.2.2.2）同形：
 * 都是「针对某一个 SKU 的一个侧面」，而不是商品档案本身的一部分。
 * 合并进 {@code CatalogController} 会让那个已经很宽的控制器继续变宽，
 * 且本模块的校验口径（HS 码格式、享惠双向一致、审核态一致性）自成一体。
 *
 * <p><b>三个动作、三个权限</b>：读 {@code catalog.compliance.read}、
 * 写 {@code catalog.compliance.update}、<b>审核 {@code catalog.compliance.review}</b>。
 * 审核单列的理由见 {@link CustomsAttributeService} 的类注释：
 * 能编辑报关属性的人不一定有权批准它，合并成一个权限等于允许「自己填自己批」。
 *
 * <p><b>只按 SKU 寻址</b>，不要求调用方同时传商品 id：SKU 的归属本来就唯一。
 */
@RestController
@RequestMapping("/catalog/customs-attributes")
public class CustomsAttributeController {
  private final AccessService access;
  private final CustomsAttributeService service;

  public CustomsAttributeController(AccessService access, CustomsAttributeService service) {
    this.access = access;
    this.service = service;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "catalog." + permission);
    return access.run(actor, "catalog." + permission, () -> work.apply(actor));
  }

  /** 读 SKU 的报关属性 + 申报要素 + 现算的可报关性判定。 */
  @GetMapping("/skus/{skuId}")
  public CustomsProfile profile(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID skuId) {
    return call(auth, "compliance.read", a -> service.profile(skuId));
  }

  /**
   * 保存报关属性与申报要素（草稿态编辑）。
   *
   * <p>{@code elements} 是<b>完整清单</b>而不是增量：位次唯一与「required 非空」
   * 都是跨行不变量，增量提交时前端要自己算最终集合。
   *
   * <p><b>已审核通过的行不可直接改</b>（409）：正确路径是「审核里驳回 → 改 → 重新提交」。
   */
  @PutMapping("/skus/{skuId}")
  public CustomsSaved save(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID skuId,
      @Valid @RequestBody CustomsInput input) {
    return call(auth, "compliance.update", a -> service.save(a, skuId, input));
  }

  /** 按 HS 编码 / 原产地 / 状态 / 协定 / 关键字筛选（供 16 合规与 10 报关批量核）。 */
  @GetMapping("/profiles")
  public CustomsPage profiles(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String hsCode,
      @RequestParam(required = false) String originCountryCode,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String preferenceAgreement,
      @RequestParam(required = false) Boolean preferentialOriginEligible,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(
        auth,
        "compliance.read",
        a ->
            service.search(
                new CustomsQuery(
                    keyword, hsCode, originCountryCode, status,
                    preferenceAgreement, preferentialOriginEligible, page, size)));
  }

  /**
   * 提交审核（DRAFT → 已提交待审）。
   *
   * <p>用独立端点而不是复用 PUT：提交是一个<b>状态流转</b>并留下审核留痕，
   * 混进保存会让「我只是想改个字」与「我把它交上去了」无法区分。
   */
  @PostMapping("/skus/{skuId}/submit")
  public CustomsProfile submit(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID skuId,
      @Valid @RequestBody SubmitInput input) {
    return call(auth, "compliance.update", a -> service.submit(a, skuId, input));
  }

  /**
   * 审核（通过 / 驳回），需 {@code catalog.compliance.review}。
   *
   * <p>驳回必填原因；通过前钉死「至少一条申报要素 + 每条 required 的都有非空值」。
   */
  @PostMapping("/skus/{skuId}/review")
  public CustomsProfile review(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID skuId,
      @Valid @RequestBody ReviewInput input) {
    return call(
        auth,
        "compliance.review",
        a -> service.review(a, skuId, input, a.permissions().contains("catalog.compliance.review")));
  }

  /**
   * <b>供 10 报关调用</b>：只返回审核通过的申报资料，未审核通过一律报错。
   *
   * <p>用 GET + query 参数而不是路径段：它是 10 报关清关时的<b>一次读</b>，
   * 且必须能被 GET 缓存与重试（重试一个写操作在语义上是危险的）。
   */
  @GetMapping("/declaration-ready")
  public DeclarationReady declarationReady(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam UUID skuId) {
    return call(auth, "compliance.read", a -> service.declarationReady(skuId));
  }
}