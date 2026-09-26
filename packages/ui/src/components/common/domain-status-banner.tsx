import * as React from 'react';
import { Clock3, Sparkles, CheckCircle2, type LucideIcon } from 'lucide-react';
import { Badge } from '../ui/badge';
import { cn } from '../../lib/utils';

/**
 * DomainStatusBanner — 页面顶部状态条
 *
 * 用途:让用户一眼看出这个 domain 当前页面落地状态
 *   scaffold  → 骨架已就位,功能待实施
 *   partial   → 部分功能已上线
 *   complete  → 全部功能已交付
 */

export type DomainPageStatus = 'scaffold' | 'partial' | 'complete';

const STATUS_LABEL: Record<DomainPageStatus, string> = {
  scaffold: '骨架就位 · 功能待实施',
  partial: '部分功能已上线',
  complete: '全部功能已交付',
};

const STATUS_TONE: Record<DomainPageStatus, 'neutral' | 'warning' | 'success'> = {
  scaffold: 'neutral',
  partial: 'warning',
  complete: 'success',
};

const STATUS_ICON: Record<DomainPageStatus, LucideIcon> = {
  scaffold: Clock3,
  partial: Sparkles,
  complete: CheckCircle2,
};

export interface DomainStatusBannerProps {
  status: DomainPageStatus;
  note?: string;
  className?: string;
}

export function DomainStatusBanner({ status, note, className }: DomainStatusBannerProps) {
  const Icon = STATUS_ICON[status];
  return (
    <div
      className={cn(
        'border-border bg-card flex flex-wrap items-start gap-3 rounded-lg border px-4 py-3',
        className,
      )}
    >
      <div className="bg-muted text-muted-foreground flex h-9 w-9 shrink-0 items-center justify-center rounded-md">
        <Icon className="h-4 w-4" />
      </div>
      <div className="min-w-0 flex-1 space-y-1">
        <div className="flex flex-wrap items-center gap-2">
          <Badge tone={STATUS_TONE[status]}>{STATUS_LABEL[status]}</Badge>
          <span className="text-copy-13 text-muted-foreground">
            页面骨架用于呈现功能边界 · 不展示业务数据
          </span>
        </div>
        {note && (
          <div className="text-copy-13 text-muted-foreground leading-relaxed">{note}</div>
        )}
      </div>
    </div>
  );
}