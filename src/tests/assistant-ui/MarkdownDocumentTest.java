package ai.enterprise.tests;

import ai.enterprise.assistant.MarkdownText;
import ai.enterprise.generated.GeneratedCatalog;
import java.lang.reflect.Field;
import java.util.*;

/** Pure Document behavior with the full seven-module classpath; no Display, model or GUI. */
public final class MarkdownDocumentTest {
    private static int checks;
    private static void check(boolean ok, String label) { checks++; if (!ok) throw new AssertionError(label); }
    private static String visible(MarkdownText.Document doc) throws Exception {
        Field field=MarkdownText.Document.class.getDeclaredField("visible"); field.setAccessible(true); return field.get(doc).toString();
    }
    @SuppressWarnings("unchecked") private static int format(MarkdownText.Document doc,int at) throws Exception {
        Field field=MarkdownText.Document.class.getDeclaredField("formats");field.setAccessible(true);return ((List<Integer>)field.get(doc)).get(at);
    }
    private static boolean paired(String text) {
        for(int i=0;i<text.length();i++) {
            if(Character.isHighSurrogate(text.charAt(i))) { if(++i>=text.length()||!Character.isLowSurrogate(text.charAt(i)))return false; }
            else if(Character.isLowSurrogate(text.charAt(i)))return false;
        }
        return true;
    }
    public static void main(String[] args) throws Exception {
        check(GeneratedCatalog.modules().size()==7,"complete seven-module classpath");
        String basic="# Заголовок\n\n**Жирный** и *курсив*, ~~удалённый~~, [ссылка](https://example.com).\n- Пункт\n```java\n# **код**\n```\n\\*буквально\\* some_name_here";
        var ordinary=new MarkdownText.Document().append(basic,true);String plain=visible(ordinary);
        check(plain.startsWith("Заголовок")&&!plain.contains("# Заголовок"),"existing heading");
        check(plain.contains("Жирный и курсив, удалённый, ссылка")&&plain.contains("• Пункт"),"existing inline and list rendering");
        check(plain.contains("# **код**")&&plain.contains("*буквально* some_name_here"),"existing fences escapes and underscores");
        check(ordinary.selection(0,plain.length()).equals(basic),"existing exact whole-document copy");
        int bold=plain.indexOf("Жирный");check(ordinary.selection(bold,bold+6).equals("**Жирный**"),"existing partial bold copy retains delimiters");

        String table="| **Критерий** | Значение |\n| :--- | ---: |\n| Срок | 2 дня [1] |\n| Организация | [Источник](https://example.com/a) |\n";
        var doc=new MarkdownText.Document().append(table,true);String text=visible(doc);
        check(text.contains("Критерий")&&text.contains("Срок")&&text.contains("2 дня [1]")&&text.contains("Источник"),"table keeps all body cells and citation");
        check(!text.contains("| :---")&&!text.contains("**")&&text.contains("│")&&text.contains("─"),"table syntax replaced by native aligned layout");
        check((format(doc,text.indexOf("Критерий"))&65)==65,"monospace bold table heading style");
        check(doc.selection(0,text.length()).equals(table),"table whole copy restores exact pipes separator emphasis URL and newline");
        String windowsTable=table.replace("\n","\r\n");var windowsDoc=new MarkdownText.Document().append(windowsTable,true);
        check(windowsDoc.selection(0,visible(windowsDoc).length()).equals(windowsTable),"table copy preserves original Windows CRLF exactly");

        String left=String.join(" ",java.util.stream.IntStream.rangeClosed(1,40).mapToObj(i->"left"+i).toList());
        String longTable="| Левая | Правая |\n| --- | --- |\n| "+left+" | RIGHT [2] |";
        var wrapped=new MarkdownText.Document().append(longTable,true);String longText=visible(wrapped);
        for(int i=1;i<=40;i++)check(longText.matches("(?s).*\\bleft"+i+"\\b.*"),"wrapped word survives "+i);
        check(longText.lines().count()>5,"long cell actually wraps");
        check(longText.lines().allMatch(line->line.codePointCount(0,line.length())<=76),"bounded monospace line width");
        int right=longText.indexOf("RIGHT"),tail=longText.indexOf("left30");
        check(right<tail,"fixture exercises nonmonotonic source anchors");
        String selected=wrapped.selection(right,tail+6);
        check(selected.contains("RIGHT")&&selected.contains("left30"),"interleaved visual selection returns enclosing original span safely");
        check(wrapped.selection(tail+6,right).equals(selected),"reversed visual bounds normalize safely");
        check(wrapped.selection(-10,Integer.MAX_VALUE).equals(longTable),"selection bounds clamp without out-of-range substring");
        check(wrapped.selection(-3,-1).isEmpty(),"empty clamped selection");

        String escaped="| A | B |\n| --- | --- |\n| A\\|B и `x|y` | **жирно** / *курсив* / ~~нет~~ / `код` [3] |";
        var escapes=new MarkdownText.Document().append(escaped,true);String escapedText=visible(escapes);
        check(escapedText.contains("A|B")&&escapedText.contains("x|y")&&escapedText.contains("[3]"),"escaped and inline-code pipes do not split cells");
        check((format(escapes,escapedText.indexOf("жирно"))&1)!=0&&(format(escapes,escapedText.indexOf("курсив"))&2)!=0,"inline emphasis inside table");
        check((format(escapes,escapedText.indexOf("код"))&4)!=0,"inline code style inside table");
        check(escapes.selection(0,escapedText.length()).equals(escaped),"escaped table original copy");
        var literalCode=new MarkdownText.Document().append("| A | B |\n| --- | --- |\n| `<br>` | незакрытый` знак |",true);
        check(visible(literalCode).contains("<br>")&&visible(literalCode).contains("незакрытый` знак"),"inline-code break and unmatched backtick remain literal cell content");
        check(!visible(literalCode).contains("| --- |"),"unmatched cell backtick does not swallow table delimiter");

        String html="| Ереже1 | Ереже2 |\n| --- | --- |\n| Абзац1 [1]<br><br>Абзац2 &lt;мән&gt; &amp; сөз | Бірінші<br/>Екінші &lt;br&gt; &quot;дәйек&quot; &#39;сөз&#39; |";
        var htmlDoc=new MarkdownText.Document().append(html,true);String htmlText=visible(htmlDoc);
        check(htmlText.contains("Абзац1 [1]")&&htmlText.contains("Абзац2 <мән> & сөз")&&htmlText.contains("Екінші <br> \"дәйек\" 'сөз'"),"producer br and escaped literal HTML preserve source content");
        check(htmlText.lines().count()>=5&&!htmlText.contains("<br/>")&&!htmlText.contains("&lt;"),"producer breaks create aligned rows with blank paragraph");
        check(htmlDoc.selection(0,htmlText.length()).equals(html),"producer HTML/line-break protocol copied exactly as original Markdown");

        String emoji="😀".repeat(22)+" аяқталды";
        String unicode="| Атау | Мән |\n| --- | --- |\n| "+emoji+" | e\u0301 Қазақша [4] |";
        var unicodeDoc=new MarkdownText.Document().append(unicode,true);String unicodeText=visible(unicodeDoc);
        check(paired(unicodeText)&&unicodeText.contains("аяқталды")&&unicodeText.contains("e\u0301 Қазақша [4]"),"Unicode wrapping preserves surrogate pairs combining characters and Kazakh");
        int face=unicodeText.indexOf("😀");check(paired(unicodeDoc.selection(face+1,face+2)),"partial UTF16 selection expands to a complete surrogate pair");
        check(unicodeDoc.selection(0,unicodeText.length()).equals(unicode),"Unicode exact original copy");

        String three="A | B | C\n--- | --- | ---\nбір | два | three [5]";
        var multi=new MarkdownText.Document().append(three,true);check(visible(multi).contains("бір")&&visible(multi).contains("three [5]")&&!visible(multi).contains("---"),"three-column table without outer pipes");
        String fenced="```\n"+table+"```";var code=new MarkdownText.Document().append(fenced,true);
        check(visible(code).contains("| :--- | ---: |"),"fenced table remains literal code");
        String malformed="| A | B |\n| -- | --- |\n| x | y |";var bad=new MarkdownText.Document().append(malformed,true);
        check(visible(bad).contains("| -- | --- |"),"malformed separator is not consumed");
        String wide="A|B|C|D|E|F|G\n---|---|---|---|---|---|---\na|b|c|d|e|f|g";
        var bounded=new MarkdownText.Document().append(wide,true);check(visible(bounded).contains("A|B|C|D|E|F|G"),"unsupported wide table retains literal content");
        var appended=new MarkdownText.Document().append("Префикс\n").append(table,true).append("Хвост");
        check(appended.selection(0,visible(appended).length()).equals("Префикс\n"+table+"Хвост"),"mixed document appends preserve whole clipboard original");
        System.out.println("MarkdownDocumentTest: "+checks+" PASS; full7 classpath; no Display/model/GUI");
    }
}
