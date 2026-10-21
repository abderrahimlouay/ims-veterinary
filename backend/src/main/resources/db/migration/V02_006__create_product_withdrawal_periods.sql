-- Withdrawal periods per species (FR-PROD-005, BR-PROD-007): one record per (product, species); days >= 0;
-- NULL means "not specified", never zero.
CREATE TABLE product_withdrawal_periods (
    product_id BIGINT   NOT NULL,
    species_id INT      NOT NULL,
    meat_days  SMALLINT NULL,
    milk_days  SMALLINT NULL,
    eggs_days  SMALLINT NULL,
    CONSTRAINT pk_product_withdrawal_periods PRIMARY KEY (product_id, species_id),
    CONSTRAINT fk_product_withdrawal_periods_product_id FOREIGN KEY (product_id) REFERENCES products (id)
        ON DELETE CASCADE,
    CONSTRAINT fk_product_withdrawal_periods_species_id FOREIGN KEY (species_id) REFERENCES animal_species (id),
    CONSTRAINT ck_product_withdrawal_periods_meat_days CHECK (meat_days IS NULL OR meat_days >= 0),
    CONSTRAINT ck_product_withdrawal_periods_milk_days CHECK (milk_days IS NULL OR milk_days >= 0),
    CONSTRAINT ck_product_withdrawal_periods_eggs_days CHECK (eggs_days IS NULL OR eggs_days >= 0),
    INDEX idx_product_withdrawal_periods_species (species_id)
);
