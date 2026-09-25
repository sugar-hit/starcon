'use client';

import * as React from 'react';
import { cn } from '../../lib/utils';

/**
 * Segmented — Tab 式切换
 * 选中态白底浮起 + 1px 阴影（"按下"感）
 */
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
}

export function Segmented<T extends string = string>({
  value,
  onChange,
  options,
  size = 'sm',
  className,
}: SegmentedProps<T>) {
  return (
    <div
      role="tablist"
      className={cn(
        'inline-flex items-center gap-0.5 rounded-md border border-border bg-muted/60 p-0.5',
        className
      )}
    >
      {options.map((opt) => {
        const isActive = opt.value === value;
        return (
          <button
            key={opt.value}
            type="button"
            role="tab"
            aria-selected={isActive}
            disabled={opt.disabled}
            onClick={() => !opt.disabled && onChange(opt.value)}
            className={cn(
              'inline-flex items-center justify-center gap-1.5 rounded-[5px] transition-colors duration-base ease-out-expo',
              size === 'sm' ? 'h-7 px-3 text-label-12' : 'h-8 px-4 text-label-14',
              isActive
                ? 'bg-background font-medium text-foreground shadow-sm'
                : 'text-muted-foreground hover:text-foreground',
              opt.disabled && 'cursor-not-allowed opacity-50'
            )}
          >
            {opt.icon}
            {opt.label}
          </button>
        );
      })}
    </div>
  );
}