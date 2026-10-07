package com.starcon.catalog;

import static com.starcon.catalog.CatalogModels.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * 3.4.1.1 媒体库：上传与版本、标签、引用计数、失效检测、删除保护。
 *
 * <p>路由挂在 {@code /catalog/media} 之下。台账里的 {@code /products/media} 是
 * **前端页面路径**，与 {@code /products/brands} 同一性质（见 {@link BrandController} 头部）。
 *
 * <p>权限沿用素材域：读 {@code catalog.assets.read}，写 {@code catalog.assets.update}。
 * 这个词表在 V3 里就已经存在（{@code catalog.assets}「素材与翻译」），
 * 不新造——新词表要改 identity 服务的迁移，属于另一个服务的写入所有权。
 *
 * <p>上传走 {@code multipart/form-data}：文件本体是**字节**，走 JSON 编码成 base64
 * 会让请求体膨胀 33%、在内存里多留一份副本，而素材库处理的恰恰是大文件。
 * 元数据（名称/类型/用途/标签）仍然放 JSON 字段，不进文件字段名。
 */
@RestController
@RequestMapping("/catalog/media")
public class MediaController {
  private final AccessService access;
  private final MediaService service;

  public MediaController(AccessService access, MediaService service) {
    this.access = access;
    this.service = service;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "catalog." + permission);
    return access.run(actor, "catalog." + permission, () -> work.apply(actor));
  }

  // ------------------------------------------------------------ 概览与列表

  /** 概览：规模与治理缺口指标，最要紧的是 missingCount（元数据说有、存储里已经没了）。 */
  @GetMapping("/overview")
  public MediaOverview overview(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "assets.read", a -> service.overview());
  }

  /**
   * 素材列表：分页 + 关键词（名称/描述/原始文件名/存储键/标签）+ 类型 / 用途 / 标签 /
   * 完整性 / 引用状态 / 启用状态筛选。
   *
   * <p>每行自带引用计数与完整性状态：删除保护要让人在列表上就看见「被引用着、删不掉」，
   * 逐个点开详情才知道的话，用户会先点删除、再吃一个 409。
   */
  @GetMapping
  public MediaPage list(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String mediaType,
      @RequestParam(required = false) String purpose,
      @RequestParam(required = false) String tagCode,
      @RequestParam(required = false) String integrityStatus,
      @RequestParam(required = false) Boolean referenced,
      @RequestParam(required = false) Boolean enabled,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(
        auth,
        "assets.read",
        a ->
            service.list(
                new MediaQuery(
                    keyword, mediaType, purpose, tagCode, integrityStatus, referenced, enabled,
                    page, size)));
  }

  /** 「这个 SPU / SKU 用了哪些素材」——商品详情页与刊登侧反向查引用。 */
  @GetMapping("/references")
  public List<MediaRow> referencesOf(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam String ownerType,
      @RequestParam(required = false) UUID spuId,
      @RequestParam(required = false) UUID skuId) {
    return call(auth, "assets.read", a -> service.referencesOf(ownerType, spuId, skuId));
  }

  // ------------------------------------------------------------ 上传与版本

  /**
   * 上传一个媒体对象。
   *
   * <p>文件本体走 {@code file} 字段，格式/宽高/时长/哈希由服务端从字节探测，
   * **不接受调用方填写**。同租户内相同内容会返回既有素材并带 {@code deduplicated=true}，
   * 不新建行也不写第二份对象——这就是蓝图说的「素材库复用」。
   */
  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public MediaSaved upload(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestPart("file") MultipartFile file,
      @Valid @RequestPart("meta") MediaUpload input) {
    return call(auth, "assets.update", a -> service.upload(a, file, input));
  }

  /**
   * 追加一个新版本。旧版本永远保留（3.4.1.2：原图始终保留）。
   *
   * <p>版本号由服务端取当前最大值 +1，不接受指定：让调用方传版本号只会把并发
   * 变成一个 409 冲突，而不是告诉用户「当前已经到 v3 了」。
   */
  @PostMapping(path = "/{id}/versions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public MediaSaved addVersion(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestPart("file") MultipartFile file,
      @Valid @RequestPart("meta") MediaVersionUpload input) {
    return call(auth, "assets.update", a -> service.addVersion(a, id, file, input));
  }

  // ------------------------------------------------------------ 详情与读写

  /** 素材详情：本体 + 版本历史 + 标签 + 引用 + 完整性历史 + 删除保护判定。 */
  @GetMapping("/{id}")
  public MediaDetail detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "assets.read", a -> service.detail(id));
  }

  /**
   * 改素材：名称 / 用途 / 描述 / 替代文本 / 标签 / 启停。
   * 媒体类型与文件本体建后不可改（文件本体走新版本）。
   */
  @PutMapping("/{id}")
  public MediaSaved update(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody MediaUpdate input) {
    return call(auth, "assets.update", a -> service.update(a, id, input));
  }

  /** 启用停用。停用后不再出现在新建引用的候选里，已有引用与版本照常可读。 */
  @PutMapping("/{id}/enabled")
  public Map<String, Boolean> setEnabled(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam boolean enabled) {
    call(
        auth,
        "assets.update",
        a -> {
          service.setEnabled(a, id, enabled);
          return Boolean.TRUE;
        });
    return Map.of("enabled", enabled);
  }

  /**
   * 删除素材：要求**没有引用**（蓝图点名的「有引用不可删」）。
   *
   * <p>有引用时返回 400 并说明被引用了几处——这是业务判定不是调用失败，
   * 但调用方需要拿到可读原因去提示用户，所以走 400 + 人话而不是 409。
   * 所有版本的对象会一起删除，不留孤儿文件。
   */
  @DeleteMapping("/{id}")
  public Map<String, Boolean> delete(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam long version) {
    call(
        auth,
        "assets.update",
        a -> {
          service.delete(a, id, version);
          return Boolean.TRUE;
        });
    return Map.of("deleted", true);
  }

  // ------------------------------------------------------------ 引用

  /** 某个素材的引用列表（被哪些 SPU / SKU 用着）。 */
  @GetMapping("/{id}/references")
  public List<MediaReference> references(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "assets.read", a -> service.references(id));
  }

  /**
   * 登记一条引用。
   *
   * <p>listing 引用刻意不在这里：04 刊登在另一个服务且尚未交付，
   * 放一个指向别库不存在表的引用列就是硬编码的假引用。04 落地后走 event / 授权 API 写入。
   */
  @PostMapping("/{id}/references")
  public MediaReferenceSaved addReference(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody MediaReferenceInput input) {
    return call(auth, "assets.update", a -> service.addReference(a, id, input));
  }

  /** 解除一条引用。删引用是正常动作（商品下架后不再用它），不是「删素材」。 */
  @DeleteMapping("/{id}/references/{referenceId}")
  public MediaReferenceSaved removeReference(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @PathVariable UUID referenceId) {
    return call(auth, "assets.update", a -> service.removeReference(a, id, referenceId));
  }

  // ------------------------------------------------------------ 版本历史

  /** 版本历史：按版本号倒序，含哪一版是当前版本。 */
  @GetMapping("/{id}/versions")
  public List<MediaVersion> versions(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "assets.read", a -> service.versions(id));
  }

  // ------------------------------------------------------------ 失效检测

  /**
   * 校验单个素材的存储完整性：真的把对象读回来算 SHA-256。
   *
   * <p>不信任元数据里那行哈希——它记录的是上传当时的字节，文件之后被删 / 被覆盖 /
   * 被迁移只有读回来才知道。每次结果落一行历史，不覆盖。
   */
  @PostMapping("/{id}/verify")
  public MediaIntegrityResult verify(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "assets.update", a -> service.verify(id));
  }

  /** 批量巡检：单次最多 200 个（按最近更新排序）。存量更大时按页分批调用。 */
  @PostMapping("/verify-sweep")
  public MediaIntegritySweep verifySweep(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "assets.update", a -> service.verifyAll());
  }

  /** 某个素材的完整性校验历史（最近 20 次）。 */
  @GetMapping("/{id}/integrity-checks")
  public List<MediaIntegrityCheck> integrityChecks(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "assets.read", a -> service.integrityChecks(id));
  }

  // ------------------------------------------------------------ 分类标签

  /** 标签字典（带使用数）。按标签筛素材的候选来源。 */
  @GetMapping("/tags")
  public List<MediaTagRow> tags(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "assets.read", a -> service.tags());
  }

  @PostMapping("/tags")
  public MediaTagSaved createTag(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody MediaTagInput input) {
    return call(auth, "assets.update", a -> service.saveTag(a, null, input));
  }

  /** 改标签：名称 / 备注 / 启停。编码建后不可改（筛选与批量刊登按 code 对齐）。 */
  @PutMapping("/tags/{tagId}")
  public MediaTagSaved updateTag(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID tagId,
      @Valid @RequestBody MediaTagInput input) {
    return call(auth, "assets.update", a -> service.saveTag(a, tagId, input));
  }

  /**
   * 删除标签。仍被素材使用时被拒，要清理请改用停用——
   * 停用后不再出现在新建筛选里，历史关联保留。
   */
  @DeleteMapping("/tags/{tagId}")
  public Map<String, Boolean> deleteTag(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID tagId,
      @RequestParam long version) {
    call(
        auth,
        "assets.update",
        a -> {
          service.deleteTag(a, tagId, version);
          return Boolean.TRUE;
        });
    return Map.of("deleted", true);
  }

  @PutMapping("/tags/{tagId}/enabled")
  public Map<String, Boolean> setTagEnabled(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID tagId,
      @RequestParam boolean enabled) {
    call(
        auth,
        "assets.update",
        a -> {
          service.setTagEnabled(a, tagId, enabled);
          return Boolean.TRUE;
        });
    return Map.of("enabled", enabled);
  }

  /** 未登录一律 401（由 AccessService 抛出，这里保留常量引用避免误删 import）。 */
  static final HttpStatus UNAUTHORIZED = HttpStatus.UNAUTHORIZED;

  static ResponseStatusException notFound() {
    return new ResponseStatusException(HttpStatus.NOT_FOUND);
  }
}