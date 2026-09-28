"""Flag `const val` declarations that Kotlin will not compile.

`const val` is legal at file top level, directly inside a named `object`,
and directly inside a `companion object`. It is NOT legal directly inside a
plain `class` body -- that is the error CI reports as
"Const 'val' is only allowed on top level, in named objects, or in
companion objects".
"""
import re
import sys
import glob

DECL = re.compile(r'^\s*(?:@[\w.]+\s+)*(?:public |private |internal |protected )*'
                  r'(?:data |value |enum |sealed |abstract |open |)(class|object|interface)\s+(\w+)')
COMPANION = re.compile(r'^\s*(?:public |private |internal |protected )*companion\s+object')
CONST = re.compile(r'^\s*(?:public |private |internal |protected )*const\s+val\s+(\w+)')


def enclosing(src):
    """Yield (line_no, stack) where stack lists enclosing declaration names."""
    stack = []          # list of dicts: {name, kind}
    depth = 0
    events = []         # (char_index, line_no)
    for i, line in enumerate(src.split('\n'), 1):
        events.append((len(line), i, line))
    out = []
    line_no = 0
    pos = 0
    lines = src.split('\n')
    ci = 0
    for li, line in enumerate(lines, 1):
        stripped = re.sub(r'//.*$', '', line)
        decl = COMPANION.match(stripped)
        klass = DECL.match(stripped)
        name = None
        if decl:
            stack.append({'name': 'companion object', 'kind': 'companion', 'line': li})
        elif klass:
            kind = klass.group(1)
            stack.append({'name': klass.group(2), 'kind': kind, 'line': li})
        out.append((li, [d['kind'] for d in stack], line))
        # close scopes that end on this line
        opens = stripped.count('{')
        closes = stripped.count('}')
        if opens == 0 and closes:
            for _ in range(min(closes, len(stack))):
                stack.pop()
    return out


def main():
    bad = []
    for f in sorted(glob.glob(sys.argv[1] if len(sys.argv) > 1 else '*.kt')):
        src = open(f).read()
        src = re.sub(r'/\*.*?\*/', lambda m: '\n' * m.group(0).count('\n'), src, flags=re.S)
        for li, kinds, line in enclosing(src):
            if not CONST.match(line):
                continue
            if not kinds:
                continue                       # file top level, legal
            top = kinds[-1]
            if top in ('object', 'companion'):
                continue                       # legal
            bad.append((f, li, line.strip(), top))
    if bad:
        for f, li, line, top in bad:
            print(f'  ILLEGAL const {f}:{li} (inside a {top} body) -> {line[:70]}')
        return 1
    print('  no illegal const declarations')
    return 0


sys.exit(main())
