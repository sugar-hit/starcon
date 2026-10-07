package com.starcon.catalog;

import static com.starcon.catalog.CatalogModels.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.*;

/**
 * 3.6.1.1 往来单位档案：业务主体（企业或个人）+ 角色指派（供应商 / 客户 / 承运商）、
 * 状态流转、软删除，以及引用候选。
 *
 * <p>路由挂在 {@code /catalog/parties} 之下，<b>不挂 {@code /products}</b>：
 * 往来单位不是商品的属性，而是与商品平级的主数据（商品引用它，采购/售后/财务也引用它）。
 * 台账里的 {@code /partners} 是<b>前端页面路径</b>，与 {@code /products/brands} 同一性质。
 *
 * <p>权限沿用商品主数据域：读 {@code catalog.spu.read}，写 {@code catalog.spu.update}。
 * 往来单位属于商品主数据域（蓝图卷 03），不新造权限词表 ——
 * 新词表要改 identity 服务的迁移，属于另一个服务的写入所有权。
 *
 * <p>租户一律来自已认证成员关系（{@link AccessService} 向 identity 解析），
 * <b>不读 {@code X-Tenant-Id} 头</b>：那是客户端自称的租户，AGENTS.md 明令禁止。
 */
@RestController
@RequestMapping("/catalog/parties")
public class PartyController {
  private final AccessService access;
  private final PartyService service;

  public PartyController(AccessService access, PartyService service) {
    this.access = access;
    this.service = service;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "catalog." + permission);
    return access.run(actor, "catalog." + permission, () -> work.apply(actor));
  }

  // ------------------------------------------------------------ 概览与列表

  /**
   * 概览：规模、状态分布、角色分布与两个治理缺口指标。
   *
   * <p>最要紧的是 {@code multiRoleCount}（同时是供应商和客户的主体数）——
   * 它证明「同一主体一份记录就够用」这条蓝图要求正在被遵守；
   * 它下降说明有人开始建重复主体了。其次是 {@code noRoleCount}。
   */
  @GetMapping("/overview")
  public PartyOverview overview(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "spu.read", a -> service.overview());
  }

  /**
   * 往来单位列表：分页 + 关键词搜索（编码 / 名称 / 邮箱 / 电话）+ 主体类型 / 状态 / 角色 /
   * 国家 / 可用性筛选。
   *
   * <p>列表行自带角色集合与可用性判定：蓝图那句「同一主体可同时是供应商和客户」
   * 要在列表上一眼看见，否则运营会去建第二条主体。
   */
  @GetMapping
  public PartyPage list(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String partyType,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String roleType,
      @RequestParam(required = false) String countryCode,
      @RequestParam(required = false) Boolean includeDeleted,
      @RequestParam(required = false) Boolean usable,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(
        auth,
        "spu.read",
        a ->
            service.list(
                new PartyQuery(
                    keyword, partyType, status, roleType, countryCode, includeDeleted, usable, page, size)));
  }

  /**
   * 引用候选：06 采购 / 11 售后 / 12 财务选往来单位时调的那一个端点。
   *
   * <p>默认只给<b>可用</b>的主体（ACTIVE 且至少一个 ACTIVE 角色）。
   * {@code includeBlocked=true} 时连不可用的也返回（带原因码），
   * 用于「历史单据上的这个供应商为什么现在选不到了」要显示出来而不是让它凭空消失。
   */
  @GetMapping("/candidates")
  public List<PartyRow> candidates(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String roleType,
      @RequestParam(required = false) String keyword,
      @RequestParam(defaultValue = "false") boolean includeBlocked) {
    return call(auth, "spu.read", a -> service.candidates(roleType, keyword, includeBlocked));
  }

  // ------------------------------------------------------------ 主体读写

  /** 往来单位详情：主体 + 角色指派 + 可用性判定，同一时刻的一致快照。 */
  @GetMapping("/{id}")
  public PartyDetail detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "spu.read", a -> service.detail(id));
  }

  /**
   * 新建往来单位。
   *
   * <p>建档时状态就要决定：状态为 ACTIVE 时必须至少带一个 ACTIVE 角色（规则 P5）。
   * 不想当场定角色的，先建成 INACTIVE，指派完角色再启用。
   */
  @PostMapping
  public PartySaved create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody PartyInput input) {
    return call(auth, "spu.update", a -> service.save(a, null, input));
  }

  /**
   * 改往来单位：名称、类型、国家、联系方式、状态与角色指派（<b>整体替换</b>）。
   *
   * <p>编码建后不可改：它是采购单、应付账款与售后单对齐这个主体的锚点。
   * 角色的整体替换语义意味着 {@code roles} 提交什么就等于什么，
   * {@code null} 等价于「清空全部角色」—— 所以编辑表单必须先取回详情再提交。
   */
  @PutMapping("/{id}")
  public PartySaved update(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody PartyInput input) {
    return call(auth, "spu.update", a -> service.save(a, id, input));
  }

  /**
   * 状态流转（激活 / 停用 / 拉黑 / 解除拉黑）。
   *
   * <p>独立端点而不是复用保存：流转只改状态，走保存路径会让人以为「改状态要重传全部字段」，
   * 而那正好是并发覆盖的来源。进出拉黑必须带 {@code reason}（规则 P4）。
   */
  @PutMapping("/{id}/status")
  public PartySaved setStatus(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody PartyStatusInput input) {
    return call(auth, "spu.update", a -> service.setStatus(a, id, input));
  }

  /**
   * 软删除：打 {@code deleted_at}，行仍在。
   *
   * <p>删除前必须先解掉全部角色，否则会留下「角色还在、主体不见了、详情页 404」的状态。
   * 刻意<b>没有</b>物理删除：采购单、应付、售后单都会引用这个 id。
   */
  @DeleteMapping("/{id}")
  public Map<String, Boolean> delete(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam long version) {
    call(
        auth,
        "spu.update",
        a -> {
          service.delete(a, id, version);
          return Boolean.TRUE;
        });
    return Map.of("deleted", true);
  }

  // ------------------------------------------------------------ 角色指派

  /** 角色指派列表：按角色类型固定顺序输出。 */
  @GetMapping("/{id}/roles")
  public List<PartyRole> roles(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "spu.read", a -> service.rolesOf(id));
  }

  /**
   * 指派一个角色。
   *
   * <p>同一主体同一角色只允许一条 —— 重复指派会被拒（DB 也有唯一约束兜底）。
   * 停用或拉黑的主体不允许新增角色（规则 P6），要先启用主体。
   */
  @PostMapping("/{id}/roles")
  public PartySaved createRole(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody PartyRoleEditInput input) {
    return call(auth, "spu.update", a -> service.saveRole(a, id, null, input));
  }

  /**
   * 改一条角色指派：使用状态与备注。
   *
   * <p>角色类型<b>建后不可改</b>：主体可以同时是供应商和客户，但每个角色类型只有一条档案，
   * 要换角色请先解指派现有角色再指派新的。
   */
  @PutMapping("/{id}/roles/{roleId}")
  public PartySaved updateRole(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @PathVariable UUID roleId,
      @Valid @RequestBody PartyRoleEditInput input) {
    return call(auth, "spu.update", a -> service.saveRole(a, id, roleId, input));
  }

  /**
   * 解除一个角色指派（物理删除这一行）。
   *
   * <p>与主体不同，角色指派允许物理删除：它是「这个主体扮演这个身份」的当前事实，
   * 没有「曾经有过供应商身份、后来解除了」这种回溯需求 ——
   * 已发生单据里的角色是单据自己的冻结快照，不靠这张表回溯。
   */
  @DeleteMapping("/{id}/roles/{roleId}")
  public PartySaved removeRole(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @PathVariable UUID roleId,
      @RequestParam long version) {
    return call(auth, "spu.update", a -> service.removeRole(a, id, roleId, version));
  }
}
