package io.github.bassemzohdy.openworkflow.agentic.runtime.adapter.source;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.dataformat.yaml.*;
import com.networknt.schema.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.security.*;
import java.util.*;

/** Offline source authoring boundary. Never binds a runtime object, registers a workflow or resolves a source URI.
 * Schema acceptance is neither engine compatibility nor activation/identity/policy permission.
 * Original bytes are always available, including when validation or conversion is refused. */
public final class WorkflowSourceCodec {
    public static final int MAX_BYTES=1_048_576;
    private static final int MAX_TOKENS=100_000;
    public enum Format {JSON,YAML}
    public enum Profile {
        RELEASED_1_0_1("1.0.1","67e328b71ada0516f64ad876a53c2a73eaf102e82d32d0052e6f118c8eeda9eb"),
        DEVELOPMENT_1_0_3("1.0.3","704ef5e91c5d823167dd8751794edb1dd1a6f9a3bdf9bfd389bf9c6b23ae3816");
        final String version,sha;
        Profile(String version,String sha){this.version=version;this.sha=sha;}
    }
    public enum Code {INVALID_UTF8,MALFORMED_SOURCE,YAML_ALIAS_UNSUPPORTED,YAML_TAG_UNSUPPORTED,
        NON_JSON_VALUE,INVALID_UNICODE,TOKEN_LIMIT,SCHEMA_INVALID,OUTPUT_LIMIT,CONVERSION_CHANGED_DATA,
        YAML_CYCLE,YAML_VERSION_UNSUPPORTED,EXPANSION_LIMIT}
    public record Diagnostic(Code code,String path,String keyword) {
        public Diagnostic {Objects.requireNonNull(code);Objects.requireNonNull(path);Objects.requireNonNull(keyword);}
    }
    /** Conversion always requires explicit acknowledgement of formatting/comment/anchor presentation loss. */
    public enum ConversionPolicy {PRESERVE_ORIGINAL,ALLOW_FORMATTING_CHANGES}
    public static final class Imported {
        private final byte[] source;
        private final Format format;
        private final Profile profile;
        private final JsonNode data;
        private final List<Diagnostic> diagnostics;
        private Imported(byte[] source,Format format,Profile profile,JsonNode data,List<Diagnostic> diagnostics) {
            this.source=source.clone();this.format=format;this.profile=profile;this.data=data;
            this.diagnostics=List.copyOf(diagnostics);
        }
        public byte[] original(){return source.clone();}
        public String originalSha256(){return sha(source);}
        public Format format(){return format;}
        public Profile profile(){return profile;}
        public List<Diagnostic> diagnostics(){return diagnostics;}
        public boolean schemaAccepted(){return diagnostics.isEmpty();}
    }
    public static final class Exported {
        private final byte[] bytes;
        private final Format format;
        private final String originalSha256;
        private final boolean exactSource;
        private final List<Diagnostic> diagnostics;
        private Exported(byte[] bytes,Format format,Imported source,boolean exactSource,List<Diagnostic> diagnostics) {
            this.bytes=bytes.clone();this.format=format;this.originalSha256=source.originalSha256();
            this.exactSource=exactSource;this.diagnostics=List.copyOf(diagnostics);
        }
        public byte[] bytes(){return bytes.clone();}
        public Format format(){return format;}
        public String originalSha256(){return originalSha256;}
        public boolean exactSource(){return exactSource;}
        public boolean succeeded(){return diagnostics.isEmpty();}
        public List<Diagnostic> diagnostics(){return diagnostics;}
        public String presentationNotice(){return exactSource?"EXACT_ORIGINAL_BYTES":
                "FORMATTING_AND_COMMENTS_NOT_PRESERVED; ALIAS_NAMES_NOT_PRESERVED; ORIGINAL_SOURCE_RETAINED; NO_ENGINE_COMPATIBILITY_OR_ACTIVATION_PROOF";}
    }
    private final ObjectMapper json,yaml;
    private final Map<Profile,Schema> schemas;

    public WorkflowSourceCodec() throws IOException {
        // No service-loader modules, class binding, global SDK mapper or checkpoint serializer.
        json=configure(new ObjectMapper());yaml=configure(new ObjectMapper(YAMLFactory.builder()
                .enable(YAMLParser.Feature.PARSE_BOOLEAN_LIKE_WORDS_AS_STRINGS).build()));
        var pinned=new EnumMap<Profile,Schema>(Profile.class);
        for(var profile:Profile.values()) {
            byte[] bytes;
            try(var in=getClass().getResourceAsStream("/conformance/schema/workflow-"+profile.version+".yaml")) {
                if(in==null)throw new IOException("Pinned source schema unavailable");bytes=in.readAllBytes();
            }
            if(!sha(bytes).equals(profile.sha))throw new IOException("Pinned source schema digest mismatch");
            // The pinned normative schemas are one self-contained document: external schema resolution is forbidden.
            JsonNode schema=yaml.readTree(bytes);requireInternalReferences(schema);
            pinned.put(profile,SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12,b->b.schemaRegistryConfig(
                    SchemaRegistryConfig.builder().formatAssertionsEnabled(true).build())).getSchema(json.writeValueAsString(schema)));
        }
        schemas=Map.copyOf(pinned);
    }
    private static ObjectMapper configure(ObjectMapper mapper) {
        mapper.getFactory().setStreamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(64)
                .maxStringLength(MAX_BYTES).maxNumberLength(1000).build());
        mapper.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        mapper.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS,DeserializationFeature.USE_BIG_INTEGER_FOR_INTS);
        return mapper;
    }
    private static void requireInternalReferences(JsonNode node) throws IOException {
        if(node.isObject()) {
            var ref=node.get("$ref");if(ref!=null&&(!ref.isTextual()||!ref.asText().startsWith("#")))
                throw new IOException("Pinned schema contains a non-local reference");
        }
        if(node.isContainerNode())for(var child:node)requireInternalReferences(child);
    }
    public Imported importSource(byte[] source,Format format,Profile profile) {
        Objects.requireNonNull(source);Objects.requireNonNull(format);Objects.requireNonNull(profile);
        if(source.length==0||source.length>MAX_BYTES)throw new IllegalArgumentException("Source must contain 1..1048576 bytes");
        source=source.clone();var diagnostics=new ArrayList<Diagnostic>();JsonNode data=null;
        try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(source));
        }catch(CharacterCodingException invalid){diagnostics.add(issue(Code.INVALID_UTF8));}
        if(diagnostics.isEmpty()) {
            try {data=parse(source,format);}
            catch(Rejected rejected){diagnostics.add(issue(rejected.code));}
            catch(IOException invalid){diagnostics.add(issue(Code.MALFORMED_SOURCE));}
        }
        if(data!=null) {
            var errors=schemas.get(profile).validate(data);
            // Never include validator messages: they can contain caller data or credentials.
            errors.stream().map(e->new Diagnostic(Code.SCHEMA_INVALID,bounded(e.getInstanceLocation().toString(),512),
                    bounded(e.getKeyword(),64))).distinct().sorted(Comparator.comparing(Diagnostic::path).thenComparing(Diagnostic::keyword))
                    .limit(32).forEach(diagnostics::add);
        }
        return new Imported(source,format,profile,data,diagnostics);
    }
    private JsonNode parse(byte[] bytes,Format format) throws IOException {
        if(format==Format.YAML) {
            try{return YamlSourceData.read(bytes,MAX_TOKENS,MAX_BYTES);}
            catch(YamlSourceData.Failure refused){throw new Rejected(refused.code);}
        }
        var mapper=mapper(format);
        // Inspect JSON before constructing a tree. YAML takes the separate bounded representation-graph path.
        try(var parser=mapper.createParser(bytes)) {
            int tokens=0;
            while(parser.nextToken()!=null) {
                if(++tokens>MAX_TOKENS)throw new Rejected(Code.TOKEN_LIMIT);
                if(parser.currentToken()==JsonToken.VALUE_EMBEDDED_OBJECT)throw new Rejected(Code.NON_JSON_VALUE);
                if(parser.currentToken()==JsonToken.VALUE_NUMBER_FLOAT&&parser.isNaN())
                    throw new Rejected(Code.NON_JSON_VALUE);
                if(parser.currentToken()==JsonToken.VALUE_STRING||parser.currentToken()==JsonToken.FIELD_NAME)
                    requireUnicode(parser.getText());
            }
        }
        try(var parser=mapper.createParser(bytes)) {
            JsonNode data=mapper.readTree(parser);
            if(data==null||parser.nextToken()!=null)throw new Rejected(Code.MALFORMED_SOURCE);
            return data;
        }
    }
    private static void requireUnicode(String value) throws Rejected {
        for(int i=0;i<value.length();i++) {
            char c=value.charAt(i);
            if(Character.isHighSurrogate(c)) {
                if(++i>=value.length()||!Character.isLowSurrogate(value.charAt(i)))throw new Rejected(Code.INVALID_UNICODE);
            }else if(Character.isLowSurrogate(c))throw new Rejected(Code.INVALID_UNICODE);
        }
    }
    /** Always exact, even for malformed/unsupported candidates. Caller still owns storage and access control. */
    public Exported exportOriginal(Imported document) {
        Objects.requireNonNull(document);return new Exported(document.source,document.format,document,true,List.of());
    }
    public Exported export(Imported document,Format target,ConversionPolicy policy) {
        Objects.requireNonNull(document);Objects.requireNonNull(target);Objects.requireNonNull(policy);
        if(policy==ConversionPolicy.PRESERVE_ORIGINAL) {
            if(target!=document.format)throw new IllegalArgumentException("Preserving original source requires its original format");
            return exportOriginal(document);
        }
        if(!document.schemaAccepted())return new Exported(new byte[0],target,document,false,document.diagnostics);
        try {
            var buffer=new ByteArrayOutputStream();
            try(var bounded=new OutputStream() {
                int count;
                public void write(int b) throws IOException {if(count==MAX_BYTES)throw new Rejected(Code.OUTPUT_LIMIT);buffer.write(b);count++;}
                public void write(byte[] b,int offset,int length) throws IOException {
                    if(length>MAX_BYTES-count)throw new Rejected(Code.OUTPUT_LIMIT);buffer.write(b,offset,length);count+=length;
                }
            }) {mapper(target).writerWithDefaultPrettyPrinter().writeValue(bounded,document.data);}
            byte[] converted=buffer.toByteArray();
            Imported reimport=importSource(converted,target,document.profile);
            if(!reimport.schemaAccepted())return new Exported(new byte[0],target,document,false,reimport.diagnostics);
            if(!sameData(document.data,reimport.data))return new Exported(new byte[0],target,document,false,List.of(issue(Code.CONVERSION_CHANGED_DATA)));
            return new Exported(converted,target,document,Arrays.equals(document.source,converted),List.of());
        }catch(IOException failure) {
            for(Throwable cause=failure;cause!=null;cause=cause.getCause())
                if(cause instanceof Rejected rejected)return new Exported(new byte[0],target,document,false,List.of(issue(rejected.code)));
            return new Exported(new byte[0],target,document,false,List.of(issue(Code.MALFORMED_SOURCE)));
        }
    }
    /** JSON data compares numbers by their exact mathematical value, never through a floating point coercion. */
    private static boolean sameData(JsonNode a,JsonNode b) {
        if(a.isNumber()&&b.isNumber())return a.decimalValue().compareTo(b.decimalValue())==0;
        if(a.isArray()&&b.isArray()) {
            if(a.size()!=b.size())return false;for(int i=0;i<a.size();i++)if(!sameData(a.get(i),b.get(i)))return false;return true;
        }
        if(a.isObject()&&b.isObject()) {
            if(a.size()!=b.size())return false;
            for(var fields=a.fields();fields.hasNext();) {var field=fields.next();var value=b.get(field.getKey());if(value==null||!sameData(field.getValue(),value))return false;}
            return true;
        }
        return a.equals(b);
    }
    private ObjectMapper mapper(Format format){return format==Format.JSON?json:yaml;}
    private static Diagnostic issue(Code code){return new Diagnostic(code,"","");}
    private static String bounded(String text,int length){return text.length()<=length?text:text.substring(0,length);}
    private static String sha(byte[] bytes) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    private static final class Rejected extends IOException {
        final Code code;
        Rejected(Code code){super(code.name());this.code=code;}
    }
}
