import { type ReactNode, useEffect, useRef } from "react";
import * as echarts from "echarts";

/**
 * The one place ECharts touches the DOM (ADR-009 §5): a lazy-friendly canvas that
 * owns the chart lifecycle — init once per mount, setOption on every change,
 * dispose on unmount. ChartWidget maps the report run's §4 chart projection onto
 * the option here; the wrapper never knows about reports.
 */
export function EChartCanvas(props: {
  option: echarts.EChartsCoreOption;
  height?: number;
  ariaLabel: string;
}): ReactNode {
  const host = useRef<HTMLDivElement | null>(null);
  const chart = useRef<echarts.ECharts | null>(null);

  useEffect(() => {
    if (!host.current) {
      return;
    }
    chart.current = echarts.init(host.current, null, { renderer: "svg" });
    return () => {
      chart.current?.dispose();
      chart.current = null;
    };
  }, []);

  useEffect(() => {
    chart.current?.setOption(props.option);
  }, [props.option]);

  return (
    <div
      ref={host}
      role="img"
      aria-label={props.ariaLabel}
      style={{ width: "100%", height: props.height ? `${props.height}px` : "260px" }}
    />
  );
}

export default EChartCanvas;
