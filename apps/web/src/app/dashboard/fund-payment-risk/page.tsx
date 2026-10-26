import { FundPaymentRiskWorkspace } from '@/components/dashboard/fund-payment-risk-workspace';

/**
 * 1.1.3.2 资金与回款风险。
 *
 * <p>卷 01「工作台与门户」的**风险速览**里的一格：应收账龄、平台待结算金额、逾期应收、
 * 即将到期的供应商应付、在途资金占用。
 *
 * <p>路由放在 `/dashboard/fund-payment-risk`：本模块在台账里的 L3 锚点路由是「—」，
 * 意味着它没有既定的蓝图路由，但**必须是真实可访问的页面**而不是占位页 ——
 * 「—」锚点的模块做成真实工作区，入口挂在对应业务区。资金与回款风险对应的是财务业务区，
 * 所以挂在 `/dashboard/fund-payment-risk` 下，同时保留「这是工作台的一格」这层语义。
 *
 * <p>页面是**只读**的：确认 / 催收 / 调整 / 核销都回 12 财务完成。
 */
export default function FundPaymentRiskPage() {
  return <FundPaymentRiskWorkspace />;
}
