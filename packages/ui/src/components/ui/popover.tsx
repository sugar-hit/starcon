'use client';

import * as React from 'react';
import * as PopoverPrimitive from '@radix-ui/react-popover';
import { cn } from '../../lib/utils';

/**
 * Popover — 通用浮层(用于 tab 右键菜单 / 卡片扩展等)
 *
 * 与 Modal 的区别:
 *   - Modal:居中弹出,打断列表上下文(强焦点)
 *   - Popover:锚定元素附近弹出(轻量焦点)
 *   - DetailDrawer:右侧滑出抽屉(详情专用)
 */

const Popover = PopoverPrimitive.Root;
const PopoverTrigger = PopoverPrimitive.Trigger;
const PopoverAnchor = PopoverPrimitive.Anchor;
const PopoverClose = PopoverPrimitive.Close;

const PopoverContent = React.forwardRef<
  React.ElementRef<typeof PopoverPrimitive.Content>,
  React.ComponentPropsWithoutRef<typeof PopoverPrimitive.Content>
>(({ className, align = 'end', sideOffset = 6, ...props }, ref) => (
  <PopoverPrimitive.Portal>
    <PopoverPrimitive.Content
      ref={ref}
      align={align}
      sideOffset={sideOffset}
      className={cn(
        'border-border bg-card text-copy-13 text-foreground shadow-elevated z-50 rounded-md border p-1',
        'data-[state=open]:animate-fade-in data-[state=closed]:animate-fade-out',
        'min-w-[160px]',
        className,
      )}
      {...props}
    />
  </PopoverPrimitive.Portal>
));
PopoverContent.displayName = PopoverPrimitive.Content.displayName;

export { Popover, PopoverTrigger, PopoverAnchor, PopoverClose, PopoverContent };
