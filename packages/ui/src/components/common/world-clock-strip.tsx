'use client';

import * as React from 'react';
import { cn } from '../../lib/utils';
import { useCityRotation } from '../../lib/use-city-rotation';
import {
  CLOCK_SWAP_STAGGER_MS,
  REGION_POOLS,
  clockParts,
  splitClockTime,
  type WorldCity,
} from '../../lib/world-clock';
import { MiniGlobe } from './mini-globe';

interface ClockSlot {
  city: WorldCity;
  /** 轮换格所属分组，本地时区为 null */
  region: string | null;
  /** 是否刚换过场 */
  switching: boolean;
  /** 换场错峰序号 */
  stagger: number;
}

/**
 * 工作台全球时间带：本地时区 + 五个区域轮换格。
 * 每格带一颗转到该城市的迷你地球，轮换时地球沿最短路径转过去，
 * 时间数字不参与动画，保证读秒即时准确。
 */
export function WorldClockStrip({
  className,
  intervalMs,
}: {
  className?: string;
  intervalMs?: number;
}) {
  const [now, setNow] = React.useState<Date | null>(null);
  const { cities, switches } = useCityRotation(intervalMs);

  React.useEffect(() => {
    const tick = () => setNow(new Date());
    tick();
    const timer = window.setInterval(tick, 1000);
    return () => window.clearInterval(timer);
  }, []);

  const slots: ClockSlot[] = cities.map((city, index) => ({
    city,
    region: REGION_POOLS[index - 1]?.label ?? null,
    switching: index > 0 && switches > 0,
    stagger: index * CLOCK_SWAP_STAGGER_MS,
  }));

  return (
    <section
      aria-label="全球时间"
      className={cn(
        'border-border bg-border relative grid grid-cols-2 gap-px overflow-hidden rounded-lg border sm:grid-cols-3 xl:grid-cols-6',
        className,
      )}
    >
      <span
        aria-hidden="true"
        className="from-brand/[0.05] pointer-events-none absolute inset-0 bg-gradient-to-b to-transparent"
      />
      {slots.map((slot, index) => {
        const parts = now ? clockParts(now, slot.city.zone) : null;
        // 冒号单独渲染，数字保持即时更新，只有冒号呼吸
        const { hours, minutes } = splitClockTime(parts?.time ?? '—:—');
        return (
          <div
            key={index}
            className={cn(
              'relative overflow-hidden px-4 py-2.5',
              index === 0 ? 'bg-brand-soft' : 'bg-card',
            )}
            aria-label={`${slot.city.country} ${slot.city.city}`}
            data-rotating={slot.region ? true : undefined}
            data-switches={slot.region ? switches : undefined}
            title={slot.region ? `${slot.region} · 自动轮换` : undefined}
          >
            <div className="flex items-start justify-between gap-2">
              <span
                key={slot.city.zone}
                data-clock-label
                className={cn(
                  'text-copy-12 inline-flex min-w-0 flex-wrap items-baseline gap-x-1 pt-1',
                  slot.switching && 'clock-swap-in',
                )}
              >
                <span className="font-medium">{slot.city.city}</span>
                <span className="text-muted-foreground">· {slot.city.country}</span>
              </span>
              <MiniGlobe
                city={slot.city}
                delay={slot.stagger}
                label={`${slot.city.city}在地球上的位置`}
                className="h-12 w-12"
              />
            </div>
            <time
              aria-label={index === 0 ? '北京时间' : `${slot.city.city}时间`}
              className={cn(
                'text-h-20 my-1 block font-medium tabular-nums',
                index === 0 && 'text-brand',
              )}
              dateTime={now?.toISOString()}
            >
              {hours}
              <span data-clock-colon className="clock-colon">
                :
              </span>
              {minutes}
            </time>
            <p
              key={slot.city.zone}
              className={cn(
                'text-copy-12 text-muted-foreground whitespace-nowrap',
                slot.switching && 'clock-swap-in',
              )}
            >
              {parts?.date ?? '—'} · {parts?.offset ?? '—'}
            </p>
            {slot.switching ? (
              <span key={switches} aria-hidden="true" className="clock-sweep" />
            ) : null}
          </div>
        );
      })}
    </section>
  );
}
