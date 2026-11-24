// Small page helpers: styled tooltips for every [title], and smooth movement of reordered rows.
(() => {
    // ---------- tooltips ----------
    // The native tooltip is slow, plain and sometimes off-screen. Every element with a title gets a styled one
    // instead (the title moves to data-tip so the system one never shows on top).
    let tip = null, timer = 0, cur = null;
    function hide() {
        clearTimeout(timer); timer = 0; cur = null;
        if (tip) tip.classList.remove('on');
    }
    function show(el, text) {
        if (!el.isConnected || cur !== el) return;
        if (!tip) { tip = document.createElement('div'); tip.className = 'pm-tip'; tip.setAttribute('role', 'tooltip'); document.body.appendChild(tip); }
        tip.textContent = text;
        const r = el.getBoundingClientRect();
        tip.style.left = '0px'; tip.style.top = '0px';
        tip.classList.add('measure');
        const w = tip.offsetWidth, h = tip.offsetHeight;
        tip.classList.remove('measure');
        const below = r.bottom + 8 + h < innerHeight - 4;
        const x = Math.max(6, Math.min(innerWidth - w - 6, r.left + r.width / 2 - w / 2));
        const y = below ? r.bottom + 8 : r.top - h - 8;
        tip.style.left = x + 'px'; tip.style.top = Math.max(4, y) + 'px';
        tip.classList.toggle('up', !below);
        tip.classList.add('on');
    }
    document.addEventListener('mouseover', e => {
        const el = e.target.closest && e.target.closest('[title],[data-tip]');
        if (el === cur) return;
        hide();
        if (!el) return;
        const text = el.getAttribute('title') || el.dataset.tip;
        if (!text) return;
        if (el.hasAttribute('title')) { el.dataset.tip = text; el.removeAttribute('title'); }
        cur = el;
        // Quick for icon buttons, a bit slower for long explanations.
        timer = setTimeout(() => show(el, el.dataset.tip || text), text.length > 60 ? 650 : 380);
    }, true);
    document.addEventListener('mouseout', e => { if (!e.relatedTarget) hide(); }, true);
    for (const ev of ['mousedown', 'wheel', 'keydown']) document.addEventListener(ev, hide, {capture: true, passive: true});

    // ---------- FLIP: rows glide to their new place ----------
    // A list marked data-flip whose children carry data-key: when the children are reordered (drag and drop,
    // sorting, a song moved in the queue), each row starts where it was and slides to where it is now.
    const reduced = matchMedia('(prefers-reduced-motion: reduce)');
    const snaps = new WeakMap();
    function snap(c) {
        const m = new Map();
        for (const ch of c.children) { const k = ch.dataset.key; if (k) m.set(k, ch.offsetTop); }
        snaps.set(c, m);
    }
    function snapAll() { for (const c of document.querySelectorAll('[data-flip]')) snap(c); }
    // Every reorder starts with a press (the handle, a sort option, a menu item): remember positions then.
    document.addEventListener('pointerdown', snapAll, {capture: true, passive: true});
    function flip(c) {
        const old = snaps.get(c);
        if (!old || reduced.matches || c.children.length > 600) { snap(c); return; }
        const moved = [];
        for (const ch of c.children) {
            const k = ch.dataset.key;
            if (!k || !old.has(k)) continue;
            const dy = old.get(k) - ch.offsetTop;
            if (Math.abs(dy) > 1 && Math.abs(dy) < 4000) moved.push([ch, dy]);
        }
        snap(c);
        if (!moved.length) return;
        for (const [ch, dy] of moved) { ch.style.transition = 'none'; ch.style.transform = `translateY(${dy}px)`; }
        void c.offsetHeight;
        requestAnimationFrame(() => {
            for (const [ch] of moved) { ch.style.transition = 'transform 340ms cubic-bezier(.2,.8,.2,1)'; ch.style.transform = ''; }
            setTimeout(() => { for (const [ch] of moved) ch.style.transition = ''; }, 380);
        });
    }
    new MutationObserver(muts => {
        const lists = new Set();
        for (const m of muts) if (m.type === 'childList' && m.target.dataset && m.target.dataset.flip !== undefined) lists.add(m.target);
        for (const c of lists) flip(c);
    }).observe(document.documentElement, {childList: true, subtree: true});

    // Scroll the playing song of a queue to the top of its panel (instead of the list's start or end).
    window.pmShowCurrent = () => requestAnimationFrame(() => {
        for (const el of document.querySelectorAll('.drag-wrap.current')) {
            const sc = el.closest('.full-body, .sp-body');
            if (!sc) continue;
            const head = sc.querySelector('.queue-head');
            const off = el.getBoundingClientRect().top - sc.getBoundingClientRect().top;
            sc.scrollTop = Math.max(0, sc.scrollTop + off - (head ? head.offsetHeight + 8 : 8));
        }
    });
})();
