package com.starcon.catalog;

import static com.starcon.catalog.CustomsDestinationVersionModels.ResolvedDeclaration;

import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.*;

/**
 * <b>供 10 报关调用的合成出口</b>（{@code GET /catalog/customs-declaration/resolve}）：
 * 把 3.7.1.1 的基础报关属性与 3.7.1.2 的目的地覆盖合成成<b>一份可直接申报的资料</b>。
 *
 * <p><b>为什么不挂在 {@code /catalog/customs-destination-versions} 下面</b>：
 * 那组路径回答的是「这个目的地的差异声明维护得怎么样」（维护界面数据），
 * 而这一条回答的是「这批货发到德国按什么报」（报关事实）。
 * 两者被不同的人、在不同的时刻、以不同的权限读，混在一个前缀下会让
 * 「改一个版本」和「取一份申报资料」在接口上看起来是同一件事。
 *
 * <p><b>只读、GET + query</b>：它是清关时的一次读，必须能被 GET 缓存与重试
 * （重试一个写操作在语义上是危险的）。
 *
 * <p><b>两道闸门都不过就报错，且没有任何降级</b>（见
 * {@link CustomsDestinationVersionService#resolve}）：
 * 基础属性未审核通过 → 409；该目的地无生效版本 → 409。
 * 回退到基础属性就是蓝图原文要杜绝的「用一套申报资料打通全球」，
 * 所以文案里连「将使用基础报关属性」这类降级说明都不出现。
 */
@RestController
@RequestMapping("/catalog/customs-declaration")
public class CustomsDeclarationResolveController {
  private final AccessService access;
  private final CustomsDestinationVersionService service;

  public CustomsDeclarationResolveController(AccessService access, CustomsDestinationVersionService service) {
    this.access = access;
    this.service = service;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "catalog." + permission);
    return access.run(actor, "catalog." + permission, () -> work.apply(actor));
  }

  /**
   * 合成后的申报资料：<b>税号与申报品名按目的地覆盖，法定计量单位与申报要素只来自基础属性</b>。
   *
   * <p>返回体字段与 V85 的 {@code customs_declaration_line} 逐一对齐（见
   * {@link ResolvedDeclaration} 的类注释），回填时不需要做名称翻译。
   *
   * <p>{@code at} 可空 = 查此刻；带时刻是为了让 10 报关能回溯
   * 「这批货当时是按哪一版目的地方案报的」。
   */
  @GetMapping("/resolve")
  public ResolvedDeclaration resolve(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam UUID skuId,
      @RequestParam String destination,
      @RequestParam(required = false) String at) {
    return call(auth, "compliance.read", a -> service.resolve(skuId, destination, at));
  }
}
