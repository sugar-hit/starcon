'use client';

import { AutomationRuleEffectWorkspace } from '@/components/automations/automation-rule-effect-workspace';

/**
 * 18.3 业务规则引擎 → 18.3.2 执行 → 18.3.2.2 规则效果
 * （卷 18 前端入口 `/automations/effects`）。
 *
 * <p>蓝图：「<strong>统计规则效果</strong>：触发次数、成功率、平均耗时、节省工时预估、
 * 误触发反馈与规则优化建议。 输入：执行日志聚合。 输出：规则效果报表。
 * 关系：上游 = 18.3.2.1；下游 = 18.3.1.1（优化依据）。
 * <strong>误触发率高的规则要能快速定位并关闭</strong>，自动化失控比没有自动化更危险。」
 *
 * <p>🔴 本路由与 <code>/automations/rules</code>（18.3.1.1 规则列表）、
 * <code>/automations/rules/editor</code>（18.3.1.2 规则编辑器）、
 * <code>/automations/runs</code>（18.3.2.1 执行日志）<strong>互不为前缀</strong> ——
 * 四个页面管的是四件不同的事。
 *
 * <p>🔴 <strong>规则执行引擎（18.3.2）尚未交付</strong>，所以本页统计的是
 * <strong>执行台账里的记录</strong>，<strong>不是</strong>「规则自动触发了几次」——
 * 页面把它明确写在顶部的提示条里，而不是让人误读成自动触发次数。
 *
 * <p>🔴 本页<strong>不提供</strong>「关闭规则」按钮：关闭动作归 18.3.1.1 规则档案，
 * 这里只负责把误触发率高的规则<strong>快速定位</strong>出来。
 */
export default function AutomationEffectsPage() {
  return (
    <div className="space-y-4">
      <AutomationRuleEffectWorkspace />
    </div>
  );
}
