package com.starcon.catalog;

import static com.starcon.catalog.PricingTemplateModels.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 5.3.2.1 定价模板。
 *
 * <p>命名空间 {@code /catalog/pricing-templates}。页面路由 {@code /pricing/templates}。
 *
 * <p>权限沿用商品主数据域：读 {@code catalog.spu.read}，写 {@code catalog.spu.update}。
 * 不新造权限词表 —— 新词表要改 identity 服务的迁移，那是另一个服务的写入所有权。
 *
 * <p><b>租户不来自任何请求头</b>：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 客户端伪造的 {@code X-Tenant-Id} 进不来也改不动它。
 *
 * <p><b>🔴 建议价来自 5.3.1.1</b>：{@code POST /templates/{id}/apply} 产出的是**建议价**，
 * 不是生效价 —— 改价是 5.4.1.1 调价单的写入所有权（蓝图关系句：
 * 上游 = 5.3.1.1，下游 = 5.4.1.1）。
 */
@RestController
@RequestMapping("/catalog/pricing-templates")
public class PricingTemplateController {

  private final AccessService access;
  private final PricingTemplateService templates;

  public PricingTemplateController(AccessService access, PricingTemplateService templates) {
    this.access = access;
    this.templates = templates;
  }

  /** 统一入口：先向 identity 确认身份与权限（租户**不**来自任何客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "catalog." + permission);
    return access.run(actor, "catalog." + permission, () -> work.apply(actor));
  }

  /**
   * 能力声明：基准方法 / 调整项 / 适用对象 / 商品阶段四张词表 + 七条口径说明。
   *
   * <p>🔴 页面固定显示它 —— 蓝图那句「模板是"怎么算"，价目表是"算完是多少"」必须写在脸上。
   */
  @GetMapping("/capabilities")
  public PricingTemplateCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "spu.read", a -> templates.capabilities());
  }

  /** 适用对象目录：内部分类 + 品牌（店铺见 {@code /sites}）。 */
  @GetMapping("/directory")
  public TemplateDirectory directory(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "spu.read", a -> templates.directory());
  }

  /**
   * 目标店铺（站点）列表：来自 2.1.1.1 平台目录。
   *
   * <p>🔴 上游不可达时返回 <b>503</b>（理由写「读不到上游」），不用空列表冒充「没有站点」。
   */
  @GetMapping("/sites")
  public List<ProfitCalculatorModels.SiteOption> sites(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(
        auth,
        "spu.read",
        a -> {
          try {
            return templates.sites(auth);
          } catch (ChannelPlatformPort.ChannelPlatformUnavailableException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
          }
        });
  }

  /** 模板台账：真分页 + 关键词 / 商品阶段 / 基准方法 / 适用对象筛选。 */
  @GetMapping("/templates")
  public TemplatePage templates(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String productStage,
      @RequestParam(required = false) String baseMethod,
      @RequestParam(required = false) Boolean enabled,
      @RequestParam(required = false) String scopeType,
      @RequestParam(required = false) String scopeRef,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    return call(
        auth,
        "spu.read",
        a ->
            templates.list(
                new PricingTemplateService.TemplateQuery(
                    keyword,
                    productStage == null ? null : ProductStage.parse(productStage),
                    baseMethod == null ? null : BaseMethod.parse(baseMethod),
                    enabled,
                    scopeType == null ? null : ScopeType.parse(scopeType),
                    scopeRef,
                    page == null ? 0 : page,
                    size == null ? 20 : size)));
  }

  /** 模板详情 = 模板本身 + 调整项 + 适用对象。 */
  @GetMapping("/templates/{templateId}")
  public TemplateDetail template(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID templateId) {
    return call(auth, "spu.read", a -> templates.detail(templateId));
  }

  /** 新建模板。 */
  @PostMapping("/templates")
  public TemplateDetail create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody TemplateInput input) {
    return call(auth, "spu.update", a -> templates.save(a, null, input));
  }

  /** 修改模板（乐观锁：版本对不上时 409）。调整项与适用对象整体替换。 */
  @PutMapping("/templates/{templateId}")
  public TemplateDetail update(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID templateId,
      @Valid @RequestBody TemplateInput input) {
    return call(auth, "spu.update", a -> templates.save(a, templateId, input));
  }

  /** 删除模板（连同它的调整项与适用对象）。 */
  @DeleteMapping("/templates/{templateId}")
  public void delete(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID templateId) {
    call(
        auth,
        "spu.update",
        a -> {
          templates.delete(templateId);
          return null;
        });
  }

  /**
   * 一键应用到商品，生成**建议价**（蓝图输出句）。
   *
   * <p>🔴 建议价的每一个数字都来自 5.3.1.1 的测算结果；成本不完整时返回 <b>409</b>，
   * **不给**一个虚高的价，并把缺口清单带回。
   */
  @PostMapping("/templates/{templateId}/apply")
  public ApplyResult apply(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID templateId,
      @Valid @RequestBody ApplyRequest request) {
    return call(auth, "spu.update", a -> templates.apply(a, templateId, request, auth));
  }

  /** 应用留痕（分页）：每次「一键应用」都留一条，说明是哪个模板、算出多少建议价。 */
  @GetMapping("/applications")
  public ApplicationPage applications(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID templateId,
      @RequestParam(required = false) UUID skuId,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    return call(
        auth,
        "spu.read",
        a ->
            templates.listApplications(
                new PricingTemplateService.ApplicationQuery(
                    templateId, skuId, page == null ? 0 : page, size == null ? 20 : size)));
  }
}
