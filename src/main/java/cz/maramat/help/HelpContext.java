package cz.maramat.help;

import cz.maramat.ui.canvas.FbdCanvas;
import cz.maramat.ui.canvas.LadderCanvas;
import cz.maramat.ui.canvas.StEditorPanel;
import cz.maramat.ui.panels.LibraryTreePanel;

import java.util.Locale;
import java.util.Set;

/**
 * Resolves the currently focused IDE component and selection to a Help page ID for F1 context help.
 */
public final class HelpContext {

    private static final Set<String> ST_KEYWORDS = Set.of(
            "VAR", "VAR_INPUT", "VAR_OUTPUT", "VAR_TEMP", "END_VAR",
            "IF", "THEN", "ELSIF", "ELSEIF", "ELSE", "END_IF", "ENDIF",
            "CASE", "OF", "END_CASE", "ENDCASE",
            "FOR", "TO", "BY", "DO", "END_FOR", "ENDFOR",
            "WHILE", "END_WHILE", "ENDWHILE",
            "REPEAT", "UNTIL", "END_REPEAT", "ENDREPEAT",
            "RETURN", "EXIT", "TRUE", "FALSE", "AND", "OR", "XOR", "NOT", "MOD",
            "BOOL", "INT", "LONG", "LINT", "DINT", "FLOAT", "REAL", "LREAL"
    );

    private HelpContext() {}

    public static String resolvePageId(
            FbdCanvas fbdCanvas,
            LibraryTreePanel libraryTreePanel,
            StEditorPanel stEditorPanel,
            LadderCanvas ladderCanvas
    ) {
        if (libraryTreePanel != null && libraryTreePanel.isTreeFocused()) {
            String defId = libraryTreePanel.getSelectedBlockDefinitionId();
            if (defId != null && !defId.startsWith("Macro.")) {
                return "blocks/" + defId;
            }
        }
        if (fbdCanvas != null && fbdCanvas.hasFocus()) {
            String defId = fbdCanvas.getSelectedSingleBlockDefinitionId();
            if (defId != null && !defId.startsWith("Macro.")) {
                return "blocks/" + defId;
            }
            return "editor/fbd-canvas";
        }
        if (stEditorPanel != null && stEditorPanel.isEditorFocused()) {
            String word = stEditorPanel.getWordAtCaret();
            if (word != null && !word.isBlank()) {
                String upper = word.toUpperCase(Locale.ROOT);
                if (ST_KEYWORDS.contains(upper)) {
                    return "st/keywords#" + upper.toLowerCase(Locale.ROOT);
                }
            }
            return "st/syntax";
        }
        if (ladderCanvas != null && ladderCanvas.hasFocus()) {
            return "ld/basics";
        }
        // Fallback: if FBD has a single selected block even if focus was elsewhere
        if (fbdCanvas != null && fbdCanvas.isShowing()) {
            String defId = fbdCanvas.getSelectedSingleBlockDefinitionId();
            if (defId != null && !defId.startsWith("Macro.")) {
                return "blocks/" + defId;
            }
        }
        return null;
    }
}
