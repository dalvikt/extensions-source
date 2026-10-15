// Reports how many pages reader-pages.js has fetched, or hands back one page (and releases it).
(index) => {
    const pages = window.pamPages;
    if (!pages) return { ready: 0, total: -1, error: window.pamError ?? null };
    if (index < 0) {
        return { ready: pages.filter((p) => p !== null).length, total: pages.length, error: window.pamError ?? null };
    }
    const page = pages[index];
    pages[index] = null;
    return page;
}
