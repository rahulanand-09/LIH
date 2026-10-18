package com.rupeek.l1.publish;

import com.rupeek.l1.engine.CompiledOperation;
import com.rupeek.l1.engine.CompiledRecipe;
import com.rupeek.l1.engine.ConfigCompiler;
import com.rupeek.l1.expr.ExpressionEngine;
import com.rupeek.l1.expr.TemplateEvaluator;
import com.rupeek.l1.model.OpRef;
import com.rupeek.l1.model.OperationDef;
import com.rupeek.l1.model.Recipe;
import com.rupeek.l1.model.Resource;
import com.rupeek.l1.ops.OperationRegistry;
import com.rupeek.l1.publish.ConfigBundle.Doc;
import com.rupeek.l1.publish.GoldenRunner.GoldenResult;
import com.rupeek.l1.publish.PublishReport.Action;
import com.rupeek.l1.publish.PublishReport.Item;
import com.rupeek.l1.publish.PublishReport.Mode;
import com.rupeek.l1.registry.ConfigCodec;
import com.rupeek.l1.registry.ConfigStore;
import com.rupeek.l1.registry.DocType;
import com.rupeek.l1.registry.InMemoryResourceProvider;
import com.rupeek.l1.registry.StoredResource;
import com.rupeek.l1.registry.StoredVersion;
import com.rupeek.l1.secrets.SecretResolver;
import com.rupeek.l1.worker.L1Components;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The publish pipeline (LLD section 12). For a config bundle:
 * <ol>
 *   <li>schema and parse errors from loading the bundle;</li>
 *   <li>versions against the registry: an existing {@code ref@version} must have the same content, a new one must
 *       be higher than {@code latest} (gaps are allowed: QA may have seen versions that prod skips);</li>
 *   <li>resources: an HTTP resource needs a block for the target environment;</li>
 *   <li>compile every operation and recipe with the worker's own compiler (references, CEL in its scope,
 *       dataflow, outcomes, outputs, kind checks); recipes may pin operations that are only in the registry;</li>
 *   <li>golden tests, at least one per recipe;</li>
 *   <li>only if everything passed and this is a real publish: insert new versions (operations first), move
 *       pointers, update changed resources;</li>
 *   <li>append a publish log entry (real publishes only, passed or failed).</li>
 * </ol>
 * Validation is offline when there is no registry to compare with: steps 2 and 7 are skipped and recipes may
 * only pin operations in the bundle.
 */
public final class Publisher {

    /** Base URL handed to the compiler and golden runs; HTTP is never called during publish. */
    private static final String NO_HOST = "http://publish.invalid";

    private final ConfigStore store;
    private final boolean offline;
    private final Clock clock;
    private final ExpressionEngine expressions = L1Components.standardExpressions();
    private final OperationRegistry operations;

    public Publisher(ConfigStore store, boolean offline, Clock clock) {
        this.store = store;
        this.offline = offline;
        this.clock = clock;
        SecretResolver noSecrets = ref -> {
            throw new IllegalStateException("secrets are not available during publish");
        };
        this.operations = L1Components.standardOperations(new TemplateEvaluator(expressions), Map.of(), noSecrets);
    }

    public PublishReport validate(ConfigBundle bundle, String env) {
        return run(bundle, env, null, null, Mode.VALIDATE);
    }

    public PublishReport publish(ConfigBundle bundle, String env, String by, String reason, boolean dryRun) {
        if (offline && !dryRun) {
            throw new IllegalStateException("cannot publish without a registry");
        }
        return run(bundle, env, by, reason, dryRun ? Mode.DRY_RUN : Mode.PUBLISH);
    }

    private PublishReport run(ConfigBundle bundle, String env, String by, String reason, Mode mode) {
        String publishId = UUID.randomUUID().toString();
        Instant at = clock.instant();
        List<String> errors = new ArrayList<>(bundle.errors());
        List<Item> items = new ArrayList<>();

        // 2. versions against the registry
        versionItems(DocType.OPERATION, bundle.operations(), d -> d.op(), d -> d.version(), errors, items);
        versionItems(DocType.RECIPE, bundle.recipes(), d -> d.recipe(), d -> d.version(), errors, items);

        // 3 + 4. resources and compile
        Compiled compiled = compile(bundle, env, errors, items);
        Map<String, CompiledRecipe> recipes = compiled.recipes();
        InMemoryResourceProvider provider = new InMemoryResourceProvider(compiled.resources(), env, name -> NO_HOST);
        Map<String, Resource> resources = compiled.resources();

        // 5. golden tests
        List<GoldenResult> goldens = runGoldens(bundle, recipes, provider, errors);

        boolean passed = errors.isEmpty() && goldens.stream().allMatch(GoldenResult::passed);
        boolean written = false;
        if (passed && mode == Mode.PUBLISH) {
            write(bundle, items, resources, publishId, by, reason, at);
            written = true;
        }
        PublishReport report = new PublishReport(publishId, mode, env, by, reason, at, items, errors, goldens, written);
        if (mode == Mode.PUBLISH) {
            store.logPublish(report.toLog());
        }
        return report;
    }

    /**
     * Compiled bundle: recipes by {@code ref@version} and the resources they run against (registry resources
     * overlaid with the bundle's).
     */
    public record Compiled(Map<String, CompiledRecipe> recipes, Map<String, Resource> resources) {

        /** {@code ref@version}, or the highest version of {@code ref} in the bundle; null if absent. */
        public CompiledRecipe recipe(String refOrPinned) {
            int at = refOrPinned.lastIndexOf('@');
            if (at > 0) {
                return recipes.get(refOrPinned);
            }
            return recipes.values().stream().filter(r -> r.ref().equals(refOrPinned))
                    .max(Comparator.comparingInt(CompiledRecipe::version)).orElse(null);
        }
    }

    /** Steps 3 and 4 alone: resource checks and compilation. Errors are added to {@code errors}. */
    public Compiled compile(ConfigBundle bundle, String env, List<String> errors) {
        return compile(bundle, env, errors, new ArrayList<>());
    }

    private Compiled compile(ConfigBundle bundle, String env, List<String> errors, List<Item> items) {
        Map<String, Resource> resources = new HashMap<>();
        if (!offline) {
            store.resources().forEach(r -> resources.put(r.resource(), ConfigCodec.resource(r)));
        }
        for (Doc<Resource> d : bundle.resources()) {
            Resource r = d.doc();
            if (r.kind() == Resource.ResourceKind.HTTP) {
                Resource.ResourceEnv block = r.env().get(env);
                if (block == null || (isBlank(block.baseUrl()) && isBlank(block.baseUrlRef()))) {
                    errors.add(d.source() + ": no '" + env + "' environment with baseUrl or baseUrlRef");
                }
            }
            items.add(new Item("RESOURCE", r.resource(), null, resourceAction(r), d.source()));
            resources.put(r.resource(), r);
        }
        ConfigCompiler compiler = new ConfigCompiler(expressions, operations,
                new InMemoryResourceProvider(resources, env, name -> NO_HOST));

        Map<OpRef, CompiledOperation> ops = new HashMap<>();
        for (Doc<OperationDef> d : bundle.operations()) {
            try {
                CompiledOperation op = compiler.compileOperation(d.doc());
                ops.put(op.ref(), op);
            } catch (RuntimeException e) {
                errors.add(d.source() + ": " + e.getMessage());
            }
        }
        Function<OpRef, CompiledOperation> lookup = ref -> ops.computeIfAbsent(ref, r -> fromRegistry(r, compiler));
        Map<String, CompiledRecipe> recipes = new HashMap<>();
        for (Doc<Recipe> d : bundle.recipes()) {
            try {
                CompiledRecipe r = compiler.compileRecipe(d.doc(), lookup);
                recipes.put(r.ref() + "@" + r.version(), r);
            } catch (RuntimeException e) {
                errors.add(d.source() + ": " + e.getMessage());
            }
        }
        return new Compiled(recipes, Map.copyOf(resources));
    }

    private <T> void versionItems(DocType type, List<Doc<T>> docs, Function<T, String> refOf,
                                  Function<T, Integer> versionOf, List<String> errors, List<Item> items) {
        Map<String, List<Doc<T>>> byRef = docs.stream().collect(Collectors.groupingBy(d -> refOf.apply(d.doc()),
                TreeMap::new, Collectors.toList()));
        byRef.forEach((ref, list) -> {
            list.sort(Comparator.comparing(d -> versionOf.apply(d.doc())));
            int next = offline ? 0 : store.latest(type, ref).orElse(0) + 1;
            for (Doc<T> d : list) {
                int v = versionOf.apply(d.doc());
                if (offline) {
                    items.add(new Item(type.name(), ref, v, Action.CHECKED, d.source()));
                    continue;
                }
                Optional<StoredVersion> existing = store.find(type, ref, v);
                if (existing.isPresent()) {
                    if (!existing.get().specHash().equals(ConfigCodec.hash(d.doc()))) {
                        errors.add(d.source() + ": " + ref + "@" + v + " is already published with different "
                                + "content; versions are immutable, publish the change as @" + Math.max(next, v + 1));
                    }
                    items.add(new Item(type.name(), ref, v, Action.UNCHANGED, d.source()));
                    next = Math.max(next, v + 1);
                } else if (v < next) {
                    errors.add(d.source() + ": " + ref + "@" + v + " cannot be published; versions only move forward "
                            + "and the latest published is @" + (next - 1));
                    items.add(new Item(type.name(), ref, v, Action.NEW, d.source()));
                } else {
                    items.add(new Item(type.name(), ref, v, Action.NEW, d.source()));
                    next = v + 1;
                }
            }
        });
    }

    private Action resourceAction(Resource r) {
        if (offline) {
            return Action.CHECKED;
        }
        return store.resource(r.resource())
                .map(s -> s.specHash().equals(ConfigCodec.hash(r)) ? Action.UNCHANGED : Action.UPDATED)
                .orElse(Action.NEW);
    }

    /** An operation pinned by a recipe but not in the bundle: compile it from the registry (null if absent). */
    private CompiledOperation fromRegistry(OpRef ref, ConfigCompiler compiler) {
        if (offline) {
            return null;
        }
        return store.find(DocType.OPERATION, ref.ref(), ref.version())
                .map(v -> compiler.compileOperation(ConfigCodec.operation(v)))
                .orElse(null);
    }

    private List<GoldenResult> runGoldens(ConfigBundle bundle, Map<String, CompiledRecipe> recipes,
                                          InMemoryResourceProvider provider, List<String> errors) {
        Map<String, Integer> highest = new TreeMap<>();
        bundle.recipes().forEach(d -> highest.merge(d.doc().recipe(), d.doc().version(), Math::max));
        Map<String, Integer> covered = new HashMap<>();
        GoldenRunner runner = new GoldenRunner(expressions, operations);
        List<GoldenResult> results = new ArrayList<>();
        for (Doc<GoldenCase> d : bundle.goldens()) {
            GoldenCase c = d.doc();
            String ref = c.recipe();
            int version;
            int at = ref.lastIndexOf('@');
            if (at > 0) {
                version = Integer.parseInt(ref.substring(at + 1));
                ref = ref.substring(0, at);
            } else {
                Integer h = highest.get(ref);
                if (h == null) {
                    errors.add(d.source() + ": recipe " + ref + " is not in this bundle");
                    continue;
                }
                version = h;
            }
            CompiledRecipe recipe = recipes.get(ref + "@" + version);
            if (recipe == null) {
                int v = version;
                String r = ref;
                if (bundle.recipes().stream().noneMatch(x -> x.doc().recipe().equals(r) && x.doc().version() == v)) {
                    errors.add(d.source() + ": recipe " + ref + "@" + version + " is not in this bundle");
                }
                continue;   // in the bundle but did not compile: already reported
            }
            covered.merge(ref + "@" + version, 1, Integer::sum);
            results.add(runner.run(d.source(), c, recipe, provider));
        }
        highest.forEach((ref, v) -> {
            if (!covered.containsKey(ref + "@" + v) && recipes.containsKey(ref + "@" + v)) {
                errors.add(ref + "@" + v + ": no golden test (add one under tests/)");
            }
        });
        return results;
    }

    private void write(ConfigBundle bundle, List<Item> items, Map<String, Resource> resources, String publishId,
                       String by, String reason, Instant at) {
        Map<String, Item> newItems = new LinkedHashMap<>();
        items.stream().filter(i -> i.action() == Action.NEW || i.action() == Action.UPDATED)
                .forEach(i -> newItems.put(key(i.type(), i.ref(), i.version()), i));
        // operations before recipes, so a stored recipe never pins a missing operation
        for (Doc<OperationDef> d : bundle.operations()) {
            OperationDef o = d.doc();
            if (newItems.containsKey(key("OPERATION", o.op(), o.version()))) {
                store.insert(new StoredVersion(DocType.OPERATION, o.op(), o.version(), ConfigCodec.json(o),
                        ConfigCodec.hash(o), by, at, reason, publishId));
            }
        }
        for (Doc<Recipe> d : bundle.recipes()) {
            Recipe r = d.doc();
            if (newItems.containsKey(key("RECIPE", r.recipe(), r.version()))) {
                store.insert(new StoredVersion(DocType.RECIPE, r.recipe(), r.version(), ConfigCodec.json(r),
                        ConfigCodec.hash(r), by, at, reason, publishId));
            }
        }
        // pointers: highest version of each ref, also repairing a pointer left behind by an interrupted publish
        movePointers(DocType.OPERATION, bundle.operations().stream()
                .collect(Collectors.toMap(d -> d.doc().op(), d -> d.doc().version(), Math::max)), by, reason, publishId);
        movePointers(DocType.RECIPE, bundle.recipes().stream()
                .collect(Collectors.toMap(d -> d.doc().recipe(), d -> d.doc().version(), Math::max)), by, reason, publishId);
        for (Doc<Resource> d : bundle.resources()) {
            Resource r = d.doc();
            if (newItems.containsKey(key("RESOURCE", r.resource(), null))) {
                store.putResource(new StoredResource(r.resource(), ConfigCodec.json(r), ConfigCodec.hash(r), by, at,
                        reason, publishId));
            }
        }
    }

    private void movePointers(DocType type, Map<String, Integer> highest, String by, String reason, String publishId) {
        highest.forEach((ref, v) -> {
            OptionalInt current = store.latest(type, ref);
            if (current.isEmpty() || current.getAsInt() < v) {
                store.movePointer(type, ref, v, by, reason, publishId);
            }
        });
    }

    private static String key(String type, String ref, Integer version) {
        return type + ":" + ref + (version == null ? "" : "@" + version);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
