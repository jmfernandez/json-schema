package org.everit.json.schema.loader;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toList;
import static org.everit.json.schema.loader.OrgJsonUtil.toMap;
import static org.everit.json.schema.loader.SpecificationVersion.DRAFT_4;
import static org.everit.json.schema.loader.SpecificationVersion.DRAFT_6;
import static org.everit.json.schema.loader.SpecificationVersion.DRAFT_7;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.everit.json.schema.AbstractCustomTypeSchema;
import org.everit.json.schema.CombinedSchema;
import org.everit.json.schema.EmptySchema;
import org.everit.json.schema.FalseSchema;
import org.everit.json.schema.FormatValidator;
import org.everit.json.schema.Schema;
import org.everit.json.schema.SchemaException;
import org.everit.json.schema.SchemaLocation;
import org.everit.json.schema.TrueSchema;
import org.everit.json.schema.loader.internal.DefaultSchemaClient;
import org.everit.json.schema.loader.internal.WrappingFormatValidator;
import org.everit.json.schema.regexp.JavaUtilRegexpFactory;
import org.everit.json.schema.regexp.RegexpFactory;
import org.json.JSONObject;

/**
 * Loads a JSON schema's JSON representation into schema validator instances.
 */
public class SchemaLoader {

    static JSONObject toOrgJSONObject(JsonObject value) {
        return new JSONObject(value.toMap());
    }

    /**
     * Builder class for {@link SchemaLoader}.
     */
    public static class SchemaLoaderBuilder {

        SchemaClient schemaClient = new DefaultSchemaClient();

        Object schemaJson;

        Object rootSchemaJson;

        Map<String, ReferenceKnot> pointerSchemas = new HashMap<>();

        URI id;

        SchemaLocation pointerToCurrentObj = SchemaLocation.empty();

        Map<String, FormatValidator> formatValidators = new HashMap<>();

        SpecificationVersion specVersion;

        private boolean specVersionIsExplicitlySet = false;

        boolean useDefaults = false;

        private boolean nullableSupport = false;

        RegexpFactory regexpFactory = new JavaUtilRegexpFactory();
        
        Map<String,Method> customTypesMap = new HashMap<>();
        Map<String,List<String>> customTypesKeywordsMap = new HashMap<>();

        Map<URI, Object> schemasByURI = null;

        public SchemaLoaderBuilder() {
            setSpecVersion(DRAFT_4);
        }
        
        /**
         * Registers a custom schema type
         *
         * @param entry
         *         a Map.Entry with the typeName and the class to register
         * @return {@code this}
         */
        public SchemaLoaderBuilder addCustomType(Map.Entry<String,Class<?>> entry) {
            return addCustomType(entry.getKey(),entry.getValue());
        }
        
        /**
         * Registers a custom schema type
         *
         * @param typeName
         *         the type name to use for this custom JSON Schema type
         * @param clazz
         *         the class which implements the validation of this custom JSON Schema type
         * @return {@code this}
         */
        public SchemaLoaderBuilder addCustomType(String typeName,Class<?> clazz) {
            typeName = requireNonNull(typeName, "the name of the custom type cannot be null");
            if(typeName.length() == 0) {
                    throw new IllegalArgumentException("the name of the custom type must be non-empty");
            }
            
            // Checking the pre-conditions
            Method method = null;
            try {
                method = clazz.getMethod("schemaBuilderLoader", LoadingState.class, LoaderConfig.class, SchemaLoader.class);
                int mods = method.getModifiers();
                if(!Modifier.isStatic(mods) || !Modifier.isPublic(mods)) {
                    throw new IllegalArgumentException("class '" + clazz.getName() + "', manager of custom type '" + typeName + "' must have a public static 'schemaBuilderLoader(LoadingState ls, LoaderConfig config, SchemaLoader defaultLoader)' method");
                }
                Class<?> retClazz = method.getReturnType();
                retClazz.asSubclass(Schema.Builder.class);
            } catch(NoSuchMethodException nsme) {
                throw new IllegalArgumentException("class '" + clazz.getName() + "', manager of custom type '" + typeName + "' must have a 'schemaBuilderLoader(LoadingState ls, LoaderConfig config, SchemaLoader defaultLoader)' method");
            } catch(ClassCastException cce) {
                throw new IllegalArgumentException("class '" + clazz.getName() + "', manager of custom type '" + typeName + "': 'schemaBuilderLoader(LoadingState ls, LoaderConfig config, SchemaLoader defaultLoader)' method must return an instance of Schema.Builder");
            }
            
            List<String> customTypeKeywords = null;
            try {
                Method kwMethod = clazz.getMethod("schemaKeywords");
                int mods = method.getModifiers();
                if(!Modifier.isStatic(mods) || !Modifier.isPublic(mods)) {
                    throw new IllegalArgumentException("class '" + clazz.getName() + "', manager of custom type '" + typeName + "' must have a public static 'schemaKeywords()' method");
                }
                Class<?> retClazz = kwMethod.getReturnType();
                retClazz.asSubclass(List.class);
                
                // Now, obtain the list
                customTypeKeywords = (List<String>)kwMethod.invoke(null);
            } catch(NoSuchMethodException nsme) {
                throw new IllegalArgumentException("class '" + clazz.getName() + "', manager of custom type '" + typeName + "' must have a 'schemaKeywords()' method");
            } catch(ClassCastException cce) {
                throw new IllegalArgumentException("class '" + clazz.getName() + "', manager of custom type '" + typeName + "': 'schemaKeywords()' method must return an instance of List<String>");
            } catch(InvocationTargetException ite) {
                throw new IllegalArgumentException("class '" + clazz.getName() + "', manager of custom type '" + typeName + "' failed invoking 'schemaKeywords()' method");
            } catch(IllegalAccessException iae) {
                throw new IllegalArgumentException("class '" + clazz.getName() + "', manager of custom type '" + typeName + "' failed invoking 'schemaKeywords()' method");
            }
            
            // If we are here, all is ok
            customTypesMap.put(typeName,method);
            customTypesKeywordsMap.put(typeName,customTypeKeywords);
            return this;
        }
        
        /**
         * Registers a format validator with the name returned by {@link FormatValidator#formatName()}.
         *
         * @param formatValidator
         *         the format validator to be registered with its name
         * @return {@code this}
         */
        public SchemaLoaderBuilder addFormatValidator(FormatValidator formatValidator) {
            formatValidators.put(formatValidator.formatName(), formatValidator);
            return this;
        }

        /**
         * @param formatName
         *         the name which will be used in the schema JSON files to refer to this {@code formatValidator}
         * @param formatValidator
         *         the object performing the validation for schemas which use the {@code formatName} format
         * @return {@code this}
         * @deprecated instead it is better to override {@link FormatValidator#formatName()}
         * and use {@link #addFormatValidator(FormatValidator)}
         */
        @Deprecated
        public SchemaLoaderBuilder addFormatValidator(String formatName,
                final FormatValidator formatValidator) {
            if (!Objects.equals(formatName, formatValidator.formatName())) {
                formatValidators.put(formatName, new WrappingFormatValidator(formatName, formatValidator));
            } else {
                formatValidators.put(formatName, formatValidator);
            }
            return this;
        }

        public SchemaLoaderBuilder draftV6Support() {
            setSpecVersion(DRAFT_6);
            specVersionIsExplicitlySet = true;
            return this;
        }

        public SchemaLoaderBuilder draftV7Support() {
            setSpecVersion(DRAFT_7);
            specVersionIsExplicitlySet = true;
            return this;
        }

        private void setSpecVersion(SpecificationVersion specVersion) {
            this.specVersion = specVersion;
        }

        private Optional<SpecificationVersion> specVersionInSchema() {
            Optional<SpecificationVersion> specVersion = Optional.empty();
            if (schemaJson instanceof Map) {
                Map<String, Object> schemaObj = (Map<String, Object>) schemaJson;
                String metaSchemaURL = (String) schemaObj.get("$schema");
                try {
                    specVersion = Optional.ofNullable(metaSchemaURL).map((SpecificationVersion::getByMetaSchemaUrl));
                } catch (IllegalArgumentException e) {
                    return specVersion;
                }
            }
            return specVersion;
        }

        public SchemaLoader build() {
            specVersionInSchema().ifPresent(this::setSpecVersion);
            formatValidators.putAll(specVersion.defaultFormatValidators());
            return new SchemaLoader(this);
        }

        @Deprecated
        public JSONObject getRootSchemaJson() {
            return new JSONObject((Map<String, Object>) (rootSchemaJson == null ? schemaJson : rootSchemaJson));
        }

        /**
         * @deprecated use {@link #schemaClient(SchemaClient)} instead
         */
        @Deprecated
        public SchemaLoaderBuilder httpClient(SchemaClient httpClient) {
            this.schemaClient = httpClient;
            return this;
        }

        public SchemaLoaderBuilder schemaClient(SchemaClient schemaClient) {
            this.schemaClient = schemaClient;
            return this;
        }

        /**
         * Sets the initial resolution scope of the schema. {@code id} and {@code $ref} attributes
         * accuring in the schema will be resolved against this value.
         *
         * @param id
         *         the initial (absolute) URI, used as the resolution scope.
         * @return {@code this}
         */
        public SchemaLoaderBuilder resolutionScope(String id) {
            try {
                return resolutionScope(new URI(id));
            } catch (URISyntaxException e) {
                throw new RuntimeException(e);
            }
        }

        public SchemaLoaderBuilder resolutionScope(URI id) {
            this.id = id;
            return this;
        }

        SchemaLoaderBuilder pointerSchemas(Map<String, ReferenceKnot> pointerSchemas) {
            this.pointerSchemas = pointerSchemas;
            return this;
        }

        SchemaLoaderBuilder rootSchemaJson(Object rootSchemaJson) {
            this.rootSchemaJson = rootSchemaJson;
            return this;
        }

        public SchemaLoaderBuilder schemaJson(JSONObject schemaJson) {
            return schemaJson(toMap(schemaJson));
        }

        public SchemaLoaderBuilder schemaJson(Object schema) {
            if (schema instanceof JSONObject) {
                schema = toMap((JSONObject) schema);
            }
            this.schemaJson = schema;
            return this;
        }

        SchemaLoaderBuilder formatValidators(Map<String, FormatValidator> formatValidators) {
            this.formatValidators = formatValidators;
            return this;
        }

        SchemaLoaderBuilder pointerToCurrentObj(SchemaLocation pointerToCurrentObj) {
            this.pointerToCurrentObj = requireNonNull(pointerToCurrentObj);
            return this;
        }

        /**
         * With this flag set to false, the validator ignores the default keyword inside the json schema.
         * If is true, validator applies default values when it's needed
         *
         * @param useDefaults
         *         if true, validator doesn't ignore default values
         * @return {@code this}
         */
        public SchemaLoaderBuilder useDefaults(boolean useDefaults) {
            this.useDefaults = useDefaults;
            return this;
        }

        public SchemaLoaderBuilder nullableSupport(boolean nullableSupport) {
            this.nullableSupport = nullableSupport;
            return this;
        }

        public SchemaLoaderBuilder regexpFactory(RegexpFactory regexpFactory) {
            this.regexpFactory = regexpFactory;
            return this;
        }

        public SchemaLoaderBuilder registerSchemaByURI(URI uri, Object schema) {
            if (schemasByURI == null) {
                schemasByURI = new HashMap<>();
            }
            schemasByURI.put(uri, schema);
            return this;
        }
    }

    public static SchemaLoaderBuilder builder() {
        return new SchemaLoaderBuilder();
    }

    /**
     * Loads a JSON schema to a schema validator using a {@link DefaultSchemaClient default HTTP
     * client}.
     *
     * @param schemaJson
     *         the JSON representation of the schema.
     * @return the schema validator object
     */
    public static Schema load(final JSONObject schemaJson) {
        return SchemaLoader.load(schemaJson, new DefaultSchemaClient());
    }

    /**
     * Creates Schema instance from its JSON representation.
     *
     * @param schemaJson
     *         the JSON representation of the schema.
     * @param customTypes
     *         the custom types to use on the validation process
     * @return the created schema
     */
    public static Schema load(final JSONObject schemaJson, final Map<String,Class<?>> customTypes) {
        return SchemaLoader.load(schemaJson, new DefaultSchemaClient(), customTypes);
    }
    
    /**
     * Creates Schema instance from its JSON representation.
     *
     * @param schemaJson
     *         the JSON representation of the schema.
     * @param schemaClient
     *         the HTTP client to be used for resolving remote JSON references.
     * @return the created schema
     */
    public static Schema load(final JSONObject schemaJson, final SchemaClient schemaClient) {
        return SchemaLoader.load(schemaJson,schemaClient,null);
    }
    
    /**
     * Creates Schema instance from its JSON representation.
     *
     * @param schemaJson
     *         the JSON representation of the schema.
     * @param schemaClient
     *         the HTTP client to be used for resolving remote JSON references.
     * @param customTypes
     *         the custom types to use on the validation process
     * @return the created schema
     */
    public static Schema load(final JSONObject schemaJson, final SchemaClient schemaClient, final Map<String,Class<?>> customTypes) {
        SchemaLoaderBuilder builder = builder();
        if(customTypes != null) {
            for(Map.Entry<String,Class<?>> customTypeP: customTypes.entrySet()) {
                builder.addCustomType(customTypeP);
            }
        }
        
        SchemaLoader loader = builder.schemaJson(schemaJson)
                .schemaClient(schemaClient)
                .build();
        return loader.load().build();
    }

    private final LoaderConfig config;

    private final LoadingState ls;

    /**
     * Constructor.
     *
     * @param builder
     *         the builder containing the properties. Only {@link SchemaLoaderBuilder#id} is
     *         nullable.
     * @throws NullPointerException
     *         if any of the builder properties except {@link SchemaLoaderBuilder#id id} is
     *         {@code null}.
     */
    public SchemaLoader(SchemaLoaderBuilder builder) {
        Object effectiveRootSchemaJson = builder.rootSchemaJson == null
                ? builder.schemaJson
                : builder.rootSchemaJson;
        Optional<String> schemaKeywordValue = extractSchemaKeywordValue(effectiveRootSchemaJson);
        SpecificationVersion specVersion;
        if (schemaKeywordValue.isPresent()) {
            try {
                specVersion = SpecificationVersion.getByMetaSchemaUrl(schemaKeywordValue.get());
            } catch (IllegalArgumentException e) {
                if (builder.specVersionIsExplicitlySet) {
                    specVersion = builder.specVersion;
                } else {
                    throw new SchemaException("#", "could not determine version");
                }
            }
        } else {
            specVersion = builder.specVersion;
        }
        this.config = new LoaderConfig(builder.schemaClient,
                builder.formatValidators,
                builder.schemasByURI,
                specVersion,
                builder.useDefaults,
                builder.nullableSupport,
                builder.regexpFactory,
                builder.customTypesMap,
                builder.customTypesKeywordsMap);
        this.ls = new LoadingState(config,
                builder.pointerSchemas,
                effectiveRootSchemaJson,
                builder.schemaJson,
                builder.id,
                builder.pointerToCurrentObj);
    }

    private static Optional<String> extractSchemaKeywordValue(Object effectiveRootSchemaJson) {
        if (effectiveRootSchemaJson instanceof Map) {
            Map<String, Object> schemaObj = (Map<String, Object>) effectiveRootSchemaJson;
            Object schemaValue = schemaObj.get("$schema");
            if (schemaValue != null) {
                return Optional.of((String) schemaValue);
            }
        }
        if (effectiveRootSchemaJson instanceof JsonObject) {
            JsonObject schemaObj = (JsonObject) effectiveRootSchemaJson;
            Object schemaValue = schemaObj.get("$schema");
            if (schemaValue != null) {
                return Optional.of((String) schemaValue);
            }
        }
        return Optional.empty();
    }

    SchemaLoader(LoadingState ls) {
        this.ls = ls;
        this.config = ls.config;
    }

    private Schema.Builder loadSchemaBoolean(Boolean rawBoolean) {
        return rawBoolean ? TrueSchema.builder() : FalseSchema.builder();
    }

    private Schema.Builder loadSchemaObject(JsonObject o) {
        AdjacentSchemaExtractionState postExtractionState = runSchemaExtractors(o);
        Collection<Schema.Builder<?>> extractedSchemas = postExtractionState.extractedSchemaBuilders();
        Schema.Builder effectiveReturnedSchema;
        if (extractedSchemas.isEmpty()) {
            effectiveReturnedSchema = EmptySchema.builder();
        } else if (extractedSchemas.size() == 1) {
            effectiveReturnedSchema = extractedSchemas.iterator().next();
        } else {
            Collection<Schema> built = extractedSchemas.stream()
                    .map(Schema.Builder::build)
                    .map(Schema.class::cast)
                    .collect(toList());
            effectiveReturnedSchema = CombinedSchema.allOf(built).isSynthetic(true);
        }
        AdjacentSchemaExtractionState postCommonPropLoadingState = loadCommonSchemaProperties(effectiveReturnedSchema, postExtractionState);
        Map<String, Object> unprocessed = postCommonPropLoadingState.projectedSchemaJson().toMap();
        effectiveReturnedSchema.unprocessedProperties(unprocessed);
        return effectiveReturnedSchema;
    }

    private AdjacentSchemaExtractionState runSchemaExtractors(JsonObject o) {
        AdjacentSchemaExtractionState state = new AdjacentSchemaExtractionState(o);
        if (o.containsKey("$ref")) {
            ExtractionResult result = new ReferenceSchemaExtractor(this).extract(o);
            state = state.reduce(result);
            return state;
        }
        List<SchemaExtractor> extractors = asList(
                new EnumSchemaExtractor(this),
                new CombinedSchemaLoader(this),
                new NotSchemaExtractor(this),
                new ConstSchemaExtractor(this),
                new TypeBasedSchemaExtractor(this,config.customTypesMap,config.customTypesKeywordsMap),
                new PropertySnifferSchemaExtractor(this)
        );
        for (SchemaExtractor extractor : extractors) {
            ExtractionResult result = extractor.extract(state.projectedSchemaJson());
            state = state.reduce(result);
        }
        return state;
    }

    private AdjacentSchemaExtractionState loadCommonSchemaProperties(Schema.Builder builder, AdjacentSchemaExtractionState state) {
        KeyConsumer consumedKeys = new KeyConsumer(state.projectedSchemaJson());
        consumedKeys.maybe(config.specVersion.idKeyword()).map(JsonValue::requireString).ifPresent(builder::id);
        consumedKeys.maybe("title").map(JsonValue::requireString).ifPresent(builder::title);
        consumedKeys.maybe("description").map(JsonValue::requireString).ifPresent(builder::description);
        if (ls.specVersion() == DRAFT_7) {
            consumedKeys.maybe("readOnly").map(JsonValue::requireBoolean).ifPresent(builder::readOnly);
            consumedKeys.maybe("writeOnly").map(JsonValue::requireBoolean).ifPresent(builder::writeOnly);
        }
        if (config.nullableSupport) {
            builder.nullable(consumedKeys.maybe("nullable")
                    .map(JsonValue::requireBoolean)
                    .orElse(Boolean.FALSE));
        }
        if (config.useDefaults) {
            consumedKeys.maybe("default").map(JsonValue::deepToOrgJson).ifPresent(builder::defaultValue);
        }
        builder.schemaLocation(ls.pointerToCurrentObj);
        return state.reduce(new ExtractionResult(consumedKeys.collect(), emptyList()));
    }

    /**
     * Populates a {@code Schema.Builder} instance from the {@code schemaJson} schema definition.
     *
     * @return the builder which already contains the validation criteria of the schema, therefore
     * {@link Schema.Builder#build()} can be immediately used to acquire the {@link Schema}
     * instance to be used for validation
     */
    public Schema.Builder<?> load() {
        return ls.schemaJson
                .canBeMappedTo(Boolean.class, this::loadSchemaBoolean)
                .orMappedTo(JsonObject.class, this::loadSchemaObject)
                .requireAny();
    }

    Schema.Builder<?> loadChild(JsonValue childJson) {
        return new SchemaLoader(childJson.ls).load();
    }

    SpecificationVersion specVersion() {
        return ls.specVersion();
    }

    /**
     * @param formatName
     * @return
     * @deprecated
     */
    @Deprecated
    Optional<FormatValidator> getFormatValidator(String formatName) {
        return Optional.ofNullable(config.formatValidators.get(formatName));
    }

}
