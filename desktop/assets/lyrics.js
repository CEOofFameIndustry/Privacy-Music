// The audio thread supplies clock samples. All drawing and scrolling share the web view's frame clock.
(() => {
    let root, scroller, lines = [], active = -1, points = [], words = [], cursor = 0;
    let base = 0, sampledAt = 0, playing = false, lastSample = null;
    let frameId = 0, glide, manualUntil = 0, centerPending = false, aimed = -1;
    // Timing (ms). A scroll starts GLIDE before its line so it lands as the line begins (never late);
    // a line lights LEAD early because the eye needs a moment to catch up.
    const GLIDE = 420, LEAD = 60;
    const reducedMotion = matchMedia('(prefers-reduced-motion: reduce)');

    function upperBound(items, time, timestamp) {
        let lo = 0, hi = items.length;
        while (lo < hi) {
            const mid = lo + Math.floor((hi - lo) / 2);
            if (timestamp(items[mid]) <= time) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }
    const lineTime = line => line.time;
    const pointTime = point => point[0];

    function requestFrame() {
        if (root && !frameId && !document.hidden) frameId = requestAnimationFrame(draw);
    }
    function bind(nextRoot) {
        // A quick close/reopen can reuse the same DOM during the exit transition.
        if (root?.isConnected) {
            for (const line of lines) line.el.classList.remove('active', 'past');
            for (const {el} of words) {
                el.classList.remove('sung', 'partial');
                el.style.removeProperty('--p');
            }
        }
        root = nextRoot;
        scroller = root?.closest('.full-body, .sp-body');
        lines = root ? Array.from(root.querySelectorAll('.lyr-line'), el => ({el, time: Number(el.dataset.time)})) : [];
        active = -1;
        words = [];
        points = [];
        cursor = 0;
        manualUntil = 0;
        centerPending = true;
        aimed = -1;
        glide = null;
    }
    window.pmReleaseLyrics = () => requestAnimationFrame(() => {
        if (!root?.isConnected) bind(null);
    });
    window.pmSync = (sample, running, snap) => {
        const nextRoot = document.querySelector('.player-overlay.open .lyrics, .sidepanel .lyrics');
        const changed = nextRoot !== root;
        if (changed) bind(nextRoot);
        const now = performance.now();
        const predicted = base + (playing ? now - sampledAt : 0);
        const moving = running && (sample !== lastSample || changed);
        base = !snap && !changed && moving && playing && Math.abs(sample - predicted) < 160
            ? predicted + (sample - predicted) * 0.15 : sample;
        sampledAt = now;
        playing = moving;
        lastSample = sample;
        if (snap) centerPending = true;
        requestFrame();
    };

    function userScroll(event) {
        if (!scroller || !scroller.contains(event.target)) return;
        manualUntil = performance.now() + 3500;
        glide = null;
        centerPending = true;
    }
    document.addEventListener('wheel', userScroll, {passive: true});
    document.addEventListener('touchmove', userScroll, {passive: true});
    document.addEventListener('pointerdown', event => {
        if (event.target === scroller) userScroll(event);
    }, {passive: true});
    window.addEventListener('resize', () => { centerPending = true; requestFrame(); });

    /** Scrolls so line `index` sits at 38 % height, arriving after `ms` (an ease-in-out glide). */
    function centerLine(index, now, ms) {
        aimed = index;
        const from = scroller.scrollTop;
        const offset = lines[index].el.getBoundingClientRect().top - scroller.getBoundingClientRect().top;
        const to = Math.max(0, Math.min(scroller.scrollHeight - scroller.clientHeight,
            from + offset - scroller.clientHeight * 0.38));
        glide = null;
        if (reducedMotion.matches || Math.abs(to - from) < 2 || ms <= 0) scroller.scrollTop = to;
        else glide = {from, to, start: now, ms};
    }
    function activate(index) {
        if (active >= 0) {
            lines[active].el.classList.remove('active');
            for (const {el} of words) {
                el.classList.remove('sung', 'partial');
                el.style.removeProperty('--p');
            }
        }
        for (let i = Math.max(0, Math.min(index, active)); i < Math.max(index, active); i++) {
            lines[i].el.classList.toggle('past', i < index);
        }
        active = index;
        cursor = 0;
        if (index < 0) { glide = null; return; }
        const el = lines[index].el;
        el.classList.remove('past');
        el.classList.add('active');
        words = Array.from(el.querySelectorAll('.w'), el => ({el, start: +el.dataset.a, end: +el.dataset.b}));
        points = JSON.parse(el.dataset.points);
        // Going backwards (a seek) or jumping far ahead: recenter now instead of waiting for the early glide.
        if (index < aimed || index > aimed + 1) centerPending = true;
    }
    function fill(time) {
        const next = upperBound(points, time, pointTime);
        let chars = 0;
        if (next) {
            const [t, offset] = points[next - 1];
            chars = offset;
            if (next < points.length) {
                const [end, target] = points[next];
                chars += (target - offset) * (time - t) / (end - t);
            }
        }
        while (cursor && words[cursor - 1].end > chars) {
            if (cursor < words.length) words[cursor].el.classList.remove('partial');
            words[--cursor].el.classList.remove('sung');
        }
        while (cursor < words.length && words[cursor].end <= chars) {
            const el = words[cursor++].el;
            el.classList.remove('partial');
            el.classList.add('sung');
        }
        const word = words[cursor];
        if (word) {
            const fraction = Math.max(0, (chars - word.start) / (word.end - word.start));
            word.el.classList.toggle('partial', fraction > 0);
            if (fraction > 0) word.el.style.setProperty('--p', (fraction * 100).toFixed(1) + '%');
        }
    }
    function draw(now) {
        frameId = 0;
        if (!root?.isConnected) { bind(null); return; }
        if (document.hidden) return;
        const time = base + (playing ? Math.max(0, now - sampledAt) : 0);
        const shown = time + LEAD;
        let index = active;
        if (shown < (lines[index]?.time ?? -Infinity) || shown >= (lines[index + 1]?.time ?? Infinity)) {
            index = upperBound(lines, shown, lineTime) - 1;
        }
        if (index !== active) activate(index);
        if (active >= 0 && root.classList.contains('flow')) fill(time + LEAD / 2);
        if (now >= manualUntil && lines.length) {
            if (centerPending) {
                // After a seek, a resize or the user's own scrolling: go straight to the current line.
                centerPending = false;
                centerLine(Math.max(0, active), now, playing ? 260 : 0);
            } else if (playing) {
                // Start moving early: aim at the line that will be current when a glide started now ends.
                const next = upperBound(lines, time + GLIDE, lineTime) - 1;
                if (next > aimed && next >= 0) centerLine(next, now, Math.max(180, Math.min(GLIDE, lines[next].time - time)));
            }
        }
        if (glide) {
            const k = Math.min(1, (now - glide.start) / glide.ms);
            const e = k < .5 ? 4 * k * k * k : 1 - (-2 * k + 2) ** 3 / 2; // ease-in-out: no jolt at either end
            scroller.scrollTop = glide.from + (glide.to - glide.from) * e;
            if (k >= 1) glide = null;
        }
        if (playing || glide) requestFrame();
    }
})();
