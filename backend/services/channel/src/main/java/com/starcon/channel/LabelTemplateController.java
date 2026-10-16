package com.starcon.channel;

import static com.starcon.channel.LabelTemplateModels.*;

import jakarta.validation.Valid;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * 10.2.2.2 面单模板。
 *
 * <p>端点按蓝图模块号标注，方便对照 10-logistics-and-freight.md。
 *
 * <p>🔴 本控制器<b>刻意没有承运商与渠道身份字段的写入口</b>：渠道挂在一个已有的渠道
 * 配置之下，渠道身份（承运商 / 渠道代码 / 适用国家 / 计费 / 限制）归 10.1.1.2，
 * 本模块的写请求只提交 {@code channelId} —— 复制一份就会造出「同一个渠道在两处各改一次」。
 *
 * <p>🔴 {@code POST /binding} 是蓝图加粗句的落点：<b>附加声明漏打会导致清关问题，
 * 模板配置要与渠道规则强绑定</b>。渠道要求由<b>服务端</b>判定，规则只有一份，
 * 下游（10.2.1.2 运单申请、8.3.4.2 打印）拿到结论与缺口即可，
 * <b>不应该也不需要自己再判断一遍</b>。
 *
 * <p>🔴 {@code POST /{id}/preview} 出的是<b>真 ZPL 指令流</b>（含真条码编码与校验位），
 * 不是「返回一段示意文本」。条码类型在这里被真问一次「这个值编不编得出来」。
 *
 * <p>权限码<b>复用</b> identity 已有的 {@code channel.operation.*}
 * （与 10.1.1.1 承运商、10.1.1.2 渠道配置、10.3.2.1 交运批次同族），不需要改动 identity 服务。
 */
@RestController
@RequestMapping("/channel/label-templates")
public class LabelTemplateController {
  private final AccessService access;
  private final LabelTemplateService service;

  public LabelTemplateController(AccessService access, LabelTemplateService service) {
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
  public LabelTemplateCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.capabilities());
  }

  /** 概览：全量口径的格式 / 条码类型 / 语言分布，**不受列表筛选与分页影响**。 */
  @GetMapping("/overview")
  public LabelTemplateOverview overview(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.overview());
  }

  // ---- 面单模板 ----

  /** 面单模板列表：真分页 + 真筛选，汇总走独立查询不受 limit/offset 影响。 */
  @GetMapping
  public LabelTemplatePage templates(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String channelId,
      @RequestParam(required = false) String countryCode,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String readiness,
      @RequestParam(required = false) String symbology,
      @RequestParam(required = false) String language,
      @RequestParam(required = false, defaultValue = "NAME_ASC") String sort,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "channel.operation.read",
        a ->
            service.templates(
                keyword, channelId, countryCode, status, readiness, symbology, language, sort,
                page, size));
  }

  @GetMapping("/{id}")
  public LabelTemplateRow detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.read", a -> service.detail(UUID.fromString(id)));
  }

  /** 登记 / 改一个面单模板。请求体<b>不接受</b>承运商与渠道身份字段。 */
  @PostMapping
  public LabelTemplateRow save(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody SaveLabelTemplateRequest request) {
    return call(auth, "channel.operation.create", a -> service.save(request));
  }

  /** 撤下一张面单模板（渲染历史随之级联删除）。 */
  @DeleteMapping("/{id}")
  public org.springframework.http.ResponseEntity<Void> delete(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    callVoid(auth, "channel.operation.delete", a -> service.delete(UUID.fromString(id)));
    return org.springframework.http.ResponseEntity.status(HttpStatus.NO_CONTENT).build();
  }

  // ---- 启用 / 停用 ----

  /**
   * 启用 / 停用 / 归档一张面单模板。
   *
   * <p>启用会先走一次<b>真渲染</b>并留证（渲染历史里出现 {@code ACTIVATION} 那一条）：
   * 齐备度不够的模板进不了 ACTIVE —— 印出去的每张面单都是真票。
   */
  @PostMapping("/{id}/status")
  public LabelTemplateRow setStatus(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody SetLabelStatusRequest request) {
    return call(
        auth, "channel.operation.update", a -> service.setStatus(UUID.fromString(id), request.status(), request.version()));
  }

  // ---- 🔴 真渲染预览 ----

  /**
   * 真渲染一张面单模板，出可打印的 ZPL 指令流 + 判定结论。
   *
   * <p>一维码（Code 128 A/B · Code 39）在这一步就<b>真编码并算校验位</b>，
   * 所以「这个运单号能不能用 CODE39 印」在预览时就有答案；二维码只校验字符集与
   * 长度上限（点阵由现场打印机的 {@code ^BQ} 生成，本模块不冒充）。
   */
  @PostMapping("/{id}/preview")
  public LabelRenderPreview preview(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody LabelPreviewRequest request) {
    return call(auth, "channel.operation.read", a -> service.preview(UUID.fromString(id), request));
  }

  /** 渲染历史（真分页，最近在前）：启用前的证据链。 */
  @GetMapping("/{id}/renders")
  public LabelRenderPage renders(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(auth, "channel.operation.read", a -> service.renders(UUID.fromString(id), page, size));
  }

  // ---- 🔴 渠道规则强绑定判定 ----

  /**
   * 判定一批模板能不能用来打这一票货，以及缺哪个附加声明。
   *
   * <p>结论三档：{@code PASS} 可用于这一票 / {@code REVIEW} 需人工确认（不拦，
   * 但不允许自动放行）/ {@code BLOCK} 不可用（漏打会导致清关问题）。
   *
   * <p>刻意<b>只读</b>：判定不产生任何副作用，所以用只读权限码，任何能看面单模板的人
   * 都能在下单前自查。
   */
  @PostMapping("/binding")
  public LabelBindingResult binding(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody LabelBindingRequest request) {
    return call(auth, "channel.operation.read", a -> service.binding(request));
  }
}
