'use client';

import { useState } from 'react';
import { OneClickPublishWorkspace } from '@/components/one-click-publish/one-click-publish-workspace';
import { PublishWorkspace } from '@/components/listing-publish/publish-workspace';
import { ListingPublishTaskWorkspace } from '@/components/listing-publish-task/listing-publish-task-workspace';

/**
 * 发布与同步：L3「4.3.1 发布与同步」的锚点路由 `/listings/publish`。
 *
 * <p>这个 L3 下有三个模块：
 *
 * <ul>
 *   <li><b>4.3.1.1 一键刊登</b> —— 单个 SKU 一次性发布到多个目标店铺/站点：自动套模板、
 *       填充内容、建立映射、上传媒体、设置价格库存、提交平台审核；跨平台发布**不是一个事务**
 *       （某个平台失败不影响其他平台，各自独立重试）。<strong>已交付</strong>，见下面
 *       「一键刊登（4.3.1.1）」页签。
 *   <li><b>4.3.1.2 批量发布</b> —— 按规则筛选待发布商品，生成任务批次，支持暂停 / 调整顺序 /
 *       批量重试，按平台配额限流。<strong>已交付</strong>，见下面「批量发布（4.3.1.2）」页签。
 *   <li><b>4.3.1.3 发布任务</b> —— <strong>单次发布任务的进度视图</strong>：各步骤状态
 *       （内容校验 → 平台创建 → 变体创建 → 媒体上传 → 价格库存设置 → 提交审核）、失败步骤与原因、
 *       可重试的步骤；输出步骤时间线 + 错误明细 + 重试操作。<strong>已交付</strong>，见下面
 *       「发布任务（4.3.1.3）」页签。
 * </ul>
 *
 * <p>🔴 <b>三个模块用页签分开，而不是做成同一个筛选维度</b>：它们是三套独立的状态机 ——
 * 一键刊登是「一个 SKU → N 个目标，每个目标各自成败、各自重试」；批量发布是
 * 「一个批次 → 一条队列，按平台 API 配额排队」；发布任务是「单次发布任务的进度视图」。
 * 共用一张表或一套端点，「一个平台失败」就会被误写成「整批失败」，这正是蓝图那句加粗要防的事。
 *
 * <p>与 `/listings`（4.1.1.1 列表）、`/listings/mappings`（4.1.2.1 映射）、`/listings/templates`
 * （4.2.2 模板）、`/listings/editor`（4.2.1 内容编辑）各挂一个工作区是同一手法。
 */
export default function ListingPublishPage() {
  const [tab, setTab] = useState<'one-click' | 'batch' | 'publish-task'>('one-click');
  const TABS = [
    { key: 'one-click' as const, label: '一键刊登（4.3.1.1）' },
    { key: 'batch' as const, label: '批量发布（4.3.1.2）' },
    { key: 'publish-task' as const, label: '发布任务（4.3.1.3）' },
  ];

  return (
    <div className="space-y-4">
      <nav className="border-border flex gap-1 border-b" aria-label="发布工作区">
        {TABS.map((item) => (
          <button
            key={item.key}
            type="button"
            aria-current={tab === item.key ? 'page' : undefined}
            onClick={() => setTab(item.key)}
            className={
              tab === item.key
                ? 'border-primary text-foreground -mb-px border-b-2 px-3 py-2 text-sm font-medium'
                : 'text-muted-foreground hover:text-foreground -mb-px border-b-2 border-transparent px-3 py-2 text-sm'
            }
          >
            {item.label}
          </button>
        ))}
      </nav>
      {tab === 'one-click' ? (
        <OneClickPublishWorkspace />
      ) : tab === 'batch' ? (
        <PublishWorkspace />
      ) : (
        <ListingPublishTaskWorkspace />
      )}
    </div>
  );
}
