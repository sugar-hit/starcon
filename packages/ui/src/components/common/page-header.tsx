'use client';

import * as React from 'react';
import { cn } from '../../lib/utils';

/**
 * PageHeader — 页面顶部标题栏（用户圈红的位置）
 *
 * 设计要点（来自用户硬规则）：
 *   - 只显示当前页 title，**不要 group / 系统前缀**
 *   - 不 sticky（被内容遮挡反而难看） — 普通块元素占文档流
 *   - 高度 h-14，背景 background (非纯白)，底部 1px border
 *   - 主区 padding 由调用方控制（不再 sticky 后无需 top-16 占位）
 *   - 右侧 actions 槽永远在右上
 *
 * 字号：h1 = h-16 (16px) font-semibold + text-primary (#0A0A0A)
 *       — 比 nav item (copy-12) 大一档, 跟 CardTitle (h-16) 同档, 视觉层级清晰
 * 布局：w-full + max-w-7xl + px-5 — 跟主区内容 div 完全一致的 layout
 *       — 避免 mx-auto 造成的"莫名居中"视觉错觉
 *
 * 用法：
 *   <PageHeader title="订单列表" actions={<Button>新建</Button>} />
 */
export interface PageHeaderProps {
  /** 当前页 title（不带系统前缀） */
  title: string;
  /** 副标题 / 描述（可选） */
  description?: string;
  /** 右侧操作区 */
  actions?: React.ReactNode;
  /** 完整覆盖（不常用） */
  children?: React.ReactNode;
  className?: string;
}

export function PageHeader({ title, description, actions, children, className }: PageHeaderProps) {
  return (
    <div className={cn('border-b border-border bg-background', className)}>
      <div className="flex h-12 w-full items-center gap-4 px-5">
        <div className="min-w-0 flex-1">
          <h1 className="truncate text-[18px] font-semibold text-primary tracking-tight">
            {title}
          </h1>
          {description && (
            <p className="truncate text-copy-13 text-muted-foreground">{description}</p>
          )}
        </div>
        <div className="ml-auto flex shrink-0 items-center gap-2">{actions}</div>
      </div>
      {children}
    </div>
  );
}