package cz.maramat.help;

import cz.maramat.help.HelpToc.TocNode;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lazy in-memory search index for Help pages (diacritic-insensitive for EN/CZ).
 */
public final class HelpSearchIndex {

    public record SearchResult(String pageId, String title, String snippet) {}

    private record IndexedPage(
            String id,
            String title,
            String body,
            String foldedTitle,
            String foldedTags,
            String foldedHeadings,
            String foldedBody
    ) {}

    private record ScoredPage(IndexedPage page, int score) {}

    private static final Pattern HEADING_PATTERN = Pattern.compile("^#{2,3}\\s+(.+)$", Pattern.MULTILINE);
    private static final Pattern DIACRITICS = Pattern.compile("\\p{M}+");

    private List<IndexedPage> pages;
    private String indexedLocale;

    public synchronized List<SearchResult> search(String rawQuery, String locale, HelpContentManager contentManager) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return List.of();
        }
        String normLocale = "cs".equals(locale) ? "cs" : "en";
        ensureBuilt(normLocale, contentManager);

        String foldedQuery = fold(rawQuery.trim());
        String[] terms = foldedQuery.split("\\s+");
        if (terms.length == 0) {
            return List.of();
        }

        List<ScoredPage> matches = new ArrayList<>();
        for (IndexedPage p : pages) {
            int score = scorePage(p, terms);
            if (score > 0) {
                matches.add(new ScoredPage(p, score));
            }
        }
        matches.sort(Comparator.comparingInt(ScoredPage::score).reversed()
                .thenComparing(sp -> sp.page().title(), String.CASE_INSENSITIVE_ORDER));

        int limit = Math.min(30, matches.size());
        List<SearchResult> results = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            IndexedPage p = matches.get(i).page();
            results.add(new SearchResult(p.id(), p.title(), makeSnippet(p.body(), rawQuery.trim())));
        }
        return results;
    }

    private static int scorePage(IndexedPage p, String[] terms) {
        int total = 0;
        for (String term : terms) {
            if (term.isEmpty()) continue;
            int termScore = 0;
            if (p.foldedTitle().contains(term)) termScore += 4;
            if (p.foldedTags().contains(term)) termScore += 3;
            if (p.foldedHeadings().contains(term)) termScore += 2;
            if (p.foldedBody().contains(term)) termScore += 1;
            if (termScore == 0) {
                return 0;
            }
            total += termScore;
        }
        return total;
    }

    private void ensureBuilt(String locale, HelpContentManager contentManager) {
        if (pages != null && locale.equals(indexedLocale)) {
            return;
        }
        TocNode root = HelpToc.load(locale, contentManager);
        Set<String> pageIds = new LinkedHashSet<>();
        collectPageIds(root, pageIds);

        List<IndexedPage> built = new ArrayList<>(pageIds.size());
        for (String pageId : pageIds) {
            HelpPage page = contentManager.loadPage(pageId, locale);
            if (page == null) continue;
            String body = stripMarkdownNoise(page.markdown());
            String headings = extractHeadings(page.markdown());
            String tags = String.join(" ", page.tags());
            built.add(new IndexedPage(
                    page.id(),
                    page.title(),
                    body,
                    fold(page.title()),
                    fold(tags),
                    fold(headings),
                    fold(body)
            ));
        }
        pages = built;
        indexedLocale = locale;
    }

    public synchronized void release() {
        pages = null;
        indexedLocale = null;
    }

    private static String fold(String s) {
        if (s == null || s.isEmpty()) return "";
        String norm = Normalizer.normalize(s, Normalizer.Form.NFD);
        return DIACRITICS.matcher(norm).replaceAll("").toLowerCase(Locale.ROOT);
    }

    private static void collectPageIds(TocNode node, Set<String> out) {
        if (node.getPageId() != null) {
            out.add(node.getPageId());
        }
        for (TocNode child : node.getChildren()) {
            collectPageIds(child, out);
        }
    }

    private static String extractHeadings(String md) {
        StringBuilder sb = new StringBuilder();
        Matcher m = HEADING_PATTERN.matcher(md);
        while (m.find()) {
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(m.group(1).trim());
        }
        return sb.toString();
    }

    private static String stripMarkdownNoise(String md) {
        return md.replaceAll("```[\\s\\S]*?```", " ")
                .replaceAll("[#`*_>|\\[\\]()]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String makeSnippet(String body, String query) {
        if (body == null || body.isBlank()) return "";
        String lowerBody = fold(body);
        String firstTerm = fold(query).split("\\s+")[0];
        int idx = lowerBody.indexOf(firstTerm);
        int start = Math.max(0, idx < 0 ? 0 : idx - 35);
        int end = Math.min(body.length(), start + 110);
        String slice = body.substring(start, end).trim();
        if (start > 0) slice = "..." + slice;
        if (end < body.length()) slice = slice + "...";
        return slice;
    }
}
