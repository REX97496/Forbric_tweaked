package net.forbric.kernel.compat;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class CompatProtocolTest {
	@Test void protocolNamesEveryShippedScript() throws Exception {
		Path root = Path.of("run/compat");
		String protocol = Files.readString(root.resolve("PROTOCOL.md"));
		try (var scripts = Files.walk(root)) {
			for (Path script : scripts.filter(Files::isRegularFile)
					.filter(p -> p.toString().endsWith(".sh") || p.toString().endsWith(".py")).toList()) {
				// The protocol is documentation, so it spells paths the portable way, with '/' separators.
				// Path.toString() uses the PLATFORM separator, so on Windows this asked whether the document
				// contained "win\common.py" and reported every script as undocumented while it listed them all.
				String documented = root.relativize(script).toString().replace('\\', '/');
				assertTrue(protocol.contains(documented),
						"Missing operational documentation for " + script + " (looked for \"" + documented + "\")");
			}
		}
	}
}
