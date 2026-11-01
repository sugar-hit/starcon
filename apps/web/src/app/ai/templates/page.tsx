'use client';

import { NotificationTemplateWorkspace } from '@/components/ai/notification-template-workspace';

/**
 * 18.5.1.1 模板管理（卷 18 前端入口 `/ai/templates`）。
 *
 * <p>蓝图：「<strong>模板管理</strong>：管理通知与消息模板：模板内容、变量、适用场景、语言版本、
 * 启用状态；修改后新产生的通知使用新版本，历史通知保留当时内容。输入：模板编辑；变量定义。
 * 输出：模板记录 + 渲染预览。关系：上游 = 3.4.2 文案资产；下游 = 1.3.1.1、15.1.1.2、18.5.2。
 * <strong>模板必须版本化</strong>，已发出的通知不能被新模板"篡改"。」
 *
 * <p>🔴 本页与 <code>/text</code>（18.4.2.1 文本与翻译）<strong>不是一件事</strong>：
 * 那一页管的是「AI 翻译链路的产物（客服话术草稿 + 置信度 + 人工确认后发布）」，
 * 这一页管的是「<strong>通知与消息模板</strong>：按场景 × 语言组织、版本化、
 * 可按时刻解析出当时生效的那一版」。
 *
 * <p>🔴 本页最要紧的三件事：<strong>编辑只能新增版本</strong>（老版本不可篡改）、
 * <strong>渲染预览缺变量要点名报错</strong>（不显示空白）、<strong>按时刻解析能翻到
 * 当时那一版</strong>（历史通知保留当时内容）。
 */
export default function NotificationTemplatesPage() {
  return (
    <div className="space-y-4">
      <NotificationTemplateWorkspace />
    </div>
  );
}
