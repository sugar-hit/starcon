package com.starcon.channel;

import static com.starcon.channel.ListingMappingModels.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import java.util.function.BiFunction;
import org.springframework.web.bind.annotation.*;

/**
 * 4.1.2.1 映射管理。
 *
 * <p>蓝图：「建立并维护"平台变体 → 内部 SKU/套装"的映射，支持一对一、一对多（套装）、多对一；支持批量映射（按规则：SKU 编码匹配、条码匹配、标题相似度推荐但需人工确认）。输入：映射建立操作；匹配规则；批量候选。输出：{@code listing_mapping_version} 记录（当前生效版本 + 历史版本）；未映射清单回流 02 映射缺失队列。关系：上游 = 4.1.1.1、02 同步到的 listing；下游 = 09 订单（订单行按映射解析 SKU）、07 库存（回写可售量）。<b>映射未建立时订单进入待映射状态，绝不按名称猜 SKU</b>（实体目录 §6）。」
 *
 * <p>端点按蓝图模块号标注，方便对照 04-listing-and-content.md。四个入口各自对应职责句里的一个词：
 *
 * <ul>
 *   <li>{@code POST /} 与 {@code DELETE /{id}} —— 「映射建立操作」与它的撤销。改映射<b>生成新版本</b>，
 *       不原地改（4.1.2.2 加粗句的物理落点）。
 *   <li>{@code POST /batch-candidates} —— 「批量候选」。<b>只写候选表，一个字节的映射行都不写</b>；
 *       标题相似度候选恒 {@code requiresConfirmation=true}，要变成映射必须走 {@code POST /candidates/{id}/decide}。
 *   <li>{@code POST /resolve} —— 09 订单行的解析入口。<b>没有生效映射就返回 {@code PENDING_MAPPING}
 *       且不给任何 SKU</b>，绝不按名称猜。
 *   <li>{@code GET /unmapped} —— 「未映射清单」。回流目标 2.4.2.3 未交付，端点显式返回
 *       {@code backflowDelivered=false} 而不是假装已回流。
 * </ul>
 *
 * <p>权限码<b>复用</b> identity 已有的 {@code channel.operation.*}（与 4.1.1.1 Listing 列表同族），
 * identity 服务零改动。
 */
@RestController
@RequestMapping("/channel/listing-mappings")
public class ListingMappingController {

  private final AccessService access;
  private final ListingMappingService service;

  public ListingMappingController(AccessService access, ListingMappingService service) {
    this.access = access;
    this.service = service;
  }

  private <T> T call(
      String bearer, String permission, java.util.function.Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, permission);
    return access.run(actor, permission, () -> work.apply(actor));
  }

  /**
   * 需要把调用方 bearer 透传给 catalog 端口的入口。
   *
   * <p>🔴 租户<b>不由本服务传给 catalog</b>：转发的是调用方自己的 bearer，
   * catalog 侧仍按已认证成员关系解析租户并强制 RLS（与 {@link CarrierProfileQueryPort} 同一纪律）。
   */
  private <T> T callWithBearer(
      String bearer, String permission, BiFunction<AccessService.Access, String, T> work) {
    var actor = access.require(bearer, permission);
    return access.run(actor, permission, () -> work.apply(actor, bearer));
  }

  // ---- 能力声明 / 筛选项 ----

  /**
   * 能力声明：逐维度 available + ownerModule + 影响。
   *
   * <p>🔴 未交付段显式 {@code available=false}，不用空数组或 0 冒充「这一项没有数据」。
   */
  @GetMapping("/capabilities")
  public ListingMappingCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.capabilities());
  }

  /** 筛选面板的可选值：全部来自本模块的词表，不硬编码在前端。 */
  @GetMapping("/filter-options")
  public MappingFilterOptions filterOptions(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.filterOptions());
  }

  // ---- 映射 CRUD ----

  /** 分页列表（基数 / 状态 / 存续 / 内部 SKU / 店铺 / 关键词 + 真分页）。 */
  @GetMapping
  public MappingPage list(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String relationType,
      @RequestParam(required = false) String mappingStatus,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String internalSkuId,
      @RequestParam(required = false) String shopCode,
      @RequestParam(required = false) String search,
      @RequestParam(required = false, defaultValue = "RECENT") String sort,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "channel.operation.read",
        a ->
            service.list(
                relationType, mappingStatus, status, internalSkuId, shopCode, search, sort,
                page, size));
  }

  /**
   * 单条详情：当前映射 + <b>完整版本历史</b>（生效时间点与变更原因逐版返回）。
   *
   * <p>🔴 不返回「受影响订单范围」：4.1.2.2 未交付且 09 订单侧无真实来源，
   * {@code affectedOrdersNote} 里写明原因而不是返回空数组冒充。
   */
  @GetMapping("/{id}")
  public MappingDetail load(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.read", a -> service.load(id));
  }

  /**
   * 建立 / 修改映射。
   *
   * <p>🔴 已建立过的变体再提交会<b>改这一条并生成新版本</b>，不会插出第二条生效映射 ——
   * 两条都生效的话，订单解析时"取哪一条"没有答案。
   */
  @PostMapping
  public MappingDetail save(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody SaveMappingRequest request) {
    return call(auth, "channel.operation.create", a -> service.save(request));
  }

  /**
   * 解除映射。
   *
   * <p>🔴 解除<b>不是删除</b>：变体退回未映射（订单行重新进入待映射状态），
   * 但历史版本原样保留 —— 「曾经存在过、什么时候撤的、为什么撤」必须可回溯。
   */
  @DeleteMapping("/{id}")
  public MappingDetail remove(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody RemoveMappingRequest request) {
    return call(auth, "channel.operation.update", a -> service.remove(id, request));
  }

  // ---- 批量候选（三条规则） ----

  /**
   * 批量映射候选。
   *
   * <p>🔴 候选真的来自 catalog 授权 API，标题相似度由 PostgreSQL {@code pg_trgm similarity()} 真算。
   * <b>本端点只写候选表</b>：确认（{@code POST /candidates/{id}/decide}）之前不产生任何映射行 ——
   * 这正是蓝图「标题相似度推荐但需人工确认」的落点。
   */
  @PostMapping("/batch-candidates")
  public BatchCandidateResult batchCandidates(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody BatchCandidateRequest request) {
    return callWithBearer(
        auth, "channel.operation.read", (a, bearer) -> service.batchCandidates(request, bearer));
  }

  /** 候选列表（按批次 / 变体 / 裁决状态筛）。 */
  @GetMapping("/candidates")
  public CandidatePage candidates(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String batchTaskId,
      @RequestParam(required = false) UUID listingVariantId,
      @RequestParam(required = false) String decision,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "channel.operation.read",
        a -> service.candidates(batchTaskId, listingVariantId, decision, page, size));
  }

  /**
   * 裁决候选：{@code CONFIRMED} 建映射并生成版本，{@code REJECTED} 只记否决。
   *
   * <p>🔴 这是候选变成映射的<b>唯一</b>路径。已裁决的候选再裁决返回 409，
   * 而不是静默改写已落库的映射。
   */
  @PostMapping("/candidates/{id}/decide")
  public CandidateDecisionResult decideCandidate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody CandidateDecisionRequest request) {
    return call(auth, "channel.operation.update", a -> service.decideCandidate(id, request));
  }

  // ---- 未映射清单 / 订单行解析 / 多对一反查 ----

  /**
   * 未映射变体清单（回流 02 映射缺失队列的数据源）。
   *
   * <p>🔴 回流目标 2.4.2.3 未交付，响应里 {@code backflowDelivered=false} + ownerModule + 说明。
   * 队列落地后本端点可直接作为其数据源，清单口径不需要改。
   */
  @GetMapping("/unmapped")
  public UnmappedPage unmapped(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String shopCode,
      @RequestParam(required = false) String search,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "channel.operation.read", a -> service.unmapped(shopCode, search, page, size));
  }

  /**
   * 按映射解析平台变体 → 内部 SKU（09 订单行解析入口）。
   *
   * <p>🔴 <b>蓝图加粗句的落点</b>：没有生效映射就返回 {@code PENDING_MAPPING} 且
   * {@code internalSkuId=null}。<b>没有第三种「疑似匹配」</b> —— 哪怕候选表里有一个 0.92 分的
   * 标题相似度候选，这里也不会用它：候选没经人工确认就不是映射。
   *
   * <p>{@code at} 支持按时刻回溯（4.1.2.2 的生效时间点语义）：传历史时刻取当时生效的版本。
   */
  @PostMapping("/resolve")
  public List<MappingResolveResult> resolve(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody MappingResolveRequest request) {
    return call(auth, "channel.operation.read", a -> service.resolve(request));
  }

  /**
   * 多对一（MANY_TO_ONE）反查：哪些平台变体共用这个内部 SKU。
   *
   * <p>「多对一」不留读出口就只是一句声明 —— 没有端点能证明「改这条映射会波及哪几个平台商品」。
   */
  @GetMapping("/by-sku/{internalSkuId}")
  public SkuUsage skuUsage(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String internalSkuId) {
    return call(auth, "channel.operation.read", a -> service.skuUsage(internalSkuId));
  }
}
