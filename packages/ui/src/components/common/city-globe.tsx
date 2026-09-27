import * as React from 'react';
import { cn } from '../../lib/utils';

/**
 * 城市标记：细网格地球，参考球体网格的观感。
 * 装饰性元素，城市名与国家已在同一行给出，因此对辅助技术隐藏。
 */
export function CityGlobe({ className, ...props }: React.SVGProps<SVGSVGElement>) {
  return (
    <span
      className={cn(
        'bg-brand/10 text-brand ring-brand/15 inline-flex h-[18px] w-[18px] shrink-0 items-center justify-center rounded-[5px] ring-1 ring-inset',
        className,
      )}
    >
      <svg
        viewBox="0 0 16 16"
        fill="none"
        stroke="currentColor"
        strokeWidth={1.2}
        strokeLinecap="round"
        aria-hidden="true"
        focusable="false"
        {...props}
      >
        <circle cx="8" cy="8" r="7" />
        <path d="M1 8h14" />
        <ellipse cx="8" cy="8" rx="3.2" ry="7" />
        <path d="M2.1 4.3Q8 2.4 13.9 4.3" />
        <path d="M2.1 11.7Q8 13.6 13.9 11.7" />
      </svg>
    </span>
  );
}
