import * as React from 'react';
import { cn } from '../../lib/utils';

interface FormFieldProps {
  id: string;
  label: string;
  hint?: string;
  error?: string | undefined;
  required?: boolean;
  children: React.ReactElement<React.InputHTMLAttributes<HTMLInputElement>>;
}
export function FormField({ id, label, hint, error, required, children }: FormFieldProps) {
  const description = [hint ? `${id}-hint` : '', error ? `${id}-error` : '']
    .filter(Boolean)
    .join(' ');
  return (
    <div className="space-y-2">
      <label htmlFor={id} className="text-label-13 text-foreground block">
        {label}
        {required && (
          <span className="text-destructive ml-1" aria-hidden="true">
            *
          </span>
        )}
      </label>
      {React.cloneElement(children, {
        id,
        required,
        'aria-invalid': Boolean(error),
        'aria-describedby': description || undefined,
      })}
      {hint && (
        <p id={`${id}-hint`} className="text-copy-12 text-muted-foreground">
          {hint}
        </p>
      )}
      {error && (
        <p id={`${id}-error`} role="alert" className="text-copy-12 text-destructive">
          {error}
        </p>
      )}
    </div>
  );
}
export function Notice({
  tone = 'info',
  title,
  children,
  action,
}: {
  tone?: 'info' | 'success' | 'warning' | 'danger';
  title: string;
  children?: React.ReactNode;
  action?: React.ReactNode;
}) {
  const colors = {
    info: 'bg-info-soft text-info',
    success: 'bg-success-soft text-success',
    warning: 'bg-warning-soft text-warning',
    danger: 'bg-destructive-soft text-destructive',
  };
  return (
    <div
      role={tone === 'danger' ? 'alert' : 'status'}
      className={cn(
        'border-current/10 flex flex-wrap items-start justify-between gap-3 rounded-md border px-4 py-3',
        colors[tone],
      )}
    >
      <div className="min-w-0 flex-1">
        <p className="text-label-13 font-semibold">{title}</p>
        {children && <div className="text-copy-13 mt-1">{children}</div>}
      </div>
      {action}
    </div>
  );
}
