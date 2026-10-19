package com.starcon.trade;

import static com.starcon.trade.ReturnQualityModels.*;

import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.DeleteMapping;
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
 * 11.2.3.1 质检与判定。
 *
 * <p>蓝图 11-aftersales-and-returns.md：「退回商品质检：外观、功能、配件完整性、是否可再销售；
 * 给出可再销售/残次/报废判定与残值估算。」
 * 输出：「质检结论 + 残值金额 + <b>质量状态指令</b>。」
 * 加粗句：「<b>可再销售判定要保守</b>：包装破损但商品完好可判可再销售，缺少配件则应判残次。」
 *
 * <p><b>命名空间独立于 {@code /trade/return-receipts}</b>：本模块的产物是<b>另一种单据</b>
 * （质检判定 = 一个结论 + 一张交给下游的指令），不是实收单的另一个视图。
 *
 * <p><b>权限码沿用</b> {@code trade.aftersales.read} / {@code trade.aftersales.write}：
 * 本模块是 11.2 锚点下的退货侧动作，与售后单同一业务面。
 *
 * <p><b>刻意不提供</b>：任何「转可售 / 残次 / 报废」的端点、残值入账端点、索赔端点 ——
 * 处置属 11.2.3.2、财务属 12 财务、索赔属 11.2.3.3，**均未交付**。
 * 质量状态指令只出不消费（{@code instruction_status} 恒 PENDING、消费方恒 11.2.3.2），
 * 而报废本身是不可逆的、需审批与原因。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} / {@link #write} 先向 identity 确认已认证成员关系，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 FORCE RLS 在数据库层强制隔离。
 * 本模块的端点大多是<b>按 id 直达</b>的 URL —— 拿别的租户的质检单 ID 来读，
 * 得到的是与「不存在」<b>完全相同</b>的错误（不告诉调用方「这个 ID 存在但不属于你」）。
 */
@RestController
@RequestMapping("/trade/return-quality")
public class ReturnQualityController {

  private static final String READ = "aftersales.read";
  private static final String WRITE = "aftersales.write";

  private final AccessService access;
  private final ReturnQualityService quality;

  public ReturnQualityController(AccessService access, ReturnQualityService quality) {
    this.access = access;
    this.quality = quality;
  }

  /** 统一读入口：先确认身份与权限（租户<b>不</b>来自客户端头），再在事务里绑定 RLS 租户上下文。 */
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
   * 能力声明：职责句的四个动词逐块报可用性，下游逐个报 delivered=false 与归属模块号、
   * 本模块<b>刻意不做</b>的事、判定三条路径与残值公式的口径。
   *
   * <p>路径刻意是 {@code capabilities}（租户级静态事实），不带 id 段。
   */
  @GetMapping("/capabilities")
  public ReturnQualityCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, actor -> quality.capabilities());
  }

  // ================================================================ 类目与 SKU 映射

  /** 类目列表（真分页 + 状态筛选 + 关键字）。 */
  @GetMapping("/categories")
  public QualityCategoryPage categories(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    return call(auth, actor -> quality.listCategories(status, keyword, page, size));
  }

  /** 建质检类目（租户自维护的作业口径）。 */
  @PostMapping("/categories")
  public QualityCategoryRow createCategory(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody QualityCategoryCreate request) {
    return write(auth, actor -> quality.createCategory(request, actor.userId()));
  }

  /** 改类目名称 / 启停。<b>刻意不给改编码的入口</b>：编码是报表口径的稳定锚点。 */
  @PutMapping("/categories/{id}")
  public QualityCategoryRow updateCategory(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody QualityCategoryUpdate request) {
    return write(auth, actor -> quality.updateCategory(id, request));
  }

  /** 把一个 SKU 归到质检类目（模板「按类目取用」的解析依据）。 */
  @PostMapping("/categories/{id}/skus")
  public void mapSku(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody QualitySkuMappingCreate request) {
    write(
        auth,
        actor -> {
          quality.mapSku(id, request, actor.userId());
          return null;
        });
  }

  // ================================================================ 质检模板

  /** 模板列表（真分页 + 状态 / 类目筛选 + 排序白名单 + 跨全表汇总）。 */
  @GetMapping("/templates")
  public QualityTemplatePage templates(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID categoryId,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String sort) {
    return call(
        auth,
        actor -> quality.listTemplates(categoryId, status, keyword, page, size, sort));
  }

  /** 模板详情：检项全量 + 每个维度失败后还能不能判可再销售。 */
  @GetMapping("/templates/{id}")
  public QualityTemplateDetail template(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, actor -> quality.templateDetail(id));
  }

  /** 建模板草稿（带检项）。<b>建模板不是启用模板</b> —— 草稿期间口径还可以改。 */
  @PostMapping("/templates")
  public QualityTemplateDetail createTemplate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody QualityTemplateCreate request) {
    return write(auth, actor -> quality.createTemplate(request, actor.userId()));
  }

  /** 改模板草稿（整单替换检项）。只有 DRAFT 可改，必须带 expectedVersion。 */
  @PutMapping("/templates/{id}")
  public QualityTemplateDetail updateTemplate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody QualityTemplateUpdate request) {
    return write(auth, actor -> quality.updateTemplate(id, request));
  }

  /** 启用模板：此后检项不可改，且可被质检单引用。 */
  @PostMapping("/templates/{id}/activate")
  public QualityTemplateDetail activateTemplate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return write(auth, actor -> quality.activateTemplate(id, actor.userId()));
  }

  /**
   * 停用模板（不再被新质检单选中；历史质检单仍指向它）。
   *
   * <p>刻意用 POST 而不是 DELETE：停用是<b>口径变更</b>，历史质检单要能复现当时的口径 ——
   * 真删掉模板会让「这张质检单当初按什么口径判的」永远答不出来。
   */
  @PostMapping("/templates/{id}/retire")
  public QualityTemplateDetail retireTemplate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return write(auth, actor -> quality.retireTemplate(id));
  }

  // ================================================================ 待检队列与判定预览

  /**
   * 待检队列：已入库分录里<b>还没有</b>在途质检单的那些。
   *
   * <p>这是质检员上班第一屏 —— 不是「一共入了多少」，而是「现在有多少件货等着我判」。
   */
  @GetMapping("/queue")
  public QualityPendingPage queue(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String skuCode,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    return call(auth, actor -> quality.pendingQueue(skuCode, page, size));
  }

  /**
   * 判定预览：提交之前就告诉质检员「能判成什么、不能判成什么、残值大概多少」。
   *
   * <p>⚠️ 残值是<b>试算</b>，最终值以判定那一刻 DB 触发器按同一个式子重算为准。
   */
  @GetMapping("/verdict-preview")
  public QualityVerdictPreview verdictPreview(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam UUID postingId) {
    return call(auth, actor -> quality.verdictPreview(postingId));
  }

  // ================================================================ 质检单

  /** 质检单列表（真分页 + 多维筛选 + 排序白名单 + 跨全表汇总）。 */
  @GetMapping("/inspections")
  public QualityInspectionPage inspections(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String verdict,
      @RequestParam(required = false) UUID categoryId,
      @RequestParam(required = false) UUID templateId,
      @RequestParam(required = false) UUID receiptId,
      @RequestParam(required = false) String skuCode,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String sort) {
    return call(
        auth,
        actor ->
            quality.listInspections(
                new QualityInspectionListQuery(
                    page, size, status, verdict, categoryId, templateId, receiptId, skuCode,
                    keyword, sort)));
  }

  /** 单张质检单全景（头 + 逐项结果 + 质量状态指令 + 留痕 + 当前可执行动作）。 */
  @GetMapping("/inspections/{id}")
  public QualityInspectionDetail inspection(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, actor -> quality.inspectionDetail(id));
  }

  /**
   * 开一张质检单（对一条待检入库分录）。
   *
   * <p><b>不接受</b>数量、SKU、币种与残值：这些都由 DB 触发器从待检分录与订单行取，
   * 传进来也传不进（V179 守卫④）。
   */
  @PostMapping("/inspections")
  public QualityInspectionDetail createInspection(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody QualityInspectionCreate request) {
    return write(auth, actor -> quality.createInspection(request, actor.userId()));
  }

  /** 录逐项检查结果（整单替换）。只有 DRAFT 可录，必须带 expectedVersion。 */
  @PutMapping("/inspections/{id}")
  public QualityInspectionDetail recordResults(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody QualityInspectionUpdate request) {
    return write(auth, actor -> quality.recordResults(id, request, actor.userId()));
  }

  /**
   * 判定：给结论 + 理由 → 服务层算残值 → <b>DB 触发器重算比对并生成质量状态指令</b>。
   *
   * <p>⚠️ <b>请求体里没有残值字段</b>：残值是财务数据，由「原值 × 生效系数」算出来。
   * 判定必须给出理由，且结论要在三条路径允许的集合内。
   */
  @PostMapping("/inspections/{id}/conclude")
  public QualityInspectionDetail conclude(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody QualityInspectionConclude request) {
    return write(auth, actor -> quality.conclude(id, request, actor.userId()));
  }

  /** 撤销（录错了 / 判错了还没生效）。只有 DRAFT 可撤销。 */
  @PostMapping("/inspections/{id}/cancel")
  public QualityInspectionDetail cancel(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody QualityInspectionCancel request) {
    return write(auth, actor -> quality.cancel(id, request, actor.userId()));
  }
}
