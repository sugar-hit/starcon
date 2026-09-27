'use client';

import * as React from 'react';
import { cn } from '../../lib/utils';
import { useCityRotation } from '../../lib/use-city-rotation';
import { HOME_CITY, PRIMARY_CITIES, clockParts, type WorldCity } from '../../lib/world-clock';
import { CityGlobe } from './city-globe';

interface ClockSlot {
  city: WorldCity;
  rotating: boolean;
  /** 轮换格已切换次数，> 0 才播换场动效，避免首屏入场就闪一下 */
  switches: number;
}

/**
 * 工作台全球时间条：本地时区 + 三个固定城市 + 两格自动轮换城市。
 * 每格右上角带地球标记；轮换格以极短的淡入上移动效换城市，
 * 时间数字本身不参与动画，保证读秒准确。
 */
export function WorldClockStrip({
  className,
  intervalMs,
}: {
  className?: string;
  intervalMs?: number;
}) {
  const [now, setNow] = React.useState<Date | null>(null);
  const { slots: rotating } = useCityRotation(intervalMs);

  React.useEffect(() => {
    const tick = () => setNow(new Date());
    tick();
    const timer = window.setInterval(tick, 1000);
    return () => window.clearInterval(timer);
  }, []);

  const fixed = (city: WorldCity): ClockSlot => ({ city, rotating: false, switches: 0 });
  const slots: ClockSlot[] = [
    fixed(HOME_CITY),
    ...PRIMARY_CITIES.map(fixed),
    ...rotating.map((slot) => ({ city: slot.city, rotating: true, switches: slot.switches })),
  ];

  return (
    <section
      aria-label="全球时间"
      className={cn(
        'border-border bg-border grid grid-cols-2 gap-px overflow-hidden rounded-lg border sm:grid-cols-3 xl:grid-cols-6',
        className,
      )}
    >
      {slots.map((slot, index) => {
        const parts = now ? clockParts(now, slot.city.zone) : null;
        const swapping = slot.rotating && slot.switches > 0;
        return (
          <div
            key={slot.city.zone}
            className={index === 0 ? 'bg-brand-soft px-4 py-3' : 'bg-card px-4 py-3'}
            aria-label={`${slot.city.country} ${slot.city.city}`}
            data-rotating={slot.rotating || undefined}
            data-switches={slot.switches}
            title={slot.rotating ? '该城市自动轮换' : undefined}
          >
            <div className="text-copy-12 flex items-start justify-between gap-2">
              <span
                key={slot.city.zone}
                data-clock-label
                className={cn(
                  'inline-flex min-w-0 flex-wrap items-baseline gap-x-1',
                  swapping && 'clock-swap-in',
                )}
              >
                <span className="font-medium">{slot.city.city}</span>
                <span className="text-muted-foreground">· {slot.city.country}</span>
              </span>
              <CityGlobe />
            </div>
            <time
              aria-label={index === 0 ? '北京时间' : `${slot.city.city}时间`}
              className={cn(
                'text-h-20 my-1.5 block font-medium tabular-nums',
                index === 0 && 'text-brand',
              )}
              dateTime={now?.toISOString()}
            >
              {parts?.time ?? '—:—'}
            </time>
            <p
              key={slot.city.zone}
              className={cn(
                'text-copy-12 text-muted-foreground whitespace-nowrap',
                swapping && 'clock-swap-in',
              )}
            >
              {parts?.date ?? '—'} · {parts?.offset ?? '—'}
            </p>
          </div>
        );
      })}
    </section>
  );
}
