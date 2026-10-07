import { GoodsReceiptWorkspace } from '@/components/warehouse/goods-receipt';

/**
 * 8.2.1.1 收货单。
 *
 * <p>蓝图：「执行到货收货：选择来源（采购/退货/调拨/平台仓回货），录入实收数量与差异，
 * 上传收货凭证，生成入库分录」；输出「收货单（草稿/已收/部分收/已关闭）+
 * 库存入库分录（转入待检或可售 bucket）」；「预期与实收分开记录」，
 * 超收/少收/拒收都要留痕并回写采购单。
 *
 * <p>页面必须让「预期与实收分开记录」看得见：预期 / 实收 / 拒收 / 还差四个独立列，
 * 差异单独一列 —— 把它们揉成「已处理」就答不出「到底还差多少没到」，
 * 而把还差当成差异会把「货还在路上」误报成货损。
 */
export default function GoodsReceiptsPage() {
  return <GoodsReceiptWorkspace />;
}