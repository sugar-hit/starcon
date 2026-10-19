package com.starcon.trade;

import static com.starcon.trade.ReturnReceiptModels.*;

import java.util.UUID;
import java.util.function.Function;
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
 * 11.2.2.1 退货入库。
 *
 * <p>蓝图 11-aftersales-and-returns.md：「登记退回实物：扫码核对 SKU 与序列号、清点数量、
 * 拍照留证、判定外包装与商品状态。」
 * 输出：「退货实收记录 + 关联授权行分配；<b>待检库存入库分录（不是可售）</b>。」
 * 加粗句：「<b>退回商品默认进待检状态</b>，这是硬规则——直接进可售会把残次品当新品卖出去。」
 *
 * <p><b>命名空间独立于 {@code /trade/rma-authorizations} 与 {@code /trade/rma-instructions}</b>：
 * 本模块的产物是<b>另一张单据</b>（一次收货事件 = 一个包裹），不是那两张单的另一个视图。
 * 本模块是 11.2.1.2 的<b>下游</b>——指引下发了不等于货到了。
 *
 * <p><b>权限码沿用</b> {@code trade.aftersales.read} / {@code trade.aftersales.write}：
 * 本模块是 11.2 锚点下的退货侧动作，与售后单同一业务面。
 *
 * <p><b>刻意不提供</b>：{@code DELETE /{id}}、任何「直接转可售」的入口 ——
 * 处置属 11.2.3.2（未交付），而退回商品默认进待检是硬规则，
 * 放行一个「入库时顺便标成可售」的接口等于把残次品当新品卖出去。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} / {@link #write} 先向 identity 确认已认证成员关系，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 FORCE RLS 在数据库层强制隔离。
 * 本模块的端点大多是<b>按 id 直达</b>的 URL —— 拿别的租户的实收单 ID 来读，
 * 得到的是与「不存在」<b>完全相同</b>的 NOT_FOUND（不告诉调用方「这个 ID 存在但不属于你」）。
 */
@RestController
@RequestMapping("/trade/return-receipts")
public class ReturnReceiptController {

  private static final String READ = "aftersales.read";
  private static final String WRITE = "aftersales.write";

  private final AccessService access;
  private final ReturnReceiptService receipts;

  public ReturnReceiptController(AccessService access, ReturnReceiptService receipts) {
    this.access = access;
    this.receipts = receipts;
  }

  /** 统一读入口：先确认身份与权限（租户<b>不</b>来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, Function<AccessService.Access, T> work) {
    AccessService.Access actor = access.require(bearer, "trade." + READ);
    return access.run(actor, "trade." + READ, () -> work.apply(actor));
  }

  /** 写入口：需要 {@code trade.aftersales.write}。租户来源与读入口完全一致。 */
  private <T> T write(String bearer, Function<AccessService.Access, T> work) {
    AccessService.Access actor = access.require(bearer, "trade." + WRITE);
    return access.run(actor, "trade." + WRITE, () -> work.apply(actor));
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：职责句的四个动词逐块报可用性，下游逐个报 delivered=false 与归属模块号、
   * 本模块<b>刻意不做</b>的事、以及「退回商品默认进待检」这条硬规则的口径。
   *
   * <p>路径刻意是 {@code capabilities}（租户级静态事实），不带 id 段。
   */
  @GetMapping("/capabilities")
  public ReturnReceiptCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, actor -> receipts.capabilities());
  }

  // ================================================================ 列表 / 详情

  /** 列表：真分页 + 多维筛选（含按承运商 / 运单号反查与两个判定维度筛选）+ 排序白名单 + 全量汇总。 */
  @GetMapping
  public ReturnReceiptPage list(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String channel,
      @RequestParam(required = false) UUID rmaId,
      @RequestParam(required = false) UUID instructionId,
      @RequestParam(required = false) UUID caseId,
      @RequestParam(required = false) String carrier,
      @RequestParam(required = false) String trackingNo,
      @RequestParam(required = false) String packagingCondition,
      @RequestParam(required = false) String goodsCondition,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String sort) {
    return call(
        auth,
        actor ->
            receipts.list(
                new ReturnReceiptListQuery(
                    page, size, status, channel, rmaId, instructionId, caseId, carrier,
                    trackingNo, packagingCondition, goodsCondition, keyword, sort)));
  }

  /** 单张实收全景。**不存在的 id 与不属于本租户的 id 返回同一条错误** —— 见类注释。 */
  @GetMapping("/{id}")
  public ReturnReceiptDetail detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, actor -> receipts.detail(id));
  }

  /**
   * 某张退货授权单的<b>剩余可收量</b>（硬规则 3 的可见面）。
   *
   * <p>把「授权量 / 已被占用 / 还能收」并排给出，让操作员在<b>扫码那一刻</b>
   * 就知道还能收几件，而不是录完被后端拒了才知道。
   *
   * <p>⚠️ 占用口径与 DB 触发器完全一致（含草稿）—— 两边算出不同的数时，
   * 界面显示「还能收 3 件」而点提交被拒，是最消耗信任的一种不一致。
   */
  @GetMapping("/receivable")
  public ReturnReceivablePage receivable(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam UUID rmaId) {
    return call(auth, actor -> receipts.receivable(rmaId));
  }

  // ================================================================ 写：建单 / 改草稿

  /**
   * 登记一张退货实收草稿（挂在已授权 / 部分授权的退货授权单下）。
   *
   * <p><b>建单不是入库</b>：此刻不产生任何库存分录。真正入库是 {@code /post} 的事 ——
   * 两者合成一个动作会让「谁扫的、什么时候扫的」在留痕里消失，
   * 而买家投诉「这批货我根本没寄这么多」时这就是唯一的证据。
   */
  @PostMapping
  public ReturnReceiptDetail create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody ReturnReceiptCreate request) {
    return write(
        auth,
        // ⚠️ createdBy 走 actor 而不是请求体：请求体里没有这个字段，
        //   身份只认已认证的成员关系。
        actor -> receipts.create(request, actor.userId()));
  }

  /**
   * 改草稿要素（收货渠道 / 承运商与运单号 / 包裹备注 / 收货时刻 / 逐件明细）。
   *
   * <p><b>只允许 DRAFT 改</b>，且<b>整单替换明细</b>：扫码台的工作面是「这一包裹一共几件」，
   * 增量追加会让「删掉扫错的那一行」在界面上无处安放。
   * 乐观锁 {@code expectedVersion} 必填。
   */
  @PutMapping("/{id}")
  public ReturnReceiptDetail update(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody ReturnReceiptUpdate request) {
    return write(auth, actor -> receipts.update(id, request, actor.userId()));
  }

  // ================================================================ 写：状态流转

  /**
   * 入库：清点完成 → <b>生成待检入库分录</b>。
   *
   * <p>⚠️ <b>刻意不接受「目标库存状态」参数</b>，传输层也没有这样一个字段：
   * 分录由 DB 触发器在状态推到 POSTED 的那一刻生成，
   * inventory_state 恒 PENDING_INSPECTION、sellable_qty_delta 恒 0（表级 CHECK 钉死）。
   * 入库前两个判定维度都必须已判。
   */
  @PostMapping("/{id}/post")
  public ReturnReceiptDetail post(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody ReturnReceiptPost request) {
    return write(auth, actor -> receipts.post(id, request, actor.userId()));
  }

  /**
   * 人工撤销（录错了 / 买家又把货拿回去了）。<b>已入库的单不可撤销</b> ——
   * 待检分录已生成，回冲属 11.2.3.2（未交付）。
   */
  @PostMapping("/{id}/cancel")
  public ReturnReceiptDetail cancel(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody ReturnReceiptCancel request) {
    return write(auth, actor -> receipts.cancel(id, request, actor.userId()));
  }

  // ================================================================ 写：拍照留证

  /**
   * 追加一张照片（拍照留证可在草稿期持续补）。
   *
   * <p><b>追加式</b>：举证材料不能被改掉（运行角色 UPDATE/DELETE 已在 DB 层收回），
   * 同一 sha256 不重复登记。
   */
  @PostMapping("/{id}/photos")
  public ReturnReceiptDetail appendPhoto(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody ReturnPhotoAppend request) {
    return write(auth, actor -> receipts.appendPhoto(id, request, actor.userId()));
  }
}
