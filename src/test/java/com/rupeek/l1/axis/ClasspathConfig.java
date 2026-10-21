package com.rupeek.l1.axis;

import com.rupeek.l1.model.ConfigJson;
import com.rupeek.l1.model.OpRef;
import com.rupeek.l1.model.OperationDef;
import com.rupeek.l1.model.Recipe;
import com.rupeek.l1.model.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Test helper: loads every config document under {@code classpath:l1-config}, assigning version 1 to each
 * (git files carry no version; the registry assigns them on publish).
 */
final class ClasspathConfig {

    final Map<String, Recipe> recipes = new LinkedHashMap<>();
    final Map<String, OperationDef> operations = new LinkedHashMap<>();
    final Map<String, Resource> resources = new LinkedHashMap<>();

    static ClasspathConfig load() throws IOException {
        ClasspathConfig c = new ClasspathConfig();
        var resolver = new PathMatchingResourcePatternResolver();
        for (var r : resolver.getResources("classpath:l1-config/recipes/**/*.json")) {
            Recipe recipe = ConfigJson.recipe(r.getInputStream(), r.getFilename()).withVersion(1);
            c.recipes.put(recipe.recipe() + "@1", recipe);
        }
        for (var r : resolver.getResources("classpath:l1-config/operations/**/*.json")) {
            OperationDef op = ConfigJson.operation(r.getInputStream(), r.getFilename()).withVersion(1);
            c.operations.put(op.op() + "@1", op);
        }
        for (var r : resolver.getResources("classpath:l1-config/resources/*.json")) {
            Resource res = ConfigJson.resource(r.getInputStream(), r.getFilename());
            c.resources.put(res.resource(), res);
        }
        return c;
    }

    OperationDef op(String ref) {
        OpRef parsed = OpRef.parse(ref);
        OperationDef op = operations.get(parsed.toString());
        if (op == null) {
            throw new IllegalArgumentException("no operation " + ref);
        }
        return op;
    }
}
