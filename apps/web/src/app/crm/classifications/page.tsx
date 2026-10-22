'use client';

import { MessageClassificationWorkspace } from '@/components/crm/message-classification-workspace';

/**
 * 15.1.1.3 消息分类（蓝图 15 卷 L3 锚点 15.1.1 下的消息子域）。
 *
 * <p>路由是 `/crm/classifications`，与 `/crm/messages`（15.1.1.1 消息收件箱）、
 * `/crm/messages/sends`（15.1.1.2 消息发送）**并列**：
 * 前两个管**消息本身**（收到 / 发出），本模块管**怎么给消息分类**。
 *
 * <p><b>页面上必须讲清的四条</b>：
 *
 * <ul>
 *   <li><b>人工纠正要回流为训练样本</b>：纠正**只追加**、不改写分类结论，
 *       并自带机器预测快照与消息正文 —— 只有正确答案的样本学不出「错在哪」。</li>
 *   <li><b>不覆写人工设过的优先级</b>：页面把
 *       <strong>建议值 / 人工值 / 生效值 / 是否冲突</strong>四件事一起显示，
 *       不只显示一个数字 —— 只显示一个的话，看的人会以为那就是唯一结论。</li>
 *   <li><b>「是否可自动回复」是判定结果</b>：15.5.1 自动回复与 1.3 预警
 *       <strong>都未交付</strong>，能力声明里逐条标 available=false，
 *       页面<strong>不提供</strong>「触发自动回复」这种看起来能点的按钮。</li>
 *   <li><b>命中依据要能复核</b>：命中了哪条规则的哪个关键词、在哪个字段命中，都显示出来；
 *       落败的规则也记着 —— 只记赢家就看不出「为什么不是它」。</li>
 * </ul>
 */
export default function CrmClassificationsPage() {
  return (
    <div className="space-y-4">
      <header className="space-y-1">
        <h1 className="text-copy-20 font-semibold">消息分类</h1>
        <p className="text-muted-foreground text-copy-13">
          对消息自动分类：
          <strong>售前咨询 / 物流查询 / 退货退款 / 投诉 / 平台合规 / 垃圾信息</strong>， 输出
          <strong>分类标签 + 优先级 + 是否可自动回复</strong>，并支持人工纠正以改进分类。 分类规则按
          <strong>关键词 + 适用平台 + 适用消息类型</strong>配置，命中依据逐条留痕。
          <strong>人工纠正要回流为训练样本</strong> —— 否则分类准确率永远上不去。 页面
          <strong>不会</strong>改掉你手工设过的消息优先级：建议值、人工值、生效值与
          「是否冲突」都会一起显示出来。
        </p>
      </header>

      <MessageClassificationWorkspace />
    </div>
  );
}
