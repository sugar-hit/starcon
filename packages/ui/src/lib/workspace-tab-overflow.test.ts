import { describe, expect, it } from 'vitest';
import { getWorkspaceTabOverflow } from './workspace-tab-overflow';

describe('getWorkspaceTabOverflow', () => {
  it('reports no overflow when every tab fits', () => {
    expect(getWorkspaceTabOverflow(0, 600, 600)).toEqual({
      canScrollBack: false,
      canScrollForward: false,
    });
  });

  it('reports the remaining direction at either edge', () => {
    expect(getWorkspaceTabOverflow(0, 900, 600)).toEqual({
      canScrollBack: false,
      canScrollForward: true,
    });
    expect(getWorkspaceTabOverflow(300, 900, 600)).toEqual({
      canScrollBack: true,
      canScrollForward: false,
    });
  });

  it('keeps both overflow hints visible in the middle', () => {
    expect(getWorkspaceTabOverflow(120.5, 900, 600)).toEqual({
      canScrollBack: true,
      canScrollForward: true,
    });
  });

  it('ignores sub-pixel drift at the edges', () => {
    expect(getWorkspaceTabOverflow(0.5, 900, 600)).toEqual({
      canScrollBack: false,
      canScrollForward: true,
    });
    expect(getWorkspaceTabOverflow(299.5, 900, 600)).toEqual({
      canScrollBack: true,
      canScrollForward: false,
    });
  });
});
