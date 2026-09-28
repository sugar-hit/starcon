/** Return paths are untrusted query-string input, never an external navigation target. */
export function loginDestination(value: string | null): string {
  if (!value || !value.startsWith('/') || value.startsWith('//') || /[\\\s]/.test(value))
    return '/';
  const destination = new URL(value, 'https://starcon.invalid');
  if (destination.origin !== 'https://starcon.invalid' || destination.pathname.startsWith('/login'))
    return '/';
  return `${destination.pathname}${destination.search}${destination.hash}`;
}
