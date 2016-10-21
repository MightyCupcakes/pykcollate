package com.pyokagan.collate;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.github.javaparser.JavaParser;
import com.github.javaparser.Position;
import com.github.javaparser.Range;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;

public class Main {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.out.println("Provide dir and email");
            return;
        }
        final String dir = args[0];
        final String email = args[1];
        final StringBuffer out = new StringBuffer();
        out.append("# ").append(email).append("\n\n");
        Files.walk(Paths.get(dir)).forEach(path -> {
            if (Files.isDirectory(path))
                return;
            List<String> contributions;
            String syntax;
            try {
                String filename = path.getFileName().toString();
                if (filename.endsWith(".java")) {
                    contributions = getJavaContributions(path.toString(), email, 0.5);
                    syntax = "java";
                } else if (filename.endsWith(".md")) {
                    contributions = getMarkdownContributions(path.toString(), email, 0.5);
                    syntax = "markdown";
                } else {
                    return;
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            for (String code : contributions) {
                out.append("###### ").append(path).append("\n\n");
                // Github does not support nested fenced code blocks :-(
                if (syntax.equals("markdown")) {
                    // Indent everything by four spaces instead (code block)
                    for (String line : code.split("\n")) {
                        out.append("    ").append(line).append("\n");
                    }
                } else {
                    out.append("``` ").append(syntax).append("\n")
                        .append(code)
                        .append("```\n");
                }
            }
        });
        System.out.print(out.toString());
    }

    private static class JavaContributionCounter implements Callable<List<String>> {
        String file, email;
        List<String> lines;
        List<String> lineAuthors;
        CompilationUnit cu;
        List<String> contributions;
        int classLine = -1;
        int startLine = -1;
        double limit;

        JavaContributionCounter(String file, String email, double limit) throws Exception {
            this.file = file;
            this.email = email;
            this.limit = limit;
            lines = getLines(file);
            lineAuthors = getLineAuthors(file);
            FileInputStream in = new FileInputStream(file);
            try {
                cu = JavaParser.parse(in);
            } finally {
                in.close();
            }
        }

        @Override
        public List<String> call() throws Exception {
            contributions = new ArrayList<>();
            for (TypeDeclaration n : cu.getTypes()) {
                Range range = rangeOfNode(n);
                // If we already had a contribution line, now is a good time to stop it
                if (startLine >= 0) {
                    contributions.add(makeContribution(range.begin.line - 1));
                    startLine = -1;
                }
                // Register our class-start line
                classLine = range.begin.line - 1;
                // If not, we do more fine tuning...
                for (BodyDeclaration x : n.getMembers()) {
                    forEach(x);
                }
            }
            if (startLine >= 0) {
                contributions.add(makeContribution(lines.size()));
            }
            return contributions;
        }

        void forEach(BodyDeclaration x) throws Exception {
            if (!(x instanceof MethodDeclaration || x instanceof TypeDeclaration || x instanceof ConstructorDeclaration))
                return;
            Range range = rangeOfNode(x);
            if (getPercentContribution(range, lineAuthors, email) > limit) {
                // Author contributed sufficiently
                if (startLine < 0) {
                    startLine = classLine >= 0 ? classLine : range.begin.line - 1;
                }
            } else {
                // Author did not contribute sufficiently
                if (startLine >= 0) {
                    contributions.add(makeContribution(range.begin.line - 1));
                }
                startLine = -1;
            }
            // Reset classLine after the first method
            classLine = -1;
        }

        String makeContribution(int end) {
            return Main.makeContribution(lines, startLine, end);
        }

        Range rangeOfNode(Node node) {
            return new Range(
                    node.hasComment() ? node.getComment().getRange().begin : node.getRange().begin,
                    node.getRange().end);
        }
    }

    /**
     * Returns the list of strings of contributions for that java file
     */
    private static List<String> getJavaContributions(String file, String email, double limit) throws Exception {
        return new JavaContributionCounter(file, email, limit).call();
    }

    /**
     * Returns the list of strings of contributions for the markdown file.
     */
    private static List<String> getMarkdownContributions(String file, String email, double limit) throws Exception {
        List<String> lines = getLines(file);
        List<String> lineAuthors = getLineAuthors(file);
        int startLine = -1;
        List<String> contributions = new ArrayList<>();
        for (Range range : getMarkdownSections(lines)) {
            if (getPercentContribution(range, lineAuthors, email) > limit) {
                if (startLine < 0) {
                    startLine = range.begin.line - 1;
                }
            } else {
                if (startLine >= 0) {
                    contributions.add(makeContribution(lines, startLine, range.begin.line - 1));
                }
                startLine = -1;
            }
        }
        if (startLine >= 0) {
            contributions.add(makeContribution(lines, startLine, lines.size()));
        }
        return contributions;
    }

    private static List<Range> getMarkdownSections(List<String> lines) {
        Pattern headerPat = Pattern.compile("^#{1,3}[^#]+");
        List<Range> ranges = new ArrayList<>();
        int sectionStart = -1;
        int lineNum = 0;
        for (String line : lines) {
            Matcher matcher = headerPat.matcher(line);
            if (!matcher.matches()) {
                lineNum++;
                continue;
            }
            if (sectionStart >= 0) {
                // There was a previous section. Add its range
                ranges.add(new Range(new Position(sectionStart + 1, 1), new Position(lineNum, 1)));
            }
            sectionStart = lineNum;
            lineNum++;
        }
        if (sectionStart >= 0) {
            ranges.add(new Range(new Position(sectionStart + 1, 1), new Position(lineNum - 1, 1)));
        }
        return ranges;
    }

    private static String makeContribution(List<String> lines, int start, int end) {
        return lines.subList(start, end).stream()
                .map(line -> line + "\n").collect(Collectors.joining(""));
    }

    private static int countContributions(Range range, List<String> lineAuthors, String email) {
        int out = 0;
        for (int i = range.begin.line; i <= range.end.line; i++) {
            if (lineAuthors.get(i - 1).equals(email)) {
                out++;
            }
        }
        return out;
    }

    private static double getPercentContribution(Range range, List<String> lineAuthors, String email) {
        int numContributions = countContributions(range, lineAuthors, email);
        int numLines = range.end.line - range.begin.line + 1; // Num of lines in range
        return ((double)numContributions) / ((double)numLines);
    }

    private static List<String> getLines(String file) throws Exception {
        return Files.lines(new File(file).toPath()).collect(Collectors.toList());
    }

    private static long numLines(String file) throws Exception {
        return Files.lines(new File(file).toPath()).count();
    }

    private static List<String> getLineAuthors(String file) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("git", "blame", "-C", "--line-porcelain", file);
        Process p = pb.start();
        InputStream input = p.getInputStream();
        Reader r = new InputStreamReader(input, Charset.forName("utf-8"));
        BufferedReader br = new BufferedReader(r);
        String line;
        String currentEmail = null;
        List<String> out = new ArrayList<>();
        while ((line = br.readLine()) != null) {
            // If we detect a tab character we know it's a line of code.
            // So we can reset stateful variables
            if (line.startsWith("\t")) {
                // It's a line!
                if (currentEmail == null)
                    throw new AssertionError("should not happen");
                out.add(currentEmail);
            } else if (line.startsWith("author-mail ")) {
                currentEmail = line.substring("author-mail <".length(), line.length() - 1);
            }
        }
        p.waitFor();
        assert numLines(file) == out.size();
        return out;
    }

}
