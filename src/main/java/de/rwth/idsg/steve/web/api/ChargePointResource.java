package de.rwth.idsg.steve.web.api;

import de.rwth.idsg.steve.repository.ChargePointRepository;
import de.rwth.idsg.steve.repository.dto.ChargePoint;
import de.rwth.idsg.steve.service.ChargePointHelperService;
import de.rwth.idsg.steve.web.dto.ChargePointQueryForm;
import de.rwth.idsg.steve.web.dto.OcppJsonStatus;
import lombok.AllArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/evse")
@AllArgsConstructor
public class ChargePointResource {

    private final ChargePointRepository chargePointRepository;
    private final ChargePointHelperService chargePointHelperService;

    @GetMapping
    public ResponseEntity<List<ChargePoint.Overview>> list() {
        List<ChargePoint.Overview> result = chargePointRepository.getOverview(new ChargePointQueryForm());
        return ResponseEntity.ok(result);
    }

    @GetMapping("/statuses")
    public ResponseEntity<List<OcppJsonStatus>> statuses() {
        List<OcppJsonStatus> result = chargePointHelperService.getOcppJsonStatus();
        return ResponseEntity.ok(result);
    }
}
