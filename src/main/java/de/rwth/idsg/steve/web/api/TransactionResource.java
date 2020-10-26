package de.rwth.idsg.steve.web.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import de.rwth.idsg.steve.ocpp.CommunicationTask;
import de.rwth.idsg.steve.ocpp.OcppProtocol;
import de.rwth.idsg.steve.ocpp.OcppTransport;
import de.rwth.idsg.steve.ocpp.RequestResult;
import de.rwth.idsg.steve.repository.ChargePointRepository;
import de.rwth.idsg.steve.repository.TaskStore;
import de.rwth.idsg.steve.repository.TransactionRepository;
import de.rwth.idsg.steve.repository.dto.ChargePoint;
import de.rwth.idsg.steve.repository.dto.ConnectorStatus;
import de.rwth.idsg.steve.repository.dto.Transaction;
import de.rwth.idsg.steve.repository.dto.TransactionDetails;
import de.rwth.idsg.steve.service.ChargePointService15_Client;
import de.rwth.idsg.steve.service.ChargePointService16_Client;
import de.rwth.idsg.steve.web.dto.ConnectorStatusForm;
import jooq.steve.db.tables.records.ChargeBoxRecord;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.joda.time.DateTime;
import org.joda.time.format.ISODateTimeFormat;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static java.util.stream.Collectors.toList;
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

    private final TaskService taskService;

    private final ObjectMapper objectMapper;

    private final ChargePointRepository chargePointRepository;

    public TransactionResource(@Qualifier("ChargePointService15_Client") ChargePointService15_Client client15,
                               @Qualifier("ChargePointService16_Client") ChargePointService16_Client client16,
                               TransactionRepository transactionRepository, TransactionService transactionService,
                               ChargePointRepository chargePointRepository, TaskService taskService) {
        this.client15 = client15;
        this.client16 = client16;
        this.transactionRepository = transactionRepository;
        this.transactionService = transactionService;
        this.chargePointRepository = chargePointRepository;
        this.taskService = taskService;
        this.objectMapper = new ObjectMapper();
        objectMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        objectMapper.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
    }

    private static int versionedOperation2(ChargeBoxRecord chargeBoxRecord,
                                               BiFunction<OcppTransport, String, Integer> v15Operation,
                                               BiFunction<OcppTransport, String, Integer> v16Operation) {
        OcppProtocol protocol = OcppProtocol.fromCompositeValue(chargeBoxRecord.getOcppProtocol());
        switch(protocol.getVersion()) {
            case V_15:
                return v15Operation.apply(protocol.getTransport(), chargeBoxRecord.getEndpointAddress());
            case V_16:
                return v16Operation.apply(protocol.getTransport(), chargeBoxRecord.getEndpointAddress());
            default:
                throw new IllegalArgumentException("Unsupported OCPP protocol [" + protocol.getVersion() + protocol.getTransport() + "]");
        }
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

        int taskId =
                versionedOperation2(chargeBox.get(),
                        (transport, endpointAddress) -> client15.remoteStartTransaction(request.asParams(transport, endpointAddress)),
                        (transport, endpointAddress) -> client16.remoteStartTransaction(request.asParams(transport, endpointAddress)));

        String result = taskService.pollTaskResult(taskId, request.chargeBoxId());

        return ResponseEntity.ok(result);
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

        Integer taskId = versionedOperation2(chargeBox.get(),
                (transport, endpointAddress) -> client15.remoteStopTransaction(request.asParams(transport, endpointAddress)),
                (transport, endpointAddress) -> client16.remoteStopTransaction(request.asParams(transport, endpointAddress)));

        String result = taskService.pollTaskResult(taskId, request.chargeBoxId());

        return ResponseEntity.ok(result);
    }

//    @DeleteMapping(value = "/transactions/{transactionId}", produces = {MediaType.APPLICATION_JSON_VALUE})
//    public ResponseEntity<?> stop2(@PathVariable Integer transactionId, @RequestParam("chargeBoxId") String chargeBoxId) throws JsonProcessingException {
//        TransactionStopRequest request = new TransactionStopRequest(transactionId, chargeBoxId);
//        TransactionDetails transactionDetails;
//        Optional<ChargeBoxRecord> chargeBox = chargeBox(request.chargeBoxId());
//
//        if (!chargeBox.isPresent()) {
//            return ResponseEntity.unprocessableEntity().body("Charge box is missing [" + request.chargeBoxId() + "]");
//        }
//
//        versionedOperation(chargeBox.get(),
//                (transport, endpointAddress) -> client15.remoteStopTransaction(request.asParams(transport, endpointAddress)),
//                (transport, endpointAddress) -> client16.remoteStopTransaction(request.asParams(transport, endpointAddress)))
//                .run();
//
//        try {
//            transactionDetails = transactionService.pollStoppedTransaction(transactionId);
//        } catch (TransactionStopException e) {
//            return ResponseEntity.badRequest().body(e.getMessage());
//        }
//        return ResponseEntity.ok(objectMapper.writeValueAsString(transactionDetails));
//    }

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

    @GetMapping("/charge-points/{chargeBoxId}/status")
    public ResponseEntity<?> chargePointStatus(@PathVariable("chargeBoxId") String chargeBoxId) {
        Optional<ChargeBoxRecord> chargeBox = chargeBox(chargeBoxId);

        if (!chargeBox.isPresent()) {
            return ResponseEntity.unprocessableEntity().body("Charge point is missing [" + chargeBoxId + "]");
        }

        ConnectorStatusForm query = new ConnectorStatusForm();
        query.setChargeBoxId(chargeBoxId);
        List<ConnectorStatus> connectorStatuses = chargePointRepository.getChargePointConnectorStatus(query);

        return ResponseEntity.ok(new ChargePointStatusRepresentation(chargeBoxId,
                chargeBox.get().getLastHeartbeatTimestamp(),
                connectorStatuses.stream()
                        .map(cs -> new ConnectorStatusRepresentation(cs.getConnectorId(), cs.getChargeBoxId(), cs.getStatus(), cs.getErrorCode()))
                        .collect(toList())));
    }

    @Service
    @AllArgsConstructor
    static class TaskService {
        private final TaskStore taskStore;

        public String pollTaskResult(int taskId, String chargeBoxId) {
            Duration pollDuration = Duration.ofSeconds(10);
            Predicate<CommunicationTask> taskFinishedCondition = CommunicationTask::isFinished;
            Predicate<CommunicationTask> taskErrorCheckCondition = t -> t.getErrorCount().get() > 0;
            Function<CommunicationTask, String> resultSuccessFunction = l -> ((RequestResult)l.getResultMap().get(chargeBoxId)).getResponse();
            Function<CommunicationTask, String> resultErrorFunction = l -> "Rejected";
            Supplier<CommunicationTask> source = () -> taskStore.get(taskId);

            log.debug("Polling task status for {}", pollDuration);
            String res =  timedPoll(source, pollDuration, taskFinishedCondition, taskErrorCheckCondition, resultSuccessFunction, resultErrorFunction);

            return  res;
        }

        // repeatedly polls <E> from {@param source} for {@param pollDuration}
        // until it matches the {@param condition}
        // maps it to return type with {@param mapResult}
        private static <E, R> R timedPoll(Supplier<E> source, Duration duration, Predicate<E> finishCondition, Predicate<E> errorCondition, Function<E, R> mapResult, Function<E, R> mapError) {
            Instant start = Instant.now();
            Instant current = start;
            Instant finish = start.plus(duration);
            E result;

            while(current.isBefore(finish)) {
                result = source.get();

                if (finishCondition.test(result)) {
                    log.debug("Polling success. Time elapsed: {}", Duration.between(start, current));
                    if (errorCondition.test(result)) {
                        return mapError.apply(result);
                    } else {
                        return mapResult.apply(result);
                    }
                }
                current = Instant.now();
            }

            log.debug("Polling failed. Time elapsed: {}", Duration.between(start, current));
            return null;
        }
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

            log.debug("Polling start-transaction result for {}", pollDuration);
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
            Duration pollDuration = Duration.ofSeconds(60);
            Predicate<TransactionDetails> stopFlag = details -> details.getTransaction().getStopTimestampDT() != null;
            Function<TransactionDetails, Optional<TransactionDetails>> mapResult = Optional::ofNullable;
            Supplier<TransactionDetails> source = () -> transactionRepository.getDetails(transactionId);

            log.debug("Polling stop-transaction result for {}", pollDuration);
            return timedPoll(source, pollDuration, stopFlag, mapResult)
                    .orElseThrow(() -> new TransactionStopException(transactionId, pollDuration.toSeconds()));
        }

        // repeatedly polls <E> from {@param source} for {@param pollDuration}
        // until it matches the {@param condition}
        // maps it to return type with {@param mapResult}
        private static <E, R> Optional<R> timedPoll(Supplier<E> source, Duration duration, Predicate<E> condition, Function<E, Optional<R>> mapResult) {
            Instant start = Instant.now();
            Instant current = start;
            Instant finish = start.plus(duration);
            E result;

            while(current.isBefore(finish)) {
                result = source.get();

                if (condition.test(result)) {
                    log.debug("Polling success. Time elapsed: {}", Duration.between(start, current));
                    return mapResult.apply(result);
                }
                current = Instant.now();
            }

            log.debug("Polling failed. Time elapsed: {}", Duration.between(start, current));
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

    @Getter
    static class ChargePointStatusRepresentation {
        private final String chargeBoxId;
        private final String lastHeartbeat;
        private final Collection<ConnectorStatusRepresentation> connectors;

        ChargePointStatusRepresentation(String chargeBoxId, DateTime lastHeartbeat, Collection<ConnectorStatusRepresentation> connectors) {
            this.chargeBoxId = chargeBoxId;
            this.lastHeartbeat = lastHeartbeat.toString(ISODateTimeFormat.dateTime());
            this.connectors = connectors;
        }
    }

    @Getter
    static class ConnectorStatusRepresentation {
        private final int connectorId;
        private final String chargeBoxId;
        private final String status;
        private final String errorCode;

        ConnectorStatusRepresentation(int connectorId, String chargeBoxId, String status, String errorCode) {
            this.connectorId = connectorId;
            this.chargeBoxId = chargeBoxId;
            this.status = status;
            this.errorCode = errorCode;
        }
    }
}
