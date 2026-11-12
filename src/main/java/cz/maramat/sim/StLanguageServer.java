package cz.maramat.sim;

import cz.maramat.model.DataType;
import cz.maramat.model.Page;
import cz.maramat.model.Project;
import cz.maramat.model.StVariable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Embedded Language Server for Structured Text (ST):
 * - Real-time syntax and semantic diagnostics (error / warning detection)
 * - Autocomplete / IntelliSense suggestions (keywords, variables, hardware I/O, functions, snippets)
 * - Hover quick documentation
 * - Code auto-formatting / indentation
 */
public final class StLanguageServer {

    public enum Severity {
        ERROR,
        WARNING,
        INFO
    }

    public record Diagnostic(
            int line,
            int colStart,
            int colEnd,
            Severity severity,
            String message,
            String code
    ) {
        @Override
        public String toString() {
            return "[" + severity + "] Line " + line + ": " + message;
        }
    }

    public enum CompletionKind {
        KEYWORD,
        VARIABLE,
        HARDWARE_IO,
        FUNCTION,
        FUNCTION_BLOCK,
        SNIPPET
    }

    public record CompletionItem(
            String label,
            String insertText,
            CompletionKind kind,
            String detail,
            String doc
    ) implements Comparable<CompletionItem> {
        @Override
        public int compareTo(CompletionItem o) {
            int c = Integer.compare(kindPriority(this.kind), kindPriority(o.kind));
            if (c != 0) return c;
            return label.compareToIgnoreCase(o.label);
        }

        private static int kindPriority(CompletionKind k) {
            return switch (k) {
                case VARIABLE -> 0;
                case HARDWARE_IO -> 1;
                case FUNCTION -> 2;
                case FUNCTION_BLOCK -> 3;
                case KEYWORD -> 4;
                case SNIPPET -> 5;
            };
        }
    }

    private static final Set<String> ST_KEYWORDS = Set.of(
            "IF", "THEN", "ELSIF", "ELSE", "END_IF",
            "CASE", "OF", "END_CASE",
            "FOR", "TO", "BY", "DO", "END_FOR",
            "WHILE", "END_WHILE",
            "REPEAT", "UNTIL", "END_REPEAT",
            "EXIT", "RETURN",
            "AND", "OR", "XOR", "NOT", "MOD",
            "TRUE", "FALSE",
            "VAR", "VAR_INPUT", "VAR_OUTPUT", "VAR_IN_OUT", "END_VAR",
            "BOOL", "BYTE", "WORD", "DWORD", "INT", "DINT", "REAL", "LREAL", "TIME", "STRING"
    );

    private static final Set<String> ST_BUILTIN_FUNCTIONS = Set.of(
            "ABS", "SQRT", "LN", "LOG", "EXP", "SIN", "COS", "TAN", "ASIN", "ACOS", "ATAN",
            "ROUND", "TRUNC", "MIN", "MAX", "LIMIT", "CLAMP", "SEL", "MUX",
            "AND", "OR", "XOR", "NOT", "NAND", "NOR", "XNOR",
            "ADD", "SUB", "MUL", "DIV", "MOD",
            "EQ", "NE", "LT", "LE", "GT", "GE",
            "RANDOM", "RND", "SINGLEPULSE", "ONESHOT"
    );

    private static final Set<String> ST_STANDARD_FBS = Set.of(
            "TON", "TOF", "TP", "CTU", "CTD", "CTUD", "SR", "RS", "R_TRIG", "F_TRIG",
            "RANDOM", "RND", "SINGLEPULSE", "ONESHOT", "COUNTER", "CNTR"
    );

    private StLanguageServer() {}

    /**
     * Runs full diagnostic analysis on the code:
     * - Syntax validation (StInterpreter parser)
     * - Undeclared identifier checks
     * - Assignment syntax checks (use of = instead of :=)
     * - Unclosed block statement checks (IF, CASE, FOR, WHILE, REPEAT, VAR)
     */
    public static List<Diagnostic> analyze(String code, List<StVariable> declaredVars, Project project) {
        List<Diagnostic> diags = new ArrayList<>();
        if (code == null || code.isBlank()) {
            return diags;
        }

        String[] lines = code.split("\n", -1);

        // 1. Unclosed Block Analysis
        checkUnclosedBlocks(code, diags);

        // 2. Line-by-line checks (= instead of := in assignments, missing semicolons)
        checkStatementSyntax(lines, diags);

        // 3. StInterpreter Parser Validation
        List<StInterpreter.Diagnostic> parserDiags = StInterpreter.validate(code, declaredVars);
        for (StInterpreter.Diagnostic pd : parserDiags) {
            diags.add(new Diagnostic(pd.line(), 0, 0, Severity.ERROR, pd.message(), "PARSER_ERROR"));
        }

        // 4. Undeclared Identifier Checks
        checkUndeclaredIdentifiers(code, declaredVars, project, diags);

        // Sort diagnostics by line number
        diags.sort((a, b) -> Integer.compare(a.line(), b.line()));
        return diags;
    }

    private static void checkUnclosedBlocks(String code, List<Diagnostic> diags) {
        String stripped = stripCommentsAndStrings(code);
        Pattern wordPat = Pattern.compile("\\b[A-Za-z_][A-Za-z0-9_]*\\b");
        Matcher m = wordPat.matcher(stripped);

        int ifCount = 0;
        int caseCount = 0;
        int forCount = 0;
        int whileCount = 0;
        int repeatCount = 0;
        int varCount = 0;

        while (m.find()) {
            String w = m.group().toUpperCase(Locale.ROOT);
            switch (w) {
                case "IF" -> ifCount++;
                case "END_IF" -> ifCount = Math.max(0, ifCount - 1);
                case "CASE" -> caseCount++;
                case "END_CASE" -> caseCount = Math.max(0, caseCount - 1);
                case "FOR" -> forCount++;
                case "END_FOR" -> forCount = Math.max(0, forCount - 1);
                case "WHILE" -> whileCount++;
                case "END_WHILE" -> whileCount = Math.max(0, whileCount - 1);
                case "REPEAT" -> repeatCount++;
                case "END_REPEAT" -> repeatCount = Math.max(0, repeatCount - 1);
                case "VAR", "VAR_INPUT", "VAR_OUTPUT", "VAR_IN_OUT" -> varCount++;
                case "END_VAR" -> varCount = Math.max(0, varCount - 1);
            }
        }

        int lastLine = Math.max(1, code.split("\n", -1).length);
        if (ifCount > 0) {
            diags.add(new Diagnostic(lastLine, 0, 0, Severity.ERROR, "Unclosed IF statement: missing END_IF;", "UNCLOSED_IF"));
        }
        if (caseCount > 0) {
            diags.add(new Diagnostic(lastLine, 0, 0, Severity.ERROR, "Unclosed CASE statement: missing END_CASE;", "UNCLOSED_CASE"));
        }
        if (forCount > 0) {
            diags.add(new Diagnostic(lastLine, 0, 0, Severity.ERROR, "Unclosed FOR loop: missing END_FOR;", "UNCLOSED_FOR"));
        }
        if (whileCount > 0) {
            diags.add(new Diagnostic(lastLine, 0, 0, Severity.ERROR, "Unclosed WHILE loop: missing END_WHILE;", "UNCLOSED_WHILE"));
        }
        if (repeatCount > 0) {
            diags.add(new Diagnostic(lastLine, 0, 0, Severity.ERROR, "Unclosed REPEAT loop: missing UNTIL ... END_REPEAT;", "UNCLOSED_REPEAT"));
        }
        if (varCount > 0) {
            diags.add(new Diagnostic(lastLine, 0, 0, Severity.ERROR, "Unclosed variable block: missing END_VAR;", "UNCLOSED_VAR"));
        }
    }

    private static void checkStatementSyntax(String[] lines, List<Diagnostic> diags) {
        Pattern badAssignPat = Pattern.compile("^\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*=(?![>=])\\s*([^=;].*)$");
        boolean inComment = false;

        for (int i = 0; i < lines.length; i++) {
            String rawLine = lines[i];
            String trimmed = rawLine.trim();

            if (trimmed.startsWith("/*")) inComment = true;
            if (inComment) {
                if (trimmed.contains("*/")) inComment = false;
                continue;
            }
            if (trimmed.startsWith("//") || trimmed.isEmpty()) continue;

            // Check for invalid assignment "=" instead of ":="
            Matcher mAssign = badAssignPat.matcher(trimmed);
            if (mAssign.matches()) {
                String lhs = mAssign.group(1).toUpperCase(Locale.ROOT);
                if (!ST_KEYWORDS.contains(lhs)) {
                    diags.add(new Diagnostic(i + 1, 0, rawLine.length(), Severity.ERROR,
                            "Invalid assignment '=' on variable '" + mAssign.group(1) + "'. Use ':=' in Structured Text.", "INVALID_ASSIGNMENT"));
                }
            }
        }
    }

    private static void checkUndeclaredIdentifiers(String code, List<StVariable> declaredVars, Project project, List<Diagnostic> diags) {
        Set<String> known = new HashSet<>();
        for (String kw : ST_KEYWORDS) known.add(kw.toUpperCase(Locale.ROOT));
        for (String fn : ST_BUILTIN_FUNCTIONS) known.add(fn.toUpperCase(Locale.ROOT));
        for (String fb : ST_STANDARD_FBS) known.add(fb.toUpperCase(Locale.ROOT));

        if (declaredVars != null) {
            for (StVariable v : declaredVars) {
                known.add(v.getName().toUpperCase(Locale.ROOT));
            }
        }

        if (project != null) {
            for (Page p : project.getPages()) {
                if (p.isMacro()) {
                    known.add(p.getCleanName().toUpperCase(Locale.ROOT));
                    known.add(p.getName().toUpperCase(Locale.ROOT));
                }
            }
        }

        for (cz.maramat.model.BlockDefinition def : cz.maramat.model.BlockCatalog.getAllDefinitions().values()) {
            known.add(def.getTypeName().toUpperCase(Locale.ROOT));
            for (cz.maramat.model.BlockDefinition.PinSpec p : def.getDefaultInputs()) {
                known.add(p.shortName().toUpperCase(Locale.ROOT));
            }
            for (cz.maramat.model.BlockDefinition.PinSpec p : def.getDefaultOutputs()) {
                known.add(p.shortName().toUpperCase(Locale.ROOT));
            }
        }

        String stripped = stripCommentsAndStrings(code);
        String[] lines = stripped.split("\n", -1);
        Pattern identPat = Pattern.compile("\\b([A-Za-z_][A-Za-z0-9_]*)\\b");

        for (int lineIdx = 0; lineIdx < lines.length; lineIdx++) {
            String line = lines[lineIdx];
            Matcher m = identPat.matcher(line);
            while (m.find()) {
                String id = m.group(1);
                String upper = id.toUpperCase(Locale.ROOT);

                if (known.contains(upper)) continue;
                if (isHardwareIo(upper)) continue;

                // Ignore named argument labels like "En := 1" or output mappings "Q => out"
                String remaining = line.substring(m.end()).trim();
                if (remaining.startsWith("=>")) continue;
                if (remaining.startsWith(":=") && !line.trim().startsWith(id)) continue;

                // Unknown identifier warning
                diags.add(new Diagnostic(lineIdx + 1, m.start(), m.end(), Severity.WARNING,
                        "Undeclared identifier '" + id + "'. Add it to the variable table above.", "UNDECLARED_ID"));
            }
        }
    }

    public static boolean isHardwareIo(String word) {
        if (word == null) return false;
        String w = word.toUpperCase(Locale.ROOT);
        return w.matches("^(?:DI|DO|AI|AO|PWM|M)\\d+$") || w.matches("^REG_\\d+$");
    }

    private static String stripCommentsAndStrings(String code) {
        // Strip multi-line comments /* ... */ and (* ... *)
        String s = code.replaceAll("/\\*[\\s\\S]*?\\*/", " ");
        s = s.replaceAll("\\(\\*[\\s\\S]*?\\*\\)", " ");
        // Strip single line comments // ...
        s = s.replaceAll("//.*", " ");
        // Strip single quoted strings '...'
        s = s.replaceAll("'[^'\\n]*'", "' '");
        return s;
    }

    private static volatile List<CompletionItem> staticCompletions;
    private static volatile String[] staticCompletionKeys;

    private static void ensureStaticCompletions() {
        if (staticCompletions != null) return;
        synchronized (StLanguageServer.class) {
            if (staticCompletions != null) return;
            List<CompletionItem> all = new ArrayList<>();
            addHardwareIoCompletions(all, "");
            addFunctionCompletions(all, "");
            addFunctionBlockCompletions(all, "");
            for (String kw : ST_KEYWORDS) {
                all.add(new CompletionItem(kw, kw, CompletionKind.KEYWORD, "Keyword", "IEC Structured Text keyword: " + kw));
            }
            addSnippetCompletions(all, "");
            Collections.sort(all);
            String[] keys = new String[all.size()];
            for (int i = 0; i < keys.length; i++) keys[i] = all.get(i).label().toLowerCase(Locale.ROOT);
            staticCompletionKeys = keys;
            staticCompletions = List.copyOf(all);
        }
    }

    /** Pre-builds the static completion tables (call from a background thread). */
    public static void warmUp() {
        ensureStaticCompletions();
    }

    /**
     * Computes completion items based on current caret offset and prefix typed.
     * Static tables are cached; only the (cheap) filter runs per call.
     */
    public static List<CompletionItem> getCompletions(String code, int caretOffset, List<StVariable> vars, Project project) {
        ensureStaticCompletions();
        String prefix = extractPrefix(code, caretOffset).toLowerCase(Locale.ROOT);

        List<CompletionItem> startsWith = new ArrayList<>();
        List<CompletionItem> contains = new ArrayList<>();

        if (vars != null) {
            for (StVariable v : vars) {
                String name = v.getName();
                int m = matchRank(name.toLowerCase(Locale.ROOT), prefix);
                if (m < 0) continue;
                CompletionItem it = new CompletionItem(
                        name, name, CompletionKind.VARIABLE,
                        v.getScope() + " " + v.getDataType() + " := " + v.getInitialValue(),
                        v.getDescription() != null && !v.getDescription().isBlank() ? v.getDescription() : "Declared variable");
                (m == 0 ? startsWith : contains).add(it);
            }
        }
        if (project != null) {
            for (Page p : project.getPages()) {
                if (!p.isMacro()) continue;
                String mName = p.getCleanName();
                int m = matchRank(mName.toLowerCase(Locale.ROOT), prefix);
                if (m < 0) continue;
                CompletionItem it = new CompletionItem(mName, mName, CompletionKind.FUNCTION_BLOCK,
                        "Macro (" + p.getLanguage().getShortCode() + ")", "Project Macro Function Block: " + p.getName());
                (m == 0 ? startsWith : contains).add(it);
            }
        }
        Collections.sort(startsWith);
        Collections.sort(contains);

        List<CompletionItem> sItems = staticCompletions;
        String[] keys = staticCompletionKeys;
        for (int i = 0; i < keys.length; i++) {
            int m = matchRank(keys[i], prefix);
            if (m < 0) continue;
            (m == 0 ? startsWith : contains).add(sItems.get(i)); // static list already sorted
        }
        // Dynamic entries were sorted separately; keep kind order stable within each group
        startsWith.sort(null);
        contains.sort(null);
        List<CompletionItem> result = new ArrayList<>(startsWith.size() + contains.size());
        result.addAll(startsWith);
        result.addAll(contains);
        return result;
    }

    /** 0 = prefix match, 1 = substring match (prefix >= 2 chars), -1 = no match. */
    private static int matchRank(String lowerText, String prefix) {
        if (prefix.isEmpty() || lowerText.startsWith(prefix)) return 0;
        if (prefix.length() >= 2 && lowerText.contains(prefix)) return 1;
        return -1;
    }

    private static boolean matchesPrefix(String text, String prefix) {
        if (prefix == null || prefix.isEmpty()) return true;
        String t = text.toLowerCase(Locale.ROOT);
        return t.startsWith(prefix) || (prefix.length() >= 2 && t.contains(prefix));
    }

    public static String extractPrefix(String code, int caretOffset) {
        if (code == null || caretOffset <= 0 || caretOffset > code.length()) return "";
        int start = caretOffset;
        while (start > 0) {
            char c = code.charAt(start - 1);
            if (Character.isLetterOrDigit(c) || c == '_') {
                start--;
            } else {
                break;
            }
        }
        return code.substring(start, caretOffset);
    }

    private static void addHardwareIoCompletions(List<CompletionItem> items, String prefix) {
        // Digital Inputs DI0..DI15
        for (int i = 0; i < 16; i++) {
            String name = "DI" + i;
            if (matchesPrefix(name, prefix)) {
                items.add(new CompletionItem(name, name, CompletionKind.HARDWARE_IO, "BOOL (Opto 24V)", "Digital Input Pin " + i));
            }
        }
        // Digital Outputs DO0..DO15
        for (int i = 0; i < 16; i++) {
            String name = "DO" + i;
            if (matchesPrefix(name, prefix)) {
                items.add(new CompletionItem(name, name, CompletionKind.HARDWARE_IO, "BOOL (Relay/SSR)", "Digital Output Pin " + i));
            }
        }
        // Analog Inputs AI0..AI7
        for (int i = 0; i < 8; i++) {
            String name = "AI" + i;
            if (matchesPrefix(name, prefix)) {
                items.add(new CompletionItem(name, name, CompletionKind.HARDWARE_IO, "REAL (0-10V / 4-20mA)", "Analog Input ADC Channel " + i));
            }
        }
        // Analog Outputs AO0..AO3
        for (int i = 0; i < 4; i++) {
            String name = "AO" + i;
            if (matchesPrefix(name, prefix)) {
                items.add(new CompletionItem(name, name, CompletionKind.HARDWARE_IO, "REAL (0-10V DAC)", "Analog Output DAC Channel " + i));
            }
        }
        // PWM Outputs PWM0..PWM3
        for (int i = 0; i < 4; i++) {
            String name = "PWM" + i;
            if (matchesPrefix(name, prefix)) {
                items.add(new CompletionItem(name, name, CompletionKind.HARDWARE_IO, "REAL (0-100% Duty)", "PWM Output Timer Channel " + i));
            }
        }
        // Memory Bits M0..M15
        for (int i = 0; i < 16; i++) {
            String name = "M" + i;
            if (matchesPrefix(name, prefix)) {
                items.add(new CompletionItem(name, name, CompletionKind.HARDWARE_IO, "BOOL (Internal Flag)", "Memory Marker Bit " + i));
            }
        }
        // Retentive Registers REG_0..REG_15
        for (int i = 0; i < 16; i++) {
            String name = "REG_" + i;
            if (matchesPrefix(name, prefix)) {
                items.add(new CompletionItem(name, name, CompletionKind.HARDWARE_IO, "REAL (Retentive FRAM)", "PLC 32-bit Data Register " + i));
            }
        }
    }

    private static void addFunctionCompletions(List<CompletionItem> items, String prefix) {
        Object[][] fns = {
                {"LIMIT", "LIMIT(min, in, max)", "REAL", "LIMIT(MN, IN, MX): Clamps input between bounds MN and MX"},
                {"MIN", "MIN(in1, in2)", "REAL", "MIN(IN1, IN2): Returns the lesser of two values"},
                {"MAX", "MAX(in1, in2)", "REAL", "MAX(IN1, IN2): Returns the greater of two values"},
                {"ABS", "ABS(in)", "REAL", "ABS(IN): Absolute value"},
                {"SQRT", "SQRT(in)", "REAL", "SQRT(IN): Square root"},
                {"SEL", "SEL(g, in0, in1)", "REAL/BOOL", "SEL(G, IN0, IN1): Binary select. Returns IN0 if G=FALSE, IN1 if G=TRUE"},
                {"MUX", "MUX(k, in0, in1)", "REAL", "MUX(K, IN0, IN1, ...): Multiplexer output based on index K"},
                {"SIN", "SIN(rad)", "REAL", "SIN(X): Sine of radians"},
                {"COS", "COS(rad)", "REAL", "COS(X): Cosine of radians"},
                {"TAN", "TAN(rad)", "REAL", "TAN(X): Tangent of radians"},
                {"ROUND", "ROUND(in)", "DINT", "ROUND(X): Round to nearest integer"},
                {"TRUNC", "TRUNC(in)", "DINT", "TRUNC(X): Truncate fractional part"},
                {"RANDOM", "RANDOM(min, max)", "REAL/DINT", "RANDOM([trig, ]min, max): Generates a random number between bounds"},
                {"RND", "RND(min, max)", "REAL/DINT", "RND([trig, ]min, max): Short alias for RANDOM"},
                {"SINGLEPULSE", "SINGLEPULSE(in)", "BOOL", "SINGLEPULSE(IN): 1-cycle edge pulse output on rising edge of IN"},
                {"ONESHOT", "ONESHOT(in)", "BOOL", "ONESHOT(IN): 1-cycle edge pulse output on rising edge of IN"}
        };
        for (Object[] row : fns) {
            String name = (String) row[0];
            String sig = (String) row[1];
            String ret = (String) row[2];
            String doc = (String) row[3];
            if (matchesPrefix(name, prefix)) {
                items.add(new CompletionItem(name, sig, CompletionKind.FUNCTION, ret + " " + sig, doc));
            }
        }
    }

    private static void addFunctionBlockCompletions(List<CompletionItem> items, String prefix) {
        Object[][] fbs = {
                {"TON", "TON", "Timer", "On-Delay Timer: IN (BOOL), PT (TIME) -> Q (BOOL), ET (TIME)"},
                {"TOF", "TOF", "Timer", "Off-Delay Timer: IN (BOOL), PT (TIME) -> Q (BOOL), ET (TIME)"},
                {"TP", "TP", "Timer", "Pulse Timer: IN (BOOL), PT (TIME) -> Q (BOOL), ET (TIME)"},
                {"CTU", "CTU", "Counter", "Count Up Counter: CU (BOOL), RESET (BOOL), PV (INT) -> Q (BOOL), CV (INT)"},
                {"CTD", "CTD", "Counter", "Count Down Counter: CD (BOOL), LOAD (BOOL), PV (INT) -> Q (BOOL), CV (INT)"},
                {"R_TRIG", "R_TRIG", "Edge", "Rising Edge Trigger: CLK (BOOL) -> Q (BOOL)"},
                {"F_TRIG", "F_TRIG", "Edge", "Falling Edge Trigger: CLK (BOOL) -> Q (BOOL)"},
                {"SR", "SR", "Bistable", "Set-Dominant Bistable Latch: S1 (BOOL), R (BOOL) -> Q1 (BOOL)"},
                {"RS", "RS", "Bistable", "Reset-Dominant Bistable Latch: S (BOOL), R1 (BOOL) -> Q1 (BOOL)"},
                {"RANDOM", "RANDOM", "Math", "Random Value Generator: TRIG (BOOL), MIN (INT), MAX (INT) -> Y (INT/REAL)"},
                {"RND", "RND", "Math", "Random Value Generator: TRIG (BOOL), MIN (INT), MAX (INT) -> Y (INT/REAL)"},
                {"COUNTER", "COUNTER", "Timers", "Up/Down Counter: CU (BOOL), CD (BOOL), R (BOOL), PV (INT) -> Q (BOOL), CV (INT)"},
                {"CNTR", "CNTR", "Timers", "Up/Down Counter: CU (BOOL), CD (BOOL), R (BOOL), PV (INT) -> Q (BOOL), CV (INT)"},
                {"SINGLEPULSE", "SINGLEPULSE", "Timers", "Single Cycle Pulse Generator: IN (BOOL) -> Q (BOOL)"},
                {"ONESHOT", "ONESHOT", "Timers", "Single Cycle Pulse Generator: IN (BOOL) -> Q (BOOL)"}
        };
        for (Object[] row : fbs) {
            String name = (String) row[0];
            String ins = (String) row[1];
            String det = (String) row[2];
            String doc = (String) row[3];
            if (matchesPrefix(name, prefix)) {
                items.add(new CompletionItem(name, ins, CompletionKind.FUNCTION_BLOCK, det, doc));
            }
        }
    }

    private static void addSnippetCompletions(List<CompletionItem> items, String prefix) {
        Object[][] snips = {
                {"if", "IF condition THEN\n    ;\nEND_IF;", "Snippet", "IF .. THEN .. END_IF;"},
                {"ifelse", "IF condition THEN\n    ;\nELSE\n    ;\nEND_IF;", "Snippet", "IF .. THEN .. ELSE .. END_IF;"},
                {"case", "CASE selector OF\n    0:\n        ;\n    ELSE\n        ;\nEND_CASE;", "Snippet", "CASE .. OF .. END_CASE;"},
                {"for", "FOR i := 0 TO 10 DO\n    ;\nEND_FOR;", "Snippet", "FOR .. TO .. DO .. END_FOR;"},
                {"while", "WHILE condition DO\n    ;\nEND_WHILE;", "Snippet", "WHILE .. DO .. END_WHILE;"},
                {"repeat", "REPEAT\n    ;\nUNTIL condition END_REPEAT;", "Snippet", "REPEAT .. UNTIL .. END_REPEAT;"},
                {"var", "VAR\n    VarName : INT := 0;\nEND_VAR", "Snippet", "VAR declaration block"}
        };
        for (Object[] s : snips) {
            String name = (String) s[0];
            String code = (String) s[1];
            String det = (String) s[2];
            String doc = (String) s[3];
            if (matchesPrefix(name, prefix)) {
                items.add(new CompletionItem(name, code, CompletionKind.SNIPPET, det, doc));
            }
        }
    }

    /**
     * Returns rich HTML hover quick documentation for the token under caret.
     */
    public static String getHoverDoc(String token, List<StVariable> vars, Project project) {
        if (token == null || token.isBlank()) return null;
        String t = token.trim();
        String upper = t.toUpperCase(Locale.ROOT);

        // Check declared variables
        if (vars != null) {
            for (StVariable v : vars) {
                if (v.getName().equalsIgnoreCase(t)) {
                    return "<html><b>Variable:</b> " + v.getName() + "<br>"
                            + "<b>Scope:</b> " + v.getScope() + "<br>"
                            + "<b>Type:</b> " + v.getDataType() + "<br>"
                            + "<b>Initial Value:</b> " + v.getInitialValue()
                            + (v.getDescription() != null && !v.getDescription().isBlank() ? "<br><b>Description:</b> " + v.getDescription() : "")
                            + "</html>";
                }
            }
        }

        // Check Hardware I/O
        if (upper.matches("^DI\\d+$")) {
            return "<html><b>Digital Input:</b> " + upper + "<br><b>Type:</b> BOOL<br>24V DC opto-isolated industrial input channel.</html>";
        }
        if (upper.matches("^DO\\d+$")) {
            return "<html><b>Digital Output:</b> " + upper + "<br><b>Type:</b> BOOL<br>Relay / SSR industrial digital output channel.</html>";
        }
        if (upper.matches("^AI\\d+$")) {
            return "<html><b>Analog Input:</b> " + upper + "<br><b>Type:</b> REAL<br>12-bit ADC input channel (0-10V / 4-20mA).</html>";
        }
        if (upper.matches("^AO\\d+$")) {
            return "<html><b>Analog Output:</b> " + upper + "<br><b>Type:</b> REAL<br>12-bit DAC output channel (0-10V).</html>";
        }
        if (upper.matches("^PWM\\d+$")) {
            return "<html><b>PWM Output:</b> " + upper + "<br><b>Type:</b> REAL<br>High-speed timer PWM output (0.0 to 100.0% duty).</html>";
        }
        if (upper.matches("^M\\d+$")) {
            return "<html><b>Memory Marker:</b> " + upper + "<br><b>Type:</b> BOOL<br>Internal memory flag marker bit.</html>";
        }
        if (upper.matches("^REG_\\d+$")) {
            return "<html><b>PLC Register:</b> " + upper + "<br><b>Type:</b> REAL / DINT<br>Retentive 32-bit register stored in external FRAM/EEPROM.</html>";
        }

        // Check built-in functions
        if (ST_BUILTIN_FUNCTIONS.contains(upper)) {
            return "<html><b>Function:</b> " + upper + "<br>Standard mathematical / selection function.</html>";
        }

        // Check function blocks
        if (ST_STANDARD_FBS.contains(upper)) {
            return "<html><b>Function Block:</b> " + upper + "<br>Standard IEC timer, counter, or trigger block.</html>";
        }

        // Check keywords
        if (ST_KEYWORDS.contains(upper)) {
            return "<html><b>Keyword:</b> " + upper + "<br>IEC Structured Text control flow or type keyword.</html>";
        }

        return null;
    }

    /**
     * Formats Structured Text code with standard 4-space indentation per nesting level.
     */
    public static String formatCode(String code) {
        if (code == null || code.isBlank()) return code;

        String[] lines = code.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        int indentLevel = 0;

        Pattern openBlockPat = Pattern.compile("^\\s*(IF|CASE|FOR|WHILE|REPEAT|VAR|VAR_INPUT|VAR_OUTPUT|VAR_IN_OUT)\\b", Pattern.CASE_INSENSITIVE);
        Pattern closeBlockPat = Pattern.compile("^\\s*(END_IF|END_CASE|END_FOR|END_WHILE|END_REPEAT|END_VAR|UNTIL)\\b", Pattern.CASE_INSENSITIVE);
        Pattern midBlockPat = Pattern.compile("^\\s*(ELSE|ELSIF)\\b", Pattern.CASE_INSENSITIVE);

        for (String raw : lines) {
            String trimmed = raw.trim();
            if (trimmed.isEmpty()) {
                sb.append("\n");
                continue;
            }

            boolean isClose = closeBlockPat.matcher(trimmed).find();
            boolean isMid = midBlockPat.matcher(trimmed).find();

            if (isClose || isMid) {
                indentLevel = Math.max(0, indentLevel - 1);
            }

            sb.append("    ".repeat(indentLevel));
            sb.append(trimmed);
            sb.append("\n");

            boolean isOpen = openBlockPat.matcher(trimmed).find();
            if ((isOpen || isMid) && !trimmed.contains("END_")) {
                indentLevel++;
            }
        }

        String res = sb.toString();
        if (!code.endsWith("\n") && res.endsWith("\n")) {
            res = res.substring(0, res.length() - 1);
        }
        return res;
    }
}
