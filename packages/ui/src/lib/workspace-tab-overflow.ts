export interface WorkspaceTabOverflow {
  canScrollBack: boolean;
  canScrollForward: boolean;
}

const OVERFLOW_TOLERANCE_PX = 1;

/** Keeps overflow affordances stable around fractional browser scroll positions. */
export function getWorkspaceTabOverflow(
  scrollLeft: number,
  scrollWidth: number,
  clientWidth: number,
): WorkspaceTabOverflow {
  const maxScrollLeft = Math.max(0, scrollWidth - clientWidth);

  return {
    canScrollBack: scrollLeft > OVERFLOW_TOLERANCE_PX,
    canScrollForward: scrollLeft < maxScrollLeft - OVERFLOW_TOLERANCE_PX,
  };
}
