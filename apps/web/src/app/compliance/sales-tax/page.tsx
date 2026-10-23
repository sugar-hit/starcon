'use client';

import { SalesTaxWorkspace } from '@/components/sales-tax/sales-tax-workspace';

/**
 * 销售税与 Marketplace Facilitator：L3「16.3.2 特殊制度」下的 16.3.2.3。
 *
 * <p>蓝图：「处理美国各州销售税与 Marketplace Facilitator 制度：应缴/代缴状态、州级阈值、免征额、
 * 按平台的代扣代缴说明与对账。输入：店铺所在州数据；平台代扣代缴报表；自营应税销售额。
 * 输出：各州税务状态 + 代扣代缴对账结果。关系：上游 = 2 店铺、12.3.1.2；下游 = 12.6 申报。
 * <strong>平台代扣代缴不等于免税</strong>，卖家的申报义务依然存在，只是由平台代收代缴，需要正确对账。」
 *
 * <p>🔴 本页是<strong>16.3.2 特殊制度</strong>（前端入口 <code>/compliance/sales-tax</code>），
 * 与 <code>/compliance/epr</code>（16.3.2.2 EPR/包装法）、
 * <code>/compliance/tax</code>（16.3.1.1 税号与辖区）<strong>是三个不同的页面</strong> ——
 * 路径不同、互不影响。
 *
 * <p>🔴 <strong>「平台代扣代缴」不等于免税</strong>：本页把「应缴」与「已由平台代缴」分成两列，
 * 差异单独给出 —— 压成一个净额就看不出该补缴还是该退款。
 *
 * <p>🔴 <strong>未交付的下游不编造、不跳转</strong>：12.6 申报当前<strong>未交付</strong>，
 * 本页只给出「各州应缴 / 平台已代缴 / 差异」的结论与申报义务视图，能力声明里逐条写清了缺什么、归谁。
 */
export default function SalesTaxPage({
  searchParams,
}: {
  searchParams: Record<string, string | string[] | undefined>;
}) {
  const one = (value: string | string[] | undefined) =>
    Array.isArray(value) ? (value[0] ?? '') : (value ?? '');
  // 从别处跳进来时带上 ?stateCode=，直接定位到那个州的判定。
  const focus = { stateCode: one(searchParams?.stateCode) };

  return (
    <div className="space-y-4">
      <SalesTaxWorkspace focus={focus} />
    </div>
  );
}
