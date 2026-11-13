package cz.maramat.help;

import cz.maramat.model.BlockCatalog;
import cz.maramat.model.BlockDefinition;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads the Help table of contents from {@code /help/toc.yml} and dynamically populates
 * the {@code blocks} section from {@link BlockCatalog#getAllDefinitions()}.
 */
public final class HelpToc {

    public static final class TocNode {
        private final String pageId; // null for pure category folders
        private final String label;
        private final List<TocNode> children = new ArrayList<>();

        public TocNode(String pageId, String label) {
            this.pageId = pageId;
            this.label = label;
        }

        public String getPageId() {
            return pageId;
        }

        public String getLabel() {
            return label;
        }

        public List<TocNode> getChildren() {
            return children;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private HelpToc() {}

    public static TocNode load(String locale, HelpContentManager contentManager) {
        TocNode root = new TocNode(null, "Help");
        try (InputStream is = HelpToc.class.getResourceAsStream("/help/toc.yml")) {
            if (is == null) {
                return root;
            }
            List<String> lines = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))
                    .lines().toList();

            String currentSection = null;
            String titleEn = null;
            String titleCs = null;
            boolean isBlocksAuto = false;
            List<String> items = new ArrayList<>();
            boolean inItems = false;

            for (String raw : lines) {
                String line = raw.replaceFirst("#.*$", "").stripTrailing();
                if (line.isBlank()) continue;
                String trimmed = line.trim();

                if (trimmed.startsWith("- section:")) {
                    if (currentSection != null) {
                        root.getChildren().add(buildSectionNode(
                                titleEn, titleCs, isBlocksAuto, items, locale, contentManager
                        ));
                    }
                    currentSection = trimmed.substring("- section:".length()).trim();
                    titleEn = currentSection;
                    titleCs = currentSection;
                    isBlocksAuto = false;
                    items = new ArrayList<>();
                    inItems = false;
                } else if (trimmed.startsWith("title_en:")) {
                    titleEn = trimmed.substring("title_en:".length()).trim();
                } else if (trimmed.startsWith("title_cs:")) {
                    titleCs = trimmed.substring("title_cs:".length()).trim();
                } else if (trimmed.startsWith("auto:")) {
                    isBlocksAuto = "blocks".equalsIgnoreCase(trimmed.substring("auto:".length()).trim());
                } else if (trimmed.equals("items:")) {
                    inItems = true;
                } else if (inItems && trimmed.startsWith("- ")) {
                    items.add(trimmed.substring(2).trim());
                }
            }
            if (currentSection != null) {
                root.getChildren().add(buildSectionNode(
                        titleEn, titleCs, isBlocksAuto, items, locale, contentManager
                ));
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load /help/toc.yml", e);
        }
        return root;
    }

    private static TocNode buildSectionNode(
            String titleEn,
            String titleCs,
            boolean isBlocksAuto,
            List<String> items,
            String locale,
            HelpContentManager contentManager
    ) {
        String label = "cs".equals(locale) && titleCs != null ? titleCs : titleEn;
        TocNode sectionNode = new TocNode(null, label);

        if (isBlocksAuto) {
            Map<String, TocNode> categoryNodes = new LinkedHashMap<>();
            for (BlockDefinition def : BlockCatalog.getAllDefinitions().values()) {
                String catPath = def.getCategoryPath();
                if (catPath != null && catPath.startsWith("Macros")) {
                    continue; // Project-local macros are not static catalog blocks
                }
                TocNode parent = sectionNode;
                if (catPath != null && !catPath.isBlank()) {
                    String[] parts = catPath.split("/");
                    StringBuilder acc = new StringBuilder();
                    for (String part : parts) {
                        if (!acc.isEmpty()) acc.append('/');
                        acc.append(part);
                        String key = acc.toString();
                        TocNode cat = categoryNodes.get(key);
                        if (cat == null) {
                            cat = new TocNode(null, part);
                            categoryNodes.put(key, cat);
                            parent.getChildren().add(cat);
                        }
                        parent = cat;
                    }
                }
                parent.getChildren().add(new TocNode("blocks/" + def.getId(), def.getTypeName()));
            }
            return sectionNode;
        }

        for (String pageId : items) {
            HelpPage page = contentManager.loadPage(pageId, locale);
            String itemLabel = page != null ? page.title() : pageId;
            sectionNode.getChildren().add(new TocNode(pageId, itemLabel));
        }
        return sectionNode;
    }
}
