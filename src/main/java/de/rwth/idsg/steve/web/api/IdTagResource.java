package de.rwth.idsg.steve.web.api;

import de.rwth.idsg.steve.SteveException;
import de.rwth.idsg.steve.repository.OcppTagRepository;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;

@RestController
@RequestMapping("/api/id-tags")
@AllArgsConstructor
public class IdTagResource {

    private final OcppTagRepository ocppTagRepository;

    @GetMapping
    public ResponseEntity<List<String>> listAll() {
        return ResponseEntity.ok(ocppTagRepository.getIdTags());
    }

    @PostMapping("/{idTag}")
    public ResponseEntity add(@PathVariable("idTag") String newIdTag) {
        try {
            ocppTagRepository.addOcppTagList(Collections.singletonList(newIdTag));
        } catch (SteveException e) {
            return ResponseEntity.ok().build();
        }
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }
}
