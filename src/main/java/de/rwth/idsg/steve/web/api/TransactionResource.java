package de.rwth.idsg.steve.web.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import de.rwth.idsg.steve.ocpp.OcppProtocol;
import de.rwth.idsg.steve.ocpp.OcppTransport;
import de.rwth.idsg.steve.repository.ChargePointRepository;
import de.rwth.idsg.steve.repository.TransactionRepository;
import de.rwth.idsg.steve.repository.dto.ChargePoint;
import de.rwth.idsg.steve.repository.dto.Transaction;
import de.rwth.idsg.steve.repository.dto.TransactionDetails;
import de.rwth.idsg.steve.service.ChargePointService15_Client;
import de.rwth.idsg.steve.service.ChargePointService16_Client;
import jooq.steve.db.tables.records.ChargeBoxRecord;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static jooq.steve.db.tables.Connector.CONNECTOR;
import static jooq.steve.db.tables.Transaction.TRANSACTION;

@Slf4j
@RestController
@RequestMapping("/api")
public class TransactionResource {

    @Qualifier("ChargePointService15_Client")
    private final ChargePointService15_Client client15;
    @Qualifier("ChargePointService16_Client")
    private final ChargePointService16_Client client16;

    private final TransactionRepository transactionRepository;

    private final TransactionService transactionService;

    private final ObjectMapper objectMapper;

    private final ChargePointRepository chargePointRepository;

    public TransactionResource(@Qualifier("ChargePointService15_Client") ChargePointService15_Client client15,
                               @Qualifier("ChargePointService16_Client") ChargePointService16_Client client16,
                               TransactionRepository transactionRepository, TransactionService transactionService,
                               ChargePointRepository chargePointRepository) {
        this.client15 = client15;
        this.client16 = client16;
        this.transactionRepository = transactionRepository;
        this.transactionService = transactionService;
        this.chargePointRepository = chargePointRepository;
        this.objectMapper = new ObjectMapper();
        objectMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        objectMapper.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
    }

    private static Runnable versionedOperation(ChargeBoxRecord chargeBoxRecord,
                                               BiFunction<OcppTransport, String, Integer> v15Operation,
                                               BiFunction<OcppTransport, String, Integer> v16Operation) {
        OcppProtocol protocol = OcppProtocol.fromCompositeValue(chargeBoxRecord.getOcppProtocol());
        switch(protocol.getVersion()) {
            case V_15:
                return () -> v15Operation.apply(protocol.getTransport(), chargeBoxRecord.getEndpointAddress());
            case V_16:
                return () -> v16Operation.apply(protocol.getTransport(), chargeBoxRecord.getEndpointAddress());
            default:
                throw new IllegalArgumentException("Unsupported OCPP protocol [" + protocol.getVersion() + protocol.getTransport() + "]");
        }
    }

    @PostMapping("/transactions")
    public ResponseEntity<?> start(@RequestBody TransactionStartRequest request) {
        Optional<ChargeBoxRecord> chargeBox = chargeBox(request.chargeBoxId());

        if (!chargeBox.isPresent()) {
            return ResponseEntity.unprocessableEntity().body("Charge box is missing [" + request.chargeBoxId() + "]");
        }

        versionedOperation(chargeBox.get(),
                (transport, endpointAddress) -> client15.remoteStartTransaction(request.asParams(transport, endpointAddress)),
                (transport, endpointAddress) -> client16.remoteStartTransaction(request.asParams(transport, endpointAddress)))
                .run();

        return ResponseEntity.ok().build();
    }

    @PostMapping("/transactions/active")
    public ResponseEntity<?> started(@RequestBody TransactionStartRequest request) {
        int transactionId;
        Optional<ChargeBoxRecord> chargeBox = chargeBox(request.chargeBoxId());

        if (!chargeBox.isPresent()) {
            return ResponseEntity.unprocessableEntity().body("Charge box is missing [" + request.chargeBoxId() + "]");
        }

        versionedOperation(chargeBox.get(),
                (transport, endpointAddress) -> client15.remoteStartTransaction(request.asParams(transport, endpointAddress)),
                (transport, endpointAddress) -> client16.remoteStartTransaction(request.asParams(transport, endpointAddress)))
                .run();

        try {
            transactionId = transactionService.pollStartedTransactionId(request.chargeBoxId(), request.ocppIdTag(), request.connectorId());
        } catch (TransactionBlockedException e) {
            log.info("Couldn't start a transaction on chargeBox: " + request.chargeBoxId(), e);
            return ResponseEntity.badRequest().body(e.getMessage());
        }
        return ResponseEntity.ok(transactionId);
    }

    @GetMapping("/tags/{idTag}/transactions")
    public ResponseEntity<List<Transaction>> userTransactions(@PathVariable String idTag) {
        TransactionListRequest request = new TransactionListRequest(idTag);
        return ResponseEntity.ok(transactionRepository.getTransactions(request.asQuery()));
    }

    @GetMapping("/transactions/{transactionId}")
    public ResponseEntity<TransactionDetails> transactionDetails(@PathVariable Integer transactionId) {
        return ResponseEntity.ok(transactionRepository.getDetails(transactionId));
    }

    @GetMapping(value = "/transactions/{transactionId}/nullable", produces = {MediaType.APPLICATION_JSON_VALUE})
    public ResponseEntity<?> transactionDetailsNullable(@PathVariable Integer transactionId) throws JsonProcessingException {
        TransactionDetails transactionDetails = transactionRepository.getDetails(transactionId);
        return ResponseEntity.ok(objectMapper.writeValueAsString(transactionDetails));
    }

    @GetMapping(value = "/transactions/active")
    public ResponseEntity<?> activeTransactionDetails() throws JsonProcessingException {
        List<Integer> activeTransactionIds = transactionRepository.getActiveTransactionIds();
        List<TransactionDetails> result = new ArrayList();
        for(Integer txId: activeTransactionIds) {
            result.add(transactionRepository.getDetails(txId));
        }

        return ResponseEntity.ok(result);
    }

    @DeleteMapping("/transactions")
    public ResponseEntity<?> stop(@RequestBody TransactionStopRequest request) {
        Optional<ChargeBoxRecord> chargeBox = chargeBox(request.chargeBoxId());

        if (!chargeBox.isPresent()) {
            return ResponseEntity.unprocessableEntity().body("Charge box is missing [" + request.chargeBoxId() + "]");
        }

        versionedOperation(chargeBox.get(),
                (transport, endpointAddress) -> client15.remoteStopTransaction(request.asParams(transport, endpointAddress)),
                (transport, endpointAddress) -> client16.remoteStopTransaction(request.asParams(transport, endpointAddress)))
                .run();

        return ResponseEntity.noContent().build();
    }

    @DeleteMapping(value = "/transactions/{transactionId}", produces = {MediaType.APPLICATION_JSON_VALUE})
    public ResponseEntity<?> stop2(@PathVariable Integer transactionId, @RequestParam("chargeBoxId") String chargeBoxId) throws JsonProcessingException {
        TransactionStopRequest request = new TransactionStopRequest(transactionId, chargeBoxId);
        TransactionDetails transactionDetails;
        Optional<ChargeBoxRecord> chargeBox = chargeBox(request.chargeBoxId());

        if (!chargeBox.isPresent()) {
            return ResponseEntity.unprocessableEntity().body("Charge box is missing [" + request.chargeBoxId() + "]");
        }

        versionedOperation(chargeBox.get(),
                (transport, endpointAddress) -> client15.remoteStopTransaction(request.asParams(transport, endpointAddress)),
                (transport, endpointAddress) -> client16.remoteStopTransaction(request.asParams(transport, endpointAddress)))
                .run();

        try {
            transactionDetails = transactionService.pollStoppedTransaction(transactionId);
        } catch (TransactionStopException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
        return ResponseEntity.ok(objectMapper.writeValueAsString(transactionDetails));
    }

    @PutMapping("/transactions")
    public ResponseEntity<?> stop3(@RequestBody TransactionStopRequest request) {
        return stop(request);
    }

    private Optional<ChargeBoxRecord> chargeBox(String chargeBoxId) {
        Integer chargeBoxPk = chargePointRepository.getChargeBoxIdPkPair(List.of(chargeBoxId)).get(chargeBoxId);

        if (chargeBoxPk == null) {
            return Optional.empty();
        }

        ChargePoint.Details details = chargePointRepository.getDetails(chargeBoxPk);
        return Optional.of(details.getChargeBox());
    }

    @Service
    @AllArgsConstructor
    static class TransactionService {
        private final TransactionRepository transactionRepository;
        private final DSLContext dsl;

        int pollStartedTransactionId(String chargeBoxId, String idTag, int connectorId) throws TransactionBlockedException {
            Duration pollDuration = Duration.ofSeconds(5);
            Predicate<List<Integer>> nonEmptyFlag = Predicate.not(List::isEmpty);
            Function<List<Integer>, Optional<Integer>> mapResult = l -> Optional.ofNullable(l.get(0));
            Supplier<List<Integer>> source = () -> getActiveTransactionIds(chargeBoxId, connectorId);

            return timedPoll(source, pollDuration, nonEmptyFlag, mapResult)
                    .orElseThrow(() -> new TransactionBlockedException(chargeBoxId, idTag, pollDuration.toSeconds()));
        }

        private List<Integer> getActiveTransactionIds(String chargeBoxId, Integer connectorId) {
            return dsl.select(TRANSACTION.TRANSACTION_PK)
                    .from(TRANSACTION)
                    .join(CONNECTOR)
                    .on(TRANSACTION.CONNECTOR_PK.equal(CONNECTOR.CONNECTOR_PK))
                    .and(CONNECTOR.CHARGE_BOX_ID.equal(chargeBoxId)).and(CONNECTOR.CONNECTOR_ID.eq(connectorId))
                    .where(TRANSACTION.STOP_TIMESTAMP.isNull())
                    .fetch(TRANSACTION.TRANSACTION_PK);
        }

        TransactionDetails pollStoppedTransaction(Integer transactionId) throws TransactionStopException {
            Duration pollDuration = Duration.ofSeconds(5);
            Predicate<TransactionDetails> stopFlag = details -> details.getTransaction().getStopTimestampDT() != null;
            Function<TransactionDetails, Optional<TransactionDetails>> mapResult = Optional::ofNullable;
            Supplier<TransactionDetails> source = () -> transactionRepository.getDetails(transactionId);

            return timedPoll(source, pollDuration, stopFlag, mapResult)
                    .orElseThrow(() -> new TransactionStopException(transactionId, pollDuration.toSeconds()));
        }

        // repeatedly polls <E> from {@param source} for {@param pollDuration}
        // until it matches the {@param condition}
        // maps it to return type with {@param mapResult}
        private static <E, R> Optional<R> timedPoll(Supplier<E> source, Duration duration, Predicate<E> condition, Function<E, Optional<R>> mapResult) {
            Supplier<Long> nowMillis = () -> Instant.now().toEpochMilli();
            long startMillis = nowMillis.get();
            long currentMillis = startMillis;
            E result;

            while(currentMillis < duration.plusMillis(startMillis).toMillis()) {
                result = source.get();

                if (condition.test(result)) {
                    return mapResult.apply(result);
                }
                currentMillis = nowMillis.get();
            }

            return Optional.empty();
        }
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    static class TransactionBlockedException extends Exception {
        TransactionBlockedException(String chargeBoxId, String idTag, long timeout) {
            super("Transaction hasn't been started within [" + timeout + "] seconds interval for IdTag [" + idTag + "] with ChargingPoint [" + chargeBoxId + "]");
        }
    }

    @ResponseStatus(HttpStatus.CONFLICT)
    static class TransactionStopException extends Exception {
        public TransactionStopException(Integer transactionId, long timeout) {
            super("Transaction [" + transactionId + "] didn't finish within [" + timeout + "] seconds interval.");
        }
    }
}
