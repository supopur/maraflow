package cz.maramat.sim;

import cz.maramat.model.StVariable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Deterministic IEC 61131-3 Structured Text (ST) lexer, recursive-descent parser,
 * validator, and bounded-iteration evaluator for Maraflow PLC pages and ST Macros.
 */
public final class StInterpreter {

    private static final int MAX_LOOP_ITERATIONS = 1000;

    public interface OperandAccessor {
        double readOperand(String name);
        void writeOperand(String name, double value);
    }

    public record Diagnostic(int line, String message) {
        @Override
        public String toString() {
            return "Line " + line + ": " + message;
        }
    }

    private enum TokType {
        IDENT, NUMBER,
        ASSIGN,         // :=
        SEMI,           // ;
        COLON,          // :
        COMMA,          // ,
        DOTDOT,         // ..
        LPAREN, RPAREN, // ( )
        PLUS, MINUS, STAR, SLASH, POWER, // + - * / **
        EQ, NEQ, LT, LTE, GT, GTE,       // = <> < <= > >=
        KW_IF, KW_THEN, KW_ELSIF, KW_ELSE, KW_END_IF,
        KW_CASE, KW_OF, KW_END_CASE,
        KW_FOR, KW_TO, KW_BY, KW_DO, KW_END_FOR,
        KW_WHILE, KW_END_WHILE,
        KW_REPEAT, KW_UNTIL, KW_END_REPEAT,
        KW_EXIT, KW_RETURN,
        KW_AND, KW_OR, KW_XOR, KW_NOT, KW_MOD,
        KW_TRUE, KW_FALSE,
        EOF
    }

    private record Token(TokType type, String text, double numVal, int line) {}

    private StInterpreter() {}

    public static List<Diagnostic> validate(String code, List<StVariable> variables) {
        List<Diagnostic> diags = new ArrayList<>();
        if (code == null || code.isBlank()) {
            return diags;
        }
        try {
            StDeclarationSync.CodeSplit split = StDeclarationSync.splitCode(code);
            String execBody = split.executableBody();
            if (execBody.isBlank()) {
                return diags;
            }
            List<Token> tokens = tokenize(execBody);
            Parser parser = new Parser(tokens, diags);
            parser.parseStatementList(TokType.EOF);
        } catch (StSyntaxException ex) {
            diags.add(new Diagnostic(ex.line, ex.getMessage()));
        } catch (Exception ex) {
            diags.add(new Diagnostic(1, "Syntax error: " + ex.getMessage()));
        }
        return diags;
    }

    public static void execute(String code, Map<String, Double> localVars, OperandAccessor accessor) {
        if (code == null || code.isBlank()) return;
        try {
            StDeclarationSync.CodeSplit split = StDeclarationSync.splitCode(code);
            String execBody = split.executableBody();
            if (execBody.isBlank()) return;

            List<Diagnostic> diags = new ArrayList<>();
            List<Token> tokens = tokenize(execBody);
            Parser parser = new Parser(tokens, diags);
            List<Stmt> stmts = parser.parseStatementList(TokType.EOF);
            if (!diags.isEmpty()) {
                return;
            }
            EvalContext ctx = new EvalContext(localVars, accessor);
            for (Stmt s : stmts) {
                s.exec(ctx);
                if (ctx.returnFlag || ctx.exitFlag) break;
            }
        } catch (Exception ignored) {
            // Runtime faults are suppressed to keep scan cycle deterministic
        }
    }

    private static final class StSyntaxException extends RuntimeException {
        final int line;
        StSyntaxException(int line, String msg) {
            super(msg);
            this.line = line;
        }
    }

    private static List<Token> tokenize(String src) {
        List<Token> out = new ArrayList<>();
        int n = src.length();
        int i = 0;
        int line = 1;

        while (i < n) {
            char c = src.charAt(i);
            if (c == '\n') {
                line++;
                i++;
                continue;
            }
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            // Single-line comment //
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                i += 2;
                while (i < n && src.charAt(i) != '\n') i++;
                continue;
            }
            // Block comment (* ... *)
            if (c == '(' && i + 1 < n && src.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(src.charAt(i) == '*' && src.charAt(i + 1) == ')')) {
                    if (src.charAt(i) == '\n') line++;
                    i++;
                }
                i = Math.min(n, i + 2);
                continue;
            }
            // Block comment /* ... */
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(src.charAt(i) == '*' && src.charAt(i + 1) == '/')) {
                    if (src.charAt(i) == '\n') line++;
                    i++;
                }
                i = Math.min(n, i + 2);
                continue;
            }

            // Multi-char operators
            if (c == ':' && i + 1 < n && src.charAt(i + 1) == '=') {
                out.add(new Token(TokType.ASSIGN, ":=", 0, line));
                i += 2;
                continue;
            }
            if (c == '.' && i + 1 < n && src.charAt(i + 1) == '.') {
                out.add(new Token(TokType.DOTDOT, "..", 0, line));
                i += 2;
                continue;
            }
            if (c == '<' && i + 1 < n && src.charAt(i + 1) == '>') {
                out.add(new Token(TokType.NEQ, "<>", 0, line));
                i += 2;
                continue;
            }
            if (c == '!' && i + 1 < n && src.charAt(i + 1) == '=') {
                out.add(new Token(TokType.NEQ, "!=", 0, line));
                i += 2;
                continue;
            }
            if (c == '<' && i + 1 < n && src.charAt(i + 1) == '=') {
                out.add(new Token(TokType.LTE, "<=", 0, line));
                i += 2;
                continue;
            }
            if (c == '>' && i + 1 < n && src.charAt(i + 1) == '=') {
                out.add(new Token(TokType.GTE, ">=", 0, line));
                i += 2;
                continue;
            }
            if (c == '*' && i + 1 < n && src.charAt(i + 1) == '*') {
                out.add(new Token(TokType.POWER, "**", 0, line));
                i += 2;
                continue;
            }

            // Single-char tokens
            switch (c) {
                case ';' -> { out.add(new Token(TokType.SEMI, ";", 0, line)); i++; continue; }
                case ':' -> { out.add(new Token(TokType.COLON, ":", 0, line)); i++; continue; }
                case ',' -> { out.add(new Token(TokType.COMMA, ",", 0, line)); i++; continue; }
                case '(' -> { out.add(new Token(TokType.LPAREN, "(", 0, line)); i++; continue; }
                case ')' -> { out.add(new Token(TokType.RPAREN, ")", 0, line)); i++; continue; }
                case '+' -> { out.add(new Token(TokType.PLUS, "+", 0, line)); i++; continue; }
                case '-' -> { out.add(new Token(TokType.MINUS, "-", 0, line)); i++; continue; }
                case '*' -> { out.add(new Token(TokType.STAR, "*", 0, line)); i++; continue; }
                case '/' -> { out.add(new Token(TokType.SLASH, "/", 0, line)); i++; continue; }
                case '=' -> { out.add(new Token(TokType.EQ, "=", 0, line)); i++; continue; }
                case '<' -> { out.add(new Token(TokType.LT, "<", 0, line)); i++; continue; }
                case '>' -> { out.add(new Token(TokType.GT, ">", 0, line)); i++; continue; }
            }

            // Numbers (including 16#FF, 2#1010, decimals)
            if (Character.isDigit(c)) {
                int start = i;
                while (i < n && (Character.isLetterOrDigit(src.charAt(i)) || src.charAt(i) == '_' || src.charAt(i) == '#')) {
                    i++;
                }
                if (i < n && src.charAt(i) == '.' && (i + 1 >= n || src.charAt(i + 1) != '.')) {
                    i++;
                    while (i < n && (Character.isDigit(src.charAt(i)) || src.charAt(i) == '_')) {
                        i++;
                    }
                }
                String raw = src.substring(start, i).replace("_", "");
                double val = parseNumericLiteral(raw, line);
                out.add(new Token(TokType.NUMBER, raw, val, line));
                continue;
            }

            // Identifiers / Keywords
            if (Character.isLetter(c) || c == '_') {
                int start = i;
                while (i < n && (Character.isLetterOrDigit(src.charAt(i)) || src.charAt(i) == '_' || src.charAt(i) == '.')) {
                    i++;
                }
                String ident = src.substring(start, i);
                String upper = ident.toUpperCase(Locale.ROOT);
                TokType kw = switch (upper) {
                    case "IF" -> TokType.KW_IF;
                    case "THEN" -> TokType.KW_THEN;
                    case "ELSIF", "ELSEIF" -> TokType.KW_ELSIF;
                    case "ELSE" -> TokType.KW_ELSE;
                    case "END_IF", "ENDIF" -> TokType.KW_END_IF;
                    case "CASE" -> TokType.KW_CASE;
                    case "OF" -> TokType.KW_OF;
                    case "END_CASE", "ENDCASE" -> TokType.KW_END_CASE;
                    case "FOR" -> TokType.KW_FOR;
                    case "TO" -> TokType.KW_TO;
                    case "BY" -> TokType.KW_BY;
                    case "DO" -> TokType.KW_DO;
                    case "END_FOR", "ENDFOR" -> TokType.KW_END_FOR;
                    case "WHILE" -> TokType.KW_WHILE;
                    case "END_WHILE", "ENDWHILE" -> TokType.KW_END_WHILE;
                    case "REPEAT" -> TokType.KW_REPEAT;
                    case "UNTIL" -> TokType.KW_UNTIL;
                    case "END_REPEAT", "ENDREPEAT" -> TokType.KW_END_REPEAT;
                    case "EXIT" -> TokType.KW_EXIT;
                    case "RETURN" -> TokType.KW_RETURN;
                    case "AND", "&" -> TokType.KW_AND;
                    case "OR" -> TokType.KW_OR;
                    case "XOR" -> TokType.KW_XOR;
                    case "NOT" -> TokType.KW_NOT;
                    case "MOD" -> TokType.KW_MOD;
                    case "TRUE" -> TokType.KW_TRUE;
                    case "FALSE" -> TokType.KW_FALSE;
                    default -> TokType.IDENT;
                };
                out.add(new Token(kw, ident, kw == TokType.KW_TRUE ? 1.0 : 0.0, line));
                continue;
            }

            throw new StSyntaxException(line, "Unexpected character '" + c + "'");
        }

        out.add(new Token(TokType.EOF, "", 0, line));
        return out;
    }

    private static double parseNumericLiteral(String raw, int line) {
        try {
            int hashIdx = raw.indexOf('#');
            if (hashIdx > 0) {
                int radix = Integer.parseInt(raw.substring(0, hashIdx));
                String digits = raw.substring(hashIdx + 1);
                return Long.parseLong(digits, radix);
            }
            return Double.parseDouble(raw);
        } catch (NumberFormatException ex) {
            throw new StSyntaxException(line, "Invalid number literal '" + raw + "'");
        }
    }

    // =========================================================================
    // AST Nodes & Evaluator
    // =========================================================================

    private static final class EvalContext {
        final Map<String, Double> localVars;
        final OperandAccessor accessor;
        boolean exitFlag = false;
        boolean returnFlag = false;

        EvalContext(Map<String, Double> localVars, OperandAccessor accessor) {
            this.localVars = localVars;
            this.accessor = accessor;
        }

        double read(String name) {
            for (Map.Entry<String, Double> e : localVars.entrySet()) {
                if (e.getKey().equalsIgnoreCase(name)) {
                    return e.getValue();
                }
            }
            return accessor != null ? accessor.readOperand(name) : 0.0;
        }

        void write(String name, double val) {
            for (String k : localVars.keySet()) {
                if (k.equalsIgnoreCase(name)) {
                    localVars.put(k, val);
                    return;
                }
            }
            if (accessor != null) {
                accessor.writeOperand(name, val);
            } else {
                localVars.put(name, val);
            }
        }
    }

    private interface Stmt {
        void exec(EvalContext ctx);
    }

    private interface Expr {
        double eval(EvalContext ctx);
    }

    private record AssignStmt(String target, Expr expr) implements Stmt {
        @Override
        public void exec(EvalContext ctx) {
            ctx.write(target, expr.eval(ctx));
        }
    }

    private record Branch(Expr cond, List<Stmt> body) {}

    private record IfStmt(List<Branch> branches, List<Stmt> elseBody) implements Stmt {
        @Override
        public void exec(EvalContext ctx) {
            for (Branch b : branches) {
                if (Math.abs(b.cond().eval(ctx)) >= 0.5) {
                    for (Stmt s : b.body()) {
                        s.exec(ctx);
                        if (ctx.exitFlag || ctx.returnFlag) return;
                    }
                    return;
                }
            }
            if (elseBody != null) {
                for (Stmt s : elseBody) {
                    s.exec(ctx);
                    if (ctx.exitFlag || ctx.returnFlag) return;
                }
            }
        }
    }

    private record CaseRange(int low, int high) {
        boolean matches(int val) {
            return val >= low && val <= high;
        }
    }

    private record CaseArm(List<CaseRange> ranges, List<Stmt> body) {}

    private record CaseStmt(Expr selector, List<CaseArm> arms, List<Stmt> elseBody) implements Stmt {
        @Override
        public void exec(EvalContext ctx) {
            int sel = (int) Math.round(selector.eval(ctx));
            for (CaseArm arm : arms) {
                for (CaseRange r : arm.ranges()) {
                    if (r.matches(sel)) {
                        for (Stmt s : arm.body()) {
                            s.exec(ctx);
                            if (ctx.exitFlag || ctx.returnFlag) return;
                        }
                        return;
                    }
                }
            }
            if (elseBody != null) {
                for (Stmt s : elseBody) {
                    s.exec(ctx);
                    if (ctx.exitFlag || ctx.returnFlag) return;
                }
            }
        }
    }

    private record ForStmt(String varName, Expr startExpr, Expr endExpr, Expr stepExpr, List<Stmt> body) implements Stmt {
        @Override
        public void exec(EvalContext ctx) {
            double cur = Math.round(startExpr.eval(ctx));
            double end = Math.round(endExpr.eval(ctx));
            double step = (stepExpr != null) ? Math.round(stepExpr.eval(ctx)) : 1.0;
            if (step == 0) step = 1.0;

            int guards = 0;
            ctx.write(varName, cur);
            while ((step > 0 ? cur <= end : cur >= end) && guards++ < MAX_LOOP_ITERATIONS) {
                for (Stmt s : body) {
                    s.exec(ctx);
                    if (ctx.returnFlag) return;
                    if (ctx.exitFlag) {
                        ctx.exitFlag = false;
                        return;
                    }
                }
                cur = ctx.read(varName) + step;
                ctx.write(varName, cur);
            }
        }
    }

    private record WhileStmt(Expr cond, List<Stmt> body) implements Stmt {
        @Override
        public void exec(EvalContext ctx) {
            int guards = 0;
            while (Math.abs(cond.eval(ctx)) >= 0.5 && guards++ < MAX_LOOP_ITERATIONS) {
                for (Stmt s : body) {
                    s.exec(ctx);
                    if (ctx.returnFlag) return;
                    if (ctx.exitFlag) {
                        ctx.exitFlag = false;
                        return;
                    }
                }
            }
        }
    }

    private record RepeatStmt(List<Stmt> body, Expr untilCond) implements Stmt {
        @Override
        public void exec(EvalContext ctx) {
            int guards = 0;
            do {
                for (Stmt s : body) {
                    s.exec(ctx);
                    if (ctx.returnFlag) return;
                    if (ctx.exitFlag) {
                        ctx.exitFlag = false;
                        return;
                    }
                }
            } while (Math.abs(untilCond.eval(ctx)) < 0.5 && ++guards < MAX_LOOP_ITERATIONS);
        }
    }

    private record ExitStmt() implements Stmt {
        @Override
        public void exec(EvalContext ctx) {
            ctx.exitFlag = true;
        }
    }

    private record ReturnStmt() implements Stmt {
        @Override
        public void exec(EvalContext ctx) {
            ctx.returnFlag = true;
        }
    }

    // =========================================================================
    // Recursive-Descent Parser
    // =========================================================================

    private static final class Parser {
        private final List<Token> tokens;
        private final List<Diagnostic> diags;
        private int pos = 0;

        Parser(List<Token> tokens, List<Diagnostic> diags) {
            this.tokens = tokens;
            this.diags = diags;
        }

        private Token peek() {
            return tokens.get(pos);
        }

        private Token next() {
            Token t = tokens.get(pos);
            if (t.type != TokType.EOF) pos++;
            return t;
        }

        private boolean match(TokType type) {
            if (peek().type == type) {
                next();
                return true;
            }
            return false;
        }

        private Token expect(TokType type, String msg) {
            Token t = peek();
            if (t.type == type) {
                return next();
            }
            throw new StSyntaxException(t.line, msg + " (found '" + (t.text.isEmpty() ? "EOF" : t.text) + "')");
        }

        List<Stmt> parseStatementList(TokType... stopTokens) {
            List<Stmt> list = new ArrayList<>();
            while (peek().type != TokType.EOF && !isStopToken(peek().type, stopTokens)) {
                if (match(TokType.SEMI)) continue;
                list.add(parseStatement());
                match(TokType.SEMI);
            }
            return list;
        }

        private boolean isStopToken(TokType type, TokType[] stops) {
            for (TokType s : stops) {
                if (type == s) return true;
            }
            return false;
        }

        private Stmt parseStatement() {
            Token t = peek();
            return switch (t.type) {
                case KW_IF -> parseIf();
                case KW_CASE -> parseCase();
                case KW_FOR -> parseFor();
                case KW_WHILE -> parseWhile();
                case KW_REPEAT -> parseRepeat();
                case KW_EXIT -> { next(); yield new ExitStmt(); }
                case KW_RETURN -> { next(); yield new ReturnStmt(); }
                case IDENT -> {
                    Token id = next();
                    expect(TokType.ASSIGN, "Expected ':=' in assignment to '" + id.text + "'");
                    Expr rhs = parseExpr();
                    yield new AssignStmt(id.text, rhs);
                }
                default -> throw new StSyntaxException(t.line, "Unexpected token '" + t.text + "' at start of statement");
            };
        }

        private Stmt parseIf() {
            expect(TokType.KW_IF, "Expected IF");
            Expr cond = parseExpr();
            expect(TokType.KW_THEN, "Expected THEN after IF condition");
            List<Branch> branches = new ArrayList<>();
            List<Stmt> thenBody = parseStatementList(TokType.KW_ELSIF, TokType.KW_ELSE, TokType.KW_END_IF);
            branches.add(new Branch(cond, thenBody));

            while (match(TokType.KW_ELSIF)) {
                Expr c = parseExpr();
                expect(TokType.KW_THEN, "Expected THEN after ELSIF condition");
                List<Stmt> b = parseStatementList(TokType.KW_ELSIF, TokType.KW_ELSE, TokType.KW_END_IF);
                branches.add(new Branch(c, b));
            }

            List<Stmt> elseBody = null;
            if (match(TokType.KW_ELSE)) {
                elseBody = parseStatementList(TokType.KW_END_IF);
            }
            expect(TokType.KW_END_IF, "Expected END_IF");
            return new IfStmt(branches, elseBody);
        }

        private Stmt parseCase() {
            expect(TokType.KW_CASE, "Expected CASE");
            Expr sel = parseExpr();
            expect(TokType.KW_OF, "Expected OF after CASE selector");
            List<CaseArm> arms = new ArrayList<>();
            List<Stmt> elseBody = null;

            while (peek().type != TokType.EOF && peek().type != TokType.KW_END_CASE) {
                if (match(TokType.KW_ELSE)) {
                    elseBody = parseStatementList(TokType.KW_END_CASE);
                    break;
                }
                List<CaseRange> ranges = new ArrayList<>();
                do {
                    int low = parseCaseInt();
                    int high = low;
                    if (match(TokType.DOTDOT)) {
                        high = parseCaseInt();
                    }
                    ranges.add(new CaseRange(low, high));
                } while (match(TokType.COMMA));
                expect(TokType.COLON, "Expected ':' after CASE label");

                List<Stmt> armStmts = new ArrayList<>();
                while (peek().type != TokType.EOF && peek().type != TokType.KW_END_CASE && peek().type != TokType.KW_ELSE && !isCaseLabelStart()) {
                    if (match(TokType.SEMI)) continue;
                    armStmts.add(parseStatement());
                    match(TokType.SEMI);
                }
                arms.add(new CaseArm(ranges, armStmts));
            }
            expect(TokType.KW_END_CASE, "Expected END_CASE");
            return new CaseStmt(sel, arms, elseBody);
        }

        private boolean isCaseLabelStart() {
            int p = pos;
            if (p < tokens.size() && (tokens.get(p).type == TokType.MINUS || tokens.get(p).type == TokType.PLUS)) p++;
            if (p < tokens.size() && tokens.get(p).type == TokType.NUMBER) {
                p++;
                if (p < tokens.size() && (tokens.get(p).type == TokType.COLON || tokens.get(p).type == TokType.COMMA || tokens.get(p).type == TokType.DOTDOT)) {
                    return true;
                }
            }
            return false;
        }

        private int parseCaseInt() {
            boolean neg = match(TokType.MINUS);
            if (!neg) match(TokType.PLUS);
            Token num = expect(TokType.NUMBER, "Expected integer constant in CASE label");
            int val = (int) Math.round(num.numVal);
            return neg ? -val : val;
        }

        private Stmt parseFor() {
            expect(TokType.KW_FOR, "Expected FOR");
            Token varTok = expect(TokType.IDENT, "Expected loop variable identifier after FOR");
            expect(TokType.ASSIGN, "Expected ':=' after FOR variable");
            Expr start = parseExpr();
            expect(TokType.KW_TO, "Expected TO in FOR loop");
            Expr end = parseExpr();
            Expr step = null;
            if (match(TokType.KW_BY)) {
                step = parseExpr();
            }
            expect(TokType.KW_DO, "Expected DO in FOR loop");
            List<Stmt> body = parseStatementList(TokType.KW_END_FOR);
            expect(TokType.KW_END_FOR, "Expected END_FOR");
            return new ForStmt(varTok.text, start, end, step, body);
        }

        private Stmt parseWhile() {
            expect(TokType.KW_WHILE, "Expected WHILE");
            Expr cond = parseExpr();
            expect(TokType.KW_DO, "Expected DO after WHILE condition");
            List<Stmt> body = parseStatementList(TokType.KW_END_WHILE);
            expect(TokType.KW_END_WHILE, "Expected END_WHILE");
            return new WhileStmt(cond, body);
        }

        private Stmt parseRepeat() {
            expect(TokType.KW_REPEAT, "Expected REPEAT");
            List<Stmt> body = parseStatementList(TokType.KW_UNTIL);
            expect(TokType.KW_UNTIL, "Expected UNTIL in REPEAT loop");
            Expr cond = parseExpr();
            match(TokType.SEMI);
            expect(TokType.KW_END_REPEAT, "Expected END_REPEAT");
            return new RepeatStmt(body, cond);
        }

        // Expressions: OR -> XOR -> AND -> Comparison -> Add/Sub -> Mul/Div/Mod -> Power -> Unary -> Primary
        private Expr parseExpr() {
            return parseOr();
        }

        private Expr parseOr() {
            Expr left = parseXor();
            while (match(TokType.KW_OR)) {
                Expr right = parseXor();
                Expr l = left;
                left = ctx -> (Math.abs(l.eval(ctx)) >= 0.5 || Math.abs(right.eval(ctx)) >= 0.5) ? 1.0 : 0.0;
            }
            return left;
        }

        private Expr parseXor() {
            Expr left = parseAnd();
            while (match(TokType.KW_XOR)) {
                Expr right = parseAnd();
                Expr l = left;
                left = ctx -> ((Math.abs(l.eval(ctx)) >= 0.5) ^ (Math.abs(right.eval(ctx)) >= 0.5)) ? 1.0 : 0.0;
            }
            return left;
        }

        private Expr parseAnd() {
            Expr left = parseComparison();
            while (match(TokType.KW_AND)) {
                Expr right = parseComparison();
                Expr l = left;
                left = ctx -> (Math.abs(l.eval(ctx)) >= 0.5 && Math.abs(right.eval(ctx)) >= 0.5) ? 1.0 : 0.0;
            }
            return left;
        }

        private Expr parseComparison() {
            Expr left = parseAddSub();
            TokType t = peek().type;
            if (t == TokType.EQ || t == TokType.NEQ || t == TokType.LT || t == TokType.LTE || t == TokType.GT || t == TokType.GTE) {
                next();
                Expr right = parseAddSub();
                Expr l = left;
                return switch (t) {
                    case EQ -> ctx -> Math.abs(l.eval(ctx) - right.eval(ctx)) < 1e-6 ? 1.0 : 0.0;
                    case NEQ -> ctx -> Math.abs(l.eval(ctx) - right.eval(ctx)) >= 1e-6 ? 1.0 : 0.0;
                    case LT -> ctx -> l.eval(ctx) < right.eval(ctx) ? 1.0 : 0.0;
                    case LTE -> ctx -> l.eval(ctx) <= right.eval(ctx) ? 1.0 : 0.0;
                    case GT -> ctx -> l.eval(ctx) > right.eval(ctx) ? 1.0 : 0.0;
                    case GTE -> ctx -> l.eval(ctx) >= right.eval(ctx) ? 1.0 : 0.0;
                    default -> left;
                };
            }
            return left;
        }

        private Expr parseAddSub() {
            Expr left = parseMulDiv();
            while (peek().type == TokType.PLUS || peek().type == TokType.MINUS) {
                TokType op = next().type;
                Expr right = parseMulDiv();
                Expr l = left;
                left = (op == TokType.PLUS)
                        ? ctx -> l.eval(ctx) + right.eval(ctx)
                        : ctx -> l.eval(ctx) - right.eval(ctx);
            }
            return left;
        }

        private Expr parseMulDiv() {
            Expr left = parsePower();
            while (peek().type == TokType.STAR || peek().type == TokType.SLASH || peek().type == TokType.KW_MOD) {
                TokType op = next().type;
                Expr right = parsePower();
                Expr l = left;
                left = switch (op) {
                    case STAR -> ctx -> l.eval(ctx) * right.eval(ctx);
                    case SLASH -> ctx -> {
                        double d = right.eval(ctx);
                        return Math.abs(d) < 1e-12 ? 0.0 : l.eval(ctx) / d;
                    };
                    case KW_MOD -> ctx -> {
                        long d = Math.round(right.eval(ctx));
                        return d == 0 ? 0.0 : (double) (Math.round(l.eval(ctx)) % d);
                    };
                    default -> l;
                };
            }
            return left;
        }

        private Expr parsePower() {
            Expr left = parseUnary();
            if (match(TokType.POWER)) {
                Expr right = parseUnary();
                Expr l = left;
                return ctx -> Math.pow(l.eval(ctx), right.eval(ctx));
            }
            return left;
        }

        private Expr parseUnary() {
            if (match(TokType.MINUS)) {
                Expr e = parseUnary();
                return ctx -> -e.eval(ctx);
            }
            if (match(TokType.PLUS)) {
                return parseUnary();
            }
            if (match(TokType.KW_NOT)) {
                Expr e = parseUnary();
                return ctx -> Math.abs(e.eval(ctx)) >= 0.5 ? 0.0 : 1.0;
            }
            return parsePrimary();
        }

        private Expr parsePrimary() {
            Token t = peek();
            if (match(TokType.NUMBER) || match(TokType.KW_TRUE) || match(TokType.KW_FALSE)) {
                double v = t.numVal;
                return ctx -> v;
            }
            if (match(TokType.LPAREN)) {
                Expr inner = parseExpr();
                expect(TokType.RPAREN, "Expected ')'");
                return inner;
            }
            if (match(TokType.IDENT)) {
                String name = t.text;
                if (match(TokType.LPAREN)) {
                    List<Expr> args = new ArrayList<>();
                    if (peek().type != TokType.RPAREN) {
                        do {
                            args.add(parseExpr());
                        } while (match(TokType.COMMA));
                    }
                    expect(TokType.RPAREN, "Expected ')' after function arguments");
                    return buildFunctionCall(name, args, t.line);
                }
                return ctx -> ctx.read(name);
            }
            throw new StSyntaxException(t.line, "Expected expression (found '" + (t.text.isEmpty() ? "EOF" : t.text) + "')");
        }

        private Expr buildFunctionCall(String fnName, List<Expr> args, int line) {
            String up = fnName.toUpperCase(Locale.ROOT);
            return switch (up) {
                case "ABS" -> {
                    requireArgs(up, args, 1, line);
                    yield ctx -> Math.abs(args.get(0).eval(ctx));
                }
                case "SQRT" -> {
                    requireArgs(up, args, 1, line);
                    yield ctx -> Math.sqrt(Math.max(0.0, args.get(0).eval(ctx)));
                }
                case "SIN" -> {
                    requireArgs(up, args, 1, line);
                    yield ctx -> Math.sin(args.get(0).eval(ctx));
                }
                case "COS" -> {
                    requireArgs(up, args, 1, line);
                    yield ctx -> Math.cos(args.get(0).eval(ctx));
                }
                case "TAN" -> {
                    requireArgs(up, args, 1, line);
                    yield ctx -> Math.tan(args.get(0).eval(ctx));
                }
                case "TRUNC", "FLOAT_TO_INT", "REAL_TO_INT", "DINT_TO_INT" -> {
                    requireArgs(up, args, 1, line);
                    yield ctx -> (double) ((long) args.get(0).eval(ctx));
                }
                case "ROUND" -> {
                    requireArgs(up, args, 1, line);
                    yield ctx -> (double) Math.round(args.get(0).eval(ctx));
                }
                case "INT_TO_FLOAT", "INT_TO_REAL", "BOOL_TO_INT" -> {
                    requireArgs(up, args, 1, line);
                    yield ctx -> args.get(0).eval(ctx);
                }
                case "INT_TO_BOOL" -> {
                    requireArgs(up, args, 1, line);
                    yield ctx -> Math.abs(args.get(0).eval(ctx)) >= 0.5 ? 1.0 : 0.0;
                }
                case "MIN" -> {
                    requireArgs(up, args, 2, line);
                    yield ctx -> Math.min(args.get(0).eval(ctx), args.get(1).eval(ctx));
                }
                case "MAX" -> {
                    requireArgs(up, args, 2, line);
                    yield ctx -> Math.max(args.get(0).eval(ctx), args.get(1).eval(ctx));
                }
                case "LIMIT" -> {
                    requireArgs(up, args, 3, line);
                    yield ctx -> Math.clamp(args.get(1).eval(ctx), args.get(0).eval(ctx), args.get(2).eval(ctx));
                }
                case "CLAMP" -> {
                    requireArgs(up, args, 3, line);
                    yield ctx -> Math.clamp(args.get(0).eval(ctx), args.get(1).eval(ctx), args.get(2).eval(ctx));
                }
                case "SEL" -> {
                    requireArgs(up, args, 3, line);
                    yield ctx -> Math.abs(args.get(0).eval(ctx)) >= 0.5 ? args.get(2).eval(ctx) : args.get(1).eval(ctx);
                }
                default -> throw new StSyntaxException(line, "Unknown ST function '" + fnName + "'");
            };
        }

        private void requireArgs(String fn, List<Expr> args, int expected, int line) {
            if (args.size() != expected) {
                throw new StSyntaxException(line, "Function " + fn + " expects " + expected + " argument(s), got " + args.size());
            }
        }
    }
}
