package org.monitoring.catchholebackend.domain.validation.processor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.analysis.processor.AnalysisStateJournal;
import org.monitoring.catchholebackend.domain.validation.entity.ValidationCheck;
import org.monitoring.catchholebackend.domain.validation.mapper.ValidationDataMapper;
import org.monitoring.catchholebackend.domain.validation.processor.ValidationPublicationValidator.Prepared;
import org.springframework.stereotype.Component;
import static org.monitoring.catchholebackend.domain.validation.processor.ValidationExecutionAccess.error;
import static org.monitoring.catchholebackend.domain.validation.exception.ValidationErrorCode.*;

/** Structural and durable completion proof; the worker owns semantic judging, never authorization. */
@Component @RequiredArgsConstructor
public class ValidationDecisionValidator {
    private final ValidationDataMapper frozen;
    private final AnalysisStateJournal journal;
    private static final Set<String> OUTCOMES=Set.of("CONFLICT","SUSPECTED_CONFLICT","NEEDS_CONTEXT","NO_CONFLICT");
    public void validate(Prepared p,ValidationCheck check,String outcome,JsonNode decision) {
        var bundle=decision.path("evidence_bundle");var question=check.getState().path("draft");var knownSettings=frozen.toValidationSettings(p.context().entries(),p.context().sourceReferences());
        if(!OUTCOMES.contains(outcome)||!"DECIDED".equals(decision.path("action").asText())||!outcome.equals(decision.path("outcome").asText())||!nonblank(decision,"reason")||!decision.path("completion").isObject()
                ||!bundle.isObject()||!bundle.path("input_digest").asText().matches("[a-f0-9]{64}")||!check.getLocalRef().equals(bundle.path("question_ref").asText())||check.getRevision()!=bundle.path("check_revision").asLong()
                ||!same(question,bundle.path("question"))||!sameSet(question.path("target_spans"),decision.path("target_spans"))
                ||!emptyArray(decision.path("follow_ups"))||!emptyArray(decision.path("affected_question_refs")))throw error(VALIDATION_RESULT_INVALID);
        if(!same(frozen.toValidationSourceCatalogue(p.context().sourceReferences()),bundle.path("source_catalogue")))throw error(VALIDATION_RESULT_INVALID);
        Set<String> facts=new HashSet<>(),known=new HashSet<>();known.add("T1");
        if(!bundle.path("settings").isArray()||!bundle.path("evidence").isArray()||!bundle.path("receipts").isArray()||!bundle.path("target_context").isArray()||bundle.path("target_context").isEmpty()||!bundle.path("additional_target_context").isArray())throw error(VALIDATION_RESULT_INVALID);
        for(var setting:bundle.path("settings")) {
            String ref=setting.path("entry_ref").asText();var actual=knownSettings.get(ref);
            if(actual==null||!same(actual,setting)||!facts.add(ref))throw error(VALIDATION_RESULT_INVALID);
        }
        Set<String> neededSettings=new HashSet<>();for(var entry:knownSettings.entrySet())for(var subject:question.path("subject_refs"))if(subject.asText().equals(entry.getValue().path("subject_ref").asText()))neededSettings.add(entry.getKey());
        for(var intent:check.getState().path("lookup_obligations"))if("BASELINE".equals(intent.path("operation").asText()))for(var ref:intent.path("evidence_refs"))neededSettings.add(ref.asText());
        if(!facts.equals(neededSettings))throw error(VALIDATION_RESULT_INVALID);known.addAll(facts);
        Set<String> evidence=new HashSet<>(),comparisonEvidence=new HashSet<>();for(var row:bundle.path("evidence")) {
            String ref=row.path("ref").asText();var source=p.context().sourceReferences().get(row.path("source_ref").asText());
            if(!ref.matches("E[1-9][0-9]*")||!evidence.add(ref)||source==null||!source.path("sourceKind").asText().equals(row.path("source_kind").asText())||!row.has("quote"))throw error(VALIDATION_RESULT_INVALID);
            if(!"T1".equals(row.path("source_ref").asText()))comparisonEvidence.add(ref);
        }known.addAll(evidence);
        Set<String> receiptRefs=new HashSet<>();var receipts=new HashMap<String,JsonNode>();boolean baseline=false,target=false;
        for(var receipt:bundle.path("receipts")) {
            String ref=receipt.path("ref").asText();var intent=receipt.path("intent");validateIntent(intent);
            if(ref.isBlank()||!receiptRefs.add(ref)||!"SUCCEEDED".equals(receipt.path("status").asText())||receipt.hasNonNull("failure_code")||!strings(receipt.path("evidence_refs"),known,false))throw error(VALIDATION_RESULT_INVALID);
            receipts.put(ref,receipt);baseline|="BASELINE".equals(intent.path("operation").asText())&&"COMPARISON_CONTEXT".equals(intent.path("kind").asText());target|="TARGET_CONTEXT".equals(intent.path("operation").asText())&&"COMPARISON_CONTEXT".equals(intent.path("kind").asText());
        }
        if(!baseline||!target)throw error(VALIDATION_RESULT_INVALID);
        for(var obligation:check.getState().path("lookup_obligations"))if(receipts.values().stream().noneMatch(r->same(normalizeIntent(obligation),normalizeIntent(r.path("intent")))))throw error(VALIDATION_RESULT_INVALID);
        for(String field:List.of("comparison_context","changes_exceptions","no_pending_clues","required_reads")) {
            var a=decision.path("completion").path(field);
            if(!nonblank(a,"reason")||!strings(a.path("evidence_refs"),known,true)||!strings(a.path("receipt_refs"),receiptRefs,true))throw error(VALIDATION_RESULT_INVALID);
        }
        if(!textSet(decision.path("completion").path("required_reads").path("receipt_refs")).equals(receiptRefs)||!textSet(decision.path("completion").path("comparison_context").path("evidence_refs")).contains("T1")||!strings(decision.path("evidence_refs"),known,false))throw error(VALIDATION_RESULT_INVALID);
        if(outcome.equals("SUSPECTED_CONFLICT")&&textSet(decision.path("completion").path("changes_exceptions").path("receipt_refs")).stream().map(receipts::get).noneMatch(r->"CHANGES_EXCEPTIONS".equals(r.path("intent").path("kind").asText())&&Set.of("KEYWORD","VECTOR").contains(r.path("intent").path("operation").asText())))throw error(VALIDATION_RESULT_INVALID);
        String expected=Map.of("CONFLICT","DIRECT_CONTRADICTION","SUSPECTED_CONFLICT","CHANGE_NOT_FOUND","NEEDS_CONTEXT","AMBIGUOUS_MEANING","NO_CONFLICT","COMPATIBLE").get(outcome);
        if(!Set.of(expected,"DB_RAW_DISAGREEMENT").contains(decision.path("basis").asText()))throw error(VALIDATION_RESULT_INVALID);
        if(outcome.equals("NO_CONFLICT")) {if(decision.hasNonNull("impact")||decision.hasNonNull("impact_reason"))throw error(VALIDATION_RESULT_INVALID);}
        else if(!Set.of("SEVERE","CAUTION","REFERENCE").contains(decision.path("impact").asText())||!nonblank(decision,"impact_reason"))throw error(VALIDATION_RESULT_INVALID);
        if(Set.of("CONFLICT","SUSPECTED_CONFLICT").contains(outcome)&&textSet(decision.path("evidence_refs")).stream().noneMatch(ref->facts.contains(ref)||comparisonEvidence.contains(ref)))throw error(VALIDATION_RESULT_INVALID);
        if(!decision.path("disagreements").isArray()||"DB_RAW_DISAGREEMENT".equals(decision.path("basis").asText())&&decision.path("disagreements").isEmpty())throw error(VALIDATION_RESULT_INVALID);
        for(var disagreement:decision.path("disagreements")) {
            if(!facts.contains(disagreement.path("fact_ref").asText())||!evidence.contains(disagreement.path("evidence_ref").asText())||!nonblank(disagreement,"reason"))throw error(VALIDATION_RESULT_INVALID);
            // A free-text model correction ref cannot establish the typed server correction's time/condition scope.
            if(!"NEEDS_CONTEXT".equals(outcome))throw error(VALIDATION_RESULT_INVALID);
        }
    }
    public JsonNode state(JsonNode state,Set<String> subjects) {
        if(!state.isObject())throw error(VALIDATION_RESULT_INVALID);draft(state.path("draft"),subjects);
        var normalized=(ObjectNode)state.deepCopy();if(!normalized.has("lookup_obligations"))normalized.set("lookup_obligations",state.path("draft").path("lookup_intents").deepCopy());
        if(!normalized.path("lookup_obligations").isArray())throw error(VALIDATION_RESULT_INVALID);
        var obligations=JsonNodeFactory.instance.arrayNode();Set<String> signatures=new HashSet<>();
        for(var intent:normalized.path("lookup_obligations")) {validateIntent(intent);if(signatures.add(journal.hash(normalizeIntent(intent))))obligations.add(intent.deepCopy());}
        for(var intent:state.path("draft").path("lookup_intents"))if(!signatures.contains(journal.hash(normalizeIntent(intent))))throw error(VALIDATION_RESULT_INVALID);
        normalized.set("lookup_obligations",obligations);return normalized;
    }
    public void draft(JsonNode q,Set<String> subjects) {
        if(!q.isObject()||!nonblank(q,"local_ref")||!q.path("target_spans").isArray()||q.path("target_spans").isEmpty()||!strings(q.path("subject_refs"),subjects,false)||!textArray(q.path("subject_mentions"))||!textArray(q.path("conditions"))
                ||!Set.of("NARRATION","DIALOGUE","THOUGHT","HYPOTHETICAL","UNKNOWN").contains(q.path("assertion_mode").asText())||!nonblank(q,"property_or_rule")||!nonblank(q,"time_context")||!nonblank(q,"check_intent")||!nonblank(q,"reason")||!q.path("lookup_intents").isArray())throw error(VALIDATION_RESULT_INVALID);
        for(var span:q.path("target_spans"))if(!"T1".equals(span.path("source_ref").asText())||!span.path("quote").isTextual()||!span.path("start").isIntegralNumber()||!span.path("end").isIntegralNumber())throw error(VALIDATION_RESULT_INVALID);
        for(var intent:q.path("lookup_intents"))validateIntent(intent);
    }
    public Set<String> subjects(Prepared p) {var refs=new HashSet<String>();for(var value:frozen.toValidationSettings(p.context().entries(),p.context().sourceReferences()).values())refs.add(value.path("subject_ref").asText());return refs;}
    public void validateIntent(JsonNode intent) {
        if(!nonblank(intent,"ref")||!nonblank(intent,"purpose")||!Set.of("COMPARISON_CONTEXT","CHANGES_EXCEPTIONS","NEW_CLUE").contains(intent.path("kind").asText())||!Set.of("BASELINE","TARGET_CONTEXT","DIRECT","KEYWORD","VECTOR","NEIGHBORS").contains(intent.path("operation").asText())||!textArray(intent.path("evidence_refs")))throw error(VALIDATION_RESULT_INVALID);
    }
    public boolean same(JsonNode a,JsonNode b) {return journal.hash(a).equals(journal.hash(b));}
    public JsonNode normalizeIntent(JsonNode n) {var r=JsonNodeFactory.instance.objectNode();for(String key:List.of("ref","kind","operation","purpose","query","source_ref","start","end","evidence_refs"))r.set(key,n.has(key)?n.path(key).deepCopy():NullNode.instance);return r;}
    private boolean sameSet(JsonNode a,JsonNode b) {if(!a.isArray()||!b.isArray())return false;Set<String> x=new HashSet<>(),y=new HashSet<>();a.forEach(n->x.add(journal.hash(n)));b.forEach(n->y.add(journal.hash(n)));return a.size()==x.size()&&b.size()==y.size()&&x.equals(y);}
    private static boolean textArray(JsonNode n) {if(!n.isArray())return false;for(var x:n)if(!x.isTextual())return false;return true;}
    private static boolean strings(JsonNode a,Set<String> allowed,boolean required) {return textArray(a)&&(!required||!a.isEmpty())&&allowed.containsAll(textSet(a));}
    private static Set<String> textSet(JsonNode n) {var result=new HashSet<String>();n.forEach(x->result.add(x.asText()));return result;}
    private static boolean emptyArray(JsonNode n) {return n.isArray()&&n.isEmpty();}
    private static boolean nonblank(JsonNode n,String key) {return n.path(key).isTextual()&&!n.path(key).asText().isBlank();}
}
