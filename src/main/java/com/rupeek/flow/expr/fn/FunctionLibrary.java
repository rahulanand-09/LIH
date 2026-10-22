package com.rupeek.flow.expr.fn;

import dev.cel.common.CelFunctionDecl;
import dev.cel.runtime.CelFunctionBinding;

import java.util.List;

/**
 * A set of Java functions made callable from CEL. Functions are only ever added or fixed, never changed in
 * meaning, so config published against version n keeps working on any later version.
 */
public interface FunctionLibrary {

    /** Short name, e.g. {@code rk}. */
    String name();

    /** Library version recorded with every published config version. */
    String version();

    /** Compile-time signatures. */
    List<CelFunctionDecl> declarations();

    /** Runtime implementations, matched to declarations by overload id. */
    List<CelFunctionBinding> bindings();
}
