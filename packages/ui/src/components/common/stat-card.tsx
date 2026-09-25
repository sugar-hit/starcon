import * as React from 'react';
import { ArrowDown, ArrowUp, Minus } from 'lucide-react';
import { cn, numClass, num, pct } from '../../lib/utils';

/**
 * StatCard — KPI 卡（支持双向趋势）
 *
 * polarity 控制"涨"是好事还是坏事：
 *   positive  营收/订单/活跃用户 → 涨=好
 *   negative  退款/投诉/库存周转 → 涨=坏
 *   neutral   库存数量/中性指标  → 涨=警示
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

function trendClasses(polarity: NonNullable<StatCardProps['polarity']>, direction: NonNullable<StatCardProps['deltaDirection']>) {
  if (direction === 'flat') {
    return { chip: 'bg-muted text-muted-foreground', icon: <Minus className="h-3 w-3" /> };
  }
  const isUp = direction === 'up';
  if (polarity === 'positive') {
    return isUp
      ? { chip: 'bg-emerald-50 text-emerald-700', icon: <ArrowUp className="h-3 w-3" /> }
      : { chip: 'bg-rose-50 text-rose-700', icon: <ArrowDown className="h-3 w-3" /> };
  }
  if (polarity === 'negative') {
    return isUp
      ? { chip: 'bg-rose-50 text-rose-700', icon: <ArrowUp className="h-3 w-3" /> }
      : { chip: 'bg-emerald-50 text-emerald-700', icon: <ArrowDown className="h-3 w-3" /> };
  }
  // neutral — 涨都是警示
  return isUp
    ? { chip: 'bg-amber-50 text-amber-700', icon: <ArrowUp className="h-3 w-3" /> }
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
    <div
      className={cn(
        'rounded-lg border border-border bg-card p-5 transition-colors duration-base ease-out-expo hover:border-border-strong',
        className
      )}
    >
      <div className="flex items-start justify-between gap-2">
        <div className="text-copy-13 text-muted-foreground">{label}</div>
        {icon && (
          <div className="flex h-9 w-9 items-center justify-center rounded-md bg-muted text-muted-foreground">
            {icon}
          </div>
        )}
      </div>

      <div className="mt-3 flex items-baseline gap-1">
        <div className={cn('text-h-32 text-foreground tracking-tight', numClass)}>{formatted}</div>
        {unit && <div className="text-copy-14 text-muted-foreground">{unit}</div>}
      </div>

      {(showDelta || comparisonLabel) && (
        <div className="mt-2 flex items-center gap-2">
          {showDelta && (
            <span
              className={cn(
                'inline-flex items-center gap-0.5 rounded-md px-1.5 py-0.5 text-label-12',
                trend.chip
              )}
            >
              {trend.icon}
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