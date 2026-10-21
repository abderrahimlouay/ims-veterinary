-- Reference values of the legacy schema, as given by the user (ADR-052), stored in English with the codes the
-- UI translates (ADR-054). Manufacturers and active ingredients start empty.
INSERT INTO animal_species (code, name) VALUES
    ('BOVINE', 'Bovine'), ('OVINE', 'Ovine'), ('CAPRINE', 'Caprine'), ('POULTRY', 'Poultry'),
    ('EQUINE', 'Equine'), ('CANINE', 'Canine'), ('FELINE', 'Feline');

INSERT INTO therapeutic_categories (code, name) VALUES
    ('ANTIBIOTIC', 'Antibiotic'), ('ANTIPARASITIC', 'Antiparasitic'), ('VACCINE', 'Vaccine'),
    ('ANTI_INFLAMMATORY', 'Anti-inflammatory'), ('VITAMIN_SUPPLEMENT', 'Vitamin / Supplement');

INSERT INTO dosage_forms (code, name) VALUES
    ('INJECTABLE', 'Injectable'), ('POWDER', 'Powder'), ('TABLET', 'Tablet'), ('SOLUTION', 'Solution'),
    ('POUR_ON', 'Pour-on');
