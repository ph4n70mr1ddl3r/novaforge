import { describe, expect, it, vi } from "vitest";

/**
 * ChartWidget's wiring contract (PHASE-5 §5): the §4 chart projection maps onto
 * the ECharts option — first column the category axis, every later column a
 * series — without ChartWidget touching ECharts' DOM itself (EChartCanvas owns
 * that, mocked here; the real canvas is browser scope, covered by the Phase 2
 * Playwright stack per spec §2).
 */
vi.mock("echarts", () => {
  const setOption = vi.fn();
  const instance = { setOption, dispose: vi.fn() };
  const init = vi.fn(() => instance);
  return {
    __instance: instance,
    init,
    setOption,
  };
});

import { render, waitFor } from "@testing-library/react";
import { ChartWidget } from "../src/catalog/ChartWidget.tsx";
import * as echarts from "echarts";

const chart = {
  xAxis: { data: ["acme", "globex"] },
  series: [
    { name: "due_date", data: ["0-30", "31-60"] },
    { name: "outstanding", data: [200, 50.25] },
  ],
};

describe("ChartWidget", () => {
  it("maps the chart projection onto the ECharts option", async () => {
    render(<ChartWidget reportRef="arAging" chart={chart} title="A/R Aging" />);
    await waitFor(() => expect(echarts.init).toHaveBeenCalled());
    const instance = (echarts as unknown as { __instance: { setOption: ReturnType<typeof vi.fn> } }).__instance;
    const option = instance.setOption.mock.calls[0]![0] as Record<string, unknown>;
    expect(option.xAxis).toEqual({ type: "category", data: ["acme", "globex"] });
    expect(option.series).toEqual([
      { name: "due_date", type: "bar", data: ["0-30", "31-60"] },
      { name: "outstanding", type: "bar", data: [200, 50.25] },
    ]);
  });

  it("labels the chart region for screen readers", async () => {
    const { container } = render(
      <ChartWidget reportRef="arAging" chart={chart} kind="line" />,
    );
    await waitFor(() => expect(echarts.init).toHaveBeenCalled());
    const region = container.querySelector('[role="img"]');
    expect(region?.getAttribute("aria-label")).toContain("2 series");
    expect(region?.getAttribute("aria-label")).toContain("acme");
  });
});
