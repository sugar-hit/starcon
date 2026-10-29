package com.starcon.channel;

import static com.starcon.channel.AdDaypartModels.*;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Function;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 14.2.1.2 分时策略的 HTTP 端点。
 *
 * <p>命名空间是 {@code /channel/ad-dayparts}：14.2.2.1 用的是 {@code /channel/ad-budgets}、
 * 14.2.2.2 用的是 {@code /channel/ad-rules} —— 兄弟模块各有各的前缀，
 * 挤在同一个前缀下会让「哪个模块的哪个端点」变得说不清。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 *
 * <p>权限码：复用 identity 已有的 {@code channel.operation.read}（读策略 / 推荐 / 任务）
 * 与 {@code channel.operation.create}（建策略 / 改曲线 / 生成推荐 / 跑任务），
 * 与 14.1.1.1 活动列表、14.2.2.1 预算分配、14.2.2.2 自动规则同族 ⇒ <b>identity 零改动</b>。
 *
 * <p>🔴 <b>「定时」不在本服务里</b>：本服务<b>不装 {@code @Scheduled}</b>，
 * 定时调整任务由<b>外部调度</b>（K8s CronJob）调 {@code POST /tasks/run} 触发
 * —— 与本仓 V212 物流轨迹拉取、V317 侵权巡检、identity 外部推送同一纪律。
 */
@RestController
@RequestMapping("/channel/ad-dayparts")
@Tag(name = "14.2.1.2 分时策略")
public class AdDaypartController {

  private final AccessService access;
  private final AdDaypartService dayparts;

  public AdDaypartController(AccessService access, AdDaypartService dayparts) {
    this.access = access;
    this.dayparts = dayparts;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, permission);
    return access.run(actor, permission, () -> work.apply(actor));
  }

  // ================================================================ 能力声明

  @GetMapping("/capabilities")
  @Operation(
      summary = "能力声明：时区怎么定、系数怎么落钱、上限有几道闸、哪些确实做不了",
      description =
          "🔴 notAvailable 段列出的是**确实做不了**的部分，每条都写清责任模块与影响 —— "
              + "把「做不了」伪装成「能做」比说清楚更糟。")
  public AdDaypartCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", actor -> dayparts.capabilities());
  }

  // ================================================================ 店铺与时区

  @GetMapping("/shops")
  @Operation(
      summary = "店铺清单 + 各自的时区归属结论（**定不了的也列出来并写清原因**）",
      description =
          "🔴 店铺**没有**自己的时区字段：时区来自 channel 的**站点**参数"
              + "（shop_marketplace → marketplace.default_timezone）。"
              + "启用多个站点且时区不一致、或没启用站点的店铺 ⇒ **定不了时区**，"
              + "它们的策略**不许激活**（DB 层 CHECK 钉死）。")
  public List<AdDaypartShopTimezone> shops(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", actor -> dayparts.shopTimezones());
  }

  @GetMapping("/shops/{shopCode}/timezone")
  @Operation(summary = "单个店铺的时区归属结论")
  public AdDaypartShopTimezone shopTimezone(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String shopCode) {
    return call(auth, "channel.operation.read", actor -> dayparts.shopTimezone(shopCode));
  }

  // ================================================================ 策略（增删改查）

  @GetMapping("/strategies")
  @Operation(
      summary = "分时策略清单（真分页 + 真筛选）",
      description = "🔴 定不了时区的店铺也会出现在清单里（`timezoneStatus=UNRESOLVED`），"
          + "并写清原因 —— 删掉它们的话，页面只能说「这个店铺不在清单里」，那会让人以为数据缺失。")
  public AdDaypartStrategyPage strategies(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String shopCode,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "channel.operation.read",
        actor -> dayparts.strategies(shopCode, status, keyword, page, size));
  }

  @PostMapping("/strategies")
  @Operation(
      summary = "新建一条分时策略",
      description =
          "🔴 **一个店铺一条策略**：分时策略按店铺时区生效，"
              + "跨店铺合并会让其中一部分的小时错位。"
              + "🔴 时区由服务端按**站点参数**解析（不硬编码、不猜）；"
              + "定不了时区时策略建成 DRAFT，**激活会被拒**。")
  public AdDaypartStrategyView createStrategy(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody AdDaypartStrategyRequest request) {
    return call(
        auth,
        "channel.operation.create",
        actor -> {
          try {
            return dayparts.createStrategy(request);
          } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
          } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "策略写不进来：" + rootMessage(e));
          }
        });
  }

  @GetMapping("/strategies/{id}")
  @Operation(summary = "一条策略的详情（含 24 个小时的曲线）")
  public AdDaypartStrategyView strategy(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.read", actor -> dayparts.strategy(id));
  }

  @PutMapping("/strategies/{id}")
  @Operation(
      summary = "改一条策略的属性（曲线不在这里）",
      description = "🔴 每次保存都会**重新解析店铺时区** —— 站点的配置会变，策略不能拿着旧时区跑。")
  public AdDaypartStrategyView updateStrategy(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @RequestBody AdDaypartStrategyRequest request) {
    return call(
        auth,
        "channel.operation.create",
        actor -> {
          try {
            return dayparts.updateStrategy(id, request);
          } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
          } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "策略改不了：" + rootMessage(e));
          }
        });
  }

  @PutMapping("/strategies/{id}/curve")
  @Operation(
      summary = "整体替换分时曲线（一次成型）",
      description =
          "请求体是**完整的小时集合**，必须覆盖 0..23 全部 24 个小时 —— "
              + "缺一个小时，那个小时就落进「没有配置」的空洞。"
              + "🔴 动作 PAUSE（停投）时预算系数**必须**是 0（否则是自相矛盾的配置）。")
  public AdDaypartStrategyView replaceCurve(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @RequestBody AdDaypartCurveRequest request) {
    return call(
        auth,
        "channel.operation.create",
        actor -> {
          try {
            return dayparts.replaceCurve(id, request);
          } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
          } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "曲线写不进来：" + rootMessage(e));
          }
        });
  }

  @PostMapping("/strategies/{id}/status")
  @Operation(
      summary = "改策略状态（激活 / 暂停 / 归档）",
      description =
          "🔴 **激活会被拒**的两种情况：店铺**定不了时区**（跨时区店铺用错时区会完全失效）；"
              + "曲线 24 个小时全是「不动」（激活它等于什么都没配）。")
  public AdDaypartStrategyView changeStatus(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @RequestBody AdDaypartStatusRequest request) {
    return call(
        auth,
        "channel.operation.create",
        actor -> {
          try {
            return dayparts.changeStatus(id, request);
          } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
          } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "状态改不了：" + rootMessage(e));
          }
        });
  }

  @DeleteMapping("/strategies/{id}")
  @Operation(
      summary = "归档一条策略",
      description =
          "归档是**软删除**：曲线、基准、推荐与任务历史都还在，事后能复盘「当时为什么这么调」。")
  public AdDaypartStrategyView archiveStrategy(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.create", actor -> dayparts.archiveStrategy(id));
  }

  // ================================================================ 推荐曲线（上游 13.2.1.2）

  @PostMapping("/recommendations")
  @Operation(
      summary = "按历史出单时段生成推荐分时曲线",
      description =
          "上游 = **13.2.1.2 分时与分周模式**（reporting 服务）："
              + "本模块**只经授权只读 API**读它，转发调用方自己的 bearer、**不传租户号**。"
              + "🔴 **上游读不到一律 503** —— 返回空集合会被读成「这个店铺没出过单」。"
              + "🔴 店铺**定不了时区**时返回一批 `upstreamStatus=SHOP_UNRESOLVED` 的空曲线，"
              + "**不摊给任何默认时区**；两侧时区不一致时返回 `CONFLICT`。")
  public AdDaypartRecommendationView generateRecommendation(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody AdDaypartRecommendationRequest request) {
    return call(
        auth,
        "channel.operation.create",
        actor -> dayparts.generateRecommendation(request, auth));
  }

  @GetMapping("/recommendations")
  @Operation(summary = "某条策略的推荐批次列表")
  public List<AdDaypartRecommendationView> recommendations(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam String strategyId) {
    return call(auth, "channel.operation.read", actor -> dayparts.recommendations(strategyId));
  }

  @GetMapping("/recommendations/{id}")
  @Operation(summary = "一批推荐的详情（含 24 个小时的判定依据）")
  public AdDaypartRecommendationView recommendation(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.read", actor -> dayparts.recommendation(id));
  }

  @PostMapping("/recommendations/{id}/apply")
  @Operation(
      summary = "把推荐曲线套到策略上",
      description =
          "套用后该小时的来源标成 `RECOMMENDED`，判定依据原样带上（页面上能看懂"
              + "「为什么这个小时是高峰」）。")
  public AdDaypartStrategyView applyRecommendation(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @RequestBody(required = false) AdDaypartApplyRecommendationRequest request) {
    return call(
        auth,
        "channel.operation.create",
        actor ->
            dayparts.applyRecommendation(
                id, request == null ? new AdDaypartApplyRecommendationRequest(List.of()) : request));
  }

  // ================================================================ 定时调整任务

  @PostMapping("/tasks/run")
  @Operation(
      summary = "跑一轮分时调整（**外部调度触发的一次入口**；本服务不装 @Scheduled）",
      description =
          "不给 `strategyId` ⇒ 跑所有「参与调度且生效中」的策略。"
              + "🔴 **「现在是几个小时」由 DB 的 now() 按店铺 IANA 时区换算**（不是 JVM 墙钟）"
              + "⇒ 夏令时对得上。"
              + "🔴 **幂等**：同一策略 + 同一本地小时 + 同一运行口径**只有一条任务**，"
              + "调度重试不会调两次钱。"
              + "🔴 `runMode=DRY_RUN` 只算「本来会变成多少」，不真调。")
  public AdDaypartRunResult run(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody(required = false) AdDaypartRunRequest request) {
    return call(
        auth,
        "channel.operation.create",
        actor ->
            dayparts.run(
                request == null ? new AdDaypartRunRequest(null, "DRY_RUN", "MANUAL", "") : request,
                auth));
  }

  @GetMapping("/tasks")
  @Operation(summary = "定时调整任务列表（真分页 + 真筛选）")
  public AdDaypartTaskPage tasks(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String strategyId,
      @RequestParam(required = false) String status,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "channel.operation.read", actor -> dayparts.tasks(strategyId, status, page, size));
  }

  @GetMapping("/tasks/{id}")
  @Operation(
      summary = "一次任务的详情（含逐条调整明细）",
      description =
          "🔴 逐条明细里 **APPLIED / REJECTED / SKIPPED / DRY_RUN** 分得开，"
              + "被拒的那几条**照样列出来**并写清被哪一道闸拦下 —— 静默丢弃会让人以为「按预期工作」。")
  public AdDaypartTaskView task(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.read", actor -> dayparts.task(id));
  }

  // ================================================================ 给下游 14.2.2.2 的读取形状

  @GetMapping("/caps")
  @Operation(
      summary = "某店铺当前生效的调整上限（**供 14.2.2.2 自动规则取用**）",
      description =
          "🔴 下游自动规则执行调整时应把**自己的上限**与这里的上限**取更严的那个** —— "
              + "两边各用各的，最后谁也说不清一次超支是谁的责任。"
              + "🔴 平台回写队列未交付 ⇒ 这里的调整只落本地并标「待回写平台」。")
  public AdDaypartShopCapsView shopCaps(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam String shopCode) {
    return call(auth, "channel.operation.read", actor -> dayparts.shopCaps(shopCode));
  }

  // ================================================================ 工具

  /** 把 DB 抛的约束错误翻成一句人话。 */
  private static String rootMessage(Exception e) {
    var message = e.getMessage();
    if (message == null) {
      return "未知原因";
    }
    var marker = message.indexOf("ERROR:");
    if (marker >= 0) {
      var detail = message.substring(marker + "ERROR:".length()).trim();
      var cut = detail.indexOf("Context:");
      if (cut > 0) {
        detail = detail.substring(0, cut).trim();
      }
      if (!detail.isEmpty()) {
        return detail;
      }
    }
    return message;
  }
}
