package com.rupeek.l1.expr;

import com.rupeek.l1.expr.fn.FunctionLibrary;
import dev.cel.bundle.Cel;
import dev.cel.bundle.CelBuilder;
import dev.cel.bundle.CelFactory;
import dev.cel.common.CelOptions;
import dev.cel.common.CelValidationException;
import dev.cel.common.CelValidationResult;
import dev.cel.common.types.SimpleType;
import dev.cel.extensions.CelExtensions;
import dev.cel.parser.CelStandardMacro;
import dev.cel.runtime.CelEvaluationException;
import dev.cel.runtime.CelRuntime;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link ExpressionEngine} backed by CEL ({@code dev.cel:cel}).
 *
 * <p>One CEL environment per {@link ScopeDecl}, built lazily and cached: planner runtime, standard macros,
 * the strings / math / lists / regex / encoders / sets / bindings / optional extensions, and the registered
 * {@link FunctionLibrary} functions. CEL native types (reflection over Java classes) are never enabled.
 */
public final class CelExpressionEngine implements ExpressionEngine {

    private final CelOptions options;
    private final List<FunctionLibrary> libraries;
    private final Map<ScopeDecl, Cel> environments = new ConcurrentHashMap<>();

    public CelExpressionEngine(ExprLimits limits, List<FunctionLibrary> libraries) {
        this.options = CelOptions.current()
                .enableOptionalSyntax(true)
                .enableHeterogeneousNumericComparisons(true)
                .maxExpressionCodePointSize(limits.maxExpressionLength())
                .maxParseRecursionDepth(limits.maxParseDepth())
                .comprehensionMaxIterations(limits.maxIterations())
                .build();
        this.libraries = List.copyOf(libraries);
    }

    @Override
    public CompiledExpr compile(String source, ScopeDecl scope, String configPath) {
        if (source == null || source.isBlank()) {
            throw new ExprCompileException(configPath, String.valueOf(source), "expression is empty");
        }
        Cel cel = environments.computeIfAbsent(scope, this::buildEnvironment);
        CelValidationResult result = cel.compile(source, configPath);
        if (result.hasError()) {
            throw new ExprCompileException(configPath, source, result.getIssueString());
        }
        try {
            CelRuntime.Program program = cel.createProgram(result.getAst());
            return new CompiledExpr(source, configPath, scope, program);
        } catch (CelValidationException | CelEvaluationException e) {
            throw new ExprCompileException(configPath, source, e.getMessage());
        }
    }

    @Override
    public Object eval(CompiledExpr expr, Map<String, Object> vars) {
        try {
            Map<String, Object> celVars = new HashMap<>();
            for (String name : expr.scope().variables()) {
                celVars.put(name, CelValues.toCel(vars.get(name)));
            }
            return CelValues.fromCel(expr.program().eval(celVars));
        } catch (CelEvaluationException e) {
            throw new ExprEvalException(expr.configPath(), expr.source(), rootMessage(e), e);
        } catch (RuntimeException e) {
            // A function binding or value conversion failed; still a deterministic config/data error.
            throw new ExprEvalException(expr.configPath(), expr.source(), rootMessage(e), e);
        }
    }

    private Cel buildEnvironment(ScopeDecl scope) {
        CelBuilder builder = CelFactory.plannerCelBuilder()
                .setOptions(options)
                .setStandardMacros(CelStandardMacro.STANDARD_MACROS)
                .addCompilerLibraries(
                        CelExtensions.strings(), CelExtensions.math(), CelExtensions.lists(), CelExtensions.regex(),
                        CelExtensions.encoders(options), CelExtensions.sets(options), CelExtensions.bindings(),
                        CelExtensions.optional())
                .addRuntimeLibraries(
                        CelExtensions.strings(), CelExtensions.math(), CelExtensions.lists(), CelExtensions.regex(),
                        CelExtensions.encoders(options), CelExtensions.sets(options), CelExtensions.optional());
        for (FunctionLibrary library : libraries) {
            builder.addFunctionDeclarations(library.declarations());
            builder.addFunctionBindings(library.bindings());
        }
        for (String variable : scope.variables()) {
            builder.addVar(variable, SimpleType.DYN);
        }
        return builder.build();
    }

    /**
     * Prefers the message of the first non-CEL exception in the chain (e.g. an {@code rk.*} function's
     * IllegalArgumentException), which says what was wrong; otherwise CEL's own message.
     */
    private static String rootMessage(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            boolean celInternal = t.getClass().getName().startsWith("dev.cel.");
            if (!celInternal && t.getMessage() != null && !t.getMessage().isBlank()) {
                return t.getMessage();
            }
        }
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
