import * as React from 'react';
import { cn } from '../../lib/utils';

/**
 * Avatar — 占位圆形头像
 * 不上真实头像功能，纯展示
 */
export interface AvatarProps {
  size?: 'sm' | 'md' | 'lg';
  src?: string;
  alt?: string;
  name?: string;
  className?: string;
}

const sizeMap = {
  sm: 'h-7 w-7 text-[11px]',
  md: 'h-9 w-9 text-label-13',
  lg: 'h-12 w-12 text-label-14',
};

export function Avatar({ size = 'md', src, alt, name, className }: AvatarProps) {
  const initials = name
    ?.split(' ')
    .map((p) => p[0])
    .slice(0, 2)
    .join('')
    .toUpperCase();

  return (
    <div
      className={cn(
        'flex shrink-0 items-center justify-center overflow-hidden rounded-full bg-gradient-to-br from-gray-1 to-gray-3 font-medium text-foreground/80 ring-1 ring-border',
        sizeMap[size],
        className
      )}
    >
      {src ? (
        // eslint-disable-next-line @next/next/no-img-element
        <img src={src} alt={alt ?? name ?? ''} className="h-full w-full object-cover" />
      ) : (
        <span>{initials ?? 'U'}</span>
      )}
    </div>
  );
}