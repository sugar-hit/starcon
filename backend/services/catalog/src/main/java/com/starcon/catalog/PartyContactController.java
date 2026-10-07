package com.starcon.catalog;

import static com.starcon.catalog.CatalogModels.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.*;

/**
 * 3.6.3.1 往来单位联系人：带姓名的那个人是谁、怎么联系到他、谁是默认联系人。
 *
 * <p>路由挂在 {@code /catalog/parties/{id}/contacts} 之下：联系人的归属就是往来单位，
 * 没有主体的联系人是无意义的（V31 的复合外键挡住「跨主体挂联系人」）。
 * 与 {@link PartyController} 的 {@code /roles} 同一形状。
 *
 * <p>与主体自己的对外通道（{@code party.email / phone / website}）分工见
 * {@code V31__party_addresses_contacts.sql} 第零节：那边回答「怎么找到这个主体」，
 * 这里回答「跟这个主体对接的那个人是谁」。
 *
 * <p>权限沿用商品主数据域：读 {@code catalog.spu.read}，写 {@code catalog.spu.update}。
 * 租户一律来自已认证成员关系，<b>不读 {@code X-Tenant-Id} 头</b>。
 */
@RestController
@RequestMapping("/catalog/parties/{id}/contacts")
public class PartyContactController {
  private final AccessService access;
  private final PartyContactService service;

  public PartyContactController(AccessService access, PartyContactService service) {
    this.access = access;
    this.service = service;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "catalog." + permission);
    return access.run(actor, "catalog." + permission, () -> work.apply(actor));
  }

  /**
   * 联系人列表：按主体取（不分页，扇出量是人决定的），关键词覆盖姓名 / 职务 / 邮箱 / 电话 / 手机。
   *
   * <p>默认联系人排在最前：06 采购问交期时要找的通常就是那一个。
   */
  @GetMapping
  public List<PartyContact> list(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) Boolean primaryOnly) {
    return call(auth, "spu.read", a -> service.list(id, keyword, primaryOnly));
  }

  /**
   * 默认联系人 —— 06 采购 / 11 售后 / 12 财务「找谁签字」走的就是它。
   *
   * <p>没有默认联系人时 404 并说明原因：单据侧要能区分「有人但没人设默认」与「一条都没有」。
   */
  @GetMapping("/default")
  public PartyContact primary(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "spu.read", a -> service.primary(id));
  }

  /** 引用候选：下游单据的下拉数据源。默认只给可选中的，带原因码的用 includeBlocked。 */
  @GetMapping("/candidates")
  public List<PartyContact> candidates(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam(required = false) String keyword,
      @RequestParam(defaultValue = "false") boolean includeBlocked) {
    return call(auth, "spu.read", a -> service.candidates(id, keyword, includeBlocked));
  }

  /** 联系人详情。 */
  @GetMapping("/{contactId}")
  public PartyContact detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @PathVariable UUID contactId) {
    return call(auth, "spu.read", a -> service.detail(id, contactId));
  }

  /**
   * 新建联系人。
   *
   * <p>邮箱 / 电话 / 手机至少给一项（规则 C1）：三项都空的联系人在 06 采购问交期、
   * 10 物流约提货、11 售后约退货三处都用不了，而它看上去和真联系人完全一样 ——
   * 被拒的应该是建这一行，而不是等它变成僵尸之后再清理。
   */
  @PostMapping
  public PartyContactSaved create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody PartyContactInput input) {
    return call(auth, "spu.update", a -> service.save(a, id, null, input));
  }

  /**
   * 改联系人：姓名、职务、联系方式与备注。
   *
   * <p>{@code isPrimary} 在这里是**首次设定**用的；日常「换默认签字人」走
   * {@code PUT /{id}/primary} —— 那是一次明确的动作，不该混在编辑别的字段里顺手发生。
   */
  @PutMapping("/{contactId}")
  public PartyContactSaved update(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @PathVariable UUID contactId,
      @Valid @RequestBody PartyContactInput input) {
    return call(auth, "spu.update", a -> service.save(a, id, contactId, input));
  }

  /** 设 / 取消默认联系人。 */
  @PutMapping("/{contactId}/primary")
  public PartyContactSaved setPrimary(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @PathVariable UUID contactId,
      @Valid @RequestBody PartyContactPrimaryInput input) {
    return call(auth, "spu.update", a -> service.setPrimary(a, id, contactId, input));
  }

  /**
   * 删除联系人（<b>物理删除</b>这一行）。
   *
   * <p>与地址不同：联系人是「当前正在对接的那个人」的当前事实，没有回溯需求 ——
   * 已发生单据里的联系人姓名与电话是单据自己的冻结快照（与 V27 第七节对 06/11/12 的约定同源）。
   * 删除前必须先改默认（规则 C2）。
   */
  @DeleteMapping("/{contactId}")
  public Map<String, Boolean> delete(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @PathVariable UUID contactId,
      @RequestParam long version) {
    call(
        auth,
        "spu.update",
        a -> {
          service.delete(a, id, contactId, version);
          return Boolean.TRUE;
        });
    return Map.of("deleted", true);
  }
}