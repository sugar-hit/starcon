package com.starcon.catalog;

import static com.starcon.catalog.CatalogModels.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.*;

/**
 * 3.6.3.1 往来单位地址：收发货地址的增删改查、用途与默认标记，
 * 以及<b>地址变更历史版本</b>（蓝图「输入：地址版本」）。
 *
 * <p>路由挂在 {@code /catalog/parties/{id}/addresses} 之下而不是另开一个顶级前缀：
 * 地址的归属就是往来单位，没有主体的地址是无意义的（V31 的复合外键把「跨主体挂用途」
 * 在 DB 层就挡住了）。这条路由与 {@link PartyController} 的 {@code /roles} 同一形状。
 *
 * <p>权限沿用商品主数据域：读 {@code catalog.spu.read}，写 {@code catalog.spu.update} ——
 * 与 3.6.1.1 同一套词表，不新造（新词表要改 identity 服务的迁移，属于另一个服务的写入所有权）。
 *
 * <p>租户一律来自已认证成员关系（{@link AccessService} 向 identity 解析），
 * <b>不读 {@code X-Tenant-Id} 头</b>：那是客户端自称的租户，AGENTS.md 明令禁止。
 */
@RestController
@RequestMapping("/catalog/parties/{id}/addresses")
public class PartyAddressController {
  private final AccessService access;
  private final PartyAddressService service;

  public PartyAddressController(AccessService access, PartyAddressService service) {
    this.access = access;
    this.service = service;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "catalog." + permission);
    return access.run(actor, "catalog." + permission, () -> work.apply(actor));
  }

  /**
   * 地址列表：分页 + 关键词（地址名 / 街道 / 城市 / 省州 / 邮编）+ 用途 / 国家筛选。
   *
   * <p>列表每行自带用途徽章、默认标记与最新版本号：默认收 / 发货地址是蓝图点名的能力，
   * 运营要在列表上一眼看出「哪个是默认提货地址」，而不是逐条点开详情。
   */
  @GetMapping
  public PartyAddressPage list(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String usage,
      @RequestParam(required = false) String countryCode,
      @RequestParam(required = false) Boolean includeDeleted,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(
        auth,
        "spu.read",
        a ->
            service.list(
                id, new PartyAddressQuery(keyword, usage, countryCode, includeDeleted, page, size)));
  }

  /**
   * 某个用途的默认地址 —— 06 采购（发货 / 收货）与 10 物流（提货）取默认地址走的就是它。
   *
   * <p>没有默认时 404 并说明「还没有默认地址」：单据侧要能区分
   * 「有人但没人设默认」（去档案里设一个）与「一个地址都没有」。
   */
  @GetMapping("/defaults")
  public PartyAddress defaultAddress(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam String usage) {
    return call(auth, "spu.read", a -> service.defaultAddress(id, usage));
  }

  /**
   * 引用候选：下游单据的下拉数据源。
   *
   * <p>默认只给<b>可选中</b>的地址；{@code includeBlocked=true} 时连不可选的也返回（带原因码），
   * 用于「历史单据上的这个地址为什么现在选不到了」要显示出来而不是让它凭空消失。
   */
  @GetMapping("/candidates")
  public List<PartyAddress> candidates(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam(required = false) String usage,
      @RequestParam(required = false) String keyword,
      @RequestParam(defaultValue = "false") boolean includeBlocked) {
    return call(auth, "spu.read", a -> service.candidates(id, usage, keyword, includeBlocked));
  }

  /** 地址详情。已软删除的地址仍可读并带 {@code deleted=true}。 */
  @GetMapping("/{addressId}")
  public PartyAddress detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @PathVariable UUID addressId) {
    return call(auth, "spu.read", a -> service.detail(id, addressId));
  }

  /**
   * 新建地址。
   *
   * <p>建档即写入版本 1：版本链上不能没有起点 —— 「第 1 版」正是 06 采购单建立时
   * 快照的那一版。拉黑的主体不允许新增（规则 A5）。
   */
  @PostMapping
  public PartyAddressSaved create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody PartyAddressInput input) {
    return call(auth, "spu.update", a -> service.save(a, id, null, input));
  }

  /**
   * 改地址：内容变了才追加一版历史（规则 A3）。
   *
   * <p>{@code usages} 是整体替换语义，与 3.6.1.1 的角色指派同一约定：
   * 提交什么就等于什么，所以编辑表单必须先取回详情再提交。
   * 而<b>只</b>改用途 / 默认标记应该走 {@code PUT /{id}/usages} ——
   * 那条路径不产生版本行（标签变化不是地址变化）。
   */
  @PutMapping("/{addressId}")
  public PartyAddressSaved update(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @PathVariable UUID addressId,
      @Valid @RequestBody PartyAddressInput input) {
    return call(auth, "spu.update", a -> service.save(a, id, addressId, input));
  }

  /** 只改用途与默认标记：不碰地址内容，<b>不产生版本行</b>（规则 A3）。 */
  @PutMapping("/{addressId}/usages")
  public PartyAddressSaved saveUsages(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @PathVariable UUID addressId,
      @Valid @RequestBody PartyAddressUsageSaveInput input) {
    return call(auth, "spu.update", a -> service.saveUsages(a, id, addressId, input));
  }

  /**
   * 设 / 取消某个用途的默认地址。
   *
   * <p>独立端点而不是复用保存：「换默认提货仓」是一次明确的动作，
   * 混进「改门牌号」里会让运营在编辑别的字段时悄悄换掉了默认地址。
   */
  @PutMapping("/{addressId}/default")
  public PartyAddressSaved setDefault(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @PathVariable UUID addressId,
      @Valid @RequestBody PartyAddressDefaultInput input) {
    return call(auth, "spu.update", a -> service.setDefault(a, id, addressId, input));
  }

  /**
   * 软删除地址。
   *
   * <p>删除前必须先改默认（规则 A4），报错点名是哪个用途的默认。
   * 刻意<b>没有</b>物理删除：06 采购单 / 10 物流单引用的是
   * {@code (address_id, version_no)} 指路牌，删掉地址会让指路牌指向不存在的行。
   */
  @DeleteMapping("/{addressId}")
  public Map<String, Boolean> delete(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @PathVariable UUID addressId,
      @RequestParam long version) {
    call(
        auth,
        "spu.update",
        a -> {
          service.delete(a, id, addressId, version);
          return Boolean.TRUE;
        });
    return Map.of("deleted", true);
  }

  /** 地址变更历史版本：最新一版在最前面。 */
  @GetMapping("/{addressId}/versions")
  public List<PartyAddressVersion> versions(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @PathVariable UUID addressId) {
    return call(auth, "spu.read", a -> service.versions(id, addressId));
  }

  /**
   * 取回<b>某一版</b> —— 冻结快照的回放出口。
   *
   * <p>06 采购单 / 10 物流单把 {@code (address_id, version_no)} 记在单据上，
   * 单据自己的快照列是权威（各自服务的写入所有权），这个端点是快照缺失时的只读兜底。
   * 「改地址不影响已发生单据」的形状写在
   * {@code V31__party_addresses_contacts.sql} 第四节。
   */
  @GetMapping("/{addressId}/versions/{versionNo}")
  public PartyAddressVersion version(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @PathVariable UUID addressId,
      @PathVariable int versionNo) {
    return call(auth, "spu.read", a -> service.version(id, addressId, versionNo));
  }
}