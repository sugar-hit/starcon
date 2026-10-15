package com.starcon.channel;

import static com.starcon.channel.LogisticsChannelModels.*;

import jakarta.validation.Valid;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * 10.1.1.2 渠道配置。
 *
 * <p>端点按蓝图模块号标注，方便对照 10-logistics-and-freight.md。
 *
 * <p>🔴 本控制器**刻意没有承运商字段的写入口**：渠道挂在一个已有的承运商档案之下，
 * 承运商身份（名称 / 编码 / 接口类型 / 账号）归 10.1.1.1，本模块一个字段都不接受 ——
 * 复制一份就会造出「同一个承运商在两处各改一次」。
 *
 * <p>🔴 {@code POST /eligibility} 是蓝图加粗句的落点：渠道限制
 * （禁限品类 / 电池液体 / 磁力）由<b>服务端</b>判定，规则只有一份。
 * 下游（10.1.2.1 运费试算 / 10.1.2.2 渠道比价 / 9.3.2.1 分仓 / 10.2.1.2 运单申请）
 * 拿到结论与理由即可，**不应该也不需要自己再判断一遍**。
 *
 * <p>权限码<b>复用</b> identity 已有的 {@code channel.operation.*}
 * （与 10.1.1.1 承运商、10.3.2.1 交运批次同族），不需要改动 identity 服务。
 */
@RestController
@RequestMapping("/channel/logistics-channels")
public class LogisticsChannelController {
  private final AccessService access;
  private final LogisticsChannelService service;

  public LogisticsChannelController(AccessService access, LogisticsChannelService service) {
    this.access = access;
    this.service = service;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, permission);
    return access.run(actor, permission, () -> work.apply(actor));
  }

  private void callVoid(String bearer, String permission, Consumer<AccessService.Access> work) {
    var actor = access.require(bearer, permission);
    access.run(
        actor,
        permission,
        () -> {
          work.accept(actor);
          return null;
        });
  }

  // ---- 能力声明 ----

  @GetMapping("/capabilities")
  public LogisticsChannelCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.capabilities());
  }

  /** 概览：全量口径的计费方式 / 限制分布，**不受列表筛选与分页影响**。 */
  @GetMapping("/overview")
  public LogisticsChannelOverview overview(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.overview());
  }

  // ---- 渠道配置 ----

  /** 渠道配置列表：真分页 + 真筛选，汇总走独立查询不受 limit/offset 影响。 */
  @GetMapping
  public LogisticsChannelPage channels(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String carrierProfileId,
      @RequestParam(required = false) String billingMode,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String readiness,
      @RequestParam(required = false) String scopeKind,
      @RequestParam(required = false) String scopeValue,
      @RequestParam(required = false) String restrictionKind,
      @RequestParam(required = false, defaultValue = "NAME_ASC") String sort,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "channel.operation.read",
        a ->
            service.channels(
                keyword, carrierProfileId, billingMode, status, readiness, scopeKind, scopeValue,
                restrictionKind, sort, page, size));
  }

  @GetMapping("/{id}")
  public LogisticsChannelRow detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.read", a -> service.detail(UUID.fromString(id)));
  }

  /** 登记 / 改一个渠道配置。请求体<b>不接受</b>承运商身份字段。 */
  @PostMapping
  public LogisticsChannelRow save(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody SaveChannelRequest request) {
    return call(auth, "channel.operation.create", a -> service.save(request));
  }

  /** 撤下一条渠道配置（<b>不动</b>承运商档案 —— 那是 10.1.1.1 的主权）。 */
  @DeleteMapping("/{id}")
  public org.springframework.http.ResponseEntity<Void> delete(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    callVoid(auth, "channel.operation.delete", a -> service.delete(UUID.fromString(id)));
    return org.springframework.http.ResponseEntity.status(HttpStatus.NO_CONTENT).build();
  }

  // ---- 🔴 渠道限制判定（下游下单前问的那个问题）----

  /**
   * 判定一批渠道能否承运这个包裹。
   *
   * <p>结论三档：{@code PASS} 可承运 / {@code REVIEW} 需人工确认（不拦，但不允许
   * 自动放行）/ {@code BLOCK} 硬拦截（承运商会拒收并产生额外费用）。
   * 拦截理由逐条给出，**不合并成一句话** —— 运营需要知道是「液体」还是「磁力」被拦。
   *
   * <p>刻意<b>只读</b>：判定不产生任何副作用，所以用只读权限码，任何能看渠道的人
   * 都能在下单前自查。
   */
  @PostMapping("/eligibility")
  public ChannelEligibilityResult eligibility(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody ChannelEligibilityRequest request) {
    return call(auth, "channel.operation.read", a -> service.eligibility(request));
  }
}
