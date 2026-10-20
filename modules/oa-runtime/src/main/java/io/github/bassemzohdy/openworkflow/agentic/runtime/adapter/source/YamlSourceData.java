package io.github.bassemzohdy.openworkflow.agentic.runtime.adapter.source;

import static io.github.bassemzohdy.openworkflow.agentic.runtime.adapter.source.WorkflowSourceCodec.Code.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.adapter.source.WorkflowSourceCodec.Code;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.io.IOException;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.*;
import org.yaml.snakeyaml.composer.Composer;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.nodes.*;
import org.yaml.snakeyaml.parser.ParserImpl;
import org.yaml.snakeyaml.reader.StreamReader;
import org.yaml.snakeyaml.resolver.Resolver;
import org.yaml.snakeyaml.scanner.ScannerImpl;
import org.yaml.snakeyaml.tokens.*;

/** YAML 1.2 core tag resolution into JSON data, not SnakeYAML load/construct or a runtime class binder.
 * Aliases are expanded with cycle/depth/node/character limits. Core mappings require string keys for JSON.
 * Normative scalar table: https://yaml.org/spec/1.2.2/#1032-tag-resolution . */
final class YamlSourceData {
    private static final Pattern NULL=Pattern.compile("^(?:null|Null|NULL|~|)$");
    private static final Pattern BOOL=Pattern.compile("^(?:true|True|TRUE|false|False|FALSE)$");
    private static final Pattern INT=Pattern.compile("^(?:[-+]?[0-9]+|0o[0-7]+|0x[0-9a-fA-F]+)$");
    private static final Pattern FINITE=Pattern.compile("^[-+]?(?:\\.[0-9]+|[0-9]+(?:\\.[0-9]*)?)(?:[eE][-+]?[0-9]+)?$");
    private static final Pattern FLOAT=Pattern.compile("^(?:[-+]?(?:\\.[0-9]+|[0-9]+(?:\\.[0-9]*)?)(?:[eE][-+]?[0-9]+)?|[-+]?\\.(?:inf|Inf|INF)|\\.(?:nan|NaN|NAN))$");
    private static final Set<Tag> CORE=Set.of(Tag.NULL,Tag.BOOL,Tag.INT,Tag.FLOAT,Tag.STR,Tag.SEQ,Tag.MAP);
    static final class Failure extends IOException {
        final Code code;
        Failure(Code code){super(code.name());this.code=code;}
    }
    private static final class TagRefused extends YAMLException {
        TagRefused(){super("Non-core tag");}
    }
    private static final class CoreResolver extends Resolver {
        @Override protected void addImplicitResolvers() {
            addImplicitResolver(Tag.NULL,YamlSourceData.NULL,"~nN\0");addImplicitResolver(Tag.BOOL,YamlSourceData.BOOL,"tTfF");
            // Resolver's default 1024-character regexp limit must not silently turn a long numeric into a string.
            addImplicitResolver(Tag.INT,YamlSourceData.INT,"-+0123456789",WorkflowSourceCodec.MAX_BYTES);
            addImplicitResolver(Tag.FLOAT,YamlSourceData.FLOAT,"-+0123456789.",WorkflowSourceCodec.MAX_BYTES);
        }
    }
    static JsonNode read(byte[] bytes,int maxNodes,int maxCharacters) throws Failure {
        String source=new String(bytes,StandardCharsets.UTF_8);
        var options=new LoaderOptions();options.setCodePointLimit(maxCharacters);options.setNestingDepthLimit(64);
        options.setMaxAliasesForCollections(maxNodes);options.setAllowDuplicateKeys(false);
        options.setAllowRecursiveKeys(false);options.setMergeOnCompose(false);
        options.setTagInspector(tag->{if(!CORE.contains(tag))throw new TagRefused();return true;});
        try {
            // Scan the bounded stream before building a graph; inspect actual directive tokens, never text regexes.
            var scanner=new ScannerImpl(new StreamReader(source),options);int tokens=0;
            while(true) {
                Token token=scanner.peekToken();if(token==null)break;scanner.getToken();
                if(++tokens>maxNodes)throw new Failure(TOKEN_LIMIT);
                if(token instanceof DirectiveToken<?> directive&&directive.getName().equals("YAML")
                        &&!directive.getValue().equals(List.of(1,2)))throw new Failure(YAML_VERSION_UNSUPPORTED);
                if(token.getTokenId()==Token.ID.StreamEnd)break;
            }
            // Composer resolves graph links only. No Constructor, load(), constructObject or reflection is invoked.
            Node root=new Composer(new ParserImpl(new StreamReader(source),options),new CoreResolver(),options).getSingleNode();
            if(root==null)throw new Failure(MALFORMED_SOURCE);
            return new Expansion(maxNodes,maxCharacters).convert(root,0);
        }catch(TagRefused refused){throw new Failure(YAML_TAG_UNSUPPORTED);}
        catch(YAMLException malformed){throw new Failure(MALFORMED_SOURCE);}
    }
    private static final class Expansion {
        final Set<Node> active=Collections.newSetFromMap(new IdentityHashMap<>());
        int remainingNodes,remainingCharacters;
        Expansion(int nodes,int characters){remainingNodes=nodes;remainingCharacters=characters;}
        JsonNode convert(Node node,int depth) throws Failure {
            if(--remainingNodes<0)throw new Failure(EXPANSION_LIMIT);
            if(depth>64)throw new Failure(EXPANSION_LIMIT);
            if(node instanceof AnchorNode anchor)node=anchor.getRealNode();
            if(!active.add(node))throw new Failure(YAML_CYCLE);
            try {
                if(!CORE.contains(node.getTag()))throw new Failure(YAML_TAG_UNSUPPORTED);
                if(node instanceof ScalarNode scalar)return scalar(scalar);
                if(node instanceof SequenceNode sequence&&node.getTag().equals(Tag.SEQ)) {
                    var result=JsonNodeFactory.instance.arrayNode();
                    for(Node child:sequence.getValue())result.add(convert(child,depth+1));return result;
                }
                if(node instanceof MappingNode mapping&&node.getTag().equals(Tag.MAP)) {
                    var result=JsonNodeFactory.instance.objectNode();
                    for(NodeTuple entry:mapping.getValue()) {
                        JsonNode key=convert(entry.getKeyNode(),depth+1);
                        if(!key.isTextual())throw new Failure(NON_JSON_VALUE);
                        if(result.has(key.textValue()))throw new Failure(MALFORMED_SOURCE);
                        result.set(key.textValue(),convert(entry.getValueNode(),depth+1));
                    }
                    return result;
                }
                throw new Failure(NON_JSON_VALUE);
            }finally{active.remove(node);}
        }
        JsonNode scalar(ScalarNode node) throws Failure {
            String value=node.getValue();remainingCharacters-=value.length();
            if(remainingCharacters<0)throw new Failure(EXPANSION_LIMIT);
            unicode(value);Tag tag=node.getTag();
            if(tag.equals(Tag.STR))return TextNode.valueOf(value);
            if(tag.equals(Tag.NULL)&&NULL.matcher(value).matches())return NullNode.instance;
            if(tag.equals(Tag.BOOL)&&BOOL.matcher(value).matches())return BooleanNode.valueOf(value.equalsIgnoreCase("true"));
            if(value.length()>1000)throw new Failure(NON_JSON_VALUE);
            try {
                if(tag.equals(Tag.INT)&&INT.matcher(value).matches()) {
                    int base=value.startsWith("0o")?8:value.startsWith("0x")?16:10;
                    return BigIntegerNode.valueOf(new BigInteger(base==10?value:value.substring(2),base));
                }
                if(tag.equals(Tag.FLOAT)&&FLOAT.matcher(value).matches()) {
                    if(!FINITE.matcher(value).matches())throw new Failure(NON_JSON_VALUE);
                    return DecimalNode.valueOf(new BigDecimal(value));
                }
            }catch(NumberFormatException invalid){throw new Failure(NON_JSON_VALUE);}
            throw new Failure(MALFORMED_SOURCE);
        }
        private static void unicode(String value) throws Failure {
            for(int i=0;i<value.length();i++) {
                char c=value.charAt(i);
                if(Character.isHighSurrogate(c)) {
                    if(++i>=value.length()||!Character.isLowSurrogate(value.charAt(i)))throw new Failure(INVALID_UNICODE);
                }else if(Character.isLowSurrogate(c))throw new Failure(INVALID_UNICODE);
            }
        }
    }
}
