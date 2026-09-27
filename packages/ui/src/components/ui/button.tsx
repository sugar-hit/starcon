import * as React from 'react';
import { Slot } from '@radix-ui/react-slot';
import { cva, type VariantProps } from 'class-variance-authority';
import { cn } from '../../lib/utils';

/**
 * Button — 主 CTA 永远是黑色（bg-primary），品牌蓝只做强调
 * 不要用 className="bg-blue-500 text-white" 拼按钮
 */
const buttonVariants = cva(
  // base 提供 font-size (text-btn-14) — 不要在 size 里重复写，
  // 否则 twMerge 会把 variant 的 text-* 颜色（如 text-primary-foreground）
  // 当作与 text-btn-14 同组冲突并覆盖掉，导致按钮看不见字。
  'inline-flex items-center justify-center gap-2 whitespace-nowrap rounded-md text-btn-14 transition-colors duration-base ease-out-expo focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 disabled:pointer-events-none disabled:opacity-50',
  {
    variants: {
      variant: {
        default: 'bg-primary text-primary-foreground hover:bg-primary/90',
        outline: 'border border-control-border bg-card hover:bg-muted',
        secondary: 'bg-muted text-foreground hover:bg-muted/80',
        ghost: 'bg-transparent hover:bg-muted',
        brand: 'bg-brand text-white hover:bg-brand/90',
        destructive: 'bg-destructive text-white hover:bg-destructive/90',
        success: 'bg-success text-white hover:bg-success/90',
        link: 'text-brand underline-offset-4 hover:underline',
      },
      size: {
        sm: 'h-8 px-3',
        default: 'h-9 px-4',
        lg: 'h-10 px-6',
        icon: 'h-9 w-9',
      },
    },
    defaultVariants: {
      variant: 'default',
      size: 'default',
    },
  },
);

export interface ButtonProps
  extends React.ButtonHTMLAttributes<HTMLButtonElement>, VariantProps<typeof buttonVariants> {
  asChild?: boolean;
}

const Button = React.forwardRef<HTMLButtonElement, ButtonProps>(
  ({ className, variant, size, asChild = false, type, ...props }, ref) => {
    const Comp = asChild ? Slot : 'button';
    return (
      <Comp
        type={asChild ? type : (type ?? 'button')}
        className={cn(buttonVariants({ variant, size, className }))}
        ref={ref}
        {...props}
      />
    );
  },
);
Button.displayName = 'Button';

export { Button, buttonVariants };
