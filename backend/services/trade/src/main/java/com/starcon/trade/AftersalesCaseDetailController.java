package com.starcon.trade;

import static com.starcon.trade.AftersalesCaseDetailModels.*;

import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 11.1.1.2 售后单详情。
 *
 * <p>蓝图 11-aftersales-and-returns.md：「单售后全景：原订单信息、申请内容与买家诉求、商品行与数量、
 * 沟通记录、附件与凭证、处置流程状态、退款与退货关联、平台侧状态、责任判定。」
 * 输出句：「详情 + 操作入口 + 时间线。」
 * 加粗句：「<b>详情页要把"买家说了什么"和"我们做了什么"按时间线对齐</b>，这是纠纷举证的基础。」
 *
 * <p><b>命名空间与 11.1.1.1 并列</b>：同一个 {@code /trade/aftersales}，本模块补上它**刻意没做**的
 * {@code cases/{uuid}}。11.1.1.1 的台账里写着「刻意不提供 cases/{uuid}（11.1.1.2 详情未交付，
 * 列表行不带任何指向它的链接）」—— 那条禁令的前提是本模块未交付；现在本模块交付了，
 * 它需要的正是这个端点。
 *
 * <p><b>权限码沿用</b> {@code trade.aftersales.read} / {@code trade.aftersales.write}：
 * 本模块是 11.1.1 同一 L3 锚点下的详情视图，**不引入**新权限码 ——
 * 一个「能看列表但不能看详情」的权限位在售后场景里没有任何业务含义，
 * 而详情页比列表页更敏感（订单号、买家姓名、收货地址、诉求原文、授权金额）。
 *
 * <p><b>刻意不提供</b>：{@code DELETE /cases/{uuid}}、{@code PUT /cases/{uuid}}。
 * 售后单的状态流转与结案由 11.1.1.3 受理（未交付）与 11.1.1.1 的批量授权推进；
 * 本模块只读聚合 + 追加留痕（备注 / 沟通 / 凭证 / 商品行），**四张留痕表全部 append-only**，
 * 运行角色连 UPDATE/DELETE 权限都没有（V157 第七节）—— 改掉或删掉一行就是毁掉纠纷证据。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} / {@link #write} 先向 identity 确认已认证成员关系，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 FORCE RLS 在数据库层强制隔离。
 * 详情页是**按 id 直达**的 URL：拿别的租户的售后单 ID 来读，得到的是与「不存在」**完全相同**的
 * NOT_FOUND（不告诉调用方「这个 ID 存在但不属于你」—— 那本身也是一条信息泄露）。
 */
@RestController
@RequestMapping("/trade/aftersales")
public class AftersalesCaseDetailController {

  private static final String READ = "aftersales.read";
  private static final String WRITE = "aftersales.write";

  private final AccessService access;
  private final AftersalesCaseDetailService details;

  public AftersalesCaseDetailController(AccessService access, AftersalesCaseDetailService details) {
    this.access = access;
    this.details = details;
  }

  /** 统一读入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
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
   * 详情页能力声明：九个职责区块逐块报可用性、关联下游逐个报 Delivered=false 与归属模块号、
   * 写入来源如实标注（哪些数据是租户登记的）。
   *
   * <p>路径刻意是 {@code case-detail/capabilities} 而不是 {@code cases/{uuid}/capabilities}：
   * 前者是**租户级**的静态事实（与具体哪张单无关），挂在 id 之下会让人以为它随单变化，
   * 也让白名单必须多放行一种「id 段 + 字面量」的混合形状。
   */
  @GetMapping("/case-detail/capabilities")
  public AftersalesDetailCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, actor -> details.capabilities());
  }

  // ================================================================ 详情

  /**
   * 单售后全景：一次请求返回职责句里的九个区块 + 操作入口 + 时间线。
   *
   * <p>**不存在的 id 与不属于本租户的 id 返回同一条错误** —— 见类注释。
   */
  @GetMapping("/cases/{id}")
  public AftersalesCaseDetail detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, actor -> details.detail(id));
  }

  /**
   * 时间线单独分页读（页面刷新与翻页用）：真分页（LIMIT/OFFSET + 独立 COUNT），
   * 并报出买家侧 / 我方侧的事件条数 —— 那是加粗句那句「对齐」的量化形态。
   */
  @GetMapping("/cases/{id}/timeline")
  public AftersalesTimelinePage timeline(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(auth, actor -> details.timelinePage(id, new AftersalesTimelineQuery(page, size)));
  }

  // ================================================================ 追加子资源

  /** 登记一条商品行（涉及哪一行、退几件、这一行退多少钱）。数量累计由 DB 触发器兜底。 */
  @PostMapping("/cases/{id}/lines")
  public AftersalesCaseLine addLine(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody AftersalesCaseLineCreate request) {
    return write(
        auth,
        actor -> {
          // ⚠️ authorId 走 actor 而不是请求体：请求体里的 authorId 只是为了兼容批量导入，
          //   这里仍然**优先**用已认证身份。
          return details.addLine(id, request, actor.userId());
        });
  }

  /**
   * 登记一条沟通记录。
   *
   * <p>{@code sentAt} 是消息<b>发生</b>的时刻而不是录入时刻 —— 填 now() 会让一条三个月前的
   * 平台消息在举证里显示成「今天买家说的」，而时间线错位在申诉里往往不被采信。
   */
  @PostMapping("/cases/{id}/messages")
  public AftersalesMessage addMessage(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody AftersalesMessageCreate request) {
    return write(auth, actor -> details.addMessage(id, request));
  }

  /**
   * 登记一份附件 / 凭证。
   *
   * <p>只登记**对象存储定位符**而不是文件内容：本服务没有文件服务，
   * 存 base64 会让一行凭证把详情响应顶到几十 MB，而举证打包要的是引用。
   */
  @PostMapping("/cases/{id}/attachments")
  public AftersalesAttachment addAttachment(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody AftersalesAttachmentCreate request) {
    return write(auth, actor -> details.addAttachment(id, request, actor.userId()));
  }

  /**
   * 详情页追加单条备注（{@code note_source = 'TENANT_SINGLE'}）。
   *
   * <p>V156 的表定义里**已经**留了这个值，但 11.1.1.1 只写 TENANT_BATCH（它的入口是批量操作）——
   * 这个槽位在本模块交付前没有任何写入者。本端点就是它的写入者。
   */
  @PostMapping("/cases/{id}/notes")
  public AftersalesCaseNote addNote(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody AftersalesNoteCreate request) {
    return write(auth, actor -> details.addNote(id, request));
  }
}
