package ai.enterprise.assistant;

import ai.enterprise.shell.UiTheme;
import java.util.*;
import java.util.List;
import java.util.regex.Pattern;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.dnd.*;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.widgets.*;

/** Native, selectable Markdown view. Clipboard text always comes from the original source. */
public final class MarkdownText extends StyledText {
    private static final int BOLD = 1, ITALIC = 2, CODE = 4, STRIKE = 8, LINK = 16, QUOTE = 32, TABLE = 64;
    private static final Pattern HEADING = Pattern.compile("^ {0,3}(#{1,6})[ \\t]+(.*)$");
    private static final Pattern LIST = Pattern.compile("^(\\s*)([-+*]|\\d+[.)])[ \\t]+(.*)$");
    private final UiTheme theme;
    private final Map<Integer, Font> fonts = new HashMap<>();
    private Document document = new Document();

    public MarkdownText(Composite parent, UiTheme theme) {
        super(parent, SWT.READ_ONLY | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL);
        this.theme = theme; theme.apply(this); setMargins(4, 4, 4, 4);
        Menu menu = new Menu(this); setMenu(menu);
        MenuItem copy = new MenuItem(menu, SWT.PUSH); copy.setText("Копировать\tCtrl+C");
        copy.addListener(SWT.Selection, event -> copy());
        MenuItem all = new MenuItem(menu, SWT.PUSH); all.setText("Выделить всё\tCtrl+A");
        all.addListener(SWT.Selection, event -> selectAll());
        menu.addListener(SWT.Show, event -> copy.setEnabled(getSelectionCount() > 0));
        addListener(SWT.Dispose, event -> fonts.values().forEach(Font::dispose));
    }

    public void setDocument(Document value) {
        document = value; setText(value.visible.toString());
        List<StyleRange> ranges = new ArrayList<>();
        for (int start = 0; start < value.formats.size();) {
            int format = value.formats.get(start), end = start + 1;
            while (end < value.formats.size() && value.formats.get(end) == format) end++;
            if (format != 0) {
                StyleRange range = new StyleRange(); range.start = start; range.length = end - start;
                range.font = fonts.computeIfAbsent(format & (BOLD | ITALIC | CODE | TABLE | 0xF00), key -> {
                    FontData data = theme.bodyFont().getFontData()[0];
                    int heading = (key >> 8) & 15;
                    if ((key & (CODE | TABLE)) != 0) data.setName("Consolas");
                    if (heading > 0) data.setHeight(data.getHeight() + Math.max(1, 9 - heading));
                    data.setStyle(((key & BOLD) != 0 || heading > 0 ? SWT.BOLD : SWT.NORMAL)
                        | ((key & ITALIC) != 0 ? SWT.ITALIC : SWT.NORMAL));
                    return new Font(getDisplay(), data);
                });
                range.strikeout = (format & STRIKE) != 0;
                range.underline = (format & LINK) != 0;
                if ((format & CODE) != 0) range.background = theme.field();
                if ((format & QUOTE) != 0) range.foreground = theme.muted();
                ranges.add(range);
            }
            start = end;
        }
        setStyleRanges(ranges.toArray(StyleRange[]::new));
    }

    @Override public void copy() { copy(DND.CLIPBOARD); }
    @Override public void copy(int clipboardType) {
        checkWidget();
        if (getSelectionCount() == 0) return;
        var selection = getSelection();
        String markdown = document.selection(selection.x, selection.y);
        Clipboard clipboard = new Clipboard(getDisplay());
        try { clipboard.setContents(new Object[] {markdown}, new Transfer[] {TextTransfer.getInstance()}, clipboardType); }
        finally { clipboard.dispose(); }
    }

    /** Source offsets are retained per UTF-16 unit, including hidden delimiters at span boundaries. */
    public static final class Document {
        private final StringBuilder raw = new StringBuilder(), visible = new StringBuilder();
        private final List<Integer> starts = new ArrayList<>(), ends = new ArrayList<>(), formats = new ArrayList<>();
        public Document append(String text) { return append(text, false); }
        public Document append(String text, boolean markdown) {
            int base = raw.length(); raw.append(text);
            if (markdown) blocks(base, raw.length()); else literal(base, raw.length(), 0);
            return this;
        }
        public boolean isEmpty() { return raw.isEmpty(); }
        public String selection(int start, int end) {
            int first = Math.max(0, Math.min(visible.length(), Math.min(start, end)));
            int last = Math.max(0, Math.min(visible.length(), Math.max(start, end)));
            if (first == last) return "";
            // Wrapped table rows visit different source cells out of source order.
            // Return their enclosing original Markdown span, never reversed bounds.
            int from = raw.length(), to = 0;
            for (int i = first; i < last; i++) { from = Math.min(from, starts.get(i)); to = Math.max(to, ends.get(i)); }
            if (first == 0) from = 0;
            if (last == visible.length()) to = raw.length();
            from = Math.max(0, Math.min(raw.length(), from));
            to = Math.max(from, Math.min(raw.length(), to));
            if (from > 0 && from < raw.length() && Character.isLowSurrogate(raw.charAt(from)) && Character.isHighSurrogate(raw.charAt(from - 1))) from--;
            if (to > 0 && to < raw.length() && Character.isHighSurrogate(raw.charAt(to - 1)) && Character.isLowSurrogate(raw.charAt(to))) to++;
            return raw.substring(from, to);
        }
        private void glyph(char character, int start, int end, int format) {
            visible.append(character); starts.add(start); ends.add(end); formats.add(format);
        }
        private void literal(int start, int end, int format) {
            for (int i = start; i < end; i++) glyph(raw.charAt(i), i, i + 1, format);
        }
        private void cover(int first, int sourceStart, int sourceEnd) {
            if (first < visible.length()) {
                starts.set(first, sourceStart); ends.set(visible.length() - 1, sourceEnd);
            }
        }
        private void blocks(int start, int end) {
            char fence = 0; int fenceLength = 0, fenceStart = -1, fenceVisible = -1;
            for (int line = start; line < end;) {
                int next = raw.indexOf("\n", line); if (next < 0 || next >= end) next = end;
                int contentEnd = next > line && raw.charAt(next - 1) == '\r' ? next - 1 : next;
                String text = raw.substring(line, contentEnd), stripped = text.stripLeading();
                int indent = text.length() - stripped.length(), run = 0;
                char marker = stripped.isEmpty() ? 0 : stripped.charAt(0);
                while (run < stripped.length() && stripped.charAt(run) == marker) run++;
                boolean delimiter = indent <= 3 && (marker == '`' || marker == '~') && run >= 3;
                if (fence == 0 && !delimiter) {
                    Table table = table(line, end);
                    if (table != null) { renderTable(table); line = table.after(); continue; }
                }
                if (fence == 0 && delimiter) {
                    fence = marker; fenceLength = run; fenceStart = line; fenceVisible = visible.length();
                } else if (fence != 0 && delimiter && marker == fence && run >= fenceLength
                    && stripped.substring(run).isBlank()) {
                    cover(fenceVisible, fenceStart, contentEnd);
                    fence = 0;
                    if (next < end) literal(contentEnd, next + 1, 0);
                } else {
                    int first = visible.length(), from = line, format = 0;
                    if (fence != 0) {
                        literal(line, contentEnd, CODE);
                        if (first == fenceVisible && first < visible.length()) starts.set(first, fenceStart);
                    } else {
                        var heading = HEADING.matcher(text);
                        var list = LIST.matcher(text);
                        if (heading.matches()) {
                            from = line + heading.start(2); format = heading.group(1).length() << 8;
                            var closing = Pattern.compile("[ \\t]+#+[ \\t]*$").matcher(raw.substring(from, contentEnd));
                            if (closing.find()) contentEnd = from + closing.start();
                        } else if (list.matches()) {
                            literal(line, line + list.group(1).length(), 0);
                            int bullet = line + list.start(2), body = line + list.start(3);
                            if (list.group(2).length() == 1) glyph('•', bullet, body, 0);
                            else literal(bullet, body - 1, 0);
                            glyph(' ', body - 1, body, 0); from = body;
                        } else if (stripped.startsWith("> ") || stripped.equals(">")) {
                            from = Math.min(contentEnd, line + indent + 2); format = QUOTE;
                            glyph('│', line, from, QUOTE); glyph(' ', line, from, QUOTE);
                        }
                        inline(from, contentEnd, format, 0);
                        cover(first, line, next > line && raw.charAt(next - 1) == '\r' ? next - 1 : next);
                    }
                    if (next < end) literal(next > line && raw.charAt(next - 1) == '\r' ? next - 1 : next, next + 1, fence != 0 ? CODE : 0);
                }
                line = next < end ? next + 1 : end;
            }
        }
        private record CellRange(int start, int end) {}
        private record TableRow(int start, int end, int after, List<CellRange> cells) {}
        private record Table(List<TableRow> rows, TableRow separator, int columns, int after) {}
        private record Glyph(char value, int start, int end, int format) {}
        private record Cell(CellRange source, List<Glyph> glyphs) {}
        private record CellLine(int start, int end, int width) {}

        private TableRow tableRow(int start, int limit) {
            int next = raw.indexOf("\n", start); if (next < 0 || next >= limit) next = limit;
            int end = next > start && raw.charAt(next - 1) == '\r' ? next - 1 : next;
            if (end - start > 65536) return null;
            List<Integer> pipes = new ArrayList<>(); int code = 0, slashes = 0;
            for (int i = start; i < end; i++) {
                char c = raw.charAt(i);
                if (c == '`' && (slashes & 1) == 0) {
                    int count = 1; while (i + count < end && raw.charAt(i + count) == '`') count++;
                    if (code == 0) {
                        int close = raw.indexOf("`".repeat(count), i + count);
                        if (close >= 0 && close < end) code = count;
                    } else if (code == count) code = 0;
                    i += count - 1; slashes = 0; continue;
                }
                if (c == '|' && (slashes & 1) == 0 && code == 0) { if (pipes.size() == 7) return null; pipes.add(i); }
                slashes = c == '\\' ? slashes + 1 : 0;
            }
            if (pipes.isEmpty()) return null;
            List<CellRange> cells = new ArrayList<>(); int from = start;
            for (int pipe : pipes) { cells.add(new CellRange(from, pipe)); from = pipe + 1; }
            cells.add(new CellRange(from, end));
            if (raw.substring(cells.getFirst().start(), cells.getFirst().end()).isBlank()) cells.removeFirst();
            if (!cells.isEmpty() && raw.substring(cells.getLast().start(), cells.getLast().end()).isBlank()) cells.removeLast();
            List<CellRange> trimmed = new ArrayList<>();
            for (CellRange cell : cells) {
                int left = cell.start(), right = cell.end();
                while (left < right && (raw.charAt(left) == ' ' || raw.charAt(left) == '\t')) left++;
                while (right > left && (raw.charAt(right - 1) == ' ' || raw.charAt(right - 1) == '\t')) right--;
                trimmed.add(new CellRange(left, right));
            }
            return new TableRow(start, end, next < limit ? next + 1 : limit, trimmed);
        }
        private Table table(int start, int end) {
            TableRow header = tableRow(start, end);
            if (header == null || header.cells().size() < 2 || header.cells().size() > 6 || header.after() >= end) return null;
            TableRow separator = tableRow(header.after(), end);
            if (separator == null || separator.cells().size() != header.cells().size()) return null;
            for (CellRange cell : separator.cells()) if (!raw.substring(cell.start(), cell.end()).matches(":?-{3,}:?")) return null;
            List<TableRow> rows = new ArrayList<>(); rows.add(header); int after = separator.after();
            while (after < end) {
                TableRow row = tableRow(after, end);
                if (row == null || row.cells().size() != header.cells().size()) break;
                // Larger or irregular input keeps the existing literal/inline path.
                if (rows.size() >= 128 || row.after() - start > 65536) return null;
                rows.add(row); after = row.after();
            }
            if (after - start > 65536) return null;
            return new Table(rows, separator, header.cells().size(), after);
        }
        private Cell cell(CellRange source, boolean header) {
            int first = visible.length(); inline(source.start(), source.end(), TABLE | (header ? BOLD : 0), 0);
            cover(first, source.start(), source.end()); List<Glyph> result = new ArrayList<>();
            for (int i = first; i < visible.length();) {
                int length = 0; char decoded = 0;
                // Table producer escapes original HTML before inserting these breaks.
                // Decode entities afterwards, so &lt;br&gt; stays literal source text.
                if ((formats.get(i) & CODE) == 0) for (String tag : List.of("<br>", "<br/>", "<br />")) if (visible.substring(i, Math.min(visible.length(), i + tag.length())).equalsIgnoreCase(tag)) { length = tag.length(); decoded = '\n'; break; }
                if (length == 0) {
                    String[] entities = {"&lt;", "&gt;", "&amp;", "&quot;", "&apos;", "&#39;"};
                    char[] values = {'<', '>', '&', '"', '\'', '\''};
                    for (int e = 0; e < entities.length; e++) if (visible.substring(i, Math.min(visible.length(), i + entities[e].length())).equals(entities[e])) { length = entities[e].length(); decoded = values[e]; break; }
                }
                if (length > 0) { result.add(new Glyph(decoded, starts.get(i), ends.get(i + length - 1), formats.get(i))); i += length; }
                else { result.add(new Glyph(visible.charAt(i), starts.get(i), ends.get(i), formats.get(i))); i++; }
            }
            visible.setLength(first); starts.subList(first, starts.size()).clear(); ends.subList(first, ends.size()).clear(); formats.subList(first, formats.size()).clear();
            return new Cell(source, result);
        }
        private int units(List<Glyph> text, int at) {
            return Character.isHighSurrogate(text.get(at).value()) && at + 1 < text.size() && Character.isLowSurrogate(text.get(at + 1).value()) ? 2 : 1;
        }
        private int columns(List<Glyph> text, int at) {
            int cp = units(text, at) == 2 ? Character.toCodePoint(text.get(at).value(), text.get(at + 1).value()) : text.get(at).value();
            int type = Character.getType(cp);
            if (type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK || type == Character.ENCLOSING_MARK) return 0;
            return cp >= 0x1100 && (cp <= 0x115f || cp >= 0x2e80 && cp <= 0xa4cf || cp >= 0xac00 && cp <= 0xd7a3
                || cp >= 0xf900 && cp <= 0xfaff || cp >= 0xfe10 && cp <= 0xfe6f || cp >= 0xff01 && cp <= 0xff60 || cp >= 0x1f300) ? 2 : 1;
        }
        private List<CellLine> wrap(Cell cell, int width) {
            List<CellLine> result = new ArrayList<>(); List<Glyph> text = cell.glyphs();
            for (int from = 0; from < text.size();) {
                if (text.get(from).value() == '\n') { result.add(new CellLine(from, from, 0)); from++; continue; }
                int to = from, used = 0, wordBreak = -1, breakWidth = 0;
                while (to < text.size()) {
                    if (text.get(to).value() == '\n') break;
                    int size = units(text, to), nextWidth = columns(text, to);
                    if (used + nextWidth > width && to > from) break;
                    used += nextWidth; to += size;
                    if (Character.isWhitespace(text.get(to - 1).value())) { wordBreak = to; breakWidth = used; }
                }
                if (to < text.size() && text.get(to).value() != '\n' && wordBreak > from) { to = wordBreak; used = breakWidth; }
                result.add(new CellLine(from, to, used)); from = to < text.size() && text.get(to).value() == '\n' ? to + 1 : to;
            }
            if (!text.isEmpty() && text.getLast().value() == '\n') result.add(new CellLine(text.size(), text.size(), 0));
            if (result.isEmpty()) result.add(new CellLine(0, 0, 0));
            return result;
        }
        private void tableGenerated(String text, int start, int end) {
            for (int i = 0; i < text.length(); i++) glyph(text.charAt(i), start, end, TABLE);
        }
        private void renderTable(Table table) {
            int first = visible.length(), count = table.columns(), cap = Math.min(32, (76 - 3 * (count - 1)) / count);
            int[] widths = new int[count]; Arrays.fill(widths, Math.min(8, cap)); List<List<Cell>> rendered = new ArrayList<>();
            for (int row = 0; row < table.rows().size(); row++) {
                List<Cell> cells = new ArrayList<>();
                for (int col = 0; col < count; col++) {
                    Cell value = cell(table.rows().get(row).cells().get(col), row == 0); cells.add(value); int width = 0;
                    for (int i = 0; i < value.glyphs().size(); i += units(value.glyphs(), i)) width += columns(value.glyphs(), i);
                    widths[col] = Math.max(widths[col], Math.min(cap, width));
                }
                rendered.add(cells);
            }
            for (int row = 0; row < rendered.size(); row++) {
                TableRow source = table.rows().get(row); List<List<CellLine>> lines = new ArrayList<>(); int height = 1;
                for (int col = 0; col < count; col++) { var wrapped = wrap(rendered.get(row).get(col), widths[col]); lines.add(wrapped); height = Math.max(height, wrapped.size()); }
                for (int line = 0; line < height; line++) {
                    for (int col = 0; col < count; col++) {
                        Cell value = rendered.get(row).get(col); int used = 0, anchor = value.source().end();
                        if (line < lines.get(col).size()) {
                            CellLine part = lines.get(col).get(line); used = part.width();
                            for (int i = part.start(); i < part.end(); i++) { Glyph g = value.glyphs().get(i); glyph(g.value(), g.start(), g.end(), g.format()); anchor = g.end(); }
                        }
                        if (col + 1 < count) {
                            tableGenerated(" ".repeat(Math.max(0, widths[col] - used)) + " │ ", anchor, anchor);
                        }
                    }
                    if (line + 1 < height || source.after() > source.end()) tableGenerated("\n", source.end(), source.after());
                }
                if (row == 0) {
                    for (int col = 0; col < count; col++) tableGenerated("─".repeat(widths[col]) + (col + 1 < count ? "─┼─" : ""), table.separator().start(), table.separator().end());
                    if (table.separator().after() > table.separator().end()) tableGenerated("\n", table.separator().end(), table.separator().after());
                }
            }
            cover(first, table.rows().getFirst().start(), table.after());
        }
        private void inline(int start, int end, int format, int depth) {
            if (depth > 16) { literal(start, end, format); return; }
            for (int i = start; i < end;) {
                char c = raw.charAt(i); int first = visible.length();
                if (c == '\\' && i + 1 < end && "\\`*_{}[]()#+-.!>~|".indexOf(raw.charAt(i + 1)) >= 0) {
                    glyph(raw.charAt(i + 1), i, i + 2, format); i += 2; continue;
                }
                if (c == '`') {
                    int count = 1; while (i + count < end && raw.charAt(i + count) == '`') count++;
                    String mark = "`".repeat(count); int close = raw.indexOf(mark, i + count);
                    if (close >= 0 && close < end && close > i + count) {
                        literal(i + count, close, format | CODE); cover(first, i, close + count); i = close + count; continue;
                    }
                }
                if (c == '[') {
                    int label = raw.indexOf("](", i + 1);
                    int close = label >= 0 ? raw.indexOf(")", label + 2) : -1;
                    if (label > i + 1 && close >= 0 && close < end) {
                        inline(i + 1, label, format | LINK, depth + 1); cover(first, i, close + 1); i = close + 1; continue;
                    }
                }
                boolean matched = false;
                for (String mark : new String[] {"***", "___", "**", "__", "~~", "*", "_"}) {
                    if (i + mark.length() >= end || !raw.substring(i, Math.min(end, i + mark.length())).equals(mark)) continue;
                    if (mark.charAt(0) == '_' && i > start && Character.isLetterOrDigit(raw.charAt(i - 1))) continue;
                    if (Character.isWhitespace(raw.charAt(i + mark.length()))) continue;
                    int close = raw.indexOf(mark, i + mark.length());
                    if (close <= i + mark.length() || close + mark.length() > end || Character.isWhitespace(raw.charAt(close - 1))) continue;
                    int extra = mark.equals("~~") ? STRIKE : mark.length() == 3 ? BOLD | ITALIC : mark.length() == 2 ? BOLD : ITALIC;
                    inline(i + mark.length(), close, format | extra, depth + 1);
                    cover(first, i, close + mark.length()); i = close + mark.length(); matched = true; break;
                }
                if (!matched) { glyph(c, i, i + 1, format); i++; }
            }
        }
    }
}
