package com.rupeek.l1.intercept;

import com.rupeek.l1.model.OperationDef;
import com.rupeek.l1.ops.ExecContext;

import java.util.Map;

/**
 * One operation call as seen by interceptors. Expression results the interceptors need (audit keys and the
 * audit request type) are evaluated by the engine beforehand, so interceptors never evaluate config.
 *
 * @param audit null when the operation is not audited
 */
public record OperationCall(
        String caseId,
        String recipeRef,
        int recipeVersion,
        String lender,
        String stepId,
        String opRef,
        OperationDef def,
        AuditInfo audit,
        Map<String, Object> params,
        ExecContext ctx) {

    /**
     * @param requestType value for {@code LenderApiAuditLog.requestType}; must be an existing lendercore
     *                    {@code ApiRequestType} name until lendercore adds new ones
     */
    public record AuditInfo(String loanRequestId, String customerId, String requestType) {
    }
}
