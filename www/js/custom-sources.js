// ==========================================================================
// custom-sources.js
// Extracted from index.html for maintainability. Contains the community
// manga source adapters (Asura Scans, Manga Read), the window.MangaSources
// registry, and the load* dispatchers.
//
// This is a CLASSIC script (not a module). It shares the global scope with
// index.html's inline scripts and depends on globals declared earlier there
// (currentSource, MangaDexSource). It MUST stay loaded AFTER those — i.e.
// keep its <script> tag as the last one before </body>, exactly where the
// old inline block was.
// ==========================================================================

  // (currentSource is now declared near the top of the earlier <script>
  // block, since code up there reads it before this block used to run —
  // see that comment for why.)

  // ========================================================



  // ==================== ASURA SCANS SOURCE ====================
  // Ported from the real eu.kanade.tachiyomi.extension.en.asurascans.AsuraScans
  // class (replaces the earlier comicasura.net-based attempt, which was the
  // wrong site's extension entirely).
  //
  // IMPORTANT CAVEAT: JADX could not decompile pageListParse — its body is
  // literally "Method dump skipped" in the source we have. Everything else
  // below (search/popular/latest via the JSON API, manga details via the API,
  // chapter list via scraping a `props` attribute out of the comic's HTML
  // page) is ported directly from real decompiled logic and should be
  // accurate. getPages() is the one part built by inference — from the same
  // "Next.js props attribute" pattern chapterListParse uses — since the real
  // method wasn't available. If pages still don't load, check the console for
  // the warnings this logs (they dump the raw parsed shape) and send that
  // back so the exact field names can be corrected.
  const AsuraScansSource = {
    name: "Asura Scans",
    baseUrl: "https://asurascans.com",
    apiUrl: "https://api.asurascans.com/api",

    _isNative() {
      return typeof window !== 'undefined' &&
        window.Capacitor &&
        typeof window.Capacitor.isNativePlatform === 'function' &&
        window.Capacitor.isNativePlatform();
    },

    // The real extension's headersBuilder() adds Referer: {baseUrl}/ to every
    // request the OkHttp client makes — API calls and comic pages alike. Some
    // of that may just be convention, but the image CDN in particular is a
    // common place for this to be enforced, so it's sent everywhere to match
    // the extension exactly.
    _headers() {
      return { Referer: `${this.baseUrl}/` };
    },

    // Routes through NativePlugin.webFetch — the hidden-WebView bridge that
    // gets past the TLS/Cloudflare fingerprinting that plain HttpURLConnection
    // (CapacitorHttp) can't. Anchoring the bridge to the URL's own origin makes
    // the internal fetch same-origin, so the site doesn't need to send CORS
    // headers for us to read the response.
    async _fetchText(url) {
      const NativePlugin = window.Capacitor?.Plugins?.NativePlugin;
      if (NativePlugin?.webFetch) {
        let origin;
        try { origin = new URL(url).origin + '/'; } catch (_) { origin = `${this.baseUrl}/`; }
        const { status, body } = await NativePlugin.webFetch({
          url, method: 'GET', headers: this._headers(), responseType: 'text', origin
        });
        if (status < 200 || status >= 400) throw new Error(`HTTP ${status}`);
        return body;
      }
      return await fetch(url, { referrer: `${this.baseUrl}/` }).then(r => r.text());
    },

    async _fetchJson(url) {
      const text = await this._fetchText(url);
      return JSON.parse(text);
    },

    async _fetchDoc(url) {
      const html = await this._fetchText(url);
      return new DOMParser().parseFromString(html, 'text/html');
    },

    // Fetches an image through the native HTTP client (bypasses CORS and lets
    // us actually send the Referer header, which a plain <img src> can't do).
    // Also handles the extension's tile-descramble anti-scraping scheme: if
    // the page URL carries a `#{...}` JSON fragment (tileCols/tileRows/tiles),
    // the downloaded image is a shuffled grid that needs reassembling — ported
    // from the real network interceptor (the `j()` method) which does exactly
    // this with a Canvas equivalent.
    async _fetchImageBase64(url) {
      const hashIdx = url.indexOf('#{');
      const cleanUrl = hashIdx >= 0 ? url.slice(0, hashIdx) : url;
      const tileFragment = hashIdx >= 0 ? url.slice(hashIdx + 1) : null;

      let mime, base64;
      const NativePlugin = window.Capacitor?.Plugins?.NativePlugin;
      if (NativePlugin?.webFetch) {
        // Same strong bridge as _fetchText — webFetch with responseType
        // "base64" resolves body as "mime|base64" already, and anchoring to the
        // image host's own origin keeps the internal fetch same-origin.
        let origin;
        try { origin = new URL(cleanUrl).origin + '/'; } catch (_) { origin = `${this.baseUrl}/`; }
        const { status, body } = await NativePlugin.webFetch({
          url: cleanUrl, headers: this._headers(), responseType: 'base64', origin
        });
        if (status < 200 || status >= 400) throw new Error(`HTTP ${status} fetching image`);
        const sep = body.indexOf('|');
        mime = (sep >= 0 ? body.slice(0, sep) : 'image/jpeg').split(';')[0].trim();
        base64 = sep >= 0 ? body.slice(sep + 1) : body;
        if (!base64) throw new Error('Empty image response');
      } else {
        const resp = await fetch(cleanUrl, { referrer: `${this.baseUrl}/` });
        if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
        const buf = await resp.arrayBuffer();
        const bytes = new Uint8Array(buf);
        let binary = '';
        const CHUNK = 8192;
        for (let i = 0; i < bytes.length; i += CHUNK) {
          binary += String.fromCharCode.apply(null, bytes.subarray(i, i + CHUNK));
        }
        base64 = btoa(binary);
        mime = (resp.headers.get('content-type') || 'image/jpeg').split(';')[0].trim();
      }

      if (!tileFragment) return `${mime}|${base64}`;

      try {
        const tileInfo = JSON.parse(tileFragment);
        return await this._reconstructTiles(base64, mime, tileInfo);
      } catch (e) {
        console.error('Asura Scans tile reconstruction failed, using raw image', e);
        return `${mime}|${base64}`;
      }
    },

    // Canvas port of the extension's interceptor: an image scrambled into a
    // tileCols × tileRows grid, where tiles[i] gives the ORIGINAL position of
    // the tile currently sitting at grid position i. Reassembles into a fresh
    // canvas and re-exports as webp, same as the Kotlin Bitmap/Canvas version.
    _reconstructTiles(base64Data, mime, tileInfo) {
      return new Promise((resolve, reject) => {
        const img = new Image();
        img.onload = () => {
          try {
            const cols = tileInfo.tileCols, rows = tileInfo.tileRows;
            const tileW = Math.floor(img.width / cols);
            const tileH = Math.floor(img.height / rows);
            const canvas = document.createElement('canvas');
            canvas.width = cols * tileW;
            canvas.height = rows * tileH;
            const ctx = canvas.getContext('2d');
            const tiles = tileInfo.tiles;
            for (let i = 0; i < tiles.length; i++) {
              const dest = tiles[i];
              const srcCol = i % cols, srcRow = Math.floor(i / cols);
              const dstCol = dest % cols, dstRow = Math.floor(dest / cols);
              ctx.drawImage(
                img,
                srcCol * tileW, srcRow * tileH, tileW, tileH,
                dstCol * tileW, dstRow * tileH, tileW, tileH
              );
            }
            const dataUrl = canvas.toDataURL('image/webp', 1.0);
            resolve(`image/webp|${dataUrl.split(',')[1]}`);
          } catch (e) { reject(e); }
        };
        img.onerror = () => reject(new Error('Failed to decode image for tile reconstruction'));
        img.src = `data:${mime};base64,${base64Data}`;
      });
    },

    // Ported verbatim from the real k() method: the site's HTML embeds
    // Next.js "flight" serialized props, where any 2-element array whose
    // first element is a primitive is really [refId, actualValue] and should
    // collapse down to just actualValue. Recurses through arrays/objects.
    _unwrapProps(node) {
      if (Array.isArray(node)) {
        if (node.length === 2 && (node[0] === null || typeof node[0] !== 'object')) {
          return this._unwrapProps(node[1]);
        }
        return node.map(n => this._unwrapProps(n));
      }
      if (node && typeof node === 'object') {
        const out = {};
        for (const key of Object.keys(node)) out[key] = this._unwrapProps(node[key]);
        return out;
      }
      return node;
    },

    // Finds an element whose `props` attribute JSON contains `keyword`
    // (mirrors Jsoup's `[props*=keyword]` attribute-contains selector), parses
    // it, and runs it through _unwrapProps.
    _extractProps(doc, keyword) {
      const el = Array.from(doc.querySelectorAll('[props]'))
        .find(e => (e.getAttribute('props') || '').includes(keyword));
      if (!el) return null;
      try {
        return this._unwrapProps(JSON.parse(el.getAttribute('props')));
      } catch (e) {
        console.error(`Asura Scans: failed to parse props containing "${keyword}"`, e);
        return null;
      }
    },

    _thumbOf(obj) {
      const t = obj?.thumbnail || obj?.coverImage || obj?.image || obj?.cover || obj?.poster || '';
      if (!t) return '';
      return t.startsWith('http') ? t : `${this.baseUrl}${t.startsWith('/') ? '' : '/'}${t}`;
    },

    _slugFromMangaUrl(mangaUrl) {
      const after = mangaUrl.split('/comics/')[1] || '';
      return after.split('/')[0];
    },

    async _searchApi(page, query, sort) {
      const url = new URL(`${this.apiUrl}/series`);
      url.searchParams.set('offset', String((page - 1) * 20));
      url.searchParams.set('limit', '20');
      if (query) url.searchParams.set('search', query);
      // NOTE: the real filter class that applies "popular"/"latest" (g1) isn't
      // decompiled either, so the exact query param name is a best guess.
      // If popular/latest come back identical to a plain search, this is why.
      if (sort) url.searchParams.set('sort', sort);
      const json = await this._fetchJson(url.href);
      const items = json.data || (Array.isArray(json) ? json : []);
      if (!items.length) console.warn('Asura Scans: series list came back empty', json);
      return items.map(item => {
        const title = item.title || item.name || '';
        if (!title) console.warn('Asura Scans: series item has no recognizable title field', item);
        return {
          title,
          url: `${this.baseUrl}/comics/${item.slug}`,
          thumbnail: this._thumbOf(item),
        };
      });
    },

    async getPopularManga(page = 1) {
      return this._searchApi(page, '', 'popular');
    },

    async getLatestUpdates(page = 1) {
      return this._searchApi(page, '', 'latest');
    },

    async searchManga(query, page = 1) {
      return this._searchApi(page, query, '');
    },

    async getMangaDetails(mangaUrl) {
      const slug = this._slugFromMangaUrl(mangaUrl);
      const json = await this._fetchJson(`${this.apiUrl}/series/${slug}`);
      // mangaDetailsParse checks for a `{ data: ... }` wrapper before falling
      // back to a bare object — ported the same check here.
      const series = (json && typeof json === 'object' && 'data' in json)
        ? (json.data.series || json.data)
        : (json.series || json);

      if (!series || typeof series !== 'object') {
        console.warn('Asura Scans: unexpected manga details shape', json);
        return { title: '', description: '', thumbnail: '', status: 0, genres: [], author: '', artist: '' };
      }

      const statusText = series.status || '';
      let status = 0;
      if (/ongoing/i.test(statusText)) status = 1;
      else if (/complet/i.test(statusText)) status = 2;

      return {
        title: series.title || series.name || '',
        description: series.description || series.synopsis || '',
        thumbnail: this._thumbOf(series),
        status,
        genres: (series.genres || series.tags || []).map(g => (typeof g === 'string' ? g : g?.name || '')),
        author: series.author || '',
        artist: series.artist || '',
      };
    },

    // Real chapterListRequest hits {baseUrl}/comics/{slug} (an HTML page, NOT
    // the JSON API) and scrapes a `[props*=chapters]` element — ported exactly.
    async getChapterList(mangaUrl) {
      const slug = this._slugFromMangaUrl(mangaUrl);
      const doc = await this._fetchDoc(`${this.baseUrl}/comics/${slug}`);
      const parsed = this._extractProps(doc, 'chapters');
      if (!parsed) {
        console.error('Asura Scans: could not find chapters props on', mangaUrl);
        return [];
      }
      const rawChapters = parsed.chapters || parsed.data?.chapters || [];
      if (!rawChapters.length) console.warn('Asura Scans: parsed chapters props but list is empty', parsed);

      // Real extension defaults "Hide premium chapters" to ON (see
      // setupPreferenceScreen's SwitchPreferenceCompat defaultValue TRUE) —
      // locked chapters don't have pages embedded in their HTML at all, so
      // showing them in the list just leads to a confusing "no pages" error
      // when tapped. Hiding them by default matches the real app's behavior.
      const hidePremium = true;
      const chapters = rawChapters
        .filter(ch => {
          const locked = !!(ch.isLocked ?? ch.locked ?? ch.premium ?? ch.isPremium ?? ch.paid ?? false);
          return !hidePremium || !locked;
        })
        .map(ch => {
          const number = ch.chapter ?? ch.number ?? ch.chapterNumber ?? ch.index ?? '';
          const id = ch.slug ?? ch.id ?? String(number);
          return {
            name: ch.title || `Chapter ${number}`,
            chapter: String(number),
            date: ch.createdAt || ch.publishedAt || ch.date || '',
            url: `${this.baseUrl}/comics/${slug}/chapter/${id}`,
          };
        });
      return chapters;
    },

    // ── INFERRED — see the caveat at the top of this source. pageListParse
    // wasn't decompilable, so this assumes the chapter page uses the same
    // props-attribute mechanism as the chapter list, just under a "pages" or
    // "images" key. If this comes back empty, check the console warning it
    // logs (the raw parsed shape) to correct the key names.
    async getPages(chapterUrl) {
      const doc = await this._fetchDoc(chapterUrl);
      let parsed = this._extractProps(doc, 'pages') || this._extractProps(doc, 'images');
      if (!parsed) {
        // No pages found at all — check whether this looks like a paywalled
        // chapter (the list-time isLocked filter may have missed it if the
        // real field name differs from the guesses in getChapterList).
        const bodyText = doc.body?.textContent || '';
        if (/subscri|premium|unlock|purchase|coin/i.test(bodyText)) {
          throw new Error('This chapter requires a subscription on Asura Scans.');
        }
        console.error('Asura Scans: could not find a pages/images props attribute on', chapterUrl);
        return [];
      }
      const rawPages = parsed.pages || parsed.images || parsed.data?.pages || (Array.isArray(parsed) ? parsed : null);
      if (!rawPages) {
        console.warn('Asura Scans: found props but no recognizable pages array', parsed);
        return [];
      }
      return rawPages.map((p, i) => {
        const url = typeof p === 'string' ? p : (p.url || p.src || p.image || '');
        if (!url) console.warn(`Asura Scans: page ${i} has no recognizable url field`, p);
        return { url, index: i };
      }).filter(p => p.url);
    },
  };

  // ==================== MANGA READ SOURCE ====================
  // mangaread.org — a WordPress "Madara" theme site (the most common Mihon
  // source framework). Selectors below are the standard Madara ones that
  // mangaread.org uses. Every request goes through the strong NativePlugin
  // .webFetch bridge (hidden WebView), anchored to each URL's own origin so the
  // internal fetch is same-origin (no CORS needed) and gets past Cloudflare/TLS
  // fingerprinting the way plain HttpURLConnection can't.
  const MangaReadSource = {
    name: "Manga Read",
    baseUrl: "https://www.mangaread.org",

    // Madara checks Referer on image/CDN requests, so send it everywhere.
    _headers() {
      return { Referer: `${this.baseUrl}/` };
    },

    _originFor(url) {
      try { return new URL(url).origin + '/'; } catch (_) { return `${this.baseUrl}/`; }
    },

    _absUrl(href) {
      if (!href) return '';
      href = href.trim();
      if (href.startsWith('http')) return href;
      if (href.startsWith('//')) return 'https:' + href;
      return `${this.baseUrl}${href.startsWith('/') ? '' : '/'}${href}`;
    },

    async _fetchText(url) {
      const NativePlugin = window.Capacitor?.Plugins?.NativePlugin;
      if (NativePlugin?.webFetch) {
        const { status, body } = await NativePlugin.webFetch({
          url, method: 'GET', headers: this._headers(), responseType: 'text', origin: this._originFor(url)
        });
        if (status < 200 || status >= 400) throw new Error(`HTTP ${status}`);
        return body;
      }
      return await fetch(url, { referrer: `${this.baseUrl}/` }).then(r => r.text());
    },

    // Madara loads its chapter list via a POST to {mangaUrl}ajax/chapters/.
    async _fetchTextPost(url, formStr = '') {
      const NativePlugin = window.Capacitor?.Plugins?.NativePlugin;
      const headers = { ...this._headers(), 'Content-Type': 'application/x-www-form-urlencoded', 'X-Requested-With': 'XMLHttpRequest' };
      if (NativePlugin?.webFetch) {
        const { status, body } = await NativePlugin.webFetch({
          url, method: 'POST', headers, body: formStr, responseType: 'text', origin: this._originFor(url)
        });
        if (status < 200 || status >= 400) throw new Error(`HTTP ${status}`);
        return body;
      }
      return await fetch(url, { method: 'POST', headers, body: formStr, referrer: `${this.baseUrl}/` }).then(r => r.text());
    },

    async _fetchDoc(url) {
      return new DOMParser().parseFromString(await this._fetchText(url), 'text/html');
    },

    // Same "mime|base64" contract the reader/downloader expect. webFetch with
    // responseType "base64" already resolves the body in exactly that format.
    async _fetchImageBase64(url) {
      const NativePlugin = window.Capacitor?.Plugins?.NativePlugin;
      if (NativePlugin?.webFetch) {
        const { status, body } = await NativePlugin.webFetch({
          url, headers: this._headers(), responseType: 'base64', origin: this._originFor(url)
        });
        if (status < 200 || status >= 400) throw new Error(`HTTP ${status} fetching image`);
        const sep = body.indexOf('|');
        const mime = (sep >= 0 ? body.slice(0, sep) : 'image/jpeg').split(';')[0].trim();
        const base64 = sep >= 0 ? body.slice(sep + 1) : body;
        if (!base64) throw new Error('Empty image response');
        return `${mime}|${base64}`;
      }
      const resp = await fetch(url, { referrer: `${this.baseUrl}/` });
      if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
      const buf = await resp.arrayBuffer();
      const bytes = new Uint8Array(buf);
      let binary = '';
      const CHUNK = 8192;
      for (let i = 0; i < bytes.length; i += CHUNK) {
        binary += String.fromCharCode.apply(null, bytes.subarray(i, i + CHUNK));
      }
      const mime = (resp.headers.get('content-type') || 'image/jpeg').split(';')[0].trim();
      return `${mime}|${btoa(binary)}`;
    },

    _imgSrc(img) {
      let src = (img.getAttribute('data-src') || img.getAttribute('data-lazy-src') ||
        img.getAttribute('data-cfsrc') || img.getAttribute('src') || '').trim();
      if (!src) {
        const srcset = img.getAttribute('srcset') || img.getAttribute('data-srcset');
        if (srcset) src = srcset.split(',')[0].trim().split(' ')[0];
      }
      return src;
    },

    // Madara search endpoint: {baseUrl}[/page/{n}]/?s={q}&post_type=wp-manga
    // with m_orderby controlling popular (views) vs latest.
    _listUrl(page, query, orderby) {
      const p = page > 1 ? `/page/${page}` : '';
      const params = new URLSearchParams();
      params.set('s', query || '');
      params.set('post_type', 'wp-manga');
      if (orderby) params.set('m_orderby', orderby);
      return `${this.baseUrl}${p}/?${params.toString()}`;
    },

    // Madara listing items: ".c-tabs-item__content" (search) / ".page-item-detail"
    // (popular grid). Pull the /manga/ link, its title, and the thumbnail.
    _parseList(doc) {
      const items = Array.from(doc.querySelectorAll('.c-tabs-item__content, .page-item-detail, .manga__item'));
      const out = [];
      const seen = new Set();
      for (const it of items) {
        const link = it.querySelector('a[href*="/manga/"]') ||
          it.querySelector('.post-title a, .tab-thumb a, h3 a, h5 a');
        if (!link) continue;
        const url = this._absUrl(link.getAttribute('href'));
        if (!url || seen.has(url)) continue;
        seen.add(url);
        const img = it.querySelector('img');
        const title = (link.getAttribute('title') || link.textContent || '').trim() ||
          (it.querySelector('.post-title')?.textContent || '').trim();
        out.push({ title, url, thumbnail: img ? this._absUrl(this._imgSrc(img)) : '' });
      }
      if (!out.length) console.warn('Manga Read: listing came back empty (check ".c-tabs-item__content" selector)');
      return out;
    },

    async getPopularManga(page = 1) {
      return this._parseList(await this._fetchDoc(this._listUrl(page, '', 'views')));
    },

    async getLatestUpdates(page = 1) {
      return this._parseList(await this._fetchDoc(this._listUrl(page, '', 'latest')));
    },

    async searchManga(query, page = 1) {
      return this._parseList(await this._fetchDoc(this._listUrl(page, query, '')));
    },

    // Standard Madara details page markup.
    async getMangaDetails(mangaUrl) {
      const doc = await this._fetchDoc(mangaUrl);
      const txt = (sel) => (doc.querySelector(sel)?.textContent || '').trim();
      const metaContent = (sel) => (doc.querySelector(sel)?.getAttribute('content') || '').trim();

      const title = txt('.post-title h1') || txt('.post-title h3') || metaContent('meta[property="og:title"]');
      const imgEl = doc.querySelector('.summary_image img');
      let thumbnail = imgEl ? this._imgSrc(imgEl) : '';
      if (!thumbnail) thumbnail = metaContent('meta[property="og:image"]');
      thumbnail = this._absUrl(thumbnail);
      const description = txt('.description-summary .summary__content') || txt('div.summary__content') ||
        txt('.manga-excerpt') || metaContent('meta[property="og:description"]');
      const genres = Array.from(doc.querySelectorAll('.genres-content a'))
        .map(a => (a.textContent || '').trim()).filter(Boolean);
      const author = Array.from(doc.querySelectorAll('.author-content a'))
        .map(a => (a.textContent || '').trim()).filter(Boolean).join(', ');
      const artist = Array.from(doc.querySelectorAll('.artist-content a'))
        .map(a => (a.textContent || '').trim()).filter(Boolean).join(', ');

      let status = 0;
      for (const it of doc.querySelectorAll('.post-content_item, .post-status .post-content_item')) {
        const head = (it.querySelector('.summary-heading')?.textContent || '').toLowerCase();
        if (head.includes('status')) {
          const val = (it.querySelector('.summary-content')?.textContent || '').toLowerCase();
          if (val.includes('ongoing')) status = 1;
          else if (val.includes('complet')) status = 2;
        }
      }
      if (!title) console.warn('Manga Read: could not read a title from details page', mangaUrl);
      return { title, description, thumbnail, status, genres, author, artist };
    },

    // Chapters come from POST {mangaUrl}ajax/chapters/. Falls back to scraping
    // the manga page itself if the AJAX call returns nothing.
    async getChapterList(mangaUrl) {
      const base = mangaUrl.endsWith('/') ? mangaUrl : mangaUrl + '/';
      let rows = [];
      try {
        const html = await this._fetchTextPost(base + 'ajax/chapters/', '');
        const doc = new DOMParser().parseFromString(html, 'text/html');
        rows = Array.from(doc.querySelectorAll('li.wp-manga-chapter > a'));
      } catch (e) {
        console.warn('Manga Read: ajax/chapters POST failed, falling back to manga page', e);
      }
      if (!rows.length) {
        const pageDoc = await this._fetchDoc(mangaUrl);
        rows = Array.from(pageDoc.querySelectorAll('li.wp-manga-chapter > a'));
      }
      if (!rows.length) console.warn('Manga Read: no chapters found (check "li.wp-manga-chapter")', mangaUrl);
      return rows.map(a => {
        const name = (a.textContent || '').trim();
        const li = a.closest('li.wp-manga-chapter');
        const date = (li?.querySelector('.chapter-release-date, span.chapter-release-date')?.textContent || '').trim();
        const numMatch = name.match(/([\d.]+)/);
        return {
          name: name || 'Chapter',
          chapter: numMatch ? numMatch[1] : name,
          date,
          url: this._absUrl(a.getAttribute('href')),
        };
      });
    },

    // Reader pages: images inside ".reading-content". Lazy-loaded, so read
    // data-src first and trim whitespace (Madara pads image URLs with newlines).
    async getPages(chapterUrl) {
      const doc = await this._fetchDoc(chapterUrl);
      const imgs = Array.from(doc.querySelectorAll('.reading-content img, .page-break img, img.wp-manga-chapter-img'));
      if (!imgs.length) {
        const bodyText = doc.body?.textContent || '';
        if (/premium|purchase|subscri|log ?in to read/i.test(bodyText)) {
          throw new Error('This chapter may require an account on Manga Read.');
        }
        console.error('Manga Read: no page images found (check ".reading-content img")', chapterUrl);
        return [];
      }
      return imgs.map((img, i) => {
        const src = this._imgSrc(img);
        if (!src) console.warn(`Manga Read: page ${i} has no src`, img);
        return { url: this._absUrl(src), index: i };
      }).filter(p => p.url);
    },
  };

  // ==========================================================================
  // SHARED NETWORK HELPER
  // The two sources above each embed their own copy of the fetch/image/DOM
  // helpers (kept as-is to avoid touching working code). The five sources below
  // instead share this single helper to cut duplication. Every call routes
  // through NativePlugin.webFetch — the strong hidden-WebView bridge that gets
  // past Cloudflare/TLS fingerprinting — anchored to each URL's own origin so
  // the internal fetch is same-origin (no CORS needed). webFetch with
  // responseType "base64" already resolves body as "mime|base64", which is
  // exactly what the reader/downloader expect from _fetchImageBase64.
  // ==========================================================================
  const SourceNet = {
    _origin(url, fallback) {
      try { return new URL(url).origin + '/'; } catch (_) { return fallback || url; }
    },
    async text(url, headers = {}, { method = 'GET', body = null } = {}) {
      const NativePlugin = window.Capacitor?.Plugins?.NativePlugin;
      if (NativePlugin?.webFetch) {
        const res = await NativePlugin.webFetch({
          url, method, headers, body, responseType: 'text', origin: this._origin(url)
        });
        if (res.status < 200 || res.status >= 400) throw new Error(`HTTP ${res.status}`);
        return res.body;
      }
      const r = await fetch(url, { method, body, headers });
      if (!r.ok) throw new Error(`HTTP ${r.status}`);
      return await r.text();
    },
    async json(url, headers = {}, opts = {}) {
      return JSON.parse(await this.text(url, headers, opts));
    },
    async doc(url, headers = {}) {
      return new DOMParser().parseFromString(await this.text(url, headers), 'text/html');
    },
    async imageBase64(url, headers = {}) {
      const NativePlugin = window.Capacitor?.Plugins?.NativePlugin;
      if (NativePlugin?.webFetch) {
        const res = await NativePlugin.webFetch({
          url, headers, responseType: 'base64', origin: this._origin(url)
        });
        if (res.status < 200 || res.status >= 400) throw new Error(`HTTP ${res.status} fetching image`);
        const sep = res.body.indexOf('|');
        const mime = (sep >= 0 ? res.body.slice(0, sep) : 'image/jpeg').split(';')[0].trim();
        const b64 = sep >= 0 ? res.body.slice(sep + 1) : res.body;
        if (!b64) throw new Error('Empty image response');
        return `${mime}|${b64}`;
      }
      const r = await fetch(url, { headers });
      if (!r.ok) throw new Error(`HTTP ${r.status}`);
      const buf = await r.arrayBuffer();
      const bytes = new Uint8Array(buf);
      let bin = '';
      for (let i = 0; i < bytes.length; i += 8192) bin += String.fromCharCode.apply(null, bytes.subarray(i, i + 8192));
      const mime = (r.headers.get('content-type') || 'image/jpeg').split(';')[0].trim();
      return `${mime}|${btoa(bin)}`;
    },
    imgSrc(img) {
      let s = (img.getAttribute('data-src') || img.getAttribute('data-lazy-src') ||
        img.getAttribute('data-cfsrc') || img.getAttribute('src') || '').trim();
      if (!s) {
        const ss = img.getAttribute('srcset') || img.getAttribute('data-srcset');
        if (ss) s = ss.split(',')[0].trim().split(' ')[0];
      }
      return s;
    },
    abs(baseUrl, href) {
      if (!href) return '';
      href = String(href).trim();
      if (href.startsWith('http')) return href;
      if (href.startsWith('//')) return 'https:' + href;
      return `${baseUrl}${href.startsWith('/') ? '' : '/'}${href}`;
    },
    stripHtml(s) { return (s || '').replace(/<[^>]+>/g, '').trim(); },
  };


  // ==================== MANHWA READ SOURCE ====================
  // manhwaread.com — custom site (NOT Madara). Ported from the real keiyoushi
  // extension (eu.kanade.tachiyomi.extension.en.manhwaread.ManhwaRead):
  //  • listing/search: {baseUrl}[/page/{n}/]?s={q}&sortby={v}&order=desc
  //    items ".main-container .manga-item", link "a.manga-item__link",
  //    thumb ".manga-item__img img". Popular = weekly_top, latest = release.
  //  • details: "#mangaSummary" markup, status from ".manga-status"
  //    data-status, cover from og:image.
  //  • chapters: "#chaptersList > a.chapter-item" (site lists newest-first).
  //  • pages: `var chapterData = {...}` inline script — its `data` field is
  //    base64-encoded JSON [{src}], each image is `${base}/${src}`.
  // The site sits behind Cloudflare, so every request routes through the
  // hidden-WebView webFetch bridge (SourceNet), which passes the challenge.
  const ManhwaReadSource = {
    name: "Manhwa Read",
    baseUrl: "https://manhwaread.com",

    _headers() { return { Referer: `${this.baseUrl}/` }; },

    _listUrl(page, query, sortby) {
      const p = page > 1 ? `/page/${page}/` : '/';
      const params = new URLSearchParams();
      params.set('s', query || '');
      if (sortby) { params.set('sortby', sortby); params.set('order', 'desc'); }
      return `${this.baseUrl}${p}?${params.toString()}`;
    },

    _parseList(doc) {
      const out = [];
      const seen = new Set();
      for (const it of doc.querySelectorAll('.main-container .manga-item')) {
        const link = it.querySelector('a.manga-item__link');
        if (!link) continue;
        const url = SourceNet.abs(this.baseUrl, link.getAttribute('href'));
        if (!url || seen.has(url)) continue;
        seen.add(url);
        const img = it.querySelector('.manga-item__img img');
        out.push({
          title: (link.textContent || '').trim(),
          url,
          thumbnail: img ? SourceNet.abs(this.baseUrl, SourceNet.imgSrc(img)) : '',
        });
      }
      if (!out.length) console.warn('Manhwa Read: listing empty (check ".main-container .manga-item")');
      return out;
    },

    async getPopularManga(page = 1) {
      return this._parseList(await SourceNet.doc(this._listUrl(page, '', 'weekly_top'), this._headers()));
    },

    async getLatestUpdates(page = 1) {
      return this._parseList(await SourceNet.doc(this._listUrl(page, '', 'release'), this._headers()));
    },

    async searchManga(query, page = 1) {
      return this._parseList(await SourceNet.doc(this._listUrl(page, query, ''), this._headers()));
    },

    async getMangaDetails(mangaUrl) {
      const doc = await SourceNet.doc(mangaUrl, this._headers());
      const txt = (sel) => (doc.querySelector(sel)?.textContent || '').trim();
      const title = txt('#mangaSummary .manga-titles h1') ||
        (doc.querySelector('meta[property="og:title"]')?.getAttribute('content') || '').trim();
      const description = txt('#mangaDesc > .manga-desc__content') || txt('#mangaDesc') ||
        (doc.querySelector('meta[property="og:description"]')?.getAttribute('content') || '').trim();
      const thumbnail = SourceNet.abs(this.baseUrl,
        doc.querySelector('meta[property="og:image"]')?.getAttribute('content') || '');
      const genres = Array.from(doc.querySelectorAll('#mangaSummary .manga-genres a'))
        .map(a => (a.textContent || '').trim()).filter(Boolean);
      const statusText = doc.querySelector('#mangaSummary .manga-status')?.getAttribute('data-status') || '';
      let status = 0;
      if (statusText === 'ongoing') status = 1;
      else if (statusText === 'completed') status = 2;
      if (!title) console.warn('Manhwa Read: no title on details page', mangaUrl);
      return { title, description, thumbnail, status, genres, author: '', artist: '' };
    },

    async getChapterList(mangaUrl) {
      const doc = await SourceNet.doc(mangaUrl, this._headers());
      const rows = Array.from(doc.querySelectorAll('#chaptersList > a.chapter-item, #chaptersList a.chapter-item'));
      if (!rows.length) console.warn('Manhwa Read: no chapters (check "#chaptersList a.chapter-item")', mangaUrl);
      // Site lists newest-first, which is what the app's chapter UI expects.
      return rows.map(a => {
        const name = (a.querySelector('span.chapter-item__name')?.textContent || a.textContent || '').trim();
        const date = (a.querySelector('span.chapter-item__date')?.textContent || '').trim();
        const numMatch = name.match(/([\d.]+)/);
        return {
          name: name || 'Chapter',
          chapter: numMatch ? numMatch[1] : name,
          date,
          url: SourceNet.abs(this.baseUrl, a.getAttribute('href')),
        };
      });
    },

    // Pages come from an inline `var chapterData = {data, base}` where `data`
    // is base64 JSON [{src}] — exactly what the real extension parses.
    async getPages(chapterUrl) {
      const html = await SourceNet.text(chapterUrl, this._headers());
      const m = html.match(/var\s+chapterData\s*=\s*(\{.*?\})\s*[;\n]/s) ||
        html.match(/var\s+chapterData\s*=\s*(\{.*\})/);
      if (!m) {
        console.error('Manhwa Read: chapterData not found in chapter page', chapterUrl);
        throw new Error('Chapter data not found — the site may have changed.');
      }
      const chapterData = JSON.parse(m[1]);
      const decoded = atob(chapterData.data);
      const pages = JSON.parse(decoded);
      return pages.map((p, i) => ({
        url: `${chapterData.base}/${p.src}`,
        index: i,
      }));
    },

    async _fetchImageBase64(url) {
      return SourceNet.imageBase64(url, this._headers());
    },
  };

  // ==================== MANHWA BUDDY SOURCE ====================
  // manhwabuddy.com — custom site (NOT Madara), selectors mapped from the live
  // markup:
  //  • latest: "/" and "/page/{n}/", items ".latest-item" with an
  //    a[href^="/manhwa/"] link and "img.img-latest" cover.
  //  • search: "/search/?s={q}" (same .latest-item results markup).
  //  • details: h1 title, og:image cover, og:description, "/genre/" links.
  //  • chapters: "ul.chapter-list li a" with span.chapter-name.
  //  • pages: reader images "img.loading" with data-src on the
  //    img01.manhwabuddy.com CDN (Referer required).
  const ManhwaBuddySource = {
    name: "Manhwa Buddy",
    baseUrl: "https://manhwabuddy.com",

    _headers() { return { Referer: `${this.baseUrl}/` }; },

    _parseList(doc) {
      const out = [];
      const seen = new Set();
      const items = doc.querySelectorAll('.latest-item, .item-move');
      for (const it of items) {
        const link = it.querySelector('a[href*="/manhwa/"]');
        if (!link) continue;
        const url = SourceNet.abs(this.baseUrl, link.getAttribute('href'));
        if (!url || seen.has(url)) continue;
        seen.add(url);
        const img = it.querySelector('img');
        const title = (link.getAttribute('title') || '').trim() ||
          (it.querySelector('h4, .name')?.textContent || '').trim() ||
          (img?.getAttribute('alt') || '').trim();
        out.push({ title, url, thumbnail: img ? SourceNet.abs(this.baseUrl, SourceNet.imgSrc(img)) : '' });
      }
      if (!out.length) console.warn('Manhwa Buddy: listing empty (check ".latest-item")');
      return out;
    },

    // No separate popularity listing with pagination, so popular reuses the
    // homepage/latest feed — same content, keeps the Browse grid working.
    async getPopularManga(page = 1) {
      return this.getLatestUpdates(page);
    },

    async getLatestUpdates(page = 1) {
      const url = page > 1 ? `${this.baseUrl}/page/${page}/` : `${this.baseUrl}/`;
      return this._parseList(await SourceNet.doc(url, this._headers()));
    },

    async searchManga(query, page = 1) {
      const params = new URLSearchParams();
      params.set('s', query || '');
      if (page > 1) params.set('page', String(page));
      return this._parseList(await SourceNet.doc(`${this.baseUrl}/search/?${params.toString()}`, this._headers()));
    },

    async getMangaDetails(mangaUrl) {
      const doc = await SourceNet.doc(mangaUrl, this._headers());
      const meta = (p) => (doc.querySelector(`meta[property="${p}"]`)?.getAttribute('content') || '').trim();
      const title = (doc.querySelector('h1')?.textContent || '').trim() || meta('og:title');
      const description = meta('og:description');
      const thumbnail = SourceNet.abs(this.baseUrl, meta('og:image'));
      // Genre links inside the main info block (the site nav also has /genre/
      // links, so scope to the content area first and fall back to page-wide).
      let genreEls = doc.querySelectorAll('.main-info-list a[href*="/genre/"], .wpmone a[href*="/genre/"]');
      if (!genreEls.length) genreEls = doc.querySelectorAll('.box a[href*="/genre/"]');
      const genres = Array.from(genreEls).map(a => (a.textContent || '').trim()).filter(Boolean);
      let status = 0;
      const bodyText = (doc.querySelector('.main-info-list, .wpmone')?.textContent || '').toLowerCase();
      if (bodyText.includes('ongoing')) status = 1;
      else if (bodyText.includes('complet')) status = 2;
      if (!title) console.warn('Manhwa Buddy: no title on details page', mangaUrl);
      return { title, description, thumbnail, status, genres, author: '', artist: '' };
    },

    async getChapterList(mangaUrl) {
      const doc = await SourceNet.doc(mangaUrl, this._headers());
      const rows = Array.from(doc.querySelectorAll('ul.chapter-list li a, .chapter-list a'));
      if (!rows.length) console.warn('Manhwa Buddy: no chapters (check "ul.chapter-list li a")', mangaUrl);
      return rows.map(a => {
        const name = (a.querySelector('.chapter-name')?.textContent || a.getAttribute('title') || a.textContent || '').trim();
        const date = (a.querySelector('.ct-update')?.textContent || '').trim();
        const numMatch = name.match(/([\d.]+)/);
        return {
          name: name || 'Chapter',
          chapter: numMatch ? numMatch[1] : name,
          date,
          url: SourceNet.abs(this.baseUrl, a.getAttribute('href')),
        };
      });
    },

    async getPages(chapterUrl) {
      const doc = await SourceNet.doc(chapterUrl, this._headers());
      // Reader images are img.loading with data-src on the img01 CDN; exclude
      // site chrome (logo, NEW badges) by requiring the chapters path.
      let imgs = Array.from(doc.querySelectorAll('img.loading'));
      if (!imgs.length) {
        imgs = Array.from(doc.querySelectorAll('img[data-src*="/chapters/"], img[src*="/chapters/"]'));
      }
      const pages = imgs
        .map(img => SourceNet.abs(this.baseUrl, SourceNet.imgSrc(img)))
        .filter(src => src && /\/chapters\//.test(src))
        .map((url, i) => ({ url, index: i }));
      if (!pages.length) console.error('Manhwa Buddy: no page images found (check "img.loading")', chapterUrl);
      return pages;
    },

    async _fetchImageBase64(url) {
      return SourceNet.imageBase64(url, this._headers());
    },
  };

  // Registry the mdSearch fallback loader looks up by name
  // ==================== REGISTER ALL SOURCES ====================
   window.MangaSources = {
    AsuraScans: AsuraScansSource,
    MangaRead: MangaReadSource,
    ManhwaRead: ManhwaReadSource,
    ManhwaBuddy: ManhwaBuddySource,
  };
  // Source select handler — switching sources refreshes the Browse grid
  const sourceSelect = document.getElementById('sourceSelect');
  if (sourceSelect) {
    sourceSelect.value = currentSource;
    sourceSelect.addEventListener('change', () => {
      currentSource = sourceSelect.value;
      console.log('Switched to:', currentSource);
      window.mdUpdateSourceChromeVisibility && window.mdUpdateSourceChromeVisibility();
      window.mdSearch && window.mdSearch(true);
    });
  }

  // ==================== DISPATCHER FUNCTIONS ====================

  // All non-MangaDex sources live in window.MangaSources keyed by the same
  // string currentSource uses, so the dispatchers below just look the source up
  // there instead of repeating an if/else chain per source. Add a new source by
  // registering it above + adding a dropdown <option> — no dispatcher edits.
  function _activeSource() {
    if (currentSource === 'MangaDex') return MangaDexSource;
    return window.MangaSources?.[currentSource] || null;
  }

   async function loadPopular(page) {
    const src = _activeSource();
    return src ? await src.getPopularManga(page) : [];
  }

    async function loadMangaDetails(mangaUrl) {
    const src = _activeSource();
    return src ? await src.getMangaDetails(mangaUrl) : null;
  }

   async function loadChapters(mangaUrl) {
    const src = _activeSource();
    return src ? await src.getChapterList(mangaUrl) : [];
  }

   async function loadPages(chapterUrl) {
    const src = _activeSource();
    return src ? await src.getPages(chapterUrl) : [];
  }

  // ==================== EXTENSIONS API BRIDGE ====================
  // The bridge index.html's extension UI talks to (the repo manager modal and
  // the Mihon-style extensions screen both do `window.ExtensionsAPI.<method>`).
  // It forwards each call to the matching NativePlugin ext* method in
  // MainActivity.java, which resolves `{ value: <result> }` — unwrapped here so
  // callers get the raw array/object/string back.
  (function () {
    const plugin = () => window.Capacitor?.Plugins?.NativePlugin;

    const isNative = () =>
      !!(window.Capacitor &&
         typeof window.Capacitor.isNativePlatform === 'function' &&
         window.Capacitor.isNativePlatform());

    async function callExt(method, args) {
      const p = plugin();
      if (!p || typeof p[method] !== 'function') {
        throw new Error(
          method + ' is not available — this APK was built before the extension ' +
          'system was added. Rebuild the app with the updated MainActivity.'
        );
      }
      const res = await p[method](args || {});
      return (res && typeof res === 'object' && 'value' in res) ? res.value : res;
    }

    window.ExtensionsAPI = {
      // True only when running inside the native app AND the installed build
      // actually has the extension plugin methods (an old APK won't).
      get available() {
        const p = plugin();
        return isNative() && !!p && typeof p.extRepoList === 'function';
      },

      // ── Repo management ── all resolve the updated repo-URL array.
      repoList: () => callExt('extRepoList'),
      repoAdd: (url) => callExt('extRepoAdd', { url }),
      repoRemove: (url) => callExt('extRepoRemove', { url }),
      // Fetches a repo's index.min.json → array of available extensions.
      repoIndex: (repo) => callExt('extRepoIndex', { repo }),

      // ── Install / uninstall / list ── all resolve the installed-list array.
      listInstalled: () => callExt('extListInstalled'),
      install: (repo, pkg, apk) => callExt('extInstall', { repo, pkg, apk }),
      uninstall: (pkg) => callExt('extUninstall', { pkg }),

      // ── Source calls (browse / search / read via installed extensions) ──
      // Param names mirror the native methods exactly (sourceId, page, query,
      // filtersJson, mangaUrl, chapterUrl, url, host, token).
      popular: (sourceId, page = 1) => callExt('extPopular', { sourceId, page }),
      latest: (sourceId, page = 1) => callExt('extLatest', { sourceId, page }),
      search: (sourceId, query = '', page = 1, filtersJson = null) =>
        callExt('extSearch', { sourceId, query, page, filtersJson }),
      filters: (sourceId) => callExt('extFilters', { sourceId }),
      mangaDetails: (sourceId, mangaUrl) => callExt('extMangaDetails', { sourceId, mangaUrl }),
      chapterList: (sourceId, mangaUrl) => callExt('extChapterList', { sourceId, mangaUrl }),
      pageList: (sourceId, chapterUrl) => callExt('extPageList', { sourceId, chapterUrl }),
      // `page` here is a page OBJECT from pageList (not a number).
      getImage: (sourceId, page) => callExt('extGetImage', { sourceId, page }),
      getCover: (sourceId, url) => callExt('extGetCover', { sourceId, url }),
      setHostAuth: (host, token = null) => callExt('extSetHostAuth', { host, token }),
    };
  })();
