import { SourceAlternativeWorkspace } from '@/components/supply/source-alternative';

/**
 * 6.1.2.2 同款货源推荐。
 *
 * <p>蓝图：「当某 SKU 缺货或质量不达标时，推荐可替代的同款/相似货源：同款不同供应商、
 * 历史采购过该货的供应商、价格对比。输入：SKU 编码与图片；历史采购记录；货源库匹配结果。
 * 输出：候选货源列表（供应商、价格、交期、历史质量评分）+ 一键生成采购申请。」
 *
 * <p>🔴 页面顶部那句话不是提示语，是这一页的设计前提：**同款判定要给置信度**，
 * 图片相似不等于同款，误判会导致采购到错误商品。
 *
 * <p>所以这一页：每条候选都带**置信度**与**判定依据**；疑似同款必须人工确认才能生成
 * 采购申请；价格永远是区间 + 采集时刻；评级客观分与最终分并排、人工调整单独标记。
 *
 * <p>路由：与 6.1.2.1（{@code /supply/sources}）、6.1.2.3（{@code /supply/qualifications}）、
 * 6.1.1.3（{@code /supply/ratings}）各占一个独立分区，互不覆盖。
 */
export default function SupplyAlternativesPage() {
  return <SourceAlternativeWorkspace />;
}
