import { LogisticsCostAnalysisWorkspace } from '@/components/logistics/logistics-cost-analysis-workspace';

/**
 * 10.6.1.1 成本分析。
 *
 * <p>蓝图：「分析物流成本：单位重量/单均运费、头程分摊后到岸成本占比、各渠道成本对比、
 * 超成本订单识别、头程费用分摊明细。输入：实际运费与头程费用；包裹与发运记录。
 * 输出：成本报表 + 分摊明细 + 异常订单清单。」并加粗
 * 「<b>物流成本常被低估</b>：真实成本 = 尾程 + 头程分摊 + 包材 + 关税 + 仓储费，
 * 报表必须全口径」。
 *
 * <p>这是一个真实工作区而不是占位页：成本报表（五个分量并排 + 逐币种合计与占比）、
 * 各渠道成本对比、超成本订单清单、头程分摊规则与分摊单明细，全部落到 PostgreSQL 16，
 * 加载态 / 空态 / 错误态三态齐全。
 *
 * <p>路由取 {@code /logistics/analytics}，与同 L3 的 10.6.2.1 时效分析（未交付）
 * 分得开 —— 成本看「花了多少」，时效看「到得及不及」。
 *
 * <p>数据来源如实标注：尾程来自 trade 的**现行**运费登记行（经授权 HTTP 端点读），
 * 头程来自 warehouse 的头程发运单（经授权 HTTP 端点读），关税来自 warehouse 的税费估算单；
 * 分摊单由本服务自持（12 财务服务在本仓库不存在）。包材走**参数**口径（租户维护），
 * 仓储费本仓库**没有来源**，页面上显式标「暂无数据 + 归属模块」而不是 0。
 */
export default function LogisticsAnalyticsPage() {
  return <LogisticsCostAnalysisWorkspace />;
}
