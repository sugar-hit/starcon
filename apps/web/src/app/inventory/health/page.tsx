import { InventoryHealthWorkspace } from '@/components/inventory/inventory-health';

/**
 * 7.6.2.1 库存健康。
 *
 * <p>蓝图：「库存健康度诊断：周转率、缺货率、超储率、呆滞占比、库存准确率、负库存发生次数，
 * 按仓库与类目打分并给出改进建议」「输出：健康度评分卡 + 趋势 + 问题清单与建议」
 * 「**库存准确率是硬指标**，低于阈值应阻断结算与对账——账不准时报表全是错的」。
 *
 * <p>页面必须让「哪些指标根本没测」看得见：六个指标里当前只有库存准确率与负库存发生次数
 * 真实可算，其余四个依赖尚未交付的 13 销量与 7.6.1。界面上把这四行藏掉，剩下的两行就会
 * 看起来像"全部指标都没问题"。
 */
export default function InventoryHealthPage() {
  return <InventoryHealthWorkspace />;
}
