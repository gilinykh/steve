package de.rwth.idsg.steve.web.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import de.rwth.idsg.steve.ocpp.OcppTransport;
import de.rwth.idsg.steve.repository.dto.ChargePointSelect;
import de.rwth.idsg.steve.web.dto.ocpp.RemoteStopTransactionParams;
import lombok.Getter;
import lombok.experimental.Accessors;

import java.util.Arrays;

@Getter
@Accessors(fluent = true)
public class TransactionStopRequest {

    private final Integer transactionId;
    private final String chargeBoxId;

    @JsonCreator
    public TransactionStopRequest(@JsonProperty("transactionId") Integer transactionId,
                                  @JsonProperty("chargeBoxId") String chargeBoxId) {
        this.transactionId = transactionId;
        this.chargeBoxId = chargeBoxId;
    }

    public RemoteStopTransactionParams asParams(OcppTransport transport, String endpointAddress) {
        RemoteStopTransactionParams params = new RemoteStopTransactionParams();
        params.setTransactionId(transactionId());
        params.setChargePointSelectList(Arrays.asList(new ChargePointSelect(transport, chargeBoxId(), endpointAddress)));
        return params;
    }
}
