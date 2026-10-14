package com.starcon.trade;

import static com.starcon.trade.BomBindingModels.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 6.4.1.2 BOM 关联。
 *
 * <p>命名空间 {@code /trade/processing}（与 6.4.1.1 同一控制器根）： 卷 06 的前端入口是 {@code
 * /purchase/processing}，浏览器只经 /api/trade 代理访问； 后端路径按<b>服务</b>命名空间走，与 9.3.2.2（前端 /orders、后端
 * /trade/fulfillment/routing） 同一纪律。
 *
 * <p><b>挂在 processing 之下而不是新建一个 bom 根</b>：本模块的输入是 「一张加工单 + 一个 BOM 版本」，输出是「这张加工单的冻结快照」。 裸 {@code
 * /trade/processing/bom-bindings} 这种「按快照列全量」的接口 <b>刻意不存在</b> ——
 * 快照永远从属于一张加工单，没有「这张快照属于谁」的问题就不该有列表接口。
 *
 * <p>权限码沿用 6.4.1.1 的 {@code trade.processing.read} / {@code trade.processing.update}：
 * 冻结等于让仓库照着这棵树备料，不是读权限能带过去的动作。
 *
 * <p>租户<b>不</b>来自任何请求头：先向 identity 确认已认证成员关系与权限， 再在一个事务里绑定 {@code app.current_tenant}，由 RLS
 * 在数据库层强制隔离。 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 */
@RestController
@RequestMapping("/trade/processing")
public class BomBindingController {

  private final AccessService access;
  private final BomBindingService bindings;

  public BomBindingController(AccessService access, BomBindingService bindings) {
    this.access = access;
    this.bindings = bindings;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "trade." + permission);
    return access.run(actor, "trade." + permission, () -> work.apply(actor));
  }

  /**
   * 冻结一张加工单的 BOM 展开结果。
   *
   * <p>蓝图「输入：BOM 版本选择；冻结动作」两个输入在同一个请求里： {@code bomVersionId} 是版本选择，请求本身就是冻结动作。
   *
   * <p><b>POST 而不是 PUT</b>：冻结是**不可逆的一次性动作**， 且一张单只能有一份快照（UNIQUE + 触发器钉死）。 PUT
   * 的语义是「把这份资源改成这样」，与「冻结之后永不可改」正好相反， 用 PUT 会让调用方以为覆盖是合法的。
   *
   * <p>已经冻结过时返回既有那份（{@code alreadyFrozen=true}）而不是报错： 冻结是幂等的，重试不该让运营以为自己没冻上。
   */
  @PostMapping("/orders/{orderId}/bom-binding")
  public BomFreezeResult freeze(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID orderId,
      @Valid @RequestBody BomFreezeInput input) {
    return call(auth, "processing.update", a -> bindings.freeze(orderId, input, a.userId(), auth));
  }

  /**
   * 读一张加工单的 BOM 绑定：冻结快照 + 「此刻生效的是不是同一版」。
   *
   * <p>{@code drifted} 为真表示 BOM 已接替新版本 —— 页面据此告警， 但<b>已下达的加工单不受影响</b>，因为快照已经冻结，领料按快照走。
   */
  @GetMapping("/orders/{orderId}/bom-binding")
  public BomBinding binding(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID orderId) {
    return call(auth, "processing.read", a -> bindings.binding(orderId, auth));
  }

  /**
   * 只读展开树（按 path 稳定输出，父行先于子行）。
   *
   * <p>与 {@code GET .../bom-binding} 的差别：绑定接口会带 lines 与漂移判定 （它要调 catalog 做规则 R4 的时刻查询），本接口是**纯本地读**
   * —— 不发任何跨服务请求，展开树几百行时用它单独取明细更快。
   *
   * <p>没有快照时返回**空数组**而不是 404：加工单存在，只是还没冻结， 这是正常业务状态（前端据此提示去冻结），不是路径错误。
   */
  @GetMapping("/orders/{orderId}/bom-binding/lines")
  public List<BomSnapshotLine> lines(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID orderId) {
    return call(auth, "processing.read", a -> bindings.lines(orderId));
  }
}
