'use client';

import { SupplierQuoteLedger } from '@/components/catalog/supplier-quote-workspace';

/**
 * 供应商报价：3.6.2.1。
 *
 * <p>路由是 `/partners/offers`，挂在 `/partners`（3.6.1.1 往来单位）**之下**
 * 而不是挂在 `/products/*` 下面：报价的主体是**供应商**（往来单位），
 * 商品只是它报价的那个 SKU —— 与 3.6.3.1 地址与联系人同一路由归属。
 *
 * <p>台账里记的锚点路由就是 `/partners/offers`（蓝图 3.6 的树形图：
 * `3.6.2.1 供应商报价 → /partners/offers`）。
 */
export default function SupplierOffersPage() {
  return <SupplierQuoteLedger writable />;
}
