import { type ReactNode, useEffect, useRef } from "react";
import type * as echarts from "echarts";

/**
 * The one place ECharts touches the DOM (ADR-009 §5): a lazy-friendly canvas that
 * owns the chart lifecycle — init once per mount, setOption on every change,
 * dispose on unmount. ChartWidget maps the report run's §4 chart projection onto
 * the option here; the wrapper never knows about reports.
 *
 * ECharts loads dynamically: it is chart-page-only weight, and the static import
 * welded its ~1 MB into every app's entry chunk. The latest option rides a ref so
 * an option updated while the module streams in applies at init — no update is
 * lost to the load race.
 */
export function EChartCanvas(props: {
  option: echarts.EChartsCoreOption;
  height?: number;
  ariaLabel: string;
}): ReactNode {
  const host = useRef<HTMLDivElement | null>(null);
  const chart = useRef<echarts.ECharts | null>(null);
  const option = useRef<echarts.EChartsCoreOption>(props.option);

  useEffect(() => {
    option.current = props.option;
    chart.current?.setOption(props.option);
  }, [props.option]);

  useEffect(() => {
    let disposed = false;
    void import("echarts").then((echartsModule) => {
      if (disposed || !host.current) {
        return;
      }
      const instance = echartsModule.init(host.current, null, { renderer: "svg" });
      instance.setOption(option.current);
      chart.current = instance;
    });
    return () => {
      disposed = true;
      chart.current?.dispose();
      chart.current = null;
    };
  }, []);

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
