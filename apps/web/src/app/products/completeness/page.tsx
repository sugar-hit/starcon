'use client';

import { CompletenessScoreWorkspace } from '@/components/catalog/completeness-score-workspace';

/**
 * 完整度评分：3.9.2.1。
 *
 * <p>路由是 `/products/completeness`，挂在 `/products`（商品主数据）**之下**而不是
 * 挂在 `/partners` 下面：评分的主体是 SPU / SKU（商品），往来单位只是它的一个来源。
 *
 * <p>页面上的评分**一律现算**：改动属性、图片、报关或认证之后刷新即可看到分数变化；
 * 评估快照只回答「当时为什么被拦住」，不参与读出口。
 */
export default function CompletenessPage() {
  return <CompletenessScoreWorkspace writable />;
}
