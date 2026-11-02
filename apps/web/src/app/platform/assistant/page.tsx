'use client';

import { AssistantWorkspace } from '@/components/platform/assistant-workspace';

/**
 * 18.4.1.1 对话助手（卷 18 前端入口 `/platform/assistant`）。
 *
 * <p>蓝图：「<strong>职责</strong>：自然语言助手：用中文提问业务问题（"上周哪个店利润最差"
 * "哪些 SKU 该补货了"），返回答案并附数据来源链接；支持追问与上下文延续。
 * <strong>输入</strong>：用户问题；当前上下文（租户、数据范围、当前页面）。
 * <strong>输出</strong>：答案 + 数据明细链接 + 建议的下一步操作。
 * <strong>关系</strong>：上游 = 各域只读查询接口；下游 = 无（只读）。
 * <strong>答案必须附明细链接</strong>——不给依据的 AI 数字不能用于决策。」
 *
 * <p>🔴 本页与 <code>/ai/forecast</code>（18.4.2.2 选品与需求预测）、
 * <code>/ai/diagnosis</code>（18.4.2.3 异常诊断）<strong>不是一件事</strong>：
 * 那两页是「把一个已经确定的问题算出来」（预测需求、归因异常），
 * 本页是「用一句话问业务问题，并给出带依据的答案」。本页只读，不改任何业务数据。
 */
export default function AssistantPage() {
  return (
    <div className="space-y-4">
      <AssistantWorkspace />
    </div>
  );
}
