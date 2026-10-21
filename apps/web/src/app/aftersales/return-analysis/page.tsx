import { ReturnAnalysisWorkspace } from '@/components/aftersales/return-analysis-workspace';

/**
 * 11.5.1.1 退货分析。
 *
 * <p>蓝图 11-aftersales-and-returns.md：「统计退货情况：退货率（按店铺/SKU/类目/原因/批次）、
 * 退货金额趋势、拒退率、处理时效、平均退款金额与原因分布。」
 * 输出：「<b>退货分析报表 + 维度下钻 + 异常识别</b>。」
 * 加粗句：「<b>退货率必须与销售额同口径比较</b>，只看绝对数会误判（退货率从 3% 升到 5% 严重，
 * 但绝对数可能没变）。」
 *
 * <p>这是一个**真实工作区**：按币种的退货率 + 拒退率 + 处理时效 + 五个维度的下钻 +
 * 退货金额趋势 + 异常识别 + 原因分布（两个轴）+ 报表快照 + 口径与阈值，
 * 数据全部来自 {@code /trade/return-analysis} 下的真实端点，
 * 加载 / 空 / 错误三态齐全，**没有占位数据**。
 *
 * <p>路由取 {@code /aftersales/return-analysis} —— 与 11.5.2.1 质量分析
 * （{@code /aftersales/quality-analysis}）**并列**而非嵌套：
 * 11.2.1.1 退货授权单（{@code /aftersales/returns}）管的是**逐单处置**，
 * 本模块管的是**比率与聚合**。授权单是工作面，分析报表是看板，
 * 嵌套进去只会让「一张待批的退货单」和「全店退货率」互相污染。
 *
 * <p>页面<b>刻意没有</b>「退货率预测」「最优解」与「写入供应商评级」三个入口：
 * 前两者本仓没有建模与预测设施，后者属 6.1.1.3（<b>未交付</b>），
 * 能力声明会如实把它们报成 {@code available=false} 并写明归属模块。
 */
export default function AftersalesReturnAnalysisPage() {
  return <ReturnAnalysisWorkspace />;
}
