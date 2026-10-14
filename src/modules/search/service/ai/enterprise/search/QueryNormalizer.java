package ai.enterprise.search;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Python 3.12/Unicode 15.0 NFC, casefold and str.isalnum equivalence. */
public final class QueryNormalizer {
    private QueryNormalizer() {}
    private static final class Tables {
        static final Map<Integer,int[]> FOLD=mapping("casefold.txt");
        static final Map<Integer,int[]> DECOMP=mapping("canonical.txt");
        static final Map<Integer,Integer> CCC=integers("combining.txt");
        static final Map<Long,Integer> COMPOSE=composition();
        static final int[] ALNUM=ranges();
    }
    private static List<String> lines(String name) {
        try(var input=QueryNormalizer.class.getResourceAsStream("/ai/enterprise/search/unicode15/"+name)) {
            if(input==null)throw new IllegalStateException("Missing bundled Unicode 15 table: "+name);
            return new String(input.readAllBytes(),StandardCharsets.US_ASCII).lines().filter(s->!s.isBlank()&&!s.startsWith("#")).toList();
        } catch(IOException e){throw new IllegalStateException("Cannot read bundled Unicode tables",e);}
    }
    private static int hex(String text){return Integer.parseInt(text,16);}
    private static Map<Integer,int[]> mapping(String name) {
        var result=new HashMap<Integer,int[]>();
        for(String line:lines(name)){String[] p=line.split(";");result.put(hex(p[0]),Arrays.stream(p[1].split(" ")).mapToInt(QueryNormalizer::hex).toArray());}
        return Map.copyOf(result);
    }
    private static Map<Integer,Integer> integers(String name) {
        var result=new HashMap<Integer,Integer>();for(String line:lines(name)){String[] p=line.split(";");result.put(hex(p[0]),Integer.parseInt(p[1]));}return Map.copyOf(result);
    }
    private static Map<Long,Integer> composition() {
        var result=new HashMap<Long,Integer>();for(String line:lines("composition.txt")){String[] p=line.split(";");String[] pair=p[0].split(" ");result.put(pair(hex(pair[0]),hex(pair[1])),hex(p[1]));}return Map.copyOf(result);
    }
    private static int[] ranges(){return lines("alnum.txt").stream().flatMap(s->Arrays.stream(s.split(";"))).mapToInt(QueryNormalizer::hex).toArray();}
    private static long pair(int a,int b){return ((long)a<<21)|b;}
    private static int combining(int cp){return Tables.CCC.getOrDefault(cp,0);}
    private static boolean alnum(int cp) {
        int low=0,high=Tables.ALNUM.length/2-1;
        while(low<=high){int m=(low+high)>>>1;int start=Tables.ALNUM[m*2],end=Tables.ALNUM[m*2+1];if(cp<start)high=m-1;else if(cp>end)low=m+1;else return true;}return false;
    }
    private static void decompose(int cp,List<Integer> output) {
        int si=cp-0xAC00;
        if(si>=0&&si<11172){output.add(0x1100+si/588);output.add(0x1161+(si%588)/28);if(si%28!=0)output.add(0x11A7+si%28);return;}
        int[] decomposed=Tables.DECOMP.get(cp);if(decomposed==null)output.add(cp);else for(int value:decomposed)decompose(value,output);
    }
    private static int composite(int a,int b) {
        if(a>=0x1100&&a<0x1113&&b>=0x1161&&b<0x1176)return 0xAC00+((a-0x1100)*21+b-0x1161)*28;
        if(a>=0xAC00&&a<0xD7A4&&(a-0xAC00)%28==0&&b>0x11A7&&b<0x11C3)return a+b-0x11A7;
        return Tables.COMPOSE.getOrDefault(pair(a,b),-1);
    }
    public static String nfc(String input) {
        Objects.requireNonNull(input);var decomposed=new ArrayList<Integer>(input.length());input.codePoints().forEach(cp->decompose(cp,decomposed));
        for(int i=1;i<decomposed.size();i++){int cp=decomposed.get(i),cc=combining(cp);if(cc==0)continue;int j=i;while(j>0&&combining(decomposed.get(j-1))>cc){decomposed.set(j,decomposed.get(j-1));j--;}decomposed.set(j,cp);}
        if(decomposed.isEmpty())return "";
        var composed=new ArrayList<Integer>(decomposed.size());composed.add(decomposed.getFirst());int starter=0,last=combining(decomposed.getFirst());
        for(int i=1;i<decomposed.size();i++){int cp=decomposed.get(i),cc=combining(cp),joined=composite(composed.get(starter),cp);
            if(joined>=0&&(last<cc||last==0))composed.set(starter,joined);
            else{if(cc==0)starter=composed.size();composed.add(cp);last=cc;}}
        var out=new StringBuilder(input.length());for(int cp:composed)out.appendCodePoint(cp);return out.toString();
    }
    public static List<String> tokens(String input) {
        var folded=new StringBuilder(input.length());nfc(input).codePoints().forEach(cp->{int[] fold=Tables.FOLD.get(cp);if(fold==null)folded.appendCodePoint(cp);else for(int value:fold)folded.appendCodePoint(value);});
        var words=new ArrayList<String>();var word=new StringBuilder();folded.codePoints().forEach(cp->{if(cp!=95&&alnum(cp))word.appendCodePoint(cp);else if(!word.isEmpty()){words.add(word.toString());word.setLength(0);}});
        if(!word.isEmpty())words.add(word.toString());return List.copyOf(words);
    }
    public static String stem(String word) {
        if(!word.isEmpty()&&word.codePoints().allMatch(cp->cp>=0x430&&cp<=0x44f||cp==0x451))return RussianStemmer.stem(word);
        return word;
    }
    public static String matchExpression(String input) {
        return tokens(input).stream().limit(30).map(word->"(\""+word+"\" OR stemmed:\""+stem(word)+"\")").reduce((a,b)->a+" AND "+b).orElse("");
    }
}
