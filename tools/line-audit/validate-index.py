#!/usr/bin/env python
"""Cross-check classindex.py's hand-written class-file reader against javap -p -s on BOTH lines.

The reader exists because javap costs one process per class; but a reader that silently desyncs the constant pool
returns CONFIDENTLY WRONG member answers, and every classification in this stage rests on it. Its first version
advanced four bytes over a MethodHandle (tag 15) entry, which is three, and therefore answered "absent" for
ResourceLocation.fromNamespaceAndPath(String, String) -- a member javap shows. This script is the instrument that
would have caught that in one run, so it is kept rather than remembered.

usage:  validate-index.py <cp file> <sample size>
"""
import io
import os
import random
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from classindex import Classpath  # noqa: E402

JAVAP = 'C:/Program Files/Java/jdk-25.0.2/bin/javap.exe'


def _name_of(decl):
    """The member name out of one javap declaration line."""
    text = decl.strip()
    if '(' in text:
        text = text[:text.index('(')]
        token = text.split()[-1] if text.split() else ''
    else:
        text = text.rstrip(';')
        token = text.split()[-1] if text.split() else ''
    token = token.split('<')[0]
    return token


def javap_members(cp, cls):
    out = subprocess.run([JAVAP, '-p', '-s', '-cp', cp, cls.replace('/', '.')],
                         capture_output=True, text=True, errors='replace')
    if out.returncode != 0:
        return None
    lines = out.stdout.splitlines()
    members = set()
    for i, line in enumerate(lines[:-1]):
        nxt = lines[i + 1].strip()
        if not nxt.startswith('descriptor:'):
            continue
        decl = line.strip()
        if not decl or decl.endswith(('{', '}')):
            continue
        name = _name_of(decl)
        # javap spells a constructor with the class's BINARY name and a static initialiser as `{}`; the class file
        # calls them <init> and <clinit>. Normalise the javap side, never the reader's side.
        if name == cls.replace('/', '.'):
            name = '<init>'
        elif name == '{}':
            name = '<clinit>'
        if not name:
            continue
        members.add((name, nxt.split(':', 1)[1].strip()))
    return members


def main(argv):
    cp_file, sample = argv[1], int(argv[2])
    cp = io.open(cp_file, encoding='utf-8').read().strip()
    index = Classpath(cp)
    candidates = [n for n in index.index if not n.startswith(('net/fabricmc/', 'META-INF'))]
    random.seed(20261024)
    random.shuffle(candidates)
    checked = 0
    mismatches = 0
    for cls in candidates[:sample]:
        mine = index.members(cls)
        if mine is None:
            print('[UNREADABLE] %s' % cls)
            mismatches += 1
            continue
        theirs = javap_members(cp, cls)
        if theirs is None:
            continue
        my_set = set(mine.methods) | set(mine.fields)
        checked += 1
        if my_set != theirs:
            only_mine = sorted(my_set - theirs)[:4]
            only_theirs = sorted(theirs - my_set)[:4]
            print('[MISMATCH] %s  mine-only=%s  javap-only=%s' % (cls, only_mine, only_theirs))
            mismatches += 1
    print('[validate] %s: %d classes checked, %d mismatch' % (os.path.basename(cp_file), checked, mismatches))
    return 1 if mismatches else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
