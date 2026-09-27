'use client';

import * as React from 'react';
import * as TabsPrimitive from '@radix-ui/react-tabs';
import { cn } from '../../lib/utils';

/**
 * Tabs — Radix 包装
 * 选中 trigger 底部 2px border-primary + text-primary font-semibold
 * 未选中 text-muted-foreground
 */
const Tabs = TabsPrimitive.Root;

const TabsList = React.forwardRef<
  React.ElementRef<typeof TabsPrimitive.List>,
  React.ComponentPropsWithoutRef<typeof TabsPrimitive.List>
>(({ className, ...props }, ref) => (
  <TabsPrimitive.List
    ref={ref}
    className={cn('border-border inline-flex items-center gap-6 border-b', className)}
    {...props}
  />
));
TabsList.displayName = TabsPrimitive.List.displayName;

const TabsTrigger = React.forwardRef<
  React.ElementRef<typeof TabsPrimitive.Trigger>,
  React.ComponentPropsWithoutRef<typeof TabsPrimitive.Trigger>
>(({ className, ...props }, ref) => (
  <TabsPrimitive.Trigger
    ref={ref}
    className={cn(
      'text-label-14 text-muted-foreground duration-base ease-out-expo hover:text-foreground focus-visible:outline-brand data-[state=active]:border-primary data-[state=active]:text-primary relative -mb-px inline-flex items-center justify-center whitespace-nowrap border-b-2 border-transparent py-3 transition-colors focus-visible:outline focus-visible:outline-2 disabled:pointer-events-none disabled:opacity-50 data-[state=active]:font-semibold',
      className,
    )}
    {...props}
  />
));
TabsTrigger.displayName = TabsPrimitive.Trigger.displayName;

const TabsContent = React.forwardRef<
  React.ElementRef<typeof TabsPrimitive.Content>,
  React.ComponentPropsWithoutRef<typeof TabsPrimitive.Content>
>(({ className, ...props }, ref) => (
  <TabsPrimitive.Content
    ref={ref}
    className={cn(
      'animate-fade-up focus-visible:outline-brand mt-4 focus-visible:outline focus-visible:outline-2',
      className,
    )}
    {...props}
  />
));
TabsContent.displayName = TabsPrimitive.Content.displayName;

export { Tabs, TabsList, TabsTrigger, TabsContent };
