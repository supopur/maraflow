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
    }

    private enum Tok {
        IDENT, NUMBER, ASSIGN, SEMI, COLON, COMMA, LPAREN, RPAREN,
        PLUS, MINUS, STAR, SLASH, EQ, NEQ, LT, LTE, GT, GTE,
        IF, THEN, ELSIF, ELSE, END_IF,
        CASE, OF, END_CASE,
        WHILE, DO, END_WHILE,
        AND, OR, XOR, NOT, MOD, TRUE, FALSE, EOF
    }

    private record Token(Tok type, String text) {}

    private final List<Token> tokens;
    private int pos = 0;
    private final ByteArrayOutputStream out;
    private final Map<String, Integer> varSlots;
    private final Map<String, DataType> varTypes;
    private final SlotAllocator allocator;

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
                            case "END_CASE" -> Tok.END_CASE;
                            case "WHILE" -> Tok.WHILE;
                            case "DO" -> Tok.DO;
                            case "END_WHILE" -> Tok.END_WHILE;
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
            } else if (peek().type == Tok.WHILE) {
                parseWhile();
            } else if (peek().type == Tok.IDENT) {
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

    private void parseWhile() {
        next(); // WHILE
        int loopStart = out.size();
        int condSlot = parseExpr();
        match(Tok.DO);
        int jmpExit = emitJumpPlaceholder(NativeMaraVm.OP_JMP_FALSE, condSlot);
        parseStmtList(Tok.END_WHILE);
        int jmpBack = emitJumpPlaceholder(NativeMaraVm.OP_JMP, 0);
        patchJumpTarget(jmpBack, loopStart);
        patchJumpHere(jmpExit);
        match(Tok.END_WHILE);
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
            String name = next().text.toUpperCase();
            Integer existing = varSlots.get(name);
            if (existing != null) return existing;
            int fallback = allocator.allocTempSlot(DataType.INT);
            varSlots.put(name, fallback);
            return fallback;
        }
        return 0;
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
