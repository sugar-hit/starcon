'use client';

import { AutomationRuleWorkspace } from '@/components/automations/automation-rule-workspace';

/**
 * 18.3 业务规则引擎 → 18.3.1 规则管理 → 18.3.1.1 规则列表
 * （卷 18 前端入口 `/automations/rules`）。
 *
 * <p>蓝图：「<strong>规则列表</strong>：管理自动化规则：规则名称、触发事件、条件（过滤）、
 * 动作（多步）、优先级、启用范围（租户/店铺/仓库）、试运行开关、执行统计。
 * 输入：规则增删改；启用停用；排序。输出：规则档案 + 执行统计。
 * 关系：上游 = 各业务事件；下游 = 18.3.1.2 编辑器、18.3.2.1 执行日志。
 * <strong>规则要能看出最后一次执行结果</strong>，否则坏规则静默失败无人察觉。」
 *
 * <p>🔴 本页与 <code>/integrations/connectors/external-integrations</code>（18.2.3.1 其他对接）
 * <strong>不是一件事</strong>：那边管的是「对接外部系统」，本页管的是「<strong>自动化规则</strong>本身」
 * —— 触发事件、条件、动作、优先级、启用范围与执行统计。
 * 两者只是同属卷 18 而已 —— 命名空间不同、权限码不同、端点前缀不同。
 *
 * <p>🔴 <strong>规则执行引擎（18.3.2）尚未交付</strong>，所以本页<strong>不提供</strong>
 * 「执行规则」按钮（一个点了必然失败的按钮会让运营以为「点一下就能跑」）。
 * 这里能用的是：规则档案、条件 / 动作 / 启用范围的配置、优先级排序、试运行开关，
 * 以及<strong>执行结果登记</strong>（本模块自己的统计台账）。
 *
 * <p>🔴 本页<strong>不提供</strong>把坏规则显示成正常的入口：连续失败达到阈值的规则
 * 带「连续失败」标记，并且有一个<strong>只看坏规则</strong>的档位 ——
 * 「规则要能看出最后一次执行结果」是这一格存在的全部理由。
 */
export default function AutomationRulesPage() {
  return (
    <div className="space-y-4">
      <AutomationRuleWorkspace />
    </div>
  );
}
