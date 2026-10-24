#!/usr/bin/env python
"""Stage 10, part 3: for the tables whose rows REWRITE a match, is the row's KEY alive on this line while its TARGET
is dead?

This is the one question about the census tables that a per-name dead/alive count cannot answer. A table row that
names a 26.2-only class cannot fire -- nothing observed on 1.21.1 can equal it -- and that is a silent no-op. But a
row whose KEY names a class and member that BOTH exist on 1.21.1 is live: the rule engine can match it, and then it
rewrites a selector to a TARGET that may not exist on this line. That is a false match rather than a miss, and it is
the difference between "does nothing" and "does something wrong".

Five of the nine tables have a rewrite target (`->`); the other four are queries with no target.

usage: table-liveness.py [--out build/x11/audit/tables.tsv]
"""
import io
import os
import re
import subprocess
import sys

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
KERNEL = os.path.join(REPO, 'forbric-kernel')
X11 = os.path.join(KERNEL, 'build', 'x11')
X = X11
TABLES = os.path.join(KERNEL, 'src', 'main', 'resources', 'net', 'forbric', 'kernel')

REWRITE = [
    ('mixin/carrier-renames.txt', 'key-member -> renamed-member : evidence | kind | ecosystems'),
    ('mixin/carrier-helpers.txt', 'key-member -> helper-member : evidence | kind | ecosystems'),
    ('mixin/carrier-stubs.txt', 'key-member -> overload-descriptor forge=... neo=...'),
    ('mixin/carrier-twins.txt', 'key-member -> overload-descriptor | index | ecosystems'),
    ('mixin/lambda-permutations.txt', 'key-member -> merged-descriptor | index'),
]

KEY = re.compile(r'^((?:[A-Za-z0-9_$]+/)+[A-Za-z0-9_$]+)#(<[A-Za-z]+>|[A-Za-z_$][A-Za-z0-9_$]*)(\([^)\s]*\)[^\s]*)')
TARGET_NAME = re.compile(r'->\s*([A-Za-z_$][A-Za-z0-9_$]*)\(([^)\s]*)\)([^\s|]*)')
TARGET_DESC = re.compile(r'->\s*(\([^)\s]*\)[^\s|]*)')


def main(argv):
    out = os.path.join(X, 'audit', 'tables.tsv')
    i = 1
    while i < len(argv):
        if argv[i] == '--out':
            out = argv[i + 1]; i += 2
        else:
            sys.stderr.write('unknown argument %s\n' % argv[i]); return 2

    rows = []
    for rel, shape in REWRITE:
        path = os.path.join(TABLES, rel)
        with io.open(path, 'r', encoding='utf-8') as fh:
            for line_no, raw in enumerate(fh, 1):
                text = raw.rstrip('\n')
                if not text.strip() or text.lstrip().startswith('#'):
                    continue
                key = KEY.match(text)
                if not key:
                    continue
                owner, member, desc = key.group(1), key.group(2), key.group(3)
                target = TARGET_NAME.search(text)
                if target:
                    # a named target on the SAME owner (renames/helpers), or a named overload (stubs/twins keep the
                    # key's name; their target is a descriptor, so the name is the key's own).
                    t_name, t_desc = target.group(1), '(' + target.group(2) + ')' + target.group(3)
                else:
                    desc_only = TARGET_DESC.search(text)
                    if not desc_only:
                        continue
                    t_name, t_desc = member, desc_only.group(1)
                rows.append((rel, line_no, owner, member, desc, t_name, t_desc))

    q_path = os.path.join(X, 'audit', 'q-tables.tsv')
    seen = set()
    with io.open(q_path, 'w', encoding='utf-8') as fh:
        for _rel, _n, owner, member, desc, t_name, t_desc in rows:
            for key in (('C', owner, '-', '-'),
                        ('M', owner, member, desc),
                        ('M', owner, t_name, t_desc),
                        ('F', owner, t_name, t_desc)):
                if key in seen:
                    continue
                seen.add(key)
                fh.write('\t'.join(key) + '\n')

    def answers(cp_file, suffix):
        f = os.path.join(X, 'audit', 'a-tables-%s.tsv' % suffix)
        subprocess.run([sys.executable, os.path.join(X, 'classindex.py'), '--cp', cp_file,
                        '--queries', q_path, '--out', f], check=True)
        out_map = {}
        with io.open(f, 'r', encoding='utf-8') as fh:
            for raw in fh:
                parts = raw.rstrip('\n').split('\t')
                out_map[tuple(parts[:-1])] = parts[-1] == '1'
        return out_map

    a1211 = answers(os.path.join(X, 'runtime-cp.txt'), '1211')
    a262 = answers(os.path.join(X, 'cp-262.txt'), '262')

    counts = {}
    lines_out = ['table\tline\towner\tkey-member\tkey-on-1.21.1\ttarget\tstate']
    for rel, line_no, owner, member, desc, t_name, t_desc in rows:
        key_live = a1211.get(('C', owner, '-', '-'), False) and \
                   (a1211.get(('M', owner, member, desc), False) or a1211.get(('F', owner, member, desc), False))
        target_live = a1211.get(('M', owner, t_name, t_desc), False) or a1211.get(('F', owner, t_name, t_desc), False)
        target_on_262 = a262.get(('M', owner, t_name, t_desc), False) or a262.get(('F', owner, t_name, t_desc), False)
        if not key_live:
            state = 'inert-key-dead'
        elif target_live:
            state = 'live-and-consistent'
        elif target_on_262:
            state = 'LIVE-KEY-DEAD-TARGET'
        else:
            state = 'live-key-target-absent-both'
        counts[state] = counts.get(state, 0) + 1
        lines_out.append('%s\t%d\t%s\t%s%s\t%s\t%s%s\t%s'
                         % (rel, line_no, owner, member, desc, 'yes' if key_live else 'no', t_name, t_desc, state))
    with io.open(out, 'w', encoding='utf-8') as fh:
        fh.write('\n'.join(lines_out) + '\n')
    print('[table-liveness] %d rewriting rows' % len(rows))
    for state, count in sorted(counts.items(), key=lambda kv: -kv[1]):
        print('  %-28s %5d' % (state, count))
    live_dead = [l for l in lines_out[1:] if l.endswith('LIVE-KEY-DEAD-TARGET')]
    print('[table-liveness] rows that can match on 1.21.1 and rewrite to a 26.2-only target: %d' % len(live_dead))
    for line in live_dead[:25]:
        print('   ', line)
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
