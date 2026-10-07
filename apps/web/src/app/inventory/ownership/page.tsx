import { StockOwnershipWorkspace } from '@/components/inventory/stock-ownership-workspace';

/**
 * 7.7.1.1 货主与所有权。
 *
 * <p>蓝图：「为库存 bucket 显式标注货主（stock_owner）与所有权类型（自有 / 供应商寄售 /
 * 平台所有 / 代销），使『货在谁的仓库』与『货是谁的』不再混为一谈。默认自有，但租户可把整仓
 * 或整 SKU 设为寄售。」
 *
 * <p>这是一个真实工作区而不是占位页：按货主拆分的库存、货权解析、货权漂移与改判、
 * 货权分档的换版与作废、货主档案的建档与停用都在这一页，落到 PostgreSQL 而不是内存。
 */
export default function StockOwnershipPage() {
  return <StockOwnershipWorkspace />;
}