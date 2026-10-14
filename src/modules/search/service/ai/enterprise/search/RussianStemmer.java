package ai.enterprise.search;

import java.util.*;

/** Literal Russian Snowball 3.1.1 suffix/region algorithm; no Kazakh stemming. */
final class RussianStemmer {
    private static final String VOWELS="аеиоуыэюя";
    private record Suffix(String text,boolean precedingAY) {}
    private static List<Suffix> suffixes(String ordinary,String constrained) {
        var result=new ArrayList<Suffix>();
        for(String s:ordinary.split(" ")) if(!s.isEmpty())result.add(new Suffix(s,false));
        for(String s:constrained.split(" ")) if(!s.isEmpty())result.add(new Suffix(s,true));
        result.sort(Comparator.comparingInt((Suffix s)->s.text.length()).reversed());return List.copyOf(result);
    }
    private static final List<Suffix> PERFECT=suffixes("ив ыв ивши ывши ившись ывшись","в вши вшись");
    private static final List<Suffix> ADJECTIVE=suffixes("ее ие ое ые ими ыми ей ий ой ый ем им ом ым его ого ему ому их ых ею ою ую юю ая яя","");
    private static final List<Suffix> PARTICIPLE=suffixes("ивш ывш ующ","ем нн вш щ ющ");
    private static final List<Suffix> REFLEXIVE=suffixes("сь ся","");
    private static final List<Suffix> VERB=suffixes("ила ыла ена ите ейте уйте или ыли ей уй ил ыл им ым ен ило ыло ено ует ит ыт уют ят ены ить ыть ишь ю ую","ла на ете йте ли й л ем н ло но нно ет ют ны ть ешь");
    private static final List<Suffix> NOUN=suffixes("а ев ов е ие ье и еи ии ами ями иями й ей ией ий ой ам ем ием ом ям иям о у ах ях иях ы ь ю ию ью я ия ья","");
    private static final List<Suffix> DERIVE=suffixes("ост ость","");
    private static Suffix match(String word,int lower,List<Suffix> choices) {
        for(Suffix suffix:choices) if(word.length()-suffix.text.length()>=lower && word.endsWith(suffix.text))return suffix;
        return null;
    }
    private static String remove(String word,int lower,List<Suffix> choices) {
        Suffix suffix=match(word,lower,choices);
        if(suffix==null)return word;
        int at=word.length()-suffix.text.length();
        if(suffix.precedingAY && (at<=lower || "ая".indexOf(word.charAt(at-1))<0))return word;
        return word.substring(0,at);
    }
    private static int nextRegion(String word,int start) {
        boolean vowel=false;
        for(int i=start;i<word.length();i++) {
            boolean current=VOWELS.indexOf(word.charAt(i))>=0;
            if(vowel&&!current)return i+1;vowel=current;
        }
        return word.length();
    }
    static String stem(String input) {
        String word=input.replace('ё','е');int rv=word.length();
        for(int i=0;i<word.length();i++) if(VOWELS.indexOf(word.charAt(i))>=0){rv=i+1;break;}
        int r2=nextRegion(word,nextRegion(word,0));
        String changed=remove(word,rv,PERFECT);
        if(changed.equals(word)) {
            word=remove(word,rv,REFLEXIVE);
            changed=remove(word,rv,ADJECTIVE);
            if(!changed.equals(word))word=remove(changed,rv,PARTICIPLE);
            else {changed=remove(word,rv,VERB);word=!changed.equals(word)?changed:remove(word,rv,NOUN);}
        } else word=changed;
        if(word.endsWith("и") && word.length()>rv)word=word.substring(0,word.length()-1);
        Suffix derivation=match(word,rv,DERIVE);
        if(derivation!=null && word.length()-derivation.text.length()>=r2)word=word.substring(0,word.length()-derivation.text.length());
        // Snowball tidy-up chooses its longest applicable suffix before testing doubled н.
        String tidy=word.endsWith("ейше")?"ейше":word.endsWith("ейш")?"ейш":word.endsWith("н")?"н":word.endsWith("ь")?"ь":"";
        if(!tidy.isEmpty() && word.length()-tidy.length()>=rv) {
            if(tidy.startsWith("ейш")) {word=word.substring(0,word.length()-tidy.length());if(word.endsWith("нн")&&word.length()-2>=rv)word=word.substring(0,word.length()-1);}
            else if(tidy.equals("ь"))word=word.substring(0,word.length()-1);
            else if(word.endsWith("нн")&&word.length()-2>=rv)word=word.substring(0,word.length()-1);
        }
        return word;
    }
}
