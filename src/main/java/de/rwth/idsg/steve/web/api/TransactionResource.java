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
import lombok.AllArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

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

    private static Runnable versionedOperation(OcppProtocol protocol, Function<OcppTransport, Integer> v15Operation, Function<OcppTransport, Integer> v16Operation) {
        switch(protocol.getVersion()) {
            case V_15:
                return () -> v15Operation.apply(protocol.getTransport());
            case V_16:
                return () -> v16Operation.apply(protocol.getTransport());
            default:
                throw new IllegalArgumentException("Unsupported OCPP protocol [" + protocol.getVersion() + protocol.getTransport() + "]");
        }
    }

    @PostMapping("/transactions")
    public ResponseEntity<?> start(@RequestBody TransactionStartRequest request) {
        Optional<OcppProtocol> protocol = chargeBoxOcppProtocol(request.chargeBoxId());

        if (!protocol.isPresent()) {
            return ResponseEntity.unprocessableEntity().body("Cannot evaluate OCPP protocol of charge box [" + request.chargeBoxId() + "]");
        }

        versionedOperation(protocol.get(),
                transport -> client15.remoteStartTransaction(request.asParams(transport)),
                transport -> client16.remoteStartTransaction(request.asParams(transport)))
                .run();

        return ResponseEntity.ok().build();
    }

    @PostMapping("/transactions/active")
    public ResponseEntity<?> started(@RequestBody TransactionStartRequest request) {
        int transactionId;
        Optional<OcppProtocol> protocol = chargeBoxOcppProtocol(request.chargeBoxId());

        if (!protocol.isPresent()) {
            return ResponseEntity.unprocessableEntity().body("Cannot evaluate OCPP protocol of charge box [" + request.chargeBoxId() + "]");
        }

        versionedOperation(protocol.get(),
                transport -> client15.remoteStartTransaction(request.asParams(transport)),
                transport -> client16.remoteStartTransaction(request.asParams(transport)))
                .run();

        try {
            transactionId = transactionService.startedTransactionId(request.chargeBoxId(), request.ocppIdTag());
        } catch (TransactionBlockedException e) {
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

    @DeleteMapping("/transactions")
    public ResponseEntity<?> stop(@RequestBody TransactionStopRequest request) {
        Optional<OcppProtocol> protocol = chargeBoxOcppProtocol(request.chargeBoxId());

        if (!protocol.isPresent()) {
            return ResponseEntity.unprocessableEntity().body("Cannot evaluate OCPP protocol of charge box [" + request.chargeBoxId() + "]");
        }

        versionedOperation(protocol.get(),
                transport -> client15.remoteStopTransaction(request.asParams(transport)),
                transport -> client16.remoteStopTransaction(request.asParams(transport)))
                .run();

        return ResponseEntity.noContent().build();
    }

    @DeleteMapping(value = "/transactions/{transactionId}", produces = {MediaType.APPLICATION_JSON_VALUE})
    public ResponseEntity<?> stop2(@PathVariable Integer transactionId, @RequestParam("chargeBoxId") String chargeBoxId) throws JsonProcessingException {
        TransactionStopRequest request = new TransactionStopRequest(transactionId, chargeBoxId, null);
        TransactionDetails transactionDetails;
        Optional<OcppProtocol> protocol = chargeBoxOcppProtocol(request.chargeBoxId());

        if (!protocol.isPresent()) {
            return ResponseEntity.unprocessableEntity().body("Cannot evaluate OCPP protocol of charge box [" + request.chargeBoxId() + "]");
        }

        versionedOperation(protocol.get(),
                transport -> client15.remoteStopTransaction(request.asParams(transport)),
                transport -> client16.remoteStopTransaction(request.asParams(transport)))
                .run();

        try {
            transactionDetails = transactionService.stoppedTransaction(transactionId);
        } catch (TransactionStopException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
        return ResponseEntity.ok(objectMapper.writeValueAsString(transactionDetails));
    }

    @PutMapping("/transactions")
    public ResponseEntity<?> stop3(@RequestBody TransactionStopRequest request) {
        return stop(request);
    }

    private Optional<OcppProtocol> chargeBoxOcppProtocol(String chargeBoxId) {
        Integer chargeBoxPk = chargePointRepository.getChargeBoxIdPkPair(List.of(chargeBoxId)).get(chargeBoxId);

        if (chargeBoxPk == null) {
            return Optional.empty();
        }

        ChargePoint.Details details = chargePointRepository.getDetails(chargeBoxPk);
        return Optional.of(OcppProtocol.fromCompositeValue(details.getChargeBox().getOcppProtocol()));
    }

    @Service
    @AllArgsConstructor
    static class TransactionService {
        private final TransactionRepository transactionRepository;

        int startedTransactionId(String chargeBoxId, String idTag) throws TransactionBlockedException {
            int txPollingTimeout = 5;
            Function<String, List<Integer>> provideTxIds = transactionRepository::getActiveTransactionIds;
            Predicate<List<Integer>> nonEmptyFlag = Predicate.not(List::isEmpty);
            Function<List<Integer>, Optional<Integer>> responder = l -> Optional.ofNullable(l.get(0));

            return tryTimeout(txPollingTimeout, chargeBoxId, provideTxIds, nonEmptyFlag, responder)
                    .orElseThrow(() -> new TransactionBlockedException(chargeBoxId, idTag, txPollingTimeout));
        }

        TransactionDetails stoppedTransaction(Integer transactionId) throws TransactionStopException {
            int txPollingTimeout = 5;
            Function<Integer, TransactionDetails> provideTx = transactionRepository::getDetails;
            Predicate<TransactionDetails> stopFlag = details -> details.getTransaction().getStopTimestampDT() != null;
            Function<TransactionDetails, Optional<TransactionDetails>> responder = Optional::ofNullable;

            return tryTimeout(txPollingTimeout, transactionId, provideTx, stopFlag, responder)
                    .orElseThrow(() -> new TransactionStopException(transactionId, txPollingTimeout));
        }

        // accepts param <P>
        // derives from it entity <E>
        // tests it to match a condition
        // responds with response <R>
        private <E, P, R> Optional<R> tryTimeout(int timeoutSec, P input, Function<P, E> provider, Predicate<E> condition, Function<E, Optional<R>> responder) {
            Supplier<Long> nowMillis = () -> Instant.now().toEpochMilli();
            long startMillis = nowMillis.get();
            long currentMillis = startMillis;
            E entity;

            while(currentMillis < startMillis + timeoutSec * 1000) {
                entity = provider.apply(input);

                if (condition.test(entity)) {
                    return responder.apply(entity);
                }
                currentMillis = nowMillis.get();
            }

            return Optional.empty();
        }
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    static class TransactionBlockedException extends Exception {
        TransactionBlockedException(String chargeBoxId, String idTag, int timeout) {
            super("Transaction hasn't been started within [" + timeout + "] seconds interval for IdTag [" + idTag + "] with ChargingPoint [" + chargeBoxId + "]");
        }
    }

    @ResponseStatus(HttpStatus.CONFLICT)
    static class TransactionStopException extends Exception {
        public TransactionStopException(Integer transactionId, int timeout) {
            super("Transaction [" + transactionId + "] didn't finish within [" + timeout + "] seconds interval.");
        }
    }
}
