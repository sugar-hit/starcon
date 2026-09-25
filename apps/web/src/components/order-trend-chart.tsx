'use client';

import {
  Area,
  AreaChart,
  CartesianGrid,
  Legend,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts';

const data = [
  { day: '周一', 本周: 1240, 上周: 980 },
  { day: '周二', 本周: 1380, 上周: 1120 },
  { day: '周三', 本周: 1560, 上周: 1240 },
  { day: '周四', 本周: 1490, 上周: 1180 },
  { day: '周五', 本周: 1820, 上周: 1390 },
  { day: '周六', 本周: 2150, 上周: 1640 },
  { day: '周日', 本周: 2430, 上周: 1820 },
];

export function OrderTrendChart() {
  return (
    <div className="h-72 w-full">
      <ResponsiveContainer width="100%" height="100%">
        <AreaChart data={data} margin={{ top: 16, right: 24, bottom: 8, left: -8 }}>
          <defs>
            <linearGradient id="grad-primary" x1="0" y1="0" x2="0" y2="1">
              <stop offset="0%" stopColor="hsl(220, 70%, 45%)" stopOpacity={0.28} />
              <stop offset="100%" stopColor="hsl(220, 70%, 45%)" stopOpacity={0} />
            </linearGradient>
            <linearGradient id="grad-compare" x1="0" y1="0" x2="0" y2="1">
              <stop offset="0%" stopColor="#cbd5e1" stopOpacity={0.28} />
              <stop offset="100%" stopColor="#cbd5e1" stopOpacity={0} />
            </linearGradient>
          </defs>
          <CartesianGrid
            stroke="#e2e8f0"
            strokeDasharray="3 3"
            vertical={false}
          />
          <XAxis
            dataKey="day"
            tick={{ fill: '#94a3b8', fontSize: 11 }}
            tickLine={false}
            axisLine={false}
          />
          <YAxis
            tick={{ fill: '#94a3b8', fontSize: 11 }}
            tickLine={false}
            axisLine={false}
            tickFormatter={(v) => `${v / 1000}k`}
          />
          <Tooltip
            contentStyle={{
              borderRadius: 8,
              border: '1px solid #e2e8f0',
              fontSize: 13,
              boxShadow: '0 4px 12px -2px rgba(0,0,0,0.08)',
            }}
          />
          <Legend
            wrapperStyle={{ fontSize: 12, paddingTop: 8 }}
            iconType="circle"
            iconSize={8}
          />
          <Area
            type="monotone"
            dataKey="上周"
            stroke="#cbd5e1"
            strokeWidth={1.5}
            fill="url(#grad-compare)"
            isAnimationActive={false}
          />
          <Area
            type="monotone"
            dataKey="本周"
            stroke="hsl(220, 70%, 45%)"
            strokeWidth={2}
            fill="url(#grad-primary)"
          />
        </AreaChart>
      </ResponsiveContainer>
    </div>
  );
}