package net.forbric.kernel.compat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real Python processes, with bounded output collection and no dependency on the developer's environment. */
final class DriverTools {
    static final Path COMPAT = Path.of("run", "compat").toAbsolutePath();
    record Result(int exit, String output) { }

    static Result run(Map<String, String> environment, String... arguments) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(System.getenv().getOrDefault("PYTHON", "python3"));
        command.addAll(List.of(materialiseInlineCode(arguments)));
        Path output = Files.createTempFile("compat-driver-output", ".txt");
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile());
        for (String variable : List.of("MODRINTH_API", "EXCLUDE_MANIFEST", "EXCLUDE", "SEED", "WANT_FABRIC", "WANT_NEO", "WANT_FORGE",
                "FORBRIC_MC", "FORBRIC_VERSION", "FORBRIC_INSTANCE", "FORBRIC_WORLD", "FORBRIC_JAVA", "FORBRIC_NATIVES", "FORBRIC_LANG")) {
            builder.environment().remove(variable);
        }
        builder.environment().putAll(environment);
        // Force UTF-8 for the child interpreter. Without it Python encodes with the OS ANSI code page, and on a
        // Chinese-locale Windows that is cp936/GBK: reading a UTF-8 driver source then dies with
        // `UnicodeDecodeError: 'gbk' codec can't decode byte 0x94`, which reads as a broken driver rather than as
        // a locale. build.gradle already pins LC_ALL for the JVM tests on Linux for the same class of reason;
        // this is the Python side of it, and it is set only on the child, never on the JVM running the suite.
        builder.environment().put("PYTHONUTF8", "1");
        builder.environment().put("PYTHONIOENCODING", "utf-8");
        Process process = builder.start();
        try {
            boolean finished = process.waitFor(45, TimeUnit.SECONDS);
            if (!finished) process.destroyForcibly();
            assertTrue(finished, "script timed out: " + String.join(" ", command));
            return new Result(process.exitValue(), Files.readString(output, StandardCharsets.UTF_8));
        } finally {
            if (process.isAlive()) process.destroyForcibly();
            try {
                Files.deleteIfExists(output);
            } catch (IOException stillHeld) {
                // Best-effort, and deliberately not allowed to fail the test: on Windows a process killed a moment
                // ago can still hold its redirected output open, so the delete loses a race with the OS handing the
                // handle back. The file lives in the JVM temp directory and is reclaimed there; turning that into a
                // test failure would report a scheduling race as a defect in the driver under test.
            }
        }
    }

    static Result script(String filename, Map<String, String> environment, String... arguments) throws Exception {
        List<String> all = new ArrayList<>();
        all.add(COMPAT.resolve(filename).toString());
        all.addAll(List.of(arguments));
        return run(environment, all.toArray(String[]::new));
    }

    /**
     * Replaces an inline {@code -c <code>} with a {@code -c} wrapper that runs that code from a file, so the code
     * text never travels on the command line.
     *
     * <p><b>Why this is needed.</b> On Windows {@code CreateProcess} hands the interpreter ONE command-line
     * string, and Python rebuilds its arguments from it. Inline code containing newlines does not survive that:
     * a suite assertion written {@code assert lang:zh_cn} came back as {@code SyntaxError: invalid syntax}, and
     * another arrived with a literal {@code bversion:4671\nlang:en_us} spliced in mid-statement -- i.e. fragments
     * of SEPARATE lines glued together. The same code executed fine when the very same interpreter was given the
     * text through a file. Linux splits argv before Python ever sees it, so CI never reproduces this.
     *
     * <p>The wrapper is what keeps behaviour identical rather than merely working: under {@code -c} Python sets
     * {@code sys.argv} to {@code ['-c', ...rest]}, while running a file would set it to {@code [path, ...rest]}.
     * The wrapper restores the {@code -c} form, so an inline program that reads {@code sys.argv} keeps reading
     * what it read before. A caller that passes no {@code -c} is returned untouched.
     *
     * <p><b>Known limitation, deliberately left visible.</b> The wrapper sets {@code sys.argv} itself, so for
     * {@code PushAndRunTest.theKernelJarIsBuiltBeforeAnythingIsStagedAndAFailedBuildStagesNothing} the arguments
     * that program assigns to {@code sys.argv} reach its own {@code argparse} only through the wrapper's copy;
     * that test reported "--label is required" on Windows. Three attempts to restore the exact {@code -c} argv
     * ordering (including {@code runpy} with an explicit {@code sys.argv}, and {@code exec(compile(...))}) did not
     * settle it, so the mechanism is left here with the failure named rather than papered over. On Linux the
     * original inline form splits correctly and this path is not taken at all.
     */
    private static String[] materialiseInlineCode(String[] arguments) throws IOException {
        int at = -1;
        for (int i = 0; i < arguments.length; i++) {
            if ("-c".equals(arguments[i])) {
                at = i;
                break;
            }
        }
        if (at < 0 || at + 1 >= arguments.length) return arguments;

        String code = arguments[at + 1];
        String[] rest = java.util.Arrays.copyOfRange(arguments, at + 2, arguments.length);
        Path script = Files.createTempFile("compat-inline-code", ".py");
        Files.writeString(script, code, StandardCharsets.UTF_8);

        StringBuilder wrapper = new StringBuilder();
        wrapper.append("import sys\n");
        // `python -c` runs the code with __name__ == '__main__' and sys.argv == ['-c', ...rest]. Running the
        // saved file with exec() keeps BOTH: runpy.run_path would set __name__ to '<run_path>', so a program
        // guarded by `if __name__ == '__main__'` would not run at all, and code that inspects sys.argv[0] would
        // see a temporary path instead of '-c'.
        wrapper.append("__name__ = '__main__'\n");
        wrapper.append("sys.argv = ['-c'] + ").append(pythonList(rest)).append('\n');
        wrapper.append("_forbric_source = open(")
               .append(pythonString(script.toAbsolutePath().toString()))
               .append(", encoding='utf-8').read()\n");
        wrapper.append("exec(compile(_forbric_source, '-c', 'exec'), globals())\n");

        // The wrapper sets sys.argv ITSELF, so the arguments must NOT also ride on the command line. Doing both
        // is how `source, target = map(Path, sys.argv[1:])` met "too many values to unpack (expected 2)": the
        // values arrived twice, once from the wrapper and once because Python appends the trailing argv to
        // sys.argv before -c runs. The caller's code is the only thing that reads argv, and it reads what the
        // wrapper rebuilt.
        List<String> replaced = new ArrayList<>(List.of(java.util.Arrays.copyOfRange(arguments, 0, at)));
        replaced.add("-c");
        replaced.add(wrapper.toString());
        return replaced.toArray(String[]::new);
    }

    /** A Python list literal of {@code values}, so the wrapper can rebuild {@code sys.argv} verbatim. */
    private static String pythonList(String[] values) {
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < values.length; i++) {
            if (i != 0) out.append(", ");
            out.append(pythonString(values[i]));
        }
        return out.append(']').toString();
    }

    /** A single-quoted Python literal; backslashes and quotes escaped so the value arrives as itself. */
    private static String pythonString(String value) {
        return "'" + value.replace("\\", "\\\\").replace("'", "\\'").replace("\r", "\\r").replace("\n", "\\n") + "'";
    }
}
