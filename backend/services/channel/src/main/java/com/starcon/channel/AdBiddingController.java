package com.starcon.channel;

import static com.starcon.channel.AdBiddingModels.*;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Function;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
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
 * 14.2.1.1 竞价管理的 HTTP 端点。
 *
 * <p>命名空间是 {@code /channel/ad-bidding}：兄弟模块各有各的前缀
 * （14.2.1.2 用的是 {@code /channel/ad-dayparts}、14.2.2.1 用的是 {@code /channel/ad-budgets}、
 * 14.2.2.2 用的是 {@code /channel/ad-rules}）—— 挤在同一个前缀下会让「哪个模块的哪个端点」
 * 变得说不清。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 *
 * <p>权限码：复用 identity 已有的 {@code channel.operation.read}（读策略 / 建议 / 任务 / 队列）
 * 与 {@code channel.operation.create}（建策略 / 改策略 / 生成建议 / 提交调整 / 撤销下发），
 * 与 14.1.1.1 活动列表、14.1.3.1 关键词管理、14.2.1.2 分时策略同族 ⇒ <b>identity 零改动</b>。
 *
 * <p>🔴 <b>「已生效」这个词不出现</b>：平台广告 API 未交付 ⇒ 调整只落<b>待下发队列</b>，
 * 界面写「待下发」。
 */
@RestController
@RequestMapping("/channel/ad-bidding")
@Tag(name = "14.2.1.1 竞价管理")
public class AdBiddingController {

  private final AccessService access;
  private final AdBiddingService bidding;

  public AdBiddingController(AccessService access, AdBiddingService bidding) {
    this.access = access;
    this.bidding = bidding;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, permission);
    return access.run(actor, permission, () -> work.apply(actor));
  }

  // ================================================================ 能力声明与下拉项

  @GetMapping("/capabilities")
  @Operation(
      summary = "能力声明：动态竞价策略几档、两道闸怎么分、哪些确实做不了",
      description =
          "🔴 notAvailable 段列出的是**确实做不了**的部分，每条都写清责任模块与影响 —— "
              + "把「做不了」伪装成「能做」比说清楚更糟。")
  public AdBiddingCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", actor -> bidding.capabilities());
  }

  @GetMapping("/options")
  @Operation(
      summary = "建策略要用的下拉项（平台 / 店铺 / 动态竞价策略三档）+ 14.1.3.1 的护栏上限",
      description = "🔴 护栏上限是**继承**来的，不是本模块另设的一套。")
  public AdBiddingOptions options(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", actor -> bidding.options());
  }

  // ================================================================ 竞价策略（增删改查）

  @GetMapping("/policies")
  @Operation(summary = "竞价策略清单（真分页 + 真筛选）")
  public AdBiddingPolicyPage policies(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String shopCode,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "channel.operation.read",
        actor -> bidding.policies(shopCode, status, keyword, page, size));
  }

  @PostMapping("/policies")
  @Operation(
      summary = "新建一条竞价策略",
      description =
          "🔴 **动态竞价策略是三档枚举**（只增只降 / 只降低 / 只提高），不是自由文本。"
              + "🔴 **目标 ACOS 与目标 ROAS 二选一**（它们互为倒数，同时给两个就必须自洽）。"
              + "🔴 新建策略一律是草稿（DRAFT），激活要单独一步 —— 激活会进**待下发队列**。")
  public AdBiddingPolicyView createPolicy(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody AdBiddingPolicyRequest request) {
    return call(
        auth,
        "channel.operation.create",
        actor -> {
          try {
            return bidding.createPolicy(request);
          } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
          } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "策略写不进来：" + rootMessage(e));
          }
        });
  }

  @GetMapping("/policies/{id}")
  @Operation(summary = "一条竞价策略的详情")
  public AdBiddingPolicyView policy(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.read", actor -> bidding.policy(id));
  }

  @PutMapping("/policies/{id}")
  @Operation(
      summary = "改一条竞价策略的属性",
      description =
          "🔴 改了**动态竞价策略档位**会进**待下发队列**（平台侧要重新知道这条策略）。"
              + "🔴 已归档的策略不能再改（归档是软删除：历史还要能复盘）。")
  public AdBiddingPolicyView updatePolicy(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @RequestBody AdBiddingPolicyRequest request) {
    return call(
        auth,
        "channel.operation.create",
        actor -> {
          try {
            return bidding.updatePolicy(id, request);
          } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
          } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "策略改不了：" + rootMessage(e));
          }
        });
  }

  @PostMapping("/policies/{id}/status")
  @Operation(
      summary = "改策略状态（激活 / 暂停 / 归档）",
      description =
          "🔴 激活前必须有目标（目标 ACOS 或目标 ROAS），否则反推不出建议竞价。"
              + "🔴 **激活会进待下发队列** —— 平台广告 API 未交付，界面写「待下发」，不是「已生效」。")
  public AdBiddingPolicyView changeStatus(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @RequestBody AdBiddingStatusRequest request) {
    return call(
        auth,
        "channel.operation.create",
        actor -> {
          try {
            return bidding.changeStatus(id, request);
          } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
          } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "状态改不了：" + rootMessage(e));
          }
        });
  }

  // ================================================================ 竞价候选清单

  @GetMapping("/keywords")
  @Operation(
      summary = "可调价的关键词清单（真分页 + 真筛选）+ 窗口内表现 + 归到 SKU 的结论",
      description =
          "🔴 窗口内的花费 / 销售额是**平台自报口径**（14.1.3.1）；"
              + "反推竞价用的是 14.3.2.1 的**归因**销售额 —— 两个口径不同，页面上分开写明。"
              + "🔴 归不到 SKU 的关键词**照样列出来**并写清原因（删掉它们的话，"
              + "页面只能说「这个店铺没关键词」，那会让人以为数据缺失）。")
  public AdBiddingKeywordPage keywords(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String shopCode,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String status,
      @RequestParam(required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate from,
      @RequestParam(required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate to,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "channel.operation.read",
        actor -> bidding.keywordCandidates(shopCode, keyword, status, from, to, page, size));
  }

  // ================================================================ 目标 ACOS 反推竞价建议

  @PostMapping("/suggestions")
  @Operation(
      summary = "按目标 ACOS / 目标 ROAS 反推竞价建议（**按商品/SKU**）",
      description =
          "🔴 口径：ACOS = 花费 ÷ **归因销售额**、ROAS = 归因销售额 ÷ 花费，"
              + "**分母为 0 ⇒ 一律 NULL（不可比）而不是 0**（「压根算不出来」与「算出来是零」"
              + "在调价里是相反的两件事）。"
              + "🔴 建议竞价 = 该关键词自己的当前竞价 × (目标 ACOS ÷ 当前 ACOS)。"
              + "🔴 **利润侧读不到一律 503** —— 没有利润数据就不知道「这个价投下去赚不赚钱」。"
              + "🔴 目标 ACOS 高于**盈亏平衡 ACOS**的建议会被**否决**并写明原因。")
  public AdBiddingSuggestionView generateSuggestion(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody AdBiddingSuggestionRequest request) {
    return call(
        auth,
        "channel.operation.create",
        actor -> bidding.generateSuggestion(request, auth));
  }

  @GetMapping("/suggestions")
  @Operation(summary = "建议批次列表（真分页 + 真筛选）")
  public AdBiddingSuggestionPage suggestions(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String policyId,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "channel.operation.read", actor -> bidding.suggestions(policyId, page, size));
  }

  @GetMapping("/suggestions/{id}")
  @Operation(
      summary = "一个建议批次的详情（含 SKU 级口径行与关键词级建议）",
      description =
          "🔴 被闸拦下或算不出来的那几条**照样列出来**并写清被哪道闸拦下 —— "
              + "静默丢弃会让人以为「按预期工作」。")
  public AdBiddingSuggestionView suggestion(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.read", actor -> bidding.suggestion(id));
  }

  // ================================================================ 批量竞价调整

  @PostMapping("/adjustments")
  @Operation(
      summary = "提交一次批量竞价调整（**走 14.1.3.1 已交付的写路径**）",
      description =
          "🔴 改竞价**一律走** `AdKeywordService.bulkAction` ⇒ ±50% / 1000.00 / "
              + "单次总上调 5000.00 / 幂等 / 操作审计**全部继承**，本模块**不另开绕过护栏的写路径**。"
              + "🔴 给了 `suggestionId` ⇒ 目标从建议批次里取（只取给出建议的那几条）；"
              + "给了 `targets` ⇒ 手工指定。二选一。"
              + "🔴 **试运行默认开**：只算「本来会变成多少」，不真的改。"
              + "🔴 真调之后只落**待下发队列**（平台广告 API 未交付）——界面写「待下发」，不是「已生效」。")
  public AdBiddingAdjustmentView adjust(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody AdBiddingAdjustmentRequest request) {
    return call(
        auth,
        "channel.operation.create",
        actor -> {
          try {
            return bidding.adjust(request, auth);
          } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
          } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "调整写不进来：" + rootMessage(e));
          }
        });
  }

  @GetMapping("/adjustments")
  @Operation(summary = "批量调整任务列表（真分页 + 真筛选）")
  public AdBiddingAdjustmentPage adjustments(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String policyId,
      @RequestParam(required = false) String status,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth, "channel.operation.read", actor -> bidding.adjustments(policyId, status, page, size));
  }

  @GetMapping("/adjustments/{id}")
  @Operation(
      summary = "一次调整任务的详情（含逐条明细与 14.1.3.1 的批量任务 id）",
      description =
          "🔴 逐条明细里 **APPLIED / REJECTED / SKIPPED** 分得开，"
              + "被拒的那几条**照样列出来**并写清被哪一道闸拦下。")
  public AdBiddingAdjustmentView adjustment(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.read", actor -> bidding.adjustment(id));
  }

  // ================================================================ 待下发队列

  @GetMapping("/dispatch")
  @Operation(
      summary = "待下发队列（真分页 + 真筛选）",
      description =
          "🔴 平台广告 API 未交付 ⇒ 这里的记录停在「**待下发**」，"
              + "**不是**「已生效」。本地竞价已经改了，平台侧还没改。")
  public AdBiddingDispatchPage dispatches(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String shopCode,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth, "channel.operation.read", actor -> bidding.dispatches(status, shopCode, page, size));
  }

  @PostMapping("/dispatch/{id}/cancel")
  @Operation(
      summary = "撤销一条待下发（**本模块唯一**能改变队列的动作）",
      description =
          "🔴 **刻意不提供**「标记已下发」这种假动作 —— 平台回写真的发生了才有 DISPATCHED，"
              + "手动标会让假事实进账。"
              + "🔴 **撤销 = 停止下发，不等于回滚**：本地竞价已经通过 14.1.3.1 的写路径改过了，"
              + "撤销**不会**把本地竞价改回去（那是一次新的调价，要走同一套护栏）。"
              + "🔴 撤销必须写清原因。")
  public AdBiddingDispatchView cancelDispatch(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @RequestBody AdBiddingDispatchCancelRequest request) {
    return call(
        auth,
        "channel.operation.create",
        actor -> {
          try {
            return bidding.cancelDispatch(id, request);
          } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
          }
        });
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
