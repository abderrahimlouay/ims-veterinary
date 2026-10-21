package com.imsvet.ims.catalog.api;

import com.imsvet.ims.catalog.application.ReferenceItemView;
import com.imsvet.ims.catalog.application.ReferenceLists;
import com.imsvet.ims.catalog.domain.ReferenceList;
import com.imsvet.ims.common.error.BusinessException;
import com.imsvet.ims.common.error.ErrorCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * api.md §4.3 reference lists; permissions on the service ({@code product.read}, {@code reference.manage}).
 */
@RestController
@RequestMapping("/api/{list:categories|manufacturers|dosage-forms|active-ingredients|animal-species}")
class ReferenceListController {

    /** {@code country} is used by manufacturers only. */
    record ReferenceEntryRequest(@NotBlank String name, String country) {
    }

    private final ReferenceLists lists;

    ReferenceListController(ReferenceLists lists) {
        this.lists = lists;
    }

    @GetMapping
    List<ReferenceItemView> list(@PathVariable String list) {
        return lists.list(of(list));
    }

    @PostMapping
    ResponseEntity<ReferenceItemView> create(@PathVariable String list, @Valid @RequestBody ReferenceEntryRequest body) {
        ReferenceItemView created = lists.create(of(list), body.name(), body.country());
        return ResponseEntity.created(URI.create("/api/" + list + "/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    ReferenceItemView rename(@PathVariable String list, @PathVariable int id,
            @Valid @RequestBody ReferenceEntryRequest body) {
        return lists.rename(of(list), id, body.name(), body.country());
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(@PathVariable String list, @PathVariable int id) {
        lists.delete(of(list), id);
        return ResponseEntity.noContent().build();
    }

    private static ReferenceList of(String path) {
        return ReferenceList.fromPath(path).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Unknown list."));
    }
}
