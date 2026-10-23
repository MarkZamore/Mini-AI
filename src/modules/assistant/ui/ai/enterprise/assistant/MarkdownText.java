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
    private static final int BOLD = 1, ITALIC = 2, CODE = 4, STRIKE = 8, LINK = 16, QUOTE = 32;
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
                range.font = fonts.computeIfAbsent(format & (BOLD | ITALIC | CODE | 0xF00), key -> {
                    FontData data = theme.bodyFont().getFontData()[0];
                    int heading = (key >> 8) & 15;
                    if ((key & CODE) != 0) data.setName("Consolas");
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
            if (start == end) return "";
            return raw.substring(start == 0 ? 0 : starts.get(start),
                end == visible.length() ? raw.length() : ends.get(end - 1));
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
