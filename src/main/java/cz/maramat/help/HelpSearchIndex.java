package cz.maramat.help;

import cz.maramat.help.HelpToc.TocNode;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.LowerCaseFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.miscellaneous.ASCIIFoldingFilter;
import org.apache.lucene.analysis.standard.StandardTokenizer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.ByteBuffersDirectory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lazy in-memory Lucene search index for Help pages (diacritic-insensitive for EN/CZ).
 */
public final class HelpSearchIndex {

    public record SearchResult(String pageId, String title, String snippet) {}

    private static final Pattern HEADING_PATTERN = Pattern.compile("^#{2,3}\\s+(.+)$", Pattern.MULTILINE);

    private ByteBuffersDirectory directory;
    private DirectoryReader reader;
    private String indexedLocale;

    public synchronized List<SearchResult> search(String rawQuery, String locale, HelpContentManager contentManager) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return List.of();
        }
        String normLocale = "cs".equals(locale) ? "cs" : "en";
        ensureBuilt(normLocale, contentManager);

        try {
            IndexSearcher searcher = new IndexSearcher(reader);
            Analyzer analyzer = createAnalyzer();
            Map<String, Float> boosts = Map.of(
                    "title", 4.0f,
                    "tags", 3.0f,
                    "headings", 2.0f,
                    "body", 1.0f
            );
            MultiFieldQueryParser parser = new MultiFieldQueryParser(
                    new String[]{"title", "tags", "headings", "body"},
                    analyzer,
                    boosts
            );
            parser.setDefaultOperator(QueryParser.Operator.AND);

            String qText = rawQuery.trim();
            Query query;
            try {
                query = parser.parse(qText);
            } catch (Exception parseEx) {
                query = parser.parse(QueryParser.escape(qText));
            }

            TopDocs topDocs = searcher.search(query, 30);
            if (topDocs.totalHits.value == 0 && !qText.contains("*")) {
                // Fallback to prefix/escaped query for partial typing
                String[] terms = qText.split("\\s+");
                StringBuilder prefixQ = new StringBuilder();
                for (String t : terms) {
                    if (!t.isEmpty()) {
                        if (!prefixQ.isEmpty()) prefixQ.append(' ');
                        prefixQ.append(QueryParser.escape(t)).append('*');
                    }
                }
                if (!prefixQ.isEmpty()) {
                    query = parser.parse(prefixQ.toString());
                    topDocs = searcher.search(query, 30);
                }
            }

            List<SearchResult> results = new ArrayList<>();
            for (ScoreDoc sd : topDocs.scoreDocs) {
                Document doc = searcher.storedFields().document(sd.doc);
                String id = doc.get("id");
                String title = doc.get("title");
                String body = doc.get("body");
                results.add(new SearchResult(id, title, makeSnippet(body, qText)));
            }
            return results;
        } catch (Exception e) {
            return List.of();
        }
    }

    private void ensureBuilt(String locale, HelpContentManager contentManager) {
        if (directory != null && reader != null && locale.equals(indexedLocale)) {
            return;
        }
        release();
        try {
            directory = new ByteBuffersDirectory();
            Analyzer analyzer = createAnalyzer();
            IndexWriterConfig cfg = new IndexWriterConfig(analyzer);
            try (IndexWriter writer = new IndexWriter(directory, cfg)) {
                TocNode root = HelpToc.load(locale, contentManager);
                Set<String> pageIds = new LinkedHashSet<>();
                collectPageIds(root, pageIds);

                for (String pageId : pageIds) {
                    HelpPage page = contentManager.loadPage(pageId, locale);
                    if (page == null) continue;
                    Document doc = new Document();
                    doc.add(new StoredField("id", page.id()));
                    doc.add(new TextField("title", page.title(), Field.Store.YES));
                    doc.add(new TextField("tags", String.join(" ", page.tags()), Field.Store.NO));
                    doc.add(new TextField("headings", extractHeadings(page.markdown()), Field.Store.NO));
                    doc.add(new TextField("body", stripMarkdownNoise(page.markdown()), Field.Store.YES));
                    writer.addDocument(doc);
                }
            }
            reader = DirectoryReader.open(directory);
            indexedLocale = locale;
        } catch (Exception e) {
            release();
            throw new IllegalStateException("Failed to build Help search index", e);
        }
    }

    public synchronized void release() {
        try {
            if (reader != null) {
                reader.close();
                reader = null;
            }
        } catch (Exception ignored) {
        }
        try {
            if (directory != null) {
                directory.close();
                directory = null;
            }
        } catch (Exception ignored) {
        }
        indexedLocale = null;
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
        String lowerBody = body.toLowerCase(Locale.ROOT);
        String firstTerm = query.toLowerCase(Locale.ROOT).split("\\s+")[0];
        int idx = lowerBody.indexOf(firstTerm);
        int start = Math.max(0, idx < 0 ? 0 : idx - 35);
        int end = Math.min(body.length(), start + 110);
        String slice = body.substring(start, end).trim();
        if (start > 0) slice = "..." + slice;
        if (end < body.length()) slice = slice + "...";
        return slice;
    }

    private static Analyzer createAnalyzer() {
        return new Analyzer() {
            @Override
            protected TokenStreamComponents createComponents(String fieldName) {
                Tokenizer source = new StandardTokenizer();
                TokenStream filter = new LowerCaseFilter(source);
                filter = new ASCIIFoldingFilter(filter);
                return new TokenStreamComponents(source, filter);
            }
        };
    }
}
