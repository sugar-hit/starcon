'use client';

import * as React from 'react';
import * as DialogPrimitive from '@radix-ui/react-dialog';
import { X } from 'lucide-react';
import { cn } from '../../lib/utils';

/**
 * DetailDrawer — 详情抽屉 (桌面右侧滑出,移动端全屏)
 *
 * 用途:
 *   - 订单/SKU/客户档案等"查看型"详情,不离开当前列表上下文
 *   - ProBuilt Floating Forms / Notion / Shopify 风格
 *
 * 与 Sheet 的区别:
 *   - Sheet  → 移动端 hamburger 触发的导航抽屉 (受用户硬规则保护)
 *   - DetailDrawer → 桌面端详情抽屉,移动端全屏
 *
 * 与 Modal 的区别:
 *   - Modal    → 编辑/创建/确认 (强焦点,打断列表)
 *   - DetailDrawer → 查看/预览 (不打断列表上下文)
 *
 * 宽度:
 *   - 默认 `md:max-w-[640px]` — 适合订单/SKU/客户档案的中等内容
 *   - 调用方可传 `width` prop 覆盖(根据页面内容多少决定)
 *   - 例如客户档案可能需要 `width="xl"`,简单预览用 `width="sm"`
 */

const DetailDrawer = DialogPrimitive.Root;
const DetailDrawerTrigger = DialogPrimitive.Trigger;
const DetailDrawerClose = DialogPrimitive.Close;

const DetailDrawerOverlay = React.forwardRef<
  React.ElementRef<typeof DialogPrimitive.Overlay>,
  React.ComponentPropsWithoutRef<typeof DialogPrimitive.Overlay>
>(({ className, ...props }, ref) => (
  <DialogPrimitive.Overlay
    ref={ref}
    className={cn(
      // 不模糊 — 抽屉本身已经够聚焦,模糊让用户看不清上下文
      'animate-fade-in fixed inset-0 z-50 bg-black/40',
      className,
    )}
    {...props}
  />
));
DetailDrawerOverlay.displayName = 'DetailDrawerOverlay';

export type DetailDrawerWidth = 'sm' | 'md' | 'lg' | 'xl' | 'full';

const WIDTH_CLASS: Record<DetailDrawerWidth, string> = {
  sm: 'md:max-w-[420px]',
  md: 'md:max-w-[640px]',
  lg: 'md:max-w-[820px]',
  xl: 'md:max-w-[1024px]',
  full: 'md:max-w-[calc(100vw-2rem)]',
};

export interface DetailDrawerContentProps extends Omit<
  React.ComponentPropsWithoutRef<typeof DialogPrimitive.Content>,
  'width'
> {
  /** 抽屉宽度 — 根据内容多少决定,默认 md (640px) */
  width?: DetailDrawerWidth;
}

const DetailDrawerContent = React.forwardRef<
  React.ElementRef<typeof DialogPrimitive.Content>,
  DetailDrawerContentProps
>(({ className, children, width = 'md', ...props }, ref) => (
  <DialogPrimitive.Portal>
    <DetailDrawerOverlay />
    <DialogPrimitive.Content
      ref={ref}
      className={cn(
        // 移动端 (<md):全屏
        // 桌面端 (md+):右侧滑出,按 width 决定 max-w
        'border-border bg-card shadow-elevated fixed inset-y-0 right-0 z-50 flex h-full w-full flex-col border-l',
        WIDTH_CLASS[width],
        'data-[state=open]:animate-slide-in-right data-[state=closed]:animate-fade-out',
        className,
      )}
      {...props}
    >
      {children}
    </DialogPrimitive.Content>
  </DialogPrimitive.Portal>
));
DetailDrawerContent.displayName = 'DetailDrawerContent';

function DetailDrawerHeader({
  className,
  children,
  ...props
}: React.HTMLAttributes<HTMLDivElement>) {
  return (
    <div
      className={cn(
        'border-border flex shrink-0 items-start justify-between gap-3 border-b px-5 py-4',
        className,
      )}
      {...props}
    >
      <div className="min-w-0 flex-1">{children}</div>
      <DialogPrimitive.Close
        aria-label="关闭详情"
        className={cn(
          'text-muted-foreground hover:bg-muted hover:text-foreground focus-visible:outline-brand -mr-1 -mt-1 rounded-md p-1 transition-colors focus-visible:outline focus-visible:outline-2',
        )}
      >
        <X className="h-4 w-4" />
        <span className="sr-only">关闭详情</span>
      </DialogPrimitive.Close>
    </div>
  );
}

function DetailDrawerTitle({ className, ...props }: React.HTMLAttributes<HTMLHeadingElement>) {
  return (
    <DialogPrimitive.Title
      className={cn('text-h-20 text-foreground tracking-tight', className)}
      {...props}
    />
  );
}

function DetailDrawerDescription({
  className,
  ...props
}: React.HTMLAttributes<HTMLParagraphElement>) {
  return (
    <DialogPrimitive.Description
      className={cn('text-copy-13 text-muted-foreground mt-1', className)}
      {...props}
    />
  );
}

function DetailDrawerBody({ className, ...props }: React.HTMLAttributes<HTMLDivElement>) {
  return <div className={cn('min-h-0 flex-1 overflow-y-auto px-5 py-4', className)} {...props} />;
}

function DetailDrawerFooter({ className, ...props }: React.HTMLAttributes<HTMLDivElement>) {
  return (
    <div
      className={cn(
        'border-border flex shrink-0 flex-wrap items-center justify-end gap-2 border-t px-5 py-3',
        className,
      )}
      {...props}
    />
  );
}

export {
  DetailDrawer,
  DetailDrawerTrigger,
  DetailDrawerClose,
  DetailDrawerContent,
  DetailDrawerHeader,
  DetailDrawerTitle,
  DetailDrawerDescription,
  DetailDrawerBody,
  DetailDrawerFooter,
};
