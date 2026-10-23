package com.imsvet.ims.inventory.infrastructure;

import com.imsvet.ims.inventory.domain.ProposalStatus;
import com.imsvet.ims.inventory.domain.ReorderProposal;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

/** Reorder proposals; never deleted. */
public interface ReorderProposalRepository extends Repository<ReorderProposal, Long> {

    ReorderProposal save(ReorderProposal proposal);

    void flush();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM ReorderProposal p WHERE p.id = :id")
    Optional<ReorderProposal> findByIdForUpdate(long id);

    List<ReorderProposal> findByStatusAndProductIdIn(ProposalStatus status, Collection<Long> productIds);

    List<ReorderProposal> findBySuppressedTrue();

    /** {@code status} optional; newest first. */
    @Query("SELECT p FROM ReorderProposal p WHERE (:status IS NULL OR p.status = :status) ORDER BY p.id DESC")
    Page<ReorderProposal> search(ProposalStatus status, Pageable pageable);
}
