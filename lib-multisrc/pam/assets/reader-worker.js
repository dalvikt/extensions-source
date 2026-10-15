// Served in place of the site reader's decrypt worker. Imports the real worker first, then reads
// the page content key, base and page-ticket header the worker's own code produces. The worker's
// own page fetches are stubbed; the pages are fetched from the site's reader page instead.
import "__REAL_WORKER__";

(() => {
    const sentinel = "__CAPTURE_PATH__";
    const realFetch = self.fetch.bind(self);
    const nativeImportKey = crypto.subtle.importKey;

    let contentKey = null;
    let page = null;
    let sent = false;

    const send = () => {
        if (sent || contentKey === null || page === null) return;
        sent = true;
        const payload = JSON.stringify({ k: contentKey, base: page.base, width: page.width, headers: page.headers });
        realFetch(sentinel + encodeURIComponent(payload)).catch(() => {});
    };

    // The content key is imported raw as HKDF, once, before any page is decrypted.
    crypto.subtle.importKey = function (format, keyData, algorithm) {
        const name = algorithm && (algorithm.name || algorithm);
        if (contentKey === null && format === "raw" && name === "HKDF") {
            try {
                const view = keyData instanceof ArrayBuffer
                    ? new Uint8Array(keyData)
                    : new Uint8Array(keyData.buffer, keyData.byteOffset || 0, keyData.byteLength);
                contentKey = btoa(String.fromCharCode(...view));
            } catch (e) {
                contentKey = "";
            }
            send();
        }
        return nativeImportKey.apply(crypto.subtle, arguments);
    };

    // The first page the worker fetches carries the base and the page-ticket header. The fetch itself
    // never resolves, so the worker loads no pages and the reader never refreshes the manifest.
    self.fetch = function (input, init) {
        try {
            const url = typeof input === "string" ? input : (input && input.url) || "";
            const match = url.match(/\/p\/[^?#]*\/\d+-(\d+)\.ece(?:[?#]|$)/);
            if (match) {
                if (page === null) {
                    const path = url.replace(/^[a-z]+:\/\/[^/]+/, "");
                    const headers = {};
                    const source = (init && init.headers) || (input && input.headers);
                    if (source) {
                        if (typeof source.forEach === "function") {
                            source.forEach((value, key) => { headers[key] = value; });
                        } else {
                            for (const key of Object.keys(source)) headers[key] = source[key];
                        }
                    }
                    page = { base: path.slice(0, path.lastIndexOf("/") + 1), width: Number(match[1]), headers };
                    send();
                }
                return new Promise(() => {});
            }
        } catch (e) { /* fall through to the real fetch */ }
        return realFetch.apply(self, arguments);
    };
})();
