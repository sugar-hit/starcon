import * as React from 'react';
import { ArrowDown, ArrowUp, Minus } from 'lucide-react';
import { cn, numClass, num, pct } from '../../lib/utils';

/**
 * StatCard — KPI 卡（支持双向趋势）
 *
 * polarity 控制"涨"是好事还是坏事：
 *   positive  营收/订单/活跃用户 → 涨=好
 *   negative  退款/投诉/库存周转 → 涨=坏
 *   neutral   库存数量/中性指标  → 不定义好坏
 *
 * 用法:
 *   <StatCard label="今日营收" value={12847} delta={12.5} deltaDirection="up" polarity="positive" />
 *   <StatCard label="今日退款" value={143}   delta={4.2}  deltaDirection="up" polarity="negative" />
 */
export interface StatCardProps {
  label: React.ReactNode;
  value: number | string;
  unit?: string;
  delta?: number;
  deltaDirection?: 'up' | 'down' | 'flat';
  polarity?: 'positive' | 'negative' | 'neutral';
  comparisonLabel?: string;
  icon?: React.ReactNode;
  className?: string;
}

function trendClasses(
  polarity: NonNullable<StatCardProps['polarity']>,
  direction: NonNullable<StatCardProps['deltaDirection']>,
) {
  if (direction === 'flat') {
    return { chip: 'bg-muted text-muted-foreground', icon: <Minus className="h-3 w-3" /> };
  }
  const isUp = direction === 'up';
  if (polarity === 'positive') {
    return isUp
      ? { chip: 'bg-success-soft text-success', icon: <ArrowUp className="h-3 w-3" /> }
      : { chip: 'bg-destructive-soft text-destructive', icon: <ArrowDown className="h-3 w-3" /> };
  }
  if (polarity === 'negative') {
    return isUp
      ? { chip: 'bg-destructive-soft text-destructive', icon: <ArrowUp className="h-3 w-3" /> }
      : { chip: 'bg-success-soft text-success', icon: <ArrowDown className="h-3 w-3" /> };
  }
  // Neutral metrics communicate direction without a value judgment.
  return isUp
    ? { chip: 'bg-muted text-muted-foreground', icon: <ArrowUp className="h-3 w-3" /> }
    : { chip: 'bg-muted text-muted-foreground', icon: <ArrowDown className="h-3 w-3" /> };
}

export function StatCard({
  label,
  value,
  unit,
  delta,
  deltaDirection = 'flat',
  polarity = 'positive',
  comparisonLabel,
  icon,
  className,
}: StatCardProps) {
  const formatted = typeof value === 'number' ? num(value) : value;
  const showDelta = typeof delta === 'number';
  const trend = trendClasses(polarity, deltaDirection);

  return (
    <div className={cn('border-border bg-card rounded-lg border p-5', className)}>
      <div className="flex items-start justify-between gap-2">
        <div className="text-copy-13 text-muted-foreground">{label}</div>
        {icon && (
          <div className="bg-muted text-muted-foreground flex h-9 w-9 items-center justify-center rounded-md">
            {icon}
          </div>
        )}
      </div>

      <div className="mt-3 flex items-baseline gap-1">
        <div className={cn('text-h-24 text-foreground tracking-tight', numClass)}>{formatted}</div>
        {unit && <div className="text-copy-14 text-muted-foreground">{unit}</div>}
      </div>

      {(showDelta || comparisonLabel) && (
        <div className="mt-2 flex items-center gap-2">
          {showDelta && (
            <span
              className={cn(
                'text-label-12 inline-flex items-center gap-0.5 rounded-md px-1.5 py-0.5',
                trend.chip,
              )}
            >
              <span aria-hidden="true">{trend.icon}</span>
              <span className="sr-only">
                {deltaDirection === 'up' ? '上升' : deltaDirection === 'down' ? '下降' : '持平'}
              </span>
              {pct(Math.abs(delta!))}
            </span>
          )}
          {comparisonLabel && (
            <span className="text-copy-13 text-muted-foreground">{comparisonLabel}</span>
          )}
        </div>
      )}
    </div>
  );
}
