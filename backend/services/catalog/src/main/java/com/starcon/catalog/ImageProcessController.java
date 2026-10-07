package com.starcon.catalog;

import static com.starcon.catalog.CatalogModels.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.*;

/**
 * 3.4.1.2 图片处理：处理配方（目标平台规格）、异步处理任务、进度与产物。
 *
 * <p>路由挂在 {@code /catalog/media/processing} 之下——与 3.4.1.1 的 {@code /catalog/media}
 * 同前缀，因为处理的输入就是素材、产物也就是素材的新版本。
 * 台账里的 {@code /products/media} 是**前端页面路径**（与 {@code BrandController} 头部同一说明）。
 *
 * <p>权限沿用素材域：读 {@code catalog.assets.read}，写 {@code catalog.assets.update}。
 * 不新造词表——新词表要改 identity 服务的迁移，属于另一个服务的写入所有权。
 *
 * <p><b>提交接口是「已受理」语义而不是「已完成」</b>：它写完任务与条目就返回
 * （蓝图要求长批量不阻塞页面），进度从任务详情查。
 */
@RestController
@RequestMapping("/catalog/media/processing")
public class ImageProcessController {
  private final AccessService access;
  private final ImageProcessService service;

  public ImageProcessController(AccessService access, ImageProcessService service) {
    this.access = access;
    this.service = service;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "catalog." + permission);
    return access.run(actor, "catalog." + permission, () -> work.apply(actor));
  }

  // ------------------------------------------------------------ 概览

  /** 处理概览：任务量、成功/失败分布、产物规模。 */
  @GetMapping("/overview")
  public MediaProcessOverview overview(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "assets.read", a -> service.overview());
  }

  // ------------------------------------------------------------ 配方

  /** 配方列表（带比例档）。停用的也在列表里——要看历史配置。 */
  @GetMapping("/recipes")
  public List<MediaProcessRecipeRow> recipes(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "assets.read", a -> service.recipes());
  }

  @GetMapping("/recipes/{id}")
  public MediaProcessRecipeRow recipe(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "assets.read", a -> service.recipe(id));
  }

  /**
   * 建配方或改配方（id 为空即新建）。比例档整体替换。
   *
   * <p>编码建后不可改：产物的比例档是按这套规格解释的，改编码会让历史产物无法回溯。
   */
  @PostMapping("/recipes")
  public MediaProcessRecipeSaved createRecipe(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody MediaProcessRecipeInput input) {
    return call(auth, "assets.update", a -> service.saveRecipe(a, null, input));
  }

  @PutMapping("/recipes/{id}")
  public MediaProcessRecipeSaved updateRecipe(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody MediaProcessRecipeInput input) {
    return call(auth, "assets.update", a -> service.saveRecipe(a, id, input));
  }

  /**
   * 删除配方。仍被任务引用时返回 400 并说明被几个任务引用——
   * 要清理请改用停用（停用后不能再用它新建任务，历史任务与产物照常可读）。
   */
  @DeleteMapping("/recipes/{id}")
  public Map<String, Boolean> deleteRecipe(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam long version) {
    call(
        auth,
        "assets.update",
        a -> {
          service.deleteRecipe(a, id, version);
          return Boolean.TRUE;
        });
    return Map.of("deleted", true);
  }

  @PutMapping("/recipes/{id}/enabled")
  public MediaProcessRecipeSaved setRecipeEnabled(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam boolean enabled) {
    return call(auth, "assets.update", a -> service.setRecipeEnabled(a, id, enabled));
  }

  // ------------------------------------------------------------ 任务

  /** 任务列表：分页 + 关键词（搜任务、配方名、它涉及的素材名）+ 状态筛选。 */
  @GetMapping("/jobs")
  public MediaProcessJobPage jobs(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String status,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(
        auth,
        "assets.read",
        a -> service.listJobs(new MediaProcessJobQuery(keyword, status, page, size)));
  }

  /**
   * 提交处理任务（异步）。
   *
   * <p>返回即代表「已受理」：任务与条目已落库，像素加工在线程池里跑。
   * 页面应该轮询任务详情看进度，而不是等这个请求返回结果——
   * 蓝图要求「长批量不阻塞页面」，所以这里绝不做同步处理。
   */
  @PostMapping("/jobs")
  public MediaProcessJob submit(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody MediaProcessJobInput input) {
    return call(auth, "assets.update", a -> service.submit(a, input));
  }

  /** 任务详情：任务 + 配方比例档 + 条目（含产物与失败原因）。 */
  @GetMapping("/jobs/{jobId}")
  public MediaProcessJobDetail job(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID jobId) {
    return call(auth, "assets.read", a -> service.detail(jobId));
  }

  /** 任务条目（含产物与失败原因）。 */
  @GetMapping("/jobs/{jobId}/items")
  public List<MediaProcessJobItem> items(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID jobId) {
    return call(auth, "assets.read", a -> service.items(jobId));
  }

  /**
   * 请求取消任务。**不阻塞**：只置标志位，工作线程在条目之间检查。
   * 已在加工的那一张会做完（像素处理没有中间态），剩下条目置「已取消」。
   */
  @PostMapping("/jobs/{jobId}/cancel")
  public MediaProcessJob cancel(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID jobId) {
    return call(auth, "assets.update", a -> service.cancel(a, jobId));
  }

  /** 某个素材的处理历史（素材详情页的「处理记录」区）。 */
  @GetMapping("/assets/{assetId}/history")
  public List<MediaProcessJobRow> history(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID assetId) {
    return call(auth, "assets.read", a -> service.historyOfAsset(assetId));
  }
}