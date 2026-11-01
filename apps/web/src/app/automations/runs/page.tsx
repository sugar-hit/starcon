'use client';

import { AutomationRuleRunWorkspace } from '@/components/automations/automation-run-workspace';

/**
 * 18.3 业务规则引擎 → 18.3.2 执行 → 18.3.2.1 执行日志
 * （卷 18 前端入口 `/automations/runs`）。
 *
 * <p>蓝图：「<strong>记录每次规则执行</strong>：触发事件、条件命中情况、执行的动作、
 * 每步结果、耗时、错误；支持查看上下文与手动重跑。
 * 输入：执行记录；重跑请求。 输出：执行明细 + 失败原因定位。
 * 关系：上游 = 18.3.1.2；下游 = 1 工作台告警。
 * <strong>规则必须幂等</strong>：同一事件重复触发不能重复执行副作用（如重复改价、重复建单）。」
 *
 * <p>🔴 本路由与 <code>/automations/rules</code>（18.3.1.1 规则列表）、
 * <code>/automations/rules/editor</code>（18.3.1.2 规则编辑器）**互不为前缀** ——
 * 三个页面管的是三件不同的事。
 *
 * <p>🔴 <strong>规则执行引擎（18.3.2）尚未交付</strong>，所以本页的「重跑」
 * <strong>只登记一条待执行记录</strong>（结论 = 未执行），
 * <strong>不会</strong>真的改价、建单或发通知 —— 页面把它明确写成
 * 「未执行 · 执行器未接通」，而不是「已重跑成功」。
 */
export default function AutomationRunsPage() {
  return (
    <div className="space-y-4">
      <AutomationRuleRunWorkspace />
    </div>
  );
}
