import { ConsignmentWorkspace } from '@/components/inventory/consignment-workspace';

/**
 * 7.7.1.2 寄售与代销。
 *
 * <p>蓝图：「管理寄售与代销业务：寄售协议（供应商货、我方销，结算按实际售出）、寄售可用量、
 * 销售触发结算数量、未售退货与期末清理。支持『寄售库存不计入我方采购入库成本，仅在售出时结转』。」
 * 输出「寄售库存视图（寄售量/已售/剩余）；结算触发单（→ 12 应付）；期末退回或转入采购的单据。」
 *
 * <p>这是一个真实工作区而不是占位页：协议生命周期、寄售可用量、销售事实、结算触发单、
 * 未售退货与期末清理全部落到 PostgreSQL 16，而「寄售未售出不产生应付」这条财务分叉由
 * 数据库不变量强制（剩余量守恒、应付额由已售 × 结算价生成、一条销售事实最多进一张结算单）。
 */
export default function ConsignmentPage() {
  return <ConsignmentWorkspace />;
}
