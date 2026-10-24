package com.imsvet.ims.numbering.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;

/** The counter of one document key in one business year (ADR-011, ADR-097). */
@Entity
@Table(name = "document_sequences")
public class DocumentSequence {

    @Embeddable
    public record Key(@Column(name = "doc_key", length = 30) String docKey, @Column(name = "year") short year)
            implements Serializable {
    }

    @EmbeddedId
    private Key id;

    @Column(name = "last_number", nullable = false)
    private int lastNumber;

    protected DocumentSequence() {
    }

    /** The next value, to be issued once the caller commits. */
    public int increment() {
        lastNumber++;
        return lastNumber;
    }

    public Key id() {
        return id;
    }

    public int lastNumber() {
        return lastNumber;
    }
}
