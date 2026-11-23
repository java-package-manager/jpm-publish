import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Lists or removes {@code //} and {@code /* ... *}{@code /} comments in Java and Kotlin files. Javadoc is left alone, and
 * so are comment markers inside strings, character literals and text blocks.
 *
 * <ul>
 *   <li>{@code java checks/Comments.java list <dir or file>...} prints each comment with the line of code
 *       it belongs to, for categorizing.</li>
 *   <li>{@code java checks/Comments.java strip <dir or file>...} removes them: a comment on a line of its
 *       own goes with its line, a trailing one with the space before it.</li>
 *   <li>A path starting with {@code !} is left out, for example vendored code: {@code src '!src/vendor'}.</li>
 *   <li>{@code java checks/Comments.java check <dir or file>...} fails when there are more comments than
 *       the baseline in {@code checks/comments-baseline.txt}, or fewer (the baseline must come down with
 *       them); {@code update} lowers the baseline. Repositories without other ratchets use this as their
 *       comment lint.</li>
 * </ul>
 */
public final class Comments {
    record Found(int start, int end, int line) {}

    public static void main(String[] args) throws IOException {
        if (args.length < 2 || !List.of("list", "strip", "check", "update").contains(args[0])) {
            System.err.println("Usage: java checks/Comments.java list|strip|check|update <dir or file>...");
            System.exit(2);
        }
        if (args[0].equals("check") || args[0].equals("update")) {
            int count = 0;
            for (Path p : javaFiles(List.of(args).subList(1, args.length)))
                count += find(Files.readString(p)).size();
            Path baseline = Path.of("checks/comments-baseline.txt");
            int was = Files.exists(baseline)
                    ? Integer.parseInt(Files.readString(baseline).strip())
                    : Integer.MAX_VALUE;
            if (args[0].equals("update")) {
                Files.writeString(baseline, Math.min(count, was) + "\n");
                System.out.println("comments: " + count + "; baseline " + Math.min(count, was));
                return;
            }
            if (was == Integer.MAX_VALUE) {
                System.err.println("comments: " + count + ", and no " + baseline
                        + ": add it with `java checks/Comments.java update`.");
                System.exit(1);
            }
            if (count > was) {
                System.err.println("comments: " + count + ", above the baseline " + was
                        + ". `java checks/Comments.java list` shows them;"
                        + " decisions belong in docs/adr (see the /delete-comment skill).");
                System.exit(1);
            }
            if (count < was) {
                System.err.println("comments: " + count + ", below the baseline " + was
                        + ": lower it with `java checks/Comments.java update`.");
                System.exit(1);
            }
            System.out.println("comments: " + count + " (baseline " + was + ")");
            return;
        }
        int files = 0, comments = 0, lines = 0;
        for (Path p : javaFiles(List.of(args).subList(1, args.length))) {
            String t = Files.readString(p);
            List<Found> found = find(t);
            if (found.isEmpty()) continue;
            files++;
            comments += found.size();
            if (args[0].equals("list")) {
                String[] text = t.split("\n", -1);
                for (Found f : found) {
                    String lineText = text[f.line() - 1];
                    int col = f.start() - (t.lastIndexOf('\n', f.start() - 1) + 1);
                    String before = lineText.substring(0, col).strip();
                    String context = before.isEmpty()
                            ? (f.line() < text.length ? "next: " + nextCode(text, f.line()) : "")
                            : "on: " + before;
                    System.out.println(p + ":" + f.line() + "  "
                            + t.substring(f.start(), f.end()).replace("\n", " ").strip());
                    System.out.println("      " + context);
                }
            } else {
                StringBuilder out = new StringBuilder();
                int pos = 0;
                for (Found f : found) {
                    out.append(t, pos, f.start());
                    int k = out.length();
                    while (k > 0 && (out.charAt(k - 1) == ' ' || out.charAt(k - 1) == '\t')) k--;
                    boolean ownLine = k == 0 || out.charAt(k - 1) == '\n';
                    out.setLength(k);
                    pos = f.end();
                    if (ownLine && pos < t.length() && t.charAt(pos) == '\n') {
                        pos++;
                        lines++;
                        String o = out.toString();
                        if ((o.isEmpty() || o.endsWith("\n\n") || o.endsWith("{\n"))
                                && pos < t.length()
                                && t.charAt(pos) == '\n') pos++;
                        int j = pos;
                        while (j < t.length() && (t.charAt(j) == ' ' || t.charAt(j) == '\t')) j++;
                        if (o.endsWith("\n\n") && j < t.length() && t.charAt(j) == '}') out.setLength(out.length() - 1);
                    }
                }
                out.append(t.substring(pos));
                Files.writeString(p, out.toString());
            }
        }
        System.err.println(comments + " comments in " + files + " files"
                + (args[0].equals("strip") ? "; " + lines + " lines removed" : ""));
    }

    private static String nextCode(String[] text, int line) {
        for (int i = line; i < text.length; i++) if (!text[i].isBlank()) return text[i].strip();
        return "";
    }

    static List<Found> find(String t) {
        List<Found> out = new ArrayList<>();
        int i = 0, n = t.length(), line = 1;
        while (i < n) {
            char c = t.charAt(i);
            int end;
            if (t.startsWith("\"\"\"", i)) {
                end = t.indexOf("\"\"\"", i + 3);
                while (end > 0 && t.charAt(end - 1) == '\\') end = t.indexOf("\"\"\"", end + 1);
                end = end < 0 ? n : end + 3;
            } else if (c == '"' || c == '\'') {
                end = i + 1;
                while (end < n && t.charAt(end) != c && t.charAt(end) != '\n') end += t.charAt(end) == '\\' ? 2 : 1;
                end = Math.min(n, end + 1);
            } else if (t.startsWith("/**", i) && !t.startsWith("/**/", i)) {
                end = t.indexOf("*/", i + 3);
                end = end < 0 ? n : end + 2;
            } else if (t.startsWith("//", i)) {
                end = t.indexOf('\n', i);
                end = end < 0 ? n : end;
                out.add(new Found(i, end, line));
            } else if (t.startsWith("/*", i)) {
                end = t.indexOf("*/", i + 2);
                end = end < 0 ? n : end + 2;
                out.add(new Found(i, end, line));
            } else {
                end = i + 1;
            }
            for (int k = i; k < end; k++) if (t.charAt(k) == '\n') line++;
            i = end;
        }
        return out;
    }

    static List<Path> javaFiles(List<String> roots) throws IOException {
        List<Path> out = new ArrayList<>();
        List<Path> excluded = roots.stream()
                .filter(r -> r.startsWith("!"))
                .map(r -> Path.of(r.substring(1)).normalize())
                .toList();
        for (String r : roots) {
            if (r.startsWith("!")) continue;
            Path root = Path.of(r);
            if (Files.isRegularFile(root)) {
                out.add(root);
                continue;
            }
            try (Stream<Path> s = Files.walk(root)) {
                s.filter(p -> (p.toString().endsWith(".java") || p.toString().endsWith(".kt"))
                        && Files.isRegularFile(p))
                        .sorted()
                        .forEach(out::add);
            }
        }
        out.removeIf(p -> excluded.stream().anyMatch(x -> p.normalize().startsWith(x)));
        return out;
    }
}
