package de.rwth.idsg.steve.web.api;

import de.rwth.idsg.steve.repository.TransactionRepository;
import de.rwth.idsg.steve.repository.dto.Transaction;
import de.rwth.idsg.steve.repository.dto.TransactionDetails;
import de.rwth.idsg.steve.service.ChargePointService16_Client;
import lombok.AllArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
@AllArgsConstructor
public class TransactionResource {

    private ChargePointService16_Client client16;

    private TransactionRepository transactionRepository;

    @PostMapping("/transactions")
    public ResponseEntity<Void> start(@RequestBody TransactionStartRequest request) {
        client16.remoteStartTransaction(request.asParams());
        return ResponseEntity.ok().build();
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
}
