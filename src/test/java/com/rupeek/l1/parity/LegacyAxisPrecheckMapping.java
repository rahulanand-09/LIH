package com.rupeek.l1.parity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * TEST ONLY: a faithful copy of how lendercore builds the Axis dedupe request, used as the parity oracle.
 *
 * <p>Copied from lender-core (branch release-AWS-2.0; identical on main for this code path):
 * <ul>
 *   <li>{@code AxisApiService.populateAxisPrecheckRequest}, {@code getDedupeIds}, {@code getCustomerAddresses}</li>
 *   <li>{@code AxisPrecheckProcessor.invokePrecheck}: {@code clientLoanId}, {@code customerId}</li>
 *   <li>{@code AxisClient.initiatePreCheck}: {@code agreementId} from {@code preCheckAgreementId}</li>
 *   <li>{@code dto.axis.AxisPrecheckRequest} (field order, JSON names, nulls serialised)</li>
 *   <li>{@code customeraccountcreation.dto.core.Customer}: every field a {@code String};
 *       {@code presentAddress} defaults to an empty address</li>
 * </ul>
 * Do not "improve" this class: its value is that it behaves exactly like the legacy code, quirks included
 * (split on a single space, first/last not upper-cased when there is no proof name).
 */
public final class LegacyAxisPrecheckMapping {

    private static final ObjectMapper JSON = new ObjectMapper();

    private LegacyAxisPrecheckMapping() {
    }

    /** The JSON body lendercore would send for this Core customer (throws where lendercore would throw). */
    public static JsonNode request(Map<String, Object> coreCustomer, String agreementId, String clientLoanId) {
        Customer customer = JSON.convertValue(coreCustomer, Customer.class);   // Jackson coerces numbers to String
        AxisPrecheckRequest r = new AxisPrecheckRequest();
        r.clientLoanId = clientLoanId;                                       // AxisPrecheckProcessor
        r.customerId = customer.id;
        populateAxisPrecheckRequest(customer, r);                            // AxisApiService
        r.agreementId = agreementId;                                         // AxisClient
        return JSON.valueToTree(r);
    }

    // ---- AxisApiService, verbatim apart from field access

    static void populateAxisPrecheckRequest(Customer customer, AxisPrecheckRequest axisPrecheckRequest) {
        String customerProofName = customer.customerProofName;
        if (!(customerProofName == null || customerProofName.isEmpty())) {   // org.springframework.util.StringUtils.isEmpty
            String[] nameParts = customerProofName.split(" ");
            if (nameParts.length == 3) {
                axisPrecheckRequest.firstName = nameParts[0].toUpperCase();
                axisPrecheckRequest.middleName = nameParts[1].toUpperCase();
                axisPrecheckRequest.lastName = nameParts[2].toUpperCase();
            } else if (nameParts.length == 2) {
                axisPrecheckRequest.firstName = nameParts[0].toUpperCase();
                axisPrecheckRequest.lastName = nameParts[1].toUpperCase();
            } else if (nameParts.length == 1) {
                axisPrecheckRequest.firstName = nameParts[0].toUpperCase();
                axisPrecheckRequest.lastName = ".";
            } else {
                axisPrecheckRequest.firstName = nameParts[0].toUpperCase();
                axisPrecheckRequest.middleName = nameParts[1].toUpperCase();
                String lastName = String.join(" ", Arrays.copyOfRange(nameParts, 2, nameParts.length));
                axisPrecheckRequest.lastName = lastName.toUpperCase();
            }
        } else {
            axisPrecheckRequest.firstName = customer.firstName;
            axisPrecheckRequest.lastName = customer.lastName;
        }

        axisPrecheckRequest.dateOfBirth = customer.dob;
        axisPrecheckRequest.gender = customer.gender;
        axisPrecheckRequest.email = customer.email;
        axisPrecheckRequest.dateOfBirthAsPerPan = customer.dob;
        axisPrecheckRequest.dedupeType = "single";
        axisPrecheckRequest.mobileNumber = customer.customerPhone;
        axisPrecheckRequest.nameAsPerPan = customer.customerPanName;

        axisPrecheckRequest.dedupeIds = getDedupeIds(customer);
        axisPrecheckRequest.address = getCustomerAddresses(customer);
        axisPrecheckRequest.aadharNumber = customer.aadhar;
    }

    private static List<DedupeId> getDedupeIds(Customer customer) {
        List<DedupeId> dedupeIds = new ArrayList<>();
        dedupeIds.add(new DedupeId("PAN", customer.pan));
        dedupeIds.add(new DedupeId("AADHAR", customer.aadhar));
        return dedupeIds;
    }

    private static List<Address> getCustomerAddresses(Customer customer) {
        List<Address> addresses = new ArrayList<>();
        String formattedAddress = customer.customerAddress
                .replaceAll("\\s+", " ")
                .trim();
        addresses.add(new Address("PERMANENT", formattedAddress, customer.permanentAddress.city,
                customer.permanentAddress.state, customer.permanentAddress.pin));
        addresses.add(new Address("COMMUNICATION", formattedAddress, customer.presentAddress.city,
                customer.presentAddress.state, customer.presentAddress.pin));
        return addresses;
    }

    // ---- DTOs (shape and JSON names of the legacy classes)

    @JsonIgnoreProperties(ignoreUnknown = true)
    static final class Customer {
        public String id;
        public String firstName;
        public String lastName;
        public String customerPhone;
        public String customerProofName;
        public String customerPanName;
        public String customerAddress;
        public String email;
        public String gender;
        public String dob;
        public String aadhar;
        public String pan;
        public PermanentAddress permanentAddress;
        public PermanentAddress presentAddress = new PermanentAddress();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static final class PermanentAddress {
        public String houseno;
        public String street;
        public String locality;
        public String city;
        public String state;
        public String pin;
    }

    static final class AxisPrecheckRequest {
        @JsonProperty("agreement_id") public String agreementId;
        @JsonProperty("customer_id") public String customerId;
        @JsonProperty("application_id") public String applicationId;
        @JsonProperty("client_loan_id") public String clientLoanId;
        @JsonProperty("model_name") public String modelName = "CLM1";
        @JsonProperty("customer_type") public String customerType = "individual";
        @JsonProperty("first_name") public String firstName;
        @JsonProperty("middle_name") public String middleName;
        @JsonProperty("last_name") public String lastName;
        @JsonProperty("name_as_per_pan") public String nameAsPerPan;
        @JsonProperty("date_of_birth") public String dateOfBirth;
        @JsonProperty("date_of_birth_as_per_pan") public String dateOfBirthAsPerPan;
        @JsonProperty("mobile_number") public String mobileNumber;
        @JsonProperty("email") public String email;
        @JsonProperty("gender") public String gender;
        @JsonProperty("dedupe_type") public String dedupeType;
        @JsonProperty("aadhar_number") public String aadharNumber;
        @JsonProperty("dedupe_ids") public List<DedupeId> dedupeIds;
        @JsonProperty("address") public List<Address> address;
    }

    record DedupeId(@JsonProperty("id_type") String idType, @JsonProperty("id_value") String idValue) {
    }

    record Address(@JsonProperty("address_type") String addressType,
                   @JsonProperty("address_value") String addressValue,
                   @JsonProperty("address_city") String addressCity,
                   @JsonProperty("address_state") String addressState,
                   @JsonProperty("address_pincode") String addressPincode) {
    }
}
