package com.starcon.catalog;

import static com.starcon.catalog.CatalogModels.*;

import jakarta.validation.Valid;
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
 * 3.5.2.2 BOM 展开：多层递归展开、子 BOM 判定、按仓库过滤、需求量/可用/在途/缺口。
 *
 * <p>路由挂在 {@code /catalog/bom-explosions} 之下，与 3.5.2.1 的
 * {@code /catalog/boms}、{@code /catalog/bom-versions} **并列**：
 * 那边管的是「物料清单长什么样」，这里管的是「按这版清单生产 N 个成品要哪些料、缺多少」。
 * 合并会让「v2 指哪个版本」在 URL 上无法回答。台账里的 {@code /products/bom} 是前端页面路径。
 *
 * <p><b>为什么展开结果要落库而不是现算</b>：蓝图说「展开结果是建议不是事实」，
 * 而建议会变成真实的采购单。06 的单子引用的是<b>某一次展开的 id</b>，
 * 不是「现在的 BOM × 现在的库存」—— 后者随时会变，单子发出去之后就无法解释
 * 「当时为什么按这个数下单」。
 *
 * <p>权限沿用 {@code catalog.spu.read} / {@code catalog.spu.update}（3.5.2.1 同一词表）。
 *
 * <p><b>跨服务</b>：{@link Authorization} 头原样透传给 warehouse 的可用量端点，
 * 租户由它从已认证成员关系解析 —— catalog 不发明「客户端自称租户」的旁路头。
 */
@RestController
@RequestMapping("/catalog")
public class BomExplosionController {
  private final AccessService access;
  private final BomExplosionService explosions;

  public BomExplosionController(AccessService access, BomExplosionService explosions) {
    this.access = access;
    this.explosions = explosions;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "catalog." + permission);
    return access.run(actor, "catalog." + permission, () -> work.apply(actor));
  }

  /**
   * 展开一次（多层递归 + 按仓库过滤 + 缺口），并把结果冻结成一条运行记录。
   *
   * <p>用 POST 而不是 GET：展开会写运行记录与明细行（建议要能被 06 的采购单引用），
   * 且一次展开树可能有几百行，放 URL 里既超长又会把清单塞进访问日志。
   *
   * <p><b>权限用读权限而不是写权限</b>：展开是<b>纯计算</b>，它不改动任何业务事实 ——
   * 蓝图明确「展开结果是建议不是事实」。落库的那份是调用者自己这份建议的快照，
   * 改动 BOM、组件或库存的入口仍然是 3.5.2.1 的 {@code spu.update} 与 warehouse 的库存写权限。
   * 给一个只读运营看展开结果却要求写权限，等于把「算一下缺多少料」这个最常见的只读操作
   * 变成 403。
   *
   * <p><b>循环引用返回 409 且不落任何记录</b>：绕回自己的 BOM 需求量在数学上无界，
   * 任何截断都是错的小数，而它会被当成建议下成真实的单。错误信息逐个点名绕回路径。
   */
  @PostMapping("/bom-explosions")
  public BomExplosionRun explode(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody BomExplosionInput input) {
    return call(auth, "spu.read", a -> explosions.explode(a, auth, input));
  }

  /** 读回一次展开（含完整展开树）。06 的采购单引用的是这个 id。 */
  @GetMapping("/bom-explosions/{id}")
  public BomExplosionRun run(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "spu.read", a -> explosions.run(id));
  }

  /** 展开运行记录列表：分页 + 按 BOM / 成品 SKU 过滤（只带摘要，不带展开树）。 */
  @GetMapping("/bom-explosions")
  public BomExplosionRunPage runs(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID bomId,
      @RequestParam(required = false) UUID skuId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(auth, "spu.read", a -> explosions.runs(new BomExplosionRunQuery(bomId, skuId, page, size)));
  }
}
