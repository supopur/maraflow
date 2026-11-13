package cz.maramat.help;

import cz.maramat.help.HelpMarkdownRenderer.RenderedHelpHtml;
import cz.maramat.help.HelpSearchIndex.SearchResult;
import cz.maramat.help.HelpToc.TocNode;
import cz.maramat.help.diagram.FbdDiagramRenderer;
import cz.maramat.model.BlockCatalog;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class HelpQualityGateTest {

    private static final Pattern HELP_LINK = Pattern.compile("\\(help:([^)#\\s]+)(?:#([^)\\s]+))?\\)");

    @Test
    void allTocPagesExistAndGettingStartedIsLocalizedInCzech() {
        HelpContentManager mgr = new HelpContentManager();
        TocNode root = HelpToc.load("en", mgr);
        Set<String> allIds = new LinkedHashSet<>();
        collectIds(root, allIds);
        assertFalse(allIds.isEmpty());

        List<String> missingCs = new ArrayList<>();
        for (String id : allIds) {
            HelpPage en = mgr.loadPage(id, "en");
            assertNotNull(en, "Missing EN page for TOC id: " + id);
            assertFalse(en.title().isBlank(), "Blank title for: " + id);

            HelpPage cs = mgr.loadPage(id, "cs");
            assertNotNull(cs, "Missing CS/fallback page for TOC id: " + id);
            if (id.startsWith("getting-started/")) {
                assertFalse(cs.fallback(), "Getting Started page must be translated in Czech: " + id);
            } else if (!id.startsWith("blocks/") && cs.fallback()) {
                missingCs.add(id);
            }
        }
        System.out.println("[Help Coverage] Non-block pages falling back to EN in CS (" + missingCs.size() + "): " + missingCs);
    }

    @Test
    void allHelpLinksResolveAndNoBrokenDiagramsOrEmojis() throws Exception {
        HelpContentManager mgr = new HelpContentManager();
        HelpMarkdownRenderer renderer = new HelpMarkdownRenderer();

        Path helpRoot = Path.of("src/main/resources/help");
        try (Stream<Path> walk = Files.walk(helpRoot)) {
            List<Path> files = walk.filter(Files::isRegularFile).toList();
            assertFalse(files.isEmpty());

            for (Path file : files) {
                String content = Files.readString(file);
                // Gate: zero emojis anywhere in help resources
                assertFalse(containsEmoji(content), "Emoji found in help file: " + file);

                if (file.toString().endsWith(".md")) {
                    // Check block override files map to real catalog entries
                    String norm = file.toString().replace('\\', '/');
                    int blocksIdx = norm.indexOf("/blocks/");
                    if (blocksIdx >= 0) {
                        String fileName = file.getFileName().toString();
                        String blockId = fileName.substring(0, fileName.length() - 3);
                        assertNotNull(BlockCatalog.getById(blockId),
                                "Block override file does not match any BlockCatalog entry: " + file);
                    }

                    // Check all help: links resolve
                    Matcher m = HELP_LINK.matcher(content);
                    while (m.find()) {
                        String targetId = m.group(1);
                        String anchor = m.group(2);
                        HelpPage target = mgr.loadPage(targetId, "en");
                        assertNotNull(target, "Broken help link '" + targetId + "' in " + file);
                        if (anchor != null && !anchor.isBlank()) {
                            RenderedHelpHtml rendered = renderer.render(target, "en", FbdDiagramRenderer.DEFAULT_GRID_PX);
                            assertTrue(rendered.html().contains("id=\"" + anchor + "\""),
                                    "Broken anchor '#" + anchor + "' on '" + targetId + "' referenced in " + file);
                        }
                    }

                    // Render markdown and check for diagram errors
                    HelpContentManager.ParsedFrontMatter fm = mgr.parseFrontMatter(content);
                    HelpPage tmp = new HelpPage("test", fm.title() != null ? fm.title() : "Test",
                            fm.tags(), "reference", fm.seeAlso(), fm.body(), "en", false);
                    RenderedHelpHtml html = renderer.render(tmp, "en", FbdDiagramRenderer.DEFAULT_GRID_PX);
                    assertFalse(html.html().contains("class=\"diagram-error\""),
                            "Diagram render error in " + file + ": " + html.html());
                }
            }
        }
    }

    @Test
    void luceneSearchSupportsEnglishAndCzechDiacriticInsensitiveQueries() {
        HelpContentManager mgr = new HelpContentManager();
        HelpSearchIndex index = new HelpSearchIndex();

        List<SearchResult> enLatch = index.search("latch", "en", mgr);
        assertFalse(enLatch.isEmpty(), "Expected English search hits for 'latch'");
        assertTrue(enLatch.stream().anyMatch(r -> r.pageId().contains("RS") || r.pageId().contains("first-latch")));

        // Search without Czech diacritics for words written with diacritics ("dominantní", "pořadí")
        List<SearchResult> csNoDiacritics = index.search("dominantni", "cs", mgr);
        assertFalse(csNoDiacritics.isEmpty(), "Expected Czech diacritic-insensitive search hits for 'dominantni'");

        List<SearchResult> csPoradi = index.search("poradi", "cs", mgr);
        assertFalse(csPoradi.isEmpty(), "Expected Czech diacritic-insensitive search hits for 'poradi'");

        index.release();
        mgr.releaseCaches();
    }

    private static void collectIds(TocNode node, Set<String> out) {
        if (node.getPageId() != null) {
            out.add(node.getPageId());
        }
        for (TocNode c : node.getChildren()) {
            collectIds(c, out);
        }
    }

    private static boolean containsEmoji(String s) {
        return s.codePoints().anyMatch(cp ->
                (cp >= 0x1F300 && cp <= 0x1FAFF) || (cp >= 0x2600 && cp <= 0x27BF));
    }
}
