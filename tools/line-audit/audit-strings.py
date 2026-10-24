#!/usr/bin/env python
"""Stage 10, part 2: ask BOTH lines' real classpaths about every name bootstrings.py extracted.

For each token: is the class there on this line; is the member there with that exact name AND descriptor. The two
lines are the ones the build actually stages (26.2's .dev/staged/run, 1.21.1's .dev/staged-1211/run), dumped through
dump-cp-line.gradle, and each dump is proved to be its own line by the ResourceLocation/Identifier pair before it is
used (see the shot in the transcript / docs).

Classification, stated once here so the numbers in the document are the numbers this script prints:

  ok-both        the class is there on 26.2 AND on 1.21.1 (member, when present in the token, is too)
  keyed          the class is there on 26.2 and NOT on 1.21.1                                -> dead on 1.21.1
  member-keyed   the class is on both lines, the member is on 26.2 and not on 1.21.1          -> dead on 1.21.1
  desc-keyed     the member name is on both lines but the exact descriptor is not on 1.21.1   -> dead on 1.21.1
  other          present on 1.21.1 but not on 26.2 (a name only this line has, in the boot half), or unmeasurable
                 (a name-only token, `*`, which cannot distinguish an overload)

  ...and, for the three dead classes, `reachable` once audit-reach.py says the consumer runs on 1.21.1.

usage: audit-strings.py [--tokens build/x11/tokens.tsv] [--out build/x11/audit]
"""
import io
import os
import subprocess
import sys

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
KERNEL = os.path.join(REPO, 'forbric-kernel')
X11 = os.path.join(KERNEL, 'build', 'x11')
X = X11                                       # the harness directory measure.sh and dump-cp-line.gradle write
CP = os.path.join(X, 'runtime-cp.txt')        # 1.21.1, dumped by measure.sh's own instrument
CP262 = os.path.join(X, 'cp-262.txt')         # 26.2, dumped by dump-cp-line.gradle


def run_index(cp_file, queries, out_file):
    subprocess.run([sys.executable, os.path.join(X, 'classindex.py'), '--cp', cp_file,
                    '--queries', queries, '--out', out_file], check=True)
    answers = {}
    with io.open(out_file, 'r', encoding='utf-8') as fh:
        for raw in fh:
            parts = raw.rstrip('\n').split('\t')
            answers[tuple(parts[:-1])] = parts[-1] == '1'
    return answers


def main(argv):
    tokens_path = os.path.join(X, 'tokens.tsv')
    out_dir = os.path.join(X, 'audit')
    i = 1
    while i < len(argv):
        if argv[i] == '--tokens':
            tokens_path = argv[i + 1]; i += 2
        elif argv[i] == '--out':
            out_dir = argv[i + 1]; i += 2
        else:
            sys.stderr.write('unknown argument %s\n' % argv[i]); return 2
    os.makedirs(out_dir, exist_ok=True)

    tokens = []
    with io.open(tokens_path, 'r', encoding='utf-8') as fh:
        header = fh.readline()
        for raw in fh:
            carrier, path, line, kind, owner, member, desc = raw.rstrip('\n').split('\t')[:7]
            tokens.append((carrier, path, int(line), kind, owner, member, desc))

    owners = sorted({t[4] for t in tokens})
    q_path = os.path.join(out_dir, 'q-classes.tsv')
    with io.open(q_path, 'w', encoding='utf-8') as fh:
        for owner in owners:
            fh.write('C\t%s\n' % owner)
    print('[audit] %d tokens, %d distinct class names' % (len(tokens), len(owners)))
    a1211 = run_index(CP, q_path, os.path.join(out_dir, 'a-classes-1211.tsv'))
    a262 = run_index(CP262, q_path, os.path.join(out_dir, 'a-classes-262.tsv'))

    # Members are asked only about classes that exist on that line; a member query on an absent class means nothing
    # more than the class query did, and would inflate every count by the number of members in the row.
    member_tokens = [t for t in tokens if t[3] in ('method', 'field')]
    seen = set()
    m_path = os.path.join(out_dir, 'q-members.tsv')
    with io.open(m_path, 'w', encoding='utf-8') as fh:
        for _c, _p, _l, kind, owner, member, desc in member_tokens:
            key = (kind, owner, member, desc)
            if key in seen:
                continue
            seen.add(key)
            fh.write('%s\t%s\t%s\t%s\n' % ('M' if kind == 'method' else 'F', owner, member, desc))
    m1211 = run_index(CP, m_path, os.path.join(out_dir, 'a-members-1211.tsv'))
    m262 = run_index(CP262, m_path, os.path.join(out_dir, 'a-members-262.tsv'))

    rows = []
    for carrier, path, line, kind, owner, member, desc in tokens:
        owner262 = a262.get(('C', owner), False)
        owner1211 = a1211.get(('C', owner), False)
        if owner262 and not owner1211:
            bucket = 'keyed'
        elif not owner262 and owner1211:
            bucket = 'other-line-only'
        elif not owner262 and not owner1211:
            bucket = 'absent-both'
        elif kind in ('method', 'field'):
            key = (('M' if kind == 'method' else 'F'), owner, member, desc)
            present1211 = m1211.get(key, False)
            present262 = m262.get(key, False)
            if desc == '*':
                bucket = 'ok-both' if present1211 else ('member-keyed?' if present262 else 'absent-both')
            elif present262 and present1211:
                bucket = 'ok-both'
            elif present262 and not present1211:
                bucket = 'member-keyed'
            elif present1211 and not present262:
                bucket = 'other-line-only'
            else:
                bucket = 'absent-both'
        else:
            bucket = 'ok-both'
        rows.append((carrier, path, line, kind, owner, member, desc, bucket))

    with io.open(os.path.join(out_dir, 'classified.tsv'), 'w', encoding='utf-8') as fh:
        fh.write('carrier\tfile\tline\tkind\towner\tmember\tdesc\tbucket\n')
        for row in rows:
            fh.write('\t'.join(str(x) for x in row) + '\n')

    counts = {}
    for row in rows:
        counts[row[7]] = counts.get(row[7], 0) + 1
    print('[audit] buckets:')
    for bucket, count in sorted(counts.items(), key=lambda kv: -kv[1]):
        print('  %-18s %6d' % (bucket, count))
    dead = [r for r in rows if r[7] in ('keyed', 'member-keyed', 'member-keyed?', 'desc-keyed')]
    dead_files = sorted({r[1] for r in dead})
    dead_tables = sorted({(r[0], r[1]) for r in dead if r[0] == 'table'})
    print('[audit] dead token occurrences: %d in %d files' % (len(dead), len(dead_files)))
    print('[audit] dead table carriers: %s' % ', '.join(t for _c, t in dead_tables))
    table_lines = {}
    for carrier, path, line, kind, owner, member, desc, bucket in dead:
        if carrier != 'table':
            continue
        table_lines.setdefault(path, set()).add(line)
    for path, lines in sorted(table_lines.items()):
        print('  %-70s %5d dead rows' % (path.replace('./', ''), len(lines)))
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
