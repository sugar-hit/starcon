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
      'animate-fade-in fixed inset-0 z-50 bg-black/40 backdrop-blur-sm',
      className,
    )}
    {...props}
  />
));
DetailDrawerOverlay.displayName = 'DetailDrawerOverlay';

const DetailDrawerContent = React.forwardRef<
  React.ElementRef<typeof DialogPrimitive.Content>,
  React.ComponentPropsWithoutRef<typeof DialogPrimitive.Content>
>(({ className, children, ...props }, ref) => (
  <DialogPrimitive.Portal>
    <DetailDrawerOverlay />
    <DialogPrimitive.Content
      ref={ref}
      className={cn(
        // 桌面端:右侧滑出 w-[480px]
        // 移动端 (<md):全屏
        'fixed inset-y-0 right-0 z-50 flex h-full w-full flex-col border-l border-border bg-card shadow-elevated',
        'md:max-w-[480px]',
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

function DetailDrawerTitle({
  className,
  ...props
}: React.HTMLAttributes<HTMLHeadingElement>) {
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

function DetailDrawerBody({
  className,
  ...props
}: React.HTMLAttributes<HTMLDivElement>) {
  return (
    <div
      className={cn('min-h-0 flex-1 overflow-y-auto px-5 py-4', className)}
      {...props}
    />
  );
}

function DetailDrawerFooter({
  className,
  ...props
}: React.HTMLAttributes<HTMLDivElement>) {
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