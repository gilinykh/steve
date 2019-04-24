package de.rwth.idsg.steve.web.api;

import de.rwth.idsg.steve.repository.ChargePointRepository;
import de.rwth.idsg.steve.repository.dto.ChargePoint;
import de.rwth.idsg.steve.repository.dto.TransactionDetails;
import de.rwth.idsg.steve.web.dto.ChargePointQueryForm;
import lombok.AllArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/charge-points")
@AllArgsConstructor
public class ChargePointResource {

    private final ChargePointRepository chargePointRepository;

    @GetMapping
    public ResponseEntity<List<ChargePoint.Overview>> list() {
        List<ChargePoint.Overview> result = chargePointRepository.getOverview(new ChargePointQueryForm());
        return ResponseEntity.ok(result);
    }

    @PostMapping("/{chargeBoxId}/session/{idTag}")
    public ResponseEntity<Void> startSession(@PathVariable String chargeBoxId, @PathVariable String idTag) {
        // TODO: start session
        return ResponseEntity.ok().build();
    }

    @GetMapping("/{chargeBoxId}/session/{idTag}")
    public ResponseEntity<TransactionDetails> getSession(@PathVariable String chargeBoxId, @PathVariable String idTag) {
        // TODO: provide most recently finished transaction details
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{chargeBoxId}/session/{idTag}")
    public ResponseEntity<Void> endSession(@PathVariable String chargeBoxId, @PathVariable String idTag) {
        // TODO: find and stop most recently started transaction
        return ResponseEntity.noContent().build();
    }
}
