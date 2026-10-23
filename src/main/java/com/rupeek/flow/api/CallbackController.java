package com.rupeek.flow.api;

import com.mongodb.client.MongoClient;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Compatibility surface for lender callbacks, shaped like LenderCore's {@code CallbackController}
 * ({@code POST /v1/callback?source=&purpose=}). The route table ({@code flow.api.callbacks.<SOURCE_PURPOSE>})
 * says which event to signal and how to find the waiting case; the generic signal path is {@link CaseService}.
 *
 * <p>Correlation mirrors LenderCore {@code AxisTimbleCallbackProcessor.processCallback} (line 49-50):
 * {@code findByCustomerIdAndLenderSlugOrderByCreatedOnDesc(customer_id, "axis").get(0)} on collection
 * {@code LenderCustomerPrecheck} ({@code PrecheckEntity.java:17}; {@code createdOn} is stored as
 * {@code creation_date}, {@code AbstractEntity.java:~30}) — newest record wins. Its {@code loanRequestId} gives the
 * case id.
 */
@RestController
public class CallbackController {

    private static final Logger LOG = LoggerFactory.getLogger(CallbackController.class);

    private final ApiProperties props;
    private final MongoClient mongo;
    private final CaseService cases;

    public CallbackController(ApiProperties props, MongoClient mongo, CaseService cases) {
        this.props = props;
        this.mongo = mongo;
        this.cases = cases;
    }

    @PostMapping("/v1/callback")
    public Map<String, String> callback(@RequestBody Map<String, Object> body, @RequestParam String source,
                                        @RequestParam String purpose) {
        // Same key construction as LenderLoanProcessorFactoryImpl.getCallbackProcessor (line 36).
        String key = source + "_" + purpose.replace("'", "");
        LOG.info("POST /v1/callback source={} purpose={}", source, purpose);
        ApiProperties.CallbackRoute route = props.getCallbacks().get(key);
        if (route == null) {
            // LenderCore falls back to a default processor that reports success and does nothing.
            LOG.warn("No callback route for {}; acknowledged without action", key);
            return Map.of("status", "Y");
        }
        Object customerId = body.get(route.getCustomerIdField());
        if (customerId == null || customerId.toString().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "missing " + route.getCustomerIdField());
        }
        Document precheck = mongo.getDatabase(props.getCallbackStore().getDatabase())
                .getCollection(props.getCallbackStore().getCollection())
                .find(Filters.and(Filters.eq("customerId", customerId.toString()),
                        Filters.eq("lenderSlug", route.getLenderSlug())))
                .sort(Sorts.descending("creation_date"))
                .first();
        Object loanRequestId = precheck == null ? null : precheck.get("loanRequestId");
        if (loanRequestId == null) {
            LOG.warn("Callback {}: no precheck record for the given customer", key);
            throw new ApiException(HttpStatus.NOT_FOUND, "no precheck record found for callback");
        }
        String workflowId = route.getWorkflowIdPrefix() + loanRequestId;
        LOG.info("Callback {} resolved workflowId={}", key, workflowId);
        cases.signal(workflowId, route.getEventName(), body);
        return Map.of("status", "Y");
    }
}
