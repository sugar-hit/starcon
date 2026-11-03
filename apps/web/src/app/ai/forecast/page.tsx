'use client';

import { AiForecastWorkspace } from '@/components/ai/ai-forecast-workspace';

/**
 * 18.4.2.2 选品与需求预测（卷 18 前端入口 `/ai/forecast`）。
 *
 * <p>蓝图：「<strong>AI 预测能力</strong>：销量预测（含促销与大促影响）、补货量建议、
 * 价格弹性与调价建议、爆品识别与机会品发现。输入：历史销量、库存、在途、价格、促销日历；
 * 预测参数。输出：预测结果 + 置信区间 + 关键影响因素说明。关系：上游 = 13 分析、6 采购、
 * 7 库存；下游 = 6.2.1.2 补货建议、5.4 调价。<strong>预测必须给置信区间和影响因素</strong>，
 * 单点数字会让用户过度信任；建议只作为参考输入，不自动执行。」
 *
 * <p>🔴 本页与 <code>/text</code>（18.4.2.1 文本与翻译）<strong>不是一件事</strong>：
 * 那一页管的是「文案怎么翻、怎么检查、怎么确认后发布」，本页管的是「销量怎么预测、
 * 补多少货、价怎么调、谁是爆品」。
 *
 * <p>🔴 本页与 <code>/ai/usage</code> / <code>/ai/templates</code>（同卷兄弟）**路由互不为前缀**：
 * 三个页面各自独立，谁也不会吃掉谁。
 *
 * <p>🔴 本仓当前<strong>没有任何真实的 AI / LLM 供应商适配器</strong>，所以「AI 大模型预测」
 * 这一格在能力声明里如实显示为「未接通，归 18.4」。预测本身用的是
 * <strong>确定性、可解释</strong>的算法（移动平均 / 指数平滑 + 促销因子），
 * 口径、参数、阈值、来源全部写在能力声明里。
 */
export default function AiForecastPage() {
  return (
    <div className="space-y-4">
      <AiForecastWorkspace />
    </div>
  );
}
