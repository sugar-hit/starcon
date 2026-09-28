'use client';

import * as React from 'react';
import { cn } from '../../lib/utils';
import { photoFor } from '../../lib/city-photos';
import {
  CITY_ROTATION_MS,
  REGION_POOLS,
  cityLabel,
  rotationCities,
  clockParts,
  splitClockTime,
  type WorldCity,
} from '../../lib/world-clock';
import { CityBackdrop } from './city-backdrop';
import { MiniGlobe } from './mini-globe';

/** 北京固定 + 五个区域轮换；城市名、时区、地球、照片始终对应同一城市。 */
export function WorldClockStrip({ className }: { className?: string }) {
  const [now, setNow] = React.useState<Date | null>(null);
  const [cursor, setCursor] = React.useState(0);

  React.useEffect(() => {
    const tick = () => setNow(new Date());
    tick();
    const timer = window.setInterval(tick, 1000);
    const rotation = window.setInterval(() => {
      if (!document.hidden) setCursor((value) => value + 1);
    }, CITY_ROTATION_MS);
    return () => {
      window.clearInterval(timer);
      window.clearInterval(rotation);
    };
  }, []);

  // 只预取下一轮，避免首屏同时下载全部城市背景。
  React.useEffect(() => {
    const timer = window.setTimeout(() => {
      if (document.hidden) return;
      for (const city of rotationCities(cursor + 1).slice(1)) {
        const photo = photoFor(city.city);
        if (!photo) continue;
        const image = new Image();
        image.src = photo.url;
        void image.decode().catch(() => undefined);
      }
    }, 1500);
    return () => window.clearTimeout(timer);
  }, [cursor]);

  return (
    <section
      aria-label="全球时间"
      className={cn(
        'border-border bg-border relative grid grid-cols-1 gap-px overflow-hidden rounded-lg border min-[560px]:grid-cols-2 lg:grid-cols-3 min-[1800px]:grid-cols-6',
        className,
      )}
    >
      {rotationCities(cursor).map((city, index) => (
        <ClockCard key={index} city={city} now={now} index={index} />
      ))}
    </section>
  );
}

function ClockCard({ city, now, index }: { city: WorldCity; now: Date | null; index: number }) {
  const parts = now ? clockParts(now, city.zone) : null;
  const { hours, minutes } = splitClockTime(parts?.time ?? '—:—');
  const photo = photoFor(city.city);

  return (
    <div
      className="bg-foreground relative overflow-hidden px-4 py-3 text-white"
      aria-label={cityLabel(city)}
      data-region={index > 0 ? REGION_POOLS[index - 1]?.label : undefined}
      data-rotating={index > 0 ? 'true' : undefined}
      data-has-photo={photo ? 'true' : undefined}
    >
      {photo ? <CityBackdrop url={photo.url} className="z-0" /> : null}
      {/*
        文字层随城市一起淡入。key 用 city.zone：换城市才换 key，
        换 key 触发重挂载，clock-swap-in 动画重播；秒级 tick 不换 key，
        所以读数不会每帧重播动画。
      */}
      <div className="relative z-10" key={city.zone}>
        <p data-clock-label className="clock-swap-in text-copy-12 whitespace-nowrap text-white/90">
          <span className="font-semibold text-white">{city.city}</span>{' '}
          {cityLabel(city).slice(city.city.length + 1)}
        </p>
        <div className="mt-2 flex min-h-12 items-center justify-between gap-3">
          <div>
            <time
              aria-label={index === 0 ? '北京时间' : `${city.city}时间`}
              className="block text-2xl font-medium tabular-nums leading-none tracking-tight"
              dateTime={now?.toISOString()}
            >
              {hours}
              <span data-clock-colon className="clock-colon">
                :
              </span>
              {minutes}
            </time>
            <p
              data-clock-meta
              className="clock-swap-in text-copy-12 mt-1 flex flex-wrap gap-x-2 text-white/90"
            >
              <span>{parts?.date ?? '—'}</span>
              <span>{parts?.offset ?? '—'}</span>
            </p>
          </div>
          <MiniGlobe city={city} label={`${city.city}在地球上的位置`} className="h-12 w-12" />
        </div>
      </div>
    </div>
  );
}
