package org.monitoring.catchholebackend.domain.validation.processor;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** v1 Python collect_clues semantics: ASCII numbers, Unicode whitespace, code-point sorted unique spans. */
@Component
public class ValidationClueCollector {
    private record Match(int start,int end,String kind) {}
    private static final Map<String,Pattern> PATTERNS=Map.of(
            "NUMBER_DATE",Pattern.compile("(?:[0-9]+(?:[.,][0-9]+)?|한|두|세|네|다섯|여섯|일곱|여덟|아홉|열|[일이삼사오육칠팔구십백천만]+)(?:\\s|[\\u001c-\\u001f])*(?:년|월|일|시|분|초|살|세|명|개|번|회|권|마리|자루|킬로|미터)",Pattern.UNICODE_CHARACTER_CLASS),
            "RELATIVE_TIME",Pattern.compile("이틀|사흘|나흘|닷새|엿새|일주일|보름"),
            "WEEKDAY",Pattern.compile("[월화수목금토일]요일"));
    public ArrayNode collectValidationClues(String text,Collection<String> names) {
        var matches=new TreeSet<Match>(Comparator.comparingInt(Match::start).thenComparingInt(Match::end).thenComparing(Match::kind));
        int[] positions=new int[text.length()+1];int points=0;for(int index=0;index<text.length();) {int size=Character.charCount(text.codePointAt(index));positions[index]=points;if(size==2)positions[index+1]=points;index+=size;positions[index]=++points;}
        PATTERNS.forEach((kind,pattern)->{var matcher=pattern.matcher(text);while(matcher.find())matches.add(new Match(positions[matcher.start()],positions[matcher.end()],kind));});
        for(String name:new LinkedHashSet<>(names))if(name!=null&&!name.isEmpty()) {
            var found=new ArrayList<Match>();var matcher=Pattern.compile(Pattern.quote(name)).matcher(text);while(matcher.find())found.add(new Match(positions[matcher.start()],positions[matcher.end()],"REPEATED_NAME"));if(found.size()>1)matches.addAll(found);
        }
        var result=JsonNodeFactory.instance.arrayNode();for(var match:matches) {
            String ref="CL"+(result.size()+1);var item=result.addObject().put("ref",ref).put("kind",match.kind());item.putObject("span").put("source_ref","T1").put("start",match.start()).put("end",match.end()).put("quote",text.substring(text.offsetByCodePoints(0,match.start()),text.offsetByCodePoints(0,match.end())));
        }return result;
    }
}
