package com.rupeek.l1.model;

/** Selects the executor for an operation. New kinds are added here plus one executor class. */
public enum OperationKind {
    HTTP,
    MONGO,
    MAP,
    GEN
}
