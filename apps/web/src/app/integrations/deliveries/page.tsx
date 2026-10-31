'use client';

import { DeliveryRecordWorkspace } from '@/components/integrations/deliveries/delivery-record-workspace';

/**
 * 18.1 开放与凭证 → 18.1.2 事件订阅 → 18.1.2.2 投递记录（卷 18 前端入口 `/integrations/deliveries`）。
 *
 * <p>蓝图：「<strong>投递记录</strong>：记录事件投递明细：事件 ID、订阅方、投递状态、响应码、
 * 重试次数；提供手动重投与死信重放。输入：投递记录；重放请求。输出：投递日志 + 重放任务。
 * 关系：上游 = 18.1.2.1；下游 = 外部端点。
 * <strong>接收方应按 event_id 幂等去重</strong>——投递保证是"至少一次"，不是"恰好一次"。」
 *
 * <p>🔴 本页与 <code>/integrations/webhooks</code>（18.1.2.1 事件订阅）<strong>不是一件事</strong>：
 * 那一页管的是「订阅怎么配」（可订阅事件列表、订阅端点、签名密钥、事件过滤、重试与死信策略），
 * 本页管的是「**投出去了没有**」（投递明细、投递日志、响应码、重试、死信、手动重投与死信重放）。
 * 两者只是同属卷 18 而已 —— 命名空间不同、权限码不同、端点前缀不同。
 *
 * <p>🔴 本页说的每一句「投出去了」都对应一条**真的落库**的投递记录：
 * 18.1.2.1 此前把「事件实际投递」如实答成「未接入」（端点恒 409、一次包都不发），
 * 归的就是本模块 —— 现在投递执行器与投递明细都在这里了。
 */
export default function DeliveriesPage() {
  return (
    <div className="space-y-4">
      <DeliveryRecordWorkspace />
    </div>
  );
}
