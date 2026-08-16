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

  // ==================== COMICK SOURCE ====================
  // comick.io — clean public JSON API (api.comick.fun). This is the most
  // reliable of the new sources: everything comes from documented JSON
  // endpoints, no HTML scraping. Cover/page images live on meo.comick.pictures
  // and are addressed by their "b2key".
  const ComickSource = {
    name: "ComicK",
    baseUrl: "https://comick.io",
    apiUrl: "https://api.comick.fun",
    imgUrl: "https://meo.comick.pictures",
    _headers() { return { Referer: `${this.baseUrl}/` }; },
    _fetchImageBase64(url) { return SourceNet.imageBase64(url, this._headers()); },

    _cover(md_covers) {
      const key = Array.isArray(md_covers) && md_covers[0]?.b2key;
      return key ? `${this.imgUrl}/${key}` : '';
    },
    _slug(mangaUrl) { return mangaUrl.split('/comic/')[1]?.split('/')[0] || ''; },

    // sort: "follow" ≈ popular, "uploaded" ≈ latest. These are the documented
    // ComicK sort values and match what the Tachiyomi extension uses.
    async _search(page, query, sort) {
      const url = new URL(`${this.apiUrl}/v1.0/search`);
      url.searchParams.set('page', String(page));
      url.searchParams.set('limit', '30');
      url.searchParams.set('tachiyomi', 'true');
      if (query) url.searchParams.set('q', query);
      if (sort) url.searchParams.set('sort', sort);
      const json = await SourceNet.json(url.href, this._headers());
      const items = Array.isArray(json) ? json : (json.data || []);
      if (!items.length) console.warn('ComicK: search returned no items', json);
      return items.map(it => ({
        title: it.title || '',
        url: `${this.baseUrl}/comic/${it.slug}`,
        thumbnail: this._cover(it.md_covers),
      }));
    },
    getPopularManga(page = 1) { return this._search(page, '', 'follow'); },
    getLatestUpdates(page = 1) { return this._search(page, '', 'uploaded'); },
    searchManga(query, page = 1) { return this._search(page, query, ''); },

    async _detailJson(slug) {
      return SourceNet.json(`${this.apiUrl}/comic/${slug}?tachiyomi=true`, this._headers());
    },
    async getMangaDetails(mangaUrl) {
      const json = await this._detailJson(this._slug(mangaUrl));
      const c = json.comic || json;
      let status = 0;
      if (c.status === 1) status = 1; else if (c.status === 2) status = 2;
      const genres = [];
      (c.md_comic_md_genres || []).forEach(g => { const n = g.md_genres?.name; if (n) genres.push(n); });
      return {
        title: c.title || '',
        description: SourceNet.stripHtml(c.desc),
        thumbnail: this._cover(c.md_covers),
        status,
        genres,
        author: (json.authors || []).map(a => a.name).filter(Boolean).join(', '),
        artist: (json.artists || []).map(a => a.name).filter(Boolean).join(', '),
      };
    },

    // Chapters need the comic's "hid", so resolve details first, then page
    // through /comic/{hid}/chapters (English only).
    async getChapterList(mangaUrl) {
      const detail = await this._detailJson(this._slug(mangaUrl));
      const hid = (detail.comic || detail).hid;
      if (!hid) { console.error('ComicK: no hid on details', detail); return []; }
      const all = [];
      for (let page = 1; page <= 100; page++) {
        const url = `${this.apiUrl}/comic/${hid}/chapters?lang=en&page=${page}&limit=100&tachiyomi=true`;
        const json = await SourceNet.json(url, this._headers());
        const chs = json.chapters || [];
        if (!chs.length) break;
        for (const ch of chs) {
          const num = ch.chap || '';
          const vol = ch.vol ? `Vol.${ch.vol} ` : '';
          const extra = ch.title ? ` - ${ch.title}` : '';
          all.push({
            name: `${vol}Chapter ${num}${extra}`.trim(),
            chapter: String(num),
            date: ch.created_at || '',
            url: `${this.apiUrl}/chapter/${ch.hid}?tachiyomi=true`,
          });
        }
        if (chs.length < 100) break;
      }
      return all;
    },

    async getPages(chapterUrl) {
      const json = await SourceNet.json(chapterUrl, this._headers());
      const imgs = json.chapter?.md_images || [];
      if (!imgs.length) console.warn('ComicK: no md_images in chapter', json);
      return imgs.map((im, i) => ({ url: `${this.imgUrl}/${im.b2key}`, index: i }))
        .filter(p => p.url && !p.url.endsWith('/undefined'));
    },
  };

  // ==================== BATO.TO SOURCE ====================
  // bato.to — large multi-language community source (custom theme, HTML).
  // Listing/detail selectors are the standard Bato ones. NOTE: Bato periodically
  // changes its reader page format; getPages() extracts the `const imgHttps`
  // JSON array the reader script embeds, with a DOM fallback. If pages break,
  // the console error will show which path failed.
  const BatoSource = {
    name: "Bato.to",
    baseUrl: "https://bato.to",
    _headers() { return { Referer: `${this.baseUrl}/` }; },
    _fetchImageBase64(url) { return SourceNet.imageBase64(url, this._headers()); },

    // Popular = views (all-time) desc; latest = last-updated desc. These browse
    // sort values are a best guess for Bato's current params — if popular/latest
    // look like a plain browse, that's the likely cause.
    async _search(page, query, sort) {
      const url = query
        ? `${this.baseUrl}/search?word=${encodeURIComponent(query)}&page=${page}`
        : `${this.baseUrl}/browse?sort=${sort}&page=${page}`;
      const doc = await SourceNet.doc(url, this._headers());
      const items = Array.from(doc.querySelectorAll('#series-list .item, .series-list .item'));
      if (!items.length) console.warn('Bato.to: listing empty (check "#series-list .item")');
      return items.map(it => {
        const link = it.querySelector('a.item-cover') || it.querySelector('a.item-title') || it.querySelector('a[href*="/series/"]');
        const img = it.querySelector('img');
        const titleEl = it.querySelector('.item-title');
        return {
          title: (titleEl?.textContent || link?.getAttribute('title') || '').trim(),
          url: SourceNet.abs(this.baseUrl, link?.getAttribute('href')),
          thumbnail: img ? SourceNet.abs(this.baseUrl, SourceNet.imgSrc(img)) : '',
        };
      }).filter(x => x.url);
    },
    getPopularManga(page = 1) { return this._search(page, '', 'views_a.za'); },
    getLatestUpdates(page = 1) { return this._search(page, '', 'update.za'); },
    searchManga(query, page = 1) { return this._search(page, query, ''); },

    async getMangaDetails(mangaUrl) {
      const doc = await SourceNet.doc(mangaUrl, this._headers());
      const title = (doc.querySelector('h3.item-title a, .item-title, h3.item-title')?.textContent || '').trim();
      const img = doc.querySelector('.attr-cover img, .item-cover img');
      const description = (doc.querySelector('.limit-html, #limit-height-body-summary .limit-html')?.textContent || '').trim();
      let status = 0, author = '', genres = [];
      doc.querySelectorAll('.attr-item').forEach(el => {
        const label = (el.querySelector('b')?.textContent || '').toLowerCase();
        const val = (el.querySelector('span')?.textContent || '').trim();
        if (label.includes('status')) { if (/ongoing/i.test(val)) status = 1; else if (/completed/i.test(val)) status = 2; }
        else if (label.includes('author')) author = val;
        else if (label.includes('genre')) genres = Array.from(el.querySelectorAll('span u, span')).map(s => (s.textContent || '').trim()).filter(Boolean);
      });
      return { title, description, thumbnail: img ? SourceNet.abs(this.baseUrl, SourceNet.imgSrc(img)) : '', status, genres, author, artist: '' };
    },

    async getChapterList(mangaUrl) {
      const doc = await SourceNet.doc(mangaUrl, this._headers());
      let links = Array.from(doc.querySelectorAll('a.chapt'));
      if (!links.length) links = Array.from(doc.querySelectorAll('.episode-list a[href*="/chapter/"], div.main div.p-2 a[href*="/chapter/"]'));
      if (!links.length) console.warn('Bato.to: no chapters found (check "a.chapt")', mangaUrl);
      return links.map(a => {
        const name = (a.querySelector('b')?.textContent || a.textContent || '').trim();
        const row = a.closest('.p-2, .item');
        const date = (row?.querySelector('i.extra, .extra, time')?.textContent || '').trim();
        return {
          name: name || 'Chapter',
          chapter: (name.match(/([\d.]+)/) || [])[1] || name,
          date,
          url: SourceNet.abs(this.baseUrl, a.getAttribute('href')),
        };
      });
    },

    async getPages(chapterUrl) {
      const html = await SourceNet.text(chapterUrl, this._headers());
      const m = html.match(/const\s+imgHttps\s*=\s*(\[[^\]]*\])/) ||
        html.match(/imgHttpLis\s*=\s*(\[[^\]]*\])/) ||
        html.match(/"imgList"\s*:\s*(\[[^\]]*\])/);
      if (m) {
        try {
          const arr = JSON.parse(m[1]);
          if (arr.length) return arr.map((u, i) => ({ url: u, index: i }));
        } catch (e) { console.error('Bato.to: imgHttps parse failed', e); }
      }
      const doc = new DOMParser().parseFromString(html, 'text/html');
      const imgs = Array.from(doc.querySelectorAll('#viewer img, .page-img, img.page-img'));
      if (imgs.length) return imgs.map((im, i) => ({ url: SourceNet.abs(this.baseUrl, SourceNet.imgSrc(im)), index: i })).filter(p => p.url);
      console.error('Bato.to: could not find page images on', chapterUrl);
      return [];
    },
  };

  // ==================== MANGAFIRE SOURCE ====================
  // mangafire.to — HTML listing + JSON AJAX for chapters/pages. The numeric id
  // after the dot in a /manga/{slug}.{id} URL is what the AJAX endpoints key on.
  // CAVEAT: some MangaFire images are scrambled (the AJAX page entry carries a
  // non-zero offset); descrambling isn't implemented here, so a scrambled title
  // may show shuffled pages. Most titles are unscrambled. Console will warn if a
  // scrambled offset is seen.
  const MangaFireSource = {
    name: "MangaFire",
    baseUrl: "https://mangafire.to",
    _headers() { return { Referer: `${this.baseUrl}/`, 'X-Requested-With': 'XMLHttpRequest' }; },
    _fetchImageBase64(url) { return SourceNet.imageBase64(url, { Referer: `${this.baseUrl}/` }); },
    _id(mangaUrl) { const slug = mangaUrl.split('/manga/')[1]?.split('/')[0] || ''; return slug.split('.').pop(); },

    async _search(page, query, sort) {
      const url = query
        ? `${this.baseUrl}/filter?keyword=${encodeURIComponent(query)}&page=${page}`
        : `${this.baseUrl}/filter?sort=${sort}&page=${page}`;
      const doc = await SourceNet.doc(url, { Referer: `${this.baseUrl}/` });
      const items = Array.from(doc.querySelectorAll('.original .unit, .unit'));
      if (!items.length) console.warn('MangaFire: listing empty (check ".unit")');
      return items.map(it => {
        const a = it.querySelector('.info a') || it.querySelector('a[href*="/manga/"]');
        const img = it.querySelector('img');
        return {
          title: (it.querySelector('.info a')?.textContent || a?.getAttribute('title') || '').trim(),
          url: SourceNet.abs(this.baseUrl, a?.getAttribute('href')),
          thumbnail: img ? SourceNet.abs(this.baseUrl, SourceNet.imgSrc(img)) : '',
        };
      }).filter(x => x.url);
    },
    getPopularManga(page = 1) { return this._search(page, '', 'trending'); },
    getLatestUpdates(page = 1) { return this._search(page, '', 'recently_updated'); },
    searchManga(query, page = 1) { return this._search(page, query, ''); },

    async getMangaDetails(mangaUrl) {
      const doc = await SourceNet.doc(mangaUrl, { Referer: `${this.baseUrl}/` });
      const title = (doc.querySelector('.info h1, .manga-detail h1, h1')?.textContent || '').trim();
      const img = doc.querySelector('.poster img, .manga-detail .poster img');
      const description = (doc.querySelector('.description, #synopsis, .info .description')?.textContent || '').trim();
      const genres = Array.from(doc.querySelectorAll('a[href*="/genre"]')).map(a => (a.textContent || '').trim()).filter(Boolean);
      let status = 0;
      const st = (doc.querySelector('.info .min-info, .status, .info p')?.textContent || '').toLowerCase();
      if (/releasing|ongoing/.test(st)) status = 1; else if (/completed/.test(st)) status = 2;
      return { title, description, thumbnail: img ? SourceNet.abs(this.baseUrl, SourceNet.imgSrc(img)) : '', status, genres, author: '', artist: '' };
    },

    async getChapterList(mangaUrl) {
      const id = this._id(mangaUrl);
      const json = await SourceNet.json(`${this.baseUrl}/ajax/read/${id}/chapter/en`, this._headers());
      const html = json?.result?.html || '';
      const doc = new DOMParser().parseFromString(html, 'text/html');
      const links = Array.from(doc.querySelectorAll('a[data-id]'));
      if (!links.length) console.warn('MangaFire: no chapters in AJAX result', json);
      return links.map(a => {
        const num = a.getAttribute('data-number') || '';
        const cid = a.getAttribute('data-id');
        const name = (a.textContent || '').trim() || `Chapter ${num}`;
        return { name, chapter: String(num), date: '', url: `${this.baseUrl}/ajax/read/chapter/${cid}` };
      });
    },

    async getPages(chapterUrl) {
      const json = await SourceNet.json(chapterUrl, this._headers());
      const images = json?.result?.images || [];
      if (!images.length) console.warn('MangaFire: no images in AJAX result', json);
      let scrambledSeen = false;
      const out = images.map((im, i) => {
        const url = Array.isArray(im) ? im[0] : (im.url || im);
        const offset = Array.isArray(im) ? im[2] : 0;
        if (offset && offset !== -1 && offset !== 0) scrambledSeen = true;
        return { url, index: i };
      }).filter(p => p.url);
      if (scrambledSeen) console.warn('MangaFire: this chapter has scrambled pages; descrambling is not implemented, so pages may look shuffled.');
      return out;
    },
  };

  // ==================== FLAME COMICS SOURCE ====================
  // flamecomics.xyz — a Next.js site. Data is embedded in the page's
  // <script id="__NEXT_DATA__"> JSON blob rather than a REST API. INFERRED:
  // Flame Comics rewrote its site and the exact JSON field names shift between
  // versions, so several field lookups below are best-effort with fallbacks and
  // console warnings. If browsing/reading is empty, the warnings dump the shape
  // to correct against.
  const FlameComicsSource = {
    name: "Flame Comics",
    baseUrl: "https://flamecomics.xyz",
    cdnUrl: "https://cdn.flamecomics.xyz",
    _headers() { return { Referer: `${this.baseUrl}/` }; },
    _fetchImageBase64(url) { return SourceNet.imageBase64(url, this._headers()); },

    async _nextData(url) {
      const html = await SourceNet.text(url, this._headers());
      const m = html.match(/<script id="__NEXT_DATA__" type="application\/json">([\s\S]*?)<\/script>/);
      if (!m) { console.error('Flame Comics: no __NEXT_DATA__ blob on', url); return null; }
      try { return JSON.parse(m[1]); } catch (e) { console.error('Flame Comics: __NEXT_DATA__ parse failed', e); return null; }
    },
    _cover(s) {
      if (!s) return '';
      if (s.cover && s.series_id) return `${this.cdnUrl}/uploads/images/series/${s.series_id}/${s.cover}`;
      if (typeof s.cover === 'string' && s.cover.startsWith('http')) return s.cover;
      return '';
    },
    _id(mangaUrl) { return mangaUrl.split('/series/')[1]?.split('/')[0] || ''; },

    async _browse() {
      const data = await this._nextData(`${this.baseUrl}/browse`);
      const series = data?.props?.pageProps?.series || data?.props?.pageProps?.data || [];
      if (!series.length) console.warn('Flame Comics: no series in browse pageProps', data?.props?.pageProps);
      return series.map(s => ({ title: s.title || '', url: `${this.baseUrl}/series/${s.series_id}`, thumbnail: this._cover(s) }));
    },
    getPopularManga() { return this._browse(); },
    getLatestUpdates() { return this._browse(); },
    async searchManga(query) {
      const all = await this._browse();
      const t = (query || '').toLowerCase();
      return all.filter(m => m.title.toLowerCase().includes(t));
    },

    async getMangaDetails(mangaUrl) {
      const data = await this._nextData(mangaUrl);
      const s = data?.props?.pageProps?.series || {};
      let status = 0;
      if (/ongoing/i.test(s.status)) status = 1; else if (/completed|finished/i.test(s.status)) status = 2;
      return {
        title: s.title || '',
        description: SourceNet.stripHtml(s.description),
        thumbnail: this._cover(s),
        status,
        genres: (s.tags || s.genres || []).map(t => (typeof t === 'string' ? t : t.name || '')).filter(Boolean),
        author: s.author || '', artist: s.artist || '',
      };
    },

    async getChapterList(mangaUrl) {
      const data = await this._nextData(mangaUrl);
      const pp = data?.props?.pageProps || {};
      const chapters = pp.chapters || pp.series?.chapters || [];
      const seriesId = this._id(mangaUrl);
      if (!chapters.length) console.warn('Flame Comics: no chapters in pageProps', pp);
      return chapters.map(ch => ({
        name: ch.title ? `Chapter ${ch.chapter} - ${ch.title}` : `Chapter ${ch.chapter}`,
        chapter: String(ch.chapter ?? ''),
        date: ch.release_date || ch.created_at || '',
        url: `${this.baseUrl}/series/${seriesId}/${ch.token}`,
      }));
    },

    async getPages(chapterUrl) {
      const data = await this._nextData(chapterUrl);
      const pp = data?.props?.pageProps || {};
      const ch = pp.chapter || {};
      const seriesId = ch.series_id || this._id(chapterUrl);
      let imgs = ch.images || pp.images || [];
      if (!Array.isArray(imgs)) imgs = Object.values(imgs);
      if (!imgs.length) console.warn('Flame Comics: no images in chapter pageProps', pp);
      return imgs.map((im, i) => {
        const name = typeof im === 'string' ? im : (im.name || im.url || '');
        const url = name.startsWith('http') ? name : `${this.cdnUrl}/uploads/images/${seriesId}/${ch.token}/${name}`;
        return { url, index: i };
      }).filter(p => p.url && !p.url.endsWith('/'));
    },
  };

  // ==================== REAPER SCANS SOURCE ====================
  // reaperscans.com — custom platform backed by a JSON API (api.reaperscans.com).
  // INFERRED: Reaper's API has changed repeatedly and isn't publicly documented,
  // so endpoint/field names below are best-effort with fallbacks + warnings.
  // Thumbnails are served from their media CDN. If lists/pages are empty, the
  // console warnings show the returned shape to correct against.
  const ReaperScansSource = {
    name: "Reaper Scans",
    baseUrl: "https://reaperscans.com",
    apiUrl: "https://api.reaperscans.com",
    mediaUrl: "https://media.reaperscans.com/file/4SRBHm",
    _headers() { return { Referer: `${this.baseUrl}/` }; },
    _fetchImageBase64(url) { return SourceNet.imageBase64(url, this._headers()); },
    _thumb(t) { if (!t) return ''; return String(t).startsWith('http') ? t : `${this.mediaUrl}/${t}`; },
    _slug(mangaUrl) { return mangaUrl.split('/series/')[1]?.split('/')[0] || ''; },

    async _query(page, query, orderBy) {
      const url = new URL(`${this.apiUrl}/query`);
      url.searchParams.set('page', String(page));
      url.searchParams.set('perpage', '20');
      url.searchParams.set('series_type', 'Comic');
      url.searchParams.set('query_string', query || '');
      url.searchParams.set('order', 'desc');
      url.searchParams.set('orderBy', orderBy || 'total_views');
      url.searchParams.set('adult', 'true');
      const json = await SourceNet.json(url.href, this._headers());
      const items = json.data || (Array.isArray(json) ? json : []);
      if (!items.length) console.warn('Reaper Scans: query returned no items', json);
      return items.map(it => ({
        title: it.title || '',
        url: `${this.baseUrl}/series/${it.series_slug || it.slug}`,
        thumbnail: this._thumb(it.thumbnail),
      }));
    },
    getPopularManga(page = 1) { return this._query(page, '', 'total_views'); },
    getLatestUpdates(page = 1) { return this._query(page, '', 'latest'); },
    searchManga(query, page = 1) { return this._query(page, query, 'total_views'); },

    async _seriesJson(slug) {
      return SourceNet.json(`${this.apiUrl}/series/${slug}`, this._headers());
    },
    async getMangaDetails(mangaUrl) {
      const s = await this._seriesJson(this._slug(mangaUrl));
      const d = s.data || s;
      let status = 0;
      if (/ongoing/i.test(d.status)) status = 1; else if (/completed|dropped|hiatus/i.test(d.status)) status = 2;
      return {
        title: d.title || '',
        description: SourceNet.stripHtml(d.description),
        thumbnail: this._thumb(d.thumbnail),
        status,
        genres: (d.tags || d.genres || []).map(g => (typeof g === 'string' ? g : g.name || '')).filter(Boolean),
        author: d.author || '', artist: d.studio || '',
      };
    },

    async getChapterList(mangaUrl) {
      const slug = this._slug(mangaUrl);
      const s = await this._seriesJson(slug);
      const seriesId = (s.data || s).id;
      const all = [];
      for (let page = 1; page <= 100; page++) {
        const url = `${this.apiUrl}/chapters/${seriesId}?page=${page}&perpage=100&order=desc`;
        let json;
        try { json = await SourceNet.json(url, this._headers()); }
        catch (e) { console.warn('Reaper Scans: chapters endpoint failed, trying inline chapters', e); break; }
        const chs = json.data || json.chapters || (Array.isArray(json) ? json : []);
        if (!chs.length) break;
        for (const ch of chs) {
          all.push({
            name: ch.chapter_name || ch.chapter_title || `Chapter ${ch.chapter_number ?? ''}`,
            chapter: String(ch.chapter_number ?? ''),
            date: ch.created_at || '',
            url: `${this.apiUrl}/chapter/${slug}/${ch.chapter_slug || ch.slug}`,
          });
        }
        if (chs.length < 100) break;
      }
      // Fallback: some responses embed chapters directly on the series object.
      if (!all.length) {
        const inline = (s.data || s).chapters || [];
        inline.forEach(ch => all.push({
          name: ch.chapter_name || `Chapter ${ch.chapter_number ?? ''}`,
          chapter: String(ch.chapter_number ?? ''),
          date: ch.created_at || '',
          url: `${this.apiUrl}/chapter/${slug}/${ch.chapter_slug || ch.slug}`,
        }));
        if (!all.length) console.error('Reaper Scans: could not build a chapter list', s);
      }
      return all;
    },

    async getPages(chapterUrl) {
      const json = await SourceNet.json(chapterUrl, this._headers());
      const c = json.data || json.chapter || json;
      let imgs = c.content?.images || c.images || [];
      if (!Array.isArray(imgs)) imgs = Object.values(imgs);
      if (!imgs.length) console.warn('Reaper Scans: no images in chapter response', json);
      return imgs.map((im, i) => {
        const raw = typeof im === 'string' ? im : (im.url || im.src || '');
        return { url: this._thumb(raw), index: i };
      }).filter(p => p.url);
    },
  };

  // Registry the mdSearch fallback loader looks up by name
  // ==================== REGISTER ALL SOURCES ====================
   window.MangaSources = {
    AsuraScans: AsuraScansSource,
    MangaRead: MangaReadSource,
    ComicK: ComickSource,
    Bato: BatoSource,
    MangaFire: MangaFireSource,
    FlameComics: FlameComicsSource,
    ReaperScans: ReaperScansSource,
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
