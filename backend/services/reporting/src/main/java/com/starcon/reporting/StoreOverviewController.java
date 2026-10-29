package com.starcon.reporting;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.function.Function;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 1.1.2.2 店铺概览的 HTTP 端点（卷 01 工作台与门户 L3 1.1.2 关键指标下的第二格）。
 *
 * <p><b>命名空间是 {@code /reporting/store-overview}</b>：与既有的
 * {@code /reporting/metric-cards}（1.1.2.1 核心指标卡）、
 * {@code /reporting/overview}（13.1.1.1 经营总览）、
 * {@code /reporting/store-comparison}（13.1.1.2 多店对比）并列，互不覆盖。
 *
 * <p>🔴 **租户不来自任何请求头**：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 *
 * <p>🔴 **本模块没有写端点**：卷 01 卷首写死「工作台只读 + 跳转」。
 * 店铺维度投影的刷新是 13.1.1.2 的职责（{@code POST /reporting/store-comparison/refresh}），
 * 本模块只读那份投影。
 *
 * <p>🔴 **权限码沿用 {@code reporting.store-comparison.read}**：本模块读的**就是**
 * 13.1.1.2 多店对比那套店铺维度投影读模型（同一批表、同一套口径、同一份 RLS），
 * 没有新增任何数据面能力 —— 另起一个权限码会让「谁能看店铺经营数据」出现两个答案，
 * 而新码还要去 identity 登记（那需要另一个服务的迁移版本号，本车道没有分配）。
 */
@RestController
@RequestMapping("/reporting/store-overview")
@Tag(name = "1.1.2.2 店铺概览")
public class StoreOverviewController {

  /** 本模块读数用的权限码（与 13.1.1.2 多店对比同一份数据面）。 */
  private static final String PERMISSION = "reporting.store-comparison.read";

  private final AccessService access;
  private final StoreOverviewService overview;

  public StoreOverviewController(AccessService access, StoreOverviewService overview) {
    this.access = access;
    this.overview = overview;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, PERMISSION);
    return access.run(actor, PERMISSION, () -> work.apply(actor));
  }

  // ================================================================ 能力声明

  @GetMapping("/capabilities")
  @Operation(
      summary = "能力声明：五个维度逐条报出指标码 / 接不接得通 / 口径归谁",
      description =
          "蓝图 1.1.2.2 点名的五个维度：销售额、订单量、利润率、库存天数、广告花费。"
              + "🔴 **「销售额」不是一个数**：店铺级成交金额分成平台侧口径与还原口径两个，"
              + "外加两者的口径差异额 —— 本模块三者并列呈现，只取其一等于抹掉口径差异。"
              + "🔴 **接不通的维度不给公式也不给分母** —— 给了就是编一个出来。")
  public StoreOverviewModels.StoreOverviewCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, actor -> overview.capabilities(actor.tenantId()));
  }

  // ================================================================ 店铺概览

  @GetMapping
  @Operation(
      summary = "店铺概览：按店铺横向对比当期表现 + 识别掉队店铺",
      description =
          "时间窗口由 window 选：TODAY（今日）/ YESTERDAY（昨日）/ THIS_MONTH（本月）/ CUSTOM（自定义区间）；"
              + "「今日 / 昨日 / 本月」的边界按**本租户时区**算（取自本租户店铺投影事实的分桶时区），不写死 UTC。"
              + "🔴 **不跨币种相加**：涉币维度按币种分列，界面上没有跨币种的「总计」。"
              + "🔴 掉队判定与 13.1.1.2 多店对比**完全同源**（环比增长率 ≤ −20%）。"
              + "工作台不下发租户号与范围参数：范围由服务端判定。")
  public StoreOverviewModels.StoreOverviewResponse overview(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false, defaultValue = "TODAY") String window,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false) String platformCode) {
    return call(
        auth,
        actor ->
            overview.overview(
                actor.tenantId(),
                window,
                from,
                to,
                platformCode == null || platformCode.isBlank() ? null : platformCode.trim()));
  }

  // ================================================================ 可见店铺集合

  @GetMapping("/shops")
  @Operation(
      summary = "可见店铺集合（筛选器用）+ 每个店铺在这个窗口里有没有事实",
      description =
          "店铺归属来自 channel 的店铺清单（经 13.1.1.2 的刷新链路投影到 reporting）。"
              + "🔴 可见范围由 **RLS 按会话租户**决定，租户不来自任何请求参数。")
  public java.util.List<StoreOverviewModels.StoreOverviewShop> shops(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false, defaultValue = "TODAY") String window,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false) String platformCode) {
    return call(
        auth,
        actor -> {
          var resolved = overview.resolveWindowFor(actor.tenantId(), window, from, to);
          return overview.visibleStores(
              actor.tenantId(),
              resolved.from(),
              resolved.to(),
              platformCode == null || platformCode.isBlank() ? null : platformCode.trim());
        });
  }
}
