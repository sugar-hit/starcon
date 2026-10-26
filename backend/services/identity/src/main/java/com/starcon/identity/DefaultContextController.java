package com.starcon.identity;

import static com.starcon.identity.DefaultContextModels.DefaultContextCapabilities;
import static com.starcon.identity.DefaultContextModels.DefaultContextInput;
import static com.starcon.identity.DefaultContextModels.DefaultContextOptions;
import static com.starcon.identity.DefaultContextModels.DefaultContextView;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 1.4.2.2 默认店铺与仓库 —— 「默认上下文」端点（identity 服务）。
 *
 * <p><b>为什么在 identity</b>：默认上下文是<b>纯个人偏好</b>，与 1.4 个人设置同域；
 * 店铺 / 法人 / 仓库这三个事实分别属 02 / 17.1 / 08，本服务**不**持有它们的副本，
 * 只经它们的授权只读端点读清单（见 {@link DefaultContextService}）。
 *
 * <p><b>租户与 membership_id 都来自已认证会话</b>：{@code X-Tenant-Id} 头在这里从头到尾
 * 没有出现过，伪造它改不动 {@code app.current_tenant}，也拿不到别人的 membership_id；
 * 请求体里也**不接受** membershipId / userId / tenantId。
 *
 * <p><b>为什么没有独立权限码</b>：本端点只能碰到「我自己」的默认上下文 ——
 * 边界是<b>数据归属</b>（tenant_id + membership_id + RLS），不是功能开关。
 * 凡是会暴露**别人**数据的入口才需要独立权限码。
 *
 * <p><b>下游怎么用</b>：09 订单履约 / 07 库存核算 / 13 利润分析 的页面拿
 * {@code GET /identity/default-context} 注入初始筛选。返回的是<b>生效后</b>的上下文
 * （失效项已被剔除并回落），下游不需要自己再判断一遍。
 */
@RestController
@RequestMapping("/identity/default-context")
public class DefaultContextController {

  private final IdentityController identity;
  private final DefaultContextService defaults;

  public DefaultContextController(IdentityController identity, DefaultContextService defaults) {
    this.identity = identity;
    this.defaults = defaults;
  }

  /**
   * 统一入口：先确认会话（租户与 membership_id 都从这里来）。
   *
   * <p>刻意**不**接收任何身份参数：请求里带 membershipId 就是把「只能改自己的」这条边界
   * 交给客户端了。
   */
  private <T> T call(String auth, Function<IdentityController.Access, T> work) {
    IdentityController.Access actor = identity.resolve(auth);
    if (actor.membershipId() == null) {
      throw IdentityController.error(HttpStatus.UNAUTHORIZED, "登录已失效，请重新登录");
    }
    return work.apply(actor);
  }

  /**
   * 读当前生效的默认上下文。
   *
   * <p>从没设过时返回 {@code stored=false}（不是 404）；存过但已失效时 {@code stored=true}
   * 而生效值为空，失效原因在 {@code stale} 里。
   */
  @GetMapping
  public DefaultContextView read(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, actor -> defaults.read(actor, auth));
  }

  /**
   * 保存默认上下文（<b>整体覆盖</b>：请求体里给 null 的维度等于清空它）。
   *
   * <p>选中的店铺 / 法人 / 仓库必须落在上游授权只读端点返回的可见清单里，
   * 否则 400；来源读不到时 503（读不到就不能确认在不在权限范围内）。
   */
  @PutMapping
  public DefaultContextView save(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody DefaultContextInput input) {
    return call(auth, actor -> defaults.save(actor, auth, input));
  }

  /** 重置默认上下文（删掉那一行 = 回到「什么都没设」）。幂等：本来没存过也算成功。 */
  @DeleteMapping
  public void reset(@RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    call(
        auth,
        actor -> {
          defaults.reset(actor);
          return null;
        });
  }

  /** 三个维度的候选集（店铺 / 法人 / 仓库），各自带来源状态。 */
  @GetMapping("/options")
  public DefaultContextOptions options(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, actor -> defaults.options(actor, auth));
  }

  /** 能力声明：哪些维度真接通、哪些没接通、缺什么、归谁。 */
  @GetMapping("/capabilities")
  public DefaultContextCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, actor -> defaults.capabilities(actor, auth));
  }

  /**
   * 本模块的错误一律带上**一句人话**（ProblemDetail）。
   *
   * <p>为什么在本控制器里处理而不是靠默认行为：默认的错误页在容器里才渲染，
   * 直接调服务的场景（前端 {@code fetch}、服务间调用）拿到的可能是一个**空响应体** ——
   * 「保存失败」四个字之外没有任何线索，用户和调用方都无从判断是「不在权限范围内」
   * 还是「上游没接通」。这两种情况的处置完全不同，必须落到响应体里。
   */
  @org.springframework.web.bind.annotation.ExceptionHandler(
      org.springframework.web.server.ResponseStatusException.class)
  org.springframework.http.ResponseEntity<org.springframework.http.ProblemDetail> handle(
      org.springframework.web.server.ResponseStatusException e) {
    var status = e.getStatusCode();
    String reason = e.getReason() == null || e.getReason().isBlank() ? "请求无法完成" : e.getReason();
    return org.springframework.http.ResponseEntity.status(status)
        .body(org.springframework.http.ProblemDetail.forStatusAndDetail(status, reason));
  }
}
