package ai.enterprise.tests;

import ai.enterprise.assistant.SourcePresentation;
import ai.enterprise.contracts.Api;
import ai.enterprise.contracts.Json;
import ai.enterprise.generated.GeneratedCatalog;
import java.util.*;

/** Pure display contracts on the complete seven-module classpath; no SWT or model launch. */
public final class SourcePresentationTest {
    private static int assertions;
    private static final String SUFFIX="\n\nОграничения источников: ";
    private static final Map<String,Object> HUMAN=Map.of("verification","human_verified","needs_review",false);
    private static Api.Fragment fragment(String id,Map<String,Object> quality) {
        return new Api.Fragment(Map.of("fragment_id",id,"document_id","document-"+id,"text","Исходное условие", "title","Документ",
            "quality",quality,"warnings",List.of(),"source_refs",List.of(Map.of("kind","pdf_region","page",2))));
    }
    private static String show(Api.Fragment fragment,String warning) {
        return SourcePresentation.answer("Название организации. [source:"+fragment.fragmentId()+"]"+(warning.isEmpty()?"":SUFFIX+warning),List.of(fragment));
    }
    public static void main(String[] args) {
        check(GeneratedCatalog.modules().stream().map(Api.ModuleFactory::id).collect(java.util.stream.Collectors.toSet())
            .equals(Set.of("assistant","database","documents","graph","inference","search","sources")),"Current complete seven-module classpath");
        Api.Fragment good=fragment("good",HUMAN);
        check(show(good,"").equals("Название организации. [1]"),"Clean reviewed evidence adds no service note");
        String partial=show(good,"документ имеет статус partial");
        check(partial.equals("Название организации. [1]"),"The useful answer has no routine service footer");
        check(show(good,"Blocks outside visible page bounds on page 3; inspect source.").equals("Название организации. [1]"),"Page extraction geometry stays in source details");
        String technical="XML locators refer to preserved LibreOffice DOCX, not original binary/RTF page positions.; Recovered by 400 DPI sparse-text segmentation after empty OCR; requires local review.; Page 9 received a 400 DPI sparse-text retry; original empty result is retained.";
        check(show(good,technical).equals("Название организации. [1]"),"Known XML/OCR retry diagnostics stay out of the answer");
        check(SourcePresentation.warningDetails("Ответ"+SUFFIX+technical).endsWith(technical),"The complete technical history remains available in source details");
        check(show(good,"Срок действия документа истёк; проверьте редакцию.").contains("Срок действия документа истёк"),"A substantive applicability restriction is not extraction noise");
        check(!partial.contains("полноту ответа")&&!partial.contains("ответ неполн"),"Document partial does not fabricate incomplete answer wording");
        check(!partial.contains("требуют сверки")&&!partial.contains("Ручная сверка"),"Document status does not invent a defect or unreviewed status for the cited fragment");
        Api.Fragment machine=fragment("machine",Map.of("verification","machine_extracted","needs_review",false));
        String unreviewed=show(machine,"машинное извлечение/OCR не подтверждено ручной сверкой");
        check(!unreviewed.contains("Примечание")&&!unreviewed.contains("Ручная сверка"),"Routine review metadata belongs to the source card, not the answer body");
        check(SourcePresentation.locator(machine).contains("ручная сверка не отмечена")&&!SourcePresentation.locator(machine).contains("требуется сверка"),"Source locator distinguishes absence of manual review from a specific defect");
        Api.Fragment needs=fragment("needs",Map.of("verification","machine_extracted","needs_review",true));
        String review=show(needs,"OCR needs_review partial");
        check(!review.contains("needs_review")&&!review.contains("Примечание")&&!review.contains("Документ извлечён частично"),"Extraction flags stay in source cards without becoming response prose");
        check(SourcePresentation.locator(needs).contains("требуется сверка"),"Explicit quality defect stays visible on source location");
        check(SourcePresentation.locator(fragment("low",Map.of("verification","machine_extracted","low_confidence",true))).contains("требуется сверка"),"Explicit low-confidence flag remains visible on source location");
        check(SourcePresentation.locator(fragment("low-label",Map.of("verification","machine_extracted","confidence","low"))).contains("требуется сверка"),"Explicit low-confidence label remains visible on source location");
        check(!show(fragment("uncalibrated",Map.of("verification","machine_extracted","confidence",0.78)),"").contains("требуют сверки"),"UI does not invent a new numerical confidence threshold");
        String truncated=show(fragment("part",Map.of("verification","machine_extracted","partial",true)),"");
        check(!truncated.contains("Примечание")&&SourcePresentation.locator(fragment("part",Map.of("verification","machine_extracted","partial",true))).contains("требуется сверка"),"Partial-fragment concern stays visible in its source card");
        String unrelated=SourcePresentation.answer("Ответ [source:good]"+SUFFIX+"есть фрагменты, требующие сверки",List.of(needs,good));
        check(unrelated.equals("Ответ [2]"),"Unused low-quality search hits do not become a warning about the cited reviewed fragment; numbering stays stable");
        String failed=show(good,"документ имеет статус failed; Критическая ошибка источника: SOURCE_CHANGED");
        check(failed.contains("завершилось ошибкой")&&failed.contains("Критическая ошибка источника: SOURCE_CHANGED"),"Failed extraction and exact critical restriction remain visible");
        String critical="Неизвестная ссылка [source:missing] — INVALID_CITATION";
        String unknown=SourcePresentation.answer("Ответ [source:missing]"+SUFFIX+critical,List.of(good));
        check(unknown.contains("Ответ [source:missing]")&&unknown.endsWith(critical),"Unknown citation tokens and unknown/critical warning text are never hidden");
        String wording="Редакция partial не подтверждена";
        check(show(good,wording).endsWith(wording)&&!show(good,wording).contains("Документ извлечён частично"),"An arbitrary occurrence of partial does not rewrite the warning's meaning");
        String canonical="Условие. [source:needs]"+SUFFIX+"OCR needs_review partial";
        String before=Json.stringify(needs.fields());
        SourcePresentation.answer(canonical,List.of(needs));
        check(SourcePresentation.warningDetails(canonical).equals("Ограничения источников: OCR needs_review partial")&&Json.stringify(needs.fields()).equals(before),"Full canonical diagnostics and source metadata remain unchanged for the source card");
        System.out.println("Source presentation checks passed: "+assertions+"; full seven-module classpath; no GUI/model/build launch");
    }
    private static void check(boolean condition,String message) {assertions++;if(!condition)throw new AssertionError(message);}
}
