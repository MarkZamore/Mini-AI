package ai.enterprise.contracts;

import java.util.*;

/** Small strict in-memory JSON codec; no reflection, resource discovery, network or temporary files. */
public final class Json {
    private Json() {}
    public static Object parse(String text) { return new Parser(text).parse(); }
    @SuppressWarnings("unchecked") public static Map<String,Object> object(String text) { Object value=parse(text); if(!(value instanceof Map)) throw new IllegalArgumentException("Expected JSON object"); return (Map<String,Object>)value; }
    @SuppressWarnings("unchecked") public static Map<String,Object> map(Object value) { if(!(value instanceof Map)) throw new IllegalArgumentException("Expected JSON object"); return (Map<String,Object>)value; }
    public static String string(Object value) { return value==null?"":value.toString(); }
    public static long integer(Object value) { return value instanceof Number n?n.longValue():Long.parseLong(string(value)); }
    public static String stringify(Object value) { var out=new StringBuilder(); write(out,value); return out.toString(); }
    private static void write(StringBuilder out,Object value) {
        if(value==null) { out.append("null"); return; }
        if(value instanceof String || value instanceof java.nio.file.Path) { quote(out,value.toString()); return; }
        if(value instanceof Boolean || value instanceof Number) { String n=value.toString(); if(n.equals("NaN")||n.contains("Infinity")) throw new IllegalArgumentException("Non-finite number"); out.append(n); return; }
        if(value instanceof Map<?,?> map) { out.append('{'); boolean first=true; for(var e:map.entrySet()) { if(!first) out.append(','); first=false; quote(out,e.getKey().toString()); out.append(':'); write(out,e.getValue()); } out.append('}'); return; }
        if(value instanceof Iterable<?> list) { out.append('['); boolean first=true; for(var item:list) { if(!first) out.append(','); first=false; write(out,item); } out.append(']'); return; }
        if(value instanceof Api.Fragment f) { write(out,f.fields()); return; }
        if(value instanceof Api.SourceRef r) { write(out,r.fields()); return; }
        if(value instanceof Api.SearchHit h) { var map=new LinkedHashMap<>(h.fragment().fields()); map.put("score",h.score()); write(out,map); return; }
        if(value instanceof Api.GraphEdge e) { write(out,e.fields()); return; }
        if(value instanceof Api.DocumentSummary d) { write(out,Map.of("document_id",d.documentId(),"title",d.title(),"source_sha256",d.sourceSha256(),"source_path",d.sourcePath(),"json_path",d.jsonPath(),"status",d.status(),"sections",d.sections(),"warnings",d.warnings())); return; }
        if(value instanceof Api.GraphNode n) { write(out,Map.of("document",n.document(),"degree",n.degree(),"radius",n.radius())); return; }
        if(value instanceof Api.GraphSnapshot g) { write(out,Map.of("nodes",g.nodes(),"edges",g.edges(),"unresolved_count",g.unresolvedCount())); return; }
        if(value instanceof Api.SourceResolution r) { write(out,Map.of("document_id",r.documentId(),"original_path",r.originalPath(),"path",r.path(),"source_sha256",r.sourceSha256(),"locator",r.locator(),"derivation_chain",r.derivationChain(),"verified",r.verified())); return; }
        if(value instanceof Api.DerivationStep d) { write(out,Map.of("document_id",d.documentId(),"artifact_id",d.artifactId(),"path",d.path(),"sha256",d.sha256(),"derived_from",d.derivedFrom())); return; }
        if(value instanceof Api.ServiceError e) { write(out,Map.of("code",e.code(),"message",e.message())); return; }
        if(value instanceof Api.Result<?> r) { var map=new LinkedHashMap<String,Object>(); map.put("success",r.success()); map.put("value",r.value()); map.put("error",r.error()); write(out,map); return; }
        if(value instanceof Api.ModelMessage m) { write(out,Map.of("role",m.role(),"content",m.content())); return; }
        throw new IllegalArgumentException("JSON type unsupported: "+value.getClass().getName());
    }
    private static void quote(StringBuilder out,String text) { out.append('"'); for(int i=0;i<text.length();i++) { char c=text.charAt(i); switch(c) { case '"'->out.append("\\\""); case '\\'->out.append("\\\\"); case '\n'->out.append("\\n"); case '\r'->out.append("\\r"); case '\t'->out.append("\\t"); case '\b'->out.append("\\b"); case '\f'->out.append("\\f"); default->{ if(c<32) out.append(String.format(Locale.ROOT,"\\u%04x",(int)c)); else out.append(c); } } } out.append('"'); }
    private static final class Parser {
        private final String text; private int index; private int depth;
        Parser(String text) { this.text=Objects.requireNonNull(text); }
        Object parse() { Object value=value(); whitespace(); if(index!=text.length()) fail(); return value; }
        void whitespace() { while(index<text.length() && " \n\r\t".indexOf(text.charAt(index))>=0) index++; }
        void fail() { throw new IllegalArgumentException("Invalid JSON at "+index); }
        char next() { if(index>=text.length()) { fail(); return 0; } return text.charAt(index++); }
        Object value() { whitespace(); if(++depth>128) throw new IllegalArgumentException("JSON nesting limit"); try { if(index>=text.length()) { fail(); return null; } return switch(text.charAt(index)) { case '{'->object(); case '['->array(); case '"'->string(); case 't'->literal("true",true); case 'f'->literal("false",false); case 'n'->literal("null",null); default->number(); }; } finally { depth--; } }
        Object literal(String word,Object value) { if(!text.startsWith(word,index)) fail(); index+=word.length(); return value; }
        Map<String,Object> object() { next(); var map=new LinkedHashMap<String,Object>(); whitespace(); if(index<text.length()&&text.charAt(index)=='}') { index++; return map; } while(true) { whitespace(); if(index>=text.length()||text.charAt(index)!='"') fail(); String key=string(); whitespace(); if(next()!=':') fail(); if(map.containsKey(key)) throw new IllegalArgumentException("Duplicate JSON key: "+key); map.put(key,value()); whitespace(); char c=next(); if(c=='}') return map; if(c!=',') fail(); } }
        List<Object> array() { next(); var list=new ArrayList<Object>(); whitespace(); if(index<text.length()&&text.charAt(index)==']') { index++; return list; } while(true) { list.add(value()); whitespace(); char c=next(); if(c==']') return list; if(c!=',') fail(); } }
        String string() { if(next()!='"') fail(); var out=new StringBuilder(); while(true) { char c=next(); if(c=='"') return out.toString(); if(c<32) fail(); if(c=='\\') { char e=next(); switch(e) { case '"','\\','/'->out.append(e); case 'b'->out.append('\b'); case 'f'->out.append('\f'); case 'n'->out.append('\n'); case 'r'->out.append('\r'); case 't'->out.append('\t'); case 'u'->{ if(index+4>text.length()) fail(); try { out.append((char)Integer.parseInt(text.substring(index,index+4),16)); } catch(NumberFormatException ex) { fail(); } index+=4; } default->fail(); } } else out.append(c); } }
        Number number() { int start=index; if(index<text.length()&&text.charAt(index)=='-') index++; if(index>=text.length()) fail(); if(text.charAt(index)=='0') index++; else { if(text.charAt(index)<'1'||text.charAt(index)>'9') fail(); while(index<text.length()&&Character.isDigit(text.charAt(index))&&text.charAt(index)<=127) index++; } boolean decimal=false; if(index<text.length()&&text.charAt(index)=='.') { decimal=true; index++; int fraction=index; while(index<text.length()&&text.charAt(index)>='0'&&text.charAt(index)<='9') index++; if(index==fraction) fail(); } if(index<text.length()&&(text.charAt(index)=='e'||text.charAt(index)=='E')) { decimal=true; index++; if(index<text.length()&&(text.charAt(index)=='+'||text.charAt(index)=='-')) index++; int exponent=index; while(index<text.length()&&text.charAt(index)>='0'&&text.charAt(index)<='9') index++; if(index==exponent) fail(); } String token=text.substring(start,index); try { if(decimal) { double n=Double.parseDouble(token); if(!Double.isFinite(n)) fail(); return n; } return Long.parseLong(token); } catch(NumberFormatException e) { return new java.math.BigDecimal(token); } }
    }
}
