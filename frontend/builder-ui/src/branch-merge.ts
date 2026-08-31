/**
 * Concurrent-edit merge for whole-branch saves (the dashboards rule, generalized):
 * the editor's intent stands for every key its mount snapshot knew — additions,
 * updates, AND deletions — while entries another tab added after this editor
 * mounted are preserved. Saving an array built from the mount-time snapshot alone
 * silently deleted every concurrent addition.
 */
export function mergeBranch<T>(
    edited: T[] | undefined,
    mounted: T[] | undefined,
    fresh: T[] | undefined,
    keyOf: (item: T) => string,
): T[] {
    const editedKeys = new Set((edited ?? []).map(keyOf));
    const mountKeys = new Set((mounted ?? []).map(keyOf));
    // unknown to this editor's mount AND untouched by its intent: a concurrent
    // addition — keep it
    const foreign = (fresh ?? []).filter(
        (item) => !mountKeys.has(keyOf(item)) && !editedKeys.has(keyOf(item)),
    );
    return [...(edited ?? []), ...foreign];
}
