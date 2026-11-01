package com.notearray.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the markdown task lines in a note into TODO items:
 *
 * <pre>- [ ] Buy milk
 *   - [x] Semi-skimmed
 * - [ ] Walk the dog</pre>
 *
 * <p>Indentation (two spaces per level, tabs count as two) determines nesting.
 * Lines that are not task items are ignored, so a note can mix prose and
 * checklists. A depth jump greater than one level is clamped to the next level
 * so malformed indentation cannot create orphans.</p>
 */
public final class TodoChecklistParser {

    private static final Pattern TASK =
            Pattern.compile("^(\\s*)[-*+]\\s+\\[([ xX])\\]\\s+(.*\\S)\\s*$");

    public static final int MAX_TEXT_LENGTH = 2000;

    /** One parsed task line; {@code depth} is zero-based. */
    public record ParsedItem(String text, boolean checked, int depth) {}

    private TodoChecklistParser() {
    }

    public static List<ParsedItem> parse(String markdown) {
        List<ParsedItem> items = new ArrayList<>();
        if (markdown == null || markdown.isBlank()) {
            return items;
        }
        String[] lines = markdown.split("\\R", -1);
        int previousDepth = 0;
        for (String line : lines) {
            Matcher matcher = TASK.matcher(line);
            if (!matcher.matches()) {
                continue;
            }
            String indent = matcher.group(1).replace("\t", "  ");
            int depth = Math.max(0, indent.length() / 2);
            if (depth > previousDepth + 1) {
                depth = previousDepth + 1;
            }
            String text = matcher.group(3).trim();
            if (text.length() > MAX_TEXT_LENGTH) {
                text = text.substring(0, MAX_TEXT_LENGTH);
            }
            boolean checked = !" ".equals(matcher.group(2));
            items.add(new ParsedItem(text, checked, depth));
            previousDepth = depth;
        }
        return items;
    }
}
