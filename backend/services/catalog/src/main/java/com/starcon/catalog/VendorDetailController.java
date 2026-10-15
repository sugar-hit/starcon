package com.starcon.catalog;

import static com.starcon.catalog.VendorDetailModels.*;

import jakarta.validation.Valid;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

/**
 * 6.1.1.2 供应商详情：<b>单供应商全景</b>与本模块自己的写入动作。
 *
 * <p>路由沿用 {@code /catalog/supply/vendors/**}（与 6.1.1.1 同前缀），但<b>刻意不与它的
 * {@code GET /{roleId}} 重叠</b>：那个端点是 6.1.1.1 的<b>列表下钻</b>（采购侧状态 / 最近事实），
 * 本模块的 {@code GET /{roleId}/panorama} 是<b>全景</b>（账期与开票 / 报价 / 采购历史 / 交期质量 /
 * 联系人 / 附件资质 + 操作入口）。两者是同一页上的两个不同区块，各有各的路径，
 * 而不是同一个路径按参数切换形状 —— 后者会让「这个响应到底是哪个模块的」永远说不清。
 *
 * <p><b>权限</b>：读 {@code catalog.spu.read}，写 {@code catalog.spu.update}，与 6.1.1.1 / 3.6.x 完全一致。
 * 账期与开票信息是付款审批的输入，它的改动属于「采购侧档案变更」，不新造权限词表
 * （那要改 identity 的迁移，是另一个服务的写入所有权）。
 *
 * <p><b>租户</b>：一律来自已认证成员关系（{@link AccessService} 向 identity 解析），<b>不读 {@code X-Tenant-Id} 头</b>。
 */
@RestController
@RequestMapping("/catalog/supply/vendors")
public class VendorDetailController {
  private final AccessService access;
  private final VendorDetailService service;

  public VendorDetailController(AccessService access, VendorDetailService service) {
    this.access = access;
    this.service = service;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "catalog." + permission);
    return access.run(actor, "catalog." + permission, () -> work.apply(actor));
  }

  /**
   * 词表选项：结算方式 / 发票类型 / 资质类型 / 报价状态。
   *
   * <p>前端下拉与后端入参校验<b>同源</b>（{@link VendorDetailRules} 的静态词表）：
   * 各维护一份的话，界面上会出现一个后端拒收的选项。
   */
  @GetMapping("/detail-options")
  public Map<String, List<VendorListRules.Option>> detailOptions(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "spu.read", a -> service.options());
  }

  /**
   * 供应商全景：基础信息 + 账期与开票 + 报价摘要 + 采购历史摘要 + 交期与质量表现
   * + 联系人 + 附件资质摘要 + 操作入口 + 治理警告。
   *
   * <p>{@code metricFrom} / {@code metricTo} 是<b>交期与质量表现的统计窗口</b>（与 6.1.1.1 同一套语义），
   * 缺省最近 365 天，实际生效的窗口回显在响应的 {@code metricWindow} 上。
   */
  @GetMapping("/{roleId}/panorama")
  public VendorPanorama panorama(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID roleId,
      @RequestParam(required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime metricFrom,
      @RequestParam(required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime metricTo) {
    return call(auth, "spu.read", a -> service.panorama(roleId, metricFrom, metricTo));
  }

  /**
   * 采购历史与金额：窗口内到货记录<b>真分页</b> + 按单位/币种分桶 + 按月金额序列。
   *
   * <p>金额按币种分组，<b>绝不相加</b>（本库没有汇率表）。
   */
  @GetMapping("/{roleId}/purchase-history")
  public VendorPurchaseHistoryPage purchaseHistory(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID roleId,
      @RequestParam(required = false) String currency,
      @RequestParam(required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime metricFrom,
      @RequestParam(required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime metricTo,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(
        auth, "spu.read", a -> service.purchaseHistory(roleId, currency, page, size, metricFrom, metricTo));
  }

  /**
   * 供货 SKU 与报价：分页 + 状态 / 币种筛选 + 「只看有效」。
   *
   * <p><b>只读</b>：supplier_quote 的写入所有权在 3.6.2.1，本模块不提供第二套编辑能力。
   */
  @GetMapping("/{roleId}/quotes")
  public VendorQuotePage quotes(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID roleId,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String currency,
      @RequestParam(required = false) Boolean effectiveOnly,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate at,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(
        auth, "spu.read", a -> service.quotes(roleId, status, currency, effectiveOnly, at, page, size));
  }

  /** 读账期与开票信息；未建档时返回 404（响应体带上「12 财务无法自动生成付款申请」这句）。 */
  @GetMapping("/{roleId}/settlement-terms")
  public VendorTermsView settlementTerms(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID roleId) {
    return call(
        auth,
        "spu.read",
        a -> {
          var terms = service.termsView(roleId);
          if (terms == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND,
                "还没建这个供应商的账期与开票信息：12 财务无法自动生成付款申请（到期日、发票类型、税号都取不到）。"
                    + "请先在供应商详情里补建。");
          }
          return terms;
        });
  }

  /**
   * 新建 / 修改账期与开票信息。
   *
   * <p>蓝图加粗句「<b>账期与开票信息是付款审批的输入</b>」的落点就是这个端点 ——
   * 它是真实可维护的数据，不是从别处只读一个字段。
   *
   * <p>{@code version} 是行级乐观锁（新建时传 0）。
   */
  @PutMapping("/{roleId}/settlement-terms")
  public VendorTermsView saveSettlementTerms(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID roleId,
      @Valid @RequestBody VendorTermsInput input) {
    return call(auth, "spu.update", a -> service.saveTerms(a, roleId, input));
  }

  /** 附件资质清单，按「快过期的排前面」排。 */
  @GetMapping("/{roleId}/qualifications")
  public VendorQualificationList qualifications(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID roleId) {
    return call(auth, "spu.read", a -> service.qualifications(roleId));
  }

  /** 登记一份附件资质。 */
  @PostMapping("/{roleId}/qualifications")
  public VendorQualificationSaved createQualification(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID roleId,
      @Valid @RequestBody VendorQualificationInput input) {
    return call(auth, "spu.update", a -> service.saveQualification(a, roleId, null, input, true));
  }

  /**
   * 改一份附件资质（换证换文件）。
   *
   * <p>资质是<b>行政记录</b>而不是只追加事实表，所以可以改可以删；
   * 与账期与开票信息不同 —— 后者是付款依据，只有一份，改了留 version 与审计。
   */
  @PutMapping("/{roleId}/qualifications/{attachmentId}")
  public VendorQualificationSaved updateQualification(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID roleId,
      @PathVariable UUID attachmentId,
      @Valid @RequestBody VendorQualificationInput input) {
    return call(auth, "spu.update", a -> service.saveQualification(a, roleId, attachmentId, input, false));
  }

  /** 删一份附件资质。 */
  @DeleteMapping("/{roleId}/qualifications/{attachmentId}")
  public Map<String, Boolean> deleteQualification(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID roleId,
      @PathVariable UUID attachmentId) {
    call(auth, "spu.update", a -> {
      service.deleteQualification(a, roleId, attachmentId);
      return null;
    });
    return Map.of("deleted", true);
  }
}
