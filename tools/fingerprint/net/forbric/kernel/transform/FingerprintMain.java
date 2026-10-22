package net.forbric.kernel.transform;

import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Prints the ForgeTransferShapeAudit fingerprint for each audited class found in a jar. */
public final class FingerprintMain {
    private static final List<String> AUDITED = List.of(
            "net.minecraftforge.items.ItemStackHandler",
            "net.minecraftforge.items.ItemHandlerHelper",
            "net.minecraftforge.fluids.capability.templates.FluidTank",
            "net.minecraftforge.energy.EnergyStorage",
            "net.minecraftforge.fluids.FluidStack",
            "net.minecraftforge.common.capabilities.CapabilityProvider",
            "net.minecraftforge.common.capabilities.CapabilityProvider$ItemStacks");

    public static void main(String[] args) throws Exception {
        Path jar = Path.of(args[0]);
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (String name : AUDITED) {
                ZipEntry entry = zip.getEntry(name.replace('.', '/') + ".class");
                if (entry == null) { System.out.println("MISSING " + name); continue; }
                byte[] bytes = zip.getInputStream(entry).readAllBytes();
                String fp;
                try { fp = ForgeTransferShapeAudit.fingerprint(bytes); }
                catch (Throwable t) { System.out.println("ERROR   " + name + " :: " + t); continue; }
                System.out.println("FP " + fp + "  " + name);
            }
        }
    }
}
