package org.everit.json.schema.loader;

import static java.util.Collections.emptyMap;
import static java.util.Objects.requireNonNull;
import static org.everit.json.schema.loader.SpecificationVersion.DRAFT_4;
import static org.everit.json.schema.loader.SpecificationVersion.DRAFT_6;
import static org.everit.json.schema.loader.SpecificationVersion.DRAFT_7;

import java.lang.reflect.Method;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.everit.json.schema.FormatValidator;
import org.everit.json.schema.loader.internal.DefaultSchemaClient;
import org.everit.json.schema.regexp.JavaUtilRegexpFactory;
import org.everit.json.schema.regexp.RegexpFactory;

/**
 * @author erosb
 */
class LoaderConfig {

    static LoaderConfig defaultV4Config() {
        return new LoaderConfig(new DefaultSchemaClient(), DRAFT_4.defaultFormatValidators(), DRAFT_4, false);
    }

    final SchemaClient schemaClient;

    final Map<String, FormatValidator> formatValidators;
    
    final Map<String, Method> customTypesMap;

    final Map<String, List<String>> customTypesKeywordsMap;

    final Map<URI, Object> schemasByURI;

    final SpecificationVersion specVersion;

    final boolean useDefaults;

    final boolean nullableSupport;

    final RegexpFactory regexpFactory;

    LoaderConfig(SchemaClient schemaClient, Map<String, FormatValidator> formatValidators,
            SpecificationVersion specVersion, boolean useDefaults) {
        this(schemaClient, formatValidators, emptyMap(), specVersion, useDefaults, false, new JavaUtilRegexpFactory(), emptyMap(), emptyMap());
    }

    LoaderConfig(SchemaClient schemaClient, Map<String, FormatValidator> formatValidators,
            Map<URI, Object> schemasByURI,
            SpecificationVersion specVersion, boolean useDefaults, boolean nullableSupport,
            RegexpFactory regexpFactory,
            Map<String,Method> customTypesMap,
            Map<String,List<String>> customTypesKeywordsMap) {
        this.schemaClient = requireNonNull(schemaClient, "schemaClient cannot be null");
        this.formatValidators = requireNonNull(formatValidators, "formatValidators cannot be null");
        this.customTypesMap = requireNonNull(customTypesMap, "customTypesMap cannot be null");
        this.customTypesKeywordsMap = requireNonNull(customTypesKeywordsMap, "customTypesKeywordsMap cannot be null");
        if (schemasByURI == null) {
            this.schemasByURI = new HashMap<>();
        } else {
            this.schemasByURI = schemasByURI;
        }
        this.specVersion = requireNonNull(specVersion, "specVersion cannot be null");
        this.useDefaults = useDefaults;
        this.nullableSupport = nullableSupport;
        this.regexpFactory = requireNonNull(regexpFactory, "regexpFactory cannot be null");
    }

    /**
     * Creates a new loader builder with {@code this} configuration
     *
     * @return
     */
    SchemaLoader.SchemaLoaderBuilder initLoader() {
        SchemaLoader.SchemaLoaderBuilder loaderBuilder = SchemaLoader.builder()
                .schemaClient(this.schemaClient)
                .useDefaults(this.useDefaults)
                .regexpFactory(this.regexpFactory)
                .nullableSupport(this.nullableSupport)
                .formatValidators(new HashMap<>(this.formatValidators));
        loaderBuilder.schemasByURI = schemasByURI;
        if (DRAFT_6.equals(specVersion)) {
            loaderBuilder.draftV6Support();
        } else if (DRAFT_7.equals(specVersion)) {
            loaderBuilder.draftV7Support();
        }
        return loaderBuilder;
    }

}
