package com.starcon.trade;

import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 6.2.2.1 采购申请读 catalog 的**供应商主数据**的端口。
 *
 * <p>蓝图本模块的输入句写死「<b>供应商选择</b>」，而供应商主数据在 <b>catalog（party）</b> 的库里
 * （卷 06 的主责是 trade + catalog（party），party 归 catalog）。AGENTS.md 模块边界纪律禁止
 * 跨服务 SQL / FK / 共享视图，所以本端口<b>只经 catalog 的授权只读端点</b>读，
 * 不直连对方库、不建跨库视图、不建跨库外键。
 *
 * <p><b>本端口不重写 HTTP 调用</b>：底层复用 6.1.2.3 供应商资质已经交付的
 * {@link SupplierPartyQueryPort}（同一个 catalog 端点 {@code GET /catalog/supply/vendors}，
 * 同一份 6.1.1.x 的 {@code VendorRow} 契约形状）。本类只补一件事：
 * <b>把「供应商没接线」这件事如实报出来</b>。
 *
 * <p><b>🔴 绝不降级成假供应商清单</b>：catalog 读不到时本端口**显式失败**（503），
 * 页面据此显示「供应商清单暂时读不到」，而不是显示一个空下拉框 ——
 * 空下拉框会被当成「这个租户一个供应商都没有」，那是**假事实**。
 *
 * <p>租户<b>不</b>由本端口传递租户号：转发的是<b>调用方自己的</b> {@code Authorization} bearer，
 * catalog 侧仍按已认证成员关系解析租户并强制 RLS。隔离因此是<b>两道</b>
 * （trade 侧 RLS + catalog 侧 RLS），而不是「本服务替对方决定租户」。
 */
@Service
public class PurchaseRequestSupplierPort {

  private final SupplierPartyQueryPort directory;
  private final boolean wired;

  public PurchaseRequestSupplierPort(
      SupplierPartyQueryPort directory, @Value("${CATALOG_URL:}") String url) {
    this.directory = directory;
    this.wired = url != null && !url.isBlank();
  }

  /** 供应商主数据能不能读（catalog 接线了没有）。 */
  public boolean available() {
    return wired;
  }

  /** 没接线时的说明（页面与能力声明都用它，如实说清「为什么是空的」）。 */
  public String unavailableNote() {
    return "供应商主数据**未接线**（CATALOG_URL 未配置）：供应商是 catalog（party）的授权只读接口"
        + "（GET /catalog/supply/vendors），跨库视图与跨服务 SQL 均被 AGENTS.md 禁止，"
        + "所以本服务不会凭空造一份供应商清单出来 —— 请配置 CATALOG_URL 后重试。";
  }

  /** 供应商候选（建申请单时选供应商用）。 */
  public List<SupplierPartyQueryPort.SupplierView> suppliers(String keyword, String bearer) {
    if (!wired) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, unavailableNote());
    }
    return directory.suppliers(keyword, bearer);
  }

  /**
   * 取一个供应商（catalog 的 SUPPLIER 角色 id）；不存在就 404。
   *
   * <p>🔴 转单前确认供应商时会用到它：<b>供应商必须是真的</b>，
   * 不能凭调用方传的一个 id 就写进转单事实。
   */
  public SupplierPartyQueryPort.SupplierView require(UUID roleId, String bearer) {
    if (!wired) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, unavailableNote());
    }
    return directory.require(roleId, bearer);
  }
}
