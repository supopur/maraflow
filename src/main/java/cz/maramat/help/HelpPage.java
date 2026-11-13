package cz.maramat.help;

import java.util.List;

public record HelpPage(
        String id,
        String title,
        List<String> tags,
        String level,
        List<String> seeAlso,
        String markdown,
        String locale,
        boolean fallback
) {}
