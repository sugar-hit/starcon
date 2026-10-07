import { InventoryLedger } from '@/components/inventory/inventory-ledger';
import { parseLedgerHref } from '@/components/inventory/inventory-model';

/**
 * 7.1.2.1 库存流水台账。
 *
 * <p>这个路由是 7.1.1.2 明细行上「查看流水」入口的契约地址（`/inventory/ledger?skuId=&warehouseId=`），
 * 所以从明细点进来时按契约把定位维度接上：台账读的是 balance 口径，
 * href 里的 locationId / lotId 属 bucket 级维度、分录接不住，由 `parseLedgerHref` 丢弃。
 *
 * <p>页面只读：流水只追加不修改，本页没有任何写入口，也不提供"改一笔"的按钮。
 */
export default function InventoryLedgerPage({
  searchParams,
}: {
  searchParams: Record<string, string | string[] | undefined>;
}) {
  const href = Object.entries(searchParams ?? {})
    .map(([key, value]) => `${key}=${Array.isArray(value) ? (value[0] ?? '') : (value ?? '')}`)
    .join('&');
  // 没有带任何定位参数时返回 null（不带筛选），而不是给出一个全空的 drill
  const drill = href ? parseLedgerHref(`/inventory/ledger?${href}`) : null;
  return <InventoryLedger drill={drill} />;
}
