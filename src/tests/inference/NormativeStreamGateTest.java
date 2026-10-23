package ai.enterprise.assistant;

public final class NormativeStreamGateTest {
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    public static void main(String[] args) {
        var gate=new NormativeStreamGate();
        check(gate.push("Условие из документа ").isEmpty(),"Uncited provisional norm stays buffered");
        check(gate.push("[source:abc]").equals("Условие из документа [source:abc]"),"A valid canonical citation releases a block without waiting for generation end");
        check(gate.push("\n\nНеподтверждённый обязательный срок.").equals("\n\n"),"Next uncited norm is not displayed");
        check(gate.finish().isEmpty()&&gate.omittedUncitedText(),"Uncited normative tail is omitted");
        var separate=new NormativeStreamGate();
        check(separate.push("Неподтверждённое правило.\n\nПодтверждённое [source:xyz]").equals("Подтверждённое [source:xyz]"),"A later citation does not validate an earlier uncited paragraph");
        var advice=new NormativeStreamGate();
        check(advice.push("Общие рекомендации (необязательные): уточните дату.\n\n").startsWith("Общие рекомендации"),"Clearly separated general advice remains visible");
        var punctuation=new NormativeStreamGate();
        check(punctuation.push("Правило [source:abc].").equals("Правило [source:abc]"),"Citation can precede punctuation");
        check(punctuation.finish().equals(".")&&!punctuation.omittedUncitedText(),"Closing punctuation is not misreported as an unsupported norm");
        var paragraphs=new NormativeStreamGate();
        String paragraphsOutput=paragraphs.push("Первое правило [source:abc]")
            +paragraphs.push(".\n\nВторое правило [source:xyz]")+paragraphs.finish();
        check(paragraphsOutput.equals("Первое правило [source:abc].\n\nВторое правило [source:xyz]"),"Punctuation and paragraph boundaries survive citation chunking");
        check(!paragraphs.omittedUncitedText(),"Punctuation between cited paragraphs is not a missing normative block");
        System.out.println("Normative streaming checks passed");
    }
}
