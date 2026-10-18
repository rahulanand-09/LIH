package com.rupeek.l1.publish.cli;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.rupeek.l1.publish.ConfigBundle;
import com.rupeek.l1.publish.PublishReport;
import com.rupeek.l1.publish.Publisher;
import com.rupeek.l1.publish.SchemaValidator;
import com.rupeek.l1.registry.ConfigStore;
import com.rupeek.l1.registry.DocType;
import com.rupeek.l1.registry.InMemoryConfigStore;
import com.rupeek.l1.registry.MongoConfigStore;
import com.rupeek.l1.registry.Redirect;
import com.rupeek.l1.registry.StoredResource;
import org.slf4j.LoggerFactory;

import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Config tooling, run locally and from CI.
 *
 * <pre>
 * validate [--dir DIR] [--env ENV] [--mongo-uri URI]          schema, refs, CEL, dataflow, golden; no writes
 * publish  --env ENV --reason TEXT [--dir DIR] [--by NAME] [--dry-run] [--mongo-uri URI]
 * status   [--mongo-uri URI]                                   pointers and resources in the registry
 * redirect --ref REF --from N --to M --reason TEXT [--by NAME] [--mongo-uri URI]
 * </pre>
 *
 * The registry URI comes from {@code --mongo-uri} or {@code L1_CONFIG_MONGO_URI}; the database from
 * {@code --database} (default {@code lih_config}). The publish reason may come from {@code L1_PUBLISH_REASON}
 * (CI passes the commit message that way). Exit code 0 = ok, 1 = validation/publish failed, 2 = usage.
 */
public final class L1ConfigCli {

    static final String DEFAULT_DIR = "src/main/resources/l1-config";
    private static final Set<String> FLAGS = Set.of("dry-run");

    private final PrintStream out;
    private final Function<String, String> env;
    private final Function<String, ConfigStore> storeFactory;

    L1ConfigCli(PrintStream out, Function<String, String> env, Function<String, ConfigStore> storeFactory) {
        this.out = out;
        this.env = env;
        this.storeFactory = storeFactory;
    }

    public static void main(String[] args) {
        ((Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).setLevel(Level.WARN);
        Map<String, MongoClient> clients = new HashMap<>();
        int code;
        try {
            code = new L1ConfigCli(System.out, System::getenv, uriAndDb -> {
                int sep = uriAndDb.lastIndexOf('|');
                MongoClient c = clients.computeIfAbsent(uriAndDb.substring(0, sep), MongoClients::create);
                return new MongoConfigStore(c.getDatabase(uriAndDb.substring(sep + 1))).ensureIndexes();
            }).run(args);
        } finally {
            clients.values().forEach(MongoClient::close);
        }
        System.exit(code);
    }

    int run(String[] args) {
        if (args.length == 0) {
            return usage("missing command");
        }
        Map<String, String> opts;
        try {
            opts = parse(args);
        } catch (IllegalArgumentException e) {
            return usage(e.getMessage());
        }
        try {
            return switch (args[0]) {
                case "validate" -> validate(opts);
                case "publish" -> publish(opts);
                case "status" -> status(opts);
                case "redirect" -> redirect(opts);
                default -> usage("unknown command '" + args[0] + "'");
            };
        } catch (UsageException e) {
            return usage(e.getMessage());
        }
    }

    private int validate(Map<String, String> o) {
        String envName = o.getOrDefault("env", "local");
        String uri = uri(o, false);
        Publisher publisher = uri == null
                ? new Publisher(new InMemoryConfigStore(), true, Clock.systemUTC())
                : new Publisher(store(o, uri), false, Clock.systemUTC());
        if (uri == null) {
            out.println("(offline: no registry given, version checks skipped)");
        }
        PublishReport report = publisher.validate(bundle(o), envName);
        out.print(report.toText());
        return report.ok() ? 0 : 1;
    }

    private int publish(Map<String, String> o) {
        String envName = required(o, "env");
        String reason = o.containsKey("reason") ? required(o, "reason") : env.apply("L1_PUBLISH_REASON");
        if (reason == null || reason.isBlank()) {
            throw new UsageException("--reason is required (or set L1_PUBLISH_REASON)");
        }
        String by = by(o);
        String uri = uri(o, true);
        PublishReport report = new Publisher(store(o, uri), false, Clock.systemUTC())
                .publish(bundle(o), envName, by, reason, o.containsKey("dry-run"));
        out.print(report.toText());
        return report.ok() ? 0 : 1;
    }

    private int status(Map<String, String> o) {
        ConfigStore store = store(o, uri(o, true));
        out.println("recipes:");
        store.pointers(DocType.RECIPE).forEach((ref, v) -> out.println("  " + ref + "@" + v));
        out.println("operations:");
        store.pointers(DocType.OPERATION).forEach((ref, v) -> out.println("  " + ref + "@" + v));
        out.println("resources:");
        for (StoredResource r : store.resources()) {
            out.println("  " + r.resource() + "  (updated " + r.updatedAt() + " by " + r.updatedBy() + ")");
        }
        return 0;
    }

    private int redirect(Map<String, String> o) {
        String ref = required(o, "ref");
        int from = number(o, "from");
        int to = number(o, "to");
        String reason = required(o, "reason");
        ConfigStore store = store(o, uri(o, true));
        for (int v : new int[] {from, to}) {
            if (store.find(DocType.RECIPE, ref, v).isEmpty()) {
                out.println("recipe " + ref + "@" + v + " is not published");
                return 1;
            }
        }
        store.addRedirect(new Redirect(ref, from, to, by(o), Clock.systemUTC().instant(), reason));
        out.println(from == to
                ? "cleared redirects for " + ref + "@" + from
                : "redirected " + ref + "@" + from + " -> @" + to + " (applies to open cases on their next step)");
        return 0;
    }

    private ConfigBundle bundle(Map<String, String> o) {
        return ConfigBundle.load(Path.of(o.getOrDefault("dir", DEFAULT_DIR)), new SchemaValidator());
    }

    private ConfigStore store(Map<String, String> o, String uri) {
        return storeFactory.apply(uri + "|" + o.getOrDefault("database", "lih_config"));
    }

    private String uri(Map<String, String> o, boolean required) {
        String uri = o.getOrDefault("mongo-uri", env.apply("L1_CONFIG_MONGO_URI"));
        if (required && (uri == null || uri.isBlank())) {
            throw new UsageException("registry not given: pass --mongo-uri or set L1_CONFIG_MONGO_URI");
        }
        return uri == null || uri.isBlank() ? null : uri;
    }

    private String by(Map<String, String> o) {
        String by = o.get("by");
        if (by == null) {
            by = env.apply("GITHUB_ACTOR");
        }
        if (by == null) {
            by = env.apply("USER");
        }
        if (by == null || by.isBlank()) {
            throw new UsageException("--by is required");
        }
        return by;
    }

    private static String required(Map<String, String> o, String name) {
        String v = o.get(name);
        if (v == null || v.isBlank()) {
            throw new UsageException("--" + name + " is required");
        }
        return v;
    }

    private static int number(Map<String, String> o, String name) {
        try {
            return Integer.parseInt(required(o, name));
        } catch (NumberFormatException e) {
            throw new UsageException("--" + name + " must be a number");
        }
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> opts = new HashMap<>();
        for (int i = 1; i < args.length; i++) {
            if (!args[i].startsWith("--")) {
                throw new IllegalArgumentException("unexpected argument '" + args[i] + "'");
            }
            String name = args[i].substring(2);
            if (FLAGS.contains(name)) {
                opts.put(name, "true");
            } else if (i + 1 < args.length) {
                opts.put(name, args[++i]);
            } else {
                throw new IllegalArgumentException("--" + name + " needs a value");
            }
        }
        return opts;
    }

    private int usage(String problem) {
        out.println("error: " + problem);
        out.println("""
                usage:
                  validate [--dir DIR] [--env ENV] [--mongo-uri URI]
                  publish  --env ENV --reason TEXT [--dir DIR] [--by NAME] [--dry-run] [--mongo-uri URI]
                  status   [--mongo-uri URI]
                  redirect --ref REF --from N --to M --reason TEXT [--by NAME] [--mongo-uri URI]
                registry: --mongo-uri or L1_CONFIG_MONGO_URI; --database (default lih_config)""");
        return 2;
    }

    private static final class UsageException extends RuntimeException {
        UsageException(String message) {
            super(message);
        }
    }
}
