import { InventoryTransferWorkspace } from '@/components/inventory/inventory-transfer';

/**
 * 7.5.1.1 调拨单。
 *
 * <p>蓝图：「发起仓库间调拨：选择源仓与目标仓、调拨 SKU 与数量、预计发出与到达时间，
 * 跟踪发出、在途、目标实收」，输出「调拨单（草稿/待发/在途/部分收/已收/差异）；
 * 库存分录（发出转入途、实收转入目标仓）」。
 *
 * <p>页面必须让「调拨不是瞬时」看得见：发出只减源仓、货挂在途列，
 * 目的仓要等收货才增加。未发出量与差异量分列显示 —— 前者是还没走，后者是路上少了。
 */
export default function InventoryTransfersPage() {
  return <InventoryTransferWorkspace />;
}
