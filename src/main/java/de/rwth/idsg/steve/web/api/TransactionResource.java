package de.rwth.idsg.steve.web.api;

import de.rwth.idsg.steve.repository.TransactionRepository;
import de.rwth.idsg.steve.repository.dto.Transaction;
import de.rwth.idsg.steve.repository.dto.TransactionDetails;
import de.rwth.idsg.steve.service.ChargePointService16_Client;
import de.rwth.idsg.steve.web.dto.ocpp.RemoteStartTransactionParams;
import lombok.AllArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

@RestController
@RequestMapping("/api")
@AllArgsConstructor
public class TransactionResource {

    private ChargePointService16_Client client16;

    private TransactionRepository transactionRepository;

    private final TransactionService transactionService;

    @PostMapping("/transactions")
    public ResponseEntity<Void> start(@RequestBody TransactionStartRequest request) {
        client16.remoteStartTransaction(request.asParams());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/transactions/active")
    public ResponseEntity<Integer> started(@RequestBody TransactionStartRequest request) {
        Integer transactionId = transactionService.startedTransactionId(request.asParams());
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

    @DeleteMapping("/transactions")
    public ResponseEntity<Void> stop(@RequestBody TransactionStopRequest request) {
        client16.remoteStopTransaction(request.asParams());
        return ResponseEntity.noContent().build();
    }

    @Service
    @AllArgsConstructor
    static class TransactionService {
        private final ChargePointService16_Client client16;
        private final TransactionRepository transactionRepository;

        public int startedTransactionId(RemoteStartTransactionParams params) {
            client16.remoteStartTransaction(params);

            String chargeBoxId = params.getChargePointSelectList().get(0).getChargeBoxId();
            int txPollingTimeout = 5;
            return getFirstActiveTransactionId(chargeBoxId, txPollingTimeout)
                    .orElseThrow(() -> new RuntimeException("Transaction hasn't been started within [" + txPollingTimeout + "] seconds interval with Charging Point [" + chargeBoxId + "]"));
        }

        private Optional<Integer> getFirstActiveTransactionId(String chargeBoxId, int timeoutSec) {
            Supplier<Long> nowMillis = () -> Instant.now().toEpochMilli();
            long startMillis = nowMillis.get();
            long currentMillis = startMillis;
            List<Integer> transactionIds;

            while(currentMillis < startMillis + timeoutSec * 1000) {
                transactionIds = transactionRepository.getActiveTransactionIds(chargeBoxId);
                if (transactionIds.size() > 0) {
                    return Optional.of(transactionIds.get(0));
                }
                currentMillis = nowMillis.get();
            }

            return Optional.empty();
        }
    }
}
