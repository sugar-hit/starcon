import * as React from 'react';
import { cn } from '../../lib/utils';

export type InputProps = React.InputHTMLAttributes<HTMLInputElement>;

export const Input = React.forwardRef<HTMLInputElement, InputProps>(
  ({ className, type, ...props }, ref) => {
    return (
      <input
        ref={ref}
        type={type}
        className={cn(
          'border-control-border bg-card text-copy-14 text-foreground duration-base ease-out-expo flex h-9 w-full rounded-md border px-3 py-1 transition-colors',
          'placeholder:text-muted-foreground',
          'focus:border-brand focus-visible:outline-brand aria-[invalid=true]:border-destructive focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2',
          'disabled:cursor-not-allowed disabled:opacity-50',
          className,
        )}
        {...props}
      />
    );
  },
);
Input.displayName = 'Input';
