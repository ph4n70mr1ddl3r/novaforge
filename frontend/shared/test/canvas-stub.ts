/**
 * jsdom has no canvas implementation — ECharts' chart mount logs unhandled
 * "Not implemented" errors that Vitest counts as suite errors even when every
 * assertion passes (the chart's own test stubs echarts; the registry render pin
 * mounts the real one). One suite-wide stub silences the noise without weakening
 * any assertion.
 */
if (typeof HTMLCanvasElement !== "undefined") {
    HTMLCanvasElement.prototype.getContext = (() => null) as unknown as
        HTMLCanvasElement["getContext"];
}
