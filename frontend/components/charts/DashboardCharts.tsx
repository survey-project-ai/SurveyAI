"use client";

import {
  CartesianGrid,
  Line,
  LineChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";

type DataPoint = { label: string; value: number };

const TOOLTIP_STYLE = {
  backgroundColor: "#0f1b2a",
  border: "1px solid rgba(138, 164, 196, 0.2)",
  borderRadius: 10,
  color: "#edf3fb",
  fontSize: 12,
  padding: "6px 10px",
};

const AXIS_TICK = { fill: "#718196", fontSize: 11 };

export function ResponseTrendChart({
  points,
  totalLabel,
}: {
  points: DataPoint[];
  totalLabel: string;
}) {
  return (
    <div className="ops-dashboard-line-chart">
      <div className="ops-dashboard-line-meta">
        <span>Son 7 gün</span>
        <strong>{totalLabel}</strong>
      </div>
      <ResponsiveContainer width="100%" height={96}>
        <LineChart data={points} margin={{ top: 4, right: 12, bottom: 0, left: -28 }}>
          <CartesianGrid strokeDasharray="3 3" stroke="rgba(138,164,196,0.10)" vertical={false} />
          <XAxis dataKey="label" tick={AXIS_TICK} axisLine={false} tickLine={false} interval={0} />
          <YAxis tick={AXIS_TICK} axisLine={false} tickLine={false} allowDecimals={false} domain={[0, "auto"]} />
          <Tooltip
            contentStyle={TOOLTIP_STYLE}
            formatter={(v) => [v ?? 0, "Yanıt"]}
            cursor={{ stroke: "rgba(138,180,230,0.2)", strokeWidth: 1 }}
          />
          <Line
            type="monotone"
            dataKey="value"
            stroke="#8ab4e6"
            strokeWidth={2}
            dot={{ r: 3, fill: "#8ab4e6", strokeWidth: 0 }}
            activeDot={{ r: 5, fill: "#b9d7fb", strokeWidth: 0 }}
          />
        </LineChart>
      </ResponsiveContainer>
    </div>
  );
}
