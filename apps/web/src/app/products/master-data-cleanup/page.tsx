'use client';

import { ZombieCleanupWorkspace } from '@/components/catalog/zombie-candidate-workspace';

/**
 * 僵尸主数据清理：3.9.2.2。
 *
 * <p>路由是 `/products/master-data-cleanup`，挂在 `/products/*`（商品主数据）**之下**
 * 而不是挂在 `/partners` 下：它同时治理 SKU、供应商与客户三类主数据，
 * 归属看的是「这是主数据治理」而不是「这是往来单位治理」——
 * 与 3.9 整卷（主数据质量治理）同源，供应商/客户只是它顺带覆盖的两类主体。
 *
 * <p>蓝图点名的下游是 3.1.1.1 SKU 列表：停用之后要能在列表里看到"已停用"，
 * 所以本页**只做识别与决定**，不改主数据本身的状态机。
 */
export default function MasterDataCleanupPage() {
  return <ZombieCleanupWorkspace writable />;
}
