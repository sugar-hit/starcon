'use client';

import * as React from 'react';
import { cn } from '../../lib/utils';

/** Page title and contextual actions, aligned with the main content grid. */
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
    <div className={cn('bg-background', className)}>
      <div className="flex w-full flex-wrap items-center gap-3">
        <div className="min-w-0 flex-1 basis-48">
          <h1 className="text-h-20 text-primary truncate font-semibold tracking-tight">{title}</h1>
          {description && <p className="text-copy-13 text-muted-foreground mt-1">{description}</p>}
        </div>
        <div className="ml-auto flex shrink-0 items-center gap-2">{actions}</div>
      </div>
      {children}
    </div>
  );
}
