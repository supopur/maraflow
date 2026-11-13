package cz.maramat.help;

import cz.maramat.model.BlockCatalog;
import cz.maramat.model.BlockDefinition;
import cz.maramat.model.BlockDefinition.PinSpec;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * Generates block reference pages from {@link BlockCatalog} and merges optional
 * per-block markdown overrides ({@code /help/<lang>/blocks/<BlockId>.md}).
 */
public final class BlockHelpGenerator {

    private BlockHelpGenerator() {}

    public static HelpPage generate(String blockId, String locale, HelpContentManager contentManager) {
        BlockDefinition def = BlockCatalog.getById(blockId);
        if (def == null) {
            return null;
        }

        boolean cs = "cs".equals(locale);
        Properties summariesEn = loadProperties("/help/en/blocks/_summaries.properties");
        Properties summariesLoc = cs ? loadProperties("/help/cs/blocks/_summaries.properties") : summariesEn;
        Properties pinsLoc = cs ? loadProperties("/help/cs/blocks/_pins.properties") : new Properties();

        String summary = summariesLoc.getProperty(blockId);
        boolean summaryFallback = false;
        if (summary == null || summary.isBlank()) {
            summary = summariesEn.getProperty(blockId, "");
            if (cs && !summary.isBlank()) {
                summaryFallback = true;
            }
        }

        String autoImage = buildAutoImage(def);
        String autoPins = buildAutoPins(def, cs, pinsLoc);
        String autoFacts = buildAutoFacts(def, cs);

        // Check for custom markdown override: locale first, then en fallback
        HelpContentManager.RawMarkdownResult override = contentManager.loadRawBlockOverride(blockId, locale);
        if (override != null) {
            HelpContentManager.ParsedFrontMatter fm = contentManager.parseFrontMatter(override.markdown());
            String body = fm.body();
            boolean hasAnyPlaceholder = body.contains("{{auto:image}}")
                    || body.contains("{{auto:summary}}")
                    || body.contains("{{auto:pins}}")
                    || body.contains("{{auto:facts}}");

            if (!hasAnyPlaceholder && fm.showImage()) {
                body = autoImage + "\n\n" + body;
            } else {
                body = body
                        .replace("{{auto:image}}", autoImage)
                        .replace("{{auto:summary}}", summary)
                        .replace("{{auto:pins}}", autoPins)
                        .replace("{{auto:facts}}", autoFacts);
            }

            String title = fm.title() != null ? fm.title() : def.getTypeName();
            String fullMd = "# " + title + "\n\n" + body;
            return new HelpPage(
                    "blocks/" + blockId,
                    title,
                    fm.tags().isEmpty() ? List.of(def.getTypeName(), def.getCategoryPath()) : fm.tags(),
                    fm.level() != null ? fm.level() : "reference",
                    fm.seeAlso(),
                    fullMd,
                    override.locale(),
                    override.fallback()
            );
        }

        // Purely auto-generated page
        StringBuilder md = new StringBuilder();
        md.append("# ").append(def.getTypeName()).append("\n\n");
        md.append("**").append(cs ? "Kategorie" : "Category").append(":** `")
                .append(def.getCategoryPath()).append("`  |  ")
                .append("**ID:** `").append(def.getId()).append("`\n\n");
        md.append(autoImage).append("\n\n");
        if (!summary.isBlank()) {
            md.append(summary.trim()).append("\n\n");
        }
        md.append(autoPins).append("\n");
        md.append(autoFacts).append("\n");

        return new HelpPage(
                "blocks/" + blockId,
                def.getTypeName(),
                List.of(def.getTypeName(), def.getCategoryPath(), def.getId()),
                "reference",
                List.of(),
                md.toString(),
                locale,
                summaryFallback
        );
    }

    private static String buildAutoImage(BlockDefinition def) {
        return "```fbd\nblock b " + def.getId() + " at 3,1\n```";
    }

    private static String buildAutoPins(BlockDefinition def, boolean cs, Properties pinsLoc) {
        StringBuilder sb = new StringBuilder();
        sb.append("## ").append(cs ? "Vstupy" : "Inputs").append("\n\n");
        if (def.getDefaultInputs().isEmpty()) {
            sb.append(cs ? "_Žádné vstupy._" : "_No inputs._").append("\n\n");
        } else {
            sb.append(cs
                    ? "| Pin | Název | Typ | Výchozí |\n|---|---|---|---|\n"
                    : "| Pin | Name | Type | Default |\n|---|---|---|---|\n");
            for (PinSpec p : def.getDefaultInputs()) {
                String fullName = pinsLoc.getProperty(def.getId() + "." + p.shortName(),
                        pinsLoc.getProperty(p.shortName(), p.fullName()));
                sb.append("| `").append(p.shortName()).append("` | ")
                        .append(escapePipe(fullName)).append(" | `")
                        .append(p.dataType().name()).append("` | `")
                        .append(p.defaultConstant()).append("` |\n");
            }
            sb.append("\n");
        }

        sb.append("## ").append(cs ? "Výstupy" : "Outputs").append("\n\n");
        if (def.getDefaultOutputs().isEmpty()) {
            sb.append(cs ? "_Žádné výstupy._" : "_No outputs._").append("\n\n");
        } else {
            sb.append(cs
                    ? "| Pin | Název | Typ |\n|---|---|---|\n"
                    : "| Pin | Name | Type |\n|---|---|---|\n");
            for (PinSpec p : def.getDefaultOutputs()) {
                String fullName = pinsLoc.getProperty(def.getId() + "." + p.shortName(),
                        pinsLoc.getProperty(p.shortName(), p.fullName()));
                sb.append("| `").append(p.shortName()).append("` | ")
                        .append(escapePipe(fullName)).append(" | `")
                        .append(p.dataType().name()).append("` |\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private static String buildAutoFacts(BlockDefinition def, boolean cs) {
        StringBuilder sb = new StringBuilder();
        sb.append("## ").append(cs ? "Vlastnosti" : "Properties").append("\n\n");
        sb.append(cs ? "| Vlastnost | Hodnota |\n|---|---|\n" : "| Property | Value |\n|---|---|\n");
        sb.append("| ").append(cs ? "Kategorie" : "Category").append(" | `").append(def.getCategoryPath()).append("` |\n");
        sb.append("| ").append(cs ? "Proměnlivý počet vstupů" : "Variable input count").append(" | ")
                .append(yesNo(def.isVariableInputCount(), cs)).append(" |\n");
        sb.append("| ").append(cs ? "Nastavitelný datový typ" : "Configurable data type").append(" | ")
                .append(yesNo(def.isConfigurableDataType(), cs)).append(" |\n");
        sb.append("| ").append(cs ? "Odhadovaná doba vykonání" : "Estimated execution cost").append(" | ")
                .append(String.format(Locale.US, "%.2f us", def.getEstimatedCostUs())).append(" |\n");
        sb.append("| ").append(cs ? "Velikost mřížky (šířka x min. výška)" : "Grid size (width x min body height)").append(" | ")
                .append(def.getCourtyardGridWidth()).append(" x ").append(def.getMinBodyGridHeight()).append(" |\n");
        return sb.toString();
    }

    private static String yesNo(boolean val, boolean cs) {
        if (cs) return val ? "Ano" : "Ne";
        return val ? "Yes" : "No";
    }

    private static String escapePipe(String s) {
        return s == null ? "" : s.replace("|", "\\|");
    }

    private static Properties loadProperties(String resourcePath) {
        Properties props = new Properties();
        try (InputStream is = BlockHelpGenerator.class.getResourceAsStream(resourcePath)) {
            if (is != null) {
                props.load(new InputStreamReader(is, StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
        }
        return props;
    }
}
