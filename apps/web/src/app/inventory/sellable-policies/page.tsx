import { SellablePolicyWorkspace } from '@/components/inventory/sellable-policy-workspace';

/**
 * 7.2.2.1 可售量口径。
 *
 * <p>蓝图：「配置不同渠道/店铺的库存口径：是否共享同一池、是否允许超卖多少、平台可售量是按
 * 自有仓还是含 FBA、平台可售数保留缓冲比例」，输出「生效的可售量口径；供 7.1.1.3 与 04 回写使用」。
 *
 * <p>这是一个真实工作区而不是占位页：共享池体检、新建/换版口径、挂仓配置、分档历史与按 SKU
 * 试算都在这一页，落到 PostgreSQL 而不是内存。
 */
export default function SellablePolicyPage() {
  return <SellablePolicyWorkspace />;
}
