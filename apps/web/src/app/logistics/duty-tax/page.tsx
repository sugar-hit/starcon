import { DutyTaxWorkspace } from '@/components/logistics/duty-tax';

/**
 * 10.5.1.3 关税与税费。
 *
 * <p>蓝图：「计算与管理进口税费：关税（按 HS 编码税率）、增值税/GST、关税承担方（DDP/DDU）、
 * 到岸成本构成，供成本核算与报价参考」；L3 锚点是 10.5.1「报关与清关」。
 *
 * <p>与 10.5.1.1 报关单同一 L3 但**不同页**（`/logistics/duty-tax`）：本页的输入是报关单，
 * 输出是到岸成本与税费差异 —— 报关单那一页管的是「申报资料准不准」，
 * 这一页管的是「该交多少税、实际交了多少、差在哪」。两条口径的读者不同，
 * 混在一个页面里会让报关操作员看到一堆与申报无关的税率列。
 */
export default function DutyTaxPage() {
  return <DutyTaxWorkspace />;
}
