import { useEffect, useRef, useState, type ReactNode } from "react";

/**
 * A JSON text input that keeps typing. The text is LOCAL state; a parseable edit
 * commits to the model, an incomplete literal (`{`, an open quote) simply doesn't
 * commit yet. Re-serializing the model straight into a controlled input — the
 * older pattern — rejected the keystroke, so valid JSON could never be typed
 * character by character, only pasted.
 */
export function JsonTextField(props: {
    "aria-label": string;
    placeholder: string;
    value: Record<string, unknown> | undefined;
    onParsed: (parsed: Record<string, unknown> | undefined) => void;
}): ReactNode {
    const seed =
        props.value && Object.keys(props.value).length > 0 ? JSON.stringify(props.value) : "";
    const [text, setText] = useState(seed);
    // The model's serialization the last time WE emitted it: an external model
    // change (a reload, a rebase) re-seeds the text; our own commit must not.
    const lastEmitted = useRef(seed);
    useEffect(() => {
        if (seed !== lastEmitted.current) {
            setText(seed);
            lastEmitted.current = seed;
        }
    }, [seed]);
    return (
        <input
            aria-label={props["aria-label"]}
            placeholder={props.placeholder}
            value={text}
            onChange={(event) => {
                const next = event.target.value;
                setText(next);
                const trimmed = next.trim();
                if (!trimmed) {
                    lastEmitted.current = "";
                    props.onParsed(undefined);
                    return;
                }
                try {
                    const parsed = JSON.parse(trimmed) as Record<string, unknown>;
                    lastEmitted.current = JSON.stringify(parsed);
                    props.onParsed(parsed);
                } catch {
                    // keep typing — an incomplete literal doesn't commit yet
                }
            }}
        />
    );
}
