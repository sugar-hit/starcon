'use client';

import * as React from 'react';
import { cn } from '../../lib/utils';

/**
 * PageHeader — 页面顶部标题栏（用户圈红的位置）
 *
 * 设计要点（来自用户硬规则）：
 *   - 只显示当前页 title，**不要 group / 系统前缀**（如不要"交易履约 / 订单中心"）
 *   - sticky top-16（紧贴顶栏 64px 下方）
 *   - 高度 h-14，背景 background，底部 1px border
 *   - 右侧 actions 槽永远在右上
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
    <div
      className={cn(
        'sticky top-16 z-20 border-b border-border bg-background/95 backdrop-blur',
        className
      )}
    >
      <div className="mx-auto flex h-14 max-w-7xl items-center gap-4 px-4 md:px-6 lg:px-8">
        <div className="min-w-0 flex-1">
          <h1 className="truncate text-h-20 text-foreground tracking-tight">{title}</h1>
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