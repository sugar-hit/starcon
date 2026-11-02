'use client';

import { AiDiagnosisWorkspace } from '@/components/ai/ai-diagnosis-workspace';

/**
 * 18.4.2.3 异常诊断（卷 18 前端入口 `/ai/diagnosis`）。
 *
 * <p>蓝图：「<strong>AI 异常诊断</strong>：对库存异常、利润异常、发货延迟、同步失败、
 * 广告亏损等给出可能原因分析（自动关联相关数据）与处置建议。输入：异常事件 + 关联数据
 * （订单、库存、成本、历史记录）。输出：诊断结论 + 依据 + 建议动作。关系：上游 = 1 工作台待办、
 * 各域异常；下游 = 对应处理页。<strong>诊断要给依据</strong>（“因为 SKU X 的头程成本上涨 18%”），
 * 不能只给结论。」
 *
 * <p>🔴 本页与 <code>/ai/forecast</code>（18.4.2.2 选品与需求预测）<strong>不是一件事</strong>：
 * 那一页管的是「销量怎么预测、补多少货、价怎么调」，本页管的是「出了什么异常、
 * 原因是什么、依据是什么、该谁去处理」。
 *
 * <p>🔴 本页与 <code>/ai/usage</code> / <code>/ai/templates</code>（同卷兄弟）
 * <strong>路由互不为前缀</strong>：四个页面各自独立，谁也不会吃掉谁。
 *
 * <p>🔴 本仓当前<strong>没有任何真实的 AI / LLM 供应商适配器</strong>，所以「AI 大模型诊断」
 * 这一格在能力声明里如实显示为「未接通，归 18.4」。诊断本身用的是
 * <strong>确定性、可解释</strong>的归因（固定阈值 + 事实表比对），
 * 口径、阈值、来源、边界全部写在能力声明里，页面上照抄后端原文，不自己编。
 */
export default function AiDiagnosisPage() {
  return (
    <div className="space-y-4">
      <AiDiagnosisWorkspace />
    </div>
  );
}
