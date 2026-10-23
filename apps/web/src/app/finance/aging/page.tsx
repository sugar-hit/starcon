import { ReceivableAgingWorkspace } from '@/components/finance/receivable-aging-workspace';

/**
 * 12.1.1.2 应收账龄。
 *
 * <p>蓝图 12-finance.md：「按账龄区间（未到期/1-30/31-60/61-90/90+）统计应收分布，支持按店铺、客户、币种维度查看，识别逾期集中风险。」输入：「应收单集合；账龄区间配置。」输出：「账龄分布报表 + 逾期清单。」
 * 加粗句：「<b>跨境应收的最大风险是平台争议冻结</b>，账龄报表需单独标识"争议中"金额。」
 *
 * <p>这是一个**真实工作区**：账龄区间配置 + 维度下钻的账龄分布 + 逾期清单，
 * 加载 / 空 / 错误三态齐全，**没有占位数据**。
 *
 * <p>路由取 {@code /finance/aging} —— 与 12.1.1.1 的 {@code /finance}（应收单管理）**并列**而非嵌套：
 * 那是另一个模块的页面（另一个命名空间、另一套端点）。
 */
export default function ReceivableAgingPage() {
  return <ReceivableAgingWorkspace />;
}
