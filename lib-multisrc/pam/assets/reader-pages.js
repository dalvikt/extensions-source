// Fetches the chapter's encrypted pages from the reader page, as the site's reader does, and keeps
// each one until the extension drains it. Returns "running"; progress is read back with reader-drain.js.
(base, width, count, headers) => {
    if (window.pamPages) return "running";

    const pages = new Array(count).fill(null);
    window.pamPages = pages;
    window.pamError = null;

    const toBase64 = (buffer) => {
        const bytes = new Uint8Array(buffer);
        let binary = "";
        for (let i = 0; i < bytes.length; i += 0x8000) {
            binary += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
        }
        return btoa(binary);
    };

    let next = 0;
    const fetchNext = async () => {
        while (window.pamError === null) {
            const index = next++;
            if (index >= count) return;
            try {
                const response = await fetch(`${base}${index + 1}-${width}.ece`, { credentials: "omit", headers });
                if (!response.ok) throw new Error(`HTTP error ${response.status}`);
                pages[index] = toBase64(await response.arrayBuffer());
            } catch (e) {
                window.pamError = String((e && e.message) || e);
                return;
            }
        }
    };

    for (let worker = 0; worker < Math.min(3, count); worker++) fetchNext();
    return "running";
}
