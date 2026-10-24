package com.meetingplanner.meeting;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface MeetingRepository extends JpaRepository<Meeting, Long> {

    /** Meetings the user created, plus meetings where the user's e-mail is on the participant list. */
    @Query("""
            select distinct m from Meeting m
            join fetch m.createdBy
            left join m.participants p
            where m.createdBy.id = :userId or p.email = :email
            order by m.startTime asc
            """)
    List<Meeting> findAccessibleBy(@Param("userId") Long userId, @Param("email") String email);
}
