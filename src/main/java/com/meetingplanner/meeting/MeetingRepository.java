package com.meetingplanner.meeting;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;

/** Lists, search and stats are built from {@link MeetingSpecifications}. */
public interface MeetingRepository extends JpaRepository<Meeting, Long>, JpaSpecificationExecutor<Meeting> {

    // The organizer is shown on every list row, so load it with the page instead of one query per row.
    @Override
    @EntityGraph(attributePaths = "createdBy")
    Page<Meeting> findAll(Specification<Meeting> spec, Pageable pageable);

    @Override
    @EntityGraph(attributePaths = "createdBy")
    List<Meeting> findAll(Specification<Meeting> spec);
}
