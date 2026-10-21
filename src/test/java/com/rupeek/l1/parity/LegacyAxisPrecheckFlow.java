package com.rupeek.l1.parity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * TEST ONLY: faithful copies of the rest of lendercore's Axis precheck logic (branch release-AWS-2.0), used as
 * parity oracles. Do not "improve" them.
 * <ul>
 *   <li>{@code PrecheckService.fetchPrecheckData}: flags + Timble to SUCCESS / FAILURE / PENDING, messages</li>
 *   <li>{@code AxisApiService.checkPreCheckStatus} + {@code AxisClient.checkPreCheckStatus}: status request</li>
 *   <li>{@code AxisTimbleCallbackProcessor.processCallback}: Timble result and message</li>
 *   <li>{@code RupeekWebServiceProvider.updatePrecheckFailureResponse}: {@code lendercustomerprofile} JSON
 *       ({@code new ObjectMapper().writeValueAsString(PrecheckEntity)})</li>
 * </ul>
 */
public final class LegacyAxisPrecheckFlow {

    /** Spring Boot's default ObjectMapper writes nulls, like this one (lendercore has no Jackson customisation). */
    private static final ObjectMapper JSON = new ObjectMapper();

    private LegacyAxisPrecheckFlow() {
    }

    // ---------------------------------------------------------------- status decision

    public record StatusDecision(String outcome, Boolean approved, ResponseMap responseMap) {
    }

    /** {@code fetchPrecheckData} from the status call onwards; {@code storedTimble} is responseMap.timble or null. */
    public static StatusDecision decide(Map<String, String> axis, ResponseMapStatus storedTimble) throws Exception {
        ResponseMap responseMap = new ResponseMap();
        responseMap.timble = storedTimble;
        ResponseMapStatus axisPreCheckStatus = new ResponseMapStatus();
        Boolean approved = null;
        List<String> flags = Arrays.asList(axis.get("dedupe"), axis.get("pan"), axis.get("ramp"), axis.get("hunter"));
        String timbleStatus = Optional.ofNullable(responseMap.timble).map(t -> t.status).orElse("FAILURE");
        String mappedTimbleStatus;
        if ("SUCCESS".equalsIgnoreCase(timbleStatus)) {
            mappedTimbleStatus = "Y";
        } else if (responseMap.timble == null) {
            mappedTimbleStatus = "WIP";
        } else {
            mappedTimbleStatus = "N";
        }
        flags = new ArrayList<>(flags);
        flags.add(mappedTimbleStatus);
        responseMap.pan = new ResponseMapStatus(value(axis.get("pan")), "");
        responseMap.hunter = new ResponseMapStatus(value(axis.get("hunter")), "");
        responseMap.dedupe = new ResponseMapStatus(value(axis.get("dedupe")), "");
        responseMap.ramp = new ResponseMapStatus(value(axis.get("ramp")), "");
        String outcome;
        if (flags.stream().allMatch(s -> Objects.equals(s, "Y"))) {
            approved = true;
            axisPreCheckStatus.status = "SUCCESS";
            axisPreCheckStatus.statusMessage = "PreCheck success for the loanRequest";
            outcome = "SUCCESS";
        } else if (flags.contains("N")) {
            approved = false;
            ResponseMap temp = new ResponseMap();
            temp.dedupe = responseMap.dedupe;
            temp.pan = responseMap.pan;
            temp.ramp = responseMap.ramp;
            temp.hunter = responseMap.hunter;
            temp.timble = responseMap.timble;
            axisPreCheckStatus.status = "FAILURE";
            axisPreCheckStatus.statusMessage = "Precheck failure received from lender services : " + JSON.writeValueAsString(temp);
            outcome = "FAILURE";
        } else if (flags.contains("WIP")) {
            axisPreCheckStatus.status = "PENDING";
            axisPreCheckStatus.statusMessage = "One of the services is giving WIP";
            outcome = "PENDING";
        } else {
            outcome = "UNEXPECTED";
        }
        responseMap.axisPreCheckStatus = axisPreCheckStatus;
        return new StatusDecision(outcome, approved, responseMap);
    }

    /** {@code AxisPreCheckStatus.valueOf(x).getValue()}: throws for anything but Y / WIP / N. */
    private static String value(String flag) {
        return switch (flag) {
            case "Y" -> "SUCCESS";
            case "WIP" -> "PENDING";
            case "N" -> "FAILURE";
            default -> throw new IllegalArgumentException("No enum constant AxisPreCheckStatus." + flag);
        };
    }

    /** The status request: a fresh AxisPrecheckRequest with the three ids and the agreement id. */
    public static JsonNode statusRequest(String applicantId, String clientLoanId, String customerId, String agreementId) {
        LegacyAxisPrecheckMapping.AxisPrecheckRequest r = new LegacyAxisPrecheckMapping.AxisPrecheckRequest();
        r.applicationId = applicantId;
        r.clientLoanId = clientLoanId;
        r.customerId = customerId;
        r.agreementId = agreementId;
        return JSON.valueToTree(r);
    }

    // ---------------------------------------------------------------- Timble callback

    public record TimbleResult(boolean ok, String status, String message) {
    }

    static final List<String> AXIS_TIMBLE_CALLBACK_FIELDS = List.of("product_id", "customer_id", "timble_status",
            "name_match", "aadhar_match", "timble_error_message");

    public static TimbleResult timbleCallback(Map<String, Object> request) throws Exception {
        Map<String, Object> filteredMap = request.entrySet().stream()
                .filter(entry -> AXIS_TIMBLE_CALLBACK_FIELDS.contains(entry.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        String nameMatch = Objects.toString(filteredMap.get("name_match"), "").trim();
        String aadharMatch = Objects.toString(filteredMap.get("aadhar_match"), "").trim();
        boolean validationFailed = "N".equalsIgnoreCase(nameMatch) || "N".equalsIgnoreCase(aadharMatch);
        List<String> timbleMessageFields = Arrays.asList("name_match", "aadhar_match", "timble_error_message");
        Map<String, Object> timbleSummaryMap = filteredMap.entrySet().stream()
                .filter(entry -> timbleMessageFields.contains(entry.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        String timbleSummaryJson = "";
        if (validationFailed && !timbleSummaryMap.isEmpty()) {
            timbleSummaryJson = JSON.writeValueAsString(timbleSummaryMap);
        }
        String status = validationFailed ? "FAILURE" : "SUCCESS";
        String message = validationFailed ? "Timble Precheck Failure : " + timbleSummaryJson : "Timble Precheck Successful";
        return new TimbleResult(!validationFailed, status, message);
    }

    // ---------------------------------------------------------------- rupeekweb failure notification

    /** {@code new ObjectMapper().writeValueAsString(entity)} for a PrecheckEntity holding these values. */
    public static String lenderCustomerProfile(PrecheckEntity entity) throws Exception {
        return new ObjectMapper().writeValueAsString(entity);
    }

    // ---------------------------------------------------------------- DTOs (legacy shapes)

    public static final class ResponseMap {
        public String customerAcceptanceStatus;
        public String customerBlacklistStatus;
        public ResponseMapStatus axisPreCheckStatus;
        public ResponseMapStatus dedupe;
        public ResponseMapStatus pan;
        public ResponseMapStatus ramp;
        public ResponseMapStatus hunter;
        public ResponseMapStatus timble;
    }

    public static final class ResponseMapStatus {
        public String status;
        public String statusMessage;

        public ResponseMapStatus() {
        }

        public ResponseMapStatus(String status, String statusMessage) {
            this.status = status;
            this.statusMessage = statusMessage;
        }
    }

    /** AbstractEntity + PrecheckEntity fields; {@code @JsonIgnore} as in the legacy base class. */
    public static final class PrecheckEntity {
        public String id;
        @JsonIgnore public int version;
        @JsonIgnore public java.util.Date createdOn;
        @JsonIgnore public java.util.Date lastUpdatedOn;
        public String loanRequestId;
        public String customerId;
        public String precheckStatus;
        public String lenderSlug;
        public String lenderCustomerId;
        public String rejectionReason;
        public ResponseMap responseMap;
        public boolean approved;
        public Object retry;
        public String overallstpstatus;
        public String unsecurestpstatus;
        public String stpstatus;
        public List<Object> lenderstpstatus;
        public String autofundtransferstatus;
        public String applicantId;
        @JsonProperty("client_loan_id") public String clientLoanId;
        public String loanProcess;
        public String timbleReferenceId;
    }
}
