package com.rupeek.l1.publish;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rupeek.l1.model.HttpRequestSpec;
import com.rupeek.l1.model.OperationDef;
import com.rupeek.l1.model.Recipe;
import com.rupeek.l1.model.Resource;
import com.rupeek.l1.model.ReturnSpec;
import com.rupeek.l1.model.Step;
import com.rupeek.l1.publish.SchemaValidator.DocKind;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.lang.reflect.RecordComponent;
import java.util.Iterator;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaValidatorTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final SchemaValidator schemas = new SchemaValidator();

    /** A field added to the model must be added to the schema (and the other way round). */
    @Test
    void schemasDeclareExactlyTheModelsFields() throws Exception {
        JsonNode recipe = schema("recipe");
        JsonNode operation = schema("operation");
        JsonNode resource = schema("resource");
        assertThat(names(recipe.get("properties"))).isEqualTo(fields(Recipe.class));
        assertThat(names(recipe.at("/$defs/step/properties"))).isEqualTo(fields(Step.class));
        assertThat(names(recipe.at("/$defs/return/properties"))).isEqualTo(fields(ReturnSpec.class));
        assertThat(names(operation.get("properties"))).isEqualTo(fields(OperationDef.class));
        assertThat(names(operation.at("/properties/request/properties"))).isEqualTo(fields(HttpRequestSpec.class));
        assertThat(names(resource.get("properties"))).isEqualTo(fields(Resource.class));
        assertThat(names(resource.at("/properties/auth/properties"))).isEqualTo(fields(Resource.AuthSpec.class));
        assertThat(names(resource.at("/properties/env/additionalProperties/properties")))
                .isEqualTo(fields(Resource.ResourceEnv.class));
    }

    @Test
    void checkedInConfigIsValid() {
        ConfigBundle bundle = ConfigBundle.load(ConfigDirs.SOURCE, schemas);
        assertThat(bundle.errors()).isEmpty();
        assertThat(bundle.recipes()).isNotEmpty();
        assertThat(bundle.goldens()).hasSizeGreaterThanOrEqualTo(8);
    }

    @Test
    void reportsEachProblemWithItsPath() throws Exception {
        JsonNode bad = JSON.readTree("""
                {"recipe": "Axis precheck", "outcomes": ["done"], "steps": [{"id": "a", "op": "x.y", "colour": 1}],
                 "return": {"outcome": "DONE"}}""");
        assertThat(String.join("\n", schemas.validate(DocKind.RECIPE, bad)))
                .contains("$.recipe").contains("$.outcomes[0]").contains("$.steps[0].op").contains("colour");
    }

    @Test
    void stepNeedsExactlyOneOfOpOrReturn() throws Exception {
        JsonNode both = JSON.readTree("""
                {"recipe": "a.b", "outcomes": ["A"], "return": {"outcome": "A"},
                 "steps": [{"id": "s", "op": "x.y@1", "return": {"outcome": "A"}}]}""");
        assertThat(schemas.validate(DocKind.RECIPE, both)).isNotEmpty();
    }

    @Test
    void kindSpecificRequirements() throws Exception {
        JsonNode http = JSON.readTree("""
                {"op": "a.b", "kind": "HTTP", "resource": "r"}""");
        JsonNode mongo = JSON.readTree("""
                {"op": "a.b", "kind": "MONGO", "resource": "r", "collection": "c", "action": "DROP"}""");
        assertThat(String.join("\n", schemas.validate(DocKind.OPERATION, http))).contains("request");
        assertThat(String.join("\n", schemas.validate(DocKind.OPERATION, mongo))).contains("action");
    }

    private static JsonNode schema(String name) throws Exception {
        try (InputStream in = SchemaValidatorTest.class.getClassLoader()
                .getResourceAsStream("l1-schemas/" + name + ".schema.json")) {
            return JSON.readTree(in);
        }
    }

    private static Set<String> names(JsonNode properties) {
        Set<String> out = new TreeSet<>();
        for (Iterator<String> it = properties.fieldNames(); it.hasNext(); ) {
            out.add(it.next());
        }
        return out;
    }

    /** JSON names of a record's components ({@code @JsonProperty} on a component lands on its field). */
    private static Set<String> fields(Class<?> record) throws NoSuchFieldException {
        Set<String> out = new TreeSet<>();
        for (RecordComponent c : record.getRecordComponents()) {
            JsonProperty p = record.getDeclaredField(c.getName()).getAnnotation(JsonProperty.class);
            out.add(p != null ? p.value() : c.getName());
        }
        return out;
    }
}
