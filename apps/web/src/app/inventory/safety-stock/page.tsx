import { SafetyStockWorkspace } from '@/components/inventory/safety-stock-workspace';

/**
 * 7.2.2.2 安全库存。
 *
 * <p>蓝图：「按 SKU/分类/仓库配置安全库存阈值与预警级别，低于阈值时触发预警并可关联自动生成补货建议。
 * 输入：阈值配置；当前可用量投影；日均销量。输出：安全库存预警 + 可选自动触发 6.2.1.2 补货建议。」
 *
 * <p>加粗的那条约束在界面上是实打实的：平台断货风险与自有仓耗尽用不同的徽标，
 * 且平台侧一律按上一级严重度处理并排在前面 —— 「FBA 断货造成的损失远大于自有仓」
 * 如果只写进注释，这个页面就分不出该先救哪个。
 *
 * <p>这是一个真实工作区而不是占位页：阈值分档、销量登记、评估生成预警、预警流转与补货建议
 * 全部落到 PostgreSQL。
 */
export default function SafetyStockPage() {
  return <SafetyStockWorkspace />;
}
