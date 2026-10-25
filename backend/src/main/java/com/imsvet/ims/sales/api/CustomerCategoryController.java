package com.imsvet.ims.sales.api;

import com.imsvet.ims.sales.application.CustomerCategories;
import com.imsvet.ims.sales.application.CustomerViews.CategoryView;
import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** api.md §4.9 {@code /api/customer-categories}; permissions on the service; no delete (ADR-112). */
@RestController
@RequestMapping("/api/customer-categories")
class CustomerCategoryController {

    /** {@code version} is required by PUT (ADR-027). */
    record CategoryRequest(String name, BigDecimal discountPct, Long version) {

        CustomerCategories.CategoryInput input() {
            return new CustomerCategories.CategoryInput(name, discountPct);
        }
    }

    private final CustomerCategories categories;

    CustomerCategoryController(CustomerCategories categories) {
        this.categories = categories;
    }

    @GetMapping
    List<CategoryView> list() {
        return categories.list();
    }

    @PostMapping
    ResponseEntity<CategoryView> create(@RequestBody CategoryRequest body) {
        CategoryView created = categories.create(body.input());
        return ResponseEntity.created(URI.create("/api/customer-categories/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    CategoryView update(@PathVariable int id, @RequestBody CategoryRequest body) {
        return categories.update(id, body.input(), body.version() == null ? -1 : body.version());
    }
}
