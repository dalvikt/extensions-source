// Reads the chapter page's Inertia data and, for reader v2, waits for the reader to lay out its pages.
() => {
    if (window._cf_chl_opt) return { s: "challenge" };

    const status = performance.getEntriesByType("navigation")[0]?.responseStatus ?? 0;
    if (status >= 400 && document.readyState === "complete") return { s: "error", status };

    const app = document.getElementById("app");
    const raw = app?.dataset.page ?? document.querySelector('script[data-page="app"][type="application/json"]')?.textContent;
    if (!raw) return { s: "pending" };

    let data;
    try {
        data = JSON.parse(raw);
    } catch (e) {
        return { s: "pending" };
    }
    const props = data.props;
    if (!props || !("reader_v2" in props)) return { s: "pending" };

    if (!props.reader_v2) {
        return { s: "ready", v2: false, count: props.page_count | 0, page: data };
    }

    // Each page becomes a plate once the manifest resolves; the pending state renders childless placeholders.
    const plates = document.querySelectorAll("section.reader-plates > div[data-reader-plate]");
    if (plates.length === 0 || plates[0].children.length === 0) return { s: "pending" };
    return { s: "ready", v2: true, count: plates.length, page: data };
}
