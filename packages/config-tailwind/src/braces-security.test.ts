import { createRequire } from 'node:module';
import { describe, expect, it } from 'vitest';

const require = createRequire(import.meta.url);
const braces = require('braces') as {
  (pattern: string): string[];
  parse(pattern: string): unknown;
  compile(ast: unknown): string;
  expand(pattern: unknown): string[];
  stringify(ast: unknown): string;
};

describe('patched braces resource limits (CVE-2026-93687)', () => {
  it('keeps ordinary build patterns working', () => {
    expect(braces.expand('{src,test}/{a,b}.ts')).toEqual([
      'src/a.ts',
      'src/b.ts',
      'test/a.ts',
      'test/b.ts',
    ]);
    expect(braces.expand('{1..3}')).toEqual(['1', '2', '3']);
  });

  it('rejects deeply nested braces and parentheses before recursive AST traversal', () => {
    for (const [open, close] of [
      ['{', '}'],
      ['(', ')'],
    ] as const) {
      const pattern = open.repeat(4000) + 'x' + close.repeat(4000);
      expect(() => braces.parse(pattern)).toThrow(/maximum depth of 128/);
      expect(() => braces(pattern)).toThrow(/maximum depth of 128/);
    }
  });

  it('protects direct callers of AST walkers as well as string parsing', () => {
    let ast: unknown = { type: 'text', value: 'x' };
    for (let i = 0; i < 4000; i++) ast = { type: 'root', nodes: [ast] };
    expect(() => braces.compile(ast)).toThrow(/maximum depth of 128/);
    expect(() => braces.expand(ast)).toThrow(/maximum depth of 128/);
    expect(() => braces.stringify(ast)).toThrow(/maximum depth of 128/);
  });
});
