package com.starcon.catalog;

import static com.starcon.catalog.VendorListModels.*;

import jakarta.validation.Valid;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

/**
 * 6.1.1.1 供应商列表：<b>采购视角</b>的供应商清单。
 *
 * <p>路由取 {@code /catalog/supply/vendors/*}（而不是挂到 {@code parties} 之下）：蓝图把本模块与 3.6.1.1 主体档案分成两个 L4 模块，
 * 前者是**采购视角的视图与运营动作**，后者是主体主数据。路由分开是为了让「谁能改什么」在 URL 上就是看得见的 ——
 * {@code /catalog/parties/*} 上的写端点改主体档案，{@code /catalog/supply/vendors/*} 上的写端点只改采购侧状态与事实。
 * 把两者混在一个前缀下，界面上「改合作等级」这一个按钮就会变得含糊：它到底写的是 3.6.1.2 还是本模块？
 *
 * <p>页面路径是台账里记的 {@code /supply/vendors}，后端前缀沿用 {@code /catalog/supply/vendors}。
 *
 * <p><b>权限</b>：读 {@code catalog.spu.read}，写 {@code catalog.spu.update}，与 3.6.1.1 / 3.6.1.2 完全一致 ——
 * 供应商档案是商品主数据域里往来单位档案的下半部分，不新造权限词表（那要改 identity 服务的迁移，属于另一个服务的写入所有权）。
 *
 * <p><b>租户</b>：一律来自已认证成员关系（{@link AccessService} 向 identity 解析），<b>不读 {@code X-Tenant-Id} 头</b>：
 * 那是客户端自称的租户，AGENTS.md 明令禁止。
 *
 * <p><b>导出</b>不在这里：蓝图要求的「支持导出」由 {@link VendorListExportDataSource} 挂进 3.8.2.1 的导出注册表实现 ——
 * 那是本仓库里**唯一**一条导出通道，它自带字段白名单、异步执行、PII 授权与审计，
 * 在这里另开一个 {@code GET /export} 端点会造出第二条没有审计的通道，而联系方式正是需要被审计的那类字段。
 */
@RestController
@RequestMapping("/catalog/supply/vendors")
public class VendorListController {
  private final AccessService access;
  private final VendorListService service;

  public VendorListController(AccessService access, VendorListService service) {
    this.access = access;
    this.service = service;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "catalog." + permission);
    return access.run(actor, "catalog." + permission, () -> work.apply(actor));
  }

  /**
   * 词表选项：前端下拉的数据源。
   *
   * <p>合作等级与主体状态是<b>只读</b>词表：它们由 3.6.1.2 / 3.6.1.1 维护，本模块只是把同一份取值给下拉用
   * （枚举只增不改这条承诺要在**一处**兑现，前端与后端各维护一份迟早会不一样）。本模块<b>不</b>提供写这两个字段的端点。
   */
  @GetMapping("/options")
  public VendorListOptions options(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "spu.read", a -> service.options());
  }

  /**
   * 概览：供应商规模、采购侧状态分布与两个全租户口径的比率。
   *
   * <p>两个比率<b>按记录数 / 数量加权，不是各家比率的平均</b>；覆盖率（有多少家真的有记录）一并返回，
   * 因为只有 3 家有记录时那个「准时率」几乎没有参考价值。
   */
  @GetMapping("/overview")
  public VendorListOverview overview(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "spu.read", a -> service.overview());
  }

  /**
   * 供应商列表：分页 + 关键词 / 合作状态 / 类目 / 合作等级 / 主体状态 / 币种 / 「缺运营档案」筛选，
   * 每行带供货 SKU 数、采购金额累计、准时交付率、质量合格率四个指标。
   *
   * <p><b>编码 / 名称 / 联系方式 / 合作等级 / 主体状态 全部读自 3.6.1.1 与 3.6.1.2</b>，本模块不写它们 ——
   * 供应商主数据只有一份（蓝图那句加粗的硬要求）。
   *
   * <p>准时交付率与质量合格率是<b>有时间窗</b>的统计：{@code metricFrom} / {@code metricTo} 缺省取最近 365 天，
   * 实际生效的窗口会回显在响应的 {@code metricWindow} 上。
   */
  @GetMapping
  public VendorListPage list(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String supplyStatus,
      @RequestParam(required = false) UUID categoryId,
      @RequestParam(required = false) String cooperationLevel,
      @RequestParam(required = false) String partyStatus,
      @RequestParam(required = false) String currency,
      @RequestParam(required = false) Boolean missingProfile,
      @RequestParam(required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime metricFrom,
      @RequestParam(required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime metricTo,
      @RequestParam(required = false) String sort,
      @RequestParam(required = false) Boolean desc,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    // 逐个 @RequestParam 而不是把 {@link VendorListQuery} 当模型属性绑定：
    // 与 PartyController / SkuListController 同一口径。模型属性绑定 record 时，
    // 少给一个参数得到的是一条与筛选条件毫无关系的 400 报文，
    // 而显式声明的每个参数都会进 OpenAPI 契约，前端能直接看到可筛项。
    return call(
        auth,
        "spu.read",
        a ->
            service.list(
                new VendorListQuery(
                    keyword,
                    supplyStatus,
                    categoryId,
                    cooperationLevel,
                    partyStatus,
                    currency,
                    missingProfile,
                    metricFrom,
                    metricTo,
                    sort,
                    desc,
                    page,
                    size)));
  }

  /**
   * 采购视角详情（列表下钻）：状态 + 指标 + 最近到货 / 质检记录 + 治理警告。
   *
   * <p><b>不是「供应商详情」</b>：6.1.1.2 才是那个模块（基础信息 + 账期 + 报价 + 附件资质全景）。
   * 本端点只回答采购侧的一件事，避免同一份内容两处可编辑。
   */
  @GetMapping("/{roleId}")
  public VendorPurchaseView detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID roleId) {
    return call(auth, "spu.read", a -> service.purchaseView(roleId));
  }

  /**
   * 建 / 改采购侧运营档案（合作状态 + 主供类目 + 备注）。
   *
   * <p>入参里**没有 code / name / email / cooperationLevel**：那些是 3.6.1.1 与 3.6.1.2 的字段。
   * 「把采购侧状态顺手写回主体档案」看起来很方便，但那就是蓝图点名要避免的「同一实体两处可编辑」——
   * 改完之后 11 售后单上的供应商名与 12 应付里对上的对手方名称会和界面上显示的不一致，而且**没有任何东西会报错**。
   *
   * <p>{@code version} 是行级乐观锁（新建时传 0）。
   */
  @PutMapping("/{roleId}/profile")
  public VendorFactSaved saveProfile(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID roleId,
      @Valid @RequestBody VendorSupplyProfileInput input) {
    return call(auth, "spu.update", a -> service.saveProfile(a, roleId, input));
  }

  /**
   * 追加一条到货记录（采购金额累计 + 准时交付率的原料）。
   *
   * <p><b>只追加</b>：到货是发生过的事实，没有 UPDATE 端点。录错了用 {@code /void} 作废并留理由 ——
   * 一条作废记录仍留在库里，这样「这个供应商的准时率里混进过什么」永远可追溯。
   */
  @PostMapping("/{roleId}/deliveries")
  public VendorFactSaved recordDelivery(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID roleId,
      @Valid @RequestBody VendorDeliveryInput input) {
    return call(auth, "spu.update", a -> service.recordDelivery(a, roleId, input));
  }

  /** 作废一条到货记录（带理由）。作废的行不计入任何指标。 */
  @PostMapping("/{roleId}/deliveries/{recordId}/void")
  public Map<String, Boolean> voidDelivery(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID roleId,
      @PathVariable UUID recordId,
      @Valid @RequestBody VendorVoidInput input) {
    return voided(auth, a -> service.voidDelivery(a, roleId, recordId, input));
  }

  /**
   * 追加一条质检记录（质量合格率的原料）。
   *
   * <p>合格数量不得超过检验数量：抽检数据录错（合格 120 / 检验 100）会让这家供应商的合格率算出 120%，
   * 而报表不会报错、只会显示一个荒谬的数字 —— 所以服务层与 DB CHECK 各拦一道。
   */
  @PostMapping("/{roleId}/quality-inspections")
  public VendorFactSaved recordInspection(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID roleId,
      @Valid @RequestBody VendorQualityInspectionInput input) {
    return call(auth, "spu.update", a -> service.recordInspection(a, roleId, input));
  }

  /** 作废一条质检记录（带理由）。 */
  @PostMapping("/{roleId}/quality-inspections/{recordId}/void")
  public Map<String, Boolean> voidInspection(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID roleId,
      @PathVariable UUID recordId,
      @Valid @RequestBody VendorVoidInput input) {
    return voided(auth, a -> service.voidInspection(a, roleId, recordId, input));
  }

  private Map<String, Boolean> voided(String auth, java.util.function.Consumer<AccessService.Access> work) {
    call(
        auth,
        "spu.update",
        a -> {
          work.accept(a);
          return null;
        });
    return Map.of("voided", true);
  }
}
