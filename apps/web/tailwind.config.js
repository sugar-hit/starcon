/** @type {import('tailwindcss').Config} */
module.exports = {
  presets: [require('@starcon/ui/tailwind-preset')],
  content: [
    './src/**/*.{ts,tsx}',
    './src/app/**/*.{ts,tsx}',
    '../../packages/ui/src/**/*.{ts,tsx}',
  ],
};
