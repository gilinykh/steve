package de.rwth.idsg.steve.web.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import de.rwth.idsg.steve.ocpp.OcppTransport;
import de.rwth.idsg.steve.repository.dto.ChargePointSelect;
import de.rwth.idsg.steve.web.dto.ocpp.RemoteStartTransactionParams;
import lombok.Getter;
import lombok.experimental.Accessors;

import java.util.Arrays;

@Getter
@Accessors(fluent = true)
public class TransactionStartRequest {

    private String endpointAddress;
    private String chargeBoxId;
    private Integer connectorId;
    private String ocppIdTag;

    @JsonCreator
    public TransactionStartRequest(@JsonProperty("endpointAddress") String endpointAddress,
                                   @JsonProperty("chargeBoxId") String chargeBoxId,
                                   @JsonProperty("connectorId") Integer connectorId,
                                   @JsonProperty("ocppIdTag") String ocppIdTag) {
        this.endpointAddress = endpointAddress;
        this.chargeBoxId = chargeBoxId;
        this.connectorId = connectorId;
        this.ocppIdTag = ocppIdTag;
    }

    public RemoteStartTransactionParams asParams(OcppTransport transport) {
        RemoteStartTransactionParams params = new RemoteStartTransactionParams();
        params.setConnectorId(connectorId());
        params.setIdTag(ocppIdTag());
        params.setChargePointSelectList(Arrays.asList(new ChargePointSelect(transport, chargeBoxId(), endpointAddress())));
        return params;
    }
}
