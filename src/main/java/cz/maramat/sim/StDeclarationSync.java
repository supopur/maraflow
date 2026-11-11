package cz.maramat.sim;

import cz.maramat.model.DataType;
import cz.maramat.model.StVariable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bi-directional synchronizer between IEC 61131-3 Structured Text (ST) declaration blocks
 * (VAR_INPUT .. END_VAR, VAR_OUTPUT .. END_VAR, VAR .. END_VAR, VAR_TEMP .. END_VAR)
 * and the GUI variable declaration table.
 */
public final class StDeclarationSync {

    private static final Pattern BLOCK_PATTERN = Pattern.compile(
            "(?i)\\b(VAR_INPUT|VAR_OUTPUT|VAR_TEMP|VAR)\\b([\\s\\S]*?)\\bEND_VAR\\b"
    );

    private static final Pattern DECL_LINE_PATTERN = Pattern.compile(
            "^\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*:\\s*([A-Za-z_][A-Za-z0-9_]*)(?:\\s*:=\\s*([^;]+))?\\s*;(?:\\s*//(.*))?$",
            Pattern.MULTILINE
    );

    public record CodeSplit(String declarationHeader, String executableBody) {}

    private StDeclarationSync() {}

    /**
     * Splits code into its declaration header (if any) and procedural body.
     */
    public static CodeSplit splitCode(String fullCode) {
        if (fullCode == null) return new CodeSplit("", "");
        Matcher m = BLOCK_PATTERN.matcher(fullCode);
        int lastEnd = 0;
        while (m.find()) {
            lastEnd = m.end();
        }
        if (lastEnd == 0) {
            return new CodeSplit("", fullCode);
        }
        String decl = fullCode.substring(0, lastEnd).trim();
        String body = fullCode.substring(lastEnd);
        if (body.startsWith("\n")) body = body.substring(1);
        if (body.startsWith("\r\n")) body = body.substring(2);
        return new CodeSplit(decl, body);
    }

    /**
     * Replaces characters inside declaration blocks (VAR .. END_VAR) with spaces,
     * preserving newlines so line numbers and column offsets in the executable body match the original code 1:1.
     */
    public static String maskDeclarations(String fullCode) {
        if (fullCode == null) return "";
        Matcher m = BLOCK_PATTERN.matcher(fullCode);
        StringBuilder sb = new StringBuilder(fullCode);
        while (m.find()) {
            for (int i = m.start(); i < m.end(); i++) {
                char c = fullCode.charAt(i);
                if (c != '\n' && c != '\r') {
                    sb.setCharAt(i, ' ');
                }
            }
        }
        return sb.toString();
    }

    /**
     * Extracts variables from ST code declaration blocks.
     * If no declaration blocks are found, returns null (meaning code has no inline declarations).
     */
    public static List<StVariable> parseDeclarationsFromCode(String fullCode) {
        if (fullCode == null || fullCode.isBlank()) return null;
        Matcher blockMatcher = BLOCK_PATTERN.matcher(fullCode);
        boolean foundAny = false;
        List<StVariable> result = new ArrayList<>();

        while (blockMatcher.find()) {
            foundAny = true;
            String scopeStr = blockMatcher.group(1).toUpperCase(Locale.ROOT);
            StVariable.Scope scope = switch (scopeStr) {
                case "VAR_INPUT" -> StVariable.Scope.VAR_INPUT;
                case "VAR_OUTPUT" -> StVariable.Scope.VAR_OUTPUT;
                case "VAR_TEMP" -> StVariable.Scope.VAR_TEMP;
                default -> StVariable.Scope.VAR;
            };

            String blockContent = blockMatcher.group(2);
            Matcher lineMatcher = DECL_LINE_PATTERN.matcher(blockContent);
            while (lineMatcher.find()) {
                String name = lineMatcher.group(1);
                String typeStr = lineMatcher.group(2).toUpperCase(Locale.ROOT);
                String initVal = lineMatcher.group(3);
                String desc = lineMatcher.group(4);

                DataType dt = parseDataType(typeStr);
                String finalInit = (initVal != null && !initVal.isBlank()) ? initVal.trim() : dt.getDefaultConstant();
                String finalDesc = (desc != null) ? desc.trim() : "";
                result.add(new StVariable(scope, name, dt, finalInit, finalDesc));
            }
        }

        return foundAny ? result : null;
    }

    /**
     * Generates standard IEC 61131-3 declaration blocks from a list of StVariables.
     */
    public static String generateDeclarationText(List<StVariable> variables) {
        if (variables == null || variables.isEmpty()) {
            return "";
        }

        Map<StVariable.Scope, List<StVariable>> grouped = new LinkedHashMap<>();
        grouped.put(StVariable.Scope.VAR_INPUT, new ArrayList<>());
        grouped.put(StVariable.Scope.VAR_OUTPUT, new ArrayList<>());
        grouped.put(StVariable.Scope.VAR, new ArrayList<>());
        grouped.put(StVariable.Scope.VAR_TEMP, new ArrayList<>());

        for (StVariable v : variables) {
            grouped.computeIfAbsent(v.getScope(), k -> new ArrayList<>()).add(v);
        }

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<StVariable.Scope, List<StVariable>> entry : grouped.entrySet()) {
            List<StVariable> list = entry.getValue();
            if (list.isEmpty()) continue;
            sb.append(entry.getKey().getKeyword()).append("\n");
            for (StVariable v : list) {
                sb.append("    ").append(v.getName()).append(" : ").append(v.getDataType().name());
                String init = v.getInitialValue();
                if (init != null && !init.isBlank() && !init.equals(v.getDataType().getDefaultConstant())) {
                    sb.append(" := ").append(init.trim());
                }
                sb.append(";");
                if (v.getDescription() != null && !v.getDescription().isBlank()) {
                    sb.append(" // ").append(v.getDescription().trim());
                }
                sb.append("\n");
            }
            sb.append("END_VAR\n\n");
        }

        return sb.toString();
    }

    /**
     * Replaces the declaration header in code with generated declarations from the table variables,
     * preserving the procedural body.
     */
    public static String replaceDeclarationsInCode(String currentFullCode, List<StVariable> variables) {
        CodeSplit split = splitCode(currentFullCode);
        String newDecl = generateDeclarationText(variables);
        String body = split.executableBody();
        if (newDecl.isEmpty()) {
            return body.stripLeading();
        }
        if (body.isBlank()) {
            return newDecl;
        }
        return newDecl + body;
    }

    private static DataType parseDataType(String raw) {
        return switch (raw) {
            case "BOOL" -> DataType.BOOL;
            case "LONG", "LINT", "DINT" -> DataType.LONG;
            case "FLOAT", "REAL", "LREAL" -> DataType.FLOAT;
            default -> DataType.INT;
        };
    }
}
