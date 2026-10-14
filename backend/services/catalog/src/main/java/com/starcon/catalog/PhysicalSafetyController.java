package com.starcon.catalog;

import static com.starcon.catalog.PhysicalSafetyModels.*;

import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.*;

/**
 * 3.7.2.1 物理与安全属性：物理档案读写、安全属性筛选、渠道可用性判定、装箱体积核算。
 *
 * <p>路径挂在 {@code /catalog/physical-safety} 之下，与 {@code /catalog/sku-attribute-override}
 * （3.2.2.2）、{@code /catalog/unit-conversion}（3.1.2.4）同形：都是「针对某一个 SKU 的一个侧面」， 而不是商品档案本身的一部分。合并进
 * {@code CatalogController} 会让那个已经很宽的控制器 继续变宽，且本模块的校验口径（毛重不小于净重、勾了标记必须确认已采集）自成一体。
 *
 * <p>权限：读 {@code catalog.compliance.read}、写 {@code catalog.compliance.update}。 理由见 {@link
 * PhysicalSafetyService} 的类注释（3.7 是「商品合规属性」一级）。
 *
 * <p><b>只按 SKU 寻址</b>，不要求调用方同时传商品 id：SKU 的归属本来就唯一。
 */
@RestController
@RequestMapping("/catalog/physical-safety")
public class PhysicalSafetyController {
  private final AccessService access;
  private final PhysicalSafetyService service;

  public PhysicalSafetyController(AccessService access, PhysicalSafetyService service) {
    this.access = access;
    this.service = service;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "catalog." + permission);
    return access.run(actor, "catalog." + permission, () -> work.apply(actor));
  }

  /**
   * 读 SKU 物理档案（物理参数 + 安全属性 + 派生体积/体积重/计费重）。
   *
   * <p>{@code divisorCm3PerKg} 是<b>体积重除数</b>，由调用方给（空运 6000、快递 5000…）。 不传就不给体积重 —— 本模块不替 10 物流选除数（规则
   * R3）。
   */
  @GetMapping("/skus/{skuId}")
  public PhysicalSafetyProfile profile(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID skuId,
      @RequestParam(required = false) BigDecimal divisorCm3PerKg) {
    return call(auth, "compliance.read", a -> service.profile(skuId, divisorCm3PerKg));
  }

  /**
   * 保存物理参数与安全属性。
   *
   * <p>一次提交写两张表：这是有意的 —— 蓝图的职责句把两者写在同一句里， 运营在 SKU 建档时也是一次填完（蓝图：「采集时点应尽量前置到 SKU 建档」）。
   *
   * <p>两个 {@code version} 各自是乐观锁凭据，撞 409 而不是静默覆盖。
   */
  @PutMapping("/skus/{skuId}")
  public PhysicalSafetySaved save(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID skuId,
      @Valid @RequestBody PhysicalSafetyInput input) {
    return call(auth, "compliance.update", a -> service.save(a, skuId, input));
  }

  /**
   * 按安全属性筛选 SKU（供 10 物流渠道筛选）。
   *
   * <p>三个标记都接受 {@code true} / {@code false}：运营会问「哪些货<b>不是</b>危险品」， 只支持筛 {@code true} 就答不了。
   */
  @GetMapping("/profiles")
  public PhysicalSafetyPage profiles(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) Boolean containsBattery,
      @RequestParam(required = false) Boolean containsLiquid,
      @RequestParam(required = false) Boolean dangerousGoods,
      @RequestParam(required = false) Boolean isMagnetic,
      @RequestParam(required = false) Boolean safetyCollected,
      @RequestParam(required = false) Boolean requiresDeclaration,
      @RequestParam(required = false) String packagingType,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(
        auth,
        "compliance.read",
        a ->
            service.search(
                new PhysicalSafetyQuery(
                    keyword,
                    containsBattery,
                    containsLiquid,
                    dangerousGoods,
                    isMagnetic,
                    safetyCollected,
                    requiresDeclaration,
                    packagingType,
                    page,
                    size)));
  }

  /**
   * 渠道可用性判定：给定渠道限制，回答这个 SKU 能不能走、要不要申报。
   *
   * <p>用 POST 而不是 GET：请求体是一份渠道策略（四个 allowed 开关 + 除数）， 放 URL 里既超长又会把渠道口径塞进访问日志。
   *
   * <p><b>安全属性未采集时判定为不可发</b>（规则 R2）：默认放行等于给了一条 「不填安全属性就能绕过申报」的路。
   */
  @PostMapping("/skus/{skuId}/channel-verdict")
  public ChannelVerdictView verdict(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID skuId,
      @Valid @RequestBody ChannelVerdictInput input) {
    return call(auth, "compliance.read", a -> service.verdict(skuId, input));
  }

  /** 装箱体积核算（供 8 拣货）：给一组 SKU 行与箱规，算每箱可装几件、共需几箱。 */
  @PostMapping("/carton-calc")
  public CartonCalcView cartonCalc(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody CartonCalcInput input) {
    return call(auth, "compliance.read", a -> service.cartonCalc(input));
  }
}
