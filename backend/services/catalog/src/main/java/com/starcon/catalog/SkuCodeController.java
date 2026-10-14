package com.starcon.catalog;

import static com.starcon.catalog.CatalogModels.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.*;

/**
 * 3.1.2.3 编码与条码：内部编码规则、手工/自动/批量三种编码方式、外部条码与冲突拒绝清单。
 *
 * <p>路由挂在 {@code /catalog/sku-code} 之下，与 3.1.2.1/3.1.2.2 的 {@code /catalog/skus} 分开： 编码是一个独立的维护域（规则
 * + 批量 + 条码），挂在 {@code /skus} 下会和 {@code /catalog/skus/export}、{@code /catalog/skus/capabilities}
 * 这些静态段抢匹配。
 *
 * <p>权限沿用 SKU 档案：读 {@code catalog.sku.read}，写 {@code catalog.sku.update}。 编码与条码是 SKU
 * 档案不可分割的一部分，不另开更宽或更窄的口子。
 */
@RestController
@RequestMapping("/catalog/sku-code")
public class SkuCodeController {
  private final AccessService access;
  private final SkuCodeService service;

  public SkuCodeController(AccessService access, SkuCodeService service) {
    this.access = access;
    this.service = service;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "catalog." + permission);
    return access.run(actor, "catalog." + permission, () -> work.apply(actor));
  }

  // ------------------------------------------------------------ 规则

  @GetMapping("/rules")
  public List<SkuCodeRule> rules(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "sku.read", a -> service.rules());
  }

  /** 新建规则（id 缺省）。展开长度与字符集在保存时就校验，不等到批量生成才发现。 */
  @PostMapping("/rules")
  public SkuCodeRuleSaved createRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody SkuCodeRuleInput input) {
    return call(auth, "sku.update", a -> service.saveRule(a, null, input));
  }

  /** 更新规则，走 version 乐观锁，不符直接 409。 */
  @PutMapping("/rules/{id}")
  public SkuCodeRuleSaved updateRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody SkuCodeRuleInput input) {
    return call(auth, "sku.update", a -> service.saveRule(a, id, input));
  }

  // ------------------------------------------------------------ 概览

  @GetMapping("/overview")
  public SkuCodeOverview overview(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "sku.read", a -> service.overview());
  }

  // ------------------------------------------------------------ 单 SKU 编码与条码

  @GetMapping("/skus/{id}")
  public SkuCodePanel panel(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "sku.read", a -> service.panel(id));
  }

  /** 手工输入内部编码。唯一允许覆盖既有编码的路径。 */
  @PutMapping("/skus/{id}/code")
  public SkuCodeSaved setCode(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody SkuCodeInput input) {
    return call(auth, "sku.update", a -> service.setCode(a, id, input));
  }

  /** 录入或扫入一个外部条码；唯一性冲突 409。 */
  @PostMapping("/skus/{id}/identifiers")
  public SkuIdentifierSaved addIdentifier(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody SkuIdentifierInput input) {
    return call(auth, "sku.update", a -> service.addIdentifier(a, id, input));
  }

  @DeleteMapping("/skus/{id}/identifiers/{identifierId}")
  public Map<String, Boolean> deleteIdentifier(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @PathVariable UUID identifierId) {
    call(
        auth,
        "sku.update",
        a -> {
          service.deleteIdentifier(a, id, identifierId);
          return Boolean.TRUE;
        });
    return Map.of("deleted", true);
  }

  // ------------------------------------------------------------ 批量

  /** 按规则自动生成（GENERATE）或批量补齐（BACKFILL）；dryRun=true 为预览。 */
  @PostMapping("/batch")
  public SkuCodeBatchResult batch(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody SkuCodeBatchInput input) {
    return call(auth, "sku.update", a -> service.batch(a, input));
  }

  /** 读回一个批次的完整明细，含被拒行与原因。 */
  @GetMapping("/batches/{id}")
  public SkuCodeBatchResult batch(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "sku.read", a -> service.batchResult(id));
  }

  /** 条码批量导入：逐行接受/拒绝，拒绝原因随行返回。 */
  @PostMapping("/identifiers/import")
  public SkuIdentifierImportResult importIdentifiers(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody SkuIdentifierImportInput input) {
    return call(auth, "sku.update", a -> service.importIdentifiers(a, input));
  }
}
