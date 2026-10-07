package com.starcon.catalog;

import static com.starcon.catalog.CatalogModels.*;

import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.*;

/**
 * 3.6.1.2 角色档案：供应商 / 客户 / 承运商三个身份的角色特有信息，
 * 以及蓝图点名的输出「供 06 采购单、12 应收应付默认取值」。
 *
 * <p>路由分两处，理由是**读取粒度不同**：
 *
 * <ul>
 *   <li>{@code /catalog/role-profiles/*} —— 跨主体的档案台账（概览 / 列表 / 词表）。
 *       运营要问的是「哪些供应商还没建档案」「哪些风险客户仍然留着额度」，
 *       这类问题的主体不固定，所以它是一个集合资源。
 *   <li>{@code /catalog/parties/{id}/{roleType}-profile} —— 单个主体的档案读写。
 *       挂在 {@code parties} 之下而不是 {@code role-profiles/{partyId}}，是因为
 *       **档案是主体的属性而不是独立实体**：档案没有自己的编码、没有独立的生命周期，
 *       它的身份完全由（租户, 角色）决定。这也是 V30 把它键在 role_id 上的同一个理由。
 * </ul>
 *
 * <p>页面路径是台账里记的 {@code /partners}（与 3.6.1.1 同一路由），
 * 后端前缀沿用 {@code /catalog/parties}，与 3.6.1.1 完全一致。
 *
 * <p>权限沿用商品主数据域：读 {@code catalog.spu.read}，写 {@code catalog.spu.update}。
 * 不新造权限词表 —— 那要改 identity 服务的迁移，属于另一个服务的写入所有权。
 *
 * <p>租户一律来自已认证成员关系（{@link AccessService} 向 identity 解析），
 * <b>不读 {@code X-Tenant-Id} 头</b>：那是客户端自称的租户，AGENTS.md 明令禁止。
 */
@RestController
@RequestMapping("/catalog")
public class PartyRoleProfileController {
  private final AccessService access;
  private final PartyRoleProfileService service;

  public PartyRoleProfileController(AccessService access, PartyRoleProfileService service) {
    this.access = access;
    this.service = service;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "catalog." + permission);
    return access.run(actor, "catalog." + permission, () -> work.apply(actor));
  }

  // ------------------------------------------------------------ 跨主体的档案台账

  /**
   * 概览：三种角色的档案覆盖率 + 两个治理缺口指标。
   *
   * <p>{@code missingProfileCount} 是最该被盯住的数字：已指派身份却没建档案的条数，
   * 也就是 06 采购 / 12 应收**取不到默认值**的那些对手方。
   * 最后两个指标正常都应为 0 —— 它们是绕过服务层写进来的脏数据的探针。
   */
  @GetMapping("/role-profiles/overview")
  public PartyRoleProfileOverview overview(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "spu.read", a -> service.overview());
  }

  /**
   * 词表选项：前端下拉的数据源。
   *
   * <p>与后端词表同源（枚举只增不改这条承诺要在**一处**兑现）。
   * 币种与采购单位刻意不在这里给列表：那是 12 财务与 3.3.3 的资产，
   * 在本模块硬编码一份等于复制了别人的写入所有权。
   */
  @GetMapping("/role-profiles/options")
  public PartyRoleProfileOptions options(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam String roleType) {
    return call(auth, "spu.read", a -> service.options(roleType));
  }

  /**
   * 角色档案列表：按角色类型分页，支持关键词 / 分级 / 能力 / 结算方式 / 币种 /
   * 「只看缺档案」筛选。
   *
   * <p>计数与取行引同一处筛选条件，所以「筛出来的总数」与「翻页翻到底」一定对得上。
   * 默认只列 ACTIVE 角色（已停用的身份当下不会被下游用来取默认值），
   * {@code activeOnly=false} 才把停用的也带上。
   */
  @GetMapping("/role-profiles")
  public PartyRoleProfilePage list(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam String roleType,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String level,
      @RequestParam(required = false) String capability,
      @RequestParam(required = false) String settlementMethod,
      @RequestParam(required = false) String defaultCurrency,
      @RequestParam(required = false) Boolean missingProfile,
      @RequestParam(required = false) Boolean activeOnly,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(
        auth,
        "spu.read",
        a ->
            service.list(
                new PartyRoleProfileQuery(
                    roleType, keyword, level, capability, settlementMethod, defaultCurrency,
                    missingProfile, activeOnly, page, size)));
  }

  // ------------------------------------------------------------ 单主体的档案读写

  /**
   * 三张角色档案的汇总（详情页一次拿全，同一时刻的一致快照）。
   *
   * <p>合成一个端点而不是三个：详情页要显示的就是这三块的同一瞬间，
   * 拆开的话运营在「供应商身份刚被别人解掉」之后看到的页面会是拼接出来的旧数据。
   */
  @GetMapping("/parties/{id}/role-profiles")
  public PartyRoleProfiles aggregate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "spu.read", a -> service.aggregate(id));
  }

  /**
   * 供应商角色档案（详情 / 06 采购单取默认值的读出口）。
   *
   * <p>返回 200 + {@code profileFound=false} 而不是 404 的两种情况：
   * 「这个主体还没被指派供应商身份」与「指派了但还没建档案」——
   * 详情页要能把它们<b>长得不一样</b>，而 404 只能表达成「什么都没了」。
   * 真正的主体不存在才 404。写入路径相反：没有对应角色时给可读的 409（规则 R3）。
   */
  @GetMapping("/parties/{id}/supplier-profile")
  public PartySupplierProfile supplier(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "spu.read", a -> service.supplier(id));
  }

  /** 客户角色档案（详情 / 12 应收取额度与账期的读出口）。 */
  @GetMapping("/parties/{id}/customer-profile")
  public PartyCustomerProfile customer(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "spu.read", a -> service.customer(id));
  }

  /** 承运商角色档案（详情 / 10 物流按能力选承运商的读出口）。 */
  @GetMapping("/parties/{id}/carrier-profile")
  public PartyCarrierProfile carrier(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "spu.read", a -> service.carrier(id));
  }

  /**
   * 建 / 改供应商角色档案。
   *
   * <p>MOQ 的数量与单位必须成对提交（只给「100」或只给「件」都说不清起订门槛）；
   * 采购单位必须已在 3.3.3 登记且启用、允许采购。
   * 结算方式与账期由服务端互判（规则 **R1**）：账期结算必须大于 0 天，
   * 其余三种方式一律 0 天。
   */
  @PutMapping("/parties/{id}/supplier-profile")
  public PartySupplierProfile saveSupplier(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody PartySupplierProfileInput input) {
    return call(auth, "spu.update", a -> service.saveSupplier(a, id, input));
  }

  /**
   * 建 / 改客户角色档案。
   *
   * <p>信用额度<b>留空 = 不限制赊账</b>，而 0 是非法值 —— 「额度为 0」与「没设额度」
   * 在业务上是<b>相反</b>的两种默认（完全不许赊 vs 不限制），折叠成同一个值之后必错其一。
   * 填了额度就必须填默认币种（规则 **R2**）；风险客户的额度必须留空（规则 **R4**）。
   */
  @PutMapping("/parties/{id}/customer-profile")
  public PartyCustomerProfile saveCustomer(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody PartyCustomerProfileInput input) {
    return call(auth, "spu.update", a -> service.saveCustomer(a, id, input));
  }

  /**
   * 建 / 改承运商角色档案。
   *
   * <p>服务能力是<b>数组</b>提交；大小写会被规范化成词表里的形态
   * （存成 {@code sea} 会在 {@code @> ARRAY['SEA']} 的查询里静默不匹配）。
   * 账号标识必填，但**只存账号、不存密钥** —— 凭据由部署侧从 secret manager 按这个键注入。
   */
  @PutMapping("/parties/{id}/carrier-profile")
  public PartyCarrierProfile saveCarrier(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody PartyCarrierProfileInput input) {
    return call(auth, "spu.update", a -> service.saveCarrier(a, id, input));
  }

  /**
   * 删掉一张角色档案（角色指派本身保留 —— 解指派是 3.6.1.1 的动作，走它自己的端点）。
   *
   * <p>删掉之后 06/12 退回「没设过」（规则 **R5**）：额度不设 = 不限制、账期 0 = 现结。
   * 这是一个可逆且有意义的状态，不是需要历史追溯的凭证。
   */
  @DeleteMapping("/parties/{id}/{roleType}-profile")
  public Map<String, Boolean> delete(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @PathVariable String roleType,
      @RequestParam long version) {
    call(
        auth,
        "spu.update",
        a -> {
          service.delete(a, id, roleType, version);
          return Boolean.TRUE;
        });
    return Map.of("deleted", true);
  }
}