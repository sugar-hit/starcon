'use client';

import { AutomationRuleEditorWorkspace } from '@/components/automations/automation-rule-editor-workspace';

/**
 * 18.3 业务规则引擎 → 18.3.1 规则管理 → 18.3.1.2 规则编辑器
 * （卷 18 前端入口 `/automations/rules/editor`）。
 *
 * <p>蓝图：「<strong>可视化编辑规则</strong>：选择触发事件 → 配置条件（字段/操作符/值）→
 * 编排动作（调接口/改状态/建任务/发通知/生成单据）→ 测试与保存。
 * 输入：规则配置；测试样本数据。输出：规则定义；测试运行结果。
 * 关系：上游 = 18.3.1.1；下游 = 18.3.2 执行。
 * <strong>规则必须支持用样本测试</strong>，否则只能在生产上试错。」
 *
 * <p>🔴 本页与 <code>/automations/rules</code>（18.3.1.1 规则列表）
 * <strong>不是一件事</strong>：那边管的是「<strong>规则档案</strong>」
 * —— 名称、优先级、启用范围、执行统计；本页管的是「<strong>怎么把一条规则编出来</strong>」
 * —— 触发事件、条件、动作、样本测试。
 * 两者是<strong>同一条规则的前后两步</strong>，保存时由本页委托 18.3.1.1 登记。
 *
 * <p>🔴 <strong>规则执行引擎（18.3.2）尚未交付</strong>，所以本页的「样本测试」
 * <strong>只求值条件并给出动作计划</strong>，<strong>不执行任何动作</strong> ——
 * 页面上没有「执行规则」的按钮（一个点了必然失败的按钮会让运营以为「点一下就能跑」）。
 *
 * <p>🔴 本页<strong>不提供</strong>把「未交付」显示成「能用」的入口：
 * 触发事件的字段目录取不到时，页面写明「未校验」而不是自己猜一套字段；
 * 接口目录取不到时，「调接口」的目标标成「未校验」而不是随便放一个目标。
 */
export default function AutomationRuleEditorPage() {
  return (
    <div className="space-y-4">
      <AutomationRuleEditorWorkspace />
    </div>
  );
}
