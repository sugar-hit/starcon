export const WORLD_CITIES = [
  { city: '纽约', country: '美国', zone: 'America/New_York' },
  { city: '伦敦', country: '英国', zone: 'Europe/London' },
  { city: '东京', country: '日本', zone: 'Asia/Tokyo' },
  { city: '伊斯坦布尔', country: '土耳其', zone: 'Europe/Istanbul' },
  { city: '罗马', country: '意大利', zone: 'Europe/Rome' },
];
export function clockParts(date: Date, timeZone: string) {
  return {
    time: new Intl.DateTimeFormat('en-GB', {
      timeZone,
      hour: '2-digit',
      minute: '2-digit',
      hourCycle: 'h23',
    }).format(date),
    date: new Intl.DateTimeFormat('zh-CN', { timeZone, month: '2-digit', day: '2-digit' }).format(
      date,
    ),
    offset:
      new Intl.DateTimeFormat('en', { timeZone, timeZoneName: 'shortOffset' })
        .formatToParts(date)
        .find((part) => part.type === 'timeZoneName')?.value ?? '',
  };
}
