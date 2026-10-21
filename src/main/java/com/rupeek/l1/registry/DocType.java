package com.rupeek.l1.registry;

/**
 * The immutable, versioned document types. Resources are live and unversioned. {@code FLOW} is
 * {@code com.rupeek.flow.model.FlowDef} config — same store, same immutable-version/latest-pointer mechanics as
 * a recipe, just no CEL-compile step (plain Jackson parse is enough to validate one).
 */
public enum DocType {
    RECIPE,
    OPERATION,
    FLOW
}
