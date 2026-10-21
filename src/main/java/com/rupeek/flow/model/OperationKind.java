package com.rupeek.flow.model;

/**
 * What a leaf TASK's single Activity invocation does. SOAP/SFTP are registered now, implemented later.
 * {@code RECIPE} delegates the whole node to a published L1 recipe ({@code com.rupeek.l1.api.TaskActivity}),
 * rather than a flow-native generic operation — this is what every Axis precheck node uses today.
 * {@code CUSTOM} is author-provided, flow-native code (not a platform-maintained kind, not an L1 recipe) —
 * {@code taskRef} names it, same generic {@code com.rupeek.flow.activity.TaskActivity} contract as HTTP/MONGO/etc.
 */
public enum OperationKind {
    HTTP, SOAP, SFTP, MONGO, MAP, GEN, RECIPE, CUSTOM
}
