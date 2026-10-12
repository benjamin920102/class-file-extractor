package dev.tools.extractorv3;

import com.sun.tools.attach.VirtualMachine;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.CodeSource;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.jar.JarFile;

/** Launches a separate, dependency-free Java agent into an already running JVM. */
public final class ExtractorCLI {
    private ExtractorCLI() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 2 || args.length > 3) {
            System.err.println("Usage: java -jar extractor-v3.jar <pid> <output.jar> [class-prefix]");
            System.err.println("  Prefix examples: obf, obf.*, net.minecraft, * (all modifiable loaded classes)");
            System.exit(2);
            return;
        }
        final String pid = args[0];
        final Path output = Paths.get(args[1]).toAbsolutePath().normalize();
        final String prefix = args.length == 3 ? args[2] : "*";
        final Path logFile = Paths.get(output.toString() + ".extractor.log");
        final Path statusFile = Paths.get(output.toString() + ".extractor.status");
        final String runId = UUID.randomUUID().toString();
        final CodeSource cs = ExtractorCLI.class.getProtectionDomain().getCodeSource();
        if (cs == null) throw new IOException("Could not determine agent JAR path");
        final Path agentJar = Paths.get(cs.getLocation().toURI()).toAbsolutePath().normalize();
        if (!Files.isRegularFile(agentJar)) throw new IOException("Agent JAR not found: " + agentJar);
        if (output.getParent() != null) Files.createDirectories(output.getParent());
        String header = "[launcher] extractor-v3 run=" + runId + " pid=" + pid + " prefix=" + prefix + " output=" + output + System.lineSeparator();
        Files.write(logFile, header.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        Files.deleteIfExists(statusFile);
        System.out.println("[extractor-v3] PID=" + pid + " prefix=" + prefix);
        System.out.println("[extractor-v3] Output=" + output);
        System.out.println("[extractor-v3] Log=" + logFile);
        System.out.println("[extractor-v3] Status=" + statusFile);

        String options = encode(output.toString()) + ":" + encode(prefix) + ":" + encode(runId);
        VirtualMachine vm = null;
        Throwable attachError = null;
        try {
            vm = VirtualMachine.attach(pid);
            vm.loadAgent(agentJar.toString(), options);
        } catch (Throwable error) {
            attachError = error;
        } finally {
            if (vm != null) {
                try { vm.detach(); }
                catch (Throwable error) {
                    System.err.println("[extractor-v3] WARNING: failed to detach: " + error);
                }
            }
        }

        String status = "";
        if (Files.isRegularFile(statusFile)) {
            status = new String(Files.readAllBytes(statusFile), StandardCharsets.UTF_8).trim();
        }
        if (attachError != null) {
            System.err.println("[extractor-v3] JVM attach/loadAgent error: " + attachError);
            attachError.printStackTrace(System.err);
        }
        if (!status.startsWith("OK " + runId + " ")) {
            System.err.println("[extractor-v3] Agent did not report success; status=" + (status.isEmpty() ? "<no status file>" : status));
            System.err.println("[extractor-v3] Read the TARGET JVM console if log has no [agent] lines.");
            printLogTail(logFile, 80);
            System.exit(1);
            return;
        }
        if (!Files.isRegularFile(output)) throw new IOException("Agent said OK but output missing: " + output);
        int entries;
        try (JarFile jar = new JarFile(output.toFile())) { entries = jar.size(); }
        System.out.println("[extractor-v3] SUCCESS: " + entries + " JAR entries -> " + output);
        if (entries == 0) {
            System.err.println("[extractor-v3] WARNING: No matching loaded and modifiable classes. Check prefix in log.");
            printLogTail(logFile, 40);
        }
    }

    private static String encode(String text) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    private static void printLogTail(Path logFile, int maxLines) {
        try {
            if (!Files.isRegularFile(logFile)) {
                System.err.println("[extractor-v3] No log file: " + logFile);
                return;
            }
            List<String> lines = Files.readAllLines(logFile, StandardCharsets.UTF_8);
            System.err.println("--- extractor log tail ---");
            for (int i = Math.max(0, lines.size() - maxLines); i < lines.size(); i++)
                System.err.println(lines.get(i));
            System.err.println("--- end ---");
        } catch (IOException e) {
            System.err.println("[extractor-v3] Could not read log: " + e);
        }
    }
}
