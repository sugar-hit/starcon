package com.starcon.channel;

import static com.starcon.channel.AdTypeDiffModels.*;

import jakarta.validation.Valid;
import java.util.function.Function;
import org.springframework.web.bind.annotation.*;

/**
 * 14.1.2.2 广告类型差异。
 *
 * <p>蓝图：「适配不同平台与广告类型的字段差异（自动广告、SB/SD、品牌广告、DSP、大促广告），把差异字段归一到内部
 * 模型展示。输入：各平台广告类型定义；平台 API 返回。输出：归一后的广告类型视图 + 特有字段补充展示。
 * 关系：上游 = 2.1.2.1 平台映射；下游 = 14.1.1.1。<b>归一化让跨平台广告可比</b>，但<b>必须保留原始类型标记，不能丢信息
 * </b>。」端点按蓝图模块号标注，方便对照 14-ads-and-growth.md。
 *
 * <p>🔴 三个入口刻意分开，各自对应职责句里的一个词：
 *
 * <ul>
 *   <li>{@code GET /resolve} —— 「上游 = 2.1.2.1 平台映射」。**无副作用**地问一句「这个原始类型该归到哪个内部类型」，
 *       供建档前自查。查不到就明说未映射，不兜底。
 *   <li>{@code POST /profiles} + {@code POST /payloads} —— 「输入」。登记类型定义与平台 API 返回。
 *   <li>{@code GET /comparable} + {@code GET /payloads/{id}} —— 「输出」。分别是归一后的广告类型视图、
 *       以及带特有字段补充展示的详情。
 * </ul>
 *
 * <p>权限码<b>复用</b> identity 已有的 {@code channel.operation.*}（与 10.1.1.2 渠道配置、
 * 10.1.2.1 运费计算器、2.1.2.1 平台枚举映射同族），identity 服务零改动。
 */
@RestController
@RequestMapping("/channel/ad-type-diffs")
public class AdTypeDiffController {

  private final AccessService access;
  private final AdTypeDiffService service;

  public AdTypeDiffController(AccessService access, AdTypeDiffService service) {
    this.access = access;
    this.service = service;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, permission);
    return access.run(actor, permission, () -> work.apply(actor));
  }

  // ---- 能力声明 ----

  /** 能力声明：未交付的段（广告 ↔ SKU 归因、活动侧分组）显式 available=false + ownerModule + 影响。 */
  @GetMapping("/capabilities")
  public AdTypeDiffCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.capabilities());
  }

  // ---- 归一口径解析（上游 2.1.2.1）----

  /**
   * 走 2.1.2.1 两层解析原始广告类型的归一口径（租户覆盖 &gt; 平台默认 &gt; 未映射）。
   *
   * <p>🔴 {@code mapped=false} + {@code internalAdType=UNMAPPED} 是合法答案：照抄 2.1.2.1 加粗句
   * 「映射缺失必须显式报未映射，不能默认落到某个兜底状态」。
   */
  @GetMapping("/resolve")
  public MappingResolution resolve(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam String platformId,
      @RequestParam String platformAdType) {
    return call(
        auth,
        "channel.operation.read",
        a -> service.resolve(java.util.UUID.fromString(platformId), platformAdType));
  }

  // ---- 广告类型档案（「各平台广告类型定义」）----

  /** 档案列表：真分页 + 真筛选。 */
  @GetMapping("/profiles")
  public AdTypeProfilePage profiles(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String platformId,
      @RequestParam(required = false) String internalAdType,
      @RequestParam(required = false) String status,
      @RequestParam(required = false, defaultValue = "PLATFORM") String sort,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "channel.operation.read",
        a ->
            service.profiles(
                blank(platformId), internalAdType, status, sort, page, size));
  }

  /**
   * 登记 / 改一条广告类型档案。
   *
   * <p>🔔 声明的 {@code internalAdType} 与 {@code mappingSource} 必须与 2.1.2.1 实际解析出的结果一致 ——
   * 归一口径的真相在 2.1.2.1，不在请求里。DB 层还会再核一遍那两张表里确有对应行。
   */
  @PostMapping("/profiles")
  public AdTypeProfileRow saveProfile(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody SaveProfileRequest request) {
    return call(auth, "channel.operation.create", a -> service.saveProfile(request));
  }

  /** 档案详情：档案本体 + 特有字段定义 + 尚未被任何字段定义覆盖的原始键。 */
  @GetMapping("/profiles/{id}")
  public AdTypeProfileDetail profile(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.read", a -> service.profile(id));
  }

  /** 某档案下的全部特有字段定义（展示顺序即 position）。 */
  @GetMapping("/profiles/{id}/field-specs")
  public java.util.List<AdTypeFieldSpecRow> fieldSpecs(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.read", a -> service.fieldSpecs(id));
  }

  /**
   * 登记 / 改一条特有字段定义。
   *
   * <p>🔔 {@code comparable=true} 时必须给 {@code internalRepresentation}：声称可比却没有内部落点是
   * 自相矛盾，DB 层是 CHECK 拒（声称可比 ⇒ 一定有内部落点）。
   */
  @PostMapping("/profiles/{id}/field-specs")
  public AdTypeFieldSpecRow saveFieldSpec(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody SaveFieldSpecRequest request) {
    return call(auth, "channel.operation.create", a -> service.saveFieldSpec(id, request));
  }

  // ---- 归一后的广告数据（「平台 API 返回」）----

  /** 归一后的广告数据列表：真分页 + 真筛选，汇总不受分页影响。 */
  @GetMapping("/payloads")
  public AdTypePayloadPage payloads(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String platformId,
      @RequestParam(required = false) String internalAdType,
      @RequestParam(required = false) String mappingStatus,
      @RequestParam(required = false) String shopCode,
      @RequestParam(required = false, defaultValue = "INGESTED_DESC") String sort,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "channel.operation.read",
        a ->
            service.payloads(
                blank(platformId), internalAdType, mappingStatus, shopCode, sort, page, size));
  }

  /**
   * 归一后的广告数据详情：原始报文原文 + 服务端已组装好的特有字段补充展示。
   *
   * <p>🔴 特有字段的「原始值」与「归一后的内部落点」在服务端拼成同一条返回 —— 前端只渲染，
   * 两端各拼一次就会出现两个版本。跨租户与不存在返回逐字相同的 404。
   */
  @GetMapping("/payloads/{id}")
  public AdTypePayloadDetail payload(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.read", a -> service.payload(id));
  }

  /**
   * 登记一条归一后的广告数据。
   *
   * <p>🔔 入参里**没有** {@code platformAdType} / {@code internalAdType} / {@code rawOnlyFieldKeys} /
   * {@code mappingStatus} —— 这四列由 DB 触发器从档案与原始报文派生，服务层声称什么都不作数。
   */
  @PostMapping("/payloads")
  public SaveResponse<AdTypePayloadRow> savePayload(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody SavePayloadRequest request) {
    return call(auth, "channel.operation.create", a -> service.savePayload(request));
  }

  // ---- 归一后的广告类型视图（「输出」之一）----

  /**
   * 归一后的广告类型视图：按内部类型横向聚合，**每个类型都带着各平台的原始类型标记**。
   *
   * <p>🔴 UNMAPPED 类型不进可比汇总，但在 {@code unmappedTypes} 里原样列出 —— 它不是「没有这种广告」，
   * 只是「没有可比口径」，从表里删掉会让人以为它不存在。
   */
  @GetMapping("/comparable")
  public ComparableView comparable(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.comparable());
  }

  /** 保真概览：「系统没有归一哪些字段」的明账，按出现次数排序。 */
  @GetMapping("/fidelity")
  public FidelityOverview fidelity(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.fidelity());
  }

  /** 空串与 null 同义，避免把 {@code ?platformId=} 当成「筛选空字符串」。 */
  private static java.util.UUID blank(String value) {
    if (value == null || value.isBlank()) return null;
    try {
      return java.util.UUID.fromString(value.trim());
    } catch (IllegalArgumentException e) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.BAD_REQUEST, "平台 id 不是合法 UUID");
    }
  }
}
