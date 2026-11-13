package cz.maramat.help;

import org.commonmark.Extension;
import org.commonmark.ext.front.matter.YamlFrontMatterExtension;
import org.commonmark.ext.front.matter.YamlFrontMatterVisitor;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads localized Help markdown pages with per-page fallback to {@code en},
 * delegates {@code blocks/<id>} to {@link BlockHelpGenerator}, and bounds in-memory caching.
 */
public final class HelpContentManager {

    public static final int MAX_CACHED_PAGES = 8;

    public record RawMarkdownResult(String markdown, String locale, boolean fallback) {}

    public record ParsedFrontMatter(
            String title,
            List<String> tags,
            String level,
            List<String> seeAlso,
            boolean showImage,
            String body
    ) {}

    private final Parser frontMatterParser;
    private final Map<String, HelpPage> pageCache = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, HelpPage> eldest) {
                    return size() > MAX_CACHED_PAGES;
                }
            }
    );

    public HelpContentManager() {
        List<Extension> exts = List.of(YamlFrontMatterExtension.create());
        this.frontMatterParser = Parser.builder().extensions(exts).build();
    }

    public HelpPage loadPage(String pageId, String locale) {
        if (pageId == null || pageId.isBlank()) return null;
        String normLocale = "cs".equals(locale) ? "cs" : "en";
        String cacheKey = normLocale + ":" + pageId;
        HelpPage cached = pageCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        HelpPage loaded;
        if (pageId.startsWith("blocks/")) {
            String blockId = pageId.substring("blocks/".length());
            loaded = BlockHelpGenerator.generate(blockId, normLocale, this);
        } else {
            loaded = loadMarkdownPage(pageId, normLocale);
        }

        if (loaded != null) {
            pageCache.put(cacheKey, loaded);
        }
        return loaded;
    }

    private HelpPage loadMarkdownPage(String pageId, String locale) {
        RawMarkdownResult raw = loadRawResource("/help/" + locale + "/" + pageId + ".md", locale, false);
        if (raw == null && !"en".equals(locale)) {
            raw = loadRawResource("/help/en/" + pageId + ".md", "en", true);
        }
        if (raw == null) return null;

        ParsedFrontMatter fm = parseFrontMatter(raw.markdown());
        String title = fm.title() != null ? fm.title() : deriveTitleFromMarkdown(fm.body(), pageId);
        return new HelpPage(
                pageId,
                title,
                fm.tags(),
                fm.level() != null ? fm.level() : "reference",
                fm.seeAlso(),
                fm.body(),
                raw.locale(),
                raw.fallback()
        );
    }

    public RawMarkdownResult loadRawBlockOverride(String blockId, String locale) {
        RawMarkdownResult raw = loadRawResource("/help/" + locale + "/blocks/" + blockId + ".md", locale, false);
        if (raw == null && !"en".equals(locale)) {
            raw = loadRawResource("/help/en/blocks/" + blockId + ".md", "en", true);
        }
        return raw;
    }

    private RawMarkdownResult loadRawResource(String path, String locale, boolean fallback) {
        try (InputStream is = HelpContentManager.class.getResourceAsStream(path)) {
            if (is == null) return null;
            String text = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            return new RawMarkdownResult(text, locale, fallback);
        } catch (Exception e) {
            return null;
        }
    }

    public ParsedFrontMatter parseFrontMatter(String rawMarkdown) {
        String body = stripYamlFrontMatterText(rawMarkdown);
        Node doc = frontMatterParser.parse(rawMarkdown);
        YamlFrontMatterVisitor visitor = new YamlFrontMatterVisitor();
        doc.accept(visitor);
        Map<String, List<String>> data = visitor.getData();

        String title = firstOrNull(data.get("title"));
        List<String> tags = data.getOrDefault("tags", List.of());
        String level = firstOrNull(data.get("level"));
        List<String> see = data.getOrDefault("see", List.of());
        String imageVal = firstOrNull(data.get("image"));
        boolean showImage = imageVal == null || !"false".equalsIgnoreCase(imageVal.trim());

        return new ParsedFrontMatter(title, tags, level, see, showImage, body);
    }

    private static String stripYamlFrontMatterText(String raw) {
        if (raw == null) return "";
        String normalized = raw.replace("\r\n", "\n");
        if (!normalized.startsWith("---\n")) {
            return raw;
        }
        int end = normalized.indexOf("\n---\n", 4);
        if (end >= 0) {
            return normalized.substring(end + 5).stripLeading();
        }
        if (normalized.endsWith("\n---")) {
            return "";
        }
        return raw;
    }

    private static String firstOrNull(List<String> list) {
        return (list == null || list.isEmpty()) ? null : list.getFirst();
    }

    private static String deriveTitleFromMarkdown(String body, String fallbackId) {
        for (String line : body.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("# ")) {
                return trimmed.substring(2).trim();
            }
        }
        int slash = fallbackId.lastIndexOf('/');
        return slash >= 0 ? fallbackId.substring(slash + 1) : fallbackId;
    }

    public void releaseCaches() {
        pageCache.clear();
    }
}
