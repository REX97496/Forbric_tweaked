#!/usr/bin/env python
"""Stage 11 instrument: does every name a carrier table carries RESOLVE on 1.21.1?

Stage 10 asked a narrower question of these tables -- "is the row's KEY alive while its TARGET is dead", the 115 rows
that can match and rewrite to a member this line does not have. This asks the whole question, per row: the owner class,
the key member, the target member, and every member the row's evidence names, all against one line's classpath. Ask it
of two table sets and the answer is a comparison:

    the shipped 26.2 tables   507 rows, 312 dead keys, 417 dead targets, 115 live-key-dead-target
    the 1.21.1 editions       348 rows,   0 dead keys,   0 dead targets,   0 live-key-dead-target

The first line reproduces stage 10's 115 exactly, from the other end, which is what makes the second line a measurement
of the same property rather than of a different one.

Row shapes understood (the four tables with a rewrite target):
  <owner>#<name><desc> -> <target><desc> : <member evidence> x<count> | <kind> | <ecosystems>[ | LEFT|UNCALLED]
  <owner>#<name><desc> -> (<overload desc>) forge=... neo=...

THE INSTRUMENT HAS THE LIMIT STAGE 10's DOES, and it must be read with it: `classindex.py` answers "is this member
DECLARED in this class", so an evidence entry naming a JDK type (`java/lang/Math`) or a member inherited from a
supertype (`Player#getHealth` is declared by `LivingEntity`) reads as absent. On the 1.21.1 editions that is 72 of the
evidence entries; keys and targets are declaration-site claims, and they are 0 dead of 348 rows.

usage:
  table-resolve.py --root src/main/resources --label "26.2 shipped" \
                   --root src/main/mc-line/1.21.1-tables --label "1.21.1 re-run"
The classpath is build/x11/runtime-cp.txt (dumped by measure.sh, stage 11's convention).
"""
import io
import os
import re
import subprocess
import sys

X = os.path.dirname(os.path.abspath(__file__))
TABLES = ['net/forbric/kernel/mixin/carrier-renames.txt',
          'net/forbric/kernel/mixin/carrier-helpers.txt',
          'net/forbric/kernel/mixin/carrier-stubs.txt',
          'net/forbric/kernel/mixin/carrier-twins.txt']

KEY = re.compile(r'^((?:[A-Za-z0-9_$]+/)+[A-Za-z0-9_$]+)#(<[A-Za-z]+>|[A-Za-z_$][A-Za-z0-9_$]*)(\([^)\s]*\)[^\s|]*)')
NAMED_TARGET = re.compile(r'->\s*([A-Za-z_$][A-Za-z0-9_$]*)\(([^)\s]*)\)([^\s|]*)')
DESC_TARGET = re.compile(r'->\s*(\([^)\s]*\)[^\s|]*)')
EVIDENCE = re.compile(r'L([A-Za-z0-9_$/]+);([A-Za-z_$<][A-Za-z0-9_$<>]*)(\([^)]*\)[A-Za-z0-9_$/;\[]+)')
FIELDS = re.compile(r'L([A-Za-z0-9_$/]+);([A-Za-z_$][A-Za-z0-9_$]*):([A-Za-z0-9_$/;\[]+)')


def rows_of(root):
    """(table, line, owner, key name, key desc, target name, target desc, evidence[]) for every data row."""
    out = []
    for rel in TABLES:
        path = os.path.join(root, rel)
        if not os.path.isfile(path):
            continue
        with io.open(path, 'r', encoding='utf-8') as fh:
            for line_no, raw in enumerate(fh, 1):
                text = raw.rstrip('\n')
                if not text.strip() or text.lstrip().startswith('#'):
                    continue
                key = KEY.match(text)
                if not key:
                    out.append((rel, line_no, None, None, None, None, None, []))
                    continue
                owner, name, desc = key.group(1), key.group(2), key.group(3)
                target = NAMED_TARGET.search(text)
                if target:
                    t_name, t_desc = target.group(1), '(' + target.group(2) + ')' + target.group(3)
                else:
                    desc_only = DESC_TARGET.search(text)
                    t_name, t_desc = (name, desc_only.group(1)) if desc_only else (None, None)
                evidence = [('M', m.group(1), m.group(2), m.group(3)) for m in EVIDENCE.finditer(text)]
                evidence += [('F', m.group(1), m.group(2), m.group(3)) for m in FIELDS.finditer(text)]
                out.append((rel, line_no, owner, name, desc, t_name, t_desc, evidence))
    return out


def answers_for(queries):
    q = os.path.join(X, 'audit', 'resolve-q.tsv')
    a = os.path.join(X, 'audit', 'resolve-a.tsv')
    with io.open(q, 'w', encoding='utf-8') as fh:
        for query in queries:
            fh.write('\t'.join(query) + '\n')
    subprocess.run([sys.executable, os.path.join(X, 'classindex.py'), '--cp', os.path.join(X, 'runtime-cp.txt'),
                    '--queries', q, '--out', a], check=True)
    found = {}
    with io.open(a, 'r', encoding='utf-8') as fh:
        for raw in fh:
            parts = raw.rstrip('\n').split('\t')
            found[tuple(parts[:-1])] = parts[-1] == '1'
    return found


def report(label, root):
    rows = rows_of(root)
    queries, seen = [], set()

    def ask(query):
        if query not in seen:
            seen.add(query)
            queries.append(query)

    for rel, line_no, owner, name, desc, t_name, t_desc, evidence in rows:
        if owner is None:
            continue
        ask(('C', owner, '-', '-'))
        ask(('M', owner, name, desc))
        if t_name is not None and t_desc is not None:
            ask(('M', owner, t_name, t_desc))
        for kind, o, n, d in evidence:
            ask((kind, o, n, d))
    found = answers_for(queries)

    totals = {'rows': 0, 'dead-key': 0, 'dead-target': 0, 'live-key-dead-target': 0, 'dead-evidence': 0, 'unparsed': 0}
    for rel, line_no, owner, name, desc, t_name, t_desc, evidence in rows:
        totals['rows'] += 1
        if owner is None:
            totals['unparsed'] += 1
            continue
        if not (found.get(('C', owner, '-', '-'), False) and found.get(('M', owner, name, desc), False)):
            totals['dead-key'] += 1
        if t_name is not None and t_desc is not None and not found.get(('M', owner, t_name, t_desc), False):
            totals['dead-target'] += 1
            if found.get(('C', owner, '-', '-'), False) and found.get(('M', owner, name, desc), False):
                totals['live-key-dead-target'] += 1
        for kind, o, n, d in evidence:
            if not found.get((kind, o, n, d), False):
                totals['dead-evidence'] += 1
    print('[%s] %d rows: dead key %d, dead target %d (live key AND dead target %d), dead evidence members %d, unparsed %d'
          % (label, totals['rows'], totals['dead-key'], totals['dead-target'], totals['live-key-dead-target'],
             totals['dead-evidence'], totals['unparsed']))
    return totals


def main(argv):
    if len(argv) < 5:
        print(__doc__)
        return 2
    root = None
    i = 1
    while i < len(argv):
        if argv[i] == '--root':
            root = argv[i + 1]
        elif argv[i] == '--label' and root is not None:
            report(argv[i + 1], root)
        else:
            print('unknown argument %s' % argv[i])
            return 2
        i += 2
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
