'use client';

import * as React from 'react';
import { cn } from '../../lib/utils';

export interface SegmentedOption<T extends string = string> {
  label: React.ReactNode;
  value: T;
  icon?: React.ReactNode;
  disabled?: boolean;
}
export interface SegmentedProps<T extends string = string> {
  value: T;
  onChange: (value: T) => void;
  options: SegmentedOption<T>[];
  size?: 'sm' | 'md';
  className?: string;
  'aria-label'?: string;
}

/** Mutually exclusive filter choices; content tabs use the separate Radix Tabs component. */
export function Segmented<T extends string = string>({
  value,
  onChange,
  options,
  size = 'sm',
  className,
  'aria-label': label = '视图选项',
}: SegmentedProps<T>) {
  const refs = React.useRef<Array<HTMLButtonElement | null>>([]);
  const activeIndex = options.findIndex((o) => o.value === value && !o.disabled);
  const tabStop = activeIndex >= 0 ? activeIndex : options.findIndex((o) => !o.disabled);
  function onKeyDown(event: React.KeyboardEvent, index: number) {
    const enabled = options.map((o, i) => (o.disabled ? -1 : i)).filter((i) => i >= 0);
    if (
      !enabled.length ||
      !['ArrowRight', 'ArrowDown', 'ArrowLeft', 'ArrowUp', 'Home', 'End'].includes(event.key)
    )
      return;
    event.preventDefault();
    const current = enabled.indexOf(index);
    const next =
      event.key === 'Home'
        ? enabled[0]
        : event.key === 'End'
          ? enabled[enabled.length - 1]
          : enabled[
              (current +
                (['ArrowRight', 'ArrowDown'].includes(event.key) ? 1 : -1) +
                enabled.length) %
                enabled.length
            ];
    if (next === undefined) return;
    refs.current[next]?.focus();
    const option = options[next];
    if (option) onChange(option.value);
  }
  return (
    <div
      role="radiogroup"
      aria-label={label}
      className={cn(
        'border-border bg-muted inline-flex max-w-full items-center gap-0.5 rounded-md border p-0.5',
        className,
      )}
    >
      {options.map((opt, index) => (
        <button
          key={opt.value}
          ref={(node) => {
            refs.current[index] = node;
          }}
          type="button"
          role="radio"
          aria-checked={opt.value === value}
          tabIndex={index === tabStop ? 0 : -1}
          disabled={opt.disabled}
          onKeyDown={(event) => onKeyDown(event, index)}
          onClick={() => onChange(opt.value)}
          className={cn(
            'focus-visible:outline-brand inline-flex shrink-0 items-center justify-center gap-1.5 whitespace-nowrap rounded-sm transition-colors focus-visible:outline focus-visible:outline-2 disabled:opacity-50',
            size === 'sm' ? 'text-label-12 h-7 px-3' : 'text-label-14 h-8 px-3',
            opt.value === value
              ? 'bg-card text-foreground shadow-xs'
              : 'text-muted-foreground hover:text-foreground',
          )}
        >
          {opt.icon}
          {opt.label}
        </button>
      ))}
    </div>
  );
}
