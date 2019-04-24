package de.rwth.idsg.steve.web.api;

import de.rwth.idsg.steve.repository.ChargePointRepository;
import de.rwth.idsg.steve.repository.dto.ChargePoint;
import de.rwth.idsg.steve.web.dto.ChargePointQueryForm;
import lombok.AllArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
}
