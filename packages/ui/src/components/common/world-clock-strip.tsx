'use client';

import * as React from 'react';
import { cn } from '../../lib/utils';
import { useCityRotation } from '../../lib/use-city-rotation';
import { useAutoInk } from '../../lib/use-auto-ink';
import { photoFor } from '../../lib/city-photos';
import {
  CLOCK_SWAP_STAGGER_MS,
  REGION_POOLS,
  clockParts,
  splitClockTime,
  type WorldCity,
} from '../../lib/world-clock';
import { CityBackdrop } from './city-backdrop';
import { MiniGlobe } from './mini-globe';

interface ClockSlot {
  city: WorldCity;
  /** 轮换格所属分组,本地时区为 null */
  region: string | null;
  /** 是否刚换过场 */
  switching: boolean;
  /** 换场错峰序号 */
  stagger: number;
}

/**
 * 工作台全球时间带:本地时区 + 五个区域轮换格。
 *
 * 每格带:
 * - 一颗转到该城市的迷你地球(48px,换城时球体沿最短路径补间)
 * - 一张该城地标照片(背景层,object-fit cover,底部对齐)
 * - 自动墨色的文字(由照片顶部像素亮度推导,白底深字/暗底白字)
 * - 时间数字不参与动画,保证读秒即时准确
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
      {slots.map((slot, index) => (
        <ClockCard
          key={index}
          slot={slot}
          now={now}
          switches={switches}
          index={index}
        />
      ))}
    </section>
  );
}

function ClockCard({
  slot,
  now,
  switches,
  index,
}: {
  slot: ClockSlot;
  now: Date | null;
  switches: number;
  index: number;
}) {
  const parts = now ? clockParts(now, slot.city.zone) : null;
  const { hours, minutes } = splitClockTime(parts?.time ?? '—:—');

  const photo = photoFor(slot.city.city);
  const ink = useAutoInk(photo?.url);
  const isLight = ink === 'light';

  const hasPhoto = !!photo?.url;

  return (
    <div
      className={cn(
        'relative overflow-hidden px-4 py-2.5',
        // 没有照片时落到原卡片底色,有照片时底色由照片决定,fallback 用卡底
        !hasPhoto && (index === 0 ? 'bg-brand-soft' : 'bg-card'),
      )}
      aria-label={`${slot.city.country} ${slot.city.city}`}
      data-rotating={slot.region ? true : undefined}
      data-switches={slot.region ? switches : undefined}
      data-ink={ink}
      data-has-photo={hasPhoto ? 'true' : undefined}
      title={slot.region ? `${slot.region} · 自动轮换` : undefined}
    >
      {hasPhoto && photo ? <CityBackdrop url={photo.url} className="z-0" /> : null}
      {/*
        文字整体收进 relative z-10 容器。
        之前 time / p 是 position:static,而照片层是 position:absolute ——
        同一 stacking context 里绝对定位元素永远压在静态流内容之上，
        所以时间数字虽然在 DOM 里排在照片之后，视觉上被整层盖掉。
        显式 z-0(照片) / z-10(文字) 把层级写死，以后新增元素不会再踩。
      */}
      <div className="relative z-10">
        <div className="flex items-start justify-between gap-2">
          <span
            key={slot.city.zone}
            data-clock-label
            className={cn(
              'text-copy-12 inline-flex min-w-0 flex-wrap items-baseline gap-x-1 pt-1',
              // 有照片时统一用白字(压暗罩兜底);ink 仍保留给调试/后续接入真实地标照
              hasPhoto ? 'text-white/90' : 'text-foreground',
              slot.switching && 'clock-swap-in',
            )}
          >
            <span className="font-semibold">{slot.city.city}</span>
            <span className={hasPhoto ? 'text-white/70' : 'text-muted-foreground'}>
              · {slot.city.country}
            </span>
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
            'text-h-20 my-1 block font-semibold tabular-nums',
            hasPhoto ? 'text-white' : index === 0 ? 'text-brand' : 'text-foreground',
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
            'text-copy-12 whitespace-nowrap',
            hasPhoto ? 'text-white/75' : 'text-muted-foreground',
            slot.switching && 'clock-swap-in',
          )}
        >
          {parts?.date ?? '—'} · {parts?.offset ?? '—'}
        </p>
        {slot.switching ? (
          <span key={switches} aria-hidden="true" className="clock-sweep" />
        ) : null}
      </div>
    </div>
  );
}
