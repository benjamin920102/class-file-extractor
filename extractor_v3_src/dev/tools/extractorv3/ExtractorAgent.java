package dev.tools.extractorv3;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/**
 * Java 8-compatible agent. Only captures retransformation-visible class files.
 * It never modifies classes, and cannot recover native/JNI implementation code.
 */
public final class ExtractorAgent {
    private static final int BATCH_SIZE = 32;
    private ExtractorAgent() {}

    public static void agentmain(String options, Instrumentation inst) {
        Path output = null;
        Path statusPath = null;
        String runId = "unknown";
        PrintWriter log = null;
        try {
            if (options == null) throw new IllegalArgumentException("Null agent options");
            String[] parts = options.split(":", -1);
            if (parts.length != 3) throw new IllegalArgumentException("Invalid agent options fields: " + parts.length);
            output = Paths.get(decode(parts[0])).toAbsolutePath().normalize();
            final String prefix = decode(parts[1]);
            runId = decode(parts[2]);
            statusPath = Paths.get(output.toString() + ".extractor.status");
            Path logPath = Paths.get(output.toString() + ".extractor.log");
            if (output.getParent() != null) Files.createDirectories(output.getParent());
            log = new PrintWriter(Files.newBufferedWriter(logPath, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND));
            log.println("[agent] entry reached; agent class=" + ExtractorAgent.class.getName());
            log.println("[agent] run=" + runId + " targetJava=" + System.getProperty("java.version"));
            log.println("[agent] prefix=" + prefix + " supported=" + inst.isRetransformClassesSupported());
            log.flush();
            int entries = extract(output, prefix, inst, log);
            log.println("[agent] SUCCESS entries=" + entries);
            log.flush();
            writeStatus(statusPath, "OK " + runId + " " + entries);
        } catch (Throwable fatal) {
            String trace = stackTrace(fatal);
            if (log != null) {
                log.println("[agent] FATAL: " + trace);
                log.flush();
            }
            System.err.println("[extractor-v3] Agent failed: " + trace);
            if (statusPath != null) {
                try { writeStatus(statusPath, "FAILED " + runId + " " + fatal.getClass().getName() + ": " + fatal.getMessage()); }
                catch (Throwable another) { System.err.println("[extractor-v3] Could not write status: " + another); }
            }
            // Do NOT rethrow. Client reads status; prevents generic AgentInitializationException
            // from masking the actual cause after agentmain was entered.
        } finally {
            if (log != null) log.close();
        }
    }

    private static void writeStatus(Path file, String text) throws Exception {
        Files.write(file, (text + "\n").getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private static String decode(String s) {
        return new String(Base64.getUrlDecoder().decode(s), StandardCharsets.UTF_8);
    }

    private static int extract(Path output, String prefix, Instrumentation inst, PrintWriter log) throws Exception {
        if (!inst.isRetransformClassesSupported())
            throw new UnsupportedOperationException("JVM retransformation not supported");
        String normalizedPrefix = prefix;
        if (normalizedPrefix.endsWith(".*")) normalizedPrefix = normalizedPrefix.substring(0, normalizedPrefix.length() - 2);
        final String matchPrefix = normalizedPrefix.replace('.', '/');
        final boolean all = matchPrefix.length() == 0 || matchPrefix.equals("*");
        final Class<?>[] loaded = inst.getAllLoadedClasses();
        List<Class<?>> candidates = new ArrayList<Class<?>>();
        int matched = 0, unmodifiable = 0;
        for (Class<?> clazz : loaded) {
            if (clazz.isArray() || clazz.isPrimitive()) continue;
            String name = clazz.getName().replace('.', '/');
            if (!all && !name.startsWith(matchPrefix)) continue;
            matched++;
            boolean modifiable;
            try { modifiable = inst.isModifiableClass(clazz); }
            catch (Throwable problem) { modifiable = false; }
            if (modifiable) candidates.add(clazz);
            else unmodifiable++;
        }
        Collections.sort(candidates, new Comparator<Class<?>>() {
            public int compare(Class<?> a, Class<?> b) { return a.getName().compareTo(b.getName()); }
        });
        log.println("[scan] loaded=" + loaded.length + " matched=" + matched
                + " modifiable=" + candidates.size() + " unmodifiable=" + unmodifiable
                + " normalized-prefix=" + matchPrefix);
        if (candidates.isEmpty()) {
            log.println("[hint] First 50 loaded non-JDK classes:");
            int count = 0;
            for (Class<?> c : loaded) {
                String name = c.getName();
                if (name.startsWith("java.") || name.startsWith("jdk.") || name.startsWith("sun.")
                        || name.startsWith("com.sun.")) continue;
                log.println("  " + name);
                if (++count == 50) break;
            }
        }
        log.flush();

        final Map<Class<?>, byte[]> captured = new ConcurrentHashMap<Class<?>, byte[]>();
        final ClassFileTransformer transformer = new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String name, Class<?> redefined,
                    ProtectionDomain domain, byte[] bytes) {
                try {
                    if (name != null && redefined != null && bytes != null
                            && (all || name.startsWith(matchPrefix)))
                        captured.put(redefined, Arrays.copyOf(bytes, bytes.length));
                } catch (Throwable ignored) { /* no error may escape this hook */ }
                return null;
            }
        };

        int failures = 0, batches = 0, individuals = 0;
        inst.addTransformer(transformer, true);
        try {
            for (int i = 0; i < candidates.size(); i += BATCH_SIZE) {
                int end = Math.min(i + BATCH_SIZE, candidates.size());
                Class<?>[] batch = candidates.subList(i, end).toArray(new Class<?>[0]);
                try { inst.retransformClasses(batch); batches += batch.length; }
                catch (Throwable err) {
                    log.println("[batch-fail] " + i + ".." + (end - 1) + " " + err);
                    for (Class<?> c : batch) {
                        try { inst.retransformClasses(c); individuals++; }
                        catch (Throwable x) {
                            failures++;
                            log.println("[class-fail] " + c.getName() + " loader=" + loaderId(c) + ": " + x);
                        }
                    }
                }
                if ((i / BATCH_SIZE) % 16 == 0 || end == candidates.size()) {
                    log.println("[progress] " + end + "/" + candidates.size()
                            + " captured=" + captured.size() + " failures=" + failures);
                    log.flush();
                }
            }
        } finally {
            try { inst.removeTransformer(transformer); }
            catch (Throwable x) { log.println("[warning] removeTransformer failed: " + x); }
        }

        List<Class<?>> ordered = new ArrayList<Class<?>>(captured.keySet());
        Collections.sort(ordered, new Comparator<Class<?>>() {
            public int compare(Class<?> a, Class<?> b) {
                int r = a.getName().compareTo(b.getName());
                return r != 0 ? r : loaderId(a).compareTo(loaderId(b));
            }
        });
        Map<String, byte[]> entries = new LinkedHashMap<String, byte[]>();
        int duplicates = 0;
        for (Class<?> c : ordered) {
            String name = c.getName().replace('.', '/') + ".class";
            if (entries.containsKey(name))
                name = "META-INF/extractor-duplicate-loaders/" + (++duplicates) + "/" + name;
            entries.put(name, captured.get(c));
        }
        Path temporary = Paths.get(output.toString() + ".extractor.part");
        try {
            try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(temporary))) {
                for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                    jar.putNextEntry(new JarEntry(e.getKey()));
                    jar.write(e.getValue());
                    jar.closeEntry();
                }
            }
            Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING);
        } finally { try { Files.deleteIfExists(temporary); } catch (Exception ignored) {} }
        log.println("[done] captured=" + captured.size() + " entries=" + entries.size()
                + " retry-ok=" + individuals + " batches-ok=" + batches + " failures=" + failures
                + " duplicate-names=" + duplicates);
        log.println("[note] JNI/native implementation is not converted to Java bytecode.");
        log.flush();
        return entries.size();
    }

    private static String stackTrace(Throwable t) {
        StringWriter out = new StringWriter();
        PrintWriter w = new PrintWriter(out);
        t.printStackTrace(w);
        w.flush();
        return out.toString();
    }

    private static String loaderId(Class<?> c) {
        ClassLoader loader = c.getClassLoader();
        return loader == null ? "bootstrap" : loader.getClass().getName()
                + "@" + Integer.toHexString(System.identityHashCode(loader));
    }
}
