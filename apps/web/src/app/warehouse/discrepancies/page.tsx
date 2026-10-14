import { GoodsReceiptDiscrepancyWorkspace } from '@/components/warehouse/goods-receipt-discrepancy';

/**
 * 8.2.1.2 收货差异（**处理**）。
 *
 * <p>蓝图：「处理收货差异：短少、破损、型号不符、过期、错发；支持拍照留证、拒收、部分接收、
 * 索赔发起」；输出「差异处理记录 + 索赔单（6 采购）+ 库存拒收不入账记录」；
 * 下游 = 6.4.2 不良品处理、6.5.1 对账索赔。
 *
 * <p>页面必须让蓝图那条硬规则看得见：**破损与短少必须拒收或待检入库**，
 * 直接入可售会把问题货卖出去。所以「正常入库」对破损 / 短收 / 型号不符 / 过期
 * 根本不渲染，风险类型也不提供「可售」去向选项 —— 让操作员去点一个注定被拒绝的按钮，
 * 等于告诉他系统坏了，而真相是这条路径本就不该存在。
 */
export default function GoodsReceiptDiscrepanciesPage() {
  return <GoodsReceiptDiscrepancyWorkspace />;
}
