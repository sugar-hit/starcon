'use client';

import * as React from 'react';
import { cn } from '../../lib/utils';
import { photoFor } from '../../lib/city-photos';
import {
  FIXED_CITIES,
  FIXED_REGIONS,
  clockParts,
  splitClockTime,
  type WorldCity,
} from '../../lib/world-clock';
import { CityBackdrop } from './city-backdrop';
import { MiniGlobe } from './mini-globe';

interface ClockSlot {
  city: WorldCity;
  /** 所属区域标签,本地时区为 null */
  region: string | null;
}

/**
 * 工作台全球时间带:六个固定城市。
 *
 * 城市不轮换 —— 照片版下换城就等于换图,用户要求图片固定,所以城市也定死。
 * 每格带:
 * - 一颗转到该城市的迷你地球(48px)
 * - 一张该城地标照片(背景层,object-fit cover,底部对齐)
 * - 时间数字不参与动画,保证读秒即时准确
 */
export function WorldClockStrip({ className }: { className?: string }) {
  const [now, setNow] = React.useState<Date | null>(null);

  React.useEffect(() => {
    const tick = () => setNow(new Date());
    tick();
    const timer = window.setInterval(tick, 1000);
    return () => window.clearInterval(timer);
  }, []);

  const slots: ClockSlot[] = FIXED_CITIES.map((city, index) => ({
    city,
    region: FIXED_REGIONS[index] ?? null,
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
        <ClockCard key={index} slot={slot} now={now} index={index} />
      ))}
    </section>
  );
}

function ClockCard({
  slot,
  now,
  index,
}: {
  slot: ClockSlot;
  now: Date | null;
  index: number;
}) {
  const parts = now ? clockParts(now, slot.city.zone) : null;
  const { hours, minutes } = splitClockTime(parts?.time ?? '—:—');

  const photo = photoFor(slot.city.city);
  const hasPhoto = !!photo?.url;

  return (
    <div
      className={cn(
        'relative overflow-hidden px-4 py-2.5',
        // 没有照片时落到原卡片底色,有照片时底色由照片决定,fallback 用卡底
        !hasPhoto && (index === 0 ? 'bg-brand-soft' : 'bg-card'),
      )}
      aria-label={`${slot.city.country} ${slot.city.city}`}
      data-region={slot.region ?? undefined}
      data-has-photo={hasPhoto ? 'true' : undefined}
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
            data-clock-label
            className={cn(
              'text-copy-12 inline-flex min-w-0 flex-wrap items-baseline gap-x-1 pt-1',
              // 文字区底色恒定(见 CityBackdrop 深色板),墨色不需要跟着照片变
              hasPhoto ? 'text-white/95' : 'text-foreground',
            )}
          >
            <span className="font-semibold">{slot.city.city}</span>
            <span className={hasPhoto ? 'text-white/70' : 'text-muted-foreground'}>
              · {slot.city.country}
            </span>
          </span>
          <MiniGlobe
            city={slot.city}
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
          className={cn(
            'text-copy-12 whitespace-nowrap',
            hasPhoto ? 'text-white/70' : 'text-muted-foreground',
          )}
        >
          {parts?.date ?? '—'} · {parts?.offset ?? '—'}
        </p>
      </div>
    </div>
  );
}
