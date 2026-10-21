import { PendingInspectionWorkspace } from '@/components/warehouse/pending-inspection-workspace';

/**
 * 8.2.2.2 待检处理（待检区库存 → 质检 → 质量状态分流）。
 *
 * <p>蓝图 08 卷：「管理待检区库存：发起质检任务（6 质检单）、质检通过后转入可售、
 * 质检不通过转残次/报废/退货。」「输出：质量状态变更分录；可售量更新。」
 *
 * <p>页面必须让三条硬要求看得见：
 *
 * <ul>
 *   <li><b>待检品不可售</b>：待检清单与可售量是两块分开的数字 ——
 *       压在待检区的货不在可售基数里，质检通过转可售之后才进。</li>
 *   <li><b>质检结论只有一个来源</b>：三量从 6 质检单（trade 服务）回读，
 *       页面不提供输入框 —— 让操作员在这里再敲一遍数字，等于让同一个结论有两份。</li>
 *   <li><b>处置必须把这一批全部处置掉</b>：各去向之和与任务批量对不上时提交按钮禁用，
 *       并说清差多少；不合格品转可售在提交前就被拦下。</li>
 * </ul>
 */
export default function PendingInspectionsPage() {
  return <PendingInspectionWorkspace />;
}
