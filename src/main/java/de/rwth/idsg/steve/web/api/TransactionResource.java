package de.rwth.idsg.steve.web.api;

import de.rwth.idsg.steve.repository.TransactionRepository;
import de.rwth.idsg.steve.repository.dto.Transaction;
import de.rwth.idsg.steve.service.ChargePointService16_Client;
import lombok.AllArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/transactions")
@AllArgsConstructor
public class TransactionResource {

    private ChargePointService16_Client client16;

    private TransactionRepository transactionRepository;

    @PostMapping
    public ResponseEntity<Void> start(@RequestBody TransactionStartRequest request) {
        client16.remoteStartTransaction(request.asParams());
        return ResponseEntity.ok().build();
    }

    @GetMapping("/{idTag}")
    public ResponseEntity<List<Transaction>> userTransactions(@RequestParam("idTag") String idTag) {
        TransactionListRequest request = new TransactionListRequest(idTag);
        return ResponseEntity.ok(transactionRepository.getTransactions(request.asQuery()));
    }

    @DeleteMapping
    public ResponseEntity<Void> stop(@RequestBody TransactionStopRequest request) {
        client16.remoteStopTransaction(request.asParams());
        return ResponseEntity.noContent().build();
    }
}
