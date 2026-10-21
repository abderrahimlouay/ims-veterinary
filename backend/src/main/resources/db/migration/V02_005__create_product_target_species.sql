-- Target species of a product (ADR-047, SD-106): with the withdrawal-period species they drive the species
-- filter of the product search (FR-SALE-002). The species foreign key makes a used species undeletable (ADR-053).
CREATE TABLE product_target_species (
    product_id BIGINT NOT NULL,
    species_id INT    NOT NULL,
    CONSTRAINT pk_product_target_species PRIMARY KEY (product_id, species_id),
    CONSTRAINT fk_product_target_species_product_id FOREIGN KEY (product_id) REFERENCES products (id)
        ON DELETE CASCADE,
    CONSTRAINT fk_product_target_species_species_id FOREIGN KEY (species_id) REFERENCES animal_species (id),
    INDEX idx_product_target_species_species (species_id)
);
