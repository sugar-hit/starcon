'use client';

import {
  Bar,
  BarChart,
  CartesianGrid,
  Legend,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts';

const data = [
  { month: '1 月', 营收: 1840, 目标: 1500 },
  { month: '2 月', 营收: 1620, 目标: 1500 },
  { month: '3 月', 营收: 2150, 目标: 1800 },
  { month: '4 月', 营收: 2380, 目标: 1800 },
  { month: '5 月', 营收: 2010, 目标: 2000 },
  { month: '6 月', 营收: 2680, 目标: 2000 },
  { month: '7 月', 营收: 2950, 目标: 2200 },
  { month: '8 月', 营收: 3120, 目标: 2200 },
];

export function BarRevenueChart() {
  return (
    <div className="h-72 w-full">
      <ResponsiveContainer width="100%" height="100%">
        <BarChart data={data} margin={{ top: 16, right: 24, bottom: 8, left: -8 }} barCategoryGap="22%">
          <CartesianGrid stroke="#e2e8f0" strokeDasharray="3 3" vertical={false} />
          <XAxis
            dataKey="month"
            tick={{ fill: '#94a3b8', fontSize: 11 }}
            tickLine={false}
            axisLine={false}
          />
          <YAxis
            tick={{ fill: '#94a3b8', fontSize: 11 }}
            tickLine={false}
            axisLine={false}
            tickFormatter={(v) => `${v}`}
          />
          <Tooltip
            cursor={{ fill: 'rgba(0,0,0,0.02)' }}
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
          <Bar
            dataKey="目标"
            fill="#cbd5e1"
            radius={[6, 6, 0, 0]}
            isAnimationActive={false}
          />
          <Bar dataKey="营收" fill="hsl(220, 70%, 45%)" radius={[6, 6, 0, 0]} />
        </BarChart>
      </ResponsiveContainer>
    </div>
  );
}