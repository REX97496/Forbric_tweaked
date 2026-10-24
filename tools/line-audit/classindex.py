#!/usr/bin/env python
"""Stage 10 audit instrument: does this NAME exist on THIS line?

The boot half has no Minecraft types at compile time -- it NAMES them in strings (mixin anchors, transform
targets, fingerprint pins, carrier tables) -- so javac never checks a single one of them. This answers the only
question that can be answered without launching: given a line's compile classpath, is the class there, and is the
member with that exact name AND descriptor there?

Why a hand-written class-file reader instead of javap or ASM: javap is one process per class (~150 ms against a
39-entry, 89 MB classpath -- minutes for thousands of names), and ASM refuses class files newer than its own
release, which is exactly the failure the energy jar produced. Reading the constant pool is version-agnostic: the
layout used here (magic, minor, major, cp_count, cp[], access, this, super, ifaces, fields[], methods[]) has not
changed since class file version 45 and does not know what a version 69 is.

usage:
   classindex.py --cp <file with ';'-separated classpath> --queries <tsv> [--out <tsv>]

queries (tab separated):
   C<TAB><internal/name>                      class present?
   M<TAB><internal/name><TAB><member><TAB><desc or *>   member present? (* = any descriptor, name only)
   F<TAB><internal/name><TAB><field><TAB><desc or *>    field present?

output: one line per query, the query's own fields plus TAB present(1|0). A class query that is absent answers 0
for every member query on it as well, which is what an anchor sees at runtime: NoSuchMethodException, not "the
class is missing and the method is too".
"""
import io
import os
import struct
import sys
import zipfile

# Constant-pool tags whose payload is a single u2 index (or a u4, for the two numeric forms).
_SINGLE_U2 = {7, 8, 16, 19, 20}          # Class, String, MethodType, Module, Package
_PAIR = {9, 10, 11, 12, 17, 18}          # refs, NameAndType, Dynamic, InvokeDynamic
_LONG = {5, 6}                            # Long, Double take two slots


class ClassFile:
    __slots__ = ('methods', 'fields')

    def __init__(self, methods, fields):
        self.methods = methods
        self.fields = fields


def read_members(data):
    """(methods, fields) as lists of (name, descriptor), parsed straight from the class file."""
    if len(data) < 10 or data[0:4] != b'\xca\xfe\xba\xbe':
        return None
    pos = 8
    (cp_count,) = struct.unpack_from('>H', data, pos)
    pos += 2
    utf8 = [None] * cp_count
    index = 1
    while index < cp_count:
        tag = data[pos]
        pos += 1
        if tag == 1:
            (length,) = struct.unpack_from('>H', data, pos)
            pos += 2
            utf8[index] = data[pos:pos + length].decode('utf-8', 'replace')
            pos += length
        elif tag in _PAIR:
            pos += 4
        elif tag in _LONG:
            pos += 8
            index += 1
        elif tag in _SINGLE_U2:
            pos += 2
        elif tag in (3, 4):
            pos += 4
        elif tag == 15:
            pos += 3                            # MethodHandle: u1 reference_kind + u2 reference_index
        else:
            raise ValueError('unknown constant-pool tag %d' % tag)
        index += 1
    pos += 6                                    # access_flags, this_class, super_class
    (iface_count,) = struct.unpack_from('>H', data, pos)
    pos += 2 + 2 * iface_count

    def members(pos):
        (count,) = struct.unpack_from('>H', data, pos)
        pos += 2
        out = []
        for _ in range(count):
            _access, name_i, desc_i, attr_count = struct.unpack_from('>HHHH', data, pos)
            pos += 8
            for _ in range(attr_count):
                (alen,) = struct.unpack_from('>I', data, pos + 2)
                pos += 6 + alen
            out.append((utf8[name_i], utf8[desc_i]))
        return out, pos

    fields, pos = members(pos)
    methods, pos = members(pos)
    return ClassFile(methods, fields)


class Classpath:
    """internal name -> where its bytes live, for one line's compile classpath."""

    def __init__(self, cp):
        self.dirs = []          # (root, )
        self.jars = []          # ZipFile
        self.index = {}
        self.cache = {}
        for entry in cp.split(';'):
            entry = entry.strip()
            if not entry:
                continue
            if os.path.isdir(entry):
                self.dirs.append(entry)
                for root, _dirs, files in os.walk(entry):
                    for f in files:
                        if f.endswith('.class'):
                            rel = os.path.relpath(os.path.join(root, f), entry)
                            self.index.setdefault(rel[:-6].replace(os.sep, '/'), ('dir', entry, rel))
            elif os.path.isfile(entry) and zipfile.is_zipfile(entry):
                try:
                    zf = zipfile.ZipFile(entry)
                except Exception:
                    continue
                self.jars.append(zf)
                for name in zf.namelist():
                    if name.endswith('.class') and not name.startswith('META-INF/'):
                        self.index.setdefault(name[:-6], ('jar', zf, name))

    def members(self, cls):
        if cls in self.cache:
            return self.cache[cls]
        hit = self.index.get(cls)
        result = None
        if hit is not None:
            try:
                if hit[0] == 'dir':
                    with open(os.path.join(hit[1], hit[2]), 'rb') as fh:
                        result = read_members(fh.read())
                else:
                    result = read_members(hit[1].read(hit[2]))
            except Exception:
                result = None
        self.cache[cls] = result
        return result

    def has_class(self, cls):
        return cls in self.index

    def has_member(self, cls, name, desc, field=False):
        cf = self.members(cls)
        if cf is None:
            return False
        pool = cf.fields if field else cf.methods
        for m_name, m_desc in pool:
            if m_name == name and (desc == '*' or m_desc == desc):
                return True
        return False


def main(argv):
    cp_file = None
    queries = None
    out = None
    i = 1
    while i < len(argv):
        if argv[i] == '--cp':
            cp_file = argv[i + 1]; i += 2
        elif argv[i] == '--queries':
            queries = argv[i + 1]; i += 2
        elif argv[i] == '--out':
            out = argv[i + 1]; i += 2
        else:
            sys.stderr.write('unknown argument %s\n' % argv[i]); return 2
    if not cp_file or not queries:
        sys.stderr.write(__doc__); return 2
    with io.open(cp_file, 'r', encoding='utf-8') as fh:
        cp = fh.read().strip()
    cpobj = Classpath(cp)
    lines = []
    with io.open(queries, 'r', encoding='utf-8') as fh:
        for raw in fh:
            raw = raw.rstrip('\n')
            if not raw:
                continue
            parts = raw.split('\t')
            kind = parts[0]
            if kind == 'C':
                present = cpobj.has_class(parts[1].replace('.', '/'))
            elif kind in ('M', 'F'):
                cls = parts[1].replace('.', '/')
                present = cpobj.has_member(cls, parts[2], parts[3] if len(parts) > 3 else '*',
                                           field=(kind == 'F'))
            else:
                present = 0
            lines.append(raw + '\t' + ('1' if present else '0'))
    text = '\n'.join(lines) + '\n'
    if out:
        with io.open(out, 'w', encoding='utf-8') as fh:
            fh.write(text)
    else:
        sys.stdout.write(text)
    sys.stderr.write('[classindex] %d entries, %d classes indexed, %d queries -> %s\n'
                     % (len(cp.split(';')), len(cpobj.index), len(lines), out or '(stdout)'))
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
