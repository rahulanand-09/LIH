package com.rupeek.flow.model;

/**
 * What a leaf TASK's single Activity invocation does. {@code HTTP}/{@code MONGO}/{@code MAP}/{@code GEN} are
 * native, implemented by {@link com.rupeek.flow.task.TaskActivityImpl} — every Axis precheck node uses one
 * of these today, config inlined directly into the node's own {@code fields} (see
 * {@code docs/flow-lld.md}'s native-executors update for the per-kind {@code fields} shape). SOAP/SFTP are
 * registered now, implemented later. {@code CUSTOM} is author-provided, flow-native code (not a
 * platform-maintained kind) — {@code taskRef} names it, same generic
 * {@code com.rupeek.flow.task.TaskActivity} contract as the rest.
 */
public enum OperationKind {
    HTTP, SOAP, SFTP, MONGO, MAP, GEN, CUSTOM
}
