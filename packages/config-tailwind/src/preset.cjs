/**
 * starcon Tailwind preset
 * 单一真相源 — 设计令牌来自 docs/design/visual-spec.md
 * 任何视觉调整先改 visual-spec.md,再改这里,最后改代码
 */

/** @type {import('tailwindcss').Config} */
module.exports = {
  darkMode: ['class'],
  theme: {
    container: {
      center: true,
      padding: '1rem',
    },
    extend: {
      colors: {
        border: 'hsl(var(--border))',
        'border-strong': 'hsl(var(--border-strong))',
        input: 'hsl(var(--border))',
        ring: 'hsl(var(--ring))',
        background: 'hsl(var(--background))',
        foreground: 'hsl(var(--foreground))',
        primary: {
          DEFAULT: 'hsl(var(--primary))',
          foreground: 'hsl(var(--primary-foreground))',
          soft: 'hsl(var(--primary-soft))',
        },
        secondary: {
          DEFAULT: 'hsl(var(--muted))',
          foreground: 'hsl(var(--foreground))',
        },
        muted: {
          DEFAULT: 'hsl(var(--muted))',
          foreground: 'hsl(var(--muted-foreground))',
        },
        destructive: {
          DEFAULT: 'hsl(var(--destructive))',
          foreground: '#ffffff',
        },
        success: {
          DEFAULT: 'hsl(var(--success))',
          foreground: '#ffffff',
        },
        warning: {
          DEFAULT: 'hsl(var(--warning))',
          foreground: '#ffffff',
        },
        brand: {
          DEFAULT: 'hsl(var(--brand))',
          soft: 'hsl(var(--brand-soft))',
        },
        card: {
          DEFAULT: 'hsl(var(--card))',
          foreground: 'hsl(var(--foreground))',
        },
        popover: {
          DEFAULT: 'hsl(var(--card))',
          foreground: 'hsl(var(--foreground))',
        },
        sidebar: {
          DEFAULT: 'hsl(var(--background))',
          foreground: 'hsl(var(--foreground))',
          border: 'hsl(var(--border))',
          accent: 'hsl(var(--brand-soft))',
          'accent-foreground': 'hsl(var(--brand))',
          primary: 'hsl(var(--brand))',
        },
        // Vercel-style 10-step gray
        gray: {
          0: '#FAFAFA',
          1: '#F5F5F5',
          2: '#EBEBEB',
          3: '#D1D1D1',
          4: '#A3A3A3',
          5: '#707070',
          6: '#484848',
          7: '#222222',
          8: '#0A0A0A',
          9: '#000000',
        },
      },
      borderRadius: {
        sm: '4px',
        DEFAULT: '6px',
        md: '6px',
        lg: '8px',
        xl: '12px',
        '2xl': '16px',
        '3xl': '24px',
      },
      boxShadow: {
        none: 'none',
        xs: '0 1px 1px 0 rgba(0,0,0,0.04)',
        sm: '0 1px 2px 0 rgba(0,0,0,0.06)',
        soft: '0 1px 2px 0 rgba(0,0,0,0.04), 0 1px 3px 0 rgba(0,0,0,0.04)',
        elevated: '0 4px 12px -2px rgba(0,0,0,0.08), 0 2px 4px -2px rgba(0,0,0,0.04)',
        ring: '0 0 0 1px rgba(0,0,0,0.06)',
        'ring-strong': '0 0 0 1px rgba(0,0,0,0.12)',
        focus: '0 0 0 2px rgba(0,0,0,0.10)',
      },
      fontFamily: {
        sans: [
          'var(--font-inter)',
          'Inter',
          '-apple-system',
          'BlinkMacSystemFont',
          'PingFang SC',
          'Microsoft YaHei',
          'sans-serif',
        ],
        mono: [
          'JetBrains Mono',
          'SFMono-Regular',
          'Menlo',
          'Monaco',
          'Consolas',
          'monospace',
        ],
      },
      // 14 档字号阶梯 — 禁止用 Tailwind 默认 text-sm/base/lg
      fontSize: {
        'h-72': ['72px', { lineHeight: '1.1', fontWeight: '500', letterSpacing: '-0.02em' }],
        'h-56': ['56px', { lineHeight: '1.15', fontWeight: '500', letterSpacing: '-0.02em' }],
        'h-48': ['48px', { lineHeight: '1.15', fontWeight: '500', letterSpacing: '-0.02em' }],
        'h-40': ['40px', { lineHeight: '1.2',  fontWeight: '500', letterSpacing: '-0.02em' }],
        'h-32': ['32px', { lineHeight: '1.2',  fontWeight: '500', letterSpacing: '-0.02em' }],
        'h-24': ['24px', { lineHeight: '1.3',  fontWeight: '500', letterSpacing: '-0.02em' }],
        'h-20': ['20px', { lineHeight: '1.4',  fontWeight: '500', letterSpacing: '-0.02em' }],
        'h-16': ['16px', { lineHeight: '1.5',  fontWeight: '500', letterSpacing: '-0.02em' }],
        'h-14': ['14px', { lineHeight: '1.5',  fontWeight: '500', letterSpacing: '-0.02em' }],
        'copy-24': ['24px', { lineHeight: '1.4' }],
        'copy-20': ['20px', { lineHeight: '1.5' }],
        'copy-18': ['18px', { lineHeight: '1.55' }],
        'copy-16': ['16px', { lineHeight: '1.6' }],
        'copy-14': ['14px', { lineHeight: '1.55' }],
        'copy-13': ['13px', { lineHeight: '1.55' }],
        'label-14': ['14px', { lineHeight: '1.4', fontWeight: '500' }],
        'label-13': ['13px', { lineHeight: '1.4', fontWeight: '500' }],
        'label-12': ['12px', { lineHeight: '1.4', fontWeight: '500', letterSpacing: '0.04em' }],
        'btn-14': ['14px', { lineHeight: '1.4', fontWeight: '500' }],
      },
      transitionDuration: {
        fast: '80ms',
        base: '150ms',
        slow: '250ms',
      },
      transitionTimingFunction: {
        'out-expo': 'cubic-bezier(0.16, 1, 0.3, 1)',
      },
      keyframes: {
        'fade-in': {
          from: { opacity: '0' },
          to: { opacity: '1' },
        },
        'fade-up': {
          from: { opacity: '0', transform: 'translateY(4px)' },
          to: { opacity: '1', transform: 'translateY(0)' },
        },
        'scale-in': {
          from: { opacity: '0', transform: 'scale(0.96)' },
          to: { opacity: '1', transform: 'scale(1)' },
        },
        'slide-in-right': {
          from: { transform: 'translateX(100%)' },
          to: { transform: 'translateX(0)' },
        },
        'slide-in-left': {
          from: { transform: 'translateX(-100%)' },
          to: { transform: 'translateX(0)' },
        },
        'accordion-down': {
          from: { height: '0' },
          to: { height: 'var(--radix-accordion-content-height)' },
        },
        'accordion-up': {
          from: { height: 'var(--radix-accordion-content-height)' },
          to: { height: '0' },
        },
      },
      animation: {
        'fade-in': 'fade-in 150ms ease-out-expo',
        'fade-up': 'fade-up 240ms ease-out-expo',
        'scale-in': 'scale-in 200ms ease-out-expo',
        'slide-in-right': 'slide-in-right 250ms ease-out-expo',
        'slide-in-left': 'slide-in-left 250ms ease-out-expo',
        'accordion-down': 'accordion-down 200ms ease-out-expo',
        'accordion-up': 'accordion-up 200ms ease-out-expo',
      },
    },
  },
  plugins: [require('tailwindcss-animate')],
};