package de.rwth.idsg.steve.web.api;


import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import de.rwth.idsg.steve.web.dto.TransactionQueryForm;
import lombok.Getter;
import lombok.experimental.Accessors;

@Getter
@Accessors(fluent = true)
public class TransactionListRequest {

    private final String idTag;

    @JsonCreator
    public TransactionListRequest(@JsonProperty("idTag") String idTag) {
        this.idTag = idTag;
    }

    public TransactionQueryForm asQuery() {
        TransactionQueryForm query = new TransactionQueryForm();
        query.setType(TransactionQueryForm.QueryType.ACTIVE);
        query.setOcppIdTag(idTag());
        return query;
    }
}
