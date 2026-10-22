'use client';

import { EmailRuleWorkspace } from '@/components/crm/email-rule-workspace';

/**
 * 15.1.2.2 邮件规则（蓝图 15 卷 L3 锚点 15.1.2 邮件下的第二个子模块）。
 *
 * <p>路由是 `/crm/email-rules`，与 `/crm/emails`（15.1.2.1 邮件收发）**并列**：
 * 那一个管**邮件本身**（收 / 发、附件、模板、发送记录），本模块管
 * **收到邮件之后按什么规则处理**（自动分配 / 自动回复 / 静默）。
 *
 * <p><b>页面上必须讲清的三条</b>：
 *
 * <ul>
 *   <li><b>重要沟通不可静默</b>：命中记录里<strong>规则声明的动作</strong>与
 *       <strong>实际生效的动作</strong>两件事都显示；静默被拒时用告警色标出并写明理由 ——
 *       关键沟通静默会造成平台违规。</li>
 *   <li><b>命中记录只追加</b>：页面<strong>不提供</strong>「删除命中记录」「改命中结果」的入口 ——
 *       改得掉的记录等于「当时静默过这封重要邮件」这句话可以随时被抹掉。</li>
 *   <li><b>未交付的下游不编</b>：15.4.1.1 工单分配与 15.5.1 自动回复<strong>都未交付</strong>，
 *       能力声明里逐条标 available=false，页面<strong>不提供</strong>「已转工单」「已自动回复」
 *       这种看起来能点的按钮。</li>
 * </ul>
 */
export default function CrmEmailRulesPage() {
  return (
    <div className="space-y-4">
      <header className="space-y-1">
        <h1 className="text-copy-20 font-semibold">邮件规则</h1>
        <p className="text-muted-foreground text-copy-13">
          配置邮件自动处理规则：按<strong>发件人 / 关键词 / 工单标签</strong>触发， 命中后
          <strong>自动分配 / 自动回复 / 静默</strong>。 规则带显式顺序与启用状态，
          命中记录会冻结当时的规则版本 —— <strong>改规则不追溯改变历史命中记录</strong>。
          <strong>关键沟通静默会造成平台违规</strong>：命中重要沟通的静默规则时，
          实际生效的动作会被服务端<strong>强制降级</strong>为「不静默 + 转人工」，并写明被拒理由。
        </p>
      </header>

      <EmailRuleWorkspace />
    </div>
  );
}
