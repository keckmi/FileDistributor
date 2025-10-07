package com.example;
//Shortcut is when you right click a file: more options > send to > file distributor
//Shortcut file: "C:\Users\ASUS\AppData\Roaming\Microsoft\Windows\SendTo\File Distributor.lnk"
//Aimed to: "C:\Program Files\Java\jdk-21\bin\javaw.exe" -jar "C:\Users\ASUS\VisualStudioCodeProjects\file-distributor\file-distributor.jar"
//so if you build a new fat jar with: mvn -DskipTests clean package //in vsc powershell from root folder
//move it from "C:\Users\ASUS\VisualStudioCodeProjects\file-distributor\target\file-distributor.jar" where it'll be generated
//and replace "C:\Users\ASUS\VisualStudioCodeProjects\file-distributor\file-distributor.jar" with it.

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import javax.swing.*;
import javax.swing.filechooser.FileSystemView;
// remove: import java.awt.*;
import java.awt.GraphicsEnvironment;
import java.awt.BorderLayout;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FileDistributor {

    // date (grp1) + "_" + key (grp2) + optional "_anything..." + "." + extension
    // Matches both "..._alice.jpg" and "..._alice_something.v2.jpg"
    // Key = any Unicode chars, stopping right before the next "_" or the final "." (extension)
    private static final Pattern NAME_PATTERN =
            //Pattern.compile("^(\\d{2}(?:\\d{2})?\\.\\d{2}\\.\\d{2})_([^._]+?)(?:_.*)?\\.[^.]+$",
            Pattern.compile("^(\\d{2}(?:\\d{2})?\\.\\d{2}\\.\\d{2})_((?:(?![_.]).)+)(?:_.*)?\\.[^.]+$",       
                Pattern.UNICODE_CHARACTER_CLASS | Pattern.UNICODE_CASE);

    private static final String CONFIG_NAME = "config.json";

    // Extension sets (lowercase, no dots)
    private static final Set<String> PIC_EXT = setOf(
            "jpg", "jpeg", "png", "gif", "bmp", "tif", "tiff", "webp", "heic", "heif"
    );
    private static final Set<String> VID_EXT = setOf(
            "mp4", "m4v", "mov", "avi", "mkv", "webm", "wmv"
    );
    private static final Set<String> MUS_EXT = setOf(
            "mp3", "m4a", "aac", "flac", "wav", "ogg", "oga", "opus", "wma", "alac"
    );

    public static void main(String[] args) {
         // Replace --args-file with real paths
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a != null && a.startsWith("--args-file=")) {
                String listPath = a.substring("--args-file=".length());
                try {
                    java.util.List<String> lines = java.nio.file.Files.readAllLines(
                            java.nio.file.Paths.get(listPath), StandardCharsets.UTF_8);
                    args = lines.toArray(new String[0]); // replace argv with the real paths
                    try { java.nio.file.Files.deleteIfExists(java.nio.file.Paths.get(listPath)); } catch (Exception ignore) {}
                } catch (Exception e) {
                    showError("Failed to read args file: " + listPath, e);
                    return;
                }
                break;
            }
        }

        // 🔎 Debug once, after replacement
        /*javax.swing.JOptionPane.showMessageDialog(
            null,
            "Args length = " + args.length + "\n" + String.join("\n", args),
            "FD Debug - after args-file replacement",
            javax.swing.JOptionPane.INFORMATION_MESSAGE
        );*/

        List<Path> inputs = new ArrayList<>();
        for (String a : args) {
            if (a != null && !a.isBlank()) inputs.add(Paths.get(a));
        }
        try {
            new FileDistributor().run(inputs);
        } catch (Throwable t) {
            showError("Unexpected error: " + t.getMessage(), t);
        }
    }

    private void run(List<Path> inputs) throws Exception {
        Path appDir = locateAppDirectory();
        Path configPath = appDir.resolve(CONFIG_NAME);

        Config cfg = loadConfig(configPath);

        // No args → allow manual selection (nice for testing)
        if (inputs.isEmpty()) {
            List<Path> selected = pickFilesDialog();
            if (selected.isEmpty()) {
                maybePopup(cfg, "No files selected. Nothing to do.");
                return;
            }
            inputs = selected;
        }

        Summary summary = new Summary();

        // Batch prompt: if user selected multiple files and some lack a known key,
        // we will ask ONCE and reuse that key for all unknown-key files in this batch.
        final boolean batchMode = inputs.size() > 1;
        String batchKeyForUnknowns = null;
        boolean batchPromptShown = false;
        boolean batchPromptCancelled = false;

        for (Path given : inputs) {
            try {
                // 🔎 DEBUG (popup): see exactly what SendTo passed and whether Java sees it
                /*JOptionPane.showMessageDialog(
                    null,
                    "[FD] raw arg: " + given + "\nexists(as-is) = " + Files.exists(given),
                    "FD Debug",
                    JOptionPane.INFORMATION_MESSAGE
                );*/

                // 🔎 Debug 2: simple cleaned probe (quotes stripped)
                /*String raw = given.toString();
                String cleaned = raw.replace("\"", "").trim();
                Path cleanedProbe = Paths.get(cleaned);
                JOptionPane.showMessageDialog(
                    null,
                    "[FD] cleaned probe: " + cleanedProbe + "\nexists(cleaned) = " + Files.exists(cleanedProbe),
                    "FD Debug",
                    JOptionPane.INFORMATION_MESSAGE
                );*/

                Path file = normalizeExistingFile(given);
                if (file == null) {
                    summary.skipped.add(given + " (not a file)");
                    continue;
                }
                String fileName = file.getFileName().toString();

                // 🔎 DEBUG here
                /*Matcher dbg = NAME_PATTERN.matcher(fileName);
                boolean ok = dbg.matches();
                /*System.out.println("[FD] match=" + ok + " name=" + fileName + 
                                (ok ? (" | key=" + dbg.group(2)) : ""));*/
                /*JOptionPane.showMessageDialog(null,
                    "[FD] match=" + ok + " name=" + fileName + (ok ? (" | key=" + dbg.group(2)) : ""),
                    "FD Debug", JOptionPane.INFORMATION_MESSAGE);*/

                // Determine category by extension
                Category cat = determineCategory(fileName);
                if (cat == Category.OTHER) {
                    summary.skipped.add(fileName + " (unsupported type)");
                    continue;
                }

                // Try to extract key; if missing/unknown → prompt to pick a key (if policy=prompt) //batch-aware version
                String key = extractKey(fileName).orElse(null);
                if (key == null || (!cfg.mappings.containsKey(key)
                        && !cfg.mappings.containsKey(key.toLowerCase(Locale.ROOT))
                        && !cfg.mappings.containsKey(key.toUpperCase(Locale.ROOT)))) {

                    String policy = norm(cfg.unmatchedPolicy, "prompt");
                    if ("prompt".equals(policy)) {

                        if (batchMode) {
                            // Ask only once per run for all unknown-key files
                            if (!batchPromptShown) {
                                batchPromptShown = true;
                                batchKeyForUnknowns = promptForKeyBatch(cfg, fileName, inputs.size());
                                if (batchKeyForUnknowns == null) {
                                    batchPromptCancelled = true;
                                }
                            }
                            if (batchPromptCancelled) {
                                summary.skipped.add(fileName + " (no key chosen for batch)");
                                continue;
                            }
                            key = batchKeyForUnknowns;
                        } else {
                            key = promptForKey(cfg, fileName);
                            if (key == null) {
                                summary.skipped.add(fileName + " (no key chosen)");
                                continue;
                            }
                        }
                    } else {
                        summary.skipped.add(fileName + " (no mapping for extracted key)");
                        continue;
                    }
                }

                // Try to extract key; if missing/unknown → prompt to pick a key (if policy=prompt)
                /*String key = extractKey(fileName).orElse(null);
                if (key == null || !cfg.mappings.containsKey(key)
                        && !cfg.mappings.containsKey(key.toLowerCase(Locale.ROOT))
                        && !cfg.mappings.containsKey(key.toUpperCase(Locale.ROOT))) {

                    String policy = norm(cfg.unmatchedPolicy, "prompt");
                    if ("prompt".equals(policy)) {
                        key = promptForKey(cfg, fileName);
                        if (key == null) {
                            summary.skipped.add(fileName + " (no key chosen)");
                            continue;
                        }
                    } else {
                        summary.skipped.add(fileName + " (no mapping for extracted key)");
                        continue;
                    }
                }*/

                // Normalize key to any case variant present in config
                String chosenKey = resolveExistingKeyCase(cfg, key);
                if (chosenKey == null) {
                    // user might have typed/selected an unknown key (shouldn't happen with dropdown)
                    summary.skipped.add(fileName + " (key not in config)");
                    continue;
                }

                // Get up to two destination paths for the detected category
                List<String> targets = targetsFor(cfg.mappings.get(chosenKey), cat);
                if (targets.isEmpty()) {
                    summary.skipped.add(fileName + " (no target paths for " + cat + " under key '" + chosenKey + "')");
                    continue;
                }

                // Copy to each non-empty target
                for (String targetDirStr : targets) {
                    if (targetDirStr == null || targetDirStr.isBlank()) continue;
                    Path targetDir = Paths.get(targetDirStr);
                    try {
                        Files.createDirectories(targetDir);
                    } catch (IOException ioe) {
                        summary.errors.add(fileName + " → " + targetDir + " (cannot create dir: " + ioe.getMessage() + ")");
                        continue;
                    }
                    Path dest = targetDir.resolve(file.getFileName().toString());
                    Path destResolved = resolveCollision(dest);
                    copyFile(file, destResolved);
                    summary.copied.add(file.getFileName() + " → " + destResolved + "  [" + chosenKey + " / " + cat + "]");
                }

            } catch (Exception ex) {
                summary.errors.add(given + " (" + ex.getClass().getSimpleName() + ": " + ex.getMessage() + ")");
            }
        }

        String msg = summary.toMessage();
        if (cfg.logPopup) showInfo(msg); else System.out.println(msg);
    }

    // === Category / routing ===

    private static Category determineCategory(String fileName) {
        String ext = extensionOf(fileName);
        if (ext == null) return Category.OTHER;
        if (PIC_EXT.contains(ext)) return Category.PICTURES;
        if (VID_EXT.contains(ext)) return Category.VIDEOS;
        if (MUS_EXT.contains(ext)) return Category.MUSIC;
        return Category.OTHER;
    }

    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return null;
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static List<String> targetsFor(KeyTargets kt, Category cat) {
        if (kt == null) return List.of();
        switch (cat) {
            case PICTURES: return sanitizeTwo(kt.pictures);
            case VIDEOS:   return sanitizeTwo(kt.videos);
            case MUSIC:    return sanitizeTwo(kt.music);
            default:       return List.of();
        }
    }

    private static List<String> sanitizeTwo(List<String> xs) {
        if (xs == null || xs.isEmpty()) return List.of();
        // keep at most two; ignore empties later in caller loop
        return xs.size() <= 2 ? xs : xs.subList(0, 2);
    }

    // === Key extraction & unmatched handling ===

    private static Optional<String> extractKey(String fileName) {
        // Strict match on the whole name (we use m.matches())
        Matcher m = NAME_PATTERN.matcher(fileName);
        if (m.matches()) return Optional.ofNullable(m.group(2));
        return Optional.empty();
    }

    private static String promptForKey(Config cfg, String fileName) {
        if (GraphicsEnvironment.isHeadless()) return null;
        // Build a combo from existing keys
        List<String> keys = new ArrayList<>(cfg.mappings.keySet());
        Collections.sort(keys, String.CASE_INSENSITIVE_ORDER);
        if (keys.isEmpty()) {
            showInfo("No keys defined in config. Please add at least one key under \"mappings\".");
            return null;
        }
        JComboBox<String> combo = new JComboBox<>(keys.toArray(new String[0]));
        combo.setEditable(false);

        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.add(new JLabel("Choose key for: " + fileName), BorderLayout.NORTH);
        panel.add(combo, BorderLayout.CENTER);

        int res = JOptionPane.showConfirmDialog(null, panel, "Select key",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (res == JOptionPane.OK_OPTION) {
            return (String) combo.getSelectedItem();
        }
        return null;
    }

    /**
     * Batch-prompt when multiple files are selected: ask once and apply the chosen key
     * to all files in this selection that don't have a known key.
     */
    String promptForKeyBatch(Config cfg, String sampleFileName, int totalCount) {
        if (GraphicsEnvironment.isHeadless()) return null;

        List<String> keys = new ArrayList<>(cfg.mappings.keySet());
        Collections.sort(keys, String.CASE_INSENSITIVE_ORDER);
        if (keys.isEmpty()) {
            showInfo("No keys defined in config. Please add at least one key under \"mappings\".");
            return null;
        }

        JComboBox<String> combo = new JComboBox<>(keys.toArray(new String[0]));
        combo.setEditable(false);

        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.add(new JLabel("Choose ONE key for all selected files (" + totalCount + ").\nExample: " + sampleFileName),
                BorderLayout.NORTH);
        panel.add(combo, BorderLayout.CENTER);

        int res = JOptionPane.showConfirmDialog(null, panel, "Select key for ALL files",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (res == JOptionPane.OK_OPTION) {
            return (String) combo.getSelectedItem();
        }
        return null;
    }

    private static String resolveExistingKeyCase(Config cfg, String candidate) {
        if (candidate == null) return null;
        if (cfg.mappings.containsKey(candidate)) return candidate;
        String lc = candidate.toLowerCase(Locale.ROOT);
        if (cfg.mappings.containsKey(lc)) return lc;
        String uc = candidate.toUpperCase(Locale.ROOT);
        if (cfg.mappings.containsKey(uc)) return uc;
        // Try case-insensitive scan as a last resort
        for (String k : cfg.mappings.keySet()) {
            if (k.equalsIgnoreCase(candidate)) return k;
        }
        return null;
    }

    // === File ops ===

    private static Path normalizeExistingFile(Path given) {
        try {
            Path p = given;
            if (!Files.exists(p)) p = Paths.get(given.toString().replace("\"", ""));
            if (!Files.exists(p) || Files.isDirectory(p)) return null;
            return p;
        } catch (Exception e) {
            return null;
        }
    }

    private static Path resolveCollision(Path dest) throws IOException {
        if (!Files.exists(dest)) return dest;
        String name = dest.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = (dot >= 0) ? name.substring(0, dot) : name;
        String ext = (dot >= 0) ? name.substring(dot) : "";
        Path parent = dest.getParent();
        Path candidate = dest;
        int tries = 0;
        while (Files.exists(candidate)) {
            candidate = parent.resolve(base + "_2" + ext);
            base = base + "_2";
            tries++;
            if (tries > 5000) throw new IOException("Too many collision attempts for: " + dest);
        }
        return candidate;
    }

    private static void copyFile(Path source, Path dest) throws IOException {
        //past long ong InputStream / OutputStream version for collisions safety
        //while resolving name collisions manually earlier this project
        //Using Files.newOutputStream(..., CREATE_NEW) ensured the copy would never overwrite an existing file if something went wrong in resolveCollision()
        /*try (InputStream in = Files.newInputStream(source);
             OutputStream out = Files.newOutputStream(dest, StandardOpenOption.CREATE_NEW)) {
            in.transferTo(out);
        }*/
        //new method copy from Files doing the job now 
        //with parameter StandardCopyOption.COPY_ATTRIBUTES it copies the files attributes like lastModifiedTime as well 
        Files.copy(source, dest, StandardCopyOption.COPY_ATTRIBUTES);
        /*try { //saved file attribute lastModifiedTime as well, as wasn't the case yet before Files.copy
            Files.setLastModifiedTime(dest, Files.getLastModifiedTime(source));
        } catch (IOException ignore) {}*/
    }

    // === Config I/O ===

    private static Config loadConfig(Path configPath) throws IOException {
        ObjectMapper om = new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        if (!Files.exists(configPath)) {
            // Create a minimal default config using the new schema
            Config cfg = new Config();
            cfg.mappings = new LinkedHashMap<>();
            cfg.unmatchedPolicy = "prompt";
            cfg.logPopup = true;

            KeyTargets example = new KeyTargets();
            example.pictures = Arrays.asList("C:\\Targets\\alice\\pics1", "C:\\Targets\\alice\\pics2");
            example.videos   = Arrays.asList("C:\\Targets\\alice\\vid1",  "");
            example.music    = Arrays.asList("", "C:\\Targets\\alice\\music2");
            cfg.mappings.put("alice", example);

            saveConfig(configPath, cfg);
            return cfg;
        }
        return om.readValue(Files.readAllBytes(configPath), Config.class);
    }

    private static void saveConfig(Path configPath, Config cfg) throws IOException {
        ObjectMapper om = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        byte[] json = om.writeValueAsBytes(cfg);
        Files.write(configPath, json, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    // === UI helpers ===

    private static void maybePopup(Config cfg, String message) {
        if (cfg != null && cfg.logPopup) showInfo(message); else System.out.println(message);
    }

    private static void showInfo(String message) {
        if (GraphicsEnvironment.isHeadless()) {
            System.out.println(message);
            return;
        }
        JOptionPane.showMessageDialog(null, message, "File Distributor", JOptionPane.INFORMATION_MESSAGE);
    }

    private static void showError(String message, Throwable t) {
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println(message);
            if (t != null) t.printStackTrace();
            return;
        }
        String details = (t == null) ? "" : ("\n\n" + t.getClass().getSimpleName() + ": " + t.getMessage());
        JOptionPane.showMessageDialog(null, message + details, "File Distributor — Error", JOptionPane.ERROR_MESSAGE);
    }

    private static List<Path> pickFilesDialog() {
        if (GraphicsEnvironment.isHeadless()) return List.of();
        JFileChooser fc = new JFileChooser(FileSystemView.getFileSystemView());
        fc.setDialogTitle("Select files to distribute");
        fc.setMultiSelectionEnabled(true);
        fc.setFileSelectionMode(JFileChooser.FILES_ONLY);
        int res = fc.showOpenDialog(null);
        if (res == JFileChooser.APPROVE_OPTION) {
            File[] files = fc.getSelectedFiles();
            List<Path> list = new ArrayList<>();
            for (File f : files) list.add(f.toPath());
            return list;
        }
        return List.of();
    }

    // === App dir resolution ===

    private static Path locateAppDirectory() throws URISyntaxException {
        Path codeSource = Paths.get(FileDistributor.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());

        if (Files.isRegularFile(codeSource) && codeSource.getFileName().toString().endsWith(".jar")) {
            return codeSource.getParent();
        }
        Path p = codeSource;
        if (p.getFileName() != null && p.getFileName().toString().equalsIgnoreCase("classes")) {
            return p.getParent();
        }
        return p.getParent() != null ? p.getParent() : p;
    }

    // === Data model ===

    public static class Config {
        /** Key -> per-type targets (up to two paths per type; empty strings allowed) */
        public Map<String, KeyTargets> mappings;
        /** "prompt" or "error" when filename doesn't contain a known key */
        public String unmatchedPolicy = "prompt";
        /** show summary popup */
        public boolean logPopup = true;
    }

    /** Per-key target folders; each list has up to two entries (some may be empty) */
    public static class KeyTargets {
        public List<String> pictures; // e.g., ["C:\\Pics1", "C:\\Pics2"]
        public List<String> videos;   // e.g., ["D:\\Videos1", ""]
        public List<String> music;    // e.g., ["", "E:\\Music2"]
    }

    private enum Category { PICTURES, VIDEOS, MUSIC, OTHER }

    private static class Summary {
        final List<String> copied = new ArrayList<>();
        final List<String> skipped = new ArrayList<>();
        final List<String> errors = new ArrayList<>();

        String toMessage() {
            StringBuilder sb = new StringBuilder();
            sb.append("File Distributor — Summary\n\n");

            sb.append("Copied (").append(copied.size()).append(")\n");
            for (String c : copied) sb.append("  • ").append(c).append('\n');
            if (copied.isEmpty()) sb.append("  (none)\n");

            sb.append("\nSkipped (").append(skipped.size()).append(")\n");
            for (String s : skipped) sb.append("  • ").append(s).append('\n');
            if (skipped.isEmpty()) sb.append("  (none)\n");

            sb.append("\nErrors (max 10 shown: ").append(errors.size()).append(")\n");
            for (int i = 0; i < Math.min(10, errors.size()); i++) {
                sb.append("  • ").append(errors.get(i)).append('\n');
            }
            if (errors.isEmpty()) sb.append("  (none)\n");

            return sb.toString();
        }
    }

    // === small helpers ===
    private static Set<String> setOf(String... xs) { return new HashSet<>(Arrays.asList(xs)); }
    private static String norm(String s, String def) {
        return (s == null || s.isBlank()) ? def : s.trim().toLowerCase(Locale.ROOT);
    }
}