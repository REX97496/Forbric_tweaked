#!/usr/bin/env python
"""Writes src/main/mc-line/1.21.1.patches: one row per boot-half site whose name is 26.2's and whose consumer
RESOLVES the name. Every row is generated from the source line itself, so the `old` text is exact by construction
and the occurrence count is counted rather than guessed.

The adjudication (what may be a row, and what may not) is in docs/1.21.1-port.md, stage 10. In one line: a row is
allowed only when (i) the consumer hands the string to a resolver, (ii) the line has the counterpart, (iii) the
counterpart is the same concept with the same shape -- each proved by build/x11/classindex.py against both real
classpaths. Pin data, anchors and census rows are NOT patchable and no row below touches them.
"""
import io
import os

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
KERNEL = os.path.join(REPO, 'forbric-kernel')
X11 = os.path.join(KERNEL, 'build', 'x11')
ROOT = os.path.join(KERNEL, 'src', 'main', 'java') + '/'
# (file, 26.2 text, 1.21.1 text, why)
ROWS = [
    ('net/forbric/kernel/boot/KernelForgeWrapperSync.java',
     'name = key.getClass().getMethod("identifier").invoke(key);',
     'name = key.getClass().getMethod("location").invoke(key);',
     'ResourceKey#identifier() is location() on 1.21.1 (javap both; M ResourceKey identifier/location = 1/0 and 0/1)'),
    ('net/forbric/kernel/boot/KernelForgeWrapperSync.java',
     'return key.getClass().getMethod("identifier").invoke(key);',
     'return key.getClass().getMethod("location").invoke(key);',
     'the same member, second call site in the same file'),
    ('net/forbric/kernel/boot/KernelForgeWrapperSync.java',
     'Class<?> identifierCls = Class.forName("net.minecraft.resources.Identifier", false, cl);',
     'Class<?> identifierCls = Class.forName("net.minecraft.resources.ResourceLocation", false, cl);',
     'stage 1 rule, re-measured: Identifier 1/0, ResourceLocation 0/1 (26.2/1.21.1)'),
    ('net/forbric/kernel/boot/KernelForgeWrapperSync.java',
     'Method getValue = registryCls.getMethod("getValue", identifierCls);',
     'Method getValue = registryCls.getMethod("get", identifierCls);',
     'Registry#getValue(Identifier) is Registry#get(ResourceLocation) on 1.21.1, and both return the value T'),
    ('net/forbric/kernel/boot/KernelClientSmoke.java',
     'Class<?> reasonCls = Class.forName("net.minecraft.world.entity.EntitySpawnReason", true, cl);',
     'Class<?> reasonCls = Class.forName("net.minecraft.world.entity.MobSpawnType", true, cl);',
     'stage 1 rule: MobSpawnType exists, COMMAND exists, EntityType.spawn(ServerLevel,BlockPos,MobSpawnType) exists'),
    ('net/forbric/kernel/boot/KernelClientSmoke.java',
     'Class<?> identifier = Class.forName("net.minecraft.resources.Identifier", false, cl);',
     'Class<?> identifier = Class.forName("net.minecraft.resources.ResourceLocation", false, cl);',
     'the same class rename, in the id probe'),
    ('net/forbric/kernel/boot/KernelClientSmoke.java',
     'getMethod("getValue", identifier)',
     'getMethod("get", identifier)',
     'the same Registry member rename, in the id probe; ResourceLocation.parse(String) exists for the next line'),
    ('net/forbric/kernel/boot/KernelLifecycle.java',
     'Object id = key.getClass().getMethod("identifier").invoke(key);',
     'Object id = key.getClass().getMethod("location").invoke(key);',
     'ResourceKey#identifier() -> location(), third call site'),
    ('net/forbric/kernel/boot/KernelRegistryAliases.java',
     'identifier = key.getClass().getMethod("identifier");',
     'identifier = key.getClass().getMethod("location");',
     'ResourceKey#identifier() -> location(), fourth call site'),
    ('net/forbric/kernel/boot/KernelWrapperEntryEvents.java',
     'Method identifier = Class.forName("net.minecraft.resources.ResourceKey", false, loader).getMethod("identifier");',
     'Method identifier = Class.forName("net.minecraft.resources.ResourceKey", false, loader).getMethod("location");',
     'ResourceKey#identifier() -> location(), fifth call site, and the only one that also names its owner'),
]


def main():
    rows = []
    for rel, old, new, why in ROWS:
        text = io.open(ROOT + rel, encoding='utf-8').read()
        count = text.count(old)
        if count != 1:
            raise SystemExit('%s: %d occurrence(s) of %r -- a row must name exactly one site' % (rel, count, old))
        rows.append((rel, count, old, new, why))

    out = ['# Forbric kernel -- Minecraft-line BOOT-HALF substitution list for 1.21.1',
           '#',
           '# src/main names Minecraft classes and members in STRINGS, so javac checks none of them and a name that',
           '# does not exist on this line fails only when the string is used. This file corrects the sites where the',
           '# string is handed to a RESOLVER, and only those. Read docs/1.21.1-port.md, stage 10, before adding a row:',
           '#',
           '#   * a row must name ONE site (whole text, not a token) and its count is an assertion -- the build fails',
           '#     if the source holds a different number of copies, so a site that moves is a build error, never a',
           '#     patch that silently stops applying;',
           '#   * a row is allowed only when the consumer RESOLVES the name (Class.forName / getMethod / getField /',
           '#     invokeStaticOn / startBus) and the counterpart is the same concept with the same shape, proved by',
           '#     build/x11/classindex.py against BOTH lines\' real classpaths;',
           '#   * PIN DATA AND ANCHORS ARE NOT PATCHABLE. A pin pairs its spelling with a body hash; renaming the',
           '#     spelling leaves the hash 26.2\'s and makes the data inconsistent instead of true;',
           '#   * CENSUS TABLES (src/main/resources) ARE OUT OF SCOPE BY CONSTRUCTION, not by promise: this file is',
           '#     applied to src/main/java only. A per-line edition of a census is fabricated evidence.',
           '#',
           '# Nothing here exists on 26.2: the task, the generated source dir and this file are read only when',
           '# mcLine != mcLineDefault.',
           '#',
           '# Format: <path under src/main/java> TAB <occurrences> TAB <exact old text> TAB <exact new text>',
           '']
    for rel, count, old, new, why in rows:
        out.append('# ' + why)
        out.append('%s\t%d\t%s\t%s' % (rel, count, old, new))
    out.append('')
    io.open('src/main/mc-line/1.21.1.patches', 'w', encoding='utf-8', newline='\n').write('\n'.join(out))
    print('[boot-patches] wrote %d rows to src/main/mc-line/1.21.1.patches' % len(rows))


if __name__ == '__main__':
    main()
