package cz.maramat.compiler;

import cz.maramat.model.DataType;
import cz.maramat.vm.NativeMaraVm;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

// Compiles IEC 61131-3 Structured Text (ST) statements into MaraVM v1 bytecode.
final class StBytecodeCompiler {

    interface SlotAllocator {
        int allocTempSlot(DataType dt);
        void addConstInit(int slotId, DataType dt, String valStr);
        int allocStateIdx();
        void emitIoReadPrefix(String name, int slotId);
        void emitIoWriteSuffix(String name, int slotId);
        int getOrAllocGlobalSlot(String name, DataType dt);
    }

    private enum Tok {
        IDENT, NUMBER, ASSIGN, SEMI, COLON, COMMA, DOT, LPAREN, RPAREN,
        PLUS, MINUS, STAR, SLASH, EQ, NEQ, LT, LTE, GT, GTE,
        IF, THEN, ELSIF, ELSE, END_IF,
        CASE, OF, END_CASE,
        FOR, TO, BY, END_FOR,
        WHILE, DO, END_WHILE,
        REPEAT, UNTIL, END_REPEAT,
        EXIT, RETURN,
        AND, OR, XOR, NOT, MOD, TRUE, FALSE, EOF
    }

    private record Token(Tok type, String text) {}

    private final List<Token> tokens;
    private int pos = 0;
    private final ByteArrayOutputStream out;
    private final Map<String, Integer> varSlots;
    private final Map<String, DataType> varTypes;
    private final SlotAllocator allocator;
    private final Map<String, Integer> fbStateIdx = new java.util.HashMap<>();
    private final Map<String, Integer> fbOutputSlots = new java.util.HashMap<>();
    private final java.util.Deque<List<Integer>> loopExitStack = new java.util.ArrayDeque<>();
    private final List<Integer> returnJumps = new ArrayList<>();

    private StBytecodeCompiler(List<Token> tokens, ByteArrayOutputStream out,
                               Map<String, Integer> varSlots, Map<String, DataType> varTypes,
                               SlotAllocator allocator) {
        this.tokens = tokens;
        this.out = out;
        this.varSlots = varSlots;
        this.varTypes = varTypes;
        this.allocator = allocator;
    }

    static void compile(String stCode, ByteArrayOutputStream out,
                        Map<String, Integer> varSlots, Map<String, DataType> varTypes,
                        SlotAllocator allocator) {
        if (stCode == null || stCode.isBlank()) return;
        String body = stripVarBlocks(stCode);
        List<Token> toks = tokenize(body);
        StBytecodeCompiler compiler = new StBytecodeCompiler(toks, out, varSlots, varTypes, allocator);
        compiler.parseStmtList(Tok.EOF);
        for (int rj : compiler.returnJumps) {
            compiler.patchJumpHere(rj);
        }
    }

    private static String stripVarBlocks(String code) {
        StringBuilder sb = new StringBuilder();
        boolean inVar = false;
        for (String line : code.split("\n")) {
            String u = line.trim().toUpperCase();
            if (u.equals("VAR") || u.startsWith("VAR_INPUT") || u.startsWith("VAR_OUTPUT") || u.startsWith("VAR_IN_OUT")) {
                inVar = true;
                continue;
            }
            if (inVar && u.equals("END_VAR")) {
                inVar = false;
                continue;
            }
            if (!inVar) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    private static List<Token> tokenize(String s) {
        List<Token> list = new ArrayList<>();
        int i = 0, n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) { i++; continue; }
            if (c == '/' && i + 1 < n && s.charAt(i + 1) == '/') {
                while (i < n && s.charAt(i) != '\n') i++;
                continue;
            }
            if (c == '(' && i + 1 < n && s.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(s.charAt(i) == '*' && s.charAt(i + 1) == ')')) i++;
                i += 2;
                continue;
            }
            if (c == ':' && i + 1 < n && s.charAt(i + 1) == '=') { list.add(new Token(Tok.ASSIGN, ":=")); i += 2; continue; }
            if (c == '<' && i + 1 < n && s.charAt(i + 1) == '>') { list.add(new Token(Tok.NEQ, "<>")); i += 2; continue; }
            if (c == '<' && i + 1 < n && s.charAt(i + 1) == '=') { list.add(new Token(Tok.LTE, "<=")); i += 2; continue; }
            if (c == '>' && i + 1 < n && s.charAt(i + 1) == '=') { list.add(new Token(Tok.GTE, ">=")); i += 2; continue; }
            switch (c) {
                case ';' -> { list.add(new Token(Tok.SEMI, ";")); i++; }
                case ':' -> { list.add(new Token(Tok.COLON, ":")); i++; }
                case ',' -> { list.add(new Token(Tok.COMMA, ",")); i++; }
                case '.' -> { list.add(new Token(Tok.DOT, ".")); i++; }
                case '(' -> { list.add(new Token(Tok.LPAREN, "(")); i++; }
                case ')' -> { list.add(new Token(Tok.RPAREN, ")")); i++; }
                case '+' -> { list.add(new Token(Tok.PLUS, "+")); i++; }
                case '-' -> { list.add(new Token(Tok.MINUS, "-")); i++; }
                case '*' -> { list.add(new Token(Tok.STAR, "*")); i++; }
                case '/' -> { list.add(new Token(Tok.SLASH, "/")); i++; }
                case '=' -> { list.add(new Token(Tok.EQ, "=")); i++; }
                case '<' -> { list.add(new Token(Tok.LT, "<")); i++; }
                case '>' -> { list.add(new Token(Tok.GT, ">")); i++; }
                default -> {
                    if (Character.isDigit(c)) {
                        int st = i;
                        while (i < n && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) i++;
                        list.add(new Token(Tok.NUMBER, s.substring(st, i)));
                    } else if (Character.isLetter(c) || c == '_') {
                        int st = i;
                        while (i < n && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_')) i++;
                        String w = s.substring(st, i);
                        Tok kw = switch (w.toUpperCase()) {
                            case "IF" -> Tok.IF;
                            case "THEN" -> Tok.THEN;
                            case "ELSIF" -> Tok.ELSIF;
                            case "ELSE" -> Tok.ELSE;
                            case "END_IF" -> Tok.END_IF;
                            case "CASE" -> Tok.CASE;
                            case "OF" -> Tok.OF;
                            case "END_CASE", "ENDCASE" -> Tok.END_CASE;
                            case "FOR" -> Tok.FOR;
                            case "TO" -> Tok.TO;
                            case "BY" -> Tok.BY;
                            case "END_FOR", "ENDFOR" -> Tok.END_FOR;
                            case "WHILE" -> Tok.WHILE;
                            case "DO" -> Tok.DO;
                            case "END_WHILE", "ENDWHILE" -> Tok.END_WHILE;
                            case "REPEAT" -> Tok.REPEAT;
                            case "UNTIL" -> Tok.UNTIL;
                            case "END_REPEAT", "ENDREPEAT" -> Tok.END_REPEAT;
                            case "EXIT" -> Tok.EXIT;
                            case "RETURN" -> Tok.RETURN;
                            case "AND" -> Tok.AND;
                            case "OR" -> Tok.OR;
                            case "XOR" -> Tok.XOR;
                            case "NOT" -> Tok.NOT;
                            case "MOD" -> Tok.MOD;
                            case "TRUE" -> Tok.TRUE;
                            case "FALSE" -> Tok.FALSE;
                            default -> Tok.IDENT;
                        };
                        list.add(new Token(kw, w));
                    } else {
                        i++;
                    }
                }
            }
        }
        list.add(new Token(Tok.EOF, ""));
        return list;
    }

    private Token peek() { return tokens.get(Math.min(pos, tokens.size() - 1)); }
    private Token next() { Token t = peek(); if (pos < tokens.size() - 1) pos++; return t; }
    private boolean match(Tok t) { if (peek().type == t) { next(); return true; } return false; }

    private void parseStmtList(Tok stop1) {
        parseStmtList(stop1, null, null);
    }

    private void parseStmtList(Tok stop1, Tok stop2, Tok stop3) {
        while (peek().type != Tok.EOF && peek().type != stop1
                && (stop2 == null || peek().type != stop2)
                && (stop3 == null || peek().type != stop3)) {
            if (match(Tok.SEMI)) continue;
            if (peek().type == Tok.IF) {
                parseIf();
            } else if (peek().type == Tok.CASE) {
                parseCase();
            } else if (peek().type == Tok.FOR) {
                parseFor();
            } else if (peek().type == Tok.WHILE) {
                parseWhile();
            } else if (peek().type == Tok.REPEAT) {
                parseRepeat();
            } else if (match(Tok.EXIT)) {
                if (!loopExitStack.isEmpty()) {
                    loopExitStack.peek().add(emitJumpPlaceholder(NativeMaraVm.OP_JMP, 0));
                }
                match(Tok.SEMI);
            } else if (match(Tok.RETURN)) {
                returnJumps.add(emitJumpPlaceholder(NativeMaraVm.OP_JMP, 0));
                match(Tok.SEMI);
            } else if (peek().type == Tok.IDENT) {
                String name = next().text;
                if (match(Tok.ASSIGN)) {
                    int valSlot = parseExpr();
                    String upper = name.toUpperCase();
                    Integer dstSlot = varSlots.get(upper);
                    if (dstSlot == null && isGlobalIoOrReg(upper)) {
                        DataType dt = upper.startsWith("DO") || upper.startsWith("DIO") ? DataType.BOOL : DataType.INT;
                        dstSlot = allocator.getOrAllocGlobalSlot(upper, dt);
                        varSlots.put(upper, dstSlot);
                    }
                    if (dstSlot != null) {
                        writeWord(NativeMaraVm.OP_MOV, 0, NativeMaraVm.SLOT_ALWAYS_ENABLED, dstSlot, valSlot);
                        allocator.emitIoWriteSuffix(upper, dstSlot);
                    }
                    match(Tok.SEMI);
                } else if (peek().type == Tok.LPAREN) {
                    parseFbCallStmt(name);
                    match(Tok.SEMI);
                }
            } else {
                next();
            }
        }
    }

    private void parseIf() {
        next(); // IF
        List<Integer> endJumps = new ArrayList<>();
        int condSlot = parseExpr();
        match(Tok.THEN);
        int jmpFalsePos = emitJumpPlaceholder(NativeMaraVm.OP_JMP_FALSE, condSlot);
        parseStmtList(Tok.ELSIF, Tok.ELSE, Tok.END_IF);

        while (match(Tok.ELSIF)) {
            endJumps.add(emitJumpPlaceholder(NativeMaraVm.OP_JMP, 0));
            patchJumpHere(jmpFalsePos);
            int c2 = parseExpr();
            match(Tok.THEN);
            jmpFalsePos = emitJumpPlaceholder(NativeMaraVm.OP_JMP_FALSE, c2);
            parseStmtList(Tok.ELSIF, Tok.ELSE, Tok.END_IF);
        }

        if (match(Tok.ELSE)) {
            endJumps.add(emitJumpPlaceholder(NativeMaraVm.OP_JMP, 0));
            patchJumpHere(jmpFalsePos);
            parseStmtList(Tok.END_IF);
        } else {
            patchJumpHere(jmpFalsePos);
        }

        match(Tok.END_IF);
        match(Tok.SEMI);
        for (int j : endJumps) patchJumpHere(j);
    }

    private void parseCase() {
        next(); // CASE
        int selSlot = parseExpr();
        match(Tok.OF);
        List<Integer> endJumps = new ArrayList<>();

        while (peek().type != Tok.ELSE && peek().type != Tok.END_CASE && peek().type != Tok.EOF) {
            if (peek().type != Tok.NUMBER && peek().type != Tok.MINUS) { next(); continue; }
            String numText = match(Tok.MINUS) ? ("-" + next().text) : next().text;
            match(Tok.COLON);

            int litSlot = allocator.allocTempSlot(DataType.INT);
            allocator.addConstInit(litSlot, DataType.INT, numText);
            int eqSlot = allocator.allocTempSlot(DataType.BOOL);
            writeWord(NativeMaraVm.OP_CMP_EQ, 0, NativeMaraVm.SLOT_ALWAYS_ENABLED, eqSlot, selSlot);
            writeU16(litSlot);
            writeU16(0);

            int jmpNextCase = emitJumpPlaceholder(NativeMaraVm.OP_JMP_FALSE, eqSlot);
            // Parse statements until next integer case label, ELSE, or END_CASE
            while (peek().type != Tok.ELSE && peek().type != Tok.END_CASE && peek().type != Tok.EOF) {
                if (peek().type == Tok.NUMBER && (pos + 1 < tokens.size()) && tokens.get(pos + 1).type == Tok.COLON) {
                    break;
                }
                if (peek().type == Tok.IF) parseIf();
                else if (peek().type == Tok.CASE) parseCase();
                else if (peek().type == Tok.WHILE) parseWhile();
                else if (peek().type == Tok.IDENT) {
                    String name = next().text;
                    if (match(Tok.ASSIGN)) {
                        int valSlot = parseExpr();
                        Integer dstSlot = varSlots.get(name.toUpperCase());
                        if (dstSlot != null) {
                            writeWord(NativeMaraVm.OP_MOV, 0, NativeMaraVm.SLOT_ALWAYS_ENABLED, dstSlot, valSlot);
                        }
                        match(Tok.SEMI);
                    }
                } else {
                    next();
                }
            }
            endJumps.add(emitJumpPlaceholder(NativeMaraVm.OP_JMP, 0));
            patchJumpHere(jmpNextCase);
        }

        if (match(Tok.ELSE)) {
            parseStmtList(Tok.END_CASE);
        }
        match(Tok.END_CASE);
        match(Tok.SEMI);
        for (int j : endJumps) patchJumpHere(j);
    }

    private void parseFor() {
        next(); // FOR
        String varName = (peek().type == Tok.IDENT) ? next().text.toUpperCase() : "I";
        Integer varSlot = varSlots.get(varName);
        if (varSlot == null) {
            varSlot = allocator.allocTempSlot(DataType.INT);
            varSlots.put(varName, varSlot);
        }
        match(Tok.ASSIGN);
        int startSlot = parseExpr();
        writeWord(NativeMaraVm.OP_MOV, 0, NativeMaraVm.SLOT_ALWAYS_ENABLED, varSlot, startSlot);
        match(Tok.TO);
        int endSlot = parseExpr();
        int stepSlot;
        if (match(Tok.BY)) {
            stepSlot = parseExpr();
        } else {
            stepSlot = allocator.allocTempSlot(DataType.INT);
            allocator.addConstInit(stepSlot, DataType.INT, "1");
        }
        match(Tok.DO);

        int loopStart = out.size();
        int condSlot = allocator.allocTempSlot(DataType.BOOL);
        writeWord(NativeMaraVm.OP_CMP_LE, 0, NativeMaraVm.SLOT_ALWAYS_ENABLED, condSlot, varSlot);
        writeU16(endSlot);
        writeU16(0);
        int jmpExit = emitJumpPlaceholder(NativeMaraVm.OP_JMP_FALSE, condSlot);

        List<Integer> exits = new ArrayList<>();
        loopExitStack.push(exits);
        parseStmtList(Tok.END_FOR);
        loopExitStack.pop();

        int nextVal = allocator.allocTempSlot(DataType.INT);
        writeWord(NativeMaraVm.OP_ADD, 0, NativeMaraVm.SLOT_ALWAYS_ENABLED, nextVal, varSlot);
        writeU16(stepSlot);
        writeU16(0);
        writeWord(NativeMaraVm.OP_MOV, 0, NativeMaraVm.SLOT_ALWAYS_ENABLED, varSlot, nextVal);

        int jmpBack = emitJumpPlaceholder(NativeMaraVm.OP_JMP, 0);
        patchJumpTarget(jmpBack, loopStart);
        patchJumpHere(jmpExit);
        for (int ex : exits) patchJumpHere(ex);
        match(Tok.END_FOR);
        match(Tok.SEMI);
    }

    private void parseWhile() {
        next(); // WHILE
        int loopStart = out.size();
        int condSlot = parseExpr();
        match(Tok.DO);
        int jmpExit = emitJumpPlaceholder(NativeMaraVm.OP_JMP_FALSE, condSlot);
        List<Integer> exits = new ArrayList<>();
        loopExitStack.push(exits);
        parseStmtList(Tok.END_WHILE);
        loopExitStack.pop();
        int jmpBack = emitJumpPlaceholder(NativeMaraVm.OP_JMP, 0);
        patchJumpTarget(jmpBack, loopStart);
        patchJumpHere(jmpExit);
        for (int ex : exits) patchJumpHere(ex);
        match(Tok.END_WHILE);
        match(Tok.SEMI);
    }

    private void parseRepeat() {
        next(); // REPEAT
        int loopStart = out.size();
        List<Integer> exits = new ArrayList<>();
        loopExitStack.push(exits);
        parseStmtList(Tok.UNTIL);
        loopExitStack.pop();
        match(Tok.UNTIL);
        int condSlot = parseExpr();
        int jmpBack = emitJumpPlaceholder(NativeMaraVm.OP_JMP_FALSE, condSlot);
        patchJumpTarget(jmpBack, loopStart);
        for (int ex : exits) patchJumpHere(ex);
        match(Tok.END_REPEAT);
        match(Tok.SEMI);
    }

    private int parseExpr() { return parseOr(); }

    private int parseOr() {
        int left = parseXor();
        while (match(Tok.OR)) {
            int right = parseXor();
            int dst = allocator.allocTempSlot(DataType.BOOL);
            writeWord(NativeMaraVm.OP_OR, 2, NativeMaraVm.SLOT_ALWAYS_ENABLED, dst, left);
            writeU16(right);
            writeU16(0);
            left = dst;
        }
        return left;
    }

    private int parseXor() {
        int left = parseAnd();
        while (match(Tok.XOR)) {
            int right = parseAnd();
            int dst = allocator.allocTempSlot(DataType.BOOL);
            writeWord(NativeMaraVm.OP_XOR, 2, NativeMaraVm.SLOT_ALWAYS_ENABLED, dst, left);
            writeU16(right);
            writeU16(0);
            left = dst;
        }
        return left;
    }

    private int parseAnd() {
        int left = parseCmp();
        while (match(Tok.AND)) {
            int right = parseCmp();
            int dst = allocator.allocTempSlot(DataType.BOOL);
            writeWord(NativeMaraVm.OP_AND, 2, NativeMaraVm.SLOT_ALWAYS_ENABLED, dst, left);
            writeU16(right);
            writeU16(0);
            left = dst;
        }
        return left;
    }

    private int parseCmp() {
        int left = parseAdd();
        while (peek().type == Tok.EQ || peek().type == Tok.NEQ || peek().type == Tok.LT
                || peek().type == Tok.LTE || peek().type == Tok.GT || peek().type == Tok.GTE) {
            Tok op = next().type;
            int right = parseAdd();
            int vmOp = switch (op) {
                case EQ -> NativeMaraVm.OP_CMP_EQ;
                case NEQ -> NativeMaraVm.OP_CMP_NE;
                case LT -> NativeMaraVm.OP_CMP_LT;
                case LTE -> NativeMaraVm.OP_CMP_LE;
                case GT -> NativeMaraVm.OP_CMP_GT;
                default -> NativeMaraVm.OP_CMP_GE;
            };
            int dst = allocator.allocTempSlot(DataType.BOOL);
            writeWord(vmOp, 0, NativeMaraVm.SLOT_ALWAYS_ENABLED, dst, left);
            writeU16(right);
            writeU16(0);
            left = dst;
        }
        return left;
    }

    private int parseAdd() {
        int left = parseMul();
        while (peek().type == Tok.PLUS || peek().type == Tok.MINUS) {
            Tok op = next().type;
            int right = parseMul();
            DataType dt = isFloatSlot(left) || isFloatSlot(right) ? DataType.FLOAT : DataType.INT;
            int dst = allocator.allocTempSlot(dt);
            writeWord(op == Tok.PLUS ? NativeMaraVm.OP_ADD : NativeMaraVm.OP_SUB, 0, NativeMaraVm.SLOT_ALWAYS_ENABLED, dst, left);
            writeU16(right);
            writeU16(0);
            left = dst;
        }
        return left;
    }

    private int parseMul() {
        int left = parseUnary();
        while (peek().type == Tok.STAR || peek().type == Tok.SLASH || peek().type == Tok.MOD) {
            Tok op = next().type;
            int right = parseUnary();
            DataType dt = isFloatSlot(left) || isFloatSlot(right) ? DataType.FLOAT : DataType.INT;
            int dst = allocator.allocTempSlot(dt);
            if (op == Tok.STAR) {
                writeWord(NativeMaraVm.OP_MUL, 0, NativeMaraVm.SLOT_ALWAYS_ENABLED, dst, left);
                writeU16(right);
                writeU16(0);
            } else {
                writeWord(op == Tok.SLASH ? NativeMaraVm.OP_DIV : NativeMaraVm.OP_MOD, 0, NativeMaraVm.SLOT_ALWAYS_ENABLED, dst, left);
                writeU16(right);
                writeU16(NativeMaraVm.SLOT_ALWAYS_ENABLED);
            }
            left = dst;
        }
        return left;
    }

    private int parseUnary() {
        if (match(Tok.NOT)) {
            int sub = parseUnary();
            int dst = allocator.allocTempSlot(DataType.BOOL);
            writeWord(NativeMaraVm.OP_NOT, 0, NativeMaraVm.SLOT_ALWAYS_ENABLED, dst, sub);
            return dst;
        }
        if (match(Tok.MINUS)) {
            int sub = parseUnary();
            int dst = allocator.allocTempSlot(isFloatSlot(sub) ? DataType.FLOAT : DataType.INT);
            writeWord(NativeMaraVm.OP_NEG, 0, NativeMaraVm.SLOT_ALWAYS_ENABLED, dst, sub);
            return dst;
        }
        return parsePrimary();
    }

    private int parsePrimary() {
        if (match(Tok.LPAREN)) {
            int e = parseExpr();
            match(Tok.RPAREN);
            return e;
        }
        if (match(Tok.TRUE)) {
            int s = allocator.allocTempSlot(DataType.BOOL);
            allocator.addConstInit(s, DataType.BOOL, "1");
            return s;
        }
        if (match(Tok.FALSE)) {
            int s = allocator.allocTempSlot(DataType.BOOL);
            allocator.addConstInit(s, DataType.BOOL, "0");
            return s;
        }
        if (peek().type == Tok.NUMBER) {
            String txt = next().text;
            DataType dt = txt.contains(".") ? DataType.FLOAT : DataType.INT;
            int s = allocator.allocTempSlot(dt);
            allocator.addConstInit(s, dt, txt);
            return s;
        }
        if (peek().type == Tok.IDENT) {
            String rawName = next().text;
            String name = rawName.toUpperCase();
            if (match(Tok.DOT)) {
                String member = (peek().type == Tok.IDENT) ? next().text.toUpperCase() : "Q";
                Integer fbOut = fbOutputSlots.get(name + "." + member);
                if (fbOut != null) return fbOut;
                Integer fallback = allocator.allocTempSlot(DataType.INT);
                fbOutputSlots.put(name + "." + member, fallback);
                return fallback;
            }
            if (peek().type == Tok.LPAREN) {
                return parseBuiltinFuncCall(name);
            }
            Integer existing = varSlots.get(name);
            if (existing != null) {
                allocator.emitIoReadPrefix(name, existing);
                return existing;
            }
            if (isGlobalIoOrReg(name)) {
                DataType dt = (name.startsWith("DI") || name.startsWith("DO") || name.startsWith("DIO"))
                        ? DataType.BOOL : DataType.INT;
                int slot = allocator.getOrAllocGlobalSlot(name, dt);
                varSlots.put(name, slot);
                allocator.emitIoReadPrefix(name, slot);
                return slot;
            }
            int fallback = allocator.allocTempSlot(DataType.INT);
            varSlots.put(name, fallback);
            return fallback;
        }
        return 0;
    }

    private static boolean isGlobalIoOrReg(String upper) {
        return upper.matches("^(DI|DO|DIO|AI|AO|PWM)\\d+$") || upper.startsWith("REG_");
    }

    private record CallArgs(Map<String, Integer> named, List<Integer> positional) {}

    private CallArgs parseCallArgs() {
        match(Tok.LPAREN);
        Map<String, Integer> named = new java.util.LinkedHashMap<>();
        List<Integer> posArgs = new ArrayList<>();
        while (peek().type != Tok.RPAREN && peek().type != Tok.EOF) {
            if (peek().type == Tok.IDENT && (pos + 1 < tokens.size()) && tokens.get(pos + 1).type == Tok.ASSIGN) {
                String argName = next().text.toUpperCase();
                next(); // :=
                int val = parseExpr();
                named.put(argName, val);
            } else {
                posArgs.add(parseExpr());
            }
            if (!match(Tok.COMMA)) break;
        }
        match(Tok.RPAREN);
        return new CallArgs(named, posArgs);
    }

    private int argAt(CallArgs args, String namedKey, int posIdx, DataType defType, String defVal) {
        Integer v = args.named.get(namedKey.toUpperCase());
        if (v != null) return v;
        if (posIdx >= 0 && posIdx < args.positional.size()) return args.positional.get(posIdx);
        int s = allocator.allocTempSlot(defType);
        allocator.addConstInit(s, defType, defVal);
        return s;
    }

    private int parseBuiltinFuncCall(String fn) {
        CallArgs args = parseCallArgs();
        List<Integer> p = args.positional;
        return switch (fn) {
            case "ABS", "SQRT", "SIN", "COS" -> {
                int a = argAt(args, "X", 0, DataType.FLOAT, "0");
                DataType dt = "ABS".equals(fn) && !isFloatSlot(a) ? DataType.INT : DataType.FLOAT;
                int dst = allocator.allocTempSlot(dt);
                int op = switch (fn) {
                    case "ABS" -> NativeMaraVm.OP_ABS;
                    case "SQRT" -> NativeMaraVm.OP_SQRT;
                    case "SIN" -> NativeMaraVm.OP_SIN;
                    default -> NativeMaraVm.OP_COS;
                };
                writeWord(op, 0, NativeMaraVm.SLOT_ALWAYS_ENABLED, dst, a);
                yield dst;
            }
            case "MIN", "MAX" -> {
                int op = "MIN".equals(fn) ? NativeMaraVm.OP_MIN : NativeMaraVm.OP_MAX;
                int acc = p.isEmpty() ? argAt(args, "X1", 0, DataType.INT, "0") : p.get(0);
                for (int i = 1; i < p.size(); i++) {
                    int rhs = p.get(i);
                    DataType dt = (isFloatSlot(acc) || isFloatSlot(rhs)) ? DataType.FLOAT : DataType.INT;
                    int dst = allocator.allocTempSlot(dt);
                    writeWord(op, 0, NativeMaraVm.SLOT_ALWAYS_ENABLED, dst, acc);
                    writeU16(rhs);
                    writeU16(0);
                    acc = dst;
                }
                yield acc;
            }
            case "LIMIT", "CLAMP" -> {
                int inS = argAt(args, "IN", 0, DataType.FLOAT, "0");
                int minS = argAt(args, "MN", 1, DataType.FLOAT, "0");
                int maxS = argAt(args, "MX", 2, DataType.FLOAT, "100");
                int dst = allocator.allocTempSlot(DataType.FLOAT);
                writeWord(NativeMaraVm.OP_CLAMP, 0, NativeMaraVm.SLOT_ALWAYS_ENABLED, dst, inS);
                writeU16(minS);
                writeU16(maxS);
                yield dst;
            }
            case "SEL" -> {
                int g = argAt(args, "G", 0, DataType.BOOL, "0");
                int x0 = argAt(args, "IN0", 1, DataType.FLOAT, "0");
                int x1 = argAt(args, "IN1", 2, DataType.FLOAT, "0");
                int dst = allocator.allocTempSlot(isFloatSlot(x0) || isFloatSlot(x1) ? DataType.FLOAT : DataType.INT);
                writeWord(NativeMaraVm.OP_SEL, 0, NativeMaraVm.SLOT_ALWAYS_ENABLED, dst, g);
                writeU16(x0);
                writeU16(x1);
                yield dst;
            }
            case "MUX" -> {
                int sel = argAt(args, "K", 0, DataType.INT, "0");
                int dst = allocator.allocTempSlot(DataType.INT);
                for (int i = 1; i < p.size(); i++) {
                    int idxSlot = allocator.allocTempSlot(DataType.INT);
                    allocator.addConstInit(idxSlot, DataType.INT, Integer.toString(i - 1));
                    int eqSlot = allocator.allocTempSlot(DataType.BOOL);
                    writeWord(NativeMaraVm.OP_CMP_EQ, 0, NativeMaraVm.SLOT_ALWAYS_ENABLED, eqSlot, sel);
                    writeU16(idxSlot);
                    writeU16(0);
                    writeWord(NativeMaraVm.OP_MOV, 0, eqSlot, dst, p.get(i));
                }
                yield dst;
            }
            case "SINGLEPULSE", "ONESHOT", "R_TRIG" -> {
                int clk = argAt(args, "CLK", 0, DataType.BOOL, "0");
                int dst = allocator.allocTempSlot(DataType.BOOL);
                int stIdx = allocator.allocStateIdx();
                writeWord(NativeMaraVm.OP_ONE_SHOT, 0, NativeMaraVm.SLOT_ALWAYS_ENABLED, dst, clk);
                writeU16(stIdx);
                writeU16(0);
                yield dst;
            }
            case "RANDOM", "RND" -> {
                int trig, minS, maxS;
                if (p.size() >= 3) {
                    trig = p.get(0);
                    minS = p.get(1);
                    maxS = p.get(2);
                } else {
                    trig = allocator.allocTempSlot(DataType.BOOL);
                    allocator.addConstInit(trig, DataType.BOOL, "1");
                    minS = argAt(args, "MIN", 0, DataType.INT, "0");
                    maxS = argAt(args, "MAX", 1, DataType.INT, "100");
                }
                int dst = allocator.allocTempSlot(DataType.INT);
                int stIdx = allocator.allocStateIdx();
                writeWord(NativeMaraVm.OP_RANDOM, 0, NativeMaraVm.SLOT_ALWAYS_ENABLED, dst, trig);
                writeU16(minS);
                writeU16(maxS);
                writeU16(stIdx);
                writeU16(0);
                yield dst;
            }
            case "TRUNC", "ROUND", "FLOAT_TO_INT", "REAL_TO_INT", "DINT_TO_INT" -> {
                int a = argAt(args, "IN", 0, DataType.FLOAT, "0");
                int dst = allocator.allocTempSlot(DataType.INT);
                writeWord(NativeMaraVm.OP_CAST, 0, NativeMaraVm.SLOT_ALWAYS_ENABLED, dst, a);
                yield dst;
            }
            case "INT_TO_FLOAT", "INT_TO_REAL" -> {
                int a = argAt(args, "IN", 0, DataType.INT, "0");
                int dst = allocator.allocTempSlot(DataType.FLOAT);
                writeWord(NativeMaraVm.OP_CAST, 0, NativeMaraVm.SLOT_ALWAYS_ENABLED, dst, a);
                yield dst;
            }
            case "BOOL_TO_INT" -> {
                int a = argAt(args, "IN", 0, DataType.BOOL, "0");
                int dst = allocator.allocTempSlot(DataType.INT);
                writeWord(NativeMaraVm.OP_CAST, 0, NativeMaraVm.SLOT_ALWAYS_ENABLED, dst, a);
                yield dst;
            }
            case "INT_TO_BOOL" -> {
                int a = argAt(args, "IN", 0, DataType.INT, "0");
                int dst = allocator.allocTempSlot(DataType.BOOL);
                writeWord(NativeMaraVm.OP_CAST, 0, NativeMaraVm.SLOT_ALWAYS_ENABLED, dst, a);
                yield dst;
            }
            default -> {
                parseFbCallByKind(fn, fn, args);
                Integer qOut = fbOutputSlots.get(fn + ".Q");
                yield (qOut != null) ? qOut : 0;
            }
        };
    }

    private void parseFbCallStmt(String instName) {
        CallArgs args = parseCallArgs();
        String upper = instName.toUpperCase();
        String kind = upper;
        if (upper.startsWith("TON")) kind = "TON";
        else if (upper.startsWith("TOF")) kind = "TOF";
        else if (upper.startsWith("TP")) kind = "TP";
        else if (upper.startsWith("CTU") && !upper.startsWith("CTUD")) kind = "CTU";
        else if (upper.startsWith("CTD")) kind = "CTD";
        else if (upper.startsWith("CTUD")) kind = "CTUD";
        else if (upper.startsWith("SR")) kind = "SR";
        else if (upper.startsWith("RS")) kind = "RS";
        else if (upper.startsWith("R_TRIG")) kind = "R_TRIG";
        else if (upper.startsWith("F_TRIG")) kind = "F_TRIG";
        parseFbCallByKind(upper, kind, args);
    }

    private void parseFbCallByKind(String instUpper, String kind, CallArgs args) {
        int stIdx = fbStateIdx.computeIfAbsent(instUpper, k -> allocator.allocStateIdx());
        switch (kind) {
            case "TON", "TOF", "TP", "TIMER" -> {
                int inS = argAt(args, "IN", 0, DataType.BOOL, "0");
                int ptS = argAt(args, "PT", 1, DataType.INT, "1000");
                int qS = fbOutputSlots.computeIfAbsent(instUpper + ".Q", k -> allocator.allocTempSlot(DataType.BOOL));
                int etS = fbOutputSlots.computeIfAbsent(instUpper + ".ET", k -> allocator.allocTempSlot(DataType.INT));
                int mode = "TOF".equals(kind) ? 1 : ("TP".equals(kind) ? 2 : 0);
                writeWord(NativeMaraVm.OP_TON, mode, NativeMaraVm.SLOT_ALWAYS_ENABLED, qS, inS);
                writeU16(ptS);
                writeU16(etS);
                writeU16(stIdx);
                writeU16(0);
            }
            case "CTU", "CTD", "CTUD", "COUNTER" -> {
                int cuS = argAt(args, "CU", 0, DataType.BOOL, "0");
                int cdS = argAt(args, "CD", 1, DataType.BOOL, "0");
                int rS = argAt(args, "RESET", -1, DataType.BOOL, "0");
                if (args.named.containsKey("R")) rS = args.named.get("R");
                int pvS = argAt(args, "PV", 2, DataType.INT, "10");
                int qS = fbOutputSlots.computeIfAbsent(instUpper + ".Q", k -> allocator.allocTempSlot(DataType.BOOL));
                int cvS = fbOutputSlots.computeIfAbsent(instUpper + ".CV", k -> allocator.allocTempSlot(DataType.INT));
                writeWord(NativeMaraVm.OP_CTUD, 0, NativeMaraVm.SLOT_ALWAYS_ENABLED, qS, cuS);
                writeU16(cdS);
                writeU16(rS);
                writeU16(pvS);
                writeU16(cvS);
                writeU16(stIdx);
                writeU16(0);
            }
            case "SR", "RS" -> {
                int sS = argAt(args, "S", 0, DataType.BOOL, "0");
                if (args.named.containsKey("S1")) sS = args.named.get("S1");
                int rS = argAt(args, "R", 1, DataType.BOOL, "0");
                if (args.named.containsKey("R1")) rS = args.named.get("R1");
                int qS = fbOutputSlots.computeIfAbsent(instUpper + ".Q", k -> allocator.allocTempSlot(DataType.BOOL));
                int nqS = fbOutputSlots.computeIfAbsent(instUpper + ".NQ", k -> allocator.allocTempSlot(DataType.BOOL));
                int op = "RS".equals(kind) ? NativeMaraVm.OP_RS : NativeMaraVm.OP_SR;
                writeWord(op, 0, NativeMaraVm.SLOT_ALWAYS_ENABLED, qS, sS);
                writeU16(rS);
                writeU16(nqS);
            }
            case "R_TRIG", "F_TRIG" -> {
                int clk = argAt(args, "CLK", 0, DataType.BOOL, "0");
                int qS = fbOutputSlots.computeIfAbsent(instUpper + ".Q", k -> allocator.allocTempSlot(DataType.BOOL));
                if ("F_TRIG".equals(kind)) {
                    int inv = allocator.allocTempSlot(DataType.BOOL);
                    writeWord(NativeMaraVm.OP_NOT, 0, NativeMaraVm.SLOT_ALWAYS_ENABLED, inv, clk);
                    clk = inv;
                }
                writeWord(NativeMaraVm.OP_ONE_SHOT, 0, NativeMaraVm.SLOT_ALWAYS_ENABLED, qS, clk);
                writeU16(stIdx);
                writeU16(0);
            }
            default -> {}
        }
    }

    private static boolean isFloatSlot(int slotId) {
        return ((slotId >>> NativeMaraVm.SLOT_TYPE_SHIFT) & 0x3) == NativeMaraVm.TYPE_FLOAT;
    }

    private int emitJumpPlaceholder(int opcode, int condSlot) {
        int off = out.size();
        writeWord(opcode, 0, NativeMaraVm.SLOT_ALWAYS_ENABLED, condSlot, 0);
        return off;
    }

    private void patchJumpHere(int jmpInstrOffset) {
        patchJumpTarget(jmpInstrOffset, out.size());
    }

    private void patchJumpTarget(int jmpInstrOffset, int targetOffset) {
        int rel = targetOffset - jmpInstrOffset;
        byte[] arr = out.toByteArray();
        arr[jmpInstrOffset + 6] = (byte) (rel & 0xFF);
        arr[jmpInstrOffset + 7] = (byte) ((rel >>> 8) & 0xFF);
        out.reset();
        out.write(arr, 0, arr.length);
    }

    private void writeWord(int op, int flags, int enSlot, int dst, int srcA) {
        out.write(op & 0xFF);
        out.write(flags & 0xFF);
        writeU16(enSlot);
        writeU16(dst);
        writeU16(srcA);
    }

    private void writeU16(int val) {
        out.write(val & 0xFF);
        out.write((val >>> 8) & 0xFF);
    }
}
