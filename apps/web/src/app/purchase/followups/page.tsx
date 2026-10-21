import { PurchaseFollowupWorkspace } from '@/components/purchase/purchase-followup-workspace';

/**
 * 6.3.2.1 跟单与交期。
 *
 * <p>蓝图 06 卷：「记录与供应商的沟通跟进（催单、确认、异常反馈），维护承诺到货日变更历史，
 * 统计平均延迟天数。」输出「跟单时间线；交期变更历史；延迟统计。」
 *
 * <p>页面必须让三条硬要求看得见：
 *
 * <ul>
 *   <li><b>两个承诺口径并排</b>：单据承诺日（6.3.1.1 持有）与跟单承诺日（本模块的变更历史）。
 *       不一致时两个都显示 —— 只显示一个会让人以为采购单上写的日期也改了。</li>
 *   <li><b>「没到货」与「延迟 0 天」分开</b>：延迟天数必须有终点；
 *       没到货的单子另给「已逾期 N 天」，绝不显示 0 天。</li>
 *   <li><b>回写如实呈现</b>：成功 / 失败 / 跳过各有徽标与原因，
 *       「交期数值没有进参数」单独标出来 —— 不假装已经回写。</li>
 * </ul>
 *
 * <p>路由在 {@code /purchase/followups}（卷 06 采购与供应）；后端命名空间按服务走，
 * 是 {@code /trade/purchase-followups}（与 6.3.1.1 采购单列表、6.3.2.2 到货跟踪同一纪律）。
 */
export default function PurchaseFollowupsPage() {
  return <PurchaseFollowupWorkspace />;
}
