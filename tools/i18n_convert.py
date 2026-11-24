#!/usr/bin/env python3
# Usage: python3 tools/i18n_convert.py app/src/main/java/com/example/privacymusic app/src/main/assets/i18n/source.json
# Wraps new Ukrainian UI literals in T("…") and adds them to the catalogue; then translate the new ids in every
# assets/i18n/<lang>.json (I18nTest fails until all languages have them).
"""Wraps Ukrainian string literals in Kotlin sources with T("...", args) calls and builds the source catalogue."""
import re, sys, json, os, glob

CYR = re.compile('[а-яА-ЯіїєґІЇЄҐ]')
SKIP_LINE = ('find(', 'Diag.log(', 'Regex(', 'const val', '@Suppress', 'private val raw', 'Tab("')

def parse_string(src, i):
    """src[i] == '"' (not raw). Returns (end_index_after_closing_quote, parts) where parts is a list of
    ('text', str) / ('expr', str) with expr already being raw Kotlin source of the template expression."""
    assert src[i] == '"'
    i += 1; parts = []; buf = []
    while True:
        c = src[i]
        if c == '\\':
            buf.append(src[i:i+2]); i += 2; continue
        if c == '"':
            if buf: parts.append(('text', ''.join(buf)))
            return i + 1, parts
        if c == '$' and src[i+1] == '{':
            if buf: parts.append(('text', ''.join(buf))); buf = []
            j = skip_code(src, i + 2, '}')
            parts.append(('expr', src[i+2:j])); i = j + 1; continue
        if c == '$' and (src[i+1].isalpha() or src[i+1] == '_'):
            if buf: parts.append(('text', ''.join(buf))); buf = []
            j = i + 1
            while j < len(src) and (src[j].isalnum() or src[j] == '_'): j += 1
            parts.append(('expr', src[i+1:j])); i = j; continue
        buf.append(c); i += 1

def skip_code(src, i, close):
    """Skips Kotlin code until the matching `close` char at depth 0, honouring nested strings/braces."""
    depth = 0
    while True:
        c = src[i]
        if c == '"':
            if src.startswith('"""', i): i = src.index('"""', i + 3) + 3; continue
            i, _ = parse_string(src, i); continue
        if c == "'":
            i = skip_char(src, i); continue
        if c in '({[': depth += 1
        elif c in ')}]':
            if depth == 0 and c == close: return i
            depth -= 1
        i += 1

def skip_char(src, i):
    j = i + 1
    if src[j] == '\\': j += 2
    else: j += 1
    while src[j] != "'": j += 1
    return j + 1

def unescape(s):
    out=[]; i=0
    while i < len(s):
        c=s[i]
        if c=='\\' and i+1 < len(s):
            n=s[i+1]
            if n=='u': out.append(chr(int(s[i+2:i+6],16))); i+=6; continue
            out.append({'n':'\n','t':'\t','r':'\r','b':'\b'}.get(n,n)); i+=2; continue
        out.append(c); i+=1
    return ''.join(out)

def transform(src, catalogue):
    out = []; i = 0; n = len(src)
    while i < n:
        c = src[i]
        if src.startswith('//', i):
            j = src.find('\n', i); j = n if j < 0 else j
            out.append(src[i:j]); i = j; continue
        if src.startswith('/*', i):
            j = src.index('*/', i) + 2; out.append(src[i:j]); i = j; continue
        if c == "'":
            j = skip_char(src, i); out.append(src[i:j]); i = j; continue
        if c == '"':
            if src.startswith('"""', i):
                j = src.index('"""', i + 3) + 3; out.append(src[i:j]); i = j; continue
            j, parts = parse_string(src, i)
            literal = src[i:j]
            line_start = src.rfind('\n', 0, i) + 1; line_end = src.find('\n', i)
            line = src[line_start:line_end]
            text_only = ''.join(p[1] for p in parts if p[0] == 'text')
            already = src[max(0, i-2):i] == 'T('
            if CYR.search(text_only) and not any(k in line for k in SKIP_LINE) and not already:
                pattern = []; args = []
                for kind, val in parts:
                    if kind == 'text': pattern.append(val.replace('{', '\\u007B') if False else val)
                    else:
                        pattern.append('{%d}' % len(args)); args.append(transform(val, catalogue))
                pat = ''.join(pattern)
                catalogue.add(unescape(pat))
                out.append('T("' + pat + '"' + ''.join(', ' + a for a in args) + ')')
            elif already and CYR.search(text_only):
                catalogue.add(unescape(text_only)); out.append(literal)
            else:
                # still translate Ukrainian literals nested inside template expressions
                if any(p[0] == 'expr' for p in parts) and not CYR.search(text_only):
                    rebuilt = ['"']
                    for kind, val in parts:
                        rebuilt.append(val if kind == 'text' else '${' + transform(val, catalogue) + '}')
                    rebuilt.append('"'); out.append(''.join(rebuilt))
                else:
                    out.append(literal)
            i = j; continue
        out.append(c); i += 1
    return ''.join(out)

def main(root, catalogue_path):
    catalogue = set()
    for p in sorted(glob.glob(os.path.join(root, '**/*.kt'), recursive=True)):
        if p.endswith('I18n.kt') or p.endswith('Links.kt'): continue
        s = open(p).read()
        t = transform(s, catalogue)
        if t != s:
            if 'import com.example.privacymusic.i18n.T' not in t and not t.startswith('package com.example.privacymusic.i18n'):
                t = re.sub(r'(^package [^\n]+\n)', r'\1\nimport com.example.privacymusic.i18n.T\n', t, count=1)
            open(p, 'w').write(t)
    existing = {}
    if os.path.exists(catalogue_path): existing = json.load(open(catalogue_path))
    known = set(existing.values())
    next_id = max([int(k, 36) for k in existing] + [-1]) + 1
    def b36(n):
        d = '0123456789abcdefghijklmnopqrstuvwxyz'; s = ''
        while True:
            s = d[n % 36] + s; n //= 36
            if n == 0: return s
    for text in sorted(catalogue):
        if text not in known:
            existing[b36(next_id)] = text; next_id += 1
    json.dump(existing, open(catalogue_path, 'w'), ensure_ascii=False, indent=0, sort_keys=False)
    print(len(catalogue), 'strings in use;', len(existing), 'in catalogue')

if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
