// The WebView runs detached, so its document reports visibilityState "hidden"; the site's reader waits
// for "visible" before loading, and Cloudflare's managed challenge will not pass a hidden document.
() => {
    const define = (prop, value) => {
        try {
            Object.defineProperty(document, prop, { configurable: true, get: () => value });
        } catch (e) { /* already defined */ }
    };
    define("visibilityState", "visible");
    define("hidden", false);
    define("webkitVisibilityState", "visible");
    define("webkitHidden", false);
    try {
        document.dispatchEvent(new Event("visibilitychange"));
    } catch (e) { /* no document yet */ }
}
