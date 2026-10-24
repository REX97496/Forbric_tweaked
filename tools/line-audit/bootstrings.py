#!/usr/bin/env python
"""Stage 10, part 1: extract every STRING in the boot half that names a Minecraft/loader type or member.

The boot half (src/main) has no Minecraft types at compile time, so javac checks none of these; and because the same
sources build on both lines, the same strings ship to both. This script does not decide anything -- it produces the
inventory that audit-strings.py then asks the two lines' real classpaths about.

Two carriers, because they fail differently:

  java   a string literal in src/main/java. Its consumer is CODE, so whether it is even looked at depends on the
         code path (an anchor match, a registry lookup, a Class.forName). Extract with a real Java lexer: `//` in a
         URL inside a literal must not swallow the rest of the line, and a text block is a literal too.

  table  a line of one of the nine committed tables under src/main/resources. Its consumer is DATA -- a rule engine
         that reads the table and matches a row against an observed class -- so a row naming an absent class is not
         a crash, it is a row that never matches.

Namespaces kept: net/minecraft, com/mojang, net/neoforged, net/minecraftforge, net/fabricmc, org/spongepowered,
team/reborn. Those are the ones whose SPELLING or EXISTENCE varies by line; java/lang, java/util and asm/commons
names are the JDK's and are not line property (they are kept out of the audit, not out of the tables).

usage: bootstrings.py [--root forbric-kernel] [--out build/x11/tokens.tsv]
"""
import io
import os
import re
import sys

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
KERNEL = os.path.join(REPO, 'forbric-kernel')
X11 = os.path.join(KERNEL, 'build', 'x11')

LINE_NAMESPACES = (
    'net/minecraft/', 'com/mojang/', 'net/neoforged/', 'net/minecraftforge/',
    'net/fabricmc/', 'org/spongepowered/', 'team/reborn/',
)

# A class file's internal name, in either the bare or the descriptor spelling. `L` is consumed only when it is the
# descriptor marker, i.e. when what follows really is a directory-qualified name ending in a class segment.
INTERNAL = re.compile(r'(?<![A-Za-z0-9_$/.])L?((?:[a-z_][A-Za-z0-9_]*/)+[A-Za-z_$][A-Za-z0-9_$]*(?:\$[A-Za-z0-9_$]+)*)')
# A dotted fully-qualified name, as boot code writes it for Class.forName/reflection: at least one package segment.
DOTTED = re.compile(r'(?<![A-Za-z0-9_$./])((?:[a-z_][A-Za-z0-9_]*\.){2,}[A-Z][A-Za-z0-9_$]*(?:\$[A-Za-z0-9_$]+)*'
                    r'(?:\.[A-Za-z0-9_$]+)*)')
# <owner>#<member><descriptor>   -- the tables' key form, and <init>/<clinit> occur in it.
HASH_MEMBER = re.compile(r'((?:[A-Za-z0-9_$]+/)+[A-Za-z0-9_$]+)#(<[A-Za-z]+>|[A-Za-z_$][A-Za-z0-9_$]*)'
                         r'(\([^)\s]*\)[^\s|]*)')
# <owner>#<member> without a descriptor -- a plain "class#member" pin.
HASH_NAME = re.compile(r'((?:[A-Za-z0-9_$]+/)+[A-Za-z0-9_$]+)#(<[A-Za-z]+>|[A-Za-z_$][A-Za-z0-9_$]*)')
# La/b/C;member(desc)ret   and   La/b/C;field:desc   -- the "call or field" half of several tables.
DESC_REF = re.compile(r'L((?:[A-Za-z0-9_$]+/)+[A-Za-z0-9_$]+);(<[A-Za-z]+>|[A-Za-z_$][A-Za-z0-9_$]*)'
                      r'(\([^)\s]*\)[^\s|]*|:[^\s|]+)')


def iter_java_literals(path):
    """Yield (line_number, literal_text) for every string literal in one Java source file."""
    with io.open(path, 'r', encoding='utf-8', errors='replace') as fh:
        src = fh.read()
    i, n = 0, len(src)
    line = 1
    while i < n:
        ch = src[i]
        if ch == '\n':
            line += 1
            i += 1
            continue
        if ch == '/' and i + 1 < n:
            if src[i + 1] == '/':
                j = src.find('\n', i)
                i = n if j < 0 else j
                continue
            if src[i + 1] == '*':
                j = src.find('*/', i + 2)
                if j < 0:
                    return
                line += src.count('\n', i, j)
                i = j + 2
                continue
        if ch == '"':
            if src.startswith('"""', i):
                start_line = line
                j = src.find('"""', i + 3)
                if j < 0:
                    return
                body = src[i + 3:j]
                line += src.count('\n', i, j)
                i = j + 3
                yield start_line, body
                continue
            start_line = line
            j = i + 1
            out = []
            while j < n:
                c = src[j]
                if c == '\\' and j + 1 < n:
                    nxt = src[j + 1]
                    out.append({'n': '\n', 't': '\t', 'r': '\r', '"': '"', '\'': '\'', '\\': '\\'}.get(nxt, nxt))
                    j += 2
                    continue
                if c == '"':
                    break
                if c == '\n':
                    line += 1
                out.append(c)
                j += 1
            yield start_line, ''.join(out)
            i = j + 1
            continue
        if ch == "'":
            j = i + 1
            while j < n:
                if src[j] == '\\':
                    j += 2
                    continue
                if src[j] == "'":
                    break
                j += 1
            i = j + 1
            continue
        i += 1


def interesting(name):
    return name.startswith(LINE_NAMESPACES)


def tokens_from_text(text):
    """Every line-keyed name in one literal or one table line: classes first, then members/fields."""
    out = []
    seen = set()

    def add(kind, owner, member, desc):
        key = (kind, owner, member, desc)
        if key not in seen:
            seen.add(key)
            out.append(key)

    for match in HASH_MEMBER.finditer(text):
        if interesting(match.group(1)):
            add('method', match.group(1), match.group(2), match.group(3))
    for match in DESC_REF.finditer(text):
        if interesting(match.group(1)):
            tail = match.group(3)
            add('field' if tail.startswith(':') else 'method', match.group(1), match.group(2),
                tail[1:] if tail.startswith(':') else tail)
    for match in HASH_NAME.finditer(text):
        if interesting(match.group(1)):
            add('method', match.group(1), match.group(2), '*')
    for match in INTERNAL.finditer(text):
        if interesting(match.group(1)):
            add('class', match.group(1), '-', '-')
    for match in DOTTED.finditer(text):
        name = match.group(1)
        parts = name.split('.')
        # A dotted name may be a class, or a class plus a MEMBER spilled by the caller (Foo.BAR, Foo.bar()). Resolve
        # by trying the longest class prefix: the existence test then asks about the class, and the tail, if any,
        # is recorded as a name-only member query.
        for cut in range(len(parts), 1, -1):
            candidate = '/'.join(parts[:cut])
            if candidate.startswith(LINE_NAMESPACES):
                add('class', candidate, '-', '-')
                if cut < len(parts):
                    add('method', candidate, parts[cut], '*')
                break
    return out


def main(argv):
    root = KERNEL
    out_path = os.path.join(X11, 'tokens.tsv')
    java_root_override = None
    i = 1
    while i < len(argv):
        if argv[i] == '--root':
            root = argv[i + 1]; i += 2
        elif argv[i] == '--out':
            out_path = argv[i + 1]; i += 2
        elif argv[i] == '--java-root':
            java_root_override = argv[i + 1]; i += 2
        else:
            sys.stderr.write('unknown argument %s\n' % argv[i]); return 2

    rows = []
    java_root = java_root_override or os.path.join(root, 'src/main/java')
    java_files = []
    for base, _dirs, files in os.walk(java_root):
        for f in files:
            if f.endswith('.java'):
                java_files.append(os.path.join(base, f))
    java_files.sort()
    for path in java_files:
        rel = os.path.relpath(path, root).replace(os.sep, '/')
        for line, literal in iter_java_literals(path):
            for kind, owner, member, desc in tokens_from_text(literal):
                rows.append(('java', rel, line, kind, owner, member, desc))

    table_root = os.path.join(root, 'src/main/resources')
    table_files = []
    for base, _dirs, files in os.walk(table_root):
        for f in files:
            if f.endswith('.txt'):
                table_files.append(os.path.join(base, f))
    table_files.sort()
    for path in table_files:
        rel = os.path.relpath(path, root).replace(os.sep, '/')
        with io.open(path, 'r', encoding='utf-8', errors='replace') as fh:
            for line_no, raw in enumerate(fh, 1):
                if raw.lstrip().startswith('#') or not raw.strip():
                    continue
                for kind, owner, member, desc in tokens_from_text(raw):
                    rows.append(('table', rel, line_no, kind, owner, member, desc))

    seen = set()
    uniq = []
    for row in rows:
        if row in seen:
            continue
        seen.add(row)
        uniq.append(row)
    with io.open(out_path, 'w', encoding='utf-8') as fh:
        fh.write('carrier\tfile\tline\tkind\towner\tmember\tdesc\tn_distinct_refs\n')
        for row in uniq:
            fh.write('\t'.join(str(x) for x in row) + '\n')
    carriers = {}
    for row in uniq:
        carriers[(row[0], row[1])] = carriers.get((row[0], row[1]), 0) + 1
    print('[bootstrings] %d files scanned, %d distinct token occurrences, %d carriers' %
          (len(java_files) + len(table_files), len(uniq), len(carriers)))
    for (carrier, path), count in sorted(carriers.items(), key=lambda kv: (-kv[1], kv[0])):
        print('  %-6s %-70s %5d' % (carrier, path.replace(root + '/', ''), count))
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
