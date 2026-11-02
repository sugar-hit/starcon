package com.starcon.catalog;

import static com.starcon.catalog.PriceListItemModels.*;

import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
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
 * 5.1.1.2 价目项批量维护。
 *
 * <p>命名空间 {@code /catalog/price-lists}（与 5.1.1.1 的价目表同一个命名空间 ——
 * 价目项是价目表的分段，不另起一个服务也不另起一个前缀）。
 * 页面路由 {@code /pricing/lists}。
 *
 * <p><b>权限沿用商品主数据域</b>：读 {@code catalog.spu.read}，写 {@code catalog.spu.update} ——
 * 与 5.1.1.1、5.3.2.1 定价模板、5.3.2.2 价格规则同一套，不新造权限词表。
 *
 * <p><b>租户不来自任何请求头</b>：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 客户端伪造的 {@code X-Tenant-Id} 进不来也改不动它。
 *
 * <h2>🔴 端点清单</h2>
 *
 * <pre>
 * GET    /item-capabilities                     能力声明（三模式 / 三范围 / 八条口径）
 * GET    /lists/{uuid}/items                    价目项列表（真分页 + 筛选）
 * POST   /lists/{uuid}/items                    新建一条价目项
 * PUT    /lists/{uuid}/items/{uuid}             修改一条价目项（乐观锁）
 * DELETE /lists/{uuid}/items/{uuid}             删除一条价目项
 * GET    /lists/{uuid}/items/{uuid}             单条价目项详情
 * GET    /lists/{uuid}/items/conflicts          本表内的冲突清单（同优先级 + 区间重叠）
 * GET    /lists/{uuid}/items/resolve            解析「这个 SKU 此刻该卖多少」（歧义 ⇒ 409）
 * POST   /lists/{uuid}/items/preview            批量设价的**影响范围预览**
 * POST   /lists/{uuid}/items/apply              批量设价**执行**
 * GET    /lists/{uuid}/items/export             导出 xlsx（真实工作簿）
 * POST   /lists/{uuid}/items/import             Excel 导入（先出校验报告）
 * </pre>
 *
 * <p>🔴 **字面量子路径（conflicts / resolve / export / preview / apply / import）必须排在
 * {@code {uuid}} 段之前** —— api-proxy 的白名单是 alternation，首个命中即返回，
 * 顺序反了会被 uuid 支吃掉半截。
 */
@RestController
@RequestMapping("/catalog/price-lists")
public class PriceListItemController {

  private final AccessService access;
  private final PriceListItemService items;

  public PriceListItemController(AccessService access, PriceListItemService items) {
    this.access = access;
    this.items = items;
  }

  /** 统一入口：先向 identity 确认身份与权限（租户**不**来自任何客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "catalog." + permission);
    return access.run(actor, "catalog." + permission, () -> work.apply(actor));
  }

  /** 能力声明：三种批量模式 + 三种批量范围 + 八条口径说明。 */
  @GetMapping("/item-capabilities")
  public PriceListItemCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "spu.read", a -> items.capabilities());
  }

  /** 价目项台账：真分页 + 关键词 / SKU 编码 / 启用状态 / 此刻是否生效筛选。 */
  @GetMapping("/lists/{priceListId}/items")
  public PriceListItemPage list(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID priceListId,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String skuCode,
      @RequestParam(required = false) Boolean enabled,
      @RequestParam(required = false) Boolean effectiveNow,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    return call(
        auth,
        "spu.read",
        a ->
            items.list(
                priceListId,
                new PriceListItemService.ItemQuery(
                    keyword, enabled, effectiveNow, skuCode, page == null ? 0 : page, size == null ? 20 : size)));
  }

  /** 单条价目项详情。 */
  @GetMapping("/lists/{priceListId}/items/{itemId}")
  public PriceListItemView detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID priceListId,
      @PathVariable UUID itemId) {
    return call(auth, "spu.read", a -> items.detail(priceListId, itemId));
  }

  /** 新建一条价目项。 */
  @PostMapping("/lists/{priceListId}/items")
  public PriceListItemView create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID priceListId,
      @Valid @RequestBody PriceListItemInput input) {
    return call(auth, "spu.update", a -> items.save(a, priceListId, null, input));
  }

  /** 修改一条价目项（乐观锁：版本对不上时 409）。 */
  @PutMapping("/lists/{priceListId}/items/{itemId}")
  public PriceListItemView update(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID priceListId,
      @PathVariable UUID itemId,
      @Valid @RequestBody PriceListItemInput input) {
    return call(auth, "spu.update", a -> items.save(a, priceListId, itemId, input));
  }

  /** 删除一条价目项。 */
  @DeleteMapping("/lists/{priceListId}/items/{itemId}")
  public void delete(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID priceListId,
      @PathVariable UUID itemId) {
    call(
        auth,
        "spu.update",
        a -> {
          items.delete(priceListId, itemId);
          return null;
        });
  }

  /**
   * 本表内的冲突清单。
   *
   * <p>🔴 这是**本表内部**的歧义（同 SKU、同档位、同优先级、区间重叠）。
   * 跨价目表的优先级与冲突是 **5.1.2.1** 的职责 —— 本模块**不**代它开形状。
   */
  @GetMapping("/lists/{priceListId}/items/conflicts")
  public List<PriceListItemConflict> conflicts(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID priceListId) {
    return call(auth, "spu.read", a -> items.conflicts(priceListId));
  }

  /**
   * 解析「这个 SKU 在这张价目表此刻该卖多少」。
   *
   * @param quantity 购买数量（阶梯价按数量门槛适用；不给 ⇒ 只认基础档）
   * @param at 判定时刻（不给 ⇒ 数据库当前时刻）
   */
  @GetMapping("/lists/{priceListId}/items/resolve")
  public PriceListItemResolution resolve(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID priceListId,
      @RequestParam String skuCode,
      @RequestParam(required = false) BigDecimal quantity,
      @RequestParam(required = false) OffsetDateTime at) {
    return call(auth, "spu.read", a -> items.resolve(priceListId, skuCode, quantity, at));
  }

  /** 批量设价的**影响范围预览**（不落库）。 */
  @PostMapping("/lists/{priceListId}/items/preview")
  public PriceListItemBatchPreview preview(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID priceListId,
      @Valid @RequestBody PriceListItemBatchRequest request) {
    return call(auth, "spu.read", a -> items.preview(priceListId, request));
  }

  /** 批量设价**执行**（与预览走同一个计划函数 ⇒ 预览即执行）。 */
  @PostMapping("/lists/{priceListId}/items/apply")
  public PriceListItemBatchResult apply(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID priceListId,
      @Valid @RequestBody PriceListItemBatchRequest request) {
    return call(auth, "spu.update", a -> items.apply(a, priceListId, request));
  }

  /**
   * 导出 xlsx。
   *
   * <p>🔴 真的是一个能打开的工作簿（Apache POI 生成），不是 CSV 改后缀。
   */
  @GetMapping("/lists/{priceListId}/items/export")
  public ResponseEntity<byte[]> export(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID priceListId) {
    var exported = call(auth, "spu.read", a -> new ExportPayload(
        items.exportXlsx(priceListId), items.exportFileName(priceListId)));
    var disposition =
        ContentDisposition.attachment()
            .filename(exported.name(), StandardCharsets.UTF_8)
            .build();
    return ResponseEntity.ok()
        .contentType(
            MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
        .body(exported.bytes());
  }

  /** 导出载荷（字节 + 文件名）。 */
  private record ExportPayload(byte[] bytes, String name) {}

  /**
   * Excel 导入（**先出校验报告**，有错整批不落库）。
   *
   * <p>🔴 文件走 base64 JSON 而不是 multipart：浏览器同源代理只转发 JSON。
   */
  @PostMapping("/lists/{priceListId}/items/import")
  public PriceListItemImportReport importFile(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID priceListId,
      @Valid @RequestBody PriceListItemImportRequest request) {
    return call(auth, "spu.update", a -> items.importFile(a, priceListId, request));
  }

  /**
   * 歧义：同优先级多条有效价目。
   *
   * <p>🔴 蓝图加粗句「同优先级多条有效价目必须报错让用户选，不随机取」的后半句要求
   * 错误里**带得出候选** —— 只回一句「存在多条有效价目」的话，运营不知道该去改哪几条，
   * 界面就只能显示一个红条然后让他自己去猜。
   *
   * <p>所以把并列候选的清单挂在 ProblemDetail 上（与 3.1.3.2 / 3.6.2.1 同一手法），
   * 每条带 id、SKU、单价、数量门槛、优先级、生效区间，界面逐条显示并给出「把这条设为首选」的入口。
   *
   * <p>🔴 这个 handler **刻意放在本控制器里**而不是共享的 {@code ApiErrorHandler} ——
   * 那是多车道共用的文件，改它会在并入时撞车；控制器级 handler 优先于 advice。
   */
  @ExceptionHandler(PriceListItemService.PriceAmbiguityException.class)
  ResponseEntity<ProblemDetail> ambiguity(PriceListItemService.PriceAmbiguityException e) {
    var result = e.resolution();
    var candidates =
        result.candidates().stream()
            .map(
                c ->
                    java.util.Map.of(
                        "id", c.id().toString(),
                        "skuCode", c.skuCode(),
                        "skuName", c.skuName(),
                        "unitPrice", c.unitPrice(),
                        "minQuantity", c.minQuantity(),
                        "priority", c.priority(),
                        "validFrom", c.validFrom().toString(),
                        "validTo", c.validTo() == null ? "" : c.validTo().toString(),
                        "selectionState", c.selectionState()))
            .toList();
    var body = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    body.setProperty("ambiguous", true);
    body.setProperty("candidates", candidates);
    body.setProperty("candidateCount", candidates.size());
    body.setProperty("skuCode", result.skuCode());
    body.setProperty("quantity", result.quantity());
    body.setProperty("ambiguityProblem", result.ambiguityProblem());
    return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
  }
}
