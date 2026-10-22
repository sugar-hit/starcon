'use client';

import { AutoReplyWorkspace } from '@/components/crm/auto-reply-workspace';

/**
 * 15.5.1.1 自动回复（蓝图 15 卷 L3 锚点 15.5.1 下的自动回复子域）。
 *
 * <p>路由是 `/crm/auto-replies`，与 `/crm/messages`（15.1.1.1 消息收件箱）、
 * `/crm/care-reminders`（15.5.2.1 关怀与催办）、`/crm/tags`（15.3.2.1 客户标签）**并列**。
 *
 * <p><b>页面上必须讲清的四条</b>：
 *
 * <ul>
 *   <li><b>一键关闭</b>：全局开关在页面最上面，关闭必须写原因；
 *       关掉后新触发不再自动发，已排队待发的会被逐条撤销（不得再发）。</li>
 *   <li><b>人工接管</b>：接管以「店铺 + 买家」为粒度，接管后同一会话不再自动回复；
 *       「该找人了」（转人工标记）与「人已经接手了」（接管记录）是两件输出。</li>
 *   <li><b>分级自动回复</b>：先自动引导 → 仍无人工介入 → 再发一级 → 转人工，
 *       每一级都留痕。</li>
 *   <li><b>节假日无人值守</b>：值班日历是租户可配的，<b>不是写死的常量</b>；
 *       挡下自动回复时会转人工（留人工出口）。</li>
 * </ul>
 */
export default function CrmAutoRepliesPage() {
  return (
    <div className="space-y-4">
      <header className="space-y-1">
        <h1 className="text-copy-20 font-semibold">自动回复</h1>
        <p className="text-muted-foreground text-copy-13">
          按触发条件（收到消息分类、关键词、无人工介入超时）自动发送回复，
          支持节假日无人值守、分级自动回复（先自动引导再转人工）。
          <strong>自动回复必须可一键关闭并留出人工接管</strong>
          —— 很多平台对纯自动回复有明确限制，所以页面最上面就是一键关闭，
          接管之后同一个会话也不再自动回复。
        </p>
      </header>

      <AutoReplyWorkspace />
    </div>
  );
}
