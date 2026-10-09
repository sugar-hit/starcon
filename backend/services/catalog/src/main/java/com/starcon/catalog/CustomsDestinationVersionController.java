package com.starcon.catalog;

import static com.starcon.catalog.CustomsDestinationVersionModels.*;

import jakarta.validation.Valid;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.*;

/**
 * 3.7.1.2 目的地报关版本：按目的地版本化的读写、发布与按目的地查询。
 *
 * <p>路径挂在 {@code /catalog/customs-destination-versions} 之下，与
 * {@code /catalog/customs-attributes}（3.7.1.1）、{@code /catalog/physical-safety}（3.7.2.1）
 * 同形：都是「针对某一个 SKU 的一个侧面」，而不是商品档案本身的一部分。
 *
 * <p><b>本控制器只有两个字面量子路径</b>（{@code effective} 与 {@code requirements}），
 * 它们不接 skuId 段而是走 query —— 因为下游（10 报关、16 合规）手里拿着的
 * 常常就是「这个 SKU 发到 DE 该报哪一版」这样的一组参数，而不是一个已知的版本 id。
 * 合成后的申报资料在 {@link CustomsDeclarationResolveController}
 * （{@code /catalog/customs-declaration/resolve}）：它是给报关清关用的那份成品，
 * 与本模块的「维护界面数据」不是一件事，分成两个控制器是刻意的。
 *
 * <p><b>三个动作、三个权限</b>：读 {@code catalog.compliance.read}、
 * 写 {@code catalog.compliance.update}、<b>发布 {@code catalog.compliance.publish}</b>。
 * 发布单列的理由见 {@link CustomsDestinationVersionService} 的类注释：
 * 能编辑目的地版本的人不一定有权把它发布成下游要用的那一份。
 */
@RestController
@RequestMapping("/catalog/customs-destination-versions")
public class CustomsDestinationVersionController {
  private final AccessService access;
  private final CustomsDestinationVersionService service;

  public CustomsDestinationVersionController(AccessService access, CustomsDestinationVersionService service) {
    this.access = access;
    this.service = service;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "catalog." + permission);
    return access.run(actor, "catalog." + permission, () -> work.apply(actor));
  }

  /**
   * 某 SKU 在某目的地的版本面板（含草稿与已发布，标出此刻生效的那个）。
   *
   * <p>{@code destination} <b>必填且不可通配</b>：目的地是这一组版本的业务主键之一，
   * 「不传 = 全部目的地」在本模块没有意义 —— 那正是蓝图要防的「一套打通全球」。
   */
  @GetMapping("/skus/{skuId}")
  public DestinationVersionProfile profile(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID skuId,
      @RequestParam String destination) {
    return call(auth, "compliance.read", a -> service.profile(skuId, destination));
  }

  /**
   * 保存一个目的地版本（草稿态）。
   *
   * <p><b>{@code requirements} 是完整清单而不是增量</b>：认证码唯一是跨行不变量，
   * 增量提交时前端要自己算最终集合。
   *
   * <p><b>已发布的版本不可直接改</b>（409）：正确路径是新建一个版本让新版本接替。
   */
  @PutMapping("/skus/{skuId}")
  public DestinationVersionSaved save(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID skuId,
      @Valid @RequestBody DestinationVersionInput input) {
    return call(auth, "compliance.update", a -> service.save(a, skuId, input));
  }

  /**
   * <b>按目的地查某时刻的生效版本</b>（规则 R4）—— 蓝图的「输出：按目的地查询的有效
   * 报关版本」就是这一条。
   *
   * <p>用 GET + query 而不是路径段：它是清关时的一次读，且必须能被 GET 缓存与重试
   * （重试一个写操作在语义上是危险的）。
   *
   * <p><b>没有生效版本时后端返回 409，不提供降级</b>：
   * 回退到基础报关属性就是「用一套申报资料打通全球」，所以这里宁可让下游拿不到。
   */
  @GetMapping("/effective")
  public EffectiveDestinationVersion effective(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam UUID skuId,
      @RequestParam String destination,
      @RequestParam(required = false) String at) {
    return call(auth, "compliance.read", a -> service.effective(skuId, destination, at));
  }

  /**
   * 认证要求清单（CE / FCC / RoHS / UKCA / 危包证 …），供 16 合规的认证台账读。
   *
   * <p>同样在无生效版本时报错而不是返回空列表 —— 返回空列表会被读成
   * 「这个目的地不需要任何认证」，而真实原因可能是「这个目的地还没维护」。
   */
  @GetMapping("/requirements")
  public DestinationRequirementList requirements(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam UUID skuId,
      @RequestParam String destination,
      @RequestParam(required = false) String at) {
    return call(auth, "compliance.read", a -> service.requirements(skuId, destination, at));
  }

  /**
   * 发布一个目的地版本（草稿 → 已发布），需 {@code catalog.compliance.publish}。
   *
   * <p>用独立端点而不是复用 PUT：发布是一个<b>状态流转</b>并留下发布留痕，
   * 混进保存会让「我只是想改个字」与「我把它发布成下游要用的那一份」无法区分。
   *
   * <p><b>区间重叠在这一刻才检查</b>（规则 R3）：草稿之间随便重叠，
   * 冲突由 DB 触发器拒绝并点名与哪一版撞了。
   */
  @PostMapping("/{versionId}/publish")
  public DestinationVersionRow publish(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID versionId,
      @Valid @RequestBody DestinationPublishInput input) {
    return call(
        auth,
        "compliance.publish",
        a -> service.publish(a, versionId, input, a.permissions().contains("catalog.compliance.publish")));
  }
}
