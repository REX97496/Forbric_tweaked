# probe-mod

A one-line Fabric mod used to answer "does a mod's code RUN on this line", separately from "is the mod discovered".

Build (from `forbric-kernel/`):

    javac -cp build/libs/forbric-kernel-<version>.jar -d /tmp/probe-classes run/probe-mod/src/probe/Probe.java
    jar cf <server>/mods/forbric-probe.jar -C run/probe-mod fabric.mod.json -C /tmp/probe-classes probe

The kernel jar is the compile classpath because `net/fabricmc/api/ModInitializer.class` is inside it -- there is no
separate fabric-loader jar in this repository.

Two observations are required, and they are independent:

    [Forbric/Fabric] FabricLoader ready -- N mod(s), entrypoint keys [main]     the manifest was parsed
    [STDOUT]: [PROBE] onInitialize RAN on this line                            it actually ran

The `depends.minecraft` value is the test's parameter: set it to the line under test. A wrong value produces a
`[Forbric/Versions]` warning before anything else happens, which is itself worth checking.
