package com.starcon.trade;

import static com.starcon.trade.CustomsElementSetModels.*;

import jakarta.validation.Valid;
import java.time.OffsetDateTime;
import java.util.function.Function;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 10.5.1.2 申报要素入口。
 *
 * <p>命名空间 {@code /trade/logistics/customs-elements}，与 10.5.1.1 的
 * {@code /warehouse/customs-declarations}（报关单，**另一个服务**）、10.5.1.3 的
 * {@code /warehouse/duty-taxes}（关税，**另一个服务**）都**字面量不同**，
 * proxy 白名单也分得开 —— 三个模块都叫「报关清关」，但它们不是同一张表、不是同一个服务。
 *
 * <p>九个形状，刻意逐个列出、不做通配：
 *
 * <ul>
 *   <li>{@code capabilities} 能力与上游可用性（含币种 / 品牌类型 / 原产地规则三张词表 + 三条常驻口径）
 *   <li>{@code filter-options} 筛选下拉（<b>真实数据 DISTINCT</b>，不是硬编码词表）
 *   <li>{@code GET /list} 清单（真分页 + 真筛选 + 排序白名单，<b>汇总不受分页影响</b>）
 *   <li>{@code GET /{id}} 详情（清单行 + 补充要素项 + <b>缺失项提示</b> + 可执行动作）
 *   <li>{@code GET /upstream-preview} 上游合成申报资料（<b>跨服务</b>：3.7.1.2 目的地报关版本）
 *   <li>{@code POST /} 建集
 *   <li>{@code PUT /{id}} 改集（<b>仅草稿</b>；乐观锁）
 *   <li>{@code POST /{id}/publish} 发布（三道闸：上游版本存在 / 产地一致 / 要素齐备）
 *   <li>{@code POST /{id}/close} 向前关闭生效区间
 * </ul>
 *
 * <p>清单走 {@code /list} 而不是裸路径：与既有 {@code sales-orders/list} 同仓同一份写法，
 * 也让「裸路径 = 后端不存在的映射」这条反例继续成立。
 *
 * <p>权限码<b>复用</b> {@code trade.fulfillment.read} / {@code trade.fulfillment.update}，
 * 不新起 {@code trade.customs.*}：identity 只注册了既有的 fulfillment 页面资源权限码，
 * 用一个**没有被注册**的码会让连 OWNER 都 403（与 10.2.1.1 同一判断）。
 */
@RestController
@RequestMapping("/trade/logistics/customs-elements")
public class CustomsElementSetController {

  private final AccessService access;
  private final CustomsElementSetService elementSets;

  public CustomsElementSetController(AccessService access, CustomsElementSetService elementSets) {
    this.access = access;
    this.elementSets = elementSets;
  }

  /** 统一入口：先确认身份与权限（租户<b>不</b>来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(
      String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "trade." + permission);
    return access.run(actor, "trade." + permission, () -> work.apply(actor));
  }

  /** 能力声明：逐条说清哪个维度真能用、哪个属别的模块、哪个没有上游。 */
  @GetMapping("/capabilities")
  public ElementSetCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "fulfillment.read", a -> elementSets.capabilities());
  }

  /** 筛选下拉：目的地 / 状态 / SKU，全部来自真实数据 DISTINCT。 */
  @GetMapping("/filter-options")
  public ElementSetFilterOptions filterOptions(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "fulfillment.read", a -> elementSets.filterOptions());
  }

  /**
   * 申报要素集清单。
   *
   * <p>汇总（{@code summary}）来自一条<b>不带 LIMIT/OFFSET</b> 的独立查询，永远覆盖筛选后的全量。
   *
   * @param at 生效性判定时刻，绑 {@link OffsetDateTime}。刻意用带时区的类型而不是
   *     {@code LocalDateTime}：后者绑 timestamptz 会<b>静默偏 −28800 秒</b>（把 UTC 当本地），
   *     不抛异常、只是时刻错了 8 小时。留空 = 判定此刻。
   */
  @GetMapping("/list")
  public ElementSetPage list(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String destination,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String skuId,
      @RequestParam(required = false) Boolean missing,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String sort,
      @RequestParam(required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime at,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(
        auth,
        "fulfillment.read",
        a -> elementSets.list(destination, status, skuId, missing, keyword, sort, page, size, at));
  }

  /** 详情：清单行 + 补充要素项 + 缺失项提示 + 可执行动作 + 与 catalog 的边界声明。 */
  @GetMapping("/{id}")
  public ElementSetDetail elementSet(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "fulfillment.read", a -> elementSets.detail(id));
  }

  /**
   * 上游合成申报资料预览（<b>跨服务</b>，读 3.7.1.2 目的地报关版本）。
   *
   * <p>建集前先看这里：它同时给出**归类事实**（税号 / 产地 / 法定单位）与**销售标题** ——
   * 后者正是「申报品名不能照搬销售标题」那道比对闸的基准，界面把两者并排显示，
   * 让运营一眼看出自己填的是清关口径还是转化文案。
   *
   * <p>取不到时后端返回 503（服务不可达）或 404（该目的地没有维护版本），
   * <b>不返回空对象</b>：空对象会被读成「这个目的地不用申报」，而真实情况可能是商品中心挂了。
   */
  @GetMapping("/upstream-preview")
  public ElementSetUpstreamPreview upstreamPreview(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam String skuId,
      @RequestParam String destination,
      @RequestParam(required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime at) {
    return call(
        auth,
        "fulfillment.read",
        a -> elementSets.preview(skuId, destination, at, auth));
  }

  /**
   * 建集（金额与数量 BigDecimal + NUMERIC，<b>禁浮点</b>）。
   *
   * <p>建集这一刻会经跨服务端口核实两件事：该目的地有生效的上游报关版本、
   * 以及取到真实销售标题用于比对申报品名。取不到一律拒绝建集 ——
   * 「跳过校验先建一个」正是那道闸形同虚设的开始。
   */
  // ⚠️ 刻意用**无值**的 @PostMapping（映射到类级路径本身），不写 "/"：
  //   Boot 3 不再把尾斜杠当等价路径，@PostMapping("/") 只匹配 .../customs-elements/，
  //   而浏览器经代理发过来的是 .../customs-elements（catch-all 段 join 之后没有尾斜杠）——
  //   那样建集接口会在浏览器里静默 404，而 service / integration test 全绿。
  //   trade 其余模块也一律用具名子路径（/authorizations、/sales），这里保持同一读法。
  @PostMapping
  public ElementSetSaved create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody ElementSetInput input) {
    return call(auth, "fulfillment.update", a -> elementSets.create(input, auth));
  }

  /** 改集（<b>仅草稿</b>，乐观锁）。已发布改正文要新建版本，服务层与 DB 触发器各拒一道。 */
  @PutMapping("/{id}")
  public ElementSetSaved update(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody ElementSetInput input) {
    return call(auth, "fulfillment.update", a -> elementSets.update(id, input, auth));
  }

  /** 发布：三道闸（上游版本存在 / 产地与归类口径一致 / 九件本体 + required 补充项齐备）。 */
  @PostMapping("/{id}/publish")
  public ElementSetSaved publish(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody ElementSetPublishInput input) {
    return call(
        auth,
        "fulfillment.update",
        a -> elementSets.publish(id, input, auth, a.membershipId()));
  }

  /** 向前关闭生效区间（规则 R5）：只允许收紧到一个仍在未来的时刻。 */
  @PostMapping("/{id}/close")
  public ElementSetSaved close(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody ElementSetCloseInput input) {
    return call(auth, "fulfillment.update", a -> elementSets.close(id, input));
  }
}
