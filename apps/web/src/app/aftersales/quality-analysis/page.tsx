import { QualityAnalysisWorkspace } from '@/components/aftersales/quality-analysis-workspace';

/**
 * 11.5.2.1 质量分析。
 *
 * <p>蓝图 11-aftersales-and-returns.md：「质量问题<b>专项分析</b>：缺陷类型分布、批次质量趋势、
 * 供应商质量对比、缺陷照片聚类、<b>支持开箱率与质量成本估算</b>。」
 * 输出：「<b>质量报表 + 责任分布 + 改进清单</b>。」
 * 加粗句：「<b>质量成本（退货物流 + 损耗 + 差评影响）应量化</b>，否则改进动力不足。」
 *
 * <p>这是一个**真实工作区**：总览 + 质量成本（逐项标依据）+ 开箱率（带覆盖率）+
 * 缺陷类型分布 + 批次质量趋势 + 供应商质量对比 + 缺陷照片聚类 + 责任分布与改进清单 +
 * 缺陷观察，加载 / 空 / 错误三态齐全，**没有占位数据**。
 *
 * <p>路由取 {@code /aftersales/quality-analysis} —— 与 11.1.2.2 归因
 * （{@code /aftersales/attributions}）和 11.5.1.1 退货分析
 * （{@code /aftersales/returns}）<b>并列</b>而非嵌套：
 * 归因判「这事该谁负责」（单据），退货分析管「退了多少」（比率），
 * 本模块答「退回来的东西坏在哪、钱花了多少、接下来先动哪几个批次」（聚合报表）。
 * 三者形状不同，嵌套进去只会让「单据列表」和「分析报表」互相污染。
 *
 * <p>页面<b>刻意没有</b>「发起供应商索赔」「写入供应商评级」两个入口：
 * 二者属 6.4.2.2 / 6.1.1.3，<b>均未交付</b>；
 * 能力声明会如实把它们报成 {@code available=false} 并写明归属模块。
 */
export default function AftersalesQualityAnalysisPage() {
  return <QualityAnalysisWorkspace />;
}
