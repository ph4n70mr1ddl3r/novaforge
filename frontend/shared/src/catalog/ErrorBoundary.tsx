import { Component, type ErrorInfo, type ReactNode } from "react";

/**
 * The last-resort render boundary: a render-time fault in any widget used to
 * unmount the entire SPA (no boundary existed anywhere — one malformed value
 * yielded a white screen and lost every unsaved form). The boundary contains the
 * blast radius to the faulting subtree and offers a retry.
 */
export class ErrorBoundary extends Component<
    { children?: ReactNode; label?: string },
    { message: string | null }
> {
    state: { message: string | null } = { message: null };

    static getDerivedStateFromError(error: unknown): { message: string | null } {
        return { message: error instanceof Error ? error.message : String(error) };
    }

    componentDidCatch(error: unknown, info: ErrorInfo): void {
        // the console keeps the stack for diagnostics; the user sees the containment
        console.error("render boundary contained a fault", error, info.componentStack);
    }

    render(): ReactNode {
        if (this.state.message !== null) {
            return (
                <div className="nf-error-boundary" role="alert">
                    <p>
                        {this.props.label ?? "This section"} failed to render:
                        {" "}{this.state.message}
                    </p>
                    <button type="button" onClick={() => this.setState({ message: null })}>
                        Try again
                    </button>
                </div>
            );
        }
        return this.props.children;
    }
}
